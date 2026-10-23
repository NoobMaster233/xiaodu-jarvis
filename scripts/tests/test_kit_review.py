"""审查中发现的真实封装缺陷；所有ADB/RPC均为隔离模拟。"""
import asyncio
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock,patch

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools'),str(ROOT/'system/codex-bridge')]
import device
import bridge
from artifacts import validate_build,expected_sources
from check_public import public_files
from configuration import local_bridge_url
from prepare import cached_match,digest
import codex_info


class FakeDevice(device.AdbDevice):
    def __init__(self):
        self.events=[];self.module=False;self.fail_unpack=False;self.bad_activity=False;self.bad_state=False;self.samples=0
        self.module_appears_at_lock=False;self.freeze_samples=False
        self.factory={'matches_tested_baseline':True}
    def prerequisites(self):self.events.append('prerequisites')
    def run(self,args,**kw):self.events.append(tuple(args))
    def shell(self,command):
        self.events.append(command)
        if command.startswith('am start'):
            return 'Error type 3\nError: Activity class does not exist' if self.bad_activity else 'Status: ok\nActivity: local.xiaodu.voice/.CallActivity'
        if command.startswith('appops get'):return 'WRITE_SETTINGS: allow'
        return ''
    def root(self,command):
        self.events.append(command)
        if command.startswith('mkdir /data/adb/xiaodu-jarvis-install.lock') and self.module_appears_at_lock:self.module=True
        if command=='test -d '+device.MODULE+' && echo exists || echo absent':return 'exists' if self.module else 'absent'
        if command.startswith('stat -c'):return '10001:10001'
        if command.startswith(device.BB+' sha256sum'):
            value=json.dumps({'bridge_url':'http://bridge.example:8766'}).encode()
            return hashlib.sha256(value).hexdigest()+'  connection.json'
        if 'unzip -q' in command and self.fail_unpack:
            self.fail_unpack=False;raise subprocess.CalledProcessError(1,'mock-unzip')
        if command.startswith('test ! -e '+device.MODULE):self.module=True
        return ''
    def state(self):
        if not self.freeze_samples:self.samples+=4000
        return {'state':'failed','wake_ready':False} if self.bad_state else {'state':'wake_ready','wake_ready':True,'samples':self.samples,'realtime':False}


class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.d=FakeDevice()
        self.patches=[patch.object(device,'AdbDevice',return_value=self.d),
                      patch.object(device,'validate_build',return_value=({'sha256':'checked'},Path('/tmp/synthetic.apk'))),
                      patch.object(device,'package',return_value=Path('/tmp/synthetic-module.zip'))]
        for p in self.patches:p.start();self.addCleanup(p.stop)
    def install(self,**kw):
        with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
            return device.install('MOCK','http://bridge.example:8766',**kw)
    def test_special_permission_uses_appops_and_readback(self):
        value=self.install()
        self.assertTrue(value['wake_ready'])
        self.assertIn('appops set local.xiaodu.screencare WRITE_SETTINGS allow',self.d.events)
        self.assertIn('appops get local.xiaodu.screencare WRITE_SETTINGS',self.d.events)
        self.assertNotIn('pm grant local.xiaodu.screencare android.permission.WRITE_SETTINGS',self.d.events)
    def test_unpack_failure_cleans_only_staging_then_retry_succeeds(self):
        self.d.fail_unpack=True
        with self.assertRaises(subprocess.CalledProcessError):self.install()
        self.assertFalse(self.d.module)
        self.assertTrue(any(isinstance(e,str) and e.startswith('rm -rf /data/adb/xiaodu-jarvis-stage-') for e in self.d.events))
        self.assertTrue(self.install()['installed'])
    def test_error_type_three_with_zero_exit_is_not_success(self):
        self.d.bad_activity=True
        with self.assertRaisesRegex(ValueError,'Activity'):self.install()
        self.assertFalse(self.d.module)
    def test_failed_health_is_reported_and_does_not_delete_installed_module(self):
        self.d.bad_state=True
        with self.assertRaisesRegex(ValueError,'尚未就绪'):self.install()
        self.assertTrue(self.d.module)
        self.assertNotIn('rm -rf '+device.MODULE,self.d.events)
    def test_existing_module_is_not_overwritten(self):
        self.d.module=True
        with self.assertRaisesRegex(ValueError,'已有模块'):self.install()
        self.assertFalse(any(isinstance(e,tuple) and e[0]=='install' for e in self.d.events))
    def test_new_module_is_rechecked_after_acquiring_lock(self):
        self.d.module_appears_at_lock=True
        with self.assertRaisesRegex(ValueError,'取得安装锁'):self.install()
        self.assertFalse(any(isinstance(e,tuple) and e[0]=='install' for e in self.d.events))
    def test_ready_without_advancing_capture_is_not_success(self):
        self.d.freeze_samples=True
        with patch.object(device.time,'monotonic',side_effect=[0,100]):
            with self.assertRaisesRegex(ValueError,'尚未就绪'):self.install()
    def test_without_clock_does_not_install_start_or_grant_clock(self):
        self.install(include_clock=False)
        self.assertEqual(len([e for e in self.d.events if isinstance(e,tuple) and e[0]=='install']),1)
        self.assertFalse(any('screencare' in str(e) or 'appops' in str(e) for e in self.d.events))
    def test_unverified_vendor_stops_before_mutation(self):
        self.d.factory={'matches_tested_baseline':False}
        with self.assertRaisesRegex(ValueError,'DCS'):self.install()
        self.assertEqual(self.d.events,['prerequisites'])


class BuildProofTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.r=Path(self.temp.name)
        for tree in ('system/voice-client','system/voice-timing'):
            shutil.copytree(ROOT/tree,self.r/tree)
        script=self.r/'scripts/build_voice_client.py';script.parent.mkdir();shutil.copyfile(ROOT/'scripts/build_voice_client.py',script)
        artifact=self.r/'artifacts/voice-client/test.apk';artifact.parent.mkdir(parents=True);artifact.write_bytes(b'build-fixture')
        proof={'apk':str(artifact.relative_to(self.r)),'sha256':digest(artifact),'sources':expected_sources(self.r,'voice-client'),
               'build_script_sha256':digest(script)}
        (artifact.parent/'build-proof.json').write_text(json.dumps(proof))
    def test_matching_build_is_accepted(self):validate_build('voice-client',self.r)
    def test_changed_manifest_rejects_old_apk(self):
        p=self.r/'system/voice-client/AndroidManifest.xml';p.write_text(p.read_text()+'\n<!-- source changed -->\n')
        with self.assertRaisesRegex(ValueError,'源码'):validate_build('voice-client',self.r)
    def test_added_source_rejects_old_apk(self):
        (self.r/'system/voice-client/src/Added.java').write_text('class Added {}')
        with self.assertRaisesRegex(ValueError,'源码'):validate_build('voice-client',self.r)
    def test_changed_build_script_rejects_old_artifact(self):
        (self.r/'scripts/build_voice_client.py').write_text('# changed compiler flags')
        with self.assertRaisesRegex(ValueError,'构建脚本'):validate_build('voice-client',self.r)
    def test_top_level_symlink_is_never_exported(self):
        private=self.r/'private.json';private.write_text('{"custom_secret":"not-public"}')
        public=self.r/'public';public.mkdir();(public/'config.example.json').symlink_to(private)
        with self.assertRaises(ValueError):public_files(public)
    def test_public_cache_layout_can_be_reused_without_download(self):
        item=self.r/'.cache/runtime/lib/test.so';item.parent.mkdir(parents=True);item.write_bytes(b'verified')
        self.assertEqual(cached_match(self.r,['.cache/runtime/lib/test.so'],digest(item)),item)


class BridgeReadyTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        with patch.object(bridge,'STATE',Path(self.temp.name)):self.b=bridge.Bridge()
        self.b.rpc.ready.set();self.b.rpc.connection_error=None
    async def health(self):
        response=await self.b.http(SimpleNamespace(path='/health',method='GET'))
        return json.loads(response.body)
    async def test_invalid_main_never_reports_ready(self):
        async def fail(*args):raise bridge.RPCError('thread not found')
        self.b.resume=fail
        with contextlib.redirect_stdout(io.StringIO()):await self.b.event('bridge/connected',{})
        value=await self.health()
        self.assertTrue(value['rpc_ready']);self.assertFalse(value['ready']);self.assertFalse(value['main_ready'])
        self.assertEqual(value['main_error'],'main_resume_failed')
    async def test_ready_requires_both_rpc_and_main(self):
        async def good(*args):return {}
        self.b.resume=good
        with contextlib.redirect_stdout(io.StringIO()):await self.b.event('bridge/connected',{})
        self.assertTrue((await self.health())['ready'])
        self.b.rpc.ready.clear()
        self.assertFalse((await self.health())['ready']);self.assertFalse((await self.health())['main_ready'])
    async def test_thread_discovery_uses_only_readonly_list_and_paginates(self):
        calls=[]
        class RPC:
            def __init__(self,*a,**k):self.ready=asyncio.Event()
            async def run(self):self.ready.set();await asyncio.Future()
            async def call(self,method,params):
                calls.append(method)
                return {'data':[{'id':'example','name':'main','cwd':'/tmp','preview':'excluded'}],
                        'nextCursor':'page-two' if len(calls)==1 else None}
        with patch.object(codex_info,'Codex',RPC):result=await codex_info.list_threads()
        self.assertEqual(calls,['thread/list','thread/list']);self.assertEqual(len(result['threads']),2)
        self.assertNotIn('preview',result['threads'][0])


class CliAddressTest(unittest.TestCase):
    def execute(self,args,environment,config=None):
        with tempfile.TemporaryDirectory() as folder:
            env={'HOME':folder,**environment}
            if config:
                p=Path(folder)/'.config/xiaodu-jarvis/config.json';p.parent.mkdir(parents=True);p.write_text(json.dumps(config))
            connection=Mock();connection.getresponse.return_value.status=200;connection.getresponse.return_value.read.return_value=b'{"ready":true}'
            with patch.dict(os.environ,env,clear=True),patch.object(sys,'argv',['codex-conversations',*args]),\
                 patch('http.client.HTTPConnection',return_value=connection) as factory,contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as end:runpy.run_path(str(ROOT/'client/codex-conversations'),run_name='__main__')
                self.assertEqual(end.exception.code,0)
                return factory.call_args.args
    def test_explicit_url_wins(self):
        self.assertEqual(self.execute(['--url','http://bridge.example:9876','health'],{'CODEX_BRIDGE_URL':'http://other.example:9999'}),('bridge.example',9876))
    def test_configured_port_is_used_without_code_edit(self):
        self.assertEqual(self.execute(['health'],{}, {'listen_host':'0.0.0.0','listen_port':9876}),('127.0.0.1',9876))
    def test_timing_uses_configured_port_and_ipv6(self):
        self.assertEqual(local_bridge_url({'listen_host':'0.0.0.0','listen_port':9876}),'http://127.0.0.1:9876')
        self.assertEqual(local_bridge_url({'listen_host':'::','listen_port':9876}),'http://[::1]:9876')


if __name__=='__main__':unittest.main()

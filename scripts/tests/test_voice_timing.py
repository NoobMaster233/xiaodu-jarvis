"""Content-free summaries and lifecycle integration, without any live service."""
import asyncio
import json
import os
import subprocess
import sys
import tempfile
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'system/codex-bridge'))
from milestones import Milestones
from realtime import Realtime


class CollectorTest(unittest.TestCase):
    def test_java_production_collector(self):
        with tempfile.TemporaryDirectory() as folder:
            subprocess.run(['javac','--release','8','-d',folder,
                str(ROOT/'system/voice-timing/src/local/xiaodu/timing/Milestones.java'),
                str(Path(__file__).with_name('VoiceMilestonesTest.java'))], check=True)
            subprocess.run(['java','-cp',folder,'VoiceMilestonesTest',folder], check=True)

    def test_disabled_does_not_read_clocks_or_write(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'last.json'
            def forbidden(): raise AssertionError('disabled clock called')
            t=Milestones(str(uuid.uuid4()),path=path,enabled=False,clock=forbidden,wall=forbidden)
            t.mark('offer_enter');t.finish('ended')
            self.assertFalse(path.exists())
            self.assertNotIn('milestones_ns',t.snapshot())

    def test_first_only_sealed_and_private(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'last.json';ns=iter(range(100,200))
            t=Milestones(str(uuid.uuid4()),path=path,enabled=True,clock=lambda:next(ns),wall=lambda:42)
            t.mark('offer_enter');first=t.points['offer_enter'];t.mark('offer_enter')
            self.assertEqual(t.points['offer_enter'],first);self.assertFalse(path.exists())
            with self.assertRaises(ValueError): t.mark('SECRET_TEXT')
            t.finish('failed');original=path.read_bytes();t.mark('greeting_ack');t.finish('ended')
            self.assertEqual(path.read_bytes(),original)
            self.assertEqual(path.stat().st_mode&0o777,0o600)
            self.assertEqual(json.loads(original)['outcome'],'failed')

    def test_history_is_bounded_and_content_free(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'last.json'
            for _ in range(24):
                t=Milestones(str(uuid.uuid4()),path=path,enabled=True);t.mark('offer_enter');t.finish('ended')
            records=list((Path(folder)/'timings').glob('*.json'))
            self.assertEqual(len(records),20)
            self.assertTrue(all(p.stat().st_mode&0o777==0o600 for p in records))
            self.assertFalse(list(Path(folder).rglob('*.tmp')))

    def test_export_discards_text_and_credentials(self):
        sys.path.insert(0,str(ROOT/'scripts'))
        from voice_timing import sanitize
        item={'call_id':str(uuid.uuid4()),'clock':'android_elapsed_realtime_ns','enabled':True,
              'audio':'SECRET_AUDIO','text':'SECRET_TRANSCRIPT','token':'SECRET_TOKEN',
              'milestones_ns':{'service_start':42,'user_text':'SECRET_TEXT'},
              'durations_ms':{'offer_roundtrip':1.2},'outcome':'ended'}
        self.assertNotIn('SECRET',json.dumps(sanitize(item)))

    def test_policy_can_toggle_for_new_calls_without_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch('milestones.LAST',Path(folder)/'last.json'):
                from milestones import enabled_by_policy
                self.assertTrue(enabled_by_policy())
                flag=Path(folder)/'voice-timing.off';flag.touch()
                self.assertFalse(enabled_by_policy())
                flag.unlink();self.assertTrue(enabled_by_policy())

    def test_ready_checkpoint_survives_without_call_end(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'last.json'
            t=Milestones(str(uuid.uuid4()),path=path,enabled=True)
            t.mark('ready_received');t.mark('greeting_ack');t.checkpoint()
            record=json.loads(path.read_text())
            self.assertFalse(record['sealed']);self.assertEqual(record['checkpoint'],'ready')
            self.assertNotIn('outcome',record)
            self.assertIn('greeting_ack',record['milestones_ns'])
            self.assertTrue((path.parent/'timings'/(t.call_id+'.json')).exists())

    def test_checkpoint_race_cannot_overwrite_terminal_record(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'last.json'
            for _ in range(20):
                t=Milestones(str(uuid.uuid4()),path=path,enabled=True)
                t.mark('offer_enter')
                with ThreadPoolExecutor(max_workers=2) as pool:
                    a=pool.submit(t.checkpoint);b=pool.submit(t.finish,'ended')
                    a.result();b.result()
                record=json.loads(path.read_text())
                self.assertTrue(record['sealed']);self.assertEqual(record['outcome'],'ended')


class LifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.folder=tempfile.TemporaryDirectory();self.addCleanup(self.folder.cleanup)
        self.path=Path(self.folder.name)/'last.json';self.cid=str(uuid.uuid4());self.calls=[]
        class Bridge:
            async def silence(inner): pass
            async def resume(inner,*args): pass
            async def call(inner,method,params):
                self.calls.append(method)
                if method=='thread/realtime/start':
                    await self.realtime.event('thread/realtime/sdp',{'threadId':'main','sdp':'v=0\nSECRET_SDP_AND_CREDENTIALS'})
                return {}
        self.bridge=Bridge();self.bridge.rpc=self.bridge
        self.realtime=Realtime(self.bridge,'main')
        p=patch('realtime.Milestones',side_effect=lambda cid:Milestones(cid,path=self.path,enabled=True))
        p.start();self.addCleanup(p.stop)

    async def test_success_and_end_summary(self):
        await self.realtime.start(self.cid,'v=0\nSECRET_OFFER')
        await self.realtime.ready(self.cid)
        self.assertFalse(json.loads(self.path.read_text())['sealed'])
        await self.realtime.end(self.cid)
        record=json.loads(self.path.read_text());points=record['milestones_ns']
        for p in ['offer_enter','silence_begin','silence_end','resume_begin','resume_end',
                  'realtime_start_begin','realtime_start_ack','sdp_arrived','ready_received','greeting_begin','greeting_ack','call_end']:
            self.assertIn(p,points)
        self.assertNotIn('SECRET',self.path.read_text());self.assertNotIn('你好',self.path.read_text())
        self.assertEqual(record['call_id'],self.cid);self.assertEqual(record['outcome'],'ended')
        self.assertTrue(all(v>=0 for v in record['durations_ms'].values()))

    async def test_failure_summary_does_not_copy_error_body(self):
        async def fail(*args): raise RuntimeError('SECRET_USER_AND_TOKEN')
        self.bridge.resume=fail
        with self.assertRaises(RuntimeError):await self.realtime.start(self.cid,'v=0\nSECRET_SDP')
        value=self.path.read_text();self.assertNotIn('SECRET',value)
        self.assertEqual(json.loads(value)['outcome'],'failed')

    async def test_remote_error_sanitized(self):
        await self.realtime.start(self.cid,'v=0\nSECRET')
        await self.realtime.event('thread/realtime/error',{'threadId':'main','message':'SECRET_TRANSCRIPT_TOKEN'})
        self.assertNotIn('SECRET',self.path.read_text())
        self.assertEqual(json.loads(self.path.read_text())['outcome'],'failed')

    async def test_wrong_call_id_does_not_finish_current(self):
        await self.realtime.start(self.cid,'v=0\nSECRET')
        await self.realtime.end(str(uuid.uuid4()))
        self.assertFalse(self.path.exists());self.assertTrue(self.realtime.active())
        await self.realtime.end(self.cid)

    async def test_shutdown_saves_metadata_without_stop_rpc(self):
        await self.realtime.start(self.cid,'v=0\nSECRET')
        await self.realtime.ready(self.cid)
        before=list(self.calls)
        self.realtime.shutdown_checkpoint()
        record=json.loads(self.path.read_text())
        self.assertFalse(record['sealed']);self.assertEqual(record['checkpoint'],'shutdown')
        self.assertIn('bridge_shutdown',record['milestones_ns'])
        self.assertEqual(before,self.calls)
        self.assertNotIn('SECRET',self.path.read_text())

    async def test_storage_failure_does_not_change_lifecycle(self):
        await self.realtime.start(self.cid,'v=0\nSECRET')
        self.realtime.timing.path=Path(self.folder.name)/'missing'/'last.json'
        result=await self.realtime.end(self.cid)
        self.assertFalse(result['active']);self.assertEqual(result['state'],'ended')

    async def test_disabled_preserves_call_lifecycle_without_writes(self):
        with patch('realtime.Milestones',side_effect=lambda cid:Milestones(cid,path=self.path,enabled=False)):
            await self.realtime.start(self.cid,'v=0\nSECRET')
            await self.realtime.ready(self.cid)
            self.assertEqual(self.realtime.status()['state'],'connected')
            result=await self.realtime.end(self.cid)
        self.assertEqual(result['state'],'ended')
        self.assertIn('thread/realtime/appendSpeech',self.calls)
        self.assertIn('thread/realtime/stop',self.calls)
        self.assertFalse(self.path.exists())
        self.assertFalse((self.path.parent/'timings').exists())

    async def test_cancellation_is_finalized_without_sensitive_data(self):
        reached=asyncio.Event()
        async def waiting(method,params):
            if method=='thread/realtime/start':
                reached.set();await asyncio.Future()
            return {}
        self.bridge.call=waiting
        task=asyncio.create_task(self.realtime.start(self.cid,'v=0\nSECRET'))
        await reached.wait();task.cancel()
        with self.assertRaises(asyncio.CancelledError):await task
        value=self.path.read_text();self.assertNotIn('SECRET',value)
        self.assertEqual(json.loads(value)['outcome'],'failed')


if __name__=='__main__': unittest.main()

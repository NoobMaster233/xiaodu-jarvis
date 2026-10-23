#!/usr/bin/env python3
"""首次安装辅助；必须指定 adb serial，默认只展示计划，--apply 才执行。"""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'system/codex-bridge'))
from configuration import validate_url

MODULE = '/data/adb/modules/xiaodu_speech_http'
STAGE = '/data/local/tmp/xiaodu-jarvis-install'


def plan(bridge_url):
    return {'model':'XDH-29-B1', 'bridge_url':validate_url(bridge_url),
            'install':['语音 APK','待机时钟 APK','采音与局域网控制模块'],
            'first_install_only':True, 'reboot':False}


def install(serial, bridge_url):
    expected = plan(bridge_url)
    adb = ['adb','-s',serial]
    def run(args, **kwargs): return subprocess.run(adb+args, check=True, **kwargs)
    def shell(command): return run(['shell',command], capture_output=True, text=True).stdout.strip()
    def root(command): return shell('su -c '+shlex.quote(command))
    if shell('getprop ro.product.model') != expected['model']: raise ValueError('仅支持 XDH-29-B1')
    if shell('getprop ro.build.version.sdk') != '27': raise ValueError('仅核验过 Android API 27')
    if 'armeabi-v7a' not in shell('getprop ro.product.cpu.abilist'): raise ValueError('缺少 ARMv7 ABI')
    if root('id -u') != '0': raise ValueError('请先完成 root；本工具不刷写启动分区')
    if root('test -x /data/adb/magisk/busybox && echo ready') != 'ready': raise ValueError('需要 Magisk BusyBox')
    if root('test -d '+MODULE+' && echo exists || echo absent') != 'absent':
        raise ValueError('发现已有模块：请按文档手工升级，不能覆盖正在运行的语音服务')
    voice = json.loads((ROOT/'artifacts/voice-client/build-proof.json').read_text())
    clock = json.loads((ROOT/'artifacts/screen-care/build-proof.json').read_text())
    from prepare import digest
    for proof in (voice, clock):
        if digest(ROOT/proof['apk']) != proof['sha256']: raise ValueError('APK 校验失败')
    from package_module import package
    module = package()
    shell('mkdir -p '+STAGE)
    for proof in (voice, clock): run(['install','-r',str(ROOT/proof['apk'])])
    shell('pm grant local.xiaodu.voice android.permission.RECORD_AUDIO')
    shell('pm grant local.xiaodu.screencare android.permission.WRITE_SECURE_SETTINGS')
    shell('pm grant local.xiaodu.screencare android.permission.WRITE_SETTINGS')
    # Write app-owned connection settings via a staging file; never touch account data.
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder)/'connection.json'
        path.write_text(json.dumps({'bridge_url':expected['bridge_url']}))
        run(['push',str(path),STAGE+'/connection.json'])
    shell('am start -n local.xiaodu.voice/.CallActivity --ez preview true')
    data = '/data/data/local.xiaodu.voice'
    root('mkdir -p '+data+'/files; cp '+STAGE+'/connection.json '+data+'/files/connection.json; '
         'chown $(stat -c %u:%g '+data+') '+data+'/files '+data+'/files/connection.json; '
         'chmod 700 '+data+'/files; chmod 600 '+data+'/files/connection.json; restorecon -R '+data+'/files')
    run(['push',str(module),STAGE+'/module.zip'])
    root('mkdir -p '+MODULE+' && /data/adb/magisk/busybox unzip -q '+STAGE+'/module.zip -d '+MODULE+
         ' && chmod 700 '+MODULE+' '+MODULE+'/watch.sh '+MODULE+'/service.sh '+MODULE+'/frida-inject')
    root('sh '+MODULE+'/service.sh')
    shell('am start -n local.xiaodu.screencare/.ClockActivity')
    shell('rm -f '+STAGE+'/connection.json '+STAGE+'/module.zip')
    print(json.dumps({'installed':True,'automatic_test_call':False}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--bridge-url', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    try:
        if args.apply: install(args.serial,args.bridge_url)
        else: print(json.dumps(plan(args.bridge_url),ensure_ascii=False,indent=2))
    except (OSError,ValueError,subprocess.CalledProcessError) as exc:
        print('安装未完成：'+str(exc), file=sys.stderr);raise SystemExit(1)

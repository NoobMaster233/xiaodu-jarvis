#!/usr/bin/env python3
"""首次安装辅助；指定 serial，默认只展示计划，--apply 安装，--check 只读检查。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
import uuid

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'system/codex-bridge'))
from configuration import validate_url
from artifacts import validate_build
from package_module import package

MODULE='/data/adb/modules/xiaodu_speech_http'
BB='/data/adb/magisk/busybox'


class AdbDevice:
    def __init__(self,serial):self.prefix=['adb','-s',serial]
    def run(self,args,**kwargs):
        return subprocess.run(self.prefix+args,check=True,timeout=120,**kwargs)
    def shell(self,command):
        return self.run(['shell',command],capture_output=True,text=True).stdout.strip()
    def root(self,command):return self.shell('su -c '+shlex.quote(command))
    def activity(self,component,extras=''):
        reply=self.shell('am start -W -n '+component+extras)
        if re.search(r'(?im)^\s*(?:Error|Exception|SecurityException|Status:\s*(?!ok\b)\w+)',reply) or not re.search(r'(?m)^Status:\s*ok\s*$',reply):
            raise ValueError('Activity 未确认启动：'+component)
    def prerequisites(self):
        if self.shell('getprop ro.product.model')!='XDH-29-B1':raise ValueError('仅支持 XDH-29-B1')
        if self.shell('getprop ro.build.version.sdk')!='27':raise ValueError('仅核验过 Android API 27')
        if 'armeabi-v7a' not in self.shell('getprop ro.product.cpu.abilist'):raise ValueError('缺少 ARMv7 ABI')
        if self.root('id -u')!='0':raise ValueError('请先完成 root；本工具不刷写启动分区')
        if self.root('test -x '+BB+' && echo ready')!='ready':raise ValueError('需要 Magisk BusyBox')
        vendor=self.shell('dumpsys package com.baidu.duershow.dcs')
        name=re.search(r'\bversionName=(\S+)',vendor);code=re.search(r'\bversionCode=(\d+)',vendor)
        if not name or not code:raise ValueError('缺少可识别的原厂 DCS 语音宿主')
        self.factory={'package':'com.baidu.duershow.dcs','version_name':name[1],'version_code':code[1],
                      'matches_tested_baseline':(name[1],code[1])==('6.0.15.0','60000')}
        launcher=self.shell('dumpsys package com.baidu.launcher')
        launcher_name=re.search(r'\bversionName=(\S+)',launcher);launcher_code=re.search(r'\bversionCode=(\d+)',launcher)
        self.factory['launcher']={'version_name':launcher_name[1] if launcher_name else None,
                                  'version_code':launcher_code[1] if launcher_code else None}
        self.factory['launcher_matches_tested_baseline']=bool(launcher_name and launcher_code and
            (launcher_name[1],launcher_code[1])==('1.68.2','10682'))
    def state(self):
        try:
            value=json.loads(self.root(BB+' wget -q -T 3 -O - http://127.0.0.1:8765/v1/voice/status'))
            return {'state':value.get('state'),'error':value.get('error'),'realtime':value.get('realtime',False),
                    'samples':value.get('samples'),'wake_source':value.get('wake_source'),
                    'factory_wake_phase':value.get('factory_wake_phase'),
                    'wake_ready':value.get('state')=='wake_ready' and not value.get('error')}
        except (ValueError,subprocess.CalledProcessError):
            return {'state':'unavailable','wake_ready':False}


def plan(bridge_url,include_clock=True,wake_source='factory'):
    if wake_source not in ('factory','local'):raise ValueError('wake_source只能为factory或local')
    return {'model':'XDH-29-B1','bridge_url':validate_url(bridge_url),
            'install':['语音 APK']+(['待机时钟 APK'] if include_clock else [])+['采音与局域网控制模块'],
            'wake_source':wake_source,'wake_word':'小度小度' if wake_source=='factory' else '真维斯',
            'first_install_only':True,'reboot':False}


def inspect(serial):
    device=AdbDevice(serial);device.prerequisites()
    exists=device.root('test -d '+MODULE+' && echo exists || echo absent')=='exists'
    return {'prerequisites_valid':True,'module_installed':exists,'factory':device.factory,**device.state()}


def install(serial,bridge_url,include_clock=True,wake_source='factory'):
    expected=plan(bridge_url,include_clock,wake_source)
    # Validate all local inputs before any device mutation.
    builds=[validate_build('voice-client')]
    if include_clock:builds.append(validate_build('screen-care'))
    module=package()
    device=AdbDevice(serial);device.prerequisites()
    if not device.factory['matches_tested_baseline']:
        raise ValueError('原厂 DCS 不在已核验基线（6.0.15.0 / 60000）；请先核对厂商私有接口，不自动安装')
    if not device.factory.get('launcher_matches_tested_baseline'):
        raise ValueError('原厂launcher不在已核验基线（1.68.2 / 10682）；请先核对厂商私有接口，不自动安装')
    if device.root('test -d '+MODULE+' && echo exists || echo absent')!='absent':
        raise ValueError('发现已有模块：请先 --check，再按维护文档处理；不能覆盖正在运行的服务')
    unique=uuid.uuid4().hex
    stage='/data/local/tmp/xiaodu-jarvis-'+unique
    candidate='/data/adb/xiaodu-jarvis-stage-'+unique
    lock='/data/adb/xiaodu-jarvis-install.lock'
    device.root('mkdir '+lock+' && chmod 700 '+lock)
    activated=False
    try:
        if device.root('test -d '+MODULE+' && echo exists || echo absent')!='absent':
            raise ValueError('取得安装锁后发现已有模块；本次不安装 APK 或改写配置')
        device.shell('mkdir '+stage+' && chmod 700 '+stage)
        for proof,apk in builds:device.run(['install','-r',str(apk)])
        device.shell('pm grant local.xiaodu.voice android.permission.RECORD_AUDIO')
        if include_clock:
            device.shell('pm grant local.xiaodu.screencare android.permission.WRITE_SECURE_SETTINGS')
            device.shell('appops set local.xiaodu.screencare WRITE_SETTINGS allow')
            mode=device.shell('appops get local.xiaodu.screencare WRITE_SETTINGS')
            if not re.search(r'WRITE_SETTINGS:\s*allow\b',mode):raise ValueError('时钟写设置权限未确认')
        with tempfile.TemporaryDirectory() as folder:
            config=Path(folder)/'connection.json'
            config.write_text(json.dumps({'bridge_url':expected['bridge_url']}))
            checksum=hashlib.sha256(config.read_bytes()).hexdigest()
            device.run(['push',str(config),stage+'/connection.json'])
        device.activity('local.xiaodu.voice/.CallActivity',' --ez preview true')
        data='/data/data/local.xiaodu.voice'
        owner=device.root('stat -c %u:%g '+data)
        if not re.fullmatch(r'\d+:\d+',owner):raise ValueError('无法确认应用数据属主')
        device.root('mkdir -p '+data+'/files && cp '+stage+'/connection.json '+data+'/files/connection.json.new && '
                    'chown '+owner+' '+data+'/files '+data+'/files/connection.json.new && '
                    'chmod 700 '+data+'/files && chmod 600 '+data+'/files/connection.json.new && '
                    'mv -f '+data+'/files/connection.json.new '+data+'/files/connection.json && restorecon -R '+data+'/files')
        observed_hash=device.root(BB+' sha256sum '+data+'/files/connection.json').split()
        if not observed_hash or observed_hash[0]!=checksum:
            raise ValueError('桥接地址写入校验失败')
        device.run(['push',str(module),stage+'/module.zip'])
        device.root('mkdir '+candidate+' && chmod 700 '+candidate+' && '+BB+' unzip -q '+stage+'/module.zip -d '+candidate+
                    ' && cd '+candidate+' && '+BB+' sha256sum -c SHA256SUMS && chmod 700 service.sh watch.sh frida-inject')
        device.root('test ! -e '+MODULE+' && mv '+candidate+' '+MODULE)
        activated=True
        device.root('setprop persist.xiaodu.wake_source '+wake_source)
        device.root('sh '+MODULE+'/service.sh')
        if include_clock:device.activity('local.xiaodu.screencare/.ClockActivity')
        deadline=time.monotonic()+45
        first_samples=None
        while True:
            observed=device.state()
            current_samples=observed.get('samples')
            if observed.get('wake_ready') and not observed.get('realtime') and observed.get('wake_source')==wake_source:
                factory_ready=wake_source=='factory' and observed.get('factory_wake_phase')=='ready'
                local_ready=wake_source=='local' and type(current_samples) is int and first_samples is not None and current_samples>first_samples
                if factory_ready or local_ready:
                    result={'installed':True,'wake_ready':True,'wake_source':wake_source,
                            'factory_wake_phase':observed.get('factory_wake_phase'),
                            'capture_advancing':local_ready,'configuration_verified':True,'automatic_test_call':False}
                    print(json.dumps(result,ensure_ascii=False));return result
                first_samples=current_samples
            else:first_samples=None
            if observed.get('state')=='failed' or time.monotonic()>=deadline:
                raise ValueError('模块已安装，但唤醒尚未就绪；请用 --check 与维护文档排查，不要重复覆盖')
            time.sleep(.25)
    except Exception:
        if activated:print('模块已保留在 '+MODULE+'；本次没有自动删改已安装组件。',file=sys.stderr)
        raise
    finally:
        # Only remove this invocation's staging paths; never delete existing apps/module.
        for command in ('rm -rf '+candidate,'rm -rf '+stage,'rmdir '+lock):
            try:device.root(command)
            except (OSError,subprocess.SubprocessError):pass


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--bridge-url')
    mode=parser.add_mutually_exclusive_group();mode.add_argument('--apply',action='store_true');mode.add_argument('--check',action='store_true')
    parser.add_argument('--no-clock',action='store_true',help='不安装或修改待机时钟')
    parser.add_argument('--wake-source',choices=['factory','local'],default='factory',help='默认原厂“小度小度”；local为可选“真维斯”')
    args=parser.parse_args()
    if not args.check and not args.bridge_url:parser.error('计划/安装需要 --bridge-url')
    try:
        if args.check:print(json.dumps(inspect(args.serial),ensure_ascii=False,indent=2))
        elif args.apply:install(args.serial,args.bridge_url,not args.no_clock,args.wake_source)
        else:print(json.dumps(plan(args.bridge_url,not args.no_clock,args.wake_source),ensure_ascii=False,indent=2))
    except (OSError,ValueError,subprocess.SubprocessError) as exc:
        print('操作未完成：'+str(exc),file=sys.stderr);raise SystemExit(1)

#!/usr/bin/env python3
"""Owner maintenance: enable/disable timing or collect content-free call summaries.

No call start, microphone, playback, service restart, or audio/transcript download.
"""
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import uuid
import urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'system/codex-bridge'))
from configuration import SETTINGS,local_bridge_url,validate_url
STATE=Path(SETTINGS['state_dir'])
DIRECTORIES={
    'kws':'/data/data/com.baidu.duershow.dcs/files/xiaodu-http/timings',
    'client':'/data/data/local.xiaodu.voice/files/timings',
}


def sanitize(record):
    """Export only typed milestone data, never existing last-call user/error fields."""
    call_id=str(uuid.UUID(record['call_id']))
    clock=record.get('clock')
    if clock not in ('android_elapsed_realtime_ns','bridge_monotonic_ns'):
        raise ValueError('unknown clock domain')
    safe={'call_id':call_id,'clock':clock,'enabled':record.get('enabled') is True}
    for field in ('milestones_ns','durations_ms'):
        safe[field]={key:value for key,value in record.get(field,{}).items()
            if isinstance(key,str) and re.fullmatch('[a-z_]{1,80}',key)
            and type(value) in (int,float) and value>=0}
    for field in ('monotonic_anchor_ns','wall_anchor_unix_ns'):
        if type(record.get(field)) is int:safe[field]=record[field]
    if record.get('outcome') in ('ended','failed'):safe['outcome']=record['outcome']
    if type(record.get('sealed')) is bool:safe['sealed']=record['sealed']
    if record.get('checkpoint') in ('ready','shutdown'):safe['checkpoint']=record['checkpoint']
    safe['downlink_pcm_semantics']='playback_callback_before_render_not_speaker_audibility'
    return safe


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['on','off','status','collect'])
    parser.add_argument('--out',type=Path,help='New output file for collect')
    parser.add_argument('--serial',required=True,help='Explicit adb device serial')
    parser.add_argument('--bridge-url',help='覆盖配置中的本机桥接地址')
    args=parser.parse_args()
    if args.command=='collect' and args.out is None:parser.error('collect requires --out')
    ADB='adb'
    info={'serial':args.serial}
    def shell(command,root=False):
        if root:command='su -c '+shlex.quote(command)
        p=subprocess.run([str(ADB),'-s',info['serial'],'shell',command],capture_output=True,text=True,timeout=15)
        if p.returncode:raise RuntimeError('device diagnostic operation failed')
        return p.stdout.strip()
    if args.command in ('on','off'):
        enabled=args.command=='on'
        shell('setprop persist.xiaodu.voice_timing '+('1' if enabled else '0'),True)
        actual=shell('getprop persist.xiaodu.voice_timing')
        if actual!=('1' if enabled else '0'):raise RuntimeError('device timing toggle unconfirmed')
        STATE.mkdir(parents=True,exist_ok=True,mode=0o700)
        flag=STATE/'voice-timing.off'
        if enabled:flag.unlink(missing_ok=True)
        else:flag.touch(mode=0o600,exist_ok=True)
    if args.command!='collect':
        device=shell('getprop persist.xiaodu.voice_timing')
        observed=None
        try:
            base=validate_url(args.bridge_url) if args.bridge_url else local_bridge_url(SETTINGS)
            with urllib.request.urlopen(base+'/v1/realtime/status',timeout=4) as response:observed=json.load(response).get('timing_enabled')
        except (OSError,ValueError):pass
        print(json.dumps({'device_enabled':device not in ('0','false','off','no'),
            'bridge_enabled':observed,'bridge_flag_enabled':not (STATE/'voice-timing.off').exists(),
            'applies':'next_call','history_limit_per_component':20,'service_restart_required':False},ensure_ascii=False))
        return
    calls={}
    for component,directory in DIRECTORIES.items():
        names=shell('/data/adb/magisk/busybox ls '+shlex.quote(directory)+' 2>/dev/null || true',True).splitlines()
        for name in names:
            if not name.endswith('.json'):continue
            try:call_id=str(uuid.UUID(name[:-5]))
            except ValueError:continue
            if name!=call_id+'.json':continue
            value=shell('cat '+shlex.quote(directory+'/'+name),True)
            if len(value)>16384:raise RuntimeError('unexpected timing summary size')
            record=sanitize(json.loads(value));calls.setdefault(record['call_id'],{})[component]=record
    directory=STATE/'timings'
    if directory.is_dir():
        for path in directory.glob('*.json'):
            try:uuid.UUID(path.stem)
            except ValueError:continue
            record=sanitize(json.loads(path.read_text()));calls.setdefault(record['call_id'],{})['bridge']=record
    result={'schema':1,'clock_warning':'Join by call_id; never directly subtract wall clocks across hosts.',
            'calls':[{'call_id':key,**value} for key,value in sorted(calls.items())]}
    descriptor=os.open(args.out,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(descriptor,'w') as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    print(json.dumps({'saved':str(args.out.resolve()),'calls':len(calls)},ensure_ascii=False))


if __name__=='__main__':
    try:main()
    except (OSError,RuntimeError,ValueError,KeyError) as e:
        print(json.dumps({'error':type(e).__name__,'operation_failed':True}),file=sys.stderr)
        raise SystemExit(1)

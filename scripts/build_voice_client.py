#!/usr/bin/env python3
"""Build the API27/ARMv7 native voice surface with a pinned libwebrtc AAR."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT/'system/voice-client'
SDK = ROOT/'.cache/android-mini'
RTC = ROOT/'.cache/webrtc-125.6422.07'
BUILD = ROOT/'artifacts/voice-client/build'
PRIVATE = ROOT/'private/voice-client-signing'


def run(args, **kwargs):
    subprocess.run(list(map(str, args)), check=True, cwd=ROOT, **kwargs)


def main():
    aar = RTC/'android-125.6422.07.aar'
    digest = hashlib.sha256(aar.read_bytes()).hexdigest()
    if digest != (RTC/'android-125.6422.07.aar.sha256').read_text().strip():
        raise RuntimeError('Maven AAR digest mismatch')
    if BUILD.exists(): shutil.rmtree(BUILD)
    for n in ('generated','classes','dex'): (BUILD/n).mkdir(parents=True,exist_ok=True)
    PRIVATE.mkdir(parents=True,exist_ok=True,mode=0o700);PRIVATE.chmod(0o700)
    password=PRIVATE/'password'
    if not password.exists(): password.write_text(secrets.token_hex(24));password.chmod(0o600)
    env=os.environ.copy();env['XIAODU_VOICE_SIGNING_PASSWORD']=password.read_text().strip()
    key=PRIVATE/'voice-client.p12'
    if not key.exists():
        run(['keytool','-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass:env','XIAODU_VOICE_SIGNING_PASSWORD','-alias','voice-client','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Xiaodu Jarvis Voice'],env=env)
        key.chmod(0o600)
    unsigned=BUILD/'unsigned.apk'
    run(['aapt','package','-f','-M',SOURCE/'AndroidManifest.xml','-S',SOURCE/'res','-I',SDK/'android-27.jar','-F',unsigned,'-J',BUILD/'generated'])
    shared=ROOT/'system/voice-timing/src'
    sources=sorted((SOURCE/'src').rglob('*.java'))+sorted(shared.rglob('*.java'))
    run(['javac','-encoding','UTF-8','--release','8','-classpath',str(SDK/'android-27.jar')+':'+str(RTC/'classes.jar'),'-d',BUILD/'classes',*sources])
    run(['java','-cp',SDK/'r8.jar','com.android.tools.r8.D8','--release','--min-api','27','--lib',SDK/'android-27.jar','--output',BUILD/'dex',RTC/'classes.jar',*sorted((BUILD/'classes').rglob('*.class'))])
    with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as z:
        for dex in sorted((BUILD/'dex').glob('*.dex')): z.write(dex,dex.name)
        z.write(RTC/'jni/armeabi-v7a/libjingle_peerconnection_so.so','lib/armeabi-v7a/libjingle_peerconnection_so.so')
        for license in sorted((SOURCE/'licenses').glob('*')):z.write(license,'assets/licenses/'+license.name)
        z.writestr('assets/THIRD_PARTY_NOTICE.txt','WebRTC Android SDK io.github.webrtc-sdk:android:125.6422.07\nhttps://github.com/webrtc-sdk/android\nBSD 3-Clause; WebRTC Project Authors. Includes upstream third-party components.\n')
    aligned=BUILD/'aligned.apk';run(['zipalign','-f','4',unsigned,aligned])
    version=ET.parse(SOURCE/'AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
    result=BUILD.parent/('xiaodu-voice-'+version+'.apk')
    run(['apksigner','sign','--ks',key,'--ks-key-alias','voice-client','--ks-pass','env:XIAODU_VOICE_SIGNING_PASSWORD','--out',result,aligned],env=env)
    run(['apksigner','verify','--verbose',result])
    proof={'apk':str(result.relative_to(ROOT)),'sha256':hashlib.sha256(result.read_bytes()).hexdigest(),'bytes':result.stat().st_size,'abi':'armeabi-v7a','api':27,'webrtc_aar_sha256':digest,
        'sources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for tree in (SOURCE,shared) for p in tree.rglob('*') if p.is_file()}}
    proof['build_script_sha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    (BUILD.parent/'build-proof.json').write_text(json.dumps(proof,indent=2)+'\n')
    print(json.dumps({k:v for k,v in proof.items() if k!='sources'},indent=2))


if __name__=='__main__': main()

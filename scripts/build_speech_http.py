#!/usr/bin/env python3
"""Build the small dex service loaded into the existing factory voice process."""
import hashlib
import json
from pathlib import Path
import subprocess
import shutil
import zipfile

ROOT=Path(__file__).resolve().parents[1]
BUILD=ROOT/'artifacts/speech-http'
SDK=ROOT/'.cache/android-mini'

def main():
    BUILD.mkdir(parents=True,exist_ok=True)
    for name in ['classes','dex']:
        if (BUILD/name).exists():shutil.rmtree(BUILD/name)
        (BUILD/name).mkdir()
    source=ROOT/'system/speech-http/src/local/xiaodu/speech/HttpSpeechServer.java'
    sources=sorted((ROOT/'system/speech-http/src').rglob('*.java'))+sorted((ROOT/'system/voice-timing/src').rglob('*.java'))
    subprocess.run(['javac','-encoding','UTF-8','--release','8','-classpath',str(SDK/'android-27.jar'),'-d',str(BUILD/'classes'),*map(str,sources)],check=True)
    subprocess.run(['java','-cp',str(SDK/'r8.jar'),'com.android.tools.r8.D8','--release','--min-api','27','--lib',str(SDK/'android-27.jar'),'--output',str(BUILD/'dex'),*map(str,sorted((BUILD/'classes').rglob('*.class')))],check=True)
    jar=BUILD/'speech-http.jar'
    with zipfile.ZipFile(jar,'w',compression=zipfile.ZIP_DEFLATED) as z:z.write(BUILD/'dex/classes.dex','classes.dex')
    proof={'jar':str(jar.relative_to(ROOT)),'bytes':jar.stat().st_size,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest()}
    proof['source_hashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    (BUILD/'build-proof.json').write_text(json.dumps(proof,indent=2)+'\n');print(json.dumps(proof))

if __name__=='__main__':main()

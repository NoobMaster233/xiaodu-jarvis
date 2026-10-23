#!/usr/bin/env python3
"""生成带 APK/JAR 的公开工具包与 SHA256SUMS，不上传、不连接设备。"""
import json
from pathlib import Path
import shutil
import stat
import zipfile
from check_public import ROOT, check
from prepare import digest
from artifacts import validate_build


def release():
    files,errors=check()
    if errors:raise ValueError('公开源码检查未通过')
    result=ROOT/'artifacts/release';result.mkdir(parents=True,exist_ok=True)
    payload={str(p.relative_to(ROOT)):p for p in files}
    outputs=[]
    for directory,field in (('voice-client','apk'),('screen-care','apk'),('speech-http','jar')):
        proof_path=ROOT/'artifacts'/directory/'build-proof.json'
        proof,file=validate_build(directory)
        payload[str(file.relative_to(ROOT))]=file
        payload[str(proof_path.relative_to(ROOT))]=proof_path
        if field=='apk':
            target=result/file.name;shutil.copyfile(file,target);outputs.append(target)
    version=(ROOT/'VERSION').read_text().strip()
    bundle=result/('xiaodu-jarvis-v'+version+'.zip')
    with zipfile.ZipFile(bundle,'w',compression=zipfile.ZIP_DEFLATED) as z:
        for name,path in sorted(payload.items()):
            item=zipfile.ZipInfo('xiaodu-jarvis/'+name,(2026,1,1,0,0,0));item.create_system=3
            executable=name.startswith('client/') or name=='skills/xiaodu-announce/scripts/xiaodu'
            item.external_attr=(stat.S_IFREG|(0o755 if executable else 0o644))<<16
            z.writestr(item,path.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
    outputs.insert(0,bundle)
    checks=result/'SHA256SUMS'
    checks.write_text(''.join(digest(p)+'  '+p.name+'\n' for p in outputs))
    print(json.dumps({'files':[{'name':p.name,'bytes':p.stat().st_size,'sha256':digest(p)} for p in outputs],
        'public_source_files':len(files),'bundled_files':len(payload)},ensure_ascii=False,indent=2))
    return outputs+[checks]


if __name__=='__main__':release()

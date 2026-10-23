#!/usr/bin/env python3
"""从本地已校验依赖组装 Magisk 模块，不连接设备。"""
import hashlib
import json
from pathlib import Path
import stat
import zipfile
from prepare import ROOT, CACHE, MANIFEST, digest
from artifacts import validate_build


def package():
    proof,jar=validate_build('speech-http')
    for entry in json.loads(MANIFEST.read_text()):
        if entry['name'] not in {'frida','sherpa','model'}: continue
        for item in entry['outputs']:
            if digest(CACHE/item['path']) != item['sha256']: raise ValueError('运行依赖校验失败')
    files = {name:ROOT/'system/speech-http'/name for name in ('module.prop','service.sh','watch.sh','loader.js')}
    files['speech-http.jar'] = jar
    for entry in json.loads(MANIFEST.read_text()):
        if entry['name'] in {'frida','sherpa','model'}:
            for item in entry['outputs']:
                path=CACHE/item['path'];files[str(path.relative_to(CACHE/'runtime'))]=path
    files['model/keywords.txt']=CACHE/'runtime/model/keywords.txt'
    files['LICENSE']=ROOT/'LICENSE'
    files['THIRD_PARTY_NOTICES.md']=ROOT/'THIRD_PARTY_NOTICES.md'
    files['licenses/sherpa-onnx-LICENSE']=ROOT/'system/speech-http/licenses/sherpa-onnx-LICENSE'
    result = ROOT/'artifacts/xiaodu-speech-module.zip'
    with zipfile.ZipFile(result, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, source in sorted(files.items()):
            mode = 0o755 if name in {'service.sh','watch.sh','frida-inject'} else 0o644
            item = zipfile.ZipInfo(name, (2026,1,1,0,0,0)); item.create_system = 3
            item.external_attr = (stat.S_IFREG|mode)<<16
            archive.writestr(item, source.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
        archive.writestr('skip_mount', '')
        checks={name:digest(source) for name,source in files.items()}
        checks['skip_mount']=hashlib.sha256(b'').hexdigest()
        archive.writestr('SHA256SUMS',''.join(checksum+'  '+name+'\n' for name,checksum in sorted(checks.items())))
    print(json.dumps({'file':str(result.relative_to(ROOT)), 'sha256':digest(result)}, ensure_ascii=False))
    return result


if __name__ == '__main__': package()

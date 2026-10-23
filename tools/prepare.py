#!/usr/bin/env python3
"""下载固定版本依赖并校验 SHA256；只写本仓库 .cache，不访问设备。"""
import argparse
import hashlib
import json
import lzma
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT/'.cache'
MANIFEST = Path(__file__).with_name('dependencies.json')


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1024*1024), b''): h.update(block)
    return h.hexdigest()


def checked_path(base, name):
    path = (base/name).resolve()
    if not path.is_relative_to(base.resolve()): raise ValueError('不安全的文件路径')
    return path


def cached_match(base,names,expected):
    if base:
        for name in names:
            if not name:continue
            path=checked_path(base,name)
            if path.is_file() and digest(path)==expected:return path
    return None


def extract(archive, kind, member, target):
    """只打开清单指定的普通文件，不调用 extractall，不接受归档链接。"""
    with tempfile.TemporaryDirectory(dir=target.parent) as folder:
        temporary = Path(folder)/'file'
        if kind == 'xz':
            with lzma.open(archive) as source, temporary.open('wb') as out:
                shutil.copyfileobj(source, out)
        elif member is None:
            shutil.copyfile(archive, temporary)
        elif kind == 'zip':
            with zipfile.ZipFile(archive) as z:
                item = z.getinfo(member)
                if item.is_dir() or (item.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError('依赖不能是目录或链接')
                with z.open(item) as source, temporary.open('wb') as out:
                    shutil.copyfileobj(source, out)
        elif kind == 'tar':
            with tarfile.open(archive) as tar:
                item = tar.getmember(member)
                if not item.isfile(): raise ValueError('依赖必须是普通文件')
                with tar.extractfile(item) as source, temporary.open('wb') as out:
                    shutil.copyfileobj(source, out)
        else: raise ValueError('未知归档类型')
        temporary.replace(target)


def prepare(group, cache_from=None):
    selected = {'frida', 'sherpa', 'model'} if group == 'runtime' else None
    for entry in json.loads(MANIFEST.read_text()):
        if selected and entry['name'] not in selected: continue
        pending = []
        for item in entry['outputs']:
            target = checked_path(CACHE, item['path']); target.parent.mkdir(parents=True, exist_ok=True)
            if target.is_file() and digest(target) == item['sha256']: continue
            cached=cached_match(cache_from,['.cache/'+item['path'],item['path'],item['cached']],item['sha256'])
            if cached:
                shutil.copyfile(cached, target)
            else: pending.append((item, target))
        if pending:
            downloads = CACHE/'downloads'; downloads.mkdir(parents=True, exist_ok=True)
            archive = downloads/entry['sha256']
            cached=cached_match(cache_from,['.cache/downloads/'+entry['sha256'],'downloads/'+entry['sha256'],entry['cached']],entry['sha256'])
            if cached:
                shutil.copyfile(cached, archive)
            if not archive.is_file() or digest(archive) != entry['sha256']:
                print('下载：'+entry['name'], flush=True)
                temporary = archive.with_suffix('.partial')
                try:
                    with urllib.request.urlopen(entry['url'], timeout=60) as response, temporary.open('wb') as out:
                        size = 0
                        while block := response.read(1024*1024):
                            size += len(block)
                            if size > 512*1024*1024: raise ValueError('依赖文件超出上限')
                            out.write(block)
                    if digest(temporary) != entry['sha256']: raise ValueError('下载 SHA256 不匹配')
                    temporary.replace(archive)
                finally: temporary.unlink(missing_ok=True)
            for item, target in pending:
                extract(archive, entry['archive'], item['member'], target)
                if digest(target) != item['sha256']:
                    target.unlink(); raise ValueError('解包文件 SHA256 不匹配')
        print('已校验：'+entry['name'], flush=True)
    runtime = CACHE/'runtime'
    (runtime/'model').mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT/'examples/keywords.txt', runtime/'model/keywords.txt')
    binary = runtime/'frida-inject'
    if binary.exists(): binary.chmod(0o755)
    rtc = CACHE/'webrtc-125.6422.07'
    if rtc.exists():
        value = next(x['sha256'] for x in json.loads(MANIFEST.read_text()) if x['name']=='webrtc')
        (rtc/'android-125.6422.07.aar.sha256').write_text(value+'\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--group', choices=['all', 'runtime'], default='all')
    parser.add_argument('--cache-from', type=Path, help='可选：从已有本地源码缓存复用同 SHA256 的依赖')
    args = parser.parse_args()
    prepare(args.group, args.cache_from)

#!/usr/bin/env python3
"""公开源码检查：允许清单、私钥/媒体拒绝规则、私网地址和配置残留。"""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
TREES=('system','client','skills','scripts','tools','docs','examples','.github')
TOP=('README.md','LICENSE','THIRD_PARTY_NOTICES.md','config.example.json','requirements.txt','.gitignore','VERSION','CHANGELOG.md')
FORBIDDEN={'.p12','.pfx','.jks','.keystore','.wav','.pcm','.img','.bin','.apk','.jar','.pyc'}
PATTERNS={
    'private_network': re.compile(rb'\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b'),
    'mac_address': re.compile(rb'\b(?:[a-fA-F0-9]{2}:){5}[a-fA-F0-9]{2}\b'),
    'private_key': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'token_literal': re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{30,})\b'),
    'owner_path': re.compile(rb'/root/(?:projects|\.codex)/'),
}


def public_files(root=ROOT):
    paths=[]
    def accept(p):
        if p.is_symlink() or not p.resolve().is_relative_to(root.resolve()):
            raise ValueError('公开文件不能是链接或仓库外文件：'+str(p.relative_to(root)))
        if p.is_file():paths.append(p)
    for p in TOP:accept(root/p)
    for tree in TREES:
        if (root/tree).is_symlink():raise ValueError('公开目录不能是链接：'+tree)
        for p in (root/tree).rglob('*'):
            if '__pycache__' in p.parts: continue
            accept(p)
    return sorted(paths)


def check(root=ROOT):
    files=public_files(root);errors=[]
    for p in files:
        relative=str(p.relative_to(root))
        if p.suffix.lower() in FORBIDDEN:errors.append((relative,'forbidden_file'))
        content=p.read_bytes()
        for label,regex in PATTERNS.items():
            if regex.search(content):errors.append((relative,label))
    return files,errors


if __name__=='__main__':
    files,errors=check()
    if errors:
        for path,reason in errors:print(reason+': '+path)
        raise SystemExit(1)
    print('公开源码检查通过：'+str(len(files))+' 个文件')

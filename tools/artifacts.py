"""构建证明校验：源码、编译脚本与产物必须属于同一版。"""
import json
from pathlib import Path
from prepare import ROOT, digest


def inside(root, name):
    path=root/name
    if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('构建输入不能是链接或仓库外文件')
    for parent in path.parents:
        if parent==root:break
        if parent.is_symlink():raise ValueError('构建输入目录不能是链接')
    return path


def expected_sources(root, kind):
    if kind=='speech-http':
        files=list((root/'system/speech-http/src').rglob('*.java'))
        files+=list((root/'system/voice-timing/src').rglob('*.java'))
    else:
        files=[p for p in (root/'system'/kind).rglob('*') if p.is_file()]
        if kind=='voice-client':files += [p for p in (root/'system/voice-timing/src').rglob('*') if p.is_file()]
    return {str(p.relative_to(root)):digest(inside(root,p.relative_to(root))) for p in files}


def validate_build(kind, root=ROOT):
    proof_path=inside(root,Path('artifacts')/kind/'build-proof.json')
    proof=json.loads(proof_path.read_text())
    recorded=proof.get('source_hashes' if kind=='speech-http' else 'sources')
    if recorded!=expected_sources(root,kind):raise ValueError(kind+' 源码已变化，请重新构建')
    script=inside(root,Path('scripts')/('build_'+kind.replace('-','_')+'.py'))
    if proof.get('build_script_sha256')!=digest(script):raise ValueError(kind+' 构建脚本已变化，请重新构建')
    artifact=inside(root,proof['jar' if kind=='speech-http' else 'apk'])
    if digest(artifact)!=proof['sha256']:raise ValueError(kind+' 产物 SHA256 不匹配')
    return proof,artifact

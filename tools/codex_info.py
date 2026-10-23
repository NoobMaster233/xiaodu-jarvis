#!/usr/bin/env python3
"""只读列出已有 Codex 对话 ID；不创建对话、不发消息、不修改模型。"""
import argparse
import asyncio
import contextlib
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'system/codex-bridge'))
from bridge import Codex,RPCError


async def list_threads(endpoint=None,search=None):
    async def ignore(method,params):pass
    rpc=Codex(ignore,endpoint=endpoint)
    task=asyncio.create_task(rpc.run())
    try:
        await asyncio.wait_for(rpc.ready.wait(),10)
        cursor=None;threads=[]
        while True:
            params={'limit':100,'sortKey':'updated_at','modelProviders':[],
                    'sourceKinds':['cli','vscode','appServer','exec'],'useStateDbOnly':True}
            if search:params['searchTerm']=search
            if cursor:params['cursor']=cursor
            result=await rpc.call('thread/list',params)
            threads += [{key:item.get(key) for key in ('id','name','cwd')} for item in result.get('data',[])]
            cursor=result.get('nextCursor')
            if not cursor:break
        return {'connected':True,'read_only':True,'threads':threads}
    finally:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):await task


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--socket',help='指定已有 app-server 的 Unix socket')
    parser.add_argument('--search',help='按标题筛选已有主对话')
    args=parser.parse_args()
    try:print(json.dumps(asyncio.run(list_threads(args.socket,args.search)),ensure_ascii=False,indent=2))
    except (OSError,ValueError,RuntimeError,TimeoutError,RPCError) as exc:
        print('未能只读列出对话：'+type(exc).__name__+'；请检查 Codex 启动方式与 socket。',file=sys.stderr)
        raise SystemExit(1)

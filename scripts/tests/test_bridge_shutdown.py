"""Actual SIGTERM handling in an isolated child, without binding or contacting anything."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]


class BridgeShutdownTest(unittest.TestCase):
    def test_sigterm_saves_unsealed_trace_without_call_stop(self):
        code=r'''
import asyncio,os,signal,sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
import bridge
from realtime import Realtime
from milestones import Milestones
class Site:
    def __init__(self,*args,**kwargs):pass
    async def start(self):
        asyncio.get_running_loop().call_soon(os.kill,os.getpid(),signal.SIGTERM)
bridge.web.TCPSite=Site
class RPC:
    async def run(self):await asyncio.Future()
    async def call(self,*args):raise AssertionError('shutdown must not issue RPC')
class IsolatedBridge(bridge.Bridge):
    def __init__(self):
        self.rpc=RPC();self.realtime=Realtime(self,'main')
        self.realtime.call={'id':'12345678-1234-1234-1234-123456789abc','state':'connected'}
        self.realtime.timing=Milestones(self.realtime.call['id'],path=Path(sys.argv[2]),enabled=True)
        self.realtime.timing.mark('ready_received')
    async def speak(self):await asyncio.Future()
    async def voice(self):await asyncio.Future()
async def main():await IsolatedBridge().run()
asyncio.run(main())
'''
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'last.json'
            subprocess.run([sys.executable,'-c',code,str(ROOT/'system/codex-bridge'),str(path)],
                           check=True,capture_output=True,text=True,timeout=10)
            record=json.loads(path.read_text())
            self.assertFalse(record['sealed'])
            self.assertEqual(record['checkpoint'],'shutdown')
            self.assertIn('bridge_shutdown',record['milestones_ns'])
            self.assertNotIn('call_end',record['milestones_ns'])
            self.assertNotIn('outcome',record)


if __name__=='__main__':unittest.main()

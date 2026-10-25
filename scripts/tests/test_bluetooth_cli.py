"""Bluetooth CLI requests are one-shot; no live device requests."""
import contextlib
import importlib.machinery
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

ROOT=Path(__file__).resolve().parents[2]
loader=importlib.machinery.SourceFileLoader('bluetooth_cli',str(ROOT/'client/xiaodu'))
spec=importlib.util.spec_from_loader(loader.name,loader)
cli=importlib.util.module_from_spec(spec);loader.exec_module(cli)


class BluetoothCliTests(unittest.TestCase):
    def run_cli(self,args,result,status=200):
        connection=Mock();reply=connection.getresponse.return_value
        reply.status=status;reply.read.return_value=json.dumps(result).encode()
        out=io.StringIO();err=io.StringIO()
        with patch.object(sys,'argv',['xiaodu','--url','http://device.example:8765',*args]),patch.object(cli.http.client,'HTTPConnection',return_value=connection),contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
            code=cli.main()
        return code,out.getvalue(),err.getvalue(),connection.request.call_args_list

    def test_status_is_one_read_only_get(self):
        for args in [['bluetooth'],['bluetooth','status']]:
            with self.subTest(args=args):
                expected={'enabled':True,'name':'Test speaker','discoverable':False}
                code,out,_,calls=self.run_cli(args,expected)
                self.assertEqual(code,0);self.assertEqual(json.loads(out),expected)
                self.assertEqual(len(calls),1);self.assertEqual(calls[0].args[:3],('GET','/v1/bluetooth',None))

    def test_actions_send_exactly_one_post(self):
        for action in ['on','off','pair']:
            with self.subTest(action=action):
                code,_,_,calls=self.run_cli(['bluetooth',action],{'verified':True})
                self.assertEqual(code,0);self.assertEqual(len(calls),1)
                self.assertEqual(calls[0].args[:2],('POST','/v1/bluetooth'))
                self.assertEqual(json.loads(calls[0].args[2]),{'action':action})

    def test_unconfirmed_action_does_not_retry(self):
        code,out,_,calls=self.run_cli(['bluetooth','on'],{'enabled':False,'verified':False})
        self.assertEqual(code,2);self.assertFalse(json.loads(out)['verified']);self.assertEqual(len(calls),1)

    def test_rejected_action_does_not_retry(self):
        code,_,err,calls=self.run_cli(['bluetooth','off'],{'error':'bluetooth_busy'},409)
        self.assertEqual(code,1);self.assertEqual(len(calls),1);self.assertFalse(json.loads(err)['automatic_replay'])

    def test_unknown_action_does_not_contact_device(self):
        with patch.object(sys,'argv',['xiaodu','bluetooth','scan']),patch.object(cli.http.client,'HTTPConnection') as connection,contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as end:cli.main()
            self.assertEqual(end.exception.code,2);self.assertFalse(connection.called)


if __name__=='__main__':unittest.main()

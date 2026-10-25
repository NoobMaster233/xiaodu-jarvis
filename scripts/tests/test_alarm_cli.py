"""One-shot native alarm requests: no device, clock service or ringing."""
import contextlib
from datetime import datetime
import importlib.machinery
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock,patch

ROOT=Path(__file__).resolve().parents[2]
loader=importlib.machinery.SourceFileLoader('alarm_cli',str(ROOT/'client/xiaodu'))
spec=importlib.util.spec_from_loader(loader.name,loader);cli=importlib.util.module_from_spec(spec);loader.exec_module(cli)


class AlarmCliTests(unittest.TestCase):
    def run_cli(self,args,result=None,status=200):
        conn=Mock();reply=conn.getresponse.return_value
        reply.status=status;reply.read.return_value=json.dumps(result or {'verified':True}).encode()
        out=io.StringIO();err=io.StringIO()
        with patch.object(sys,'argv',['xiaodu','--url','http://device.example:8765',*args]),patch.object(cli.http.client,'HTTPConnection',return_value=conn),contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):code=cli.main()
        return code,out.getvalue(),err.getvalue(),conn.request.call_args_list

    def test_list_is_one_get(self):
        for args in [['alarm'],['alarm','list']]:
            code,_,_,calls=self.run_cli(args,{'alarms':[]})
            self.assertEqual(code,0);self.assertEqual(len(calls),1);self.assertEqual(calls[0].args[:3],('GET','/v1/alarms',None))

    def test_relative_alarm_uses_device_time_and_unique_ids(self):
        ids=[]
        for _ in range(2):
            code,_,_,calls=self.run_cli(['alarm','add','--after','30'])
            self.assertEqual(code,0);self.assertEqual(len(calls),1)
            self.assertEqual(calls[0].args[:2],('POST','/v1/alarms'))
            body=json.loads(calls[0].args[2]);self.assertEqual(body['action'],'add');self.assertEqual(body['after_minutes'],30)
            self.assertNotIn('at_ms',body);self.assertNotIn('repeat',body);ids.append(body['id'])
        self.assertNotEqual(*ids)

    def test_absolute_time_keeps_timezone(self):
        value='2026-10-25T08:00:00+08:00'
        code,_,_,calls=self.run_cli(['alarm','add','--at',value]);self.assertEqual(code,0)
        body=json.loads(calls[0].args[2]);self.assertEqual(body['at_ms'],int(datetime.fromisoformat(value).timestamp())*1000)

    def test_delete_passes_exact_opaque_id_in_body(self):
        identity='native/id:abc=='
        code,_,_,calls=self.run_cli(['alarm','delete',identity]);self.assertEqual(code,0);self.assertEqual(len(calls),1)
        self.assertEqual(json.loads(calls[0].args[2]),{'action':'delete','id':identity})

    def test_invalid_arguments_never_send_a_request(self):
        for args in [['alarm','add'],['alarm','add','--after','0'],['alarm','add','--after','525601'],['alarm','add','--at','2026-10-25T08:00:00'],['alarm','delete'],['alarm','list','an-id'],['alarm','delete','some-id','--after','30']]:
            with self.subTest(args=args):
                code,_,_,calls=self.run_cli(args);self.assertEqual(code,1);self.assertFalse(calls)

    def test_missing_confirmation_is_not_repeated(self):
        code,_,_,calls=self.run_cli(['alarm','add','--after','30'],{'verified':False,'id':'xiaodu-test'})
        self.assertEqual(code,2);self.assertEqual(len(calls),1)

    def test_server_failure_is_not_repeated(self):
        code,_,err,calls=self.run_cli(['alarm','delete','id'],{'error':'alarm_service_unavailable'},503)
        self.assertEqual(code,1);self.assertFalse(json.loads(err)['automatic_replay']);self.assertEqual(len(calls),1)

    def test_conflicting_time_flags_fail_before_request(self):
        with patch.object(sys,'argv',['xiaodu','alarm','add','--after','30','--at','2026-10-25T08:00:00+08:00']),patch.object(cli.http.client,'HTTPConnection') as conn,contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):cli.main()
            self.assertFalse(conn.called)


if __name__=='__main__':unittest.main()

"""Fixed native controls: one request, explicit targets and no automatic replay."""
import contextlib
import importlib.machinery
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock,patch

ROOT=Path(__file__).resolve().parents[2]
loader=importlib.machinery.SourceFileLoader('factory_cli',str(ROOT/'client/xiaodu'))
spec=importlib.util.spec_from_loader(loader.name,loader)
cli=importlib.util.module_from_spec(spec);loader.exec_module(cli)


class FactoryCliTests(unittest.TestCase):
    def run_cli(self,args,result=None,status=200):
        conn=Mock();reply=conn.getresponse.return_value
        reply.status=status;reply.read.return_value=json.dumps(result if result is not None else {'verified':True}).encode()
        out=io.StringIO();err=io.StringIO()
        with patch.object(sys,'argv',['xiaodu','--url','http://device.example:8765',*args]),patch.object(cli.http.client,'HTTPConnection',return_value=conn),contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
            code=cli.main()
        return code,out.getvalue(),err.getvalue(),conn.request.call_args_list

    def test_alarm_volume_read_and_set_use_separate_fixed_route(self):
        code,_,_,calls=self.run_cli(['volume','--stream','alarm'])
        self.assertEqual(code,0);self.assertEqual(len(calls),1)
        self.assertEqual(calls[0].args[:3],('GET','/v1/alarm-volume',None))
        code,_,_,calls=self.run_cli(['volume','40','--stream','alarm'])
        self.assertEqual(code,0);self.assertEqual(len(calls),1)
        self.assertEqual(calls[0].args[:2],('POST','/v1/alarm-volume'))
        self.assertEqual(json.loads(calls[0].args[2]),{'value':40})

    def test_media_volume_default_remains_unchanged(self):
        code,_,_,calls=self.run_cli(['volume','30'])
        self.assertEqual(code,0);self.assertEqual(calls[0].args[:2],('POST','/v1/volume'))
        self.assertEqual(json.loads(calls[0].args[2]),{'value':30})

    def test_countdown_uses_device_relative_seconds_and_unique_ids(self):
        identities=[]
        for args in [['timer','add','--after','10'],['timer','add','--seconds','600']]:
            code,_,_,calls=self.run_cli(args)
            self.assertEqual(code,0);self.assertEqual(len(calls),1)
            self.assertEqual(calls[0].args[:2],('POST','/v1/timers'))
            body=json.loads(calls[0].args[2]);self.assertEqual(body['duration_seconds'],600)
            self.assertEqual(set(body),{'action','id','duration_seconds'})
            identities.append(body['id'])
        self.assertNotEqual(*identities)

    def test_timer_queries_and_deletion_do_not_touch_alarms(self):
        code,_,_,calls=self.run_cli(['timer'])
        self.assertEqual(code,0);self.assertEqual(calls[0].args[:3],('GET','/v1/timers',None))
        code,_,_,calls=self.run_cli(['timer','delete','opaque/native:id'])
        self.assertEqual(code,0);self.assertEqual(len(calls),1)
        self.assertEqual(calls[0].args[:2],('POST','/v1/timers'))
        self.assertEqual(json.loads(calls[0].args[2]),{'action':'delete','id':'opaque/native:id'})

    def test_alarm_stop_is_not_delete_all_or_media_stop(self):
        code,_,_,calls=self.run_cli(['alarm','stop'])
        self.assertEqual(code,0);self.assertEqual(len(calls),1)
        self.assertEqual(calls[0].args[:2],('POST','/v1/alarms'))
        self.assertEqual(json.loads(calls[0].args[2]),{'action':'stop'})

    def test_bluetooth_next_targets_only_bluetooth(self):
        code,_,_,calls=self.run_cli(['media','next','--app','bluetooth'])
        self.assertEqual(code,0);self.assertEqual(len(calls),1)
        self.assertEqual(json.loads(calls[0].args[2]),{'action':'next','app':'bluetooth'})

    def test_bluetooth_query_never_substitutes_netease(self):
        code,out,_,calls=self.run_cli(['media','--app','bluetooth'],{'sessions':[{'package':'com.netease.cloudmusic.tv'}]})
        self.assertEqual(code,0);self.assertEqual(json.loads(out),{'sessions':[]})
        self.assertEqual(len(calls),1);self.assertEqual(calls[0].args[:2],('GET','/v1/media'))

    def test_camera_power_is_explicit_and_never_takes_a_photo(self):
        for action in ['on','off']:
            code,_,_,calls=self.run_cli(['camera',action])
            self.assertEqual(code,0);self.assertEqual(len(calls),1)
            self.assertEqual(calls[0].args[:2],('POST','/v1/camera/power'))
            self.assertEqual(json.loads(calls[0].args[2]),{'enabled':action=='on'})

    def test_notice_keeps_text_as_data_and_clears_only_own_card(self):
        text='完成；$(echo secret) `test` "换行"\n第二行'
        code,_,_,calls=self.run_cli(['notify',text,'--title','结果'])
        self.assertEqual(code,0);self.assertEqual(len(calls),1)
        self.assertEqual(calls[0].args[:2],('POST','/v1/notice'))
        self.assertEqual(json.loads(calls[0].args[2]),{'text':text,'title':'结果'})
        code,_,_,calls=self.run_cli(['notify','--clear'])
        self.assertEqual(code,0);self.assertEqual(json.loads(calls[0].args[2]),{'action':'clear'})

    def test_bad_duration_or_notice_never_sends_a_request(self):
        for args in [['timer','add'],['timer','add','--seconds','0'],['timer','add','--after','1441'],['timer','list','id'],['timer','delete'],['timer','delete','id','--seconds','10'],['alarm','stop','id'],['notify'],['notify',''],['notify','😀'*251],['notify','body','--title','😀'*41],['notify','body','--clear'],['notify','--clear','--title','title']]:
            with self.subTest(args=args):
                code,_,_,calls=self.run_cli(args)
                self.assertEqual(code,1);self.assertFalse(calls)

    def test_unconfirmed_or_failed_controls_never_repeat(self):
        for args in [['timer','add','--after','10'],['camera','off'],['notify','完成'],['alarm','stop'],['volume','40','--stream','alarm']]:
            with self.subTest(args=args):
                code,_,_,calls=self.run_cli(args,{'verified':False})
                self.assertEqual(code,2);self.assertEqual(len(calls),1)
                code,_,err,calls=self.run_cli(args,{'error':'factory_device_unavailable'},503)
                self.assertEqual(code,1);self.assertEqual(len(calls),1)
                self.assertFalse(json.loads(err)['automatic_replay'])


if __name__=='__main__':unittest.main()

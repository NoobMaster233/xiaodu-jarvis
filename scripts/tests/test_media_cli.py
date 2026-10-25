"""Media query selection and action targeting; no device requests."""
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
loader=importlib.machinery.SourceFileLoader('media_cli',str(ROOT/'client/xiaodu'))
spec=importlib.util.spec_from_loader(loader.name,loader)
cli=importlib.util.module_from_spec(spec)
loader.exec_module(cli)


class MediaCliTests(unittest.TestCase):
    def run_cli(self,args,result):
        connection=Mock()
        reply=connection.getresponse.return_value
        reply.status=200
        reply.read.return_value=json.dumps(result).encode()
        output=io.StringIO()
        with patch.object(sys,'argv',['xiaodu','--url','http://device.example:8765',*args]), \
                patch.object(cli.http.client,'HTTPConnection',return_value=connection), \
                contextlib.redirect_stdout(output):
            code=cli.main()
        return code,json.loads(output.getvalue()),connection.request.call_args_list

    def test_query_filters_each_supported_app_without_control_requests(self):
        sessions=[{'package':p,'title':name} for name,p in cli.MEDIA_PACKAGES.items()]
        for name,package in cli.MEDIA_PACKAGES.items():
            for args in [['media','--app',name],['media','status','--app',name]]:
                with self.subTest(args=args):
                    code,result,calls=self.run_cli(args,{'sessions':sessions})
                    self.assertEqual(code,0)
                    self.assertEqual(result['sessions'],[s for s in sessions if s['package']==package])
                    self.assertEqual(len(calls),1)
                    self.assertEqual(calls[0].args[:3],('GET','/v1/media',None))

    def test_missing_selected_app_returns_empty_without_using_another_player(self):
        code,result,calls=self.run_cli(['media','--app','bilibili'],{
            'sessions':[{'package':'com.netease.cloudmusic.tv','state':'playing'}]})
        self.assertEqual(code,0)
        self.assertEqual(result,{'sessions':[]})
        self.assertEqual(len(calls),1)

    def test_unfiltered_query_preserves_all_sessions(self):
        expected={'sessions':[{'package':'com.netease.cloudmusic.tv'}, {'package':'org.videolan.vlc'}]}
        code,result,calls=self.run_cli(['media'],expected)
        self.assertEqual(code,0)
        self.assertEqual(result,expected)
        self.assertEqual(len(calls),1)

    def test_targeted_next_still_sends_exactly_one_post(self):
        code,result,calls=self.run_cli(['media','next','--app','netease'],{'sent':True,'verified':True})
        self.assertEqual(code,0)
        self.assertEqual(len(calls),1)
        self.assertEqual(calls[0].args[:2],('POST','/v1/media'))
        self.assertEqual(json.loads(calls[0].args[2]),{'action':'next','app':'netease'})


if __name__=='__main__':unittest.main()

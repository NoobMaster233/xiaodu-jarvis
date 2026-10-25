"""Camera CLI contracts: no live device, camera or model requests."""
import contextlib
import importlib.util
import importlib.machinery
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch,Mock

ROOT=Path(__file__).resolve().parents[2]
loader=importlib.machinery.SourceFileLoader('xiaodu_camera_cli',str(ROOT/'client/xiaodu'))
spec=importlib.util.spec_from_loader(loader.name,loader);cli=importlib.util.module_from_spec(spec);loader.exec_module(cli)
JPEG=b'\xff\xd8\xff\xc0'+struct.pack('>H',17)+b'\x08'+struct.pack('>HH',2,3)+b'\x03\x01\x11\x00\x02\x11\x00\x03\x11\x00\xff\xd9'

class CameraCliTest(unittest.TestCase):
 def run_cli(self,args,response=None,status=200):
  connection=Mock();reply=connection.getresponse.return_value
  reply.status=status;reply.read.return_value=JPEG if response is None else response
  reply.getheader.return_value='image/jpeg'
  out=io.StringIO();err=io.StringIO()
  with patch.object(sys,'argv',['xiaodu','--url','http://device.example:8765',*args]),patch.object(cli.http.client,'HTTPConnection',return_value=connection),contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):rc=cli.main()
  return rc,connection,out.getvalue(),err.getvalue()
 def test_jpeg_dimensions(self):self.assertEqual(cli.jpeg_dimensions(JPEG),(3,2))
 def test_rejects_truncated_jpeg(self):
  with self.assertRaises(RuntimeError):cli.jpeg_dimensions(JPEG[:-1])
 def test_rejects_broken_segment(self):
  with self.assertRaises(RuntimeError):cli.jpeg_dimensions(b'\xff\xd8\xff\xe1\xff\xff\xff\xd9')
 def test_existing_output_never_takes_a_photo(self):
  with tempfile.TemporaryDirectory() as folder:
   path=Path(folder)/'photo.jpg';path.write_bytes(b'original')
   code,connection,_,_=self.run_cli(['photo',str(path)])
   self.assertEqual(code,1);self.assertFalse(connection.request.called);self.assertEqual(path.read_bytes(),b'original')
 def test_success_is_exactly_one_post_and_private_image(self):
  with tempfile.TemporaryDirectory() as folder:
   path=Path(folder)/'photo.jpg';code,connection,out,_=self.run_cli(['photo',str(path)])
   self.assertEqual(code,0);self.assertEqual(connection.request.call_count,1)
   self.assertEqual(connection.request.call_args.args[:2],('POST','/v1/camera/photo'))
   self.assertEqual(json.loads(connection.request.call_args.args[2]),{})
   self.assertEqual(path.stat().st_mode&0o777,0o600);self.assertEqual(path.read_bytes(),JPEG)
   value=json.loads(out);self.assertEqual((value['width'],value['height']),(3,2));self.assertNotIn('base64',value)
 def test_invalid_photo_is_removed_without_retry(self):
  with tempfile.TemporaryDirectory() as folder:
   path=Path(folder)/'photo.jpg';code,connection,_,err=self.run_cli(['photo',str(path)],b'not a JPEG')
   self.assertEqual(code,1);self.assertEqual(connection.request.call_count,1);self.assertFalse(path.exists())
   self.assertFalse(json.loads(err)['automatic_replay'])
 def test_removed_camera_query_is_not_an_exposed_command(self):
  with patch.object(sys,'argv',['xiaodu','camera']),patch.object(cli.http.client,'HTTPConnection') as connection,contextlib.redirect_stderr(io.StringIO()):
   with self.assertRaises(SystemExit) as end:cli.main()
   self.assertEqual(end.exception.code,2);self.assertFalse(connection.called)
 def test_removed_camera_selection_is_rejected_before_request(self):
  with patch.object(sys,'argv',['xiaodu','photo','--camera','0']),patch.object(cli.http.client,'HTTPConnection') as connection,contextlib.redirect_stderr(io.StringIO()):
   with self.assertRaises(SystemExit) as end:cli.main()
   self.assertEqual(end.exception.code,2);self.assertFalse(connection.called)
 def test_response_reports_only_useful_file_details(self):
  with tempfile.TemporaryDirectory() as folder:
   code,_,out,_=self.run_cli(['photo',str(Path(folder)/'one.jpg')])
   self.assertEqual(code,0);self.assertEqual(set(json.loads(out)),{'saved','width','height','bytes'})

 def test_redirect_is_failure_without_following_or_leaving_a_file(self):
  with tempfile.TemporaryDirectory() as folder:
   path=Path(folder)/'redirect.jpg';code,connection,_,err=self.run_cli(['photo',str(path)],b'{"error":"moved"}',status=302)
   self.assertEqual(code,1);self.assertEqual(connection.request.call_count,1);self.assertFalse(path.exists())
   self.assertFalse(json.loads(err)['automatic_replay'])

if __name__=='__main__':unittest.main()

"""发布配置与封装测试，不能访问真实设备、账号或现有 Codex。"""
import importlib.util
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
sys.path.insert(0,str(ROOT/'system/codex-bridge'))
from configuration import load_config,validate_url
from prepare import checked_path,extract
import device
from check_public import check,public_files


class ConfigurationTest(unittest.TestCase):
    def test_missing_required_config_fails_before_startup(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):load_config(Path(folder)/'absent',required=True)

    def test_valid_configuration_preserves_thread_settings(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'config.json';p.write_text(json.dumps({'main_thread_id':'sample-thread','device_url':'http://device.example:8765/'}))
            value=load_config(p,required=True)
            self.assertEqual(value['device_url'],'http://device.example:8765')
            self.assertEqual(value['voice'],'juniper')
            self.assertIsNone(value['backend_model']);self.assertIsNone(value['service_tier'])

    def test_unknown_config_field_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'config.json';p.write_text('{"token":"not-needed"}')
            with self.assertRaises(ValueError):load_config(p)

    def test_credentials_paths_and_wrong_schemes_are_rejected(self):
        for url in ('http://name:pass@device.example','http://device.example/api','https://device.example',
                    'file:///tmp/a','http://device.example:0','http://device.example?secret=bad'):
            with self.subTest(url=url),self.assertRaises(ValueError):validate_url(url)

    def test_install_plan_cannot_contact_a_device(self):
        with patch('subprocess.run',side_effect=AssertionError('plan must be offline')):
            p=device.plan('http://bridge.example:8766')
        self.assertFalse(p['reboot']);self.assertTrue(p['first_install_only'])


class ArchiveTest(unittest.TestCase):
    def test_destination_cannot_escape_cache(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):checked_path(Path(folder),'../outside')

    def test_zip_extracts_only_selected_file(self):
        with tempfile.TemporaryDirectory() as folder:
            f=Path(folder);archive=f/'a.zip'
            with zipfile.ZipFile(archive,'w') as z:z.writestr('ok','verified');z.writestr('../escape','bad')
            extract(archive,'zip','ok',f/'out')
            self.assertEqual((f/'out').read_text(),'verified');self.assertFalse((f/'escape').exists())

    def test_tar_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            f=Path(folder);archive=f/'a.tar'
            with tarfile.open(archive,'w') as tar:
                item=tarfile.TarInfo('selected');item.type=tarfile.SYMTYPE;item.linkname='/etc/passwd';tar.addfile(item)
            with self.assertRaises(ValueError):extract(archive,'tar','selected',f/'out')

    def test_public_source_has_no_private_payload(self):
        files,errors=check()
        self.assertGreater(len(files),50);self.assertEqual(errors,[])

    def test_source_allowlist_excludes_local_state_and_signing(self):
        paths=[str(p.relative_to(ROOT)) for p in public_files()]
        self.assertFalse(any(p.startswith(('private/','.cache/','artifacts/','.git/')) for p in paths))


if __name__=='__main__':unittest.main()

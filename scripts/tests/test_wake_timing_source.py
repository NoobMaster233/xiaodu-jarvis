"""Keep source semantics without allowing arbitrary content into timing exports."""
from pathlib import Path
import importlib.util
import unittest
import uuid

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('voice_timing_source',ROOT/'scripts/voice_timing.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class WakeTimingSourceTest(unittest.TestCase):
    def test_export_keeps_only_known_source_semantics(self):
        for source,kind in [('factory','factory_sdk_stop_ack'),('local','local_audio_record_release')]:
            raw={'call_id':str(uuid.uuid4()),'clock':'android_elapsed_realtime_ns','wake_source':source,'release_kind':kind,'text':'private','audio':'private'}
            result=module.sanitize(raw)
            self.assertEqual(result['wake_source'],source);self.assertEqual(result['release_kind'],kind)
            self.assertNotIn('text',result);self.assertNotIn('audio',result)

    def test_unknown_values_are_not_exported(self):
        result=module.sanitize({'call_id':str(uuid.uuid4()),'clock':'android_elapsed_realtime_ns','wake_source':'private payload','release_kind':{'secret':'private'}})
        self.assertNotIn('wake_source',result);self.assertNotIn('release_kind',result)


if __name__=='__main__':unittest.main()

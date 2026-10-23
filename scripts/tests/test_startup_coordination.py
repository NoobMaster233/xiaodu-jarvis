"""Production Java event/ownership tests, isolated from real devices and servers."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]


class StartupCoordinationTest(unittest.TestCase):
    def test_production_event_barriers_and_capture_races(self):
        with tempfile.TemporaryDirectory() as folder:
            sources=[ROOT/'system/speech-http/src/local/xiaodu/speech/MicrophoneHandoff.java',
                     ROOT/'system/voice-client/src/local/xiaodu/voice/AudioStartupGate.java',
                     Path(__file__).with_name('StartupCoordinationTest.java')]
            subprocess.run(['javac','--release','8','-d',folder,*map(str,sources)],check=True)
            subprocess.run(['java','-cp',folder,'StartupCoordinationTest'],check=True,timeout=15)


if __name__=='__main__':unittest.main()

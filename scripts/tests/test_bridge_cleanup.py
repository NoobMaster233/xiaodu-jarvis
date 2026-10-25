"""Wake cancellation and the single ready-driven greeting, without live services."""
import asyncio
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import AsyncMock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'system/codex-bridge'))
import bridge
from milestones import Milestones
from realtime import Realtime


class BridgeCleanupTest(unittest.IsolatedAsyncioTestCase):
    async def test_wake_observer_cancels_speech_without_submitting_legacy_asr(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(bridge, 'STATE', Path(folder)):
            observer = bridge.Bridge()
        observer.silence = AsyncMock()
        observer.input = AsyncMock()
        responses = iter([
            {'instance_id': 'source', 'cursor': 5, 'events': []},
            {'instance_id': 'source', 'cursor': 7, 'events': [
                {'seq': 6, 'kind': 'wake'},
                {'seq': 7, 'kind': 'final', 'text': 'legacy input must not be submitted'},
            ]},
        ])
        paths = []

        def device(method, path):
            paths.append((method, path))
            return next(responses)

        sleeps = 0

        async def sleep(_):
            nonlocal sleeps
            sleeps += 1
            if sleeps == 2:
                raise asyncio.CancelledError()

        with patch.object(bridge, 'device', side_effect=device), patch.object(bridge.asyncio, 'sleep', side_effect=sleep):
            with self.assertRaises(asyncio.CancelledError):
                await observer.voice()
        observer.silence.assert_awaited_once()
        observer.input.assert_not_awaited()
        self.assertEqual(observer.voice_cursor, 7)
        self.assertEqual(paths, [('GET', '/v1/voice/events'), ('GET', '/v1/voice/events?after=5')])

    async def test_greeting_requires_matching_ready_and_is_sent_once(self):
        calls = []
        cid = str(uuid.uuid4())

        class RPC:
            async def call(inner, method, params):
                calls.append((method, params))
                if method == 'thread/realtime/start':
                    await realtime.event('thread/realtime/sdp', {'threadId': 'main', 'sdp': 'v=0\nanswer'})
                else:
                    await asyncio.sleep(0)
                return {}

        class Host:
            rpc = RPC()
            silence = AsyncMock()
            resume = AsyncMock()

        with tempfile.TemporaryDirectory() as folder, patch('realtime.Milestones', side_effect=lambda value: Milestones(value, path=Path(folder)/'last.json', enabled=True)):
            realtime = Realtime(Host(), 'main')
            await realtime.start(cid, 'v=0\noffer')
            self.assertEqual([m for m, _ in calls], ['thread/realtime/start'])
            await realtime.ready(str(uuid.uuid4()))
            self.assertEqual(len(calls), 1)
            await asyncio.gather(realtime.ready(cid), realtime.ready(cid))
            self.assertEqual(sum(m == 'thread/realtime/appendSpeech' for m, _ in calls), 1)
            await realtime.end(cid)
            await realtime.ready(cid)
            self.assertEqual(sum(m == 'thread/realtime/appendSpeech' for m, _ in calls), 1)

    async def test_native_call_and_teardown_still_suppress_http_speech(self):
        realtime = Realtime(None, 'main')
        with patch('realtime.time.time', return_value=100):
            self.assertFalse(realtime.suppress_tts())
            realtime.call = {'state': 'connecting'}
            self.assertTrue(realtime.suppress_tts())
            realtime.call['state'] = 'ended'
            realtime.hold_until = 108
            self.assertTrue(realtime.suppress_tts())
        with patch('realtime.time.time', return_value=109):
            self.assertFalse(realtime.suppress_tts())


if __name__ == '__main__':
    unittest.main()

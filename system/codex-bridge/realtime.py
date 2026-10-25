"""One native Codex voice session, attached to the existing main thread.

Only SDP and lifecycle pass through this process. WebRTC media stays on its
negotiated connection. No audio, SDP credentials, or transcripts are logged.
"""
import asyncio
import contextlib
import time
import uuid

from aiohttp import web
from milestones import Milestones, enabled_by_policy
from configuration import SETTINGS

class Realtime:
    def __init__(self, bridge, main):
        self.bridge, self.main = bridge, main
        self.call = None
        self.lock = asyncio.Lock()
        self.answer = None
        self.hold_until = 0
        self.timing = None

    def active(self):
        return bool(self.call and self.call['state'] in ('connecting', 'connected'))

    def suppress_tts(self):
        return self.active() or time.time() < self.hold_until

    def status(self):
        keys = ('id', 'state', 'reason', 'started', 'connected', 'input_text', 'output_text', 'backend_busy')
        value = {k: self.call[k] for k in keys if self.call and k in self.call}
        value.update(active=self.active(), main_thread=self.main, timing_enabled=enabled_by_policy())
        return value

    def mark(self, point):
        if self.timing:
            self.timing.mark(point)

    def finish_timing(self, outcome):
        if self.timing:
            self.timing.finish(outcome)

    def shutdown_checkpoint(self):
        if self.timing:
            self.timing.mark('bridge_shutdown')
            self.timing.checkpoint('shutdown')

    async def event(self, method, p):
        if not self.call or p.get('threadId') != self.main:
            return
        if method == 'thread/realtime/sdp':
            self.mark('sdp_arrived')
            if self.answer and not self.answer.done():
                self.answer.set_result(p['sdp'])
        elif method == 'thread/realtime/error':
            self.mark('remote_error')
            self.call.update(state='failed', reason=p.get('message', 'voice_error')[:200])
            if self.answer and not self.answer.done():
                self.answer.set_exception(RuntimeError(self.call['reason']))
            self.hold_until = max(self.hold_until, time.time()+5)
            self.finish_timing('failed')
        elif method == 'thread/realtime/closed':
            self.mark('remote_closed')
            if self.call['state'] not in ('failed', 'ended'):
                self.call.update(state='ended', reason=p.get('reason') or 'remote_closed')
            self.hold_until = max(self.hold_until, time.time()+5)
            if self.answer and not self.answer.done():
                self.answer.set_exception(RuntimeError('voice_closed_during_start'))
            self.finish_timing('failed' if self.call['state'] == 'failed' else 'ended')
        elif method == 'thread/realtime/transcript/done':
            key = 'input_text' if p.get('role') == 'user' else 'output_text'
            self.call[key] = p.get('text', '')[-400:]
        elif method == 'turn/started':
            self.call['backend_busy'] = True
        elif method == 'turn/completed':
            self.call['backend_busy'] = False

    async def start(self, call_id, sdp):
        if not isinstance(sdp, str) or not sdp.startswith('v=0') or len(sdp) > 65536:
            raise ValueError('invalid_sdp')
        uuid.UUID(call_id)
        async with self.lock:
            if self.active():
                raise web.HTTPConflict(text='voice_session_already_active')
            self.call = {'id': call_id, 'state': 'connecting', 'started': time.time(),
                         'last_seen': time.monotonic(), 'backend_busy': False}
            self.timing = Milestones(call_id)
            self.mark('offer_enter')
            self.answer = asyncio.get_running_loop().create_future()
            try:
                self.mark('silence_begin');await self.bridge.silence();self.mark('silence_end')
                self.mark('resume_begin');await self.bridge.resume(self.main, True);self.mark('resume_end')
                self.mark('realtime_start_begin')
                await self.bridge.rpc.call('thread/realtime/start', {
                    'threadId': self.main, 'version': 'v3', 'outputModality': 'audio',
                    'realtimeSessionId': call_id, 'voice': SETTINGS['voice'],
                    'initialItems': [{'role': 'developer', 'text': (
                        '当前通过小度进行实时语音，使用简洁中文交流。'
                        '需要工具执行、查询后台进度、修改或取消任务、结束通话时，交给同一主对话的Codex处理，依据实际结果答复。'
                        '小度支持按需拍摄单张照片；现场视觉问题也交给Codex拍照并看图。'
                    )}],
                    'transport': {'type': 'webrtc', 'sdp': sdp},
                    'codexResponseHandoffMode': 'commentary',
                    'backendReasoningStatus': False,
                    'realtimeStartInstructions': (
                        '本次为小度原生实时语音通话，回复已由实时音频呈现。'
                        '沿用本主对话的AGENTS.md与对应Skill。'
                    ),
                    'realtimeEndInstructions': (
                        '本次实时语音已结束，无需播报结束通知。'
                    ),
                })
                self.mark('realtime_start_ack')
                answer = await asyncio.wait_for(asyncio.shield(self.answer), 25)
                self.call['last_seen'] = time.monotonic()
                self.mark('answer_return')
                return {'id': call_id, 'sdp': answer, 'main_thread': self.main}
            except BaseException:
                with contextlib.suppress(Exception):
                    await self.bridge.rpc.call('thread/realtime/stop', {'threadId': self.main})
                self.call.update(state='failed', reason='connection_failed')
                self.finish_timing('failed')
                self.hold_until = max(self.hold_until, time.time()+5)
                raise

    async def ready(self, call_id):
        if not self.call or self.call['id'] != call_id or not self.active():
            return self.status()
        self.call['last_seen'] = time.monotonic()
        self.mark('ready_received')
        if not self.call.get('connected'):
            self.call.update(state='connected', connected=time.time())
            # Native speech generation, after the media peer has connected.
            self.mark('greeting_begin')
            await self.bridge.rpc.call('thread/realtime/appendSpeech', {
                'threadId': self.main, 'text': '你好，我是贾维斯。请说。',
            })
            self.mark('greeting_ack')
            # Save once while connected, so an operator restart cannot erase startup timings.
            if self.timing:
                await asyncio.to_thread(self.timing.checkpoint)
        return self.status()

    async def end(self, call_id=None, reason='requested'):
        async with self.lock:
            if not self.call:
                # A controller restart can lose its RAM state while the client
                # is still closing the old fixed-main-thread media connection.
                with contextlib.suppress(Exception):
                    await self.bridge.rpc.call('thread/realtime/stop', {'threadId': self.main})
                return self.status()
            if call_id and self.call['id'] != call_id:
                return self.status()
            self.mark('close_begin')
            if self.active():
                # Hold local speech before stop; ending a call must not trigger
                # the legacy HTTP TTS path for its trailing backend messages.
                self.hold_until = time.time()+8
                await self.bridge.rpc.call('thread/realtime/stop', {'threadId': self.main})
                if self.call['state'] != 'failed':
                    self.call.update(state='ended', reason=reason)
            if not self.active():
                self.finish_timing('failed' if self.call['state'] == 'failed' else 'ended')
            return self.status()

    async def watch(self):
        while True:
            await asyncio.sleep(2)
            if not self.active():
                continue
            stale = time.monotonic()-self.call['last_seen']
            limit = 40 if self.call['state'] == 'connecting' else 20
            if stale > limit or time.time()-self.call['started'] > 3500:
                with contextlib.suppress(Exception):
                    await self.end(reason='client_disconnected' if stale > limit else 'duration_limit')

    async def http(self, request):
        path = request.path
        if request.method == 'GET' and path == '/v1/realtime/status':
            call_id = request.query.get('id')
            if self.call and self.call['id'] == call_id and self.active():
                self.call['last_seen'] = time.monotonic()
            return web.json_response(self.status())
        if request.method != 'POST':
            raise web.HTTPNotFound()
        body = await request.json()
        if path == '/v1/realtime/offer':
            return web.json_response(await self.start(body['id'], body['sdp']))
        if path == '/v1/realtime/ready':
            return web.json_response(await self.ready(body['id']))
        if path == '/v1/realtime/end':
            return web.json_response(await self.end(body.get('id')))
        raise web.HTTPNotFound()

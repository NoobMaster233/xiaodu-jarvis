"""One bounded, content-free timing summary per realtime call."""
import json
import os
import time
import uuid
from threading import RLock
from functools import wraps
from pathlib import Path

from configuration import SETTINGS
LAST = Path(SETTINGS['state_dir']).expanduser()/'last-realtime-timing.json'
POINTS = frozenset(('offer_enter silence_begin silence_end resume_begin resume_end '
    'realtime_start_begin realtime_start_ack sdp_arrived answer_return ready_received '
    'greeting_begin greeting_ack remote_closed remote_error bridge_shutdown close_begin call_end').split())


def locked(method):
    @wraps(method)
    def invoke(self, *args, **kwargs):
        with self._lock:
            return method(self, *args, **kwargs)
    return invoke


def enabled_by_policy():
    return os.environ.get('XIAODU_VOICE_TIMING', '1') != '0' and not (LAST.parent/'voice-timing.off').exists()


def write_private(path, data):
    temporary=path.with_name(path.name+'.tmp')
    try:
        descriptor=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
        with os.fdopen(descriptor,'w') as stream:
            os.fchmod(stream.fileno(),0o600);stream.write(data)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


class Milestones:
    def __init__(self, call_id, *, path=LAST, enabled=None, clock=time.monotonic_ns,
                 wall=time.time_ns):
        uuid.UUID(call_id)
        self._lock = RLock()
        self.call_id, self.path, self.clock = call_id, Path(path), clock
        self.enabled = enabled_by_policy() if enabled is None else enabled
        self.anchor_ns, self.wall_ns = (clock(), wall()) if self.enabled else (None, None)
        self.points, self.sealed, self.outcome = {}, False, None
        self.checkpoints = set()
        self.checkpoint_stage = None

    @locked
    def mark(self, point):
        if point not in POINTS:
            raise ValueError('unknown_timing_point')
        if self.enabled and not self.sealed and point not in self.points:
            self.points[point] = self.clock()

    @locked
    def snapshot(self):
        value = {'schema': 1, 'call_id': self.call_id, 'enabled': self.enabled,
                 'clock': 'bridge_monotonic_ns'}
        if not self.enabled:
            return value
        value.update(monotonic_anchor_ns=self.anchor_ns, wall_anchor_unix_ns=self.wall_ns,
                     wall_anchor_semantics='adjacent_samples_not_cross_host_clock_sync',
                     milestones_ns=dict(self.points), sealed=self.sealed)
        if self.outcome:
            value['outcome'] = self.outcome
        if self.checkpoint_stage:
            value['checkpoint'] = self.checkpoint_stage
        durations = {}
        for name, begin, end in [('silence', 'silence_begin', 'silence_end'),
            ('resume', 'resume_begin', 'resume_end'),
            ('realtime_start_rpc', 'realtime_start_begin', 'realtime_start_ack'),
            ('realtime_start_to_sdp', 'realtime_start_begin', 'sdp_arrived'),
            ('offer_to_ready', 'offer_enter', 'ready_received'),
            ('greeting_rpc', 'greeting_begin', 'greeting_ack')]:
            if begin in self.points and end in self.points and self.points[end] >= self.points[begin]:
                durations[name] = (self.points[end]-self.points[begin])/1_000_000
        value['durations_ms'] = durations
        return value

    @locked
    def checkpoint(self, stage='ready'):
        if stage not in ('ready', 'shutdown'):
            raise ValueError('unknown_timing_checkpoint')
        if not self.enabled or self.sealed or stage in self.checkpoints:
            return
        self.checkpoints.add(stage)
        self.checkpoint_stage = stage
        self._persist()

    @locked
    def finish(self, outcome):
        if not self.enabled or self.sealed:
            return
        if outcome not in ('ended', 'failed'):
            raise ValueError('unknown_timing_outcome')
        self.mark('call_end')
        self.outcome, self.sealed = outcome, True
        self._persist()

    def _persist(self):
        try:
            data=json.dumps(self.snapshot(),ensure_ascii=True,separators=(',',':'))
            write_private(self.path,data)
            history = self.path.parent/'timings'
            history.mkdir(mode=0o700, exist_ok=True)
            history.chmod(0o700)
            item = history/(self.call_id+'.json')
            write_private(item,data)
            owned = []
            for record in history.glob('*.json'):
                try:
                    uuid.UUID(record.stem)
                    owned.append(record)
                except ValueError:
                    pass
            for record in sorted(owned, key=lambda p:p.stat().st_mtime_ns)[:-20]:
                record.unlink()
        except OSError:
            # Diagnostic storage cannot fail or change the call lifecycle.
            pass

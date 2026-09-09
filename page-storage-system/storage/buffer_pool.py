import base64
import binascii
from dataclasses import dataclass, field
import threading
from typing import Dict, List

from .constants import PAGE_SIZE
from .errors import StorageError
from .replacement import make_policy


@dataclass
class BufferFrame:
    data: bytearray
    dirty: bool = False
    pending_lsns: List[int] = field(default_factory=list)


class BufferPool:
    def __init__(self, page_manager, capacity: int = 16, policy: str = 'LRU', log: bool = True,
                 wal=None, lock_manager=None):
        if not isinstance(capacity, int) or isinstance(capacity, bool) or capacity <= 0:
            raise StorageError('BUFFER_INVALID_CAPACITY', 'capacity 必须是正整数')
        self.page_manager = page_manager
        self.capacity = capacity
        self.policy = make_policy(policy)
        self.log_enabled = bool(log)
        self.wal = wal
        self.lock_manager = lock_manager
        self.frames: Dict[int, BufferFrame] = {}
        self.hits = 0
        self.misses = 0
        self.evictions = 0
        self.flushes = 0
        self.events = []
        self._guard = threading.RLock()

    def _log(self, event: str, page_id: int, victim_page_id=None):
        if self.log_enabled:
            self.events.append({'event': event, 'pageId': page_id, 'victimPageId': victim_page_id})

    def set_policy(self, policy: str):
        new_policy = make_policy(policy)
        for page_id in self.frames:
            new_policy.record_insert(page_id)
        self.policy = new_policy
        return {'policy': self.policy.name}

    def get_events(self, *, clear=False):
        events = [dict(e) for e in self.events]
        if clear:
            self.events.clear()
        return events

    def _lock(self, page_id, mode, owner):
        if self.lock_manager is None:
            return None
        owner = owner or f'buffer-{threading.get_ident()}'
        return self.lock_manager.held(page_id, mode, owner)

    def _flush_frame(self, page_id: int, frame: BufferFrame):
        if not frame.dirty:
            return False
        self.page_manager.write_page(page_id, bytes(frame.data))
        self.page_manager.sync()
        if self.wal is not None:
            for seq in frame.pending_lsns:
                self.wal.mark_applied(seq)
        frame.pending_lsns.clear()
        frame.dirty = False
        self.flushes += 1
        self._log('FLUSH', page_id)
        return True

    def _evict_if_needed(self):
        if len(self.frames) < self.capacity:
            return None, None
        victim = self.policy.choose_victim(self.frames.keys())
        frame = self.frames[victim]
        flushed = victim if self._flush_frame(victim, frame) else None
        del self.frames[victim]
        self.policy.record_remove(victim)
        self.evictions += 1
        self._log('EVICT', victim, victim_page_id=victim)
        return victim, flushed

    def get_page(self, page_id: int, *, owner=None):
        ctx = self._lock(page_id, 'READ', owner)
        if ctx is None:
            return self._get_page_locked(page_id)
        with ctx:
            return self._get_page_locked(page_id)

    def _get_page_locked(self, page_id):
        with self._guard:
            if page_id in self.frames:
                self.hits += 1
                self.policy.record_access(page_id)
                self._log('HIT', page_id)
                frame = self.frames[page_id]
                return bytes(frame.data), True, frame.dirty
            self.misses += 1
            self._log('MISS', page_id)
            data = self.page_manager.read_page(page_id)
            self._evict_if_needed()
            self.frames[page_id] = BufferFrame(bytearray(data), False)
            self.policy.record_insert(page_id)
            return bytes(data), False, False

    def put_page(self, page_id: int, data: bytes, dirty: bool = True, *, owner=None, tx_id: int = 0):
        ctx = self._lock(page_id, 'WRITE', owner)
        if ctx is None:
            return self._put_page_locked(page_id, data, dirty, tx_id)
        with ctx:
            return self._put_page_locked(page_id, data, dirty, tx_id)

    def _put_page_locked(self, page_id, data, dirty, tx_id):
        data = bytes(data)
        if len(data) != PAGE_SIZE:
            raise StorageError('INVALID_PAGE_SIZE', '写入页必须恰好为 4096 字节', page_id=page_id)
        if not self.page_manager.is_allocated(page_id):
            raise StorageError('PAGE_NOT_ALLOCATED', '页不存在或已经释放', page_id=page_id)
        with self._guard:
            before = bytes(self.frames[page_id].data) if page_id in self.frames else self.page_manager.read_page(page_id)
            pending_seq = None
            if dirty and self.wal is not None and before != data:
                pending_seq = self.wal.append_log(tx_id, page_id, before, data)

            evicted = flushed = None
            if page_id not in self.frames:
                evicted, flushed = self._evict_if_needed()
                self.frames[page_id] = BufferFrame(bytearray(data), bool(dirty))
                self.policy.record_insert(page_id)
            else:
                frame = self.frames[page_id]
                frame.data[:] = data
                frame.dirty = frame.dirty or bool(dirty)
                self.policy.record_access(page_id)
            if pending_seq is not None:
                self.frames[page_id].pending_lsns.append(pending_seq)
            return evicted, flushed

    def flush_page(self, page_id: int, *, owner=None) -> bool:
        ctx = self._lock(page_id, 'WRITE', owner)
        if ctx is None:
            return self._flush_page_locked(page_id)
        with ctx:
            return self._flush_page_locked(page_id)

    def _flush_page_locked(self, page_id):
        with self._guard:
            if page_id not in self.frames:
                raise StorageError('BUFFER_PAGE_NOT_RESIDENT', '页当前不在 Buffer Pool 中', page_id=page_id)
            self._flush_frame(page_id, self.frames[page_id])
            return True

    def flush_all(self):
        flushed = []
        for page_id in list(self.frames.keys()):
            ctx = self._lock(page_id, 'WRITE', None)
            if ctx is None:
                with self._guard:
                    if self._flush_frame(page_id, self.frames[page_id]):
                        flushed.append(page_id)
            else:
                with ctx:
                    with self._guard:
                        if self._flush_frame(page_id, self.frames[page_id]):
                            flushed.append(page_id)
        self.page_manager.sync()
        return flushed

    def discard_page(self, page_id: int):
        with self._guard:
            frame = self.frames.get(page_id)
            if frame is not None and frame.dirty:
                raise StorageError('BUFFER_DIRTY_DISCARD', '不能直接丢弃脏页', page_id=page_id)
            self.frames.pop(page_id, None)
            self.policy.record_remove(page_id)

    def force_discard_page(self, page_id: int):
        with self._guard:
            frame = self.frames.pop(page_id, None)
            if frame is not None and self.wal is not None:
                for seq in frame.pending_lsns:
                    self.wal.mark_applied(seq)
            self.policy.record_remove(page_id)

    def stats(self):
        return {
            'capacity': self.capacity,
            'size': len(self.frames),
            'hits': self.hits,
            'misses': self.misses,
            'evictions': self.evictions,
            'flushes': self.flushes,
            'policy': self.policy.name,
        }

    @staticmethod
    def _fields(request, allowed):
        unknown = set(request) - set(allowed)
        if unknown:
            raise StorageError('INVALID_REQUEST', f'请求包含未定义字段: {sorted(unknown)}')

    @staticmethod
    def _decode(data, page_id=None):
        if not isinstance(data, str):
            raise StorageError('INVALID_PAGE_DATA', 'data 必须是 Base64 字符串', page_id=page_id)
        try:
            raw = base64.b64decode(data, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise StorageError('INVALID_PAGE_DATA', 'data 不是合法 Base64', page_id=page_id) from exc
        if len(raw) != PAGE_SIZE:
            raise StorageError('INVALID_PAGE_SIZE', 'Base64 解码后必须恰好为 4096 字节', page_id=page_id)
        return raw

    def handle(self, request):
        """Standalone JSON API matching section 3 of paged-storage-spec.md."""
        try:
            if not isinstance(request, dict):
                raise StorageError('INVALID_REQUEST', '请求必须是 JSON 对象')
            op = request.get('op')
            if op == 'get_page':
                self._fields(request, {'op', 'pageId'})
                page_id = request.get('pageId'); data, hit, dirty = self.get_page(page_id)
                result = {'pageId': page_id, 'data': base64.b64encode(data).decode('ascii'), 'hit': hit, 'dirty': dirty}
            elif op == 'put_page':
                self._fields(request, {'op', 'pageId', 'data', 'dirty'})
                page_id = request.get('pageId'); dirty = request.get('dirty')
                if not isinstance(dirty, bool):
                    raise StorageError('INVALID_REQUEST', 'dirty 必须是布尔值', page_id=page_id)
                evicted, flushed = self.put_page(page_id, self._decode(request.get('data'), page_id), dirty=dirty)
                result = {'pageId': page_id, 'evicted': evicted, 'flushedPageId': flushed}
            elif op == 'flush_page':
                self._fields(request, {'op', 'pageId'}); page_id = request.get('pageId'); self.flush_page(page_id)
                result = {'pageId': page_id, 'flushed': True}
            elif op == 'flush_all':
                self._fields(request, {'op'}); result = {'flushedPageIds': self.flush_all()}
            elif op == 'stats':
                self._fields(request, {'op'}); result = self.stats()
            else:
                raise StorageError('UNSUPPORTED_OPERATION', f'不支持的 Buffer Pool 操作: {op}')
            return {'ok': True, 'data': result}
        except StorageError as exc:
            return {'ok': False, 'error': exc.to_dict()}

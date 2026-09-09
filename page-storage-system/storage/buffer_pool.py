from dataclasses import dataclass
from typing import Dict

from .constants import PAGE_SIZE
from .errors import StorageError
from .page_manager import PageManager
from .replacement import make_policy


@dataclass
class BufferFrame:
    data: bytearray
    dirty: bool = False


class BufferPool:
    def __init__(self, page_manager: PageManager, capacity: int = 16, policy: str = "LRU", log: bool = True):
        if not isinstance(capacity, int) or isinstance(capacity, bool) or capacity <= 0:
            raise StorageError("BUFFER_INVALID_CAPACITY", "capacity 必须是正整数")
        self.page_manager = page_manager
        self.capacity = capacity
        self.policy = make_policy(policy)
        self.log_enabled = bool(log)
        self.frames: Dict[int, BufferFrame] = {}
        self.hits = 0
        self.misses = 0
        self.evictions = 0
        self.flushes = 0
        self.events = []

    def _log(self, event: str, page_id: int, victim_page_id=None):
        if self.log_enabled:
            self.events.append({"event": event, "pageId": page_id, "victimPageId": victim_page_id})

    def _evict_if_needed(self):
        if len(self.frames) < self.capacity:
            return None, None
        victim = self.policy.choose_victim(self.frames.keys())
        frame = self.frames[victim]
        flushed = None
        if frame.dirty:
            self.page_manager.write_page(victim, bytes(frame.data))
            frame.dirty = False
            self.flushes += 1
            flushed = victim
            self._log("FLUSH", victim)
        del self.frames[victim]
        self.policy.record_remove(victim)
        self.evictions += 1
        self._log("EVICT", victim, victim_page_id=victim)
        return victim, flushed

    def get_page(self, page_id: int):
        if page_id in self.frames:
            self.hits += 1
            self.policy.record_access(page_id)
            self._log("HIT", page_id)
            frame = self.frames[page_id]
            return bytes(frame.data), True, frame.dirty

        self.misses += 1
        self._log("MISS", page_id)
        self._evict_if_needed()
        data = self.page_manager.read_page(page_id)
        self.frames[page_id] = BufferFrame(bytearray(data), False)
        self.policy.record_insert(page_id)
        return bytes(data), False, False

    def put_page(self, page_id: int, data: bytes, dirty: bool = True):
        data = bytes(data)
        if len(data) != PAGE_SIZE:
            raise StorageError("INVALID_PAGE_SIZE", "写入页必须恰好为 4096 字节", page_id=page_id)
        if not self.page_manager.is_allocated(page_id):
            raise StorageError("PAGE_NOT_ALLOCATED", "页不存在或已经释放", page_id=page_id)

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
        return evicted, flushed

    def flush_page(self, page_id: int) -> bool:
        if page_id not in self.frames:
            raise StorageError("BUFFER_PAGE_NOT_RESIDENT", "页当前不在 Buffer Pool 中", page_id=page_id)
        frame = self.frames[page_id]
        if frame.dirty:
            self.page_manager.write_page(page_id, bytes(frame.data))
            frame.dirty = False
            self.flushes += 1
            self._log("FLUSH", page_id)
        return True

    def flush_all(self):
        flushed = []
        for page_id in list(self.frames.keys()):
            frame = self.frames[page_id]
            if frame.dirty:
                self.page_manager.write_page(page_id, bytes(frame.data))
                frame.dirty = False
                self.flushes += 1
                self._log("FLUSH", page_id)
                flushed.append(page_id)
        self.page_manager.sync()
        return flushed

    def discard_page(self, page_id: int):
        self.frames.pop(page_id, None)
        self.policy.record_remove(page_id)

    def stats(self):
        return {
            "capacity": self.capacity,
            "size": len(self.frames),
            "hits": self.hits,
            "misses": self.misses,
            "evictions": self.evictions,
            "flushes": self.flushes,
            "policy": self.policy.name,
        }

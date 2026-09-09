import threading
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Dict, Optional

from .errors import StorageError


@dataclass
class _PageLockState:
    readers: Dict[str, int] = field(default_factory=dict)
    writer: Optional[str] = None
    writer_count: int = 0


class LockManager:
    """Non-blocking page-level shared/exclusive lock manager.

    Public lock_page returns wait=True on conflict. BufferPool uses acquire_or_raise
    to protect read/write/flush critical sections.
    """

    def __init__(self):
        self._guard = threading.RLock()
        self._states: Dict[int, _PageLockState] = {}

    @staticmethod
    def _validate(page_id, mode, owner):
        if not isinstance(page_id, int) or isinstance(page_id, bool) or page_id < 0:
            raise StorageError('INVALID_PAGE_ID', 'pageId 必须是非负整数', page_id=page_id)
        mode = str(mode).upper()
        if mode not in {'READ', 'WRITE'}:
            raise StorageError('LOCK_INVALID_MODE', 'mode 只支持 READ 或 WRITE', page_id=page_id)
        if not isinstance(owner, str) or not owner.strip():
            raise StorageError('LOCK_INVALID_OWNER', 'owner 不能为空', page_id=page_id)
        return mode, owner.strip()

    def lock_page(self, page_id: int, mode: str, owner: str):
        mode, owner = self._validate(page_id, mode, owner)
        with self._guard:
            state = self._states.setdefault(page_id, _PageLockState())
            if mode == 'READ':
                if state.writer is None or state.writer == owner:
                    state.readers[owner] = state.readers.get(owner, 0) + 1
                    return {'granted': True}
                return {'granted': False, 'wait': True}

            if state.writer == owner:
                state.writer_count += 1
                return {'granted': True}
            other_readers = [reader for reader, count in state.readers.items() if reader != owner and count > 0]
            if state.writer is None and not other_readers:
                state.writer = owner
                state.writer_count = 1
                return {'granted': True}
            return {'granted': False, 'wait': True}

    def release_mode(self, page_id: int, owner: str, mode: str):
        mode, owner = self._validate(page_id, mode, owner)
        with self._guard:
            state = self._states.get(page_id)
            if state is None:
                raise StorageError('LOCK_NOT_HELD', '该页没有锁', page_id=page_id)
            if mode == 'WRITE':
                if state.writer != owner or state.writer_count <= 0:
                    raise StorageError('LOCK_NOT_OWNER', '解锁者不是 WRITE 锁持有者', page_id=page_id)
                state.writer_count -= 1
                if state.writer_count == 0:
                    state.writer = None
            else:
                count = state.readers.get(owner, 0)
                if count <= 0:
                    raise StorageError('LOCK_NOT_OWNER', '解锁者不是 READ 锁持有者', page_id=page_id)
                if count == 1:
                    state.readers.pop(owner, None)
                else:
                    state.readers[owner] = count - 1
            self._cleanup(page_id, state)
        return {'released': True}

    def unlock_page(self, page_id: int, owner: str):
        if not isinstance(owner, str) or not owner.strip():
            raise StorageError('LOCK_INVALID_OWNER', 'owner 不能为空', page_id=page_id)
        owner = owner.strip()
        with self._guard:
            state = self._states.get(page_id)
            if state is None:
                raise StorageError('LOCK_NOT_HELD', '该页没有锁', page_id=page_id)
            if state.writer == owner and state.writer_count > 0:
                state.writer_count -= 1
                if state.writer_count == 0:
                    state.writer = None
            elif state.readers.get(owner, 0) > 0:
                count = state.readers[owner]
                if count == 1:
                    state.readers.pop(owner, None)
                else:
                    state.readers[owner] = count - 1
            else:
                raise StorageError('LOCK_NOT_OWNER', '解锁者不是锁持有者', page_id=page_id)
            self._cleanup(page_id, state)
        return {'released': True}

    def _cleanup(self, page_id, state):
        if state.writer is None and not state.readers:
            self._states.pop(page_id, None)

    def acquire_or_raise(self, page_id: int, mode: str, owner: str):
        result = self.lock_page(page_id, mode, owner)
        if not result['granted']:
            raise StorageError('PAGE_LOCK_BUSY', f'pageId {page_id} 当前无法获得 {mode} 锁', page_id=page_id)

    @contextmanager
    def held(self, page_id: int, mode: str, owner: str):
        self.acquire_or_raise(page_id, mode, owner)
        try:
            yield
        finally:
            self.release_mode(page_id, owner, mode)

    def snapshot(self, page_id: int):
        with self._guard:
            state = self._states.get(page_id, _PageLockState())
            return {
                'pageId': page_id,
                'readers': sorted([owner for owner, count in state.readers.items() if count > 0]),
                'writer': state.writer,
            }

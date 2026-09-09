import base64
import binascii
import json
import os
from pathlib import Path
from typing import Union

from .constants import PAGE_SIZE
from .errors import StorageError
from .file_manager import FileManager


class PageManager:
    """Owns persistent page allocation state and validates every page access."""

    def __init__(self, root: Union[str, Path]):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.file_manager = FileManager(self.root / 'pages.dat')
        self.meta_path = self.root / 'page_allocation.json'
        self._allocated = set()
        self._free = []
        self._load_or_initialize()
        self.file_manager.set_allocation_validator(self.is_allocated)

    def _load_or_initialize(self):
        if not self.meta_path.exists():
            self._persist(); return
        try:
            state = json.loads(self.meta_path.read_text(encoding='utf-8'))
            self._allocated = {int(v) for v in state.get('allocated', [])}
            self._free = sorted({int(v) for v in state.get('free', [])})
            if self._allocated & set(self._free):
                raise ValueError('allocated/free overlap')
            if any(v < 0 for v in self._allocated) or any(v < 0 for v in self._free):
                raise ValueError('negative page id')
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as exc:
            raise StorageError('PAGE_METADATA_CORRUPT', f'页分配元数据损坏: {exc}') from exc

    def _persist(self):
        state = {'allocated': sorted(self._allocated), 'free': sorted(self._free)}
        tmp = self.meta_path.with_suffix('.tmp')
        try:
            tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')
            with tmp.open('rb') as fp:
                os.fsync(fp.fileno())
            os.replace(tmp, self.meta_path)
        except OSError as exc:
            raise StorageError('FILE_IO_ERROR', f'持久化页分配状态失败: {exc}') from exc

    def is_allocated(self, page_id: int) -> bool:
        return isinstance(page_id, int) and not isinstance(page_id, bool) and page_id in self._allocated

    def _require_allocated(self, page_id: int):
        if not isinstance(page_id, int) or isinstance(page_id, bool) or page_id < 0:
            raise StorageError('INVALID_PAGE_ID', 'pageId 必须是非负整数', page_id=page_id)
        if page_id not in self._allocated:
            raise StorageError('PAGE_NOT_ALLOCATED', '页不存在或已经释放', page_id=page_id)

    def allocate_page(self) -> int:
        reused = bool(self._free)
        if reused:
            page_id = self._free.pop(0)
            self.file_manager.write_at(page_id, bytes(PAGE_SIZE))
        else:
            page_id = self.file_manager.append_zero_page()
        self._allocated.add(page_id)
        try:
            self._persist()
        except Exception:
            self._allocated.discard(page_id)
            if reused and page_id not in self._free:
                self._free.append(page_id); self._free.sort()
            raise
        return page_id

    def free_page(self, page_id: int) -> None:
        self._require_allocated(page_id)
        self._allocated.remove(page_id)
        self._free.append(page_id); self._free.sort()
        try:
            self._persist()
        except Exception:
            self._free.remove(page_id); self._allocated.add(page_id); raise

    def read_page(self, page_id: int) -> bytes:
        self._require_allocated(page_id)
        return self.file_manager.read_at(page_id)

    def write_page(self, page_id: int, data: bytes) -> int:
        self._require_allocated(page_id)
        data = bytes(data)
        if len(data) != PAGE_SIZE:
            raise StorageError('INVALID_PAGE_SIZE', '写入页必须恰好为 4096 字节', page_id=page_id)
        return self.file_manager.write_at(page_id, data)

    def sync(self):
        self.file_manager.sync()

    @property
    def allocated_count(self) -> int:
        return len(self._allocated)

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
        try:
            if not isinstance(request, dict):
                raise StorageError('INVALID_REQUEST', '请求必须是 JSON 对象')
            op = request.get('op')
            if op == 'allocate_page':
                self._fields(request, {'op'}); result = {'pageId': self.allocate_page()}
            elif op == 'free_page':
                self._fields(request, {'op', 'pageId'}); page_id = request.get('pageId'); self.free_page(page_id); result = {'pageId': page_id, 'freed': True}
            elif op == 'read_page':
                self._fields(request, {'op', 'pageId'}); page_id = request.get('pageId')
                result = {'pageId': page_id, 'data': base64.b64encode(self.read_page(page_id)).decode('ascii')}
            elif op == 'write_page':
                self._fields(request, {'op', 'pageId', 'data'}); page_id = request.get('pageId')
                result = {'pageId': page_id, 'written': self.write_page(page_id, self._decode(request.get('data'), page_id))}
            else:
                raise StorageError('UNSUPPORTED_OPERATION', f'不支持的 Page Manager 操作: {op}')
            return {'ok': True, 'data': result}
        except StorageError as exc:
            return {'ok': False, 'error': exc.to_dict()}

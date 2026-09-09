import base64
import binascii
from pathlib import Path
from typing import Any, Dict, Union

from .bplus_tree import BPlusTreeManager
from .buffer_pool import BufferPool
from .constants import DEFAULT_BUFFER_CAPACITY, PAGE_SIZE
from .errors import StorageError
from .lock_manager import LockManager
from .page_manager import PageManager
from .slotted_page import SlottedPage
from .table_page_map import TablePageMap
from .wal import WALManager


class StorageManager:
    """Unified page-storage API used by the database Storage Engine."""

    def __init__(self, root: Union[str, Path], *, capacity: int = DEFAULT_BUFFER_CAPACITY,
                 policy: str = 'LRU', log: bool = True, recover_on_start: bool = True):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.pages = PageManager(self.root)
        self.wal = WALManager(self.root)
        self.last_recovery = self.wal.recover(self.pages) if recover_on_start else {'replayed': 0, 'undone': 0, 'clean': True}
        self.locks = LockManager()
        self.buffer = BufferPool(self.pages, capacity=capacity, policy=policy, log=log,
                                 wal=self.wal, lock_manager=self.locks)
        self.table_map = TablePageMap(self.root)
        self.indexes = BPlusTreeManager(self.root, self.pages, self.buffer)

    # ----- Core page / table interface -----
    def get_page(self, page_id: int, *, owner=None):
        data, hit, dirty = self.buffer.get_page(page_id, owner=owner)
        return {'pageId': page_id, 'data': data, 'hit': hit, 'dirty': dirty}

    def write_page(self, page_id: int, data: bytes, *, owner=None, tx_id: int = 0):
        self.buffer.put_page(page_id, data, dirty=True, owner=owner, tx_id=tx_id)
        return {'pageId': page_id, 'dirty': True}

    def create_table_pages(self, table: str):
        return {'table': table.strip().lower(), 'pageIds': self.table_map.create(table)}

    def allocate_page_for_table(self, table: str):
        self.table_map.list_pages(table)
        page_id = self.pages.allocate_page()
        try:
            page_ids = self.table_map.append(table, page_id)
        except Exception:
            self.pages.free_page(page_id)
            raise
        return {'table': table.strip().lower(), 'pageId': page_id, 'pageIds': page_ids}

    def list_table_pages(self, table: str):
        return {'table': table.strip().lower(), 'pageIds': self.table_map.list_pages(table)}

    def drop_table_pages(self, table: str):
        page_ids = self.table_map.list_pages(table)
        for page_id in page_ids:
            if page_id in self.buffer.frames:
                if self.buffer.frames[page_id].dirty:
                    self.buffer.flush_page(page_id)
                self.buffer.discard_page(page_id)
        removed = self.table_map.remove(table)
        freed = []
        for page_id in removed:
            self.pages.free_page(page_id)
            freed.append(page_id)
        return {'table': table.strip().lower(), 'removed': True, 'freedPageIds': freed}

    def flush_page(self, page_id: int, *, owner=None):
        self.buffer.flush_page(page_id, owner=owner)
        return {'pageId': page_id, 'flushed': True}

    def flush_all(self):
        return {'flushedPageIds': self.buffer.flush_all()}

    def storage_stats(self):
        result = self.buffer.stats()
        result['allocatedPages'] = self.pages.allocated_count
        return result

    def set_policy(self, policy: str):
        return self.buffer.set_policy(policy)

    def buffer_events(self, *, clear=False):
        return {'events': self.buffer.get_events(clear=bool(clear))}

    # ----- Slotted page records -----
    def insert_record(self, page_id: int, row: Dict[str, Any], *, owner=None, tx_id: int = 0):
        raw, _, _ = self.buffer.get_page(page_id, owner=owner)
        page = SlottedPage.from_bytes(raw)
        slot_id, free_bytes = page.insert_record(row)
        self.buffer.put_page(page_id, page.to_bytes(), dirty=True, owner=owner, tx_id=tx_id)
        return {'pageId': page_id, 'slotId': slot_id, 'freeBytes': free_bytes}

    def read_record(self, page_id: int, slot_id: int, *, owner=None):
        raw, _, _ = self.buffer.get_page(page_id, owner=owner)
        return {'row': SlottedPage.from_bytes(raw).read_record(slot_id), 'deleted': False}

    def delete_record(self, page_id: int, slot_id: int, *, owner=None, tx_id: int = 0):
        raw, _, _ = self.buffer.get_page(page_id, owner=owner)
        page = SlottedPage.from_bytes(raw)
        page.delete_record(slot_id)
        self.buffer.put_page(page_id, page.to_bytes(), dirty=True, owner=owner, tx_id=tx_id)
        return {'pageId': page_id, 'slotId': slot_id, 'deleted': True}

    def scan_records(self, page_id: int, *, owner=None):
        raw, _, _ = self.buffer.get_page(page_id, owner=owner)
        records = [{'rowId': {'pageId': page_id, 'slotId': slot_id}, 'row': row}
                   for slot_id, row in SlottedPage.from_bytes(raw).iter_records()]
        return {'pageId': page_id, 'records': records}

    # ----- B+ tree -----
    def create_index(self, index_id: int, *, unique=True, key_type=None):
        return self.indexes.create_index(index_id, unique=unique, key_type=key_type)

    def drop_index(self, index_id: int):
        return self.indexes.drop_index(index_id)

    def index_search(self, index_id: int, key):
        return self.indexes.index_search(index_id, key)

    def index_range(self, index_id: int, start=None, end=None):
        return self.indexes.index_range(index_id, start, end)

    def index_insert(self, index_id: int, key, row_id):
        return self.indexes.index_insert(index_id, key, row_id)

    def index_delete(self, index_id: int, key, row_id=None):
        return self.indexes.index_delete(index_id, key, row_id)

    # ----- WAL / recovery -----
    def append_log(self, tx_id: int, page_id: int, before: bytes, after: bytes):
        return {'logSeq': self.wal.append_log(tx_id, page_id, before, after), 'durable': True}

    def recover(self):
        if any(frame.dirty for frame in self.buffer.frames.values()):
            raise StorageError('RECOVERY_DIRTY_BUFFER', '运行时 recover 前必须先 flush_all')
        for page_id in list(self.buffer.frames):
            self.buffer.discard_page(page_id)
        self.last_recovery = self.wal.recover(self.pages)
        return self.last_recovery

    # ----- Page locks -----
    def lock_page(self, page_id: int, mode: str, owner: str):
        self.pages._require_allocated(page_id)
        return self.locks.lock_page(page_id, mode, owner)

    def unlock_page(self, page_id: int, owner: str):
        return self.locks.unlock_page(page_id, owner)

    # ----- Low-level OS testing helpers -----
    def allocate_page(self):
        return {'pageId': self.pages.allocate_page()}

    def free_page(self, page_id: int):
        if page_id in self.buffer.frames:
            if self.buffer.frames[page_id].dirty:
                self.buffer.flush_page(page_id)
            self.buffer.discard_page(page_id)
        self.pages.free_page(page_id)
        return {'pageId': page_id, 'freed': True}

    @staticmethod
    def _decode_page(data: str, page_id=None) -> bytes:
        if not isinstance(data, str):
            raise StorageError('INVALID_PAGE_DATA', 'data 必须是 Base64 字符串', page_id=page_id)
        try:
            decoded = base64.b64decode(data, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise StorageError('INVALID_PAGE_DATA', 'data 不是合法 Base64', page_id=page_id) from exc
        if len(decoded) != PAGE_SIZE:
            raise StorageError('INVALID_PAGE_SIZE', 'Base64 解码后必须恰好为 4096 字节', page_id=page_id)
        return decoded

    @staticmethod
    def _encode_page(data: bytes) -> str:
        return base64.b64encode(bytes(data)).decode('ascii')

    @staticmethod
    def _check_fields(request: Dict[str, Any], allowed):
        unknown = set(request) - set(allowed)
        if unknown:
            raise StorageError('INVALID_REQUEST', f'请求包含未定义字段: {sorted(unknown)}')

    @staticmethod
    def _owner(request):
        owner = request.get('owner')
        if owner is not None and (not isinstance(owner, str) or not owner.strip()):
            raise StorageError('LOCK_INVALID_OWNER', 'owner 必须是非空字符串')
        return owner

    def handle(self, request: Dict[str, Any]):
        request_id = request.get('requestId') if isinstance(request, dict) else None
        try:
            if not isinstance(request, dict):
                raise StorageError('INVALID_REQUEST', '请求必须是 JSON 对象')
            if request_id is not None and (not isinstance(request_id, str) or not request_id):
                raise StorageError('INVALID_REQUEST', 'requestId 必须是非空字符串')
            op = request.get('op')
            if not isinstance(op, str) or not op:
                raise StorageError('INVALID_REQUEST', '缺少 op')

            if op == 'get_page':
                self._check_fields(request, {'requestId', 'op', 'pageId', 'owner'})
                result = self.get_page(request.get('pageId'), owner=self._owner(request)); result['data'] = self._encode_page(result['data'])
            elif op == 'write_page':
                self._check_fields(request, {'requestId', 'op', 'pageId', 'data', 'owner', 'txId'})
                page_id = request.get('pageId')
                result = self.write_page(page_id, self._decode_page(request.get('data'), page_id), owner=self._owner(request), tx_id=request.get('txId', 0))
            elif op == 'create_table_pages':
                self._check_fields(request, {'requestId', 'op', 'table'}); result = self.create_table_pages(request.get('table'))
            elif op == 'drop_table_pages':
                self._check_fields(request, {'requestId', 'op', 'table'}); result = self.drop_table_pages(request.get('table'))
            elif op == 'allocate_page_for_table':
                self._check_fields(request, {'requestId', 'op', 'table'}); result = self.allocate_page_for_table(request.get('table'))
            elif op == 'list_table_pages':
                self._check_fields(request, {'requestId', 'op', 'table'}); result = self.list_table_pages(request.get('table'))
            elif op == 'flush_page':
                self._check_fields(request, {'requestId', 'op', 'pageId', 'owner'}); result = self.flush_page(request.get('pageId'), owner=self._owner(request))
            elif op == 'flush_all':
                self._check_fields(request, {'requestId', 'op'}); result = self.flush_all()
            elif op == 'storage_stats':
                self._check_fields(request, {'requestId', 'op'}); result = self.storage_stats()
            elif op == 'set_policy':
                self._check_fields(request, {'requestId', 'op', 'policy'}); result = self.set_policy(request.get('policy'))
            elif op == 'buffer_events':
                self._check_fields(request, {'requestId', 'op', 'clear'}); result = self.buffer_events(clear=request.get('clear', False))
            elif op == 'insert_record':
                self._check_fields(request, {'requestId', 'op', 'pageId', 'row', 'owner', 'txId'}); result = self.insert_record(request.get('pageId'), request.get('row'), owner=self._owner(request), tx_id=request.get('txId', 0))
            elif op == 'read_record':
                self._check_fields(request, {'requestId', 'op', 'pageId', 'slotId', 'owner'}); result = self.read_record(request.get('pageId'), request.get('slotId'), owner=self._owner(request))
            elif op == 'delete_record':
                self._check_fields(request, {'requestId', 'op', 'pageId', 'slotId', 'owner', 'txId'}); result = self.delete_record(request.get('pageId'), request.get('slotId'), owner=self._owner(request), tx_id=request.get('txId', 0))
            elif op == 'scan_records':
                self._check_fields(request, {'requestId', 'op', 'pageId', 'owner'}); result = self.scan_records(request.get('pageId'), owner=self._owner(request))
            elif op == 'create_index':
                self._check_fields(request, {'requestId', 'op', 'indexId', 'unique', 'keyType'}); result = self.create_index(request.get('indexId'), unique=request.get('unique', True), key_type=request.get('keyType'))
            elif op == 'drop_index':
                self._check_fields(request, {'requestId', 'op', 'indexId'}); result = self.drop_index(request.get('indexId'))
            elif op == 'index_search':
                self._check_fields(request, {'requestId', 'op', 'indexId', 'key'}); result = self.index_search(request.get('indexId'), request.get('key'))
            elif op == 'index_range':
                self._check_fields(request, {'requestId', 'op', 'indexId', 'start', 'end'}); result = self.index_range(request.get('indexId'), request.get('start'), request.get('end'))
            elif op == 'index_insert':
                self._check_fields(request, {'requestId', 'op', 'indexId', 'key', 'rowId'}); result = self.index_insert(request.get('indexId'), request.get('key'), request.get('rowId'))
            elif op == 'index_delete':
                self._check_fields(request, {'requestId', 'op', 'indexId', 'key', 'rowId'}); result = self.index_delete(request.get('indexId'), request.get('key'), request.get('rowId'))
            elif op == 'append_log':
                self._check_fields(request, {'requestId', 'op', 'txId', 'pageId', 'before', 'after'})
                page_id = request.get('pageId')
                result = self.append_log(request.get('txId'), page_id, self._decode_page(request.get('before'), page_id), self._decode_page(request.get('after'), page_id))
            elif op == 'recover':
                self._check_fields(request, {'requestId', 'op'}); result = self.recover()
            elif op == 'lock_page':
                self._check_fields(request, {'requestId', 'op', 'pageId', 'mode', 'owner'}); result = self.lock_page(request.get('pageId'), request.get('mode'), request.get('owner'))
            elif op == 'unlock_page':
                self._check_fields(request, {'requestId', 'op', 'pageId', 'owner'}); result = self.unlock_page(request.get('pageId'), request.get('owner'))
            elif op == 'allocate_page':
                self._check_fields(request, {'requestId', 'op'}); result = self.allocate_page()
            elif op == 'free_page':
                self._check_fields(request, {'requestId', 'op', 'pageId'}); result = self.free_page(request.get('pageId'))
            else:
                raise StorageError('UNSUPPORTED_OPERATION', f'不支持的操作: {op}')

            if request_id is not None:
                result = {'requestId': request_id, **result}
            return {'ok': True, 'data': result}
        except StorageError as exc:
            error = exc.to_dict()
            error.update({'requestId': request_id, 'statementIndex': None, 'line': None, 'column': None})
            return {'ok': False, 'error': error}
        except Exception as exc:
            error = StorageError('STORAGE_INTERNAL_ERROR', f'存储系统内部错误: {exc}').to_dict()
            error.update({'requestId': request_id, 'statementIndex': None, 'line': None, 'column': None})
            return {'ok': False, 'error': error}

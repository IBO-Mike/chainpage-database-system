import bisect
import json
import os
import struct
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple, Union

from .constants import PAGE_SIZE
BPLUS_PAGE_MAGIC = b'CPB1'
from .errors import StorageError

_NODE_HEADER = struct.Struct('>4sI')


class BPlusTreeManager:
    """Persistent page-backed B+ tree index manager."""

    def __init__(self, root: Union[str, Path], page_manager, buffer_pool, max_keys: int = 16):
        if max_keys < 4:
            raise ValueError('max_keys must be >= 4')
        self.root = Path(root)
        self.meta_path = self.root / 'indexes.json'
        self.page_manager = page_manager
        self.buffer = buffer_pool
        self.max_keys = max_keys
        self.indexes: Dict[str, Dict[str, Any]] = {}
        self._load()

    def _load(self):
        if not self.meta_path.exists():
            self._persist(); return
        try:
            raw = json.loads(self.meta_path.read_text(encoding='utf-8'))
            if not isinstance(raw, dict): raise ValueError('root must be object')
            self.indexes = raw
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as exc:
            raise StorageError('INDEX_METADATA_CORRUPT', f'索引元数据损坏: {exc}') from exc

    def _persist(self):
        self.root.mkdir(parents=True, exist_ok=True)
        tmp = self.meta_path.with_suffix('.tmp')
        try:
            tmp.write_text(json.dumps(self.indexes, ensure_ascii=False, indent=2), encoding='utf-8')
            with tmp.open('rb') as fp: os.fsync(fp.fileno())
            os.replace(tmp, self.meta_path)
        except OSError as exc:
            raise StorageError('FILE_IO_ERROR', f'持久化索引元数据失败: {exc}') from exc

    @staticmethod
    def _key(index_id: int) -> str:
        if not isinstance(index_id, int) or isinstance(index_id, bool) or index_id < 0:
            raise StorageError('INDEX_INVALID_ID', 'indexId 必须是非负整数')
        return str(index_id)

    @staticmethod
    def _rowid(row_id):
        if not isinstance(row_id, dict) or set(row_id) != {'pageId', 'slotId'}:
            raise StorageError('INDEX_INVALID_ROWID', 'RowId 必须包含 pageId 与 slotId')
        page_id, slot_id = row_id['pageId'], row_id['slotId']
        if not isinstance(page_id, int) or isinstance(page_id, bool) or page_id < 0:
            raise StorageError('INDEX_INVALID_ROWID', 'RowId.pageId 非法')
        if not isinstance(slot_id, int) or isinstance(slot_id, bool) or slot_id < 0:
            raise StorageError('INDEX_INVALID_ROWID', 'RowId.slotId 非法')
        return {'pageId': page_id, 'slotId': slot_id}

    @staticmethod
    def _key_type(value):
        if isinstance(value, bool) or not isinstance(value, (int, str)):
            raise StorageError('INDEX_KEY_TYPE_ERROR', '索引键只支持 INT 或 VARCHAR')
        return 'INT' if isinstance(value, int) else 'VARCHAR'

    def _meta(self, index_id: int):
        key = self._key(index_id)
        if key not in self.indexes:
            raise StorageError('INDEX_NOT_FOUND', f'indexId {index_id} 不存在')
        return self.indexes[key]

    def create_index(self, index_id: int, *, unique: bool = True, key_type: Optional[str] = None):
        key = self._key(index_id)
        if key in self.indexes:
            raise StorageError('INDEX_EXISTS', f'indexId {index_id} 已存在')
        if key_type is not None:
            key_type = str(key_type).upper()
            if key_type not in {'INT', 'VARCHAR'}:
                raise StorageError('INDEX_KEY_TYPE_ERROR', 'keyType 只支持 INT 或 VARCHAR')
        root_page = self.page_manager.allocate_page()
        self._write_node(root_page, self._new_leaf())
        self.indexes[key] = {'rootPageId': root_page, 'unique': bool(unique), 'keyType': key_type}
        try:
            self._persist()
        except Exception:
            self.buffer.force_discard_page(root_page)
            self.page_manager.free_page(root_page)
            raise
        return {'indexId': index_id, 'rootPageId': root_page, 'unique': bool(unique), 'keyType': key_type}

    def drop_index(self, index_id: int):
        meta = self._meta(index_id)
        pages = self._collect_pages(meta['rootPageId'])
        for page_id in pages:
            self.buffer.force_discard_page(page_id)
        for page_id in pages:
            self.page_manager.free_page(page_id)
        del self.indexes[self._key(index_id)]
        self._persist()
        return {'indexId': index_id, 'removed': True, 'freedPageIds': pages}

    @staticmethod
    def _new_leaf(prev=None, next_=None):
        return {'leaf': True, 'keys': [], 'values': [], 'prev': prev, 'next': next_}

    def _encode_node(self, node: Dict[str, Any]) -> bytes:
        raw = json.dumps(node, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        if len(raw) + _NODE_HEADER.size > PAGE_SIZE:
            raise StorageError('INDEX_PAGE_OVERFLOW', 'B+ 树节点超过单页 4096 字节')
        page = bytearray(PAGE_SIZE)
        _NODE_HEADER.pack_into(page, 0, BPLUS_PAGE_MAGIC, len(raw))
        page[_NODE_HEADER.size:_NODE_HEADER.size + len(raw)] = raw
        return bytes(page)

    def _decode_node(self, data: bytes, page_id=None):
        if len(data) != PAGE_SIZE:
            raise StorageError('INDEX_PAGE_CORRUPT', '索引页长度错误', page_id=page_id)
        try:
            magic, length = _NODE_HEADER.unpack_from(data, 0)
            if magic != BPLUS_PAGE_MAGIC or length <= 0 or length > PAGE_SIZE - _NODE_HEADER.size:
                raise ValueError('header')
            node = json.loads(data[_NODE_HEADER.size:_NODE_HEADER.size + length].decode('utf-8'))
        except Exception as exc:
            raise StorageError('INDEX_PAGE_CORRUPT', 'B+ 树索引页损坏', page_id=page_id) from exc
        if not isinstance(node, dict) or not isinstance(node.get('leaf'), bool) or not isinstance(node.get('keys'), list):
            raise StorageError('INDEX_PAGE_CORRUPT', 'B+ 树节点结构无效', page_id=page_id)
        if node['leaf']:
            if len(node.get('values', [])) != len(node['keys']):
                raise StorageError('INDEX_PAGE_CORRUPT', '叶节点 keys/values 数量不匹配', page_id=page_id)
        elif len(node.get('children', [])) != len(node['keys']) + 1:
            raise StorageError('INDEX_PAGE_CORRUPT', '内部节点 children 数量无效', page_id=page_id)
        return node

    def _read_node(self, page_id: int):
        data, _, _ = self.buffer.get_page(page_id, owner='bptree')
        return self._decode_node(data, page_id=page_id)

    def _write_node(self, page_id: int, node):
        self.buffer.put_page(page_id, self._encode_node(node), dirty=True, owner='bptree', tx_id=0)

    def _validate_key(self, meta, key):
        actual = self._key_type(key)
        expected = meta.get('keyType')
        if expected is None:
            meta['keyType'] = actual
            self._persist()
        elif actual != expected:
            raise StorageError('INDEX_KEY_TYPE_ERROR', f'索引键期望 {expected}，实际 {actual}')

    def _find_leaf(self, root_page: int, key) -> Tuple[int, Dict, List[Tuple[int, int]]]:
        page_id = root_page
        path = []
        while True:
            node = self._read_node(page_id)
            if node['leaf']:
                return page_id, node, path
            child_idx = bisect.bisect_right(node['keys'], key)
            path.append((page_id, child_idx))
            page_id = node['children'][child_idx]

    def index_search(self, index_id: int, key):
        meta = self._meta(index_id); self._validate_key(meta, key)
        _, leaf, _ = self._find_leaf(meta['rootPageId'], key)
        pos = bisect.bisect_left(leaf['keys'], key)
        if pos < len(leaf['keys']) and leaf['keys'][pos] == key:
            return {'rowIds': [dict(v) for v in leaf['values'][pos]]}
        return {'rowIds': []}

    def index_range(self, index_id: int, start=None, end=None):
        meta = self._meta(index_id)
        if start is not None: self._validate_key(meta, start)
        if end is not None: self._validate_key(meta, end)
        if start is not None and end is not None and start > end:
            raise StorageError('INDEX_INVALID_RANGE', 'start 不能大于 end')
        if start is None:
            page_id = meta['rootPageId']
            node = self._read_node(page_id)
            while not node['leaf']:
                page_id = node['children'][0]; node = self._read_node(page_id)
        else:
            page_id, node, _ = self._find_leaf(meta['rootPageId'], start)
        rows = []
        while True:
            for key, values in zip(node['keys'], node['values']):
                if start is not None and key < start: continue
                if end is not None and key > end: return {'rowIds': rows}
                rows.extend(dict(v) for v in values)
            if node.get('next') is None: break
            page_id = node['next']; node = self._read_node(page_id)
        return {'rowIds': rows}

    def index_insert(self, index_id: int, key, row_id):
        meta = self._meta(index_id); self._validate_key(meta, key); row_id = self._rowid(row_id)
        promoted = self._insert_recursive(meta['rootPageId'], key, row_id, bool(meta.get('unique', True)))
        if promoted is not None:
            promoted_key, right_page = promoted
            old_root = meta['rootPageId']
            new_root_page = self.page_manager.allocate_page()
            new_root = {'leaf': False, 'keys': [promoted_key], 'children': [old_root, right_page]}
            self._write_node(new_root_page, new_root)
            meta['rootPageId'] = new_root_page
            self._persist()
        return {'inserted': True}

    def _insert_recursive(self, page_id, key, row_id, unique):
        node = self._read_node(page_id)
        if node['leaf']:
            pos = bisect.bisect_left(node['keys'], key)
            if pos < len(node['keys']) and node['keys'][pos] == key:
                if unique:
                    raise StorageError('INDEX_DUPLICATE_KEY', f'重复索引键: {key}')
                if row_id in node['values'][pos]:
                    raise StorageError('INDEX_DUPLICATE_ROWID', '同一 key/RowId 已存在')
                node['values'][pos].append(row_id); self._write_node(page_id, node); return None
            node['keys'].insert(pos, key); node['values'].insert(pos, [row_id])
            if len(node['keys']) <= self.max_keys:
                self._write_node(page_id, node); return None
            mid = len(node['keys']) // 2
            right_page = self.page_manager.allocate_page()
            right = {'leaf': True, 'keys': node['keys'][mid:], 'values': node['values'][mid:], 'prev': page_id, 'next': node.get('next')}
            node['keys'] = node['keys'][:mid]; node['values'] = node['values'][:mid]
            old_next = node.get('next'); node['next'] = right_page
            self._write_node(page_id, node); self._write_node(right_page, right)
            if old_next is not None:
                next_node = self._read_node(old_next); next_node['prev'] = right_page; self._write_node(old_next, next_node)
            return right['keys'][0], right_page

        idx = bisect.bisect_right(node['keys'], key)
        result = self._insert_recursive(node['children'][idx], key, row_id, unique)
        if result is None: return None
        promoted_key, right_page = result
        node['keys'].insert(idx, promoted_key); node['children'].insert(idx + 1, right_page)
        if len(node['keys']) <= self.max_keys:
            self._write_node(page_id, node); return None
        mid = len(node['keys']) // 2
        up_key = node['keys'][mid]
        right = {'leaf': False, 'keys': node['keys'][mid + 1:], 'children': node['children'][mid + 1:]}
        node['keys'] = node['keys'][:mid]; node['children'] = node['children'][:mid + 1]
        right_page_id = self.page_manager.allocate_page()
        self._write_node(page_id, node); self._write_node(right_page_id, right)
        return up_key, right_page_id

    def index_delete(self, index_id: int, key, row_id=None):
        meta = self._meta(index_id); self._validate_key(meta, key)
        page_id, leaf, path = self._find_leaf(meta['rootPageId'], key)
        pos = bisect.bisect_left(leaf['keys'], key)
        if pos >= len(leaf['keys']) or leaf['keys'][pos] != key:
            raise StorageError('INDEX_KEY_NOT_FOUND', f'索引键 {key} 不存在')
        if row_id is not None:
            row_id = self._rowid(row_id)
            if row_id not in leaf['values'][pos]:
                raise StorageError('INDEX_ROWID_NOT_FOUND', '指定 RowId 不存在')
            leaf['values'][pos].remove(row_id)
            if leaf['values'][pos]:
                self._write_node(page_id, leaf); return {'deleted': True}
        leaf['keys'].pop(pos); leaf['values'].pop(pos)
        self._write_node(page_id, leaf)
        self._rebalance_after_delete(index_id, page_id, path)
        return {'deleted': True}

    def _min_keys(self, node):
        return (self.max_keys + 1) // 2 if node['leaf'] else self.max_keys // 2

    def _rebalance_after_delete(self, index_id: int, node_page: int, path: List[Tuple[int, int]]):
        meta = self._meta(index_id)
        while path:
            node = self._read_node(node_page)
            if len(node['keys']) >= self._min_keys(node):
                break
            parent_page, child_idx = path.pop()
            parent = self._read_node(parent_page)
            if child_idx >= len(parent['children']) or parent['children'][child_idx] != node_page:
                try: child_idx = parent['children'].index(node_page)
                except ValueError: break
            left_page = parent['children'][child_idx - 1] if child_idx > 0 else None
            right_page = parent['children'][child_idx + 1] if child_idx + 1 < len(parent['children']) else None
            left = self._read_node(left_page) if left_page is not None else None
            right = self._read_node(right_page) if right_page is not None else None

            if left is not None and len(left['keys']) > self._min_keys(left):
                if node['leaf']:
                    node['keys'].insert(0, left['keys'].pop()); node['values'].insert(0, left['values'].pop())
                    parent['keys'][child_idx - 1] = node['keys'][0]
                else:
                    node['keys'].insert(0, parent['keys'][child_idx - 1])
                    node['children'].insert(0, left['children'].pop())
                    parent['keys'][child_idx - 1] = left['keys'].pop()
                self._write_node(left_page, left); self._write_node(node_page, node); self._write_node(parent_page, parent)
                return
            if right is not None and len(right['keys']) > self._min_keys(right):
                if node['leaf']:
                    node['keys'].append(right['keys'].pop(0)); node['values'].append(right['values'].pop(0))
                    parent['keys'][child_idx] = right['keys'][0]
                else:
                    node['keys'].append(parent['keys'][child_idx])
                    node['children'].append(right['children'].pop(0))
                    parent['keys'][child_idx] = right['keys'].pop(0)
                self._write_node(right_page, right); self._write_node(node_page, node); self._write_node(parent_page, parent)
                return

            if left is not None:
                if node['leaf']:
                    left['keys'].extend(node['keys']); left['values'].extend(node['values']); left['next'] = node.get('next')
                    if node.get('next') is not None:
                        nxt = self._read_node(node['next']); nxt['prev'] = left_page; self._write_node(node['next'], nxt)
                else:
                    left['keys'].append(parent['keys'][child_idx - 1]); left['keys'].extend(node['keys']); left['children'].extend(node['children'])
                parent['keys'].pop(child_idx - 1); parent['children'].pop(child_idx)
                self._write_node(left_page, left); self._write_node(parent_page, parent)
                self.buffer.force_discard_page(node_page); self.page_manager.free_page(node_page)
            elif right is not None:
                if node['leaf']:
                    node['keys'].extend(right['keys']); node['values'].extend(right['values']); node['next'] = right.get('next')
                    if right.get('next') is not None:
                        nxt = self._read_node(right['next']); nxt['prev'] = node_page; self._write_node(right['next'], nxt)
                else:
                    node['keys'].append(parent['keys'][child_idx]); node['keys'].extend(right['keys']); node['children'].extend(right['children'])
                parent['keys'].pop(child_idx); parent['children'].pop(child_idx + 1)
                self._write_node(node_page, node); self._write_node(parent_page, parent)
                self.buffer.force_discard_page(right_page); self.page_manager.free_page(right_page)
            node_page = parent_page

        root_page = meta['rootPageId']
        root = self._read_node(root_page)
        if not root['leaf'] and len(root['keys']) == 0:
            new_root = root['children'][0]
            meta['rootPageId'] = new_root; self._persist()
            self.buffer.force_discard_page(root_page); self.page_manager.free_page(root_page)

    def _collect_pages(self, root_page: int):
        out = []; stack = [root_page]
        while stack:
            page_id = stack.pop(); out.append(page_id)
            node = self._read_node(page_id)
            if not node['leaf']: stack.extend(node['children'])
        return sorted(set(out))

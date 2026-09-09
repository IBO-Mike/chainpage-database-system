import json
import os
from pathlib import Path
from typing import Union

from .errors import StorageError


class TablePageMap:
    def __init__(self, root: Union[str, Path]):
        self.path = Path(root) / 'table_pages.json'
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.tables = {}
        self._load()

    @staticmethod
    def _normalize(table: str) -> str:
        if not isinstance(table, str) or not table.strip():
            raise StorageError('STORAGE_INVALID_TABLE', 'table 必须是非空字符串')
        return table.strip().lower()

    @staticmethod
    def _page_id(page_id):
        if not isinstance(page_id, int) or isinstance(page_id, bool) or page_id < 0:
            raise StorageError('INVALID_PAGE_ID', 'pageId 必须是非负整数', page_id=page_id)
        return page_id

    def _load(self):
        if not self.path.exists():
            self._persist(); return
        try:
            raw = json.loads(self.path.read_text(encoding='utf-8'))
            if not isinstance(raw, dict):
                raise ValueError('root must be object')
            normalized = {}
            for key, vals in raw.items():
                if not isinstance(vals, list):
                    raise ValueError('page list must be array')
                ids = [self._page_id(v) for v in vals]
                if len(ids) != len(set(ids)):
                    raise ValueError('duplicate page id')
                normalized[str(key).lower()] = ids
            self.tables = normalized
        except (OSError, ValueError, TypeError, json.JSONDecodeError, StorageError) as exc:
            raise StorageError('STORAGE_METADATA_CORRUPT', f'表页映射损坏: {exc}') from exc

    def _persist(self):
        tmp = self.path.with_suffix('.tmp')
        try:
            tmp.write_text(json.dumps(self.tables, ensure_ascii=False, indent=2), encoding='utf-8')
            with tmp.open('rb') as fp:
                os.fsync(fp.fileno())
            os.replace(tmp, self.path)
        except OSError as exc:
            raise StorageError('FILE_IO_ERROR', f'持久化表页映射失败: {exc}') from exc

    def create(self, table: str):
        table = self._normalize(table)
        if table in self.tables:
            raise StorageError('STORAGE_TABLE_EXISTS', f'表 {table} 已存在')
        self.tables[table] = []
        try:
            self._persist()
        except Exception:
            self.tables.pop(table, None); raise
        return []

    def append(self, table: str, page_id: int):
        table = self._normalize(table); page_id = self._page_id(page_id)
        if table not in self.tables:
            raise StorageError('STORAGE_TABLE_NOT_FOUND', f'表 {table} 不存在')
        if page_id in self.tables[table]:
            # The repository spec explicitly assigns STORAGE_TABLE_EXISTS to duplicate append.
            raise StorageError('STORAGE_TABLE_EXISTS', f'pageId {page_id} 已属于表 {table}', page_id=page_id)
        self.tables[table].append(page_id)
        try:
            self._persist()
        except Exception:
            self.tables[table].remove(page_id); raise
        return list(self.tables[table])

    def list_pages(self, table: str):
        table = self._normalize(table)
        if table not in self.tables:
            raise StorageError('STORAGE_TABLE_NOT_FOUND', f'表 {table} 不存在')
        return list(self.tables[table])

    def remove(self, table: str):
        table = self._normalize(table)
        if table not in self.tables:
            raise StorageError('STORAGE_TABLE_NOT_FOUND', f'表 {table} 不存在')
        pages = self.tables.pop(table)
        try:
            self._persist()
        except Exception:
            self.tables[table] = pages; raise
        return pages

    @staticmethod
    def _fields(request, allowed):
        unknown = set(request) - set(allowed)
        if unknown:
            raise StorageError('INVALID_REQUEST', f'请求包含未定义字段: {sorted(unknown)}')

    def handle(self, request):
        try:
            if not isinstance(request, dict):
                raise StorageError('INVALID_REQUEST', '请求必须是 JSON 对象')
            op = request.get('op')
            if op == 'create_table_pages':
                self._fields(request, {'op', 'table'}); table = self._normalize(request.get('table')); result = {'table': table, 'pageIds': self.create(table)}
            elif op == 'append_page':
                self._fields(request, {'op', 'table', 'pageId'}); table = self._normalize(request.get('table'))
                result = {'table': table, 'pageIds': self.append(table, request.get('pageId'))}
            elif op == 'list_pages':
                self._fields(request, {'op', 'table'}); table = self._normalize(request.get('table')); result = {'table': table, 'pageIds': self.list_pages(table)}
            elif op == 'drop_table_pages':
                self._fields(request, {'op', 'table'}); table = self._normalize(request.get('table')); pages = self.remove(table)
                result = {'table': table, 'removed': True, 'pageIds': pages}
            else:
                raise StorageError('UNSUPPORTED_OPERATION', f'不支持的 Table Page Map 操作: {op}')
            return {'ok': True, 'data': result}
        except StorageError as exc:
            return {'ok': False, 'error': exc.to_dict()}

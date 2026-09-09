import json
import os
from pathlib import Path
from typing import Union

from .errors import StorageError


class TablePageMap:
    def __init__(self, root: Union[str, Path]):
        self.path = Path(root) / "table_pages.json"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.tables = {}
        self._load()

    @staticmethod
    def _normalize(table: str) -> str:
        if not isinstance(table, str) or not table.strip():
            raise StorageError("STORAGE_INVALID_TABLE", "table 必须是非空字符串")
        return table.strip().lower()

    def _load(self):
        if not self.path.exists():
            self._persist()
            return
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            self.tables = {str(k).lower(): [int(v) for v in vals] for k, vals in raw.items()}
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as exc:
            raise StorageError("STORAGE_METADATA_CORRUPT", f"表页映射损坏: {exc}") from exc

    def _persist(self):
        tmp = self.path.with_suffix(".tmp")
        try:
            tmp.write_text(json.dumps(self.tables, ensure_ascii=False, indent=2), encoding="utf-8")
            os.replace(tmp, self.path)
        except OSError as exc:
            raise StorageError("FILE_IO_ERROR", f"持久化表页映射失败: {exc}") from exc

    def create(self, table: str):
        table = self._normalize(table)
        if table in self.tables:
            raise StorageError("STORAGE_TABLE_EXISTS", f"表 {table} 已存在")
        self.tables[table] = []
        self._persist()
        return []

    def append(self, table: str, page_id: int):
        table = self._normalize(table)
        if table not in self.tables:
            raise StorageError("STORAGE_TABLE_NOT_FOUND", f"表 {table} 不存在")
        if page_id in self.tables[table]:
            raise StorageError("STORAGE_PAGE_ALREADY_MAPPED", f"pageId {page_id} 已属于表 {table}", page_id=page_id)
        self.tables[table].append(page_id)
        self._persist()
        return list(self.tables[table])

    def list_pages(self, table: str):
        table = self._normalize(table)
        if table not in self.tables:
            raise StorageError("STORAGE_TABLE_NOT_FOUND", f"表 {table} 不存在")
        return list(self.tables[table])

    def remove(self, table: str):
        table = self._normalize(table)
        if table not in self.tables:
            raise StorageError("STORAGE_TABLE_NOT_FOUND", f"表 {table} 不存在")
        pages = self.tables.pop(table)
        self._persist()
        return pages

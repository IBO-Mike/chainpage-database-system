import base64
import binascii
from pathlib import Path
from typing import Any, Dict, Union

from .buffer_pool import BufferPool
from .constants import DEFAULT_BUFFER_CAPACITY, PAGE_SIZE
from .errors import StorageError
from .page_manager import PageManager
from .table_page_map import TablePageMap


class StorageManager:
    """Unified storage API used by the database engine."""

    def __init__(self, root: Union[str, Path], *, capacity: int = DEFAULT_BUFFER_CAPACITY, policy: str = "LRU", log: bool = True):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.pages = PageManager(self.root)
        self.table_map = TablePageMap(self.root)
        self.buffer = BufferPool(self.pages, capacity=capacity, policy=policy, log=log)

    def get_page(self, page_id: int):
        data, hit, dirty = self.buffer.get_page(page_id)
        return {"pageId": page_id, "data": data, "hit": hit, "dirty": dirty}

    def write_page(self, page_id: int, data: bytes):
        self.buffer.put_page(page_id, data, dirty=True)
        return {"pageId": page_id, "dirty": True}

    def create_table_pages(self, table: str):
        pages = self.table_map.create(table)
        return {"table": table.lower(), "pageIds": pages}

    def allocate_page_for_table(self, table: str):
        self.table_map.list_pages(table)
        page_id = self.pages.allocate_page()
        try:
            page_ids = self.table_map.append(table, page_id)
        except Exception:
            self.pages.free_page(page_id)
            raise
        return {"table": table.lower(), "pageId": page_id, "pageIds": page_ids}

    def list_table_pages(self, table: str):
        return {"table": table.lower(), "pageIds": self.table_map.list_pages(table)}

    def drop_table_pages(self, table: str):
        page_ids = self.table_map.list_pages(table)
        for page_id in page_ids:
            if page_id in self.buffer.frames and self.buffer.frames[page_id].dirty:
                self.buffer.flush_page(page_id)
        removed = self.table_map.remove(table)
        freed = []
        for page_id in removed:
            self.buffer.discard_page(page_id)
            self.pages.free_page(page_id)
            freed.append(page_id)
        return {"table": table.lower(), "removed": True, "freedPageIds": freed}

    def flush_page(self, page_id: int):
        self.buffer.flush_page(page_id)
        self.pages.sync()
        return {"pageId": page_id, "flushed": True}

    def flush_all(self):
        return {"flushedPageIds": self.buffer.flush_all()}

    def storage_stats(self):
        stats = self.buffer.stats()
        stats["allocatedPages"] = self.pages.allocated_count
        return stats

    @staticmethod
    def _decode_page(data: str, page_id=None) -> bytes:
        if not isinstance(data, str):
            raise StorageError("INVALID_PAGE_DATA", "data 必须是 Base64 字符串", page_id=page_id)
        try:
            decoded = base64.b64decode(data, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise StorageError("INVALID_PAGE_DATA", "data 不是合法 Base64", page_id=page_id) from exc
        if len(decoded) != PAGE_SIZE:
            raise StorageError("INVALID_PAGE_SIZE", "Base64 解码后必须恰好为 4096 字节", page_id=page_id)
        return decoded

    @staticmethod
    def _encode_page(data: bytes) -> str:
        return base64.b64encode(data).decode("ascii")

    @staticmethod
    def _check_fields(request: Dict[str, Any], allowed):
        unknown = set(request) - set(allowed)
        if unknown:
            raise StorageError("INVALID_REQUEST", f"请求包含未定义字段: {sorted(unknown)}")

    def handle(self, request: Dict[str, Any]):
        """JSON-compatible request/response envelope for module integration."""
        request_id = request.get("requestId") if isinstance(request, dict) else None
        try:
            if not isinstance(request, dict):
                raise StorageError("INVALID_REQUEST", "请求必须是 JSON 对象")
            op = request.get("op")
            if not isinstance(op, str) or not op:
                raise StorageError("INVALID_REQUEST", "缺少 op")

            if op == "get_page":
                self._check_fields(request, {"requestId", "op", "pageId"})
                result = self.get_page(request.get("pageId"))
                result["data"] = self._encode_page(result["data"])
            elif op == "write_page":
                self._check_fields(request, {"requestId", "op", "pageId", "data"})
                page_id = request.get("pageId")
                result = self.write_page(page_id, self._decode_page(request.get("data"), page_id))
            elif op == "create_table_pages":
                self._check_fields(request, {"requestId", "op", "table"})
                result = self.create_table_pages(request.get("table"))
            elif op == "drop_table_pages":
                self._check_fields(request, {"requestId", "op", "table"})
                result = self.drop_table_pages(request.get("table"))
            elif op == "allocate_page_for_table":
                self._check_fields(request, {"requestId", "op", "table"})
                result = self.allocate_page_for_table(request.get("table"))
            elif op == "list_table_pages":
                self._check_fields(request, {"requestId", "op", "table"})
                result = self.list_table_pages(request.get("table"))
            elif op == "flush_page":
                self._check_fields(request, {"requestId", "op", "pageId"})
                result = self.flush_page(request.get("pageId"))
            elif op == "flush_all":
                self._check_fields(request, {"requestId", "op"})
                result = self.flush_all()
            elif op == "storage_stats":
                self._check_fields(request, {"requestId", "op"})
                result = self.storage_stats()
            else:
                raise StorageError("UNSUPPORTED_OPERATION", f"不支持的操作: {op}")

            if request_id is not None:
                result = {"requestId": request_id, **result}
            return {"ok": True, "data": result}
        except StorageError as exc:
            error = exc.to_dict()
            error["requestId"] = request_id
            error["statementIndex"] = None
            error["line"] = None
            error["column"] = None
            return {"ok": False, "error": error}

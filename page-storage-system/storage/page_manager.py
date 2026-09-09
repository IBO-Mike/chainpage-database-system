import json
import os
from pathlib import Path
from typing import Union

from .constants import PAGE_SIZE
from .errors import StorageError
from .file_manager import FileManager


class PageManager:
    """Owns page allocation state and validates all page access."""

    def __init__(self, root: Union[str, Path]):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.file_manager = FileManager(self.root / "pages.dat")
        self.meta_path = self.root / "page_allocation.json"
        self._allocated = set()
        self._free = []
        self._load_or_initialize()

    def _load_or_initialize(self):
        if not self.meta_path.exists():
            self._persist()
            return
        try:
            state = json.loads(self.meta_path.read_text(encoding="utf-8"))
            self._allocated = {int(v) for v in state.get("allocated", [])}
            self._free = sorted({int(v) for v in state.get("free", [])})
        except (OSError, ValueError, TypeError, json.JSONDecodeError) as exc:
            raise StorageError("PAGE_METADATA_CORRUPT", f"页分配元数据损坏: {exc}") from exc

    def _persist(self):
        state = {"allocated": sorted(self._allocated), "free": sorted(self._free)}
        tmp = self.meta_path.with_suffix(".tmp")
        try:
            tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
            os.replace(tmp, self.meta_path)
        except OSError as exc:
            raise StorageError("FILE_IO_ERROR", f"持久化页分配状态失败: {exc}") from exc

    def is_allocated(self, page_id: int) -> bool:
        return page_id in self._allocated

    def _require_allocated(self, page_id: int):
        if not isinstance(page_id, int) or isinstance(page_id, bool) or page_id < 0:
            raise StorageError("INVALID_PAGE_ID", "pageId 必须是非负整数", page_id=page_id)
        if page_id not in self._allocated:
            raise StorageError("PAGE_NOT_ALLOCATED", "页不存在或已经释放", page_id=page_id)

    def allocate_page(self) -> int:
        if self._free:
            page_id = self._free.pop(0)
            self.file_manager.write_at(page_id, bytes(PAGE_SIZE))
        else:
            page_id = self.file_manager.append_zero_page()
        self._allocated.add(page_id)
        try:
            self._persist()
        except Exception:
            self._allocated.discard(page_id)
            if page_id not in self._free:
                self._free.append(page_id)
                self._free.sort()
            raise
        return page_id

    def free_page(self, page_id: int) -> None:
        self._require_allocated(page_id)
        self._allocated.remove(page_id)
        self._free.append(page_id)
        self._free.sort()
        try:
            self._persist()
        except Exception:
            self._free.remove(page_id)
            self._allocated.add(page_id)
            raise

    def read_page(self, page_id: int) -> bytes:
        self._require_allocated(page_id)
        return self.file_manager.read_at(page_id)

    def write_page(self, page_id: int, data: bytes) -> int:
        self._require_allocated(page_id)
        if len(data) != PAGE_SIZE:
            raise StorageError("INVALID_PAGE_SIZE", "写入页必须恰好为 4096 字节", page_id=page_id)
        return self.file_manager.write_at(page_id, data)

    def sync(self):
        self.file_manager.sync()

    @property
    def allocated_count(self) -> int:
        return len(self._allocated)

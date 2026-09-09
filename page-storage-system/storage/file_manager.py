from pathlib import Path
from typing import Union

from .constants import PAGE_SIZE
from .errors import StorageError

BytesLike = Union[bytes, bytearray, memoryview]


class FileManager:
    """Fixed-size page I/O over one data file."""

    def __init__(self, data_file: Union[str, Path]):
        self.path = Path(data_file)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.touch(exist_ok=True)

    def _offset(self, page_id: int) -> int:
        if not isinstance(page_id, int) or isinstance(page_id, bool) or page_id < 0:
            raise StorageError("INVALID_PAGE_ID", "pageId 必须是非负整数", page_id=page_id)
        return page_id * PAGE_SIZE

    def read_at(self, page_id: int) -> bytes:
        offset = self._offset(page_id)
        try:
            with self.path.open("rb") as fp:
                fp.seek(offset)
                data = fp.read(PAGE_SIZE)
        except OSError as exc:
            raise StorageError("FILE_IO_ERROR", f"读取数据文件失败: {exc}", page_id=page_id) from exc
        if len(data) != PAGE_SIZE:
            raise StorageError("FILE_IO_ERROR", "数据文件中的页长度不足 4096 字节", page_id=page_id)
        return data

    def write_at(self, page_id: int, data: BytesLike) -> int:
        offset = self._offset(page_id)
        data = bytes(data)
        if len(data) != PAGE_SIZE:
            raise StorageError("INVALID_PAGE_SIZE", "写入页必须恰好为 4096 字节", page_id=page_id)
        try:
            with self.path.open("r+b") as fp:
                fp.seek(offset)
                fp.write(data)
                fp.flush()
        except OSError as exc:
            raise StorageError("FILE_IO_ERROR", f"写入数据文件失败: {exc}", page_id=page_id) from exc
        return PAGE_SIZE

    def append_zero_page(self) -> int:
        try:
            size = self.path.stat().st_size
            if size % PAGE_SIZE != 0:
                raise StorageError("FILE_IO_ERROR", "数据文件长度不是页大小的整数倍")
            page_id = size // PAGE_SIZE
            with self.path.open("ab") as fp:
                fp.write(bytes(PAGE_SIZE))
                fp.flush()
            return page_id
        except OSError as exc:
            raise StorageError("FILE_IO_ERROR", f"扩展数据文件失败: {exc}") from exc

    def sync(self) -> None:
        try:
            with self.path.open("r+b") as fp:
                fp.flush()
                import os
                os.fsync(fp.fileno())
        except OSError as exc:
            raise StorageError("FILE_IO_ERROR", f"同步数据文件失败: {exc}") from exc

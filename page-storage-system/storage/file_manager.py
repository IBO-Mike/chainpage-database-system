import base64
import binascii
from pathlib import Path
from typing import Union

from .constants import PAGE_SIZE
from .errors import StorageError

BytesLike = Union[bytes, bytearray, memoryview]


class FileManager:
    """Fixed-size page I/O over one data file.

    Raw methods are intentionally low-level. `handle()` is the standalone JSON
    test boundary defined by the repository spec and, when attached to a
    PageManager, also validates allocation state.
    """

    def __init__(self, data_file: Union[str, Path]):
        self.path = Path(data_file)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.touch(exist_ok=True)
        self._allocation_validator = None

    def set_allocation_validator(self, validator):
        self._allocation_validator = validator

    def _offset(self, page_id: int) -> int:
        if not isinstance(page_id, int) or isinstance(page_id, bool) or page_id < 0:
            raise StorageError('INVALID_PAGE_ID', 'pageId 必须是非负整数', page_id=page_id)
        return page_id * PAGE_SIZE

    def _require_api_allocated(self, page_id: int):
        self._offset(page_id)
        if self._allocation_validator is not None:
            if not self._allocation_validator(page_id):
                raise StorageError('PAGE_NOT_ALLOCATED', '页不存在或已经释放', page_id=page_id)
        elif (page_id + 1) * PAGE_SIZE > self.path.stat().st_size:
            raise StorageError('PAGE_NOT_ALLOCATED', '页不存在', page_id=page_id)

    def read_at(self, page_id: int) -> bytes:
        offset = self._offset(page_id)
        try:
            with self.path.open('rb') as fp:
                fp.seek(offset)
                data = fp.read(PAGE_SIZE)
        except OSError as exc:
            raise StorageError('FILE_IO_ERROR', f'读取数据文件失败: {exc}', page_id=page_id) from exc
        if len(data) != PAGE_SIZE:
            raise StorageError('FILE_IO_ERROR', '数据文件中的页长度不足 4096 字节', page_id=page_id)
        return data

    def write_at(self, page_id: int, data: BytesLike) -> int:
        offset = self._offset(page_id)
        data = bytes(data)
        if len(data) != PAGE_SIZE:
            raise StorageError('INVALID_PAGE_SIZE', '写入页必须恰好为 4096 字节', page_id=page_id)
        try:
            with self.path.open('r+b') as fp:
                fp.seek(offset)
                fp.write(data)
                fp.flush()
        except OSError as exc:
            raise StorageError('FILE_IO_ERROR', f'写入数据文件失败: {exc}', page_id=page_id) from exc
        return PAGE_SIZE

    def append_zero_page(self) -> int:
        try:
            size = self.path.stat().st_size
            if size % PAGE_SIZE != 0:
                raise StorageError('FILE_IO_ERROR', '数据文件长度不是页大小的整数倍')
            page_id = size // PAGE_SIZE
            with self.path.open('ab') as fp:
                fp.write(bytes(PAGE_SIZE))
                fp.flush()
            return page_id
        except OSError as exc:
            raise StorageError('FILE_IO_ERROR', f'扩展数据文件失败: {exc}') from exc

    def sync(self) -> None:
        try:
            with self.path.open('r+b') as fp:
                fp.flush()
                import os
                os.fsync(fp.fileno())
        except OSError as exc:
            raise StorageError('FILE_IO_ERROR', f'同步数据文件失败: {exc}') from exc

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
            if op == 'read_at':
                self._fields(request, {'op', 'pageId'})
                page_id = request.get('pageId'); self._require_api_allocated(page_id)
                data = base64.b64encode(self.read_at(page_id)).decode('ascii')
                result = {'pageId': page_id, 'data': data}
            elif op == 'write_at':
                self._fields(request, {'op', 'pageId', 'data'})
                page_id = request.get('pageId'); self._require_api_allocated(page_id)
                written = self.write_at(page_id, self._decode(request.get('data'), page_id))
                result = {'pageId': page_id, 'written': written}
            elif op == 'sync':
                self._fields(request, {'op'}); self.sync(); result = {'flushed': True}
            else:
                raise StorageError('UNSUPPORTED_OPERATION', f'不支持的 File Manager 操作: {op}')
            return {'ok': True, 'data': result}
        except StorageError as exc:
            return {'ok': False, 'error': exc.to_dict()}

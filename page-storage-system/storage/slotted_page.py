import json
import struct
from dataclasses import dataclass
from typing import Any, Dict, List, Tuple

from .constants import PAGE_SIZE
SLOTTED_PAGE_MAGIC = b'CPS1'
from .errors import StorageError

_HEADER = struct.Struct('>4sHHH')
_SLOT = struct.Struct('>HHB3x')
HEADER_SIZE = _HEADER.size
SLOT_SIZE = _SLOT.size


@dataclass
class Slot:
    offset: int
    length: int
    deleted: bool
    payload: bytes = b''


class SlottedPage:
    """4096-byte slotted page with stable slot ids and compaction on mutation."""

    def __init__(self, slots: List[Slot] = None):
        self.slots = slots or []

    @staticmethod
    def _encode_row(row: Dict[str, Any]) -> bytes:
        if not isinstance(row, dict):
            raise StorageError('ROW_INVALID', 'Row 必须是 JSON 对象')
        try:
            return json.dumps(row, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        except (TypeError, ValueError) as exc:
            raise StorageError('ROW_INVALID', f'Row 无法序列化: {exc}') from exc

    @staticmethod
    def _decode_row(payload: bytes):
        try:
            value = json.loads(payload.decode('utf-8'))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise StorageError('PAGE_RECORD_CORRUPT', '页内记录无法反序列化') from exc
        if not isinstance(value, dict):
            raise StorageError('PAGE_RECORD_CORRUPT', '页内记录不是 Row 对象')
        return value

    @classmethod
    def from_bytes(cls, data: bytes):
        data = bytes(data)
        if len(data) != PAGE_SIZE:
            raise StorageError('INVALID_PAGE_SIZE', '页必须恰好为 4096 字节')
        if data == bytes(PAGE_SIZE):
            return cls([])
        try:
            magic, slot_count, free_start, free_end = _HEADER.unpack_from(data, 0)
        except struct.error as exc:
            raise StorageError('PAGE_FORMAT_ERROR', '页头损坏') from exc
        if magic != SLOTTED_PAGE_MAGIC:
            raise StorageError('PAGE_FORMAT_ERROR', '该页不是 Slotted Page')
        expected_start = HEADER_SIZE + slot_count * SLOT_SIZE
        if free_start != expected_start or not (free_start <= free_end <= PAGE_SIZE):
            raise StorageError('PAGE_FORMAT_ERROR', 'Slotted Page 空闲区元数据损坏')
        slots = []
        for slot_id in range(slot_count):
            pos = HEADER_SIZE + slot_id * SLOT_SIZE
            offset, length, flags = _SLOT.unpack_from(data, pos)
            deleted = bool(flags & 1)
            if deleted:
                slots.append(Slot(0, 0, True, b''))
                continue
            if length <= 0 or offset < free_end or offset + length > PAGE_SIZE:
                raise StorageError('PAGE_FORMAT_ERROR', f'slot {slot_id} 指针越界')
            slots.append(Slot(offset, length, False, data[offset:offset + length]))
        return cls(slots)

    def _serialized_size(self, extra_slots=0, extra_payload=0):
        live_bytes = sum(len(slot.payload) for slot in self.slots if not slot.deleted)
        return HEADER_SIZE + (len(self.slots) + extra_slots) * SLOT_SIZE + live_bytes + extra_payload

    def free_bytes(self):
        return PAGE_SIZE - self._serialized_size()

    def to_bytes(self) -> bytes:
        page = bytearray(PAGE_SIZE)
        free_start = HEADER_SIZE + len(self.slots) * SLOT_SIZE
        cursor = PAGE_SIZE
        layout = []
        for slot in self.slots:
            if slot.deleted:
                layout.append((0, 0, 1))
                continue
            payload = bytes(slot.payload)
            cursor -= len(payload)
            if cursor < free_start:
                raise StorageError('PAGE_NO_SPACE', '页内记录超过 4096 字节容量')
            page[cursor:cursor + len(payload)] = payload
            layout.append((cursor, len(payload), 0))
        _HEADER.pack_into(page, 0, SLOTTED_PAGE_MAGIC, len(self.slots), free_start, cursor)
        for slot_id, (offset, length, flags) in enumerate(layout):
            _SLOT.pack_into(page, HEADER_SIZE + slot_id * SLOT_SIZE, offset, length, flags)
        return bytes(page)

    def insert_record(self, row: Dict[str, Any]) -> Tuple[int, int]:
        payload = self._encode_row(row)
        reusable = next((i for i, slot in enumerate(self.slots) if slot.deleted), None)
        extra_slots = 0 if reusable is not None else 1
        if self._serialized_size(extra_slots=extra_slots, extra_payload=len(payload)) > PAGE_SIZE:
            raise StorageError('PAGE_NO_SPACE', '页内剩余空间不足')
        if reusable is not None:
            self.slots[reusable] = Slot(0, len(payload), False, payload)
            slot_id = reusable
        else:
            self.slots.append(Slot(0, len(payload), False, payload))
            slot_id = len(self.slots) - 1
        return slot_id, self.free_bytes()

    def read_record(self, slot_id: int):
        self._require_slot(slot_id)
        slot = self.slots[slot_id]
        if slot.deleted:
            raise StorageError('PAGE_SLOT_DELETED', '该 slot 已删除')
        return self._decode_row(slot.payload)

    def delete_record(self, slot_id: int):
        self._require_slot(slot_id)
        if self.slots[slot_id].deleted:
            raise StorageError('PAGE_SLOT_DELETED', '该 slot 已删除')
        self.slots[slot_id] = Slot(0, 0, True, b'')
        return True

    def iter_records(self):
        for slot_id, slot in enumerate(self.slots):
            if not slot.deleted:
                yield slot_id, self._decode_row(slot.payload)

    def _require_slot(self, slot_id: int):
        if not isinstance(slot_id, int) or isinstance(slot_id, bool) or slot_id < 0 or slot_id >= len(self.slots):
            raise StorageError('PAGE_SLOT_NOT_FOUND', f'slotId {slot_id} 不存在')

import base64
import json
import os
from pathlib import Path
from typing import Dict, List, Union

from .constants import PAGE_SIZE
from .errors import StorageError


class WALManager:
    """Simple write-ahead log using durable JSONL update records + APPLIED markers."""

    def __init__(self, root: Union[str, Path]):
        self.path = Path(root) / 'wal.log'
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.touch(exist_ok=True)
        self._next_seq = 1
        self._bootstrap_seq()

    @staticmethod
    def _encode_page(data: bytes) -> str:
        data = bytes(data)
        if len(data) != PAGE_SIZE:
            raise StorageError('WAL_INVALID_PAGE_DATA', 'WAL 页镜像必须恰好为 4096 字节')
        return base64.b64encode(data).decode('ascii')

    @staticmethod
    def _decode_page(data: str, *, page_id=None) -> bytes:
        try:
            raw = base64.b64decode(data, validate=True)
        except Exception as exc:
            raise StorageError('WAL_CORRUPT', 'WAL 中存在非法 Base64 页镜像', page_id=page_id) from exc
        if len(raw) != PAGE_SIZE:
            raise StorageError('WAL_CORRUPT', 'WAL 中页镜像长度不是 4096 字节', page_id=page_id)
        return raw

    def _read_lines(self) -> List[Dict]:
        records = []
        try:
            with self.path.open('r', encoding='utf-8') as fp:
                for no, line in enumerate(fp, 1):
                    if not line.strip():
                        continue
                    try:
                        record = json.loads(line)
                    except json.JSONDecodeError as exc:
                        raise StorageError('WAL_CORRUPT', f'WAL 第 {no} 行 JSON 损坏') from exc
                    if not isinstance(record, dict) or 'kind' not in record:
                        raise StorageError('WAL_CORRUPT', f'WAL 第 {no} 行格式无效')
                    records.append(record)
        except OSError as exc:
            raise StorageError('FILE_IO_ERROR', f'读取 WAL 失败: {exc}') from exc
        return records

    def _bootstrap_seq(self):
        seqs = [r.get('logSeq') for r in self._read_lines() if r.get('kind') == 'UPDATE']
        if seqs:
            ints = []
            for seq in seqs:
                if not isinstance(seq, int) or isinstance(seq, bool) or seq <= 0:
                    raise StorageError('WAL_CORRUPT', 'WAL logSeq 非法')
                ints.append(seq)
            expected = list(range(1, max(ints) + 1))
            if sorted(ints) != expected:
                raise StorageError('WAL_SEQUENCE_GAP', 'WAL logSeq 存在断裂')
            self._next_seq = max(ints) + 1

    def _append(self, record: Dict) -> None:
        try:
            with self.path.open('a', encoding='utf-8') as fp:
                fp.write(json.dumps(record, ensure_ascii=False, separators=(',', ':')) + '\n')
                fp.flush()
                os.fsync(fp.fileno())
        except OSError as exc:
            raise StorageError('FILE_IO_ERROR', f'写入 WAL 失败: {exc}') from exc

    def append_log(self, tx_id: int, page_id: int, before: bytes, after: bytes) -> int:
        if not isinstance(tx_id, int) or isinstance(tx_id, bool) or tx_id < 0:
            raise StorageError('WAL_INVALID_TX', 'txId 必须是非负整数', page_id=page_id)
        if not isinstance(page_id, int) or isinstance(page_id, bool) or page_id < 0:
            raise StorageError('INVALID_PAGE_ID', 'pageId 必须是非负整数', page_id=page_id)
        seq = self._next_seq
        self._append({
            'kind': 'UPDATE',
            'logSeq': seq,
            'txId': tx_id,
            'pageId': page_id,
            'before': self._encode_page(before),
            'after': self._encode_page(after),
        })
        self._next_seq += 1
        return seq

    def mark_applied(self, log_seq: int) -> None:
        if not isinstance(log_seq, int) or log_seq <= 0:
            raise StorageError('WAL_INVALID_SEQUENCE', 'logSeq 必须是正整数')
        self._append({'kind': 'APPLIED', 'logSeq': log_seq})

    def recover(self, page_manager) -> Dict[str, int]:
        records = self._read_lines()
        updates: Dict[int, Dict] = {}
        applied = set()
        expected = 1
        for record in records:
            kind = record.get('kind')
            if kind == 'UPDATE':
                seq = record.get('logSeq')
                if seq != expected:
                    raise StorageError('WAL_SEQUENCE_GAP', f'WAL 期望 logSeq={expected}，实际为 {seq}')
                expected += 1
                page_id = record.get('pageId')
                tx_id = record.get('txId')
                if not isinstance(page_id, int) or page_id < 0 or not isinstance(tx_id, int) or tx_id < 0:
                    raise StorageError('WAL_CORRUPT', 'WAL UPDATE 元数据非法', page_id=page_id)
                self._decode_page(record.get('before'), page_id=page_id)
                self._decode_page(record.get('after'), page_id=page_id)
                updates[seq] = record
            elif kind == 'APPLIED':
                seq = record.get('logSeq')
                if not isinstance(seq, int) or seq <= 0:
                    raise StorageError('WAL_CORRUPT', 'WAL APPLIED 记录非法')
                applied.add(seq)
            else:
                raise StorageError('WAL_CORRUPT', f'未知 WAL 记录类型: {kind}')

        replayed = 0
        for seq in sorted(updates):
            if seq in applied:
                continue
            record = updates[seq]
            page_id = record['pageId']
            if not page_manager.is_allocated(page_id):
                raise StorageError('WAL_PAGE_NOT_ALLOCATED', 'WAL 指向未分配页，无法恢复', page_id=page_id)
            after = self._decode_page(record['after'], page_id=page_id)
            page_manager.write_page(page_id, after)
            replayed += 1
        if replayed:
            page_manager.sync()
            for seq in sorted(updates):
                if seq not in applied:
                    self.mark_applied(seq)
        self._next_seq = max(updates.keys(), default=0) + 1
        return {'replayed': replayed, 'undone': 0, 'clean': True}

    @property
    def next_sequence(self):
        return self._next_seq

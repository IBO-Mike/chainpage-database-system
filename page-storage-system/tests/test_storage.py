import base64

import pytest

from storage import StorageManager, StorageError


def page_bytes(fill: int):
    return bytes([fill]) * 4096


def test_page_allocate_write_flush_restart(tmp_path):
    db = StorageManager(tmp_path, capacity=2, policy='LRU')
    db.create_table_pages('student')
    page_id = db.allocate_page_for_table('student')['pageId']
    db.write_page(page_id, page_bytes(7))
    assert db.get_page(page_id)['dirty'] is True
    db.flush_all()
    restarted = StorageManager(tmp_path, capacity=2, policy='LRU')
    assert restarted.list_table_pages('STUDENT')['pageIds'] == [page_id]
    assert restarted.get_page(page_id)['data'] == page_bytes(7)


def test_free_page_is_reused(tmp_path):
    db = StorageManager(tmp_path)
    db.create_table_pages('a')
    p0 = db.allocate_page_for_table('a')['pageId']
    db.drop_table_pages('a')
    db.create_table_pages('b')
    p1 = db.allocate_page_for_table('b')['pageId']
    assert p1 == p0


def test_lru_fifo_and_runtime_switch(tmp_path):
    db = StorageManager(tmp_path, capacity=2, policy='LRU')
    db.create_table_pages('t')
    ids = [db.allocate_page_for_table('t')['pageId'] for _ in range(4)]
    db.get_page(ids[0]); db.get_page(ids[1]); db.get_page(ids[0]); db.get_page(ids[2])
    assert ids[1] not in db.buffer.frames
    db.set_policy('FIFO')
    db.get_page(ids[0]); db.get_page(ids[3])
    assert db.storage_stats()['policy'] == 'FIFO'
    assert db.storage_stats()['evictions'] >= 2
    assert any(e['event'] == 'EVICT' for e in db.buffer_events()['events'])


def test_json_api_base64_and_error_envelope(tmp_path):
    db = StorageManager(tmp_path)
    assert db.handle({'requestId': 'r1', 'op': 'create_table_pages', 'table': 'student'})['ok']
    alloc = db.handle({'requestId': 'r1', 'op': 'allocate_page_for_table', 'table': 'student'})
    page_id = alloc['data']['pageId']
    encoded = base64.b64encode(page_bytes(9)).decode('ascii')
    result = db.handle({'requestId': 'r2', 'op': 'write_page', 'pageId': page_id, 'data': encoded})
    assert result == {'ok': True, 'data': {'requestId': 'r2', 'pageId': page_id, 'dirty': True}}
    bad = db.handle({'requestId': 'r3', 'op': 'get_page', 'pageId': 999})
    assert bad['ok'] is False
    assert bad['error']['code'] == 'PAGE_NOT_ALLOCATED'
    assert bad['error']['requestId'] == 'r3'


def test_slotted_page_insert_read_delete_and_reuse(tmp_path):
    db = StorageManager(tmp_path)
    db.create_table_pages('student')
    page_id = db.allocate_page_for_table('student')['pageId']
    r0 = db.insert_record(page_id, {'id': 1, 'name': 'Alice'})
    r1 = db.insert_record(page_id, {'id': 2, 'name': 'Bob'})
    assert r0['slotId'] == 0 and r1['slotId'] == 1
    assert db.read_record(page_id, 0)['row'] == {'id': 1, 'name': 'Alice'}
    db.delete_record(page_id, 0)
    with pytest.raises(StorageError):
        db.read_record(page_id, 0)
    r2 = db.insert_record(page_id, {'id': 3, 'name': 'Carol'})
    assert r2['slotId'] == 0
    assert [r['row']['id'] for r in db.scan_records(page_id)['records']] == [3, 2]
    db.flush_all()
    restarted = StorageManager(tmp_path)
    assert restarted.read_record(page_id, 0)['row']['name'] == 'Carol'


def test_page_lock_shared_read_exclusive_write(tmp_path):
    db = StorageManager(tmp_path)
    page_id = db.allocate_page()['pageId']
    assert db.lock_page(page_id, 'READ', 'alice') == {'granted': True}
    assert db.lock_page(page_id, 'READ', 'bob') == {'granted': True}
    assert db.lock_page(page_id, 'WRITE', 'charlie') == {'granted': False, 'wait': True}
    db.unlock_page(page_id, 'alice'); db.unlock_page(page_id, 'bob')
    assert db.lock_page(page_id, 'WRITE', 'charlie') == {'granted': True}
    assert db.lock_page(page_id, 'READ', 'alice') == {'granted': False, 'wait': True}
    db.unlock_page(page_id, 'charlie')


def test_wal_replays_dirty_page_after_simulated_crash(tmp_path):
    first = StorageManager(tmp_path, capacity=2)
    page_id = first.allocate_page()['pageId']
    first.write_page(page_id, page_bytes(33), tx_id=7)
    assert first.pages.read_page(page_id) == bytes(4096)
    restarted = StorageManager(tmp_path, capacity=2)
    assert restarted.last_recovery['replayed'] >= 1
    assert restarted.get_page(page_id)['data'] == page_bytes(33)


def test_bplus_tree_split_search_range_delete_restart(tmp_path):
    db = StorageManager(tmp_path, capacity=8)
    db.create_index(1, unique=True, key_type='INT')
    for key in range(80):
        db.index_insert(1, key, {'pageId': key, 'slotId': 0})
    assert db.index_search(1, 42)['rowIds'] == [{'pageId': 42, 'slotId': 0}]
    assert [r['pageId'] for r in db.index_range(1, 10, 15)['rowIds']] == list(range(10, 16))
    for key in range(60):
        db.index_delete(1, key)
    assert db.index_search(1, 42)['rowIds'] == []
    assert db.index_search(1, 70)['rowIds'] == [{'pageId': 70, 'slotId': 0}]
    db.flush_all()
    restarted = StorageManager(tmp_path, capacity=8)
    assert restarted.index_search(1, 70)['rowIds'] == [{'pageId': 70, 'slotId': 0}]


def test_non_unique_index_and_duplicate_constraint(tmp_path):
    db = StorageManager(tmp_path)
    db.create_index(3, unique=False)
    db.index_insert(3, 'x', {'pageId': 1, 'slotId': 1})
    db.index_insert(3, 'x', {'pageId': 2, 'slotId': 2})
    assert len(db.index_search(3, 'x')['rowIds']) == 2
    db.create_index(4, unique=True)
    db.index_insert(4, 1, {'pageId': 1, 'slotId': 1})
    with pytest.raises(StorageError):
        db.index_insert(4, 1, {'pageId': 2, 'slotId': 2})

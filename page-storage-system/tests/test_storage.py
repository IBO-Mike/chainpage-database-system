import base64

from storage import StorageManager


def page_bytes(fill: int):
    return bytes([fill]) * 4096


def test_page_allocate_write_flush_restart(tmp_path):
    db = StorageManager(tmp_path, capacity=2, policy="LRU")
    db.create_table_pages("student")
    page_id = db.allocate_page_for_table("student")["pageId"]
    db.write_page(page_id, page_bytes(7))
    assert db.get_page(page_id)["dirty"] is True
    db.flush_all()

    restarted = StorageManager(tmp_path, capacity=2, policy="LRU")
    assert restarted.list_table_pages("student")["pageIds"] == [page_id]
    assert restarted.get_page(page_id)["data"] == page_bytes(7)


def test_free_page_is_reused(tmp_path):
    db = StorageManager(tmp_path)
    db.create_table_pages("a")
    p0 = db.allocate_page_for_table("a")["pageId"]
    db.drop_table_pages("a")
    db.create_table_pages("b")
    p1 = db.allocate_page_for_table("b")["pageId"]
    assert p1 == p0


def test_lru_eviction(tmp_path):
    db = StorageManager(tmp_path, capacity=2, policy="LRU")
    db.create_table_pages("t")
    ids = [db.allocate_page_for_table("t")["pageId"] for _ in range(3)]
    db.get_page(ids[0])
    db.get_page(ids[1])
    db.write_page(ids[0], page_bytes(1))
    db.get_page(ids[2])
    assert ids[1] not in db.buffer.frames
    assert db.storage_stats()["evictions"] == 1


def test_fifo_access_does_not_change_order(tmp_path):
    db = StorageManager(tmp_path, capacity=2, policy="FIFO")
    db.create_table_pages("t")
    ids = [db.allocate_page_for_table("t")["pageId"] for _ in range(3)]
    db.get_page(ids[0])
    db.get_page(ids[1])
    db.get_page(ids[0])
    db.get_page(ids[2])
    assert ids[0] not in db.buffer.frames


def test_json_api_base64_and_error_envelope(tmp_path):
    db = StorageManager(tmp_path)
    assert db.handle({"requestId": "r1", "op": "create_table_pages", "table": "student"})["ok"]
    alloc = db.handle({"requestId": "r1", "op": "allocate_page_for_table", "table": "student"})
    page_id = alloc["data"]["pageId"]
    encoded = base64.b64encode(page_bytes(9)).decode("ascii")
    result = db.handle({"requestId": "r2", "op": "write_page", "pageId": page_id, "data": encoded})
    assert result == {"ok": True, "data": {"requestId": "r2", "pageId": page_id, "dirty": True}}

    bad = db.handle({"requestId": "r3", "op": "get_page", "pageId": 999})
    assert bad["ok"] is False
    assert bad["error"]["code"] == "PAGE_NOT_ALLOCATED"
    assert bad["error"]["requestId"] == "r3"

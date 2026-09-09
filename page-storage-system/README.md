# ChainPage DB — Page Storage / OS Subsystem

`page-storage-system` is the operating-system-oriented storage layer of ChainPage DB. It follows both the course requirements and `paged-storage-spec.md`, and exposes a JSON-compatible interface to the database Storage Engine.

## Implemented scope

### Core course requirements

- **4KB paged file storage**: fixed-size page I/O, unique `pageId`, allocation/free and free-page reuse.
- **Persistent page metadata**: allocation state survives restart.
- **Buffer Pool**: cache hit/miss accounting, dirty-page write-back, explicit flush and eviction.
- **Replacement policies**: LRU and FIFO, including runtime policy switching.
- **Buffer event log**: `HIT / MISS / EVICT / FLUSH` events and counters.
- **Table Page Map**: persistent `table -> pageId[]` mapping.
- **Unified storage API**: Base64 page payloads and stable JSON success/error envelopes for database-engine integration.

### Repository-required extensions (7–11)

- **Slotted Page**: page header, stable slot IDs, free-space management, record insertion/read/deletion and compaction.
- **B+ Tree index pages**: persistent internal/leaf pages, leaf links, exact/range lookup, insertion split, deletion borrow/merge and root collapse.
- **WAL crash recovery**: durable before/after page images, write-ahead ordering, applied markers and startup redo of unflushed dirty pages.
- **Page concurrency control**: shared READ locks, exclusive WRITE locks, owner validation and conflict reporting.

## Architecture

```text
Database Storage Engine
        |
        v
StorageManager  <---- JSON / Base64 boundary
   |       |       |        |
   |       |       |        +--> BPlusTreeManager
   |       |       +-----------> SlottedPage
   |       +-------------------> TablePageMap
   v
BufferPool ----> LRU / FIFO
   |  |  \
   |  |   +--> LockManager
   |  +------> WALManager
   v
PageManager
   v
FileManager
   v
pages.dat
```

Persistent metadata/log files are stored beside `pages.dat`:

```text
page_allocation.json
table_pages.json
indexes.json
wal.log
```

## Main JSON operations

Core interface:

```text
get_page / write_page
create_table_pages / drop_table_pages
allocate_page_for_table / list_table_pages
flush_page / flush_all / storage_stats
```

Extended interface:

```text
set_policy / buffer_events
insert_record / read_record / delete_record / scan_records
create_index / drop_index / index_search / index_range / index_insert / index_delete
append_log / recover
lock_page / unlock_page
```

`requestId` is preserved when supplied. Cross-module page data is Base64 and must decode to exactly 4096 bytes. Table names are normalized to lowercase.

## Quick start

Run tests from this directory:

```bash
PYTHONPATH=. pytest -q
```

Python API:

```python
from storage import StorageManager

store = StorageManager('./data', capacity=16, policy='LRU')
store.create_table_pages('student')
page_id = store.allocate_page_for_table('student')['pageId']
row_id = store.insert_record(page_id, {'id': 1, 'name': 'Alice'})
store.flush_all()
```

JSON-compatible API:

```python
result = store.handle({
    'requestId': 'req-0001',
    'op': 'list_table_pages',
    'table': 'student',
})
```

## Tests

`tests/test_storage.py` covers:

- allocation/free/reuse and restart persistence;
- LRU/FIFO behavior and runtime switching;
- Base64 + error-envelope contract;
- Slotted Page insert/read/delete/reuse;
- shared/exclusive page locks;
- WAL replay after a simulated crash;
- B+ Tree split/search/range/delete/merge/restart;
- unique and non-unique index behavior.

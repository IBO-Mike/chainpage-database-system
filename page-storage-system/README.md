# ChainPage DB - OS / Page Storage Core

This module implements the course's operating-system-oriented storage layer:

- fixed 4KB page I/O;
- persistent page allocation and free-page reuse;
- Buffer Pool with LRU / FIFO replacement;
- dirty-page flush, hit/miss/eviction/flush statistics and event logging;
- persistent table-to-page mapping;
- unified JSON-compatible API for the database engine.

## Structure

```text
storage/
  constants.py
  errors.py
  file_manager.py
  page_manager.py
  replacement.py
  buffer_pool.py
  table_page_map.py
  storage_manager.py
tests/
  test_storage.py
```

## Quick start

Run from `page-storage-system/`:

```bash
PYTHONPATH=. pytest -q
```

```python
from storage import StorageManager

store = StorageManager("./data", capacity=16, policy="LRU")
store.create_table_pages("student")
page = store.allocate_page_for_table("student")
```

Cross-module callers should use `StorageManager.handle(request)` so request and response shapes stay JSON-compatible with `module-interfaces.md`.

## Scope

The official OS module requires paged storage, cache management, LRU/FIFO, statistics/logging, persistence and a unified storage interface. B+ Tree indexing, WAL/crash recovery and concurrency control can be implemented later as advanced extensions if the group chooses to keep the extended repository spec.

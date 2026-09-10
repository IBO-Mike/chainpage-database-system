# ChainPage DB — Page Storage / OS Subsystem (Java 17)

This directory is a pure Java implementation of the page-storage specification.
Maven builds the library, runs JUnit tests, and creates a self-contained JSONL CLI jar.

## Build and test

```bash
cd page-storage-system
mvn clean test
mvn package
```

Requirements: JDK 17+ and Maven 3.9+. Runtime JSON support is Jackson 2.18.2;
tests use JUnit 5.11.4. Run the CLI with:

```bash
java -jar target/storage-cli.jar --root ./storage-data --capacity 16 --policy LRU
```

It reads one UTF-8 JSON request per line and writes one response per line.

```text
read_at / write_at / sync / allocate_page / free_page / read_page
get_page / put_page / write_page / flush_page / flush_all / stats / storage_stats
record_insert / record_access / choose_victim / set_policy / buffer_events
create_table_pages / append_page / list_pages / allocate_page_for_table / list_table_pages / drop_table_pages
insert_record / read_record / delete_record / delete_rows / scan_records
create_index / drop_index / index_insert / index_delete / index_search / index_range
append_log / recover / lock_page / unlock_page
```

## Architecture and files

```text
StorageCli -> StorageManager
  -> BufferPool -> LRU/FIFO + LockManager
  -> PageManager -> FileManager -> pages.dat
  -> TablePageMap
  -> BPlusTree (page-backed nodes and linked leaves)
  -> WalManager + AtomicCoordinator
```

Persistent files beside `pages.dat` are `page_allocation.json`,
`table_pages.json`, `indexes.json`, `wal.log`, and `storage.lock`.
`operation.undo` exists only while a compound operation is outstanding.

## Durability and concurrency contract

- Page writes use durable redo WAL. A complete JSONL record is forced before a
  dirty frame reaches the page file. Recovery rejects corrupt complete records
  and sequence gaps; only an unterminated final record is discarded.
- Allocation generations prevent stale cache frames and old WAL records from
  affecting a freed and reused page ID.
- Table lifecycle and B+ tree mutations use a checksummed operation undo file.
  Page/metadata before-images and the prior WAL offset are durable before
  mutation. Resulting pages are flushed before commit. Startup restores an
  unfinished operation before redo, and restoration survives another crash.
- The public page WAL remains redo-only (`undone: 0`). Compound undo is an
  internal consistency mechanism, not a general SQL transaction/txId rollback.
- One process owns a storage directory (`STORAGE_BUSY` otherwise). Logical page
  conflicts return `PAGE_LOCK_BUSY` or `{granted:false,wait:true}` for retry.
- `close()` flushes and releases the directory. Reopening a path in one JVM
  retires its older instance (`STORAGE_CLOSED` on later use).

This implementation favors correctness for a course-sized database. Compound
operations snapshot the entire page file, so their time, memory and temporary
disk costs grow with database size. Serialized keys are limited to 1024 UTF-8
bytes; overflow pages for one non-unique key's RowId list are not implemented.

Files and replacement files are forced before success. Tests cover actual JVM
termination, restart, malformed storage, and thread contention. They do not
certify hardware power-loss behavior or disk-controller cache guarantees.

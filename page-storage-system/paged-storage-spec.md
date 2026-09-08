# 基本功能实现

## 统一数据约定

### 功能介绍

存储系统不理解 SQL，它只处理“第几页、页里放什么”。统一的数据约定像仓库编号规则，让上面的数据库模块能准确取到想要的纸箱。

### 输入与输出格式

所有接口接收和返回 UTF-8 JSON。成功返回 `{ "ok":true, "data":... }`，失败返回 `{ "ok":false, "error":... }`。错误格式固定为：

```json
{ "stage":"PAGE", "code":"PAGE_NOT_ALLOCATED", "message":"页不存在", "pageId":12 }
```

页大小固定为 `4096` 字节。`pageId` 是从 0 开始、全局唯一的非负整数。页内容跨接口使用 Base64 字符串表示，并且解码后必须刚好是 4096 字节；新分配的页内容全部为 `0x00`。

### 实现说明

对外暴露 JSON 接口，对内可使用字节数组、文件对象和类。调用者只依赖本规约中的字段，不得依赖具体文件名、内存地址或语言类型。

## 子模块清单与关系

本规约的子模块依次为：

1. File Manager
2. Page Manager
3. Buffer Pool
4. Replacement Policy
5. Table Page Map
6. 统一存储访问接口
7. 运行时策略切换与完整日志
8. 页内记录槽与空闲空间管理
9. B+ 树索引页
10. 崩溃恢复日志
11. 并发页访问控制

第 7 至 11 项均为必做。

基本调用关系如下：

```text
数据库 Storage Engine
        ↓ 调用 6. 统一存储访问接口
6. 统一存储访问接口
   ├── 3. Buffer Pool ──→ 4. Replacement Policy
   │       └────────────→ 2. Page Manager ──→ 1. File Manager ──→ 磁盘文件
   └── 5. Table Page Map ──→ 2. Page Manager（分配/释放）
```

File Manager 只负责文件偏移和磁盘字节；Page Manager 负责页号的分配、释放和合法性；Buffer Pool 负责把页暂存到内存；Replacement Policy 只选择被替换页，不直接读写磁盘；Table Page Map 记录表对应的页号集合；统一存储访问接口负责把这些能力组合起来。读请求从统一接口进入，通常经过缓存，未命中时向下读取；写请求先更新缓存并标记脏页，刷新时向下写回。所有子模块都可以单独用 JSON 请求测试。

## 1. File Manager

### 功能介绍

File Manager 是管理磁盘文件的管理员。它负责把“页号”换成文件中的固定位置，并负责可靠地读取和写入那一段字节。

### 输入与输出格式

输入操作：

```jsonl
{ "op":"read_at", "pageId":12 }
{ "op":"write_at", "pageId":12, "data":"4096字节的Base64" }
{ "op":"sync" }
```

`read_at` 成功返回 `{ "ok":true,"data":{ "pageId":12,"data":"Base64" } }`；`write_at` 返回 `{ "ok":true,"data":{ "pageId":12,"written":4096 } }`；`sync` 返回 `{ "ok":true,"data":{ "flushed":true } }`。访问未分配页必须报 `PAGE_NOT_ALLOCATED`，文件读写失败报 `FILE_IO_ERROR`。请求不得包含未定义字段。

### 实现说明

使用一个可配置的数据文件。第 `pageId` 页的偏移为 `pageId * 4096`。写入必须完整写满一页；必要时调用文件同步接口，保证已确认写入的数据在程序重启后仍可读取。

## 2. Page Manager

### 功能介绍

Page Manager 像图书馆管理员：它决定哪些页已经借出、哪些页空着，并提供按页号读写的基本能力。

### 输入与输出格式

输入操作：

```jsonl
{ "op":"allocate_page" }
{ "op":"free_page", "pageId":12 }
{ "op":"read_page", "pageId":12 }
{ "op":"write_page", "pageId":12, "data":"4096字节Base64" }
```

输出：`allocate_page` 返回 `{ "ok":true,"data":{ "pageId":整数 } }`；`free_page` 返回 `{ "ok":true,"data":{ "pageId":整数,"freed":true } }`；`read_page` 返回 `{ "ok":true,"data":{ "pageId":整数,"data":"4096字节Base64" } }`；`write_page` 返回 `{ "ok":true,"data":{ "pageId":整数,"written":4096 } }`。重复释放、访问已释放页和非 4096 字节写入都必须返回失败。失败后页分配状态不得改变；分配、释放状态必须持久化。

### 实现说明

维护一个持久化的页分配表或空闲页列表。`allocate_page` 优先复用空闲页，否则在数据文件尾部新增一页并写零；`free_page` 清除分配状态。Page Manager 可以调用 File Manager，但不得直接由数据库模块跳过它操作文件。

## 3. Buffer Pool

### 功能介绍

Buffer Pool 是内存中的“小书桌”。常用页先放在桌上，再读同一页就很快；桌子满了才按替换规则收走一页。

### 输入与输出格式

初始化输入：`{ "capacity": 正整数, "policy":"LRU | FIFO" }`。运行操作：

```jsonl
{ "op":"get_page", "pageId":12 }
{ "op":"put_page", "pageId":12, "data":"4096字节Base64", "dirty":true }
{ "op":"flush_page", "pageId":12 }
{ "op":"flush_all" }
{ "op":"stats" }
```

`get_page` 成功返回 `{ "ok":true,"data":{ "pageId":12,"data":"Base64","hit":布尔值,"dirty":布尔值 } }`。未命中时必须从 Page Manager 加载。`put_page` 返回 `{ "ok":true,"data":{ "pageId":12,"evicted":页号或null,"flushedPageId":页号或null } }`。`flush_page` 返回 `{ "ok":true,"data":{ "pageId":12,"flushed":true } }`；`flush_all` 返回 `{ "ok":true,"data":{ "flushedPageIds":[整数] } }`；`stats` 返回 `{ "ok":true,"data":{ "capacity":整数,"size":整数,"hits":整数,"misses":整数,"evictions":整数,"policy":"LRU | FIFO" } }`。

### 实现说明

缓存项至少保存页内容、脏标记和替换所需顺序信息。被换出的脏页必须先调用 Page Manager 写回。`flush_page` 和 `flush_all` 必须把脏页写回后清除脏标记。

## 4. Replacement Policy

### 功能介绍

替换策略负责在书桌满了时回答“该收走哪一页”。它只做选择，不读取磁盘、不修改页内容，因此可以单独开发和测试。

### 输入与输出格式

输入为事件流：

```jsonl
{ "op":"record_insert", "pageId":12 }
{ "op":"record_access", "pageId":12 }
{ "op":"choose_victim", "residentPageIds":[1,5,12] }
```

`record_insert` 和 `record_access` 成功输出 `{ "ok":true,"data":{ "recorded":true } }`；`choose_victim` 成功输出 `{ "ok":true,"data":{ "pageId":整数 } }`。候选为空、候选含重复页号或页未登记时返回 `BUFFER_POLICY_INVALID_STATE`。FIFO 中 `record_access` 不改变淘汰顺序；LRU 中每次成功访问都把该页更新为最新使用。

### 实现说明

FIFO 可用队列，LRU 可用有序字典或双向链表加哈希表。Buffer Pool 通过事件调用该模块；替换策略不应了解缓存项是否为脏页。

## 5. Table Page Map

### 功能介绍

Table Page Map 是“表到页号的目录”。它让存储引擎知道一张逻辑表的数据分别放在哪些物理页里。

### 输入与输出格式

输入操作：

```jsonl
{ "op":"create_table_pages", "table":"student" }
{ "op":"append_page", "table":"student", "pageId":12 }
{ "op":"list_pages", "table":"student" }
{ "op":"drop_table_pages", "table":"student" }
```

成功输出的 `data` 分别为 `{ "table":"student","pageIds":[] }`、更新后的 `{ "table":"student","pageIds":[12] }`、完整 `{ "table":"student","pageIds":[12,13] }`、`{ "table":"student","removed":true,"pageIds":[被移除页号] }`。不存在的表返回 `STORAGE_TABLE_NOT_FOUND`，重复创建或重复追加同一页返回 `STORAGE_TABLE_EXISTS`。映射更新必须在返回成功前持久化。

### 实现说明

基本版可使用一个单独的元数据文件，原子覆盖或追加日志以避免重启后映射丢失。页的释放由上层确认后逐个调用 Page Manager；该模块只维护映射，不自行清除数据页。

## 6. 统一存储访问接口

### 功能介绍

这是给数据库存储引擎使用的总入口。它把“缓存、页管理、文件管理、表页映射”藏在后面，上层只需要请求页和刷新数据。

### 输入与输出格式

必须提供以下接口，语义与前述模块一致：

```jsonl
{ "op":"get_page", "pageId":12 }
{ "op":"write_page", "pageId":12, "data":"4096字节Base64" }
{ "op":"create_table_pages", "table":"student" }
{ "op":"drop_table_pages", "table":"student" }
{ "op":"allocate_page_for_table", "table":"student" }
{ "op":"list_table_pages", "table":"student" }
{ "op":"flush_all" }
{ "op":"storage_stats" }
```

`get_page` 返回 `{ "ok":true,"data":{ "pageId":整数,"data":"4096字节Base64","hit":布尔值,"dirty":布尔值 } }`；`write_page` 返回 `{ "ok":true,"data":{ "pageId":整数,"dirty":true } }`。`create_table_pages` 建立空映射并返回 `{ "ok":true,"data":{ "table":字符串,"pageIds":[] } }`；`drop_table_pages` 释放映射中的页并返回 `{ "ok":true,"data":{ "table":字符串,"removed":true,"freedPageIds":[整数] } }`。`allocate_page_for_table` 必须分配新页、加入该表映射并返回 `{ "ok":true,"data":{ "table":字符串,"pageId":整数,"pageIds":[整数] } }`。`list_table_pages` 返回 `{ "ok":true,"data":{ "table":字符串,"pageIds":[整数] } }`；`flush_all` 返回 `{ "ok":true,"data":{ "flushedPageIds":[整数] } }`；`storage_stats` 返回 `{ "ok":true,"data":{ "capacity":整数,"size":整数,"hits":整数,"misses":整数,"evictions":整数,"allocatedPages":整数 } }`。任何下层失败都必须返回统一错误包络。

### 实现说明

这个模块只做组合与转发，不重复实现缓存或页逻辑。写入流程为：取得缓存页或创建缓存项，更新内容并标脏；刷新流程为：缓存写回 Page Manager，再由 File Manager 落盘。

# 拓展功能实现

## 7. 运行时策略切换与完整日志

### 功能介绍
允许选择 LRU 或 FIFO，并记录缓存命中、换出和刷盘过程。

### 输入与输出格式
初始化输入 `{ "capacity":正整数,"policy":"LRU"或"FIFO","log":true或false }`；事件输出 `{ "event":"HIT"或"MISS"或"EVICT"或"FLUSH","pageId":整数,"victimPageId":整数或null }`。统计输出必须包含 `hits,misses,evictions,flushes`。

### 实现说明
在既有 Buffer Pool 事件点调用日志记录器，策略仍由 Replacement Policy 实现。

## 8. 页内记录槽与空闲空间管理

### 功能介绍
让一页不只保存原始字节，还能独立找到、插入和删除其中的每条记录。

### 输入与输出格式
`insert_record({ "pageId":整数,"row":Row })` 返回 `{ "pageId":整数,"slotId":整数,"freeBytes":整数 }`；`read_record({ "pageId":整数,"slotId":整数 })` 返回 `{ "row":Row,"deleted":false }`；`delete_record` 返回 `{ "pageId":整数,"slotId":整数,"deleted":true }`。页空间不足、槽不存在必须失败。

### 实现说明
页头保存槽数量和空闲区间；槽保存记录偏移与长度。所有修改先进入 Buffer Pool 并标记脏页。

## 9. B+ 树索引页

### 功能介绍
用树形索引快速找到键对应的数据页，范围查询时沿叶子页链表顺序读取。

### 输入与输出格式
`index_search({ "indexId":整数,"key":标量 })` 返回 `{ "rowIds":[RowId] }`；`index_insert({ "indexId":整数,"key":标量,"rowId":RowId })` 返回 `{ "inserted":true }`；`index_delete` 返回 `{ "deleted":true }`。键类型错误、索引不存在或重复键违反约束必须失败。

### 实现说明
非叶节点存键和子页号，叶节点存键和 RowId，并维护前后叶指针；分裂和合并产生的页都走统一页分配和缓存接口。

## 10. 崩溃恢复日志

### 功能介绍
程序突然停止后，使用日志把页恢复到一致状态，避免只写了一半的数据。

### 输入与输出格式
`append_log({ "txId":整数,"pageId":整数,"before":PageData,"after":PageData })` 返回 `{ "logSeq":整数,"durable":true }`；`recover({})` 返回 `{ "replayed":整数,"undone":整数,"clean":true }`。日志内容长度错误或序号断裂必须失败。

### 实现说明
先持久化日志再确认脏页写入；启动时按序重放未完成记录，恢复后调用 sync。

## 11. 并发页访问控制

### 功能介绍
让多人同时读写时不会互相踩坏同一页：读可以共享，写必须独占。

### 输入与输出格式
`lock_page({ "pageId":整数,"mode":"READ"或"WRITE","owner":字符串 })` 返回 `{ "granted":true }`；`unlock_page` 返回 `{ "released":true }`；无法获得锁时返回 `{ "granted":false,"wait":true }` 或超时错误。owner 不能为空，解锁者必须是持有者。

### 实现说明
为每页维护读者集合和写者；Buffer Pool 的读、写、刷盘分别在正确的锁范围内执行，最后通过 unlock 释放。

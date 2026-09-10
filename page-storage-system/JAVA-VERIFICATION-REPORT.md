# OS / Page Storage Java 验证报告

验证对象：`IBO-Mike/chainpage-database-system` 的 `os-storage-core` 分支，基线提交 `7a4198f117f6716b35256531dd905e554badedca`。验证环境为 Windows 11、Temurin OpenJDK 17.0.20.1、Maven 3.9.16。本次按要求将 `page-storage-system` 统一重写为 Java 17；Python 源码和 Python 测试已移除。

# 总体结论

**PASS**

当前 Java 实现通过自动测试、真实 JVM 强制中止恢复测试、第二 JVM 目录互斥测试和两次独立 JSONL CLI 启动验证。没有遗留已知功能正确性缺陷。实现仍有课程规模下可接受、生产规模下需处理的性能和耐久性限制，见“仍存在的风险”。

# 自动测试结果

执行命令：

```text
mvn -q clean test
```

结果：**32 项测试，32 通过，0 失败，0 错误，0 跳过**。

| 测试集 | 总数 | 通过 | 失败 | 用时 |
|---|---:|---:|---:|---:|
| `StorageSystemTest` | 11 | 11 | 0 | 35.25 s |
| `AdvancedVerificationTest` | 10 | 10 | 0 | 22.54 s |
| `CrashRecoveryTest` | 11 | 11 | 0 | 18.71 s |

失败用例原因：**无失败用例**。

`CrashRecoveryTest` 不是模拟异常返回，而是在 10 个写入点启动独立 JVM 并调用 `Runtime.halt(61)`：operation journal 已持久化后、页分配后、不同索引页写入后、提交前、页释放中，以及表分配/删除中。另有一次恢复过程中再次强制中止，随后第三次启动完成幂等恢复。

# 手工验证结果

## A. Page Manager — PASS

- 连续分配得到页 0、1；释放页 0 后下一次分配复用页 0，并重新写成 4096 个零字节。
- 负 pageId、未分配 pageId、重复释放、读取已释放页均返回稳定错误。
- 非 4096 字节写入被拒绝，失败后分配表不变。
- 关闭并重新构造 `StorageManager` 后，分配集合、空闲集合和页 generation 保留。
- 被表或索引引用的页不能通过低层 `free_page` 释放，避免悬空元数据。

## B. Buffer Pool — PASS

- 首次读取为 MISS，重复读取为 HIT；命中、未命中、淘汰和刷新计数与事件一致。
- 写入先成为 dirty frame；`flush_page` 和 `flush_all` 写回后清除 dirty。
- 容量 2 的 LRU 序列 `A,B,A,C` 淘汰 B；同一序列的 FIFO 淘汰 A。
- dirty victim 在淘汰前写回，磁盘读取验证了写回内容。
- 外部 READ 锁存在时写入、刷新和释放返回 `PAGE_LOCK_BUSY`；锁释放后成功。

## C. Table Page Map — PASS

- 建立空映射、为表分配页、追加已分配页、查询和删除均符合包络。
- 表名按接口要求归一化为小写，比较不区分大小写。
- 同一物理页不能被两个表引用；启动时也会拒绝未分配页、重复引用和非整数元数据。
- 删除表通过可恢复的复合操作释放全部页；中途崩溃不会留下部分删除。
- 重启后映射仍存在。

## D. Unified Storage API — PASS

- `requestId` 在成功和失败包络中原样返回；不适用的错误字段显式为 `null`。
- 4096 字节数据的 Base64 编码、解码及长度校验通过。
- 成功固定为 `ok/data`，失败固定为 `ok/error`。
- 每个操作拒绝未定义字段；字段类型也进行严格校验。
- 已补齐 `module-interfaces.md` 中的原子 `delete_rows`：先验证所有 RowId，再只删除指定槽；任一 RowId 非法时整批回滚。

## E. Slotted Page — PASS

- 插入 30 条记录、随机读取、扫描、删除及重启读取通过。
- 删除槽不能读取；新记录会复用已删除 slotId。
- `freeBytes` 随写入下降，删除后反映可复用记录空间，且始终扣除槽目录成本。
- 超过一页的记录返回 `PAGE_NO_SPACE`；Row 仅接受字符串字段名和 INT/VARCHAR 值。

## F. B+ Tree — PASS

- 连续插入 100 个 INT key 和 40 个长 VARCHAR key，均触发多叶页和内部节点。
- 精确查询、闭区间范围查询、唯一约束、非唯一 RowId 列表和删除通过。
- 删除 79/80 个 key 后完成重新分配/合并并把根收缩为单叶根。
- 全范围查询验证叶链顺序；重启后索引仍可读。
- `validateIndex` 检查最大/最小占用、排序、子节点数量、separator 路由、所有叶深度一致、页唯一且已分配、前后叶指针、RowId 和 key 类型。
- 当前采用确定性的整树重平衡来实现 split、redistribution/borrow、merge 和 root collapse，结果满足 B+ Tree 不变量。

## G. WAL / Crash Recovery — PASS

- UPDATE JSONL 记录通过 `FileChannel.force(true)` 持久化后，dirty page 才允许落盘。
- before/after image 必须恰好 4096 字节。
- “日志已写、页面未落盘”后重新启动，页面恢复为 after image。
- 8 线程并发追加 80 条记录得到连续且唯一的 1..80 序号。
- 完整但损坏的 WAL 行会阻止启动；仅最后一条没有换行的半记录会被截断。
- redo 后记录 APPLIED；重复启动和恢复中再次崩溃均保持幂等。
- 对外 WAL 是 **redo-only**，因此 `recover` 固定返回 `undone:0`。表和索引复合操作另用内部 before-image `operation.undo` 恢复，但这不是通用 txId undo。

## H. Concurrency — PASS

- 多个 owner 可同时持有 READ；WRITE 为独占。
- READ 存在时 WRITE 返回 `{granted:false,wait:true}`，WRITE 存在时 READ 同样等待/重试。
- 非 owner 解锁返回 `LOCK_NOT_OWNER`。
- Buffer Pool 的 get/put/evict/flush/free 路径均取得相应 READ/WRITE 页锁，并用 try-with-resources 释放内部锁。
- 第二个 JVM 打开相同目录返回 `STORAGE_BUSY`；同一 JVM 重开会安全退休旧实例。

# JSONL CLI 验证

使用构建产物 `target/storage-cli.jar`，以容量 2、LRU 策略运行。第一次进程依次执行：

```text
create_table_pages
allocate_page_for_table
get_page
write_page
storage_stats
flush_all
list_table_pages
```

所有响应均为 `ok:true`。新页初始数据 SHA-256 为 `ad7facb2586fc6e966c004d7d1d16b024f5805ff7cb47c7a85dabd8b48892ca7`（4096 个零字节）。写入 4096 个 `0x5a` 并刷新后退出。

第二次独立启动执行 `get_page`，得到 SHA-256 `f302957da5220938a7e3e51a8718c79b9e00dc13ab2119e8cfc978f041720382`（4096 字节），证明已确认写入跨进程存在。`list_table_pages` 仍返回 `[0]`。随后 `drop_table_pages` 返回 `freedPageIds:[0]`，再次 `get_page` 返回 `PAGE_NOT_ALLOCATED`。

退出后的实际文件：

| 文件 | 大小 | 核验 |
|---|---:|---|
| `pages.dat` | 4096 | 一页物理空间；逻辑页已释放 |
| `page_allocation.json` | 84 | `allocated:[]`、`free:[0]`、generation 1 |
| `table_pages.json` | 3 | 空对象，表已删除 |
| `indexes.json` | 3 | 空对象 |
| `wal.log` | 11045 | 2 行：UPDATE + APPLIED |
| `storage.lock` | 0 | OS 文件锁载体 |

成功结束后不存在 `operation.undo`。

# 与 paged-storage-spec.md 的符合度

| 项 | 模块 | 结论 | 依据 |
|---:|---|---|---|
| 1 | File Manager | PASS | 固定偏移、整页读写、sync、未分配页检查、独立 JSON 操作 |
| 2 | Page Manager | PASS | 分配/释放/复用/零页/严格长度/持久化/generation |
| 3 | Buffer Pool | PASS | hit/miss、dirty、刷新、容量、统计和写回淘汰 |
| 4 | Replacement Policy | PASS | 独立 LRU/FIFO 状态机及候选校验 JSON 操作 |
| 5 | Table Page Map | PASS | 原子持久化、严格所有权、重启、完整删除 |
| 6 | 统一存储接口 | PASS | 必需操作、Base64、统一包络和错误传递 |
| 7 | 策略切换与日志 | PASS | 运行时切换、HIT/MISS/EVICT/FLUSH 事件和四项统计 |
| 8 | Slotted Page | PASS | 槽目录、复用、空间检查、Buffer Pool dirty 路径 |
| 9 | B+ Tree | PASS | 页式节点、叶链、分裂/重平衡/合并/根收缩及验证器 |
| 10 | WAL / Recovery | PASS | WAL-before-data、连续序号、redo、幂等和损坏拒绝 |
| 11 | Concurrency | PASS | 共享读、独占写、owner、内部锁、进程目录租约 |

# 与 module-interfaces.md 的符合度

**PASS**。跨模块只使用 UTF-8 JSON 支持的类型；表名转成小写；页使用 Base64；`requestId` 原样返回。错误对象固定包含 `requestId`、`statementIndex`、`stage`、`code`、`message`、`line`、`column`、`pageId`，不适用字段为 `null`。`get_page` 的 hit/dirty 来自实际 Buffer Pool，`write_page` 先标脏，表页创建、分配、查询、删除和 `flush_all` 的字段与文档一致。`delete_rows` 只删除传入 RowId，并作为一个可恢复操作提交。

# 发现的问题

## Critical

- **复合元数据和数据页存在部分提交窗口。** 复现：在 `allocate_page_for_table`、`drop_table_pages` 或 B+ Tree 重建的页分配/释放中强制结束进程。最小修复位于 `AtomicCoordinator.operation/restore`：先强制落盘带 SHA-256 的全量 before-image 和 WAL offset，完成全部页/元数据并刷新后才删除 journal；启动先恢复 journal 再做 WAL redo。
- **已释放并复用的 pageId 可能被旧缓存或旧 WAL 覆盖。** 复现：写入未刷新的页，释放并复用相同 pageId，再恢复旧日志。最小修复位于 `PageManager`、`BufferPool`、`WalManager`：持久化 allocation generation，释放时先驱逐 frame，WAL 记录 generation，恢复时跳过旧代记录。

## Major

- **B+ Tree 尾节点可能低于占用下限，且缺少可执行不变量检查。** 复现：插入 17 个 INT key 或大量插入后删除至根收缩。修复位于 `BPlusTree.rebuild/validate`：平衡分组、全树重平衡、叶链和 separator 校验、根收缩。
- **刷新和释放路径曾未完整取得页 WRITE 锁。** 复现：owner A 持有 READ 后，owner B 调用 `flush_all` 或 `free_page`。修复位于 `BufferPool.flushAll/beforeFree`：所有写回和驱逐路径统一取得 WRITE lease，冲突时返回 `PAGE_LOCK_BUSY`。
- **表、索引和直接释放之间缺少物理页所有权约束。** 复现：把同一 pageId 追加到两个表，或直接释放仍被表/索引引用的页。修复位于 `TablePageMap.load/append`、`BPlusTree.load/validateDisjoint`、`StorageManager.freePage`。
- **同一目录可被第二个实例破坏并发假设。** 复现：保持一个 JVM 打开目录，再由另一个 JVM 打开。修复位于 `DirectoryLease`：OS 文件锁、进程内实例登记以及活动操作 guard。

## Minor

- **共享 Jackson mapper 的 pretty-print 开关存在竞态。** 修复位于 `JsonFiles.compact`，改用不可变 writer 配置。
- **JSON 元数据曾接受可转换的浮点 pageId。** 修复位于 `PageManager.readIds` 和 `TablePageMap.load`，要求真正的 JSON 整数。
- **`module-interfaces.md` 的 `delete_rows` 未暴露。** 修复位于 `StorageManager.deleteRows` 和 `StorageCli`，并增加失败全回滚测试。

# 已修复的问题

上述 Critical、Major、Minor 项均已修复，并纳入自动回归或真实进程崩溃矩阵。没有进行与问题无关的大型框架引入；运行时仅使用 Java 17 和 Jackson。

# 仍存在的风险

- B+ Tree 变更使用确定性的整树重建，正确性清晰，但单次变更是 O(N)，大型索引会产生明显写放大。
- `operation.undo` 保存整个页文件的 before-image；数据库增大后会占用接近一份数据库的额外临时磁盘和内存。
- WAL 尚无 checkpoint/截断策略，会持续增长。
- 单个非唯一 key 的 RowId 列表没有 overflow page；超过一个索引页时会明确返回 `INDEX_PAGE_OVERFLOW`，不会静默损坏。
- 页锁是非阻塞重试模型，没有公平等待队列，竞争很高时可能饥饿。
- Windows 无通用目录 fsync 接口；文件内容和文件自身已 force，但硬件断电语义仍取决于文件系统和存储控制器。

# 最终可否合并 main

**可以合并 main。** 合并依据是当前分支上的 32/32 自动测试、真实 JVM 崩溃恢复矩阵、跨进程锁验证和两次 CLI 持久化验证全部通过。合并时应保留本报告列出的规模限制；若目标是生产级大数据量，再单独安排增量 B+ Tree 更新、overflow page、WAL checkpoint 和更强的磁盘故障注入。

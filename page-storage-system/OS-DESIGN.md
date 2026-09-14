# OS 存储部分设计说明

## 目标与边界

OS 部分将上层对记录的访问转化为固定 4096 字节数据页的持久化读写，并通过缓存减少页文件读取。对外提供 Java `StorageManager` 和 UTF-8 JSONL `StorageCli`。存储模块不解析 SQL，不创建 SQL 执行计划，也不负责 SQL Catalog 的业务结构。

本分支只有 SQL 编译器、数据库引擎的规约，没有这两部分的运行代码。因此 OS 接口可单独验证，但完整 SQL 链路仍需与组员的实现联调。

## 模块职责与数据结构

| 模块 | 职责 | 核心结构或文件 |
|---|---|---|
| StorageManager | 组合各模块，串行协调调用和复合操作 | 可重入 guard，目录租约 |
| FileManager | 按 `pageId * 4096` 定位并完整读写页 | FileChannel，pages.dat |
| PageManager | 分配、释放、复用和检查页号 | allocated/free 有序集合，generation 映射 |
| BufferPool | 命中检查、缓存写入、脏页刷盘和淘汰 | `HashMap<Integer, Frame>`，dirty，WAL 序号列表 |
| ReplacementPolicy | 根据驻留页集合选 victim | LRU 用访问顺序 LinkedHashMap，FIFO 用插入顺序 LinkedHashSet |
| TablePageMap | 表名到有序页列表的持久化映射 | table_pages.json，页独占检查 |
| SlottedPage | 页内变长记录布局、槽复用和空间管理 | 页头、槽目录和记录数据区 |
| BPlusTree | 点查、闭区间范围查和索引变更 | 页式节点，双向叶链，indexes.json |
| WalManager | UPDATE/APPLIED 日志与 REDO | wal.log，连续 logSeq，页 generation |
| AtomicCoordinator | 多文件操作的提交与恢复 | 带 SHA-256 的 operation.undo 和 WAL offset |
| LockManager | 按逻辑 owner 管理页级共享读和独占写 | reader 引用计数，writer 重入计数 |
| DirectoryLease | 避免多个进程同时打开同一目录 | storage.lock 的操作系统文件锁 |

Java 集合的哈希、冲突处理和扩容由 JDK 实现。项目负责页映射和顺序维护，没有自行实现哈希表。

## 核心流程

### 读页

`StorageManager.getPage` 检查目录租约和健康状态，取得全局 guard，再交由 BufferPool 取得页 READ 锁。缓存帧 generation 与当前分配代数不一致时丢弃旧帧。命中时更新访问顺序并返回数据副本；未命中时从 PageManager/FileManager 读取，必要时淘汰 victim，再登记新帧。

返回副本避免调用者直接修改缓存里的字节而绕过 dirty 和 WAL。LRU 的 `get` 更新访问顺序；FIFO 的读取不更新插入顺序。

### 写页与失败处理

先校验页长度、分配状态和非负 txId，之后取得 WRITE 锁。计算 before image 与最终 dirty 状态。未驻留时先完成容量检查和必要淘汰，随后持久化 UPDATE，最后才发布新的缓存字节和序号。

本次修正将 txId 校验前移，并把日志追加放在缓存字节更新之前。非法 txId 不改变缓存内容、驻留集合、替换顺序和统计；WAL 追加失败不发布新的未记录脏页。若日志 I/O 失败前已经淘汰其他页，该合法淘汰不会回滚；单页写入不承诺所有缓存管理副作用的事务回滚。

`putPage(dirty=false)` 用于缓存装载等已有内容的登记，不应当用它代替需要持久化的新数据写入。上层修改数据使用 `write_page` 或 dirty=true。

### 刷盘与淘汰

dirty victim 先写入页文件并同步，再追加对应 APPLIED 标记，最后清除 dirty 和帧内序号。这样崩溃发生在写页与标记之间时，恢复可重复 REDO。generation 已变化的帧不能写回新分配的同号页。

低层 `read_at/write_at` 是直接页文件 I/O；`get_page/write_page` 是缓存路径。上层正常读写应统一走缓存路径，不在驻留页上随意混用直接写入与缓存读取。本次不更改规约中低层接口的语义。

### 页分配与记录布局

优先使用最小空闲页号，复用前写零并增加 generation，否则在文件尾追加零页。分配表持久化失败时恢复内存集合，并处理新增物理页。释放页前检查表或索引是否仍引用该页，再使缓存失效并更新分配表。

SlottedPage 页头为 10 字节，每槽为 8 字节。槽目录向后增长，记录字节从页尾向前排列，中间为空闲区。序列化会紧凑重排记录数据，但保留活跃 slotId；删除槽可被后续插入复用。RowId 是 pageId 与 slotId，槽复用后旧 RowId 不具有永久身份保证。

### B+ 树

点查沿分隔键找到叶节点，再二分搜索；范围查从起始叶沿 next 链读取，包含两端。当前变更收集有序条目后整树重建：先写叶层，再逐层写内部节点，公布新根后释放旧页，最后检查深度、路由、叶链和页所有权等不变量。

这是一种课程规模的确定性重平衡实现，不是增量插入时的局部分裂/借位算法。单次变更为 O(N)，全文件 undo 快照还会增加开销。不可宣称已验证大规模索引性能优化。

### 崩溃恢复与复合操作

单页日志为 REDO-only。恢复按序处理未 APPLIED 的 UPDATE，跳过已释放、generation 不同或早于同代页已应用高水位的日志。完整损坏记录和序号缺口拒绝启动，末尾未换行半记录会被截去。

表生命周期、批量删除和索引变更进入 AtomicCoordinator：先刷新基线，保存数据页/元数据 before image 和 WAL offset，再执行任务；完成后刷新并删除 undo journal 作为提交边界。启动先恢复未完成复合操作，截去它的 WAL，再加载各模块并执行 REDO。它不等价于通用 SQL 事务回滚。

### 并发边界

StorageManager 的 guard 串行协调模块，可重入以允许复合操作调用公共方法；冲突返回可重试错误。页锁允许不同 owner 共享 READ，WRITE 排斥其他 owner，可重入。没有阻塞等待队列、公平性、死锁检测或 MVCC。不同进程通过目录文件锁互斥，同 JVM 重开会退休旧实例。

## 联调约定

上层通过 JSONL 发送 `create_table_pages`、`allocate_page_for_table`、`get_page`、`write_page`、`flush_all` 等操作，或使用对应 Java facade。页字节以 Base64 编码，必须解码为 4096 字节。表名大小写不敏感，成功固定 `ok/data`，失败固定 `ok/error`，requestId 原样返回，不适用错误字段为 null。

新测试启动真实 StorageCli 子进程，模拟上层调用者创建表、分配页、写入、刷新，退出后再启动读取数据和映射。这证明 OS 边界可用，不能替代真实 SQL 编译器和执行引擎的端到端联调。

## 答辩准备

按目标、分层、读写流程、替换策略、恢复边界介绍，再打开对应代码。建议准备以下实际操作：

1. 将缓存容量 2 改为 1，先预测 A B A C 的 miss 和 eviction，再运行固定序列测试。
2. 指出 LinkedHashMap 的 accessOrder=true 如何影响 LRU，解释 FIFO 为什么不更新读顺序。
3. 展示非法 txId 的失败路径，解释为什么要在修改缓存前检查，以及 UPDATE 为什么要先于脏字节发布。
4. 解释 generation 如何阻止旧页日志覆盖复用页，区分 REDO 和 operation.undo。
5. 展示 BPlusTree.rebuild 和 validate，诚实说明整树重建的复杂度与限制。

现场熟悉程度、独立定位问题能力和个人贡献必须由本人演示和 Git 记录证实，文档不能代替答辩。

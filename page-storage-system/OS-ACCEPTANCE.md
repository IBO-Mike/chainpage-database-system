# OS 评分验收用例与预期结果

## 执行方法

环境为 JDK 17 和 Maven。在 page-storage-system 目录执行：

```text
mvn -B -Dtest=RubricAcceptanceTest test
mvn -B clean verify
```

第一条快速运行 7 个新增验收测试；第二条运行全部测试并生成独立 CLI jar。测试每次使用隔离临时目录，不修改实际数据库。缓存实验产物为 `target/rubric-cache-comparison.json`。

## 基本功能预设用例

| 编号 | 检查项 | 输入或操作 | 预期结果 | 自动测试 |
|---|---|---|---|---|
| P01 | 分配释放复用 | 分配 0、1；写页 1；释放 0；重开 | 页 0 可复用且为零页，页 1 数据保留 | StorageSystemTest.allocationFreeReuseAndRestart |
| P02 | 边界和空间 | 错误页号、被引用页释放、超大记录、删除槽读取 | 稳定错误码，引用和数据不被破坏 | AdvancedVerificationTest 的所有权、空间测试 |
| P03 | 未刷盘恢复 | 写 dirty 页后重新打开 | after image 恢复，replayed=1 | StorageSystemTest.redoAfterUnflushedWrite |
| C01 | LRU 与 FIFO | 容量 2，冷缓存，A B A C | 两者 1 hit、3 miss、1 eviction；LRU 淘汰 B，FIFO 淘汰 A | RubricAcceptanceTest.fixedSequenceHasDifferentLruAndFifoVictims |
| C02 | dirty 回写 | 容量 1，写 A=42，再读 B | A 淘汰前写回，FLUSH 事件先于 EVICT | RubricAcceptanceTest.dirtyVictimIsWrittenBackBeforeEviction |
| I01 | 统一接口和持久化 | 真实 CLI 创建表、分配、写、flush；重启 get/list | data 和映射保留，requestId 原样返回 | RubricAcceptanceTest.actualCliSupportsAnUpperLayerCallerAndRestart |
| I02 | 错误包络 | CLI get 未分配页 999 | ok=false，PAGE_NOT_ALLOCATED，null 字段保留 | 同 I01 及 AdvancedVerificationTest.standaloneJsonContractsAndStrictFields |
| F01 | 非法事务号 | 对驻留页和非驻留页 write(tx=-1) | WAL_INVALID_TX；不改缓存内容、统计或替换顺序 | RubricAcceptanceTest 前两个 rejectedTransaction 测试 |
| F02 | 日志 I/O 失败 | 暂时移走 WAL 后写驻留页 | FILE_IO_ERROR；仍为原字节、clean；恢复 WAL 后 flush 不写错误数据 | RubricAcceptanceTest.walFailureDoesNotPublishAnUnloggedDirtyImage |

## 扩展验收

| 检查项 | 操作与预期 | 自动测试 |
|---|---|---|
| 记录槽 | 30 条记录读取扫描，删除后拒绝访问，重启后 29 条 | StorageSystemTest.slottedRecordsPersist |
| 原子批量删除 | 只删传入 RowId；任意非法项时整批回滚 | AdvancedVerificationTest.batchDeleteIsExactAndAtomic |
| B+ 树 | 多叶/内部节点、点查、闭区间查询、删除根收缩、叶链不变量 | 两个现有测试集中的 B+ 树测试 |
| WAL 规则 | 旧日志不覆盖较新已应用内容；完整损坏拒绝，半尾截断 | StorageSystemTest.staleRedoCannotOverwriteNewerApplied；AdvancedVerificationTest 的 WAL 损坏测试 |
| 崩溃矩阵 | 10 个写入位置强制 halt，重启恢复原快照；恢复中再次崩溃仍可恢复 | CrashRecoveryTest 的 11 项测试 |
| 并发页锁 | 共享 READ、排他 WRITE、owner 检查；flush/free 尊重外部锁 | StorageSystemTest 和 AdvancedVerificationTest 的锁测试 |
| 进程目录互斥 | 第二 JVM 打开同一路径返回 STORAGE_BUSY | AdvancedVerificationTest.operatingSystemLeaseRejectsSecondJvm |

## 缓存对比实验的预期值

容量 2，三页初始数据分别为字节 1、2、3。序列 A B A C A B 重复 100 次，共 600 次，所有模式逐次比较完整页内容。

| 模式 | hit | 页文件读取次数 | 相对直接读取减少 |
|---|---:|---:|---:|
| DIRECT | 0 | 600 | 0 |
| FIFO | 298 | 302 | 49.67% |
| LRU | 398 | 202 | 66.33% |

FIFO 第一轮 miss=5，后续每轮 miss=3，因此为 5+99×3=302；LRU 第一轮 miss=4，后续每轮 miss=2，因此为 4+99×2=202。LRU 比 FIFO 少 100 次页文件读取，减少 33.11%。这只证明给定热点序列下的效果，不表示 LRU 在所有访问模式下更优。

elapsedNanos 记录实际耗时，但包含测试断言、数据比较、JVM 执行和操作系统文件缓存影响，不设置耗时通过阈值。页文件读取次数不是硬件物理磁盘读取次数，也不是项目代码优化前后版本比较；这里比较的是直接 I/O、FIFO 和 LRU 三种现有模式。

## 已完成真实三模块联调

使用最新 main 的编译器、引擎和适配器，在独立 detached worktree 中配合 OS 分支版本，完成根 Maven clean verify 的 212 项测试。verification/verify-sql-integration.ps1 对真实数据库 jar 执行 CREATE、INSERT、SELECT、DELETE、compile 和错误请求，并以第二 JVM 验证重启后表结构、删除结果和后续读写。版本、预期值、实际响应与复现步骤见 OS-INTEGRATION.md。

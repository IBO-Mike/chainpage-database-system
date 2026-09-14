# OS 性能实验与结果分析

2026-09-14，Windows 11、Temurin JDK 17.0.20.1、Maven 3.9.16。基线为 `89ed0c458d911c8001115174af1dd8d02c25cbc7`（改进前 core），改进 core 为 `c2f7309dbf4ddeef1688476d4b232176bd74bf87`。同一独立实验程序分别加载两个 shaded JAR，只调用两版共有的公开接口。JAR 和源码摘要见 verification/highest-standard-2026-09-14/manifest.json。

## 增量索引变更与整树重建基线

容量 8、LRU、唯一 INT，Random(4711) 打乱 128 个键后逐一插入，再删除 0–95。每轮独立临时数据库；一轮预热不计入，五轮有效样本。插入、删除后完整范围结果与独立有序预期一致，并验证树不变量。下表为五轮中位数；写页为 dirty flush 次数，不是包括 WAL/元数据/undo 的全部 I/O。

| 操作 | 基线写页 | 改进写页 | 写页减少 | 基线秒 | 改进秒 | 本机耗时比 |
|---|---:|---:|---:|---:|---:|---:|
| 插入 128 键 | 688 | 155 | 77.47% | 28.195 | 2.554 | 11.04× |
| 删除 96 键 | 618 | 248 | 59.87% | 24.670 | 3.303 | 7.47× |

局部分裂/重平衡避免每次改动写出整棵树。该效果适用于本实验的唯一 INT；不推断变长/非唯一删除也有相同收益。整体操作仍包括全树验证和全文件 undo，不能宣称整体 O(log N)。基线运行期间有一次本地回归构建重叠，系统负载与 Windows 文件扫描未严格控制，因此耗时比仅作为观察；确定的算法写页计数比耗时更可靠。

最初两次工作区内基线实验遇到 Windows AccessDeniedException，未产生有效样本，未纳入比较。最终基线和改进都使用同样的系统临时目录策略成功完成。改进后的文件替换增加有限 AccessDenied 重试，其他 I/O 错误仍传播。

## 四种缓存负载

每负载 1024 次访问、32 页、容量 8、固定 Random(20260914)。DIRECT/FIFO/LRU/CLOCK 各一轮预热、三轮有效样本，共 48 个有效样本；逐次验证完整 4096 字节。每轮新实例、冷 BufferPool，初始化写入排除在访问计时外。表中页文件读取为三轮中位数（本次各轮计数一致）。

| 负载 | DIRECT | FIFO | LRU | CLOCK |
|---|---:|---:|---:|---:|
| hotspot | 1024 | 294 | 180 | 217 |
| sequential | 1024 | 1024 | 1024 | 1024 |
| random | 1024 | 781 | 772 | 773 |
| working-set | 1024 | 6 | 6 | 6 |

hotspot：80% 访问四个热页，其余访问冷页；LRU 最少读页，CLOCK 位于 LRU/FIFO 之间。sequential：循环 32 页超过容量，三种策略都无收益。random：均匀访问，差距较小。working-set：只访问六页，可全部驻留，三种策略都只发生六次冷 miss。不能把 CLOCK 宣称为普遍优于 LRU；其价值是用引用位和循环指针提供另一种成本与淘汰取舍。

## 索引点查与完整表扫描

同一持久化数据集：256 条记录，每条包含 id 和 180 字符 label；真实 SlottedPage 跨 14 个表页，唯一 INT 索引。两种方法均执行 64 个相同点查询 `(query*71)%256`，返回并逐次校验完整记录；索引法计入 RowId 后读取记录的开销。每种一轮预热、三轮有效样本。实例启动/恢复/索引加载在计时外，BufferPool 状态不是严格冷缓存。

| 方法 | 页文件读取中位数 | 耗时中位数 ms |
|---|---:|---:|
| 逐页扫描 | 896 | 202.291 |
| B+ 树点查并取记录 | 181 | 48.219 |

此数据集索引法少读 79.80% 的页文件，耗时中位数比为 4.20×。这是 OS API 层的实测索引用途；不代表 SQL 优化器一定选择该索引。

## 复现与证据

原始 JSON 位于 verification/highest-standard-2026-09-14/performance/。每轮计数、纳秒耗时、参数和版本均保留，无耗时通过阈值。所有计时包含实际断言、JVM 和操作系统文件缓存；页文件 read 不能等同硬件磁盘 read。

1. 从独立 detached checkout 构建基线 89ed0c4 的 storage-cli.jar，将 JAR 复制到独立路径；另构建当前 OS 的 JAR。不要切换 main 或用正在运行的 JAR 做 clean。
2. 使用 JDK 17+，在无其他实验 JVM 的情况下运行：

```powershell
./verification/verify-performance.ps1 -BaselineJar <baseline-storage-cli.jar> -ImprovedJar <target/storage-cli.jar> -EvidenceDirectory <new-directory> -ImprovedVersion <tested-commit>
```

脚本顺序执行四个实验组，编译同一实验源码并保存 JAR 摘要。输出目录必须不存在；仅把 results.json/manifest 作为证据归档，临时数据库无需提交。Mutation 在系统临时目录生成数据库，使用完可自行清理 chainpage-mutation-*。

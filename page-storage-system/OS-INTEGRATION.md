# OS 与真实数据库模块联调结果

## 当前 main 组合及复现

Ubuntu/Windows 远端 CI 均成功，运行 https://github.com/IBO-Mike/chainpage-database-system/actions/runs/34854368269 （e682bf8）。状态原文在 latest-main/ci-result.json。

最终本地根构建 BUILD SUCCESS：编译器 11、OS 53、引擎 175，共 239 项，0 失败/错误/跳过。数据库 JAR 11 请求、两个 JVM 按预期。最新证据在 verification/highest-standard-2026-09-14/latest-main/。首次 Windows 换行断言失败日志也保留；不把调整后的测试说成未改动的原始上层测试。

最终核对远端发现 main 已更新至 `ddb0dd7ca677da64f7b65f101a22402826fa81f7`（默认 HUMAN 输出）。本次额外验证这个版本，仍使用相同 OS core c2f7309；CLI 验收显式指定 `--json`，不修改上层生产代码。

ddb0dd7 的 DatabaseCliTest 有一个固定 LF 的 println 断言，在 Windows 因实际 CRLF 失败。独立联调目录仅将此断言的预期改为 `System.lineSeparator()`；内容仍精确比较，没有删除、跳过或放宽测试。修正由 verification/apply-main-windows-test-fix.ps1 可重复应用，差异保存在 verification/highest-standard-2026-09-14/upstream-test-newline.patch。main 分支没有被修改。

从 OS 检出目录执行，目录必须为新目录：

```powershell
git fetch origin main
git worktree add --detach ../chainpage-current-check ddb0dd7ca677da64f7b65f101a22402826fa81f7
git -C ../chainpage-current-check restore --source=os-storage-core --worktree -- page-storage-system
./page-storage-system/verification/apply-main-windows-test-fix.ps1 -IntegrationCheckout ../chainpage-current-check
```

进入 ../chainpage-current-check 运行 `mvn -B clean verify`；回到 OS 检出目录运行：

```powershell
./page-storage-system/verification/verify-sql-integration.ps1 -DatabaseJar ../chainpage-current-check/database-engine/target/chainpage-db.jar -EvidenceDirectory ../current-check-evidence -CliArguments @('--json')
```

CI 在 Ubuntu/Windows 使用相同组合与显式 JSON。其余旧版本数据作为历史对照保留，不以旧 main 替代当前组合验收。

## 最新最高档补强验证

OS 提交 `c2f7309dbf4ddeef1688476d4b232176bd74bf87`，上层仍固定 `0efbb25175ea94bcfd28c8fbaabe16712d43bbf6`。2026-09-14 最终根 `mvn -B clean verify` 为 BUILD SUCCESS：编译器 11、OS 53、引擎 162，共 226 项；失败/错误/跳过均为 0。RealModuleIntegrationTest 的 6 项保留通过。

最新数据库 JAR 再运行同一 SQL 脚本，11 条请求、两个真实 JVM 均符合预期。证据保存在 `verification/highest-standard-2026-09-14/`：maven-summary.json 包含每套测试的计数，manifest.json 包含所有被测 Java 文件及 JAR 的 SHA-256，sql/ 包含请求、实际响应与核验摘要。目录不提交临时数据库文件。

以下 212 项和旧 OS 版本描述作为前次联调历史记录保留。最新结果以本节为准；没有修改 main 或合并 PR。

## 结论与版本

2026 年 9 月 14 日，在独立 detached worktree 中验证通过。上层 SQL 编译器、数据库引擎、适配器和根 Maven 工程来自 main 提交 `0efbb25175ea94bcfd28c8fbaabe16712d43bbf6`；page-storage-system 替换为 os-storage-core 提交 `d0c4a4c3e5aec8a7271817db7a454ce30a012338` 的文件。

没有修改或提交 main，也没有合并 PR。OS 分支保持独立，没有把上层代码复制提交进 OS 分支。先前报告中的“尚无上层实现”只反映对 OS 分支的检查；检查最新 main 后，已发现实际实现并完成以下联调。

## 实际连接方式

ChainPageMain 启动 ChainPageDatabase，后者连接真实 CompilerModuleClient 与 PageStorageModuleClient。编译器生成计划，PlanDispatcher 执行 SeqScan、Filter、Project、Insert、Delete 等算子，StorageEngine 经 PageStorageModuleClient 调用 StorageCli.handle 和 StorageManager。

因此三模块之间使用生产适配器的进程内公开 JSON 操作，不是用 FakePageStorageClient 或模拟 SQL 结果替代 OS。SQL CLI 的两次启动是两个真实 JVM，共享一个隔离数据目录。

## Maven 全量结果

在组合后的根目录运行 `mvn -B clean verify`，三个模块与根聚合工程均为 SUCCESS。环境为 Windows 11、Temurin OpenJDK 17.0.20.1、Maven 3.9.16。

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| SQL 编译器 | 11 | 0 | 0 | 0 |
| OS 页式存储 | 39 | 0 | 0 | 0 |
| 数据库引擎 | 162 | 0 | 0 | 0 |
| 总计 | 212 | 0 | 0 | 0 |

数据库引擎中的 RealModuleIntegrationTest 为 6 项，全部通过。覆盖核心 SQL、多语句与错误、真实存储重开、扩展 SQL、100 条记录跨页持久化和真实页上的事务回滚/提交。其余引擎测试中包含 fake 客户端单元测试；212 项不等于全部都是端到端测试。

## 独立 JAR 与跨 JVM 验收

使用构建生成的 database-engine/target/chainpage-db.jar，运行 verification/verify-sql-integration.ps1。脚本使用全新目录，不访问仓库已有的 chainpage-data。检查 11 个输入对应的 11 个实际响应，其中缺表请求应失败，其余应成功。

| 次序 | 操作 | 实际结果 |
|---|---|---|
| 第一 JVM 1 | CREATE student(id INT,name VARCHAR,age INT) | CREATE 成功 |
| 第一 JVM 2 | INSERT Alice，id=1，age=20 | affectedRows=1 |
| 第一 JVM 3 | INSERT Bob，id=2，age=17 | affectedRows=1 |
| 第一 JVM 4 | SELECT id,name WHERE age>18 | [[1,"Alice"]] |
| 第一 JVM 5 | DELETE WHERE id=1 | affectedRows=1 |
| 第一 JVM 6 | SELECT * | [[2,"Bob",17]] |
| 第一 JVM 7 | compile 模式 SELECT id WHERE age>10 | 实际计划 Project → Filter → SeqScan |
| 第一 JVM 8 | SELECT missing_table | ok=false，SEMANTIC_TABLE_NOT_FOUND，stage=SEMANTIC |
| 第二 JVM 1 | 重启后 SELECT * | [[2,"Bob",17]]，表结构与删除结果保留 |
| 第二 JVM 2 | INSERT Carol，id=3，age=22 | affectedRows=1 |
| 第二 JVM 3 | SELECT id,name WHERE age>18 | [[3,"Carol"]] |

两个进程退出码均为 0。全量响应、输入、stderr 和 summary.json 保存在 verification/sql-integration-2026-09-14；summary 包含此次 JAR 的 SHA-256。摘要中的检查覆盖 envelope、行数据、逻辑计划与错误字段。

第一次运行验收脚本时误把计划字段写成 logicalPlan；检查实际 CompiledStatement 契约后修正为 plan，并断言 Project/Filter/SeqScan。最终使用新的隔离目录重跑通过，未改动任何生产适配器。

## 复现步骤

以下从 OS 仓库检出目录执行。确保新 worktree 名称和证据目录不存在；只在 detached worktree 中替换 OS 文件。

```text
git fetch origin main
git worktree add --detach ../chainpage-integration-check 0efbb25175ea94bcfd28c8fbaabe16712d43bbf6
git -C ../chainpage-integration-check restore --source=d0c4a4c3e5aec8a7271817db7a454ce30a012338 --worktree -- page-storage-system
```

进入 ../chainpage-integration-check，执行：

```text
mvn -B clean verify
```

回到 OS 检出目录，在 PowerShell 7 中执行：

```powershell
./page-storage-system/verification/verify-sql-integration.ps1 `
  -DatabaseJar ../chainpage-integration-check/database-engine/target/chainpage-db.jar `
  -EvidenceDirectory ../sql-integration-check-evidence
```

脚本遇到已有 EvidenceDirectory 会停止，以免覆盖数据。Windows 的 PowerShell 7、JDK 17 与 Maven 是本次复现环境；没有验证本脚本在其他 shell 下执行。

## 评分项对应与边界

“接口与集成 4 分”现有证据包括统一 OS 接口测试、真实数据库适配器、实际 SQL CLI 与跨进程重启，状态可改为“已验证”。本次没有替代教师评分，没有证明硬件断电保证、所有 SQL 方言或任意未来提交兼容性。个人对模块的熟悉程度、独立贡献、创新归属和现场协作表现仍需本人说明。

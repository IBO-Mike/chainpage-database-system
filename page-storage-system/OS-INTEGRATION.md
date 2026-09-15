# OS 与最新完整代码联调报告

## 被测代码

- 远端最新 main：`ddb0dd7ca677da64f7b65f101a22402826fa81f7`。
- main 与 OS 分支的共同基线：`c236cb3f6365800d37b9f1917669e4b1ca29fbef`。
- 完整合并提交：`65458581bfdbf87ad6f4a3ae54fcc10e4bc2d9eb`。
- 首次完整验证提交：`a01e1761a961932a4ab86dc15c3a9f484da7704f`。

Git 检查确认：从共同基线到 ddb0dd7，`page-storage-system` 没有源码变更；新上传内容主要是完整 SQL 编译器、数据库引擎、CLI 与项目文档。此前 OS 分支没有包含这些文件，只在临时目录覆盖联调。本次已经把 ddb0dd7 完整合入 `os-storage-core`，因此分支本身就是可构建的三模块工程，不再依赖覆盖旧目录。

main 引用没有被修改，也没有合并 PR。OS 分支相对 main 的上层生产源码无改动；唯一的上层差异是 `DatabaseCliTest` 将固定 `\n` 改为 `System.lineSeparator()`，使 `PrintStream.println` 的精确断言在 Windows 与 Linux 都成立。

## Maven 全量测试

在合并后的仓库根目录运行 `mvn -B clean verify`：

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| SQL 编译器 | 11 | 0 | 0 | 0 |
| OS 页式存储 | 53 | 0 | 0 | 0 |
| 数据库引擎 | 175 | 0 | 0 | 0 |
| 合计 | 239 | 0 | 0 | 0 |

其中数据库引擎的 `RealModuleIntegrationTest` 6 项使用真实编译器、真实 OS 存储和真实适配器；覆盖核心 SQL、扩展 SQL、100 行跨页、重启持久化和事务提交/回滚。其余 239 项包含单元、契约和回归测试，因此不把全部测试都称为端到端测试。

首次构建只在 Windows 的上层 CLI 换行断言失败：测试写死 LF，实际 `println` 返回 CRLF。修正预期为系统换行后全量通过；失败与修正后的日志都已保留，没有删除或跳过测试。

## 真实数据库 JAR

`verification/verify-sql-integration.ps1` 使用当前根构建生成的 `database-engine/target/chainpage-db.jar`，显式选择 `--json`，在新数据目录执行 11 个请求和两个独立 JVM：

1. 创建 student 表，插入 Alice 与 Bob；筛选、投影结果正确。
2. 删除 Alice 后只剩 Bob；compile 返回 `Project → Filter → SeqScan`。
3. 查询缺失表返回语义阶段的稳定错误代码。
4. 第二个 JVM 重启后仍能读取 Bob，再插入 Carol 并查询成功。

两个进程退出码均为 0，请求数与响应数一致。另以最新默认 HUMAN 模式运行 SQL 文件，实际输出 `score=96` 的表格，证明新 CLI 展示层、数据库引擎和 OS 数据页链路共同工作。

## 跨平台持续集成

`.github/workflows/storage-tests.yml` 直接检出并构建当前 `os-storage-core`，不再拼装固定旧 main。Ubuntu 与 Windows 都执行根 `clean verify`、真实 SQL 双 JVM 脚本，并上传测试及 SQL 证据。提交 a01e176 的两个矩阵任务均为 SUCCESS。

## 复现

```powershell
git switch os-storage-core
mvn -B clean verify
./page-storage-system/verification/verify-sql-integration.ps1 `
  -DatabaseJar ./database-engine/target/chainpage-db.jar `
  -EvidenceDirectory ../new-sql-evidence `
  -CliArguments @('--json')
```

证据目录必须不存在，脚本不会覆盖已有数据。当前证据位于 `verification/latest-code-2026-09-15/`，其中包括 Maven 摘要、成功与首次失败日志、JAR 摘要、JSONL 输入输出、HUMAN 输出和 CI 状态。

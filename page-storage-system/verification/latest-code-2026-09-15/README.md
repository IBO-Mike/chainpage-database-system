# 最新代码验证证据（2026-09-15）

本目录只对应最新 main `ddb0dd7ca677da64f7b65f101a22402826fa81f7` 完整合入 OS 分支后的验证，不使用旧目录覆盖方式。

| 文件 | 内容 |
|---|---|
| `manifest.json` | main/分支版本、OS Java 源码及两个可执行 JAR 的 SHA-256 |
| `maven-summary.json` | 239 项测试逐套计数 |
| `maven-final.log` | 根目录 `mvn -B clean verify` 成功原始日志 |
| `main-os-baseline-build.log` | 最新 main 自带 OS 的 32 项独立测试成功日志 |
| `maven-before-newline-fix.log` | 新上层测试固定 LF 在 Windows 失败的原始日志 |
| `ci-result.json` | e3bc1c4（当前 Actions 版本）的 Ubuntu/Windows 成功状态 |
| `sql-json/` | 11 条请求、两个 JVM 的输入、输出、stderr 和断言摘要 |
| `sql-human/` | 最新 HUMAN CLI 的 SQL 文件、表格输出和 stderr |
| `performance/` | 最新 main OS 与改进版的原始多轮 JSON 和 JAR 摘要 |

`maven-summary.json` 的总计为 tests=239、failures=0、errors=0、skipped=0。性能结果只收录通过完整结果检查的样本；说明和限制见 `../../OS-PERFORMANCE.md`。

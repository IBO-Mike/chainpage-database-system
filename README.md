# ChainPage DB（链页数据库系统）

这是一个使用 Java 编写的、可在本地命令行运行的小型数据库系统。它把 SQL 编译、页式存储和数据库执行引擎组合起来，数据默认写入磁盘，关闭后可以用同一个数据目录重新打开。

```text
SQL 文本
   ↓
SQL 编译器（词法、语法、语义、计划）
   ↓
数据库引擎（目录、计划分派、执行器）
   ↓
页式存储系统（页、缓存、WAL、索引）
   ↓
数据目录中的磁盘文件
```

## 1. 项目组成

| 子模块 | 主要职责 |
| --- | --- |
| `sql-compiler` | 把 SQL 文本转换为 Token、AST、语义标注和逻辑执行计划，并提供优化与 `EXPLAIN` 所需的信息。 |
| `page-storage-system` | 管理固定大小的数据页、缓冲池、页分配/释放、记录读写、索引、WAL 和崩溃恢复。 |
| `database-engine` | 维护系统目录，接收编译计划，调用执行器完成建表、增删改查、排序、分组、连接和 `EXPLAIN`，并提供命令行入口。 |

三个模块由根目录的 [module-interfaces.md](module-interfaces.md) 约定数据结构和错误格式；各模块的详细要求见：

- [SQL 编译器规约](sql-compiler/sql-compiler-spec.md)
- [页式存储系统规约](page-storage-system/paged-storage-spec.md)
- [OS 设计与关键流程](page-storage-system/OS-DESIGN.md)
- [OS 评分逐项完成报告](page-storage-system/OS-RUBRIC-REPORT.md)
- [OS 最新代码验证证据](page-storage-system/verification/latest-code-2026-09-15/README.md)
- [数据库引擎规约](database-engine/database-engine-spec.md)

## 2. 目录结构

```text
chainpage-database-system/
├── sql-compiler/         SQL 编译器
├── page-storage-system/  页式存储系统
├── database-engine/      数据库引擎和命令行入口
├── docs/                 项目指导书、整合记录和展示材料
├── demo.sql              可直接运行的示例 SQL 文件
├── run.sh                macOS/Linux 启动脚本
├── run.cmd               Windows CMD 启动脚本
├── run.ps1               Windows PowerShell 启动脚本
└── pom.xml               Maven 多模块构建入口
```

## 3. 环境要求

- JDK 17 或更高版本（源码和 Maven 配置使用 Java 17）。
- Maven 3.x，并确保 `mvn` 命令已加入 PATH。
- UTF-8 终端。Windows 可使用 PowerShell 或 Windows Terminal。
- 不需要安装 MySQL 等外部数据库。

## 4. 构建项目

在仓库根目录执行。第一次构建会下载 Maven 依赖，因此需要网络或已配置本地 Maven 仓库。

```bash
# 编译三个模块并运行全部测试
mvn -pl database-engine -am package

# 只构建可执行 JAR，适合准备启动时使用
mvn -DskipTests -pl database-engine -am package

# 运行整个项目的回归测试（依赖已缓存时可离线执行）
mvn -o -q test
```

成功后，可执行文件位于：

```text
database-engine/target/chainpage-db.jar
```

## 5. 启动方式

### 5.1 一键启动（推荐）

脚本会检查 JAR 是否存在或是否已被源码/POM 更新；需要时自动执行一次跳过测试的 Maven 构建。

```bash
# macOS / Linux
./run.sh

# Windows CMD
run.cmd

# Windows PowerShell
.\run.ps1
```

如果 macOS/Linux 提示没有执行权限，先执行：

```bash
chmod +x run.sh
```

### 5.2 直接运行 JAR

```bash
java -jar database-engine/target/chainpage-db.jar
```

默认数据目录是当前工作目录下的 `chainpage-data/`。通过 `--data` 指定目录后，建表和数据文件都会写入该目录；以后使用同一个路径启动即可恢复原数据。

启动成功后会显示欢迎信息，并出现：

```text
ChainPage 数据库系统已启动。输入 SQL 执行，输入 quit 或 exit 退出。
cpdbs>
```

交互模式支持左右方向键、历史记录和 `Ctrl+C` 取消当前输入；输入 `quit`、`exit` 或按 `Ctrl+D` 退出。

## 6. 命令行参数

| 参数 | 说明 |
| --- | --- |
| `--data <目录>` | 指定持久化数据目录，默认是 `./chainpage-data`。 |
| `--file <文件>` | 按 UTF-8 读取并依次执行 SQL 文件；支持多行 SQL、注释和字符串中的分号，不显示 `cpdbs>` 提示符。任一语句失败时进程返回非零状态。 |
| `--format human` | 人类可读的 MySQL 风格文本输出（默认）。 |
| `--format json` | 保留机器可读的 JSON 包络输出。 |
| `--json` | `--format json` 的简写。 |
| `--help` | 显示参数帮助。 |

常用例子：

```bash
# 交互式使用，并把数据放到独立目录
./run.sh --data ./demo-data

# 批量执行根目录的 demo.sql
./run.sh --data ./demo-data --file demo.sql

# 以 JSON 输出，便于脚本或其他程序读取
./run.sh --format json --file demo.sql
```

Windows CMD 的等价写法是：

```bat
run.cmd --data .\demo-data --file demo.sql
```

## 7. SQL 语法使用指南

每条 SQL 都必须以英文分号 `;` 结束。交互模式一次可以输入一条或同一行中的多条语句；`--file` 会按照分号顺序执行文件中的语句。

下面的例子使用表 `demo_students`。建议先执行建表语句，再执行其他语句。

### 7.1 创建表：`CREATE TABLE`

```sql
CREATE TABLE demo_students (
    id INT,
    name VARCHAR,
    age INT,
    score INT
);
```

当前数据库引擎实际执行时最稳定的列类型是 `INT` 和 `VARCHAR`。列名和表名不区分大小写，当前不提供主键、默认值、自增列或 `IF NOT EXISTS`。

### 7.2 插入：`INSERT INTO`

```sql
INSERT INTO demo_students(id, name, age, score)
VALUES (1, 'Alice', 20, 90);
```

当前集成引擎建议每条 `INSERT` 插入一行，并写出列清单。字符串使用单引号；字符串中的单引号写成两个单引号：

```sql
INSERT INTO demo_students(id, name, age, score)
VALUES (2, 'Bob''s record', 18, 80);
```

### 7.3 查询：`SELECT`

```sql
-- 查询全部列
SELECT * FROM demo_students;

-- 查询指定列、筛选并排序
SELECT name, score
FROM demo_students
WHERE age >= 18
ORDER BY score DESC, id ASC;
```

`WHERE` 支持比较运算符 `= != <> > >= < <=`，以及算术运算 `+ - * /`、逻辑运算 `AND`、`OR`、`NOT` 和括号。结果默认显示为 ASCII 表格，并在末尾显示行数。

### 7.4 修改：`UPDATE`

```sql
UPDATE demo_students
SET score = score + 5,
    age = 21
WHERE id = 1;
```

`SET` 可以包含多个赋值；不写 `WHERE` 时会尝试修改所有记录，实际使用时请确认条件是否正确。

### 7.5 删除：`DELETE`

```sql
DELETE FROM demo_students
WHERE id = 2;
```

不写 `WHERE` 表示删除表中的所有记录。

### 7.6 分组与聚合：`GROUP BY`、`HAVING`

```sql
SELECT score, COUNT(*) AS total
FROM demo_students
GROUP BY score
HAVING COUNT(*) >= 2
ORDER BY score;

SELECT SUM(score) AS total_score
FROM demo_students;
```

当前执行引擎支持 `COUNT(*)`、`COUNT(column)` 和 `SUM(column)`。`HAVING` 用于筛选分组后的结果。

### 7.7 内连接：`INNER JOIN` / `JOIN`

```sql
SELECT a.name, b.score
FROM demo_students AS a
INNER JOIN demo_students AS b ON a.id = b.id
ORDER BY a.id;
```

当前集成执行器主要支持等值内连接、表别名和自连接。`LEFT JOIN`、复杂非等值连接以及其他未列出的连接形式可能被编译器识别，但不保证能被数据库引擎执行。

### 7.8 查看计划：`EXPLAIN`

```sql
EXPLAIN SELECT name
FROM demo_students
WHERE age >= 18
ORDER BY name;

EXPLAIN UPDATE demo_students
SET score = 100
WHERE id = 1;
```

`EXPLAIN` 只编译、优化并以树形文本显示计划，不读取结果行，也不会真正执行更新、插入或删除。因此第二条语句不会改变 `score`。

### 7.9 注释

```sql
-- 这是单行注释
/* 这是可以跨行的块注释 */
SELECT * FROM demo_students;
```

## 8. JSON 请求和编译模式

默认的人类可读文本只用于命令行展示。需要和程序对接时，可以使用 `--format json`，或者通过标准输入发送一行一个 JSON 请求：

```json
{"sql":"SELECT * FROM demo_students;","mode":"execute"}
{"sql":"SELECT * FROM demo_students;","mode":"compile"}
```

- `mode: "execute"`：编译并执行 SQL，返回查询行或受影响行数。
- `mode: "compile"`：只返回 Token、AST、语义结果、原始计划和优化计划，不写入数据。

JSON 模式下标准输出每行都是一个 JSON 响应，不会混入启动信息或 `cpdbs>` 提示符，适合被脚本读取。

## 9. 批处理示例

根目录的 [demo.sql](demo.sql) 包含建表、插入、查询和 `EXPLAIN` 示例：

```bash
./run.sh --data ./demo-data --file demo.sql
```

执行文件时，SQL 可以跨多行；单引号字符串、`--` 单行注释和 `/* ... */` 块注释中的分号不会被错误地当作语句结束符。若某条 SQL 失败，程序会继续输出后续结果，但最终以非零状态退出，便于脚本检测失败。

## 10. 持久化、退出与测试

页文件、目录元数据、WAL 和索引文件都保存在 `--data` 指定的目录中。验证持久化的方法：

```bash
./run.sh --data ./demo-data
# 在 cpdbs> 中执行 CREATE/INSERT，然后输入 quit

./run.sh --data ./demo-data
# 再执行 SELECT，应该能看到上一次保存的数据
```

修改 Java 代码后，可重新运行：

```bash
mvn -o -q test
```

当前仓库的三模块回归测试已覆盖编译器、页式存储和数据库引擎；测试输出以 Maven 的 `target/surefire-reports` 为准。

## 11. 当前边界

- 编译器扩展文档还描述了 `BIGINT`、`DECIMAL`、`BOOL`、`DATE`、`NULL` 等语法；这些语法能否从 SQL 一直执行到存储层，取决于下游计划和执行器，不能仅凭编译成功判断数据库执行成功。
- 当前命令行重点覆盖 `CREATE TABLE`、单行 `INSERT`、`SELECT`、`UPDATE`、`DELETE`、排序、`GROUP BY/HAVING`、`COUNT/SUM`、等值 `INNER JOIN` 和 `EXPLAIN`。
- 暂无面向命令行的事务控制语句（如 `BEGIN`/`COMMIT`），也没有 SQL 层的 `CREATE INDEX` 命令；相关 Java 能力属于模块内部接口或存储层能力。
- 具体错误会标出阶段（如 `LEXER`、`PARSER`、`SEMANTIC`、`PLANNER`、`EXECUTOR`）和错误代码，便于定位 SQL 或执行问题。

更多展示 SQL、预期输出和错误示例见 [docs/mike/项目展示SQL用例.md](docs/mike/项目展示SQL用例.md)；三模块整合时的代码改动记录见 [docs/三模块整合步骤与代码修改记录.md](docs/三模块整合步骤与代码修改记录.md)。

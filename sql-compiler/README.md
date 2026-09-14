# Java 词法分析器

实现位于 `src/main/java/com/chainpage/sqlcompiler/lexer`，接口入口为：

```java
LexResponse response = new Lexer().lex(new LexRequest(sql));
```

`LexResponse.toMap()` 会生成规约要求的 JSON 兼容对象结构；项目未绑定具体 JSON 库，方便数据库系统集成时选用 Jackson、Gson 或其他序列化方案。

## LL(1) Parser 与 Lexer 对接

`Parser` 使用预测分析表和显式分析栈实现 LL(1) 分析，再将语法树转换为规约中的 AST。可直接传入 Token，也可使用组合入口：

```java
ParseResponse response = new CompilerFrontEnd().parseSql(sql);
```

组合入口会先调用 Lexer；词法错误保留 `stage: LEXER`，词法成功后再调用 Parser，语法错误返回 `stage: PARSER`。

## AST 接口

Parser 返回的每个 `statements` 元素都可以直接交给 AST 接口：

```java
Map<String, Object> statement = response.statements().get(0);
AstService ast = new AstService();

AstResponse made = ast.makeNode(new MakeNodeRequest(statement));
String json = made.json();

AstResponse restored = ast.parseNode(new ParseNodeRequest(json));
Map<String, Object> node = restored.node();
```

`makeNode` 校验节点并返回 `{node,json}`；`parseNode` 解析 JSON、重新校验节点并返回 `{node}`。两者失败时都返回 `stage: AST` 和 `code: AST_INVALID_NODE`。

不依赖第三方测试框架的验证方式：

```powershell
$sources = Get-ChildItem -Recurse -Filter *.java .\src | ForEach-Object FullName
javac --release 17 -encoding UTF-8 -d target/classes $sources
java -cp target/classes com.chainpage.sqlcompiler.lexer.LexerTest
```

## Catalog 接口

Catalog 使用 `InMemoryCatalog.execute(CatalogRequest)` 作为统一入口，支持
`create_table`、`find_table`、`find_column` 和 `snapshot`。名称按大小写不敏感比较，
表名和列名统一存为小写，数据类型统一存为大写。

```java
InMemoryCatalog catalog = new InMemoryCatalog();
catalog.execute(CatalogRequest.createTable("student", List.of(
        new ColumnSchema("id", "INT"),
        new ColumnSchema("name", "VARCHAR"))));

CatalogResponse snapshot = catalog.execute(CatalogRequest.snapshot());
```

## Semantic Analyzer 对接

`CompilerFrontEnd.analyzeSql` 串联 Lexer、LL(1) Parser、AST 校验、Catalog 快照和
Semantic Analyzer：

```java
AnalyzeResponse result = new CompilerFrontEnd().analyzeSql(sql, catalog);
```

成功时返回带类型标注的 AST。每个表达式会增加 `inferredType`，每个
`IdentifierExpr` 还会增加 `binding`。同一次分析中的 `CREATE TABLE` 只更新临时
Catalog，使后续语句可引用该表，不会修改传入的持久 Catalog。

## 手动输入 SQL

在 `sql-compiler` 目录编译后启动控制台：

```powershell
$sources = Get-ChildItem -Recurse -Filter *.java .\src\main\java | ForEach-Object FullName
javac -encoding UTF-8 -d target/classes $sources
java -cp target/classes com.chainpage.sqlcompiler.ConsoleApp
```

控制台命令为 `:help`、`:catalog` 和 `:quit`。每条 SQL 都必须以分号结束。

Windows CMD 或 VS Code 终端推荐直接运行一键脚本。脚本会自动切换代码页、编译并
以 UTF-8 启动程序：

```bat
run-console.cmd
```

## Plan Generator（第 6 部分）

实现位于 `src/main/java/com/chainpage/sqlcompiler/planner`，与 `parser`、`semantic` 并列。
`buildPlan` 对应规约的 `build_plan`，接收语义阶段已经通过检查的 AST：

```java
BuildPlanResponse result = new PlanGenerator().buildPlan(
        new BuildPlanRequest(analyzed.statements()));
// 或串联前面的所有阶段：
BuildPlanResponse result = new CompilerFrontEnd().buildPlanSql(sql, catalog);
```

成功时 `toMap()` 返回 `{ok:true,data:{plans:[...]}}`，计划与语句顺序一致。
SELECT 转换为 `Project → Filter（有 WHERE 时）→ SeqScan`；语义阶段的
`resolvedTable` 保存完整列顺序和类型，供扫描 schema 和 `SELECT *` 展开使用。
计划生成器不访问 Catalog；表达式保留类型、binding 和 loc，输入 AST 不被修改。
独立入口错误为 `PLANNER`，组合入口保留上游的错误阶段。

编译全部源码与测试后运行 `com.chainpage.sqlcompiler.planner.PlanGeneratorTest` 验证。


## 第 6～10 部分的统一编译入口

数据库系统应调用 `new CompilerFrontEnd().compile(request)`，request 为：

```java
Map.of("requestId", "req-1", "sql", sql,
       "catalogSnapshot", snapshot, "optimize", true)
```

响应按根目录 module-interfaces.md 返回 requestId 和逐语句的
statementIndex/tokens/ast/semantic/plan/optimizedPlan。optimize=false 时仍保留 optimizedPlan:null。
错误上下文字段完整，不适用字段为 null。编译仅修改临时 Catalog 副本。

第 6～10 部分仍分别放在 planner、optimizer、recovery、extension、explain 目录。
第 7 部分 Optimizer 同时处理基础和标准扩展计划；第 8 部分 StatementRecovery 支持扩展语法，
其独立恢复结果不能当成正式编译成功。EXPLAIN 复用统一流水线，控制台已接入这两个入口。
独立 buildPlanSql、analyzeSql 等保留用于基础模块教学/测试，不是跨模块 compile 接口。

详细扩展范围和不可表达的语法见 extension/README.md；跨模块类型及元数据见根接口补充。
运行全部 Java 17 契约与回归测试：

```sh
sh sql-compiler/test.sh
```

## 随机 SQL 测试（第 11 部分）

独立目录：`src/main/java/com/chainpage/sqlcompiler/randomtesting`。
`RandomSqlTester.run({seed,count,mode})` 按固定种子生成有确定预期的合法/非法 SQL，
调用统一 compile，返回接受、拒绝、崩溃、误判、位置错误的统计及逐例复现信息。
支持 valid、invalid、mixed，完整用法和统计定义见该目录 README.md。
测试类 `RandomSqlTesterTest` 已纳入 `sh sql-compiler/test.sh`。

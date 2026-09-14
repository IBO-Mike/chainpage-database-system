# 第 8 部分：更完整的错误恢复

本目录与 `parser`、`planner`、`optimizer` 并列，提供独立的 `RecoveringParser`。
它复用现有 LL(1) Parser 的文法、AST 构建和诊断，以分号或 EOF 为同步点继续解析。
不修改原 Parser 的遇错即返回行为，也不修改 Lexer 或后续语义分析、计划接口。

测试位于 `sql-compiler/src/test/java/com/chainpage/sqlcompiler/recovery/RecoveringParserTest.java`。

## 接口与调用

`RecoverParseRequest` 对应 JSON 请求 `{ "tokens": Token[] }`。
Token 来自 Lexer，保留原始行列，末尾必须有且只有一个 EOF。
与其他模块一致，由调用者负责 JSON 编解码，`toMap()` 返回 JSON 兼容对象。

```java
import com.chainpage.sqlcompiler.lexer.LexRequest;
import com.chainpage.sqlcompiler.lexer.Lexer;
import com.chainpage.sqlcompiler.recovery.RecoverParseRequest;
import com.chainpage.sqlcompiler.recovery.RecoveringParser;

String sql = "SELECT name student;\nSELECT * FROM student;";
var lexed = new Lexer().lex(new LexRequest(sql));
if (lexed.ok()) {
    var response = new RecoveringParser().parse(new RecoverParseRequest(lexed.tokens()));
    if (response.ok()) {
        var validStatements = response.statements(); // 只有第二条 SELECT 的完整 AST
        var syntaxErrors = response.errors();       // 第一条缺少 FROM 的错误
        boolean allValid = syntaxErrors.isEmpty();
    }
    var jsonObject = response.toMap();
}
```

恢复解析完成时，即使有 SQL 语法错误，仍按第 8 部分规约返回 `ok:true`：

```text
{
  "ok": true,
  "data": {
    "statements": [第二条 SELECT 的完整 AST],
    "errors": [{
      "stage": "PARSER",
      "code": "PARSER_UNEXPECTED_TOKEN",
      "message": "Token 不符合 LL(1) 文法：IDENTIFIER",
      "line": 1,
      "column": 13,
      "unexpected": "student",
      "expected": [",", "FROM"]
    }]
  }
}
```

`expected` 的具体候选项由原 LL(1) Parser 决定，顺序无需依赖。
`unexpected` 为意外 Token 的原始词素；文件末尾统一写作 `"EOF"`。
`statements` 和 `errors` 分别按源文本顺序排列。

空请求、缺少 tokens、Token 字段不完整、缺少/重复/提前 EOF，以及不符合 Token 格式的字符串字面量
属于输入契约错误，返回 `ok:false`，不返回部分 AST：

```json
{
  "ok": false,
  "error": {
    "stage": "PARSER",
    "code": "PARSER_INVALID_REQUEST",
    "message": "请求必须包含 tokens 数组",
    "line": null,
    "column": null,
    "unexpected": null,
    "expected": ["EOF"]
  }
}
```

## 恢复原理

1. 检查完整 Token 数组的结构。
2. 顺序寻找 `DELIMITER` 类型、词素为 `;` 的 Token 或最终 EOF，将该边界之前的 Token 作为一个语句片段。
3. 对以分号结束的片段附加内部 EOF，复用 `Parser.parse`。片段中的源 Token 不被改动。
4. 成功时收集完整 AST；失败时收集本片段的首个错误，丢弃整条错误语句的 AST。
5. 消费同步分号并从下一 Token 继续；到 EOF 停止。每次都前进，不对同一错误片段重试。

在当前所有语句都由分号结束的文法下，预先划分片段与报错后跳过到分号的同步策略一致。
独立解析片段可以复用原 Parser，不必复制预测分析表，也不会把错误语句构建到一半的 AST 暴露给调用者。
AST 构建时的 `PARSER_INT_OUT_OF_RANGE` 同样会被收集，随后继续下一条语句。

## 边界行为

- Lexer 已将字符串整体作为一个 `STRING_LITERAL` Token，并去除注释；其中的分号不会触发同步。
- 空文件、空白和注释只产生 EOF，返回空 statements 和 errors。
- 当前文法不接受空语句，因此单独的 `;` 会记录一个错误，然后继续。
- 最后一条语句缺少分号时，在原始 EOF 位置报错，不自动补全 AST。
- 两条语句之间缺少分号时，会跳过到下一个分号：例如 `SELECT * FROM t DELETE FROM t;`
  整段被丢弃，不会猜测第二个关键字是新语句起点。后续有明确边界的语句仍可继续解析。
- 括号未闭合也按分号同步，避免错误跨越到下一条已明确分隔的语句。
- 一条错误语句只记录其第一个错误，避免级联诊断；每条成功语句沿用原 Parser 的 AST 格式和源位置。
- 所有恢复状态均在单次调用内保存，不修改输入 Token 数组，不跨调用共享 AST。

本功能处理 Parser 阶段恢复。Lexer 若因非法字符或字符串未闭合而失败，调用者应先展示其词法错误；
这里不自行猜测缺失 Token 或新增 Lexer 的恢复行为。
成功 AST 仍需后续语义检查；例如错误的建表语句被丢弃后，后面的 SELECT 语法可能合法，但表可能不存在。

## 验证

在仓库根目录执行（macOS/Linux，Java 17+）：

```sh
build_dir=$(mktemp -d /tmp/chainpage-recovery-tests.XXXXXX)
rg --files sql-compiler/src -g '*.java' > "$build_dir/sources.txt"
javac --release 17 -encoding UTF-8 -d "$build_dir/classes" @"$build_dir/sources.txt"
java -cp "$build_dir/classes" com.chainpage.sqlcompiler.recovery.RecoveringParserTest
```

测试覆盖合法 AST 与原 Parser 完全一致、多条混合语句、诊断位置和候选项、EOF 恢复、连续错误、
字符串与注释边界、错误 AST 丢弃、请求错误、输入不变和重复调用隔离。
另外验证 1,000 条混合语句和包含 10,000 个分号的长字符串，确保扫描能够持续前进。


统一编译与扩展语法使用同目录的 StatementRecovery：parse({tokens}) 接受 JSON Token，
基础语法复用原 Parser，扩展语法复用 ExtensionParser；按分号/EOF 同步并保留源位置。
CompileService 使用相同 segments 边界保留 statementIndex，任一语法错误使正式 compile 失败。
原 RecoveringParser 的 Java Token 接口继续用于基础模块。

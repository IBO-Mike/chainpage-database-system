# 基本功能实现

## 统一数据约定

### 功能介绍

这一部分规定 SQL 编译器各子模块交接数据时使用的格式。它像快递单：只要寄件人和收件人都按同一张单子填写，两个模块即使由不同开发者完成，也能直接连接。

### 输入与输出格式

模块接口使用 UTF-8 编码的 JSON 对象；同一次调用只传递一个对象。所有成功响应必须是 `{ "ok": true, "data": ... }`，所有失败响应必须是 `{ "ok": false, "error": ... }`。

错误对象固定为：

```json
{
  "stage": "LEXER | PARSER | SEMANTIC | PLANNER | OPTIMIZER | CATALOG",
  "code": "稳定的机器可读错误码",
  "message": "给人的中文说明",
  "line": 1,
  "column": 1,
  "expected": []
}
```

`line` 和 `column` 均从 1 开始计数。无法对应源文本位置时，两项填 `null`。标识符只允许字母或下划线开头，后续可含字母、数字、下划线；关键字大小写不敏感，保存到 Token 中时统一为大写。基本类型只有 `INT` 与 `VARCHAR`。

### 实现说明

为每个模块定义一个纯函数或等价接口。不要用打印文本代替结构化返回值；命令行展示、日志、JSON 文件导出都应由接口返回的对象生成。

## 子模块清单与关系

本规约的子模块依次为：

1. Lexer
2. Parser
3. AST
4. Catalog
5. Semantic Analyzer
6. Plan Generator
7. 规则优化
8. 更完整的错误恢复
9. SQL 语言扩展
10. 查询说明与计划可视化
11. 随机 SQL 测试

以下 7 至 11 项拓展功能均为必做。基本数据关系如下：

```text
SQL 字符串
  ↓
1. Lexer ── Token[] ──→ 2. Parser ──→ 3. AST ──→ 5. Semantic Analyzer
                                                       ↑              │
                                          4. Catalog ──┘              │ Annotated AST
                                                                      ↓
                                                        6. Plan Generator
                                                                      │ Logical Plan
                                                                      ↓
                                                        7. 规则优化（Optimizer）
```

Lexer 只依赖统一 Token 格式；Parser 只依赖 Token 和 AST 节点格式；Semantic Analyzer 读取 Parser 生成的 AST，并通过 Catalog 查询表结构；Plan Generator 读取语义分析通过且已标注的 AST。Catalog 是语义分析使用的查询服务，不负责解析 SQL；AST 是共享的数据结构，不负责访问 Catalog。每个箭头表示“前者输出作为后者输入”，Catalog 到 Semantic Analyzer 的箭头表示查询关系。

## 1. Lexer

### 功能介绍

Lexer 是“分词员”。它把一长串 SQL 字符切成一个个有名字的小积木，例如把 `SELECT name` 切成 `SELECT` 和 `name`，并记住每块积木在原文第几行第几列。

### 输入与输出格式

输入：

```json
{ "sql": "SELECT name FROM student WHERE age >= 18;" }
```

输出的 `data` 为 Token 数组；Token 格式固定如下：

```json
{
  "type": "KEYWORD | IDENTIFIER | INT_LITERAL | STRING_LITERAL | OPERATOR | DELIMITER | EOF",
  "lexeme": "原始词素",
  "line": 1,
  "column": 1
}
```

必须识别关键字 `SELECT`、`FROM`、`WHERE`、`CREATE`、`TABLE`、`INSERT`、`INTO`、`VALUES`、`DELETE`、`INT`、`VARCHAR`、`AND`、`OR`、`NOT`；运算符 `=`、`!=`、`>`、`>=`、`<`、`<=`、`+`、`-`、`*`、`/`；分隔符 `(`、`)`、`,`、`;`。跳过空白、`--` 单行注释和 `/* ... */` 多行注释。字符串使用单引号，两个连续单引号表示字符串中的一个单引号，例如 `'Tom''s book'`。

Token 数组最后必须附加 `{ "type":"EOF", "lexeme":"", "line":最后行号, "column":最后列号 }`。非法字符、未闭合字符串或未闭合注释必须返回错误，且不得崩溃。

接口名为 `lex(request)`。请求必须是 `{ "sql": string }`，`sql` 可以为空但不得为 `null`。成功响应必须是 `{ "ok":true, "data":{ "tokens":Token[] } }`；失败响应必须是 `{ "ok":false, "error":Error }`。遇到错误时不得把错误位置之后的半成品 Token 当作成功结果。

### 实现说明

从左到右扫描字符，优先识别两个字符的运算符，再识别单字符运算符。维护当前位置的行号、列号；读取换行时行号加一、列号重置为一。用关键字表把普通标识符转换为关键字 Token。

## 2. Parser

### 功能介绍

Parser 是“句法老师”。它接收 Token，检查它们能否组成规定的 SQL 句子；正确时画出 AST，错误时指出在哪个 Token 处、原本可以写什么。

### 输入与输出格式

输入请求必须为 `{ "tokens":[Token] }`，其中 `Token` 在本接口内固定为 `{ "type":"KEYWORD | IDENTIFIER | INT_LITERAL | STRING_LITERAL | OPERATOR | DELIMITER | EOF","lexeme":字符串,"line":正整数,"column":正整数 }`。输出必须为 `{ "ok":true,"data":{ "statements":[Statement] } }`。`Statement` 只允许以下结构：

```text
CreateTableStmt = { kind:"CreateTableStmt", table:string, columns:[{name:string,dataType:"INT"|"VARCHAR",loc:Loc}], loc:Loc }
InsertStmt      = { kind:"InsertStmt", table:string, columns:[string], values:[Expr], loc:Loc }
SelectStmt      = { kind:"SelectStmt", columns:[string], table:string, where:Expr|null, loc:Loc }
DeleteStmt      = { kind:"DeleteStmt", table:string, where:Expr|null, loc:Loc }
Expr            = IdentifierExpr | LiteralExpr | BinaryExpr | UnaryExpr
IdentifierExpr  = { kind:"IdentifierExpr", name:string, loc:Loc }
LiteralExpr     = { kind:"LiteralExpr", literalType:"INT"|"VARCHAR"|"BOOL", value:int|string|bool, loc:Loc }
BinaryExpr      = { kind:"BinaryExpr", operator:string, left:Expr, right:Expr, loc:Loc }
UnaryExpr       = { kind:"UnaryExpr", operator:"NOT", operand:Expr, loc:Loc }
Loc             = { line:正整数, column:正整数 }
```

一段输入可有多条语句，且每条都必须以 `;` 结束。

必需接受的文法为：

```text
statement   := create_stmt | insert_stmt | select_stmt | delete_stmt
create_stmt := CREATE TABLE IDENTIFIER '(' column_def (',' column_def)* ')' ';'
column_def  := IDENTIFIER (INT | VARCHAR)
insert_stmt := INSERT INTO IDENTIFIER '(' id_list ')' VALUES '(' value_list ')' ';'
select_stmt := SELECT ('*' | id_list) FROM IDENTIFIER where_opt ';'
delete_stmt := DELETE FROM IDENTIFIER where_opt ';'
id_list     := IDENTIFIER (',' IDENTIFIER)*
value_list  := literal (',' literal)*
literal     := INT_LITERAL | STRING_LITERAL
where_opt   := WHERE expression | 空
expression  := or_expr
or_expr     := and_expr (OR and_expr)*
and_expr    := not_expr (AND not_expr)*
not_expr    := NOT not_expr | comparison
comparison  := primary (comp_op primary)?
primary     := IDENTIFIER | INT_LITERAL | STRING_LITERAL | '(' expression ')'
comp_op     := '=' | '!=' | '>' | '>=' | '<' | '<='
```

优先级从高到低是：括号、`NOT`、比较、`AND`、`OR`。失败时使用统一错误对象，`stage` 为 `PARSER`，`expected` 至少列出一个当前可接受的 Token 类型或词素。

接口 `parse(request)` 的请求必须为 `{ "tokens":Token[] }`，数组必须以且只能以一个 `EOF` 结束。成功输出为 `{ "ok":true, "data":{ "statements":Statement[] } }`；失败输出为统一错误对象且 `stage` 必须为 `PARSER`。Parser 只读 Token，不读取原始 SQL、不查询 Catalog、不执行数据操作。

### 实现说明

推荐递归下降：每个非终结符对应一个函数；`or_expr`、`and_expr` 用循环构造左结合的 `BinaryExpr`，自然保证优先级。也可实现 LL(1) 或 LR，但输出 AST、错误位置和错误格式必须完全相同。

## 3. AST

### 功能介绍

AST 是 SQL 句子的“结构图”，而不是把所有 Token 再堆一遍。例如查询语句会明确记录“查哪张表、筛选什么条件、返回哪些列”。Parser 写入这张图，语义分析器和计划生成器读取它。

### 输入与输出格式

本子模块输入为 AST 节点构造参数，输出为以下 JSON 节点。每个节点都必须含 `kind` 和 `loc`；`loc` 是 `{ "line": 正整数, "column": 正整数 }`。

```text
// 语句
{ "kind":"CreateTableStmt", "table":"student", "columns":[{"name":"id","dataType":"INT","loc":{} }], "loc":{} }
{ "kind":"InsertStmt", "table":"student", "columns":["id","name"], "values":[Expr], "loc":{} }
{ "kind":"SelectStmt", "columns":["id","name"], "table":"student", "where": Expr或null, "loc":{} }
{ "kind":"DeleteStmt", "table":"student", "where": Expr或null, "loc":{} }

// 表达式
{ "kind":"IdentifierExpr", "name":"age", "loc":{} }
{ "kind":"LiteralExpr", "literalType":"INT | VARCHAR | BOOL", "value":18, "loc":{} }
{ "kind":"BinaryExpr", "operator":"= | != | > | >= | < | <= | + | - | * | / | AND | OR", "left":Expr, "right":Expr, "loc":{} }
{ "kind":"UnaryExpr", "operator":"NOT", "operand":Expr, "loc":{} }
```

`SelectStmt.columns` 可为 `['*']`，除此以外列名不得为 `*`。`InsertStmt.columns` 与 `values` 的长度可暂不由 AST 校验，但必须完整保留给语义分析。

接口 `make_node(request)` 的请求为 `{ "node":节点 JSON }`，成功输出为 `{ "ok":true, "data":{ "node":节点, "json":string } }`。反序列化 `parse_node` 的请求为 `{ "json":string }`，成功输出为 `{ "ok":true, "data":{ "node":节点 } }`。失败输出为 `{ "ok":false, "error":{ "stage":"AST", "code":"AST_INVALID_NODE", "message":string, "line":整数或null, "column":整数或null } }`。

### 实现说明

只提供节点构造、字段校验和序列化/反序列化功能，不在此处做表是否存在、类型是否正确等业务判断。节点构造器发现字段缺失或类型不对时，返回 `AST_INVALID_NODE` 错误。

## 4. Catalog

### 功能介绍

Catalog 是数据库的“表格目录”。它记录每张表叫什么、里面有哪些列、每列是什么类型，让语义分析器不用猜测 `age` 是否真的存在。

### 输入与输出格式

Catalog 必须提供以下 JSON 操作。作为独立模块时，可先使用内存实现；数据库系统集成后，接口语义不变，数据由系统目录持久化保存。

```jsonl
{ "op":"create_table", "table":"student", "columns":[{"name":"id","dataType":"INT"}] }
{ "op":"find_table", "table":"student" }
{ "op":"find_column", "table":"student", "column":"id" }
{ "op":"snapshot" }
```

成功响应分别返回完整 `TableSchema`、查询结果、查询结果、`{ "tables": TableSchema[] }`。`TableSchema` 为 `{ "name":字符串, "columns":[ColumnSchema] }`；`ColumnSchema` 为 `{ "name":字符串, "dataType":"INT | VARCHAR" }`。查询不存在时返回 `ok:true` 和 `found:false`；重复建表返回 `CATALOG_TABLE_EXISTS`。

`create_table` 成功的 `data` 为 `{ "table":TableSchema }`；`find_table` 成功的 `data` 为 `{ "found":true, "table":TableSchema }`，查不到时为 `{ "found":false, "table":null }`；`find_column` 同理返回 `ColumnSchema` 或 `null`；`snapshot` 返回 `{ "tables":TableSchema[] }`。查询不存在不应抛异常；重复建表才返回失败。

### 实现说明

用以表名为键、以列名为键的数据结构保存模式。所有名称按大小写不敏感比较并统一保存为小写。`create_table` 必须拒绝空列集合、重复列名和未知类型。

## 5. Semantic Analyzer

### 功能介绍

语义分析器负责检查 SQL “看起来像一句话”之外，是否真的能做。例如表是否存在、列名有没有拼错、整数列能不能塞进字符串。它还会给 AST 中的表达式补上类型信息。

### 输入与输出格式

输入：

```text
{ "statements": [Statement], "catalogSnapshot": { "tables": [{ "name":"student", "columns":[{ "name":"id", "dataType":"INT" }] }] } }
```

`Statement` 必须是 `CreateTableStmt`、`InsertStmt`、`SelectStmt` 或 `DeleteStmt`，字段与 Parser 输出中列出的结构完全一致；`TableSchema` 必须是 `{ "name":字符串,"columns":[{ "name":字符串,"dataType":"INT | VARCHAR" }] }`。输出中的每个表达式在保留所有输入字段的基础上增加 `inferredType`；每个 `IdentifierExpr` 还增加 `{ "binding":{ "table":字符串,"column":字符串,"dataType":"INT | VARCHAR" } }`。

输出为经过标注的同一批 AST：每个表达式节点增加 `inferredType: "INT | VARCHAR | BOOL"`；每个 `IdentifierExpr` 增加 `binding: { "table":"student", "column":"age", "dataType":"INT" }`。成功响应还返回 `{ "accepted": true, "statements": [...] }`。

必须检查：建表时表和列名不能重复；`SELECT`、`INSERT`、`DELETE` 的表存在；查询和条件中的列存在；`INSERT` 的列数和值数一致、列不重复、每个值类型与目标列一致；比较只能比较两个同类型的 `INT` 或两个 `VARCHAR`；`AND`、`OR`、`NOT` 的操作数必须为 `BOOL`；`WHERE` 最终必须为 `BOOL`。语义错误的 `stage` 为 `SEMANTIC`。

接口 `analyze(request)` 请求固定为 `{ "statements":Statement[], "catalogSnapshot":{ "tables":TableSchema[] } }`。成功输出为 `{ "ok":true, "data":{ "statements":AnnotatedStatement[], "diagnostics":[] } }`；失败输出为 `{ "ok":false, "error":Error }`，其中 `Error.stage` 为 `SEMANTIC`。分析器不得要求调用方传入不可序列化的对象。

### 实现说明

递归遍历 AST。单独写一张“运算符、左类型、右类型、结果类型”的规则表，避免把类型判断分散到各处。分析多条语句时，`CreateTableStmt` 通过检查后只更新本次编译使用的临时 Catalog 快照，使后续语句能引用该表；不得直接修改数据库系统的持久化 Catalog。

## 6. Plan Generator

### 功能介绍

计划生成器把已经确认正确的 AST 改写成执行引擎的“任务清单树”。例如查询先读整张表，再过滤，再挑出需要的列。

### 输入与输出格式

输入请求为 `{ "statements":[AnnotatedStatement] }`。`AnnotatedStatement` 是 Parser 定义的四种 Statement，其中所有表达式都含 `inferredType`，所有 `IdentifierExpr` 都含 `{ "binding":{ "table":字符串,"column":字符串,"dataType":"INT | VARCHAR" } }`。输出为 `{ "ok":true,"data":{ "plans":[Plan] } }`。每个 Plan 都必须有 `kind`、`children` 和 `schema`；`schema` 是输出列数组，每列格式为 `{ "name":字符串,"dataType":"INT | VARCHAR" }`。

```text
{ "kind":"CreateTable", "table":"student", "columns":[ColumnSchema], "children":[], "schema":[] }
{ "kind":"Insert", "table":"student", "columns":["id"], "values":[Expr], "children":[], "schema":[] }
{ "kind":"SeqScan", "table":"student", "children":[], "schema":[ColumnSchema] }
{ "kind":"Filter", "predicate":Expr, "children":[Plan], "schema":[ColumnSchema] }
{ "kind":"Project", "columns":["name"], "children":[Plan], "schema":[ColumnSchema] }
{ "kind":"Delete", "table":"student", "predicate":Expr或null, "children":[], "schema":[] }
```

转换规则固定：`CREATE TABLE` 生成 `CreateTable`；`INSERT` 生成 `Insert`；`DELETE` 生成 `Delete`；`SELECT` 生成 `SeqScan`，有 `WHERE` 时在其上包一层 `Filter`，最后包一层 `Project`。`SELECT *` 的 `Project.columns` 为扫描表的全部列名。

接口 `build_plan(request)` 请求固定为 `{ "statements":AnnotatedStatement[] }`。成功输出为 `{ "ok":true, "data":{ "plans":Plan[] } }`，下标一一对应；失败输出为 `{ "ok":false, "error":Error }` 且 `Error.stage` 为 `PLANNER`。计划中的 `predicate` 和 `values` 必须是完整 Expr JSON，不得只传打印文本。

### 实现说明

只读取语义已验证的 AST 和其中的 `binding`、`inferredType`，不要重新做词法、语法或类型检查。使用递归或分派表按 `Statement.kind` 构造计划；每个计划节点只保存执行所需的信息。

# 拓展功能实现

## 7. 规则优化

### 功能介绍
Optimizer 在不改变查询答案的情况下删去多余步骤或提前计算固定表达式。

### 输入与输出格式
输入 `{ "plan":Plan }`；输出 `{ "ok":true,"data":{ "originalPlan":Plan,"optimizedPlan":Plan,"appliedRules":[字符串] } }`。错误输出的 `stage` 为 `OPTIMIZER`，且不得修改 `originalPlan`。

### 实现说明
递归优化子节点和表达式，逐条返回新节点及是否改变；至少实现常量折叠、布尔化简、恒真 Filter 删除。

## 8. 更完整的错误恢复

### 功能介绍
错误恢复让一条 SQL 出错后仍能检查后面的语句。

### 输入与输出格式
输入 `{ "tokens":Token[] }`；输出 `{ "ok":true,"data":{ "statements":Statement[],"errors":Error[] } }`。每个错误包含 `stage,line,column,unexpected,expected`。

### 实现说明
Parser 出错后跳过 Token 直到分号或 EOF，不为错误语句生成不完整 AST。

## 9. SQL 语言扩展

### 功能介绍
扩展支持修改记录、排序、分组、连接多表、NULL 和更多数据类型。

### 输入与输出格式
仍输入 Token[]，但必须新增 `UpdateStmt`、`OrderBy`、`GroupBy`、`Join`、`NullLiteralExpr` 等完整 JSON 节点；Parser、Semantic、Planner 的成功输出分别仍为 `Statement[]`、`AnnotatedStatement[]`、`Plan[]`。任何下游不支持都必须返回阶段错误。

### 实现说明
每项能力必须同时修改文法、AST、语义规则、计划节点和执行接口，不能只让 Parser 接受。

## 10. 查询说明与计划可视化

### 功能介绍
EXPLAIN 展示执行计划，但不读取或修改数据。

### 输入与输出格式
输入 `{ "sql":"EXPLAIN SELECT ...;" }`；输出 `{ "ok":true,"data":{ "tokens":Token[],"ast":Statement,"semantic":AnnotatedStatement,"plan":Plan,"optimizedPlan":Plan,"tree":字符串 } }`。不得产生数据行或写操作。

### 实现说明
复用编译和优化流水线，递归把计划格式化为缩进树。

## 11. 随机 SQL 测试

### 功能介绍
随机测试自动生成正常和故意写错的 SQL，检查编译器是否崩溃或误判。

### 输入与输出格式
输入 `{ "seed":整数,"count":正整数,"mode":"valid"或"invalid"或"mixed" }`；输出 `{ "ok":true,"data":{ "total":整数,"accepted":整数,"rejected":整数,"crashes":整数,"wrongAccept":整数,"wrongReject":整数,"locationErrors":整数,"cases":[对象] } }`。

### 实现说明
固定 seed 保证复现；逐条调用 Lexer 到 Plan，并记录预期与实际结果。

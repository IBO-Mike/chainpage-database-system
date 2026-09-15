# 第 9 部分：SQL 语言扩展

正式编译入口为 `CompilerFrontEnd.compile(Map)`，请求和响应严格采用根目录
`module-interfaces.md` 的 compile 契约。`SqlExtension.compileSql` 仅保留本地便捷调用，
其 plans 数组来自同一编译流水线。基础语法优先使用原 Parser/AST/Semantic/Planner；
扩展语法使用本目录的前端和标准计划生成器。

## 可执行范围

- UPDATE：多列赋值、条件更新；所有赋值读取原行。
- ORDER BY：列、可解析为列的选择项别名/序号；ASC/DESC。
- GROUP BY：列分组，COUNT(*)、COUNT(column)、SUM(column)，HAVING。
- JOIN：INNER 等值列连接、表别名、自连接、多段连接。
- NULL：NULL 字面量、IS NULL/IS NOT NULL、三值逻辑；列可声明 NOT NULL。
- 类型：INT、VARCHAR、BIGINT、DECIMAL、BOOL、DATE；具体边界见根接口文档的扩展补充。
- 算术表达式可用于 UPDATE、条件和单行 INSERT；Project 接口只接受列引用。

Parser 能构造更宽的 AST，但下游接口无法表达的 LEFT JOIN、任意表达式投影/分组/排序、
AVG/MIN/MAX、多行 VALUES、自定义非默认 NULLS 顺序，在 Planner 返回
`PLANNER_UNSUPPORTED_PLAN`，不生成部分计划。Sort 默认 NULL 在 ASC 最后、DESC 最前。
这些错误是当前执行契约的边界，不应通过改名字段让下游猜测其含义。

## 分阶段入口

各入口接受 JSON 兼容 Map，返回 ExtensionResponse：

| 入口 | 输入 | 成功 data |
| --- | --- | --- |
| ExtensionLexer.lex | {sql} | {tokens} |
| ExtensionParser.parse | {tokens} | {statements} |
| ExtensionAst.validate | {statements} | {statements} |
| ExtensionSemanticAnalyzer.analyze | {statements,catalogSnapshot} | {statements,diagnostics:[]} |
| ExtensionPlanner.buildPlan | {statements} | {plans} |

Token 保持 type/lexeme/line/column，增加 DECIMAL_LITERAL 和扩展关键字。
语句恢复入口 `StatementRecovery.parse({tokens})` 同时支持基础和扩展 Token，返回
{statements,errors}；统一 compile 利用相同同步边界，任何语法错误都会导致整次编译失败，
不会把恢复得到的部分 AST 当作可执行批次。

## 扩展文法

```text
statement := create | insert | select | update | delete
create    := CREATE TABLE id '(' column (',' column)* ')'
column    := id type [NOT NULL | NULL]
type      := INT | BIGINT | DECIMAL | VARCHAR | BOOL | BOOLEAN | DATE
insert    := INSERT INTO id '(' id (',' id)* ')' VALUES tuple (',' tuple)*
tuple     := '(' expr (',' expr)* ')'
update    := UPDATE id SET id '=' expr (',' id '=' expr)* [WHERE expr]
delete    := DELETE FROM id [WHERE expr]
select    := SELECT item (',' item)* FROM table_ref join*
             [WHERE expr] [GROUP BY expr (',' expr)*] [HAVING expr]
             [ORDER BY sort_item (',' sort_item)*]
item      := expr [AS id]
table_ref := id [[AS] id]
join      := [INNER | LEFT [OUTER]] JOIN table_ref ON expr
sort_item := expr [ASC | DESC] [NULLS FIRST | NULLS LAST]
```

每条语句必须以分号结束。表达式优先级由高到低为：括号/函数调用、一元正负号、乘除、加减、比较/IS NULL、NOT、AND、OR。
DATE 字面量写作 `DATE '2024-02-29'`，布尔字面量为 TRUE/FALSE；字符串使用双单引号转义。

## 完整 AST 节点

以下节点均带 `kind` 与 `loc:{line,column}`。明确可空的字段使用 JSON null，其余字段必须存在。
标识符由扩展 Parser 统一为小写。

```text
CreateTableStmt {table, columns:[ColumnDef]}
ColumnDef       {name, dataType, nullable:boolean}
InsertStmt      {table, columns:[string], rows:[[Expr]]}
UpdateStmt      {table, assignments:[Assignment], where:Expr|null}
Assignment      {column, value:Expr}
DeleteStmt      {table, where:Expr|null}
SelectStmt      {items:[SelectItem], from:TableRef, joins:[Join],
                 where:Expr|null, groupBy:GroupBy|null,
                 having:Expr|null, orderBy:OrderBy|null}
SelectItem      {expression:Expr, alias:string|null}
TableRef        {table, alias}
Join            {joinType:"INNER"|"LEFT", table:TableRef, on:Expr}
GroupBy         {expressions:[Expr]}
OrderBy         {items:[SortItem]}
SortItem        {expression:Expr, direction:"ASC"|"DESC", nulls:"FIRST"|"LAST"}

IdentifierExpr  {name, qualifier:string|null}
LiteralExpr     {literalType, value:number|string|boolean}
NullLiteralExpr {value:null}
StarExpr        {qualifier:string|null}
BinaryExpr      {operator, left:Expr, right:Expr}
UnaryExpr       {operator:"NOT"|"+"|"-", operand:Expr}
IsNullExpr      {operand:Expr, negated:boolean}
AggregateExpr   {function:"COUNT"|"SUM"|"AVG"|"MIN"|"MAX", argument:Expr}
```

Semantic 保留 AST 并为可求值表达式增加 `inferredType`、`nullable`，为列引用增加
`binding:{table:别名,column,dataType,nullable}`。COUNT(*) 的 StarExpr 是函数参数占位符，不单独求值。
还会展开选择项通配符，并补充写语句的 `resolvedTable`、TableRef 的 `resolvedSchema`、
选择项输出 `name`、SELECT 的 `aggregation:{groups,aggregates}|null`。

语义检查包括：表/列存在性、同名列歧义、重复表别名、赋值类型兼容、NOT NULL、
条件类型、聚合参数类型、禁止嵌套聚合、禁止 WHERE/ON/GROUP BY/SET 中使用聚合、
以及 SELECT/HAVING/ORDER BY 的非聚合列符合 GROUP BY。
ORDER BY 支持显式选择项别名和整数序号；GROUP BY/HAVING 使用源列或表达式。
同批次 CREATE 仅改变临时语义快照，不影响调用者的 Catalog。

## 标准计划与参考执行器

计划只有公共字段 kind/children/schema 和下列算子字段，无 version 字段：

```text
CreateTable {table,columns}
Insert      {table,columns,values}
Update      {table,assignments,predicate}
Delete      {table,predicate}
SeqScan     {table}
Filter      {predicate}
Project     {columns}
Sort        {keys:[{column,direction}]}
GroupBy     {keys:[string],aggregates:[{function,column,alias}]}
Join        {leftKey,rightKey}
```

children 数量、行结构与 database-engine-spec.md 一致。PlanContract 共享结构校验，
优化和参考执行均拒绝缺失字段。表达式中间表示只存在于 Planner 内部；公开计划不包含
Aggregate 节点、SlotRefExpr、Project.items 或 Insert.rows。
限定列、聚合列别名、类型及 resolvedTable 的跨模块约定见 module-interfaces.md 扩展补充。

InMemoryExtensionExecutor 是无页存储的测试参考实现，不替代数据库系统的持久化执行引擎。
`execute({plan})` 接受文档中的单计划：中间查询算子返回 {schema,rows:[{rowId:null,values}]}，
最终投影和写操作返回对应结果。测试数据库没有物理 RowId，因此使用 null。

便捷批处理 `execute({plans})` 返回 {results:[{statementIndex,kind,result}]}；每条语句成功后提交，
失败语句回滚自身副本，错误中的 statementIndex 和 results 保留失败位置及此前成功结果。
批处理不是跨模块 compile 响应，也不是项目的事务管理器。正式数据库 API 由数据库模块协调。

ExtensionExecutor 只声明支持的算子和类型，不要求私有版本号；执行前检查不支持的算子或类型，
返回 EXECUTOR_UNSUPPORTED_PLAN / EXECUTOR_UNSUPPORTED_TYPE。

## 验证

仓库根目录运行 `sh sql-compiler/test.sh`。契约测试包含：精确请求/响应和计划字段、
手写标准计划消费、内部行包络、基础 AST 兼容、优化接入、语句恢复、更新/连接/分组/NULL/类型、
不支持能力的阶段错误、编译快照隔离以及多语句执行失败后的结果保留。

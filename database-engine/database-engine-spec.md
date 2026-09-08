# 基本功能实现

## 统一数据约定

### 功能介绍

数据库系统是总调度员：它接收 SQL，交给编译器翻译，然后叫执行器、存储引擎和页式存储系统一起完成事情。统一格式保证这些角色能互相听懂。

### 输入与输出格式

所有公开接口使用 UTF-8 JSON。成功响应为 `{ "ok":true, "data":... }`，失败响应为 `{ "ok":false, "error":... }`。错误对象：

```json
{ "stage":"EXECUTOR", "code":"EXECUTOR_UNSUPPORTED_PLAN", "message":"不支持该计划节点", "line":null, "column":null }
```

本模块接收 SQL 编译器产生的逻辑计划，其节点格式必须与 `sql-compiler-spec.md` 的 `CreateTable`、`Insert`、`SeqScan`、`Filter`、`Project`、`Delete` 一致；访问页式存储系统时必须使用 `paged-storage-spec.md` 的统一存储访问接口。

### 实现说明

各子模块之间只能传递本规约定义的 JSON 数据。可在同一进程中以函数调用实现，也可通过 API 实现；两种实现的请求和响应语义必须相同。

## 子模块清单与关系

本规约的子模块依次为：

1. CLI 或数据库 API
2. System Catalog Manager
3. Storage Engine
4. Plan Dispatcher
5. CreateTable Executor
6. Insert Executor
7. SeqScan Executor
8. Filter Executor
9. Project Executor
10. Delete Executor
11. 优化后执行
12. UPDATE、排序、分组与连接
13. 索引扫描
14. EXPLAIN
15. 重启恢复
16. 事务、并发与访问控制

第 11 至 16 项拓展功能均为必做。

基本运行关系如下：

```text
用户 SQL
   ↓
1. CLI 或数据库 API
   ↓ 调用
SQL 编译器（Lexer → Parser/AST → Semantic → Plan）
   ↓ Logical Plan
4. Plan Dispatcher
   ├── 5. CreateTable Executor ──┬─→ 2. System Catalog Manager
   │                             └─→ 3. Storage Engine ──→ 页式存储系统
   ├── 6. Insert Executor ───────→ 3. Storage Engine ──→ 页式存储系统
   ├── 7. SeqScan Executor ──────→ 3. Storage Engine ──→ 页式存储系统
   ├── 8. Filter Executor（处理 7. SeqScan 的行）
   ├── 9. Project Executor（处理 8. Filter/7. SeqScan 的行）
   └── 10. Delete Executor ──────→ 3. Storage Engine ──→ 页式存储系统
```

CLI/API 是唯一接收用户 SQL 的入口；编译器输出计划，Plan Dispatcher 按 `kind` 选择执行器。`SeqScan` 产生行集，`Filter` 消费并筛选行，`Project` 消费并选择列，三者组成查询计划树。CreateTable、Insert、Delete 通过 Storage Engine 改变数据；Storage Engine 调用页式存储接口，不直接由执行器操作文件。System Catalog Manager 为编译器语义检查和执行器提供表结构，并通过 Storage Engine 持久化。执行器的关系既有计划树中的“上游输出给下游”，也有调用关系中的“上层主动请求下层服务”。

## 1. CLI 或数据库 API

### 功能介绍

这是用户和数据库说话的门口。用户提交一段 SQL，它负责调用编译器和执行器，并把查询结果或错误清楚地返回。

### 输入与输出格式

输入：`{ "sql":"一条或多条以分号结束的SQL","mode":"execute | compile" }`。`sql` 必须是字符串，`mode` 只能取两个给定值。

`mode:"compile"` 成功时返回 `{ "ok":true,"data":{ "results":[{ "statementIndex":非负整数,"tokens":[Token],"ast":Statement,"semantic":AnnotatedStatement,"plan":Plan,"optimizedPlan":Plan或null }] } }`；`mode:"execute"` 成功时返回 `{ "ok":true,"data":{ "results":[ExecutionResult] } }`。每条 `ExecutionResult` 格式为：

```json
{ "statementIndex":0, "kind":"SELECT", "result":{"columns":["id"],"rows":[[1]],"affectedRows":0,"message":"1 row selected"} }
```

执行失败时，保留失败语句之前已经成功完成的结果，并返回对应错误。基本版不要求跨多条语句的事务回滚。

### 实现说明

循环读取 CLI 的一行或 API 的 `sql` 字段。编译错误直接返回，不调用执行器；编译成功后，把每个逻辑计划交给 Plan Dispatcher。不得让 CLI 自己解析 SQL 或自己读写数据页。

## 2. System Catalog Manager

### 功能介绍

系统目录是数据库自己的“通讯录”。它记录表名、列名和列类型；它本身也要作为特殊表持久化，程序重启后仍能查到以前建过的表。表与数据页的映射只由页式存储系统的 Table Page Map 维护，避免两份页号列表不一致。

### 输入与输出格式

输入操作：

```jsonl
{ "op":"create_table", "table":"student", "columns":[{"name":"id","dataType":"INT"}] }
{ "op":"get_table", "table":"student" }
{ "op":"load" }
```

`ColumnSchema` 固定为 `{ "name":字符串,"dataType":"INT | VARCHAR" }`；`TableSchema` 固定为 `{ "name":字符串,"columns":[ColumnSchema] }`。`create_table` 返回 `{ "ok":true,"data":{ "table":TableSchema } }`；`get_table` 返回 `{ "ok":true,"data":{ "found":布尔值,"table":TableSchema或null } }`；`load` 返回 `{ "ok":true,"data":{ "tables":[TableSchema] } }`。重复创建返回 `SYSTEM_CATALOG_TABLE_EXISTS`。目录改变必须通过 Storage Engine 持久化。

### 实现说明

首次启动时创建保留名称的目录表或目录文件。它必须提供与 SQL 编译器 Catalog 等价的查表、查列、查类型能力，使语义分析和执行引擎使用同一份元数据。

## 3. Storage Engine

### 功能介绍

Storage Engine 负责把人眼里的“一行记录”变成磁盘页中的字节，也负责从页里把字节还原成记录。执行器只告诉它要读哪张表、写哪一行，不必知道页号和缓存细节。

### 输入与输出格式

`ColumnSchema` 为 `{ "name":字符串,"dataType":"INT | VARCHAR" }`；`TableSchema` 为 `{ "name":字符串,"columns":[ColumnSchema] }`；`Row` 是以列名为键、以整数或字符串为值的 JSON 对象；`RowId` 为 `{ "pageId":非负整数,"slotId":非负整数 }`。接口：

```jsonl
{ "op":"create_table_storage", "schema":{"name":"student","columns":[{"name":"id","dataType":"INT"}]} }
{ "op":"insert_row", "table":"student", "row":{"id":1} }
{ "op":"scan_rows", "table":"student" }
{ "op":"delete_rows", "table":"student", "rowIds":[{"pageId":12,"slotId":3}] }
```

`create_table_storage` 返回 `{ "ok":true,"data":{ "table":"student","pageIds":[] } }`；`insert_row` 返回 `{ "ok":true,"data":{ "rowId":{ "pageId":整数,"slotId":整数 } } }`；`scan_rows` 返回 `{ "ok":true,"data":{ "schema":[ColumnSchema],"rows":[{ "rowId":RowId,"values":Row }] } }`；`delete_rows` 返回 `{ "ok":true,"data":{ "deleted":非负整数 } }`。缺表、页 I/O 失败、记录过大分别返回清晰错误。

### 实现说明

基本版允许采用定长或带长度前缀的记录编码，但必须为同一表稳定地序列化和反序列化 `INT` 与 `VARCHAR`。`create_table_storage` 调用页式存储统一接口的 `create_table_pages`；插入时先寻找有空间的已有页，没有则调用 `allocate_page_for_table`；修改后调用 `write_page`。页号映射由页式存储系统维护，不写入 System Catalog。

## 4. Plan Dispatcher

### 功能介绍

Plan Dispatcher 是执行计划的“分拣员”。它查看计划节点的 `kind`，把任务交给正确的执行算子，并把下层算子的结果传给上层算子。

### 输入与输出格式

输入请求为 `{ "plan":Plan }`。`Plan` 只能是以下六种完整对象之一：`CreateTable{table,columns,children:[],schema:[]}`、`Insert{table,columns,values,children:[],schema:[]}`、`SeqScan{table,children:[],schema}`、`Filter{predicate,children:[Plan],schema}`、`Project{columns,children:[Plan],schema}`、`Delete{table,predicate,children:[],schema:[]}`。输出：

```json
{ "kind":"INSERT", "rows":[], "affectedRows":1, "message":"1 row inserted" }
```

`CreateTable` 调用 CreateTable Executor，`Insert` 调用 Insert Executor，`Project`、`Filter`、`SeqScan` 以树形递归执行，`Delete` 调用 Delete Executor。未知节点必须返回 `EXECUTOR_UNSUPPORTED_PLAN`。

### 实现说明

实现 `execute(plan)` 分派函数。对于有 `children` 的节点，先执行唯一子节点，再把该结果交给当前节点；这保证了 `Project(Filter(SeqScan))` 的数据流顺序正确。

## 5. CreateTable Executor

### 功能介绍

它执行建表计划：先准备这张表的存储位置，再把表结构登记到系统目录。之后其他模块才能知道这张表存在。

### 输入与输出格式

输入：`{ "kind":"CreateTable", "table":字符串, "columns":[ColumnSchema], "children":[], "schema":[] }`。成功输出：`{ "kind":"CREATE", "rows":[], "affectedRows":0, "message":"table created" }`。重复表名、存储初始化失败或目录持久化失败必须返回错误。

### 实现说明

调用 Storage Engine 的 `create_table_storage`，再调用 System Catalog Manager 的 `create_table`。若目录登记失败，应清理刚创建但尚未使用的存储映射，避免留下不可见表。

## 6. Insert Executor

### 功能介绍

它执行插入计划：把 SQL 中的一组值按列名拼成一行，然后交给 Storage Engine 写进数据页。

### 输入与输出格式

输入：`{ "kind":"Insert", "table":字符串, "columns":[字符串], "values":[LiteralExpr], "children":[], "schema":[] }`。成功输出：`{ "kind":"INSERT", "rows":[], "affectedRows":1, "message":"1 row inserted" }`。

### 实现说明

根据 System Catalog 中的完整列顺序创建 Row。基本 SQL 语义分析已经保证类型、列数和列名正确；执行器仍应拒绝缺少目录或无法编码的值。之后调用 Storage Engine 的 `insert_row`。

## 7. SeqScan Executor

### 功能介绍

SeqScan 是“逐页翻表的人”。它读取目标表的所有数据页，把每条还未删除的记录依次交给上层。

### 输入与输出格式

输入：`{ "kind":"SeqScan", "table":字符串, "children":[], "schema":[ColumnSchema] }`。成功输出内部行集：`{ "schema":[ColumnSchema], "rows":[{ "rowId":RowId, "values":Row }] }`。表不存在或读取页失败必须返回错误。

### 实现说明

直接调用 Storage Engine 的 `scan_rows`；不能在该算子中解释 WHERE 条件或挑选列。这样它能够独立被其他计划节点复用。

## 8. Filter Executor

### 功能介绍

Filter 像筛子：它拿到一批行，逐行判断 `WHERE` 条件，只留下条件为真的行。

### 输入与输出格式

输入：`{ "kind":"Filter","predicate":Expr,"children":[唯一Plan],"schema":[ColumnSchema] }`。`Expr` 只能是 `{kind:"IdentifierExpr",name}`、`{kind:"LiteralExpr",literalType,value}`、`{kind:"BinaryExpr",operator,left,right}`、`{kind:"UnaryExpr",operator:"NOT",operand}` 四种递归结构之一。输出保持与 SeqScan 一样的 `{ "schema":[ColumnSchema],"rows":[{ "rowId":RowId,"values":Row }] }`，但只包含谓词结果为 `true` 的行。谓词计算出非布尔值或引用不存在列时返回 `EXECUTOR_PREDICATE_ERROR`。

### 实现说明

先通过 Plan Dispatcher 执行唯一子节点，再递归求值 AST 表达式：`IdentifierExpr` 从当前 Row 取值，`LiteralExpr` 直接取值，`BinaryExpr` 和 `UnaryExpr` 按语义分析已经确认的类型规则计算。基本版把 SQL 三值逻辑留给 NULL 扩展处理。

## 9. Project Executor

### 功能介绍

Project 是“选列的人”。它拿到已经筛选过的行，只保留 SELECT 指定的列，然后形成最后展示给用户的查询结果。

### 输入与输出格式

输入：`{ "kind":"Project", "columns":["id","name"]或["*"], "children":[子计划], "schema":[ColumnSchema] }`。输出：

```json
{ "kind":"SELECT", "columns":["id","name"], "rows":[[1,"Alice"]], "affectedRows":0, "message":"1 row selected" }
```

`columns:['*']` 时按子计划 `schema` 的顺序返回所有列。输出行数组中的元素顺序必须与 `columns` 一一对应。

### 实现说明

先执行子计划，再按列名从每行 `values` 取值。列已经在语义阶段验证过，但 Project 仍须检查输入行是否真的含有所需字段，防止计划损坏时返回错误结果。

## 10. Delete Executor

### 功能介绍

它执行删除计划：找到满足条件的记录，并要求存储引擎把这些记录标记删除或物理删除。

### 输入与输出格式

输入：`{ "kind":"Delete", "table":字符串, "predicate":Expr或null, "children":[], "schema":[] }`。成功输出：`{ "kind":"DELETE", "rows":[], "affectedRows":非负整数, "message":"n rows deleted" }`。`predicate:null` 表示删除表中全部记录。

### 实现说明

复用 Filter Executor 的表达式求值器或相同的独立函数得到要删除的 `rowId` 集合，再调用 Storage Engine。逻辑删除时，`scan_rows` 必须跳过已删除记录；物理删除时必须更新页内记录结构。

# 拓展功能实现

## 11. 查询优化后的执行

### 功能介绍
执行编译器已经优化过的计划，并让调用者看到优化规则和最终结果。

### 输入与输出格式
输入 `{ "plan":Plan,"optimizedPlan":Plan }`；输出 `{ "result":Result,"plan":Plan,"appliedRules":[字符串] }`。缺少优化计划时必须失败，不得把原计划默默当作优化计划。

### 实现说明
只执行 `optimizedPlan`，执行器节点接口保持基本版不变。

## 12. UPDATE、排序、分组与连接

### 功能介绍
增加修改记录、排序结果、分组统计以及多表连接。

### 输入与输出格式
`Update` 输入 `{ "kind":"Update","table":字符串,"assignments":[{ "column":字符串,"value":Expr }],"predicate":Expr或null,"children":[],"schema":[] }`，输出 `{ "kind":"UPDATE","rows":[],"affectedRows":非负整数,"message":字符串 }`。`Sort` 输入 `{ "kind":"Sort","keys":[{ "column":字符串,"direction":"ASC | DESC" }],"children":[唯一Plan],"schema":[ColumnSchema] }`，输出 `{ "schema":[ColumnSchema],"rows":[InternalRow] }`。`GroupBy` 输入 `{ "kind":"GroupBy","keys":[字符串],"aggregates":[{ "function":"COUNT | SUM","column":字符串或"*","alias":字符串 }],"children":[唯一Plan],"schema":[ColumnSchema] }`，输出同一行集结构。`Join` 输入 `{ "kind":"Join","leftKey":字符串,"rightKey":字符串,"children":[左Plan,右Plan],"schema":[ColumnSchema] }`，输出合并后的行集。`InternalRow` 为 `{ "rowId":RowId或null,"values":Row }`。字段缺失或类型不匹配必须返回错误。

### 实现说明
先为每个节点定义独立执行器，再在 Dispatcher 注册；每个节点只能消费子计划规定的行集。

## 13. 索引扫描

### 功能介绍
使用索引快速定位记录，找不到可用索引时仍能退回顺序扫描。

### 输入与输出格式
输入 `{ "kind":"IndexScan","table":字符串,"index":字符串,"condition":Expr,"children":[],"schema":[ColumnSchema] }`；成功输出 `{ "ok":true,"data":{ "schema":[ColumnSchema],"rows":[{ "rowId":RowId,"values":Row }] } }`。索引不可用必须返回 `INDEX_UNAVAILABLE`，由优化器决定是否生成 SeqScan 回退计划。

### 实现说明
调用 Storage Engine 的索引查找接口得到 RowId，再读取对应记录；不要复制 B+ 树实现到执行器中。

## 14. EXPLAIN

### 功能介绍
只显示计划，不执行数据操作，方便检查优化器和执行器的连接是否正确。

### 输入与输出格式
输入 `{ "sql":"EXPLAIN ...;" }`；输出 `{ "tokens":Token[],"ast":Statement,"semantic":AnnotatedStatement,"plan":Plan,"optimizedPlan":Plan,"tree":字符串 }`。输出中不得有 `rows`，且不得调用写页接口。

### 实现说明
CLI 识别 EXPLAIN 后运行编译和优化流水线，调用计划格式化器后直接返回。

## 15. 数据持久化验收与重启恢复

### 功能介绍
保证退出再启动后，之前创建的表和插入的数据仍然存在。

### 输入与输出格式
`shutdown({})` 返回 `{ "flushedPages":[整数],"closed":true }`；`startup({})` 返回 `{ "tables":[TableSchema],"ready":true }`；重启后的查询响应必须与关闭前相同。目录损坏或页校验失败必须返回启动错误。

### 实现说明
关闭时调用统一存储接口 flush_all，启动时先加载目录再恢复页映射；只有 ready 为 true 才接受 SQL。

## 16. 事务、并发与访问控制

### 功能介绍
事务把多条操作当成一个整体，并防止同时操作时互相破坏；权限控制决定用户能否访问某张表。

### 输入与输出格式
`begin({ "session":字符串 })` 返回 `{ "txId":整数 }`；`execute({ "txId":整数,"plan":Plan })` 返回普通 Result；`commit({ "txId":整数 })` 返回 `{ "committed":true }`；`rollback` 返回 `{ "rolledBack":true }`；权限检查输入 `{ "user":字符串,"action":"SELECT"或"INSERT"或"DELETE"或"CREATE","table":字符串 }`，输出 `{ "allowed":true或false }`。非法 txId、越权访问必须失败。

### 实现说明
为每个事务保存修改页和目录变更，commit 时统一刷盘，rollback 时恢复；执行前先做权限检查，再获取页锁。

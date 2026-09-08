# 三部分跨模块接口规约

## 1. 文档范围

本文档只规定三个独立部分之间如何沟通：

1. SQL 编译器：把 SQL 文本转换为逻辑执行计划。
2. 页式存储系统：按页号读写持久化数据，并管理缓存。
3. 小型数据库系统：调用编译器得到计划，调用页式存储系统执行计划。

三个部分的内部实现分别遵循：

- [SQL 编译器规约](sql-compiler/sql-compiler-spec.md)
- [页式存储系统规约](page-storage-system/paged-storage-spec.md)
- [数据库系统规约](database-engine/database-engine-spec.md)

## 2. 调用关系

```text
用户
  │ SQL 文本
  ▼
数据库系统（对外入口）
  │ ① compile(sql)
  ▼
SQL 编译器
  │ ② Logical Plan
  ▼
数据库系统执行引擎
  │ ③ get_page / write_page / allocate_page_for_table
  ▼
页式存储系统
  │ ④ 页数据或存储错误
  └──────────────────────→ 执行引擎
```

数据库系统是三个部分的总协调者。用户不直接调用 SQL 编译器内部函数，也不直接操作页式存储系统。SQL 编译器不负责读写数据页；页式存储系统不理解 SQL、AST 或执行计划。

语义分析有一个反向的“查询依赖”：SQL 编译器需要 Catalog 快照来确认表和列，但 Catalog 的持久化由数据库系统通过页式存储系统负责。这个依赖不是把 SQL 编译器输出倒传给存储系统，而是数据库系统在编译前提供元数据快照。

## 3. 统一通信规则

### 3.1 编码与包络

所有跨模块请求和响应使用 UTF-8 JSON。成功响应固定为：

```json
{
  "ok": true,
  "data": {}
}
```

失败响应固定为：

```json
{
  "ok": false,
  "error": {
    "requestId": "req-0001",
    "statementIndex": 0,
    "stage": "模块阶段",
    "code": "稳定的机器可读错误码",
    "message": "给开发者或用户看的说明",
    "line": 1,
    "column": 1,
    "pageId": null
  }
}
```

不适用的字段必须填 `null`，不能省略。`line`、`column` 从 1 开始；存储错误使用 `pageId`，编译错误使用 `line` 和 `column`。

### 3.2 数据类型

三个模块跨边界只使用以下类型：

```text
INT      JSON 整数
VARCHAR  JSON 字符串
BOOL     JSON true/false
NULL     JSON null，仅在 NULL 扩展启用后使用
```

页内容统一使用 Base64 字符串表示，解码后必须正好为 4096 字节。表名和列名在接口比较时大小写不敏感，传输时使用小写；字符串常量的内容不得被改写。

## 4. 接口一：数据库系统调用 SQL 编译器

### 4.1 请求

数据库系统调用 `compile`：

```json
{
  "requestId": "req-0001",
  "sql": "SELECT name FROM student WHERE age > 18;",
  "catalogSnapshot": {
    "tables": [
      {
        "name": "student",
        "columns": [
          { "name": "id", "dataType": "INT" },
          { "name": "name", "dataType": "VARCHAR" },
          { "name": "age", "dataType": "INT" }
        ]
      }
    ]
  },
  "optimize": true
}
```

字段要求：

- `requestId` 是调用方生成的非空字符串，响应必须原样返回。
- `sql` 是非空或可报告空输入的字符串，可以包含多条以分号结束的语句。
- `catalogSnapshot` 必须包含 `tables` 数组；其结构与数据库系统的 System Catalog 一致。
- `optimize` 为布尔值；为 `true` 时返回优化计划，为 `false` 时只生成原始计划。

### 4.2 成功响应

成功响应必须严格符合以下类型，不得省略字段：

```text
CompileSuccess = {
  ok: true,
  data: {
    requestId: string,
    statements: [
      {
        statementIndex: 非负整数,
        tokens: Token[],
        ast: Statement,
        semantic: AnnotatedStatement,
        plan: Plan,
        optimizedPlan: Plan | null
      }
    ]
  }
}
```

其中 `Token`、`Statement`、`AnnotatedStatement` 和 `Plan` 必须使用 SQL 编译器规约定义的完整字段，不能用空对象、打印文本或省略字段代替。`statements[i]` 的 `statementIndex` 从 0 开始，并与输入 SQL 中的语句顺序一致。`optimizedPlan` 在 `optimize:false` 时为 `null`，在 `true` 时必须是完整 Plan。

### 4.3 失败响应

Lexer、Parser、Semantic、Planner 的错误直接放入统一 `error` 对象，数据库系统不得把它改写成普通存储错误。例如列不存在必须返回 `stage:"SEMANTIC"`；缺少分号必须返回 `stage:"PARSER"`。编译失败时不得调用执行引擎或页式存储系统。

## 5. 接口二：数据库系统调用页式存储系统

数据库系统的 Storage Engine 只能通过以下接口访问页式存储系统。`requestId` 便于把底层错误对应回一次 SQL 请求。

### 5.1 读取页

请求：

```json
{
  "requestId": "req-0001",
  "op": "get_page",
  "pageId": 12
}
```

成功响应：

```json
{
  "ok": true,
  "data": {
    "requestId": "req-0001",
    "pageId": 12,
    "data": "Base64(4096 bytes)",
    "hit": true,
    "dirty": false
  }
}
```

`pageId` 必须是非负整数；读取未分配页返回 `PAGE_NOT_ALLOCATED`。`hit` 表示是否从 Buffer Pool 命中，不能由上层猜测或固定写死。

### 5.2 写入页

请求：

```json
{
  "requestId": "req-0001",
  "op": "write_page",
  "pageId": 12,
  "data": "Base64(4096 bytes)"
}
```

成功响应：

```json
{
  "ok": true,
  "data": {
    "requestId": "req-0001",
    "pageId": 12,
    "dirty": true
  }
}
```

写入先更新缓存并标记脏页；真正落盘可在 `flush_all` 时完成。Base64 解码不是 4096 字节、页号未分配或写回失败都必须返回错误。

### 5.3 建立和删除表页映射

建表请求为 `{ "requestId":字符串,"op":"create_table_pages","table":字符串 }`，成功返回 `{ "ok":true,"data":{ "requestId":字符串,"table":字符串,"pageIds":[] } }`。删除映射请求为 `{ "requestId":字符串,"op":"drop_table_pages","table":字符串 }`，成功返回 `{ "ok":true,"data":{ "requestId":字符串,"table":字符串,"removed":true,"freedPageIds":[非负整数] } }`。删除操作必须释放映射中的所有数据页；不存在或部分释放失败时不得返回成功。

### 5.4 为表分配页

请求：

```json
{
  "requestId": "req-0001",
  "op": "allocate_page_for_table",
  "table": "student"
}
```

成功响应：

```json
{
  "ok": true,
  "data": {
    "requestId": "req-0001",
    "table": "student",
    "pageId": 13,
    "pageIds": [12, 13]
  }
}
```

该操作必须同时完成页分配和表页映射更新；只分配页但不更新映射不得返回成功。

### 5.5 查询表页和刷新

查询表页请求为 `{ "requestId":字符串,"op":"list_table_pages","table":字符串 }`，成功返回 `{ "ok":true,"data":{ "requestId":字符串,"table":字符串,"pageIds":[非负整数] } }`。刷新请求为 `{ "requestId":字符串,"op":"flush_all" }`，成功返回 `{ "ok":true,"data":{ "requestId":字符串,"flushedPageIds":[非负整数] } }`。`flush_all` 返回成功后，程序重启必须能读到已确认的数据。

## 6. 接口三：数据库系统内部的执行计划到存储操作

### 6.1 CreateTable

执行器接收编译器的 `CreateTable` 计划：

```json
{
  "kind": "CreateTable",
  "table": "student",
  "columns": [
    { "name": "id", "dataType": "INT" },
    { "name": "name", "dataType": "VARCHAR" }
  ],
  "children": [],
  "schema": []
}
```

执行器先调用 Storage Engine 的 `create_table_storage` 建立空的表页映射，再调用 System Catalog Manager 登记表结构。建表时不强制分配数据页；第一次插入且没有可用页时再调用 `allocate_page_for_table`。成功结果为 `{ "kind":"CREATE","rows":[],"affectedRows":0,"message":"table created" }`。

### 6.2 Insert

执行器接收 `Insert` 计划：

```json
{
  "kind": "Insert",
  "table": "student",
  "columns": ["id", "name"],
  "values": [
    { "kind": "LiteralExpr", "literalType": "INT", "value": 1 },
    { "kind": "LiteralExpr", "literalType": "VARCHAR", "value": "Alice" }
  ],
  "children": [],
  "schema": []
}
```

执行器根据 Catalog 的完整列顺序组成 Row，调用 Storage Engine 的 `insert_row`。成功结果为 `{ "kind":"INSERT","rows":[],"affectedRows":1,"message":"1 row inserted" }`。

### 6.3 Select

`SeqScan` 请求 Storage Engine 返回 `{ "schema":[ColumnSchema],"rows":[{ "rowId":RowId,"values":Row }] }`。`Filter` 在内存中消费该行集并返回相同结构的子集；`Project` 再返回 `{ "kind":"SELECT","columns":[字符串],"rows":[数组],"affectedRows":整数,"message":字符串 }`。因此 Filter 和 Project 不直接调用页式存储系统。

### 6.4 Delete

执行器先扫描目标表或使用索引得到待删 `RowId[]`，再调用：

```json
{
  "op": "delete_rows",
  "table": "student",
  "rowIds": [
    { "pageId": 12, "slotId": 3 }
  ]
}
```

成功响应为 `{ "ok":true,"data":{ "deleted":1 } }`，执行器再向用户返回 `{ "kind":"DELETE","rows":[],"affectedRows":1,"message":"1 row deleted" }`。存储引擎不得把 `rowIds` 之外的记录删除。

## 7. Catalog 快照接口

数据库系统在每次编译前向 SQL 编译器提供只读快照：

```json
{
  "tables": [
    {
      "name": "student",
      "columns": [
        { "name": "id", "dataType": "INT" },
        { "name": "name", "dataType": "VARCHAR" }
      ]
    }
  ]
}
```

编译器可以查询快照，但不能直接修改数据库目录。`CREATE TABLE` 计划执行成功后，数据库系统才调用 System Catalog Manager 写入新表；写入失败必须向用户报告，不能只返回编译成功。

## 8. 错误传递规则

1. SQL 编译错误：数据库系统原样返回 `LEXER`、`PARSER`、`SEMANTIC` 或 `PLANNER` 阶段和源代码位置，不调用页式存储系统。
2. 执行器错误：使用 `EXECUTOR` 阶段，保留导致错误的 `statementIndex`。
3. 存储错误：页式存储系统返回 `FILE_IO_ERROR`、`PAGE_NOT_ALLOCATED`、`BUFFER_*` 等稳定错误码，数据库系统只补充 `requestId` 和 `statementIndex`，不得伪装成 SQL 语法错误。
4. 任意模块都不得通过返回空数组、空字符串或 `null` 来表示失败；失败必须 `ok:false`。
5. 一条 SQL 失败时，不得把未完成的写页操作报告为成功；写操作的实际成功数量必须与 `affectedRows` 一致。

## 9. 一条 SQL 的完整示例

对 `INSERT INTO student(id,name) VALUES (1,'Alice');`：

1. 数据库系统向 SQL 编译器发送 `sql` 和 Catalog 快照。
2. 编译器返回 `Insert` 计划。
3. Plan Dispatcher 把计划交给 Insert Executor。
4. Insert Executor 向 Storage Engine 发送 Row。
5. Storage Engine 请求页式存储系统取得或分配目标页。
6. 页式存储系统返回页内容或错误；成功写入后返回 RowId。
7. 数据库系统向用户返回 `affectedRows:1`。

整个过程中，SQL 编译器只负责“理解并翻译”SQL，页式存储系统只负责“管理页”，数据库系统负责把两者连接起来并返回最终结果。

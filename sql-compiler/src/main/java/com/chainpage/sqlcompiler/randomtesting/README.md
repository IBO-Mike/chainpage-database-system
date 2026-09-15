# 第 11 部分：随机 SQL 测试

实现入口 `RandomSqlTester.run(Map<String,Object>)`，按 SQL 编译器规约第 11 节接受：

```json
{"seed":42,"count":600,"mode":"mixed"}
```

seed 为有符号 64 位整数，count 为正的 32 位整数；mode 为 valid、invalid 或 mixed。
缺失字段、非整数、越界数值和非法模式返回 `{ok:false,error}`，不调用编译器。
成功完成测试运行时返回以下固定字段；即使发现编译器问题，运行报告仍为 ok:true：

```text
{ok:true,data:{total,accepted,rejected,crashes,wrongAccept,wrongReject,locationErrors,cases}}
```

## 调用

```java
var tester = new com.chainpage.sqlcompiler.randomtesting.RandomSqlTester();
var report = tester.run(java.util.Map.of("seed", 42L, "count", 600, "mode", "mixed"));
System.out.println(com.chainpage.sqlcompiler.ast.JsonCodec.stringify(report.toMap()));
```

每例调用 `CompilerFrontEnd.compile`，执行 Lexer 到 Plan 和优化阶段。仅使用临时 Catalog
快照，不执行 SQL，不连接存储。每例初始表为 t(id INT,v INT,name VARCHAR)；多语句测试中
CREATE 只影响该例内部的临时快照。其他用例不会依赖上一例的建表结果。

## 生成策略与复现

固定 java.util.Random(seed)，随机选择已知文法模板、数值、列、比较运算符、排序方向和
前置注释/换行。相同输入在同一实现版本产生相同 SQL、顺序和结果；报告不含时间戳。
mixed 在随机选择第一例类型后交替产生合法/非法用例，count>=2 时保证两类都有覆盖。

合法模板覆盖基础 SELECT/INSERT/DELETE、UPDATE、排序、COUNT/SUM 分组和 HAVING、
INNER JOIN、自连接、NULL、扩展类型及多语句 CREATE。只生成当前计划接口可以表达的能力。
非法模板独立定义预期，包含非法字符、未闭合字符串、缺失投影/表达式/分号、未知表/列、
赋值类型错误。不会以编译器的返回值反过来决定用例是否应当合法。

每个 cases 元素记录 index、sql、catalogSnapshot、expected、category、expectedStage、
actual、error、exception、locationError。actual 为 accepted/rejected/crashed；
error 保存实际编译错误，exception 保存异常类型及消息，未发生时为 null。
将该例 sql 和 catalogSnapshot 重新传给 compile 即可单独复现，optimize 使用 true。

## 统计定义

- total = accepted + rejected + crashes。
- wrongAccept：预期非法，实际成功编译。
- wrongReject：预期合法，实际返回编译错误。
- crashes：编译调用抛出 RuntimeException、AssertionError、StackOverflowError，或返回缺少
  必要计划/错误数据的响应；记录后继续下一例。崩溃不重复计入 rejected/wrongReject。
- locationErrors：拒绝响应的位置缺失、非正整数或超出 SQL 范围；非法字符、未闭合字符串和
  缺失分号还验证已知字符位置/EOF 的精确偏移。其余语义错误只检查位置范围，不宣称校验了
  每一种语义诊断的精确光标位置。expectedStage 和 error.stage 可用于进一步诊断阶段差异。

这是有明确预期的随机模板测试，不是穷举 SQL 文法，也不验证真实存储执行结果。

## 验证与命令行

仓库根目录执行 `sh sql-compiler/test.sh`，包含独立测试类
`com.chainpage.sqlcompiler.randomtesting.RandomSqlTesterTest`。
它验证多种 seed 和全部模式、完整输出、复现性、非法请求、报告隔离，并注入误接受、误拒绝、
错误位置和三种异常，检查统计器确实能够发现问题且继续运行。

也可在编译后的 classpath 上运行：

```sh
java -cp /path/to/classes com.chainpage.sqlcompiler.randomtesting.RandomSqlTester 42 600 mixed
```

输出为一份完整 JSON 报告；命令行不自动保存文件。

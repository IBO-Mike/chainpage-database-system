# 第 7 部分：规则优化器

本目录与 `planner` 并列，独立实现规约中的 Optimizer。生产代码只依赖 Java 17 标准库，
输入为第 6 部分的单棵 Plan，不读取 Catalog、不访问存储、不执行 SQL。
测试位于 `sql-compiler/src/test/java/com/chainpage/sqlcompiler/optimizer/OptimizerTest.java`。

## 接口

Java 请求对象 `OptimizeRequest` 对应 JSON `{ "plan": Plan }`。
与前面模块一样，调用者负责 JSON 序列化/反序列化；`toMap()` 返回 JSON 兼容对象。

```java
import com.chainpage.sqlcompiler.CompilerFrontEnd;
import com.chainpage.sqlcompiler.catalog.CatalogRequest;
import com.chainpage.sqlcompiler.catalog.ColumnSchema;
import com.chainpage.sqlcompiler.catalog.InMemoryCatalog;
import com.chainpage.sqlcompiler.optimizer.OptimizeRequest;
import com.chainpage.sqlcompiler.optimizer.OptimizeResponse;
import com.chainpage.sqlcompiler.optimizer.Optimizer;
import com.chainpage.sqlcompiler.planner.BuildPlanResponse;
import java.util.List;

InMemoryCatalog catalog = new InMemoryCatalog();
catalog.execute(CatalogRequest.createTable("student", List.of(
        new ColumnSchema("name", "VARCHAR"), new ColumnSchema("age", "INT"))));
BuildPlanResponse planned = new CompilerFrontEnd().buildPlanSql(
        "SELECT name FROM student WHERE 1=1 AND age>18;", catalog);
if (planned.ok()) {
    // 多条 SQL 的 plans 按原顺序逐棵调用，optimize 本身只接收一棵计划。
    OptimizeResponse result = new Optimizer().optimize(new OptimizeRequest(planned.plans().get(0)));
    if (result.ok()) {
        var original = result.originalPlan();
        var optimized = result.optimizedPlan();
        var rules = result.appliedRules();
    }
    var jsonObject = result.toMap();
}
```

成功响应：

```text
{
  "ok": true,
  "data": {
    "originalPlan": Plan,
    "optimizedPlan": Plan,
    "appliedRules": ["CONSTANT_FOLDING", "BOOLEAN_SIMPLIFICATION"]
  }
}
```

失败响应：

```json
{
  "ok": false,
  "error": {
    "stage": "OPTIMIZER",
    "code": "OPTIMIZER_INVALID_PLAN",
    "message": "缺少 predicate 字段",
    "line": null,
    "column": null,
    "expected": []
  }
}
```

错误码：`OPTIMIZER_INVALID_REQUEST`（空请求/空 plan）、`OPTIMIZER_INVALID_PLAN`（依赖的结构或标注无效）、
`OPTIMIZER_UNSUPPORTED_PLAN`（不支持的计划节点）、`OPTIMIZER_UNSUPPORTED_EXPRESSION`（不支持的表达式或运算符）。
能够定位到表达式时使用其 `loc`，否则位置为 `null`。

## 规则与执行顺序

采用后序遍历：先递归优化子计划、子表达式，再重写当前节点。
每次重写返回节点和 `changed` 标志；规则记录按实际首次应用顺序去重。
每次调用拥有独立记录，没有修改时 `appliedRules` 为空。

| 规则标识 | 转换 |
| --- | --- |
| `CONSTANT_FOLDING` | 相同类型 INT/VARCHAR 的常量比较；常量 `AND`、`OR`、`NOT` |
| `BOOLEAN_SIMPLIFICATION` | `TRUE AND p → p`、`FALSE AND p → FALSE`、`TRUE OR p → TRUE`、`FALSE OR p → p`，含左右对称情况；`NOT NOT p → p` |
| `REMOVE_TRUE_FILTER` | `Filter(TRUE, child) → child` |

例如：

```text
SELECT name FROM student WHERE 1=1 AND age>18;

优化前：Project(name) → Filter((1=1) AND (age>18)) → SeqScan(student)
优化后：Project(name) → Filter(age>18) → SeqScan(student)

SELECT name FROM student WHERE 1=1 OR age>18;

优化前：Project(name) → Filter((1=1) OR (age>18)) → SeqScan(student)
优化后：Project(name) → SeqScan(student)
```

`Filter(FALSE)` 会保留，避免把“没有结果”错误地改成扫描全部行。
`Delete` 只优化 predicate，节点始终保留；`Insert` 递归处理 values；
`CreateTable`、`SeqScan`、`Project` 的业务字段保持原样。
删除 Filter 前要求其 schema 与子节点一致。

## 数据与语义约定

- 输入必须是规约中的 JSON 兼容树，包含 Plan 的 `kind/children/schema` 和语义阶段的表达式标注。
  `children` 中的计划、`predicate`、`values` 都会递归处理。非法分支不会因为另一侧恒真/恒假而被静默忽略。
- 调用期间不修改输入；返回的 `originalPlan` 是深拷贝快照，`optimizedPlan` 与它及输入均不共享可变 Map/List。
  调用者可以分别修改返回对象；不会连带修改其他两棵树。
- 保留表达式的 `inferredType`、`binding` 和 `loc`。新折叠的 BOOL 字面量沿用被折叠表达式的 `loc`，
  删除不再适用的 operator/left/right/operand 字段；保留下来的子表达式保持自身位置。
- INT 常量按精确整数比较，不经 double 转换或相减比较，避免大整数精度损失和减法溢出。
  非整数、NaN、Infinity 返回阶段错误。
- VARCHAR 采用 Java `String.compareTo` 的区分大小写 UTF-16 字典序，不使用语言环境排序。
  后续执行引擎需要采用相同的比较语义；若引入 SQL collation，必须同步调整折叠规则。
- 公共入口同时遍历标准 Update/Sort/GroupBy/Join。NULL 和扩展类型保守优化，
  仅对兼容基础规则的子表达式应用折叠；不新增算术折叠、索引选择或成本优化。
  编译与 EXPLAIN 使用同一个 Optimizer，扩展计划先经过共享 PlanContract 校验。
- 一次自底向上的遍历完成当前规则组合，再次优化同一结果不会继续改变计划或记录新规则。

## 验证

在仓库根目录执行（macOS/Linux，Java 17+）：

```sh
build_dir=$(mktemp -d /tmp/chainpage-optimizer-tests.XXXXXX)
rg --files sql-compiler/src -g '*.java' > "$build_dir/sources.txt"
javac --release 17 -encoding UTF-8 -d "$build_dir/classes" @"$build_dir/sources.txt"
java -cp "$build_dir/classes" com.chainpage.sqlcompiler.optimizer.OptimizerTest
```

测试包括六种整数/字符串比较、边界整数、布尔真值表、嵌套表达式与计划、SQL 到 Planner 到 Optimizer 的对接、
增删语句保留、错误对象、源位置、深拷贝隔离，以及 250 棵固定种子的表达式计划。
测试解释器分别执行优化前后计划，比较结果行、顺序、重复行，并验证重复优化的幂等性。

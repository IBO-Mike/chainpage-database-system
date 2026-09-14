# 第 10 部分：EXPLAIN

`new ExplainService(catalogSnapshot).explain(new ExplainRequest("EXPLAIN SELECT id FROM t;"))`
返回 `{ok:true,data:{tokens,ast,semantic,plan,optimizedPlan,tree}}`。服务只持有表结构快照副本，
不接收执行器、数据行或存储连接；CREATE/INSERT/UPDATE/DELETE 也只生成计划。

EXPLAIN 控制 Token 被移除后，调用普通编译共用的 CompileService.compileTokens 和 Optimizer。
Token 与 loc 保留原 SQL 的行列。基础 SQL 使用基础 AST，扩展 SQL 使用扩展 AST；
两者都输出根接口/数据库引擎规约定义的计划，无私有版本号。
ExplainOptimizer 是 Optimizer 的兼容便捷入口，不再单独选择另一种计划协议。

PlanTreeFormatter 支持 CreateTable、Insert、Update、Delete、SeqScan、Filter、Project、
Sort、GroupBy、Join。Sort 读取 column/direction，Join 读取 leftKey/rightKey，
GroupBy 显示 keys 和 function/column/alias。未知节点明确报错。

服务恰好解释一条语句，允许 EXPLAIN 前的空白、注释和混合大小写；
拒绝空 EXPLAIN、多语句及 EXPLAIN ANALYZE。不支持的 SQL 能力与正常编译返回相同阶段错误。
输出不包含执行结果 rows，INSERT 计划使用 values 表达式。

原计划与优化计划深拷贝隔离。优化保持 NULL 三值语义，保留 FALSE/NULL Filter；
仅对已有规则支持的表达式执行折叠，不调用执行器计算常量。

控制台已接入编译和 EXPLAIN；它只演示编译，不持久化 CREATE。
运行 `sh sql-compiler/test.sh` 验证六字段契约、源码位置、统一流水线一致性、手写标准计划、
原始/优化计划结果等价、输入隔离、阶段错误，以及解释写语句不改变数据或 Catalog。

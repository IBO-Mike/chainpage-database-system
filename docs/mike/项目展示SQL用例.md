# ChainPage 数据库系统展示 SQL 用例

使用全新的数据目录启动项目，按下表顺序在 `cpdbs>` 后输入每格中的整行内容。预期输出按默认文本模式描述；查询结果会显示为表格。第 21 项在一行中提交两条 SQL。完成第 24 项后退出程序，使用同一数据目录重新启动，再执行第 25 项。

| 序号 | 展示 SQL | 作用 | 期待输出 |
| --- | --- | --- | --- |
| 1 | `CREATE TABLE demo_students(id INT, name VARCHAR, age INT, score INT);` | 创建含整数列和字符串列的学生表。 | `Query OK, 0 rows affected`。 |
| 2 | `INSERT INTO demo_students(id,name,age,score) VALUES (1,'Alice',20,90);` | 插入第一条记录。 | `Query OK, 1 row affected`。 |
| 3 | `INSERT INTO demo_students(id,name,age,score) VALUES (2,'Bob',17,80);` | 插入第二条记录。 | `Query OK, 1 row affected`。 |
| 4 | `INSERT INTO demo_students(id,name,age,score) VALUES (3,'Cindy',21,90);` | 插入第三条记录。 | `Query OK, 1 row affected`。 |
| 5 | `SELECT * FROM demo_students;` | 查询全部列和全部记录。 | 表格包含 `(1,Alice,20,90)`、`(2,Bob,17,80)`、`(3,Cindy,21,90)`，末行显示 `3 rows in set`。 |
| 6 | `SELECT name,score FROM demo_students WHERE age >= 18 ORDER BY id;` | 展示指定列、条件筛选和升序排列。 | `name,score` 两列依次为 `(Alice,90)`、`(Cindy,90)`；`2 rows in set`。 |
| 7 | `SELECT id,name FROM demo_students WHERE NOT (age < 18 OR score < 90) ORDER BY id;` | 展示括号、`NOT` 和 `OR` 组成的复合条件。 | `id,name` 两列依次为 `(1,Alice)`、`(3,Cindy)`；`2 rows in set`。 |
| 8 | `UPDATE demo_students SET score=95 WHERE id=2;` | 按条件修改 Bob 的分数。 | `Query OK, 1 row affected`。 |
| 9 | `SELECT id,name,score FROM demo_students ORDER BY id;` | 确认更新生效，并展示排序。 | 三行依次为 `(1,Alice,90)`、`(2,Bob,95)`、`(3,Cindy,90)`。 |
| 10 | `SELECT score,COUNT(*) AS total FROM demo_students GROUP BY score ORDER BY score;` | 按分数分组并统计每组人数。 | `score,total` 两列依次为 `(90,2)`、`(95,1)`；`2 rows in set`。 |
| 11 | `SELECT score,COUNT(*) AS total FROM demo_students GROUP BY score HAVING COUNT(*) >= 2 ORDER BY score;` | 用 `HAVING` 筛选聚合后的分组。 | 仅返回 `(90,2)`；`1 row in set`。 |
| 12 | `SELECT SUM(score) AS total_score FROM demo_students;` | 计算所有分数之和。 | `total_score` 为 `275`；`1 row in set`。 |
| 13 | `SELECT a.name,b.score FROM demo_students a JOIN demo_students b ON a.id=b.id ORDER BY a.id;` | 用表别名和等值自连接展示 `JOIN`。 | `name,score` 两列依次为 `(Alice,90)`、`(Bob,95)`、`(Cindy,90)`。 |
| 14 | `EXPLAIN SELECT name FROM demo_students WHERE age >= 18;` | 查看带筛选条件的查询计划。 | 输出以 `EXPLAIN` 开头，计划树依次包含 `Project columns=[name]`、`Filter`、`SeqScan table=demo_students`；不返回数据行。 |
| 15 | `EXPLAIN SELECT id FROM demo_students WHERE 1=1;` | 展示优化器消除恒真过滤条件。 | 输出以 `EXPLAIN` 开头，优化后计划树仅含 `Project columns=[id]` 和 `SeqScan table=demo_students`，没有 `Filter`。 |
| 16 | `EXPLAIN UPDATE demo_students SET score=100 WHERE id=1;` | 查看写入语句的计划，同时验证 `EXPLAIN` 不执行更新。 | 输出以 `EXPLAIN` 开头，包含 `Update table=demo_students`、条件和赋值信息；没有 `Query OK` 或数据行。 |
| 17 | `SELECT id,score FROM demo_students WHERE id=1;` | 确认上一条 `EXPLAIN UPDATE` 没有改动数据。 | 仅返回 `(1,90)`，分数仍为 `90`。 |
| 18 | `DELETE FROM demo_students WHERE id=3;` | 按条件删除 Cindy 的记录。 | `Query OK, 1 row affected`。 |
| 19 | `SELECT id,name FROM demo_students ORDER BY id;` | 确认删除生效。 | 仅返回 `(1,Alice)`、`(2,Bob)`；`2 rows in set`。 |
| 20 | `SELECT id FROM demo_students WHERE id=3;` | 展示无匹配记录的查询结果。 | 显示 `id` 表头和 `Empty set`，没有数据行。 |
| 21 | `INSERT INTO demo_students(id,name,age,score) VALUES (4,'David',19,70); SELECT id,name FROM demo_students WHERE id=4;` | 在同一行提交两条 SQL，展示多语句顺序执行。 | 先输出 `Query OK, 1 row affected`，再返回 `(4,David)`；`1 row in set`。 |
| 22 | `CREATE TABLE demo_students(id INT);` | 展示重复建表时的语义检查。 | 输出以 `ERROR SEMANTIC_TABLE_EXISTS (SEMANTIC)` 开头，说明表已存在。 |
| 23 | `SELECT missing_column FROM demo_students;` | 展示查询不存在的列时的语义错误。 | 输出以 `ERROR SEMANTIC_COLUMN_NOT_FOUND (SEMANTIC)` 开头，说明列不存在。 |
| 24 | `SELECT FROM demo_students;` | 展示语法错误定位。 | 输出以 `ERROR PARSER_UNEXPECTED_TOKEN (PARSER)` 开头，并指出 `FROM` 位于第 1 行第 8 列。 |
| 25 | `SELECT id,name,score FROM demo_students ORDER BY id;` | 退出程序并使用同一数据目录重启后，验证表结构和数据已持久化。 | 依次返回 `(1,Alice,90)`、`(2,Bob,95)`、`(4,David,70)`；`3 rows in set`。 |

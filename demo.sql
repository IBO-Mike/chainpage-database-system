-- ChainPage DB 最小批处理示例；可用 ./run.sh --file demo.sql 执行
CREATE TABLE demo_users(
    id INT,
    name VARCHAR
);
INSERT INTO demo_users(id, name) VALUES (1, 'Alice');
INSERT INTO demo_users(id, name) VALUES (2, 'Bob; Jr.');
SELECT * FROM demo_users;
EXPLAIN SELECT * FROM demo_users WHERE id = 2;

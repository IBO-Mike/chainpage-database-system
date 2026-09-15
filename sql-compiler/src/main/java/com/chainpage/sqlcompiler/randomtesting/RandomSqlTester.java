package com.chainpage.sqlcompiler.randomtesting;

import com.chainpage.sqlcompiler.CompilerFrontEnd;
import com.chainpage.sqlcompiler.ast.JsonCodec;
import com.chainpage.sqlcompiler.extension.ExtensionResponse;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/** 第 11 部分：有确定预期的随机 SQL；只调用编译器，不执行数据操作。 */
public final class RandomSqlTester {
    private final Function<Map<String, Object>, ExtensionResponse> compiler;
    public RandomSqlTester() { this(new CompilerFrontEnd()::compile); }
    // 包内注入用于验证测试器是否能识别误判、崩溃和错误位置。
    RandomSqlTester(Function<Map<String, Object>, ExtensionResponse> compiler) { this.compiler = Objects.requireNonNull(compiler); }

    public RandomSqlTestResponse run(Map<String, Object> request) {
        long seed; int count; String mode;
        try {
            seed = integer(request.get("seed"));
            count = Math.toIntExact(integer(request.get("count")));
            mode = (String) request.get("mode");
            if (count <= 0 || !Set.of("valid", "invalid", "mixed").contains(mode)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | ArithmeticException | NullPointerException | ClassCastException e) {
            return new RandomSqlTestResponse(false, null, fields("stage", "TEST", "code", "TEST_INVALID_REQUEST",
                    "message", "需要 64 位整数 seed、正整数 count 和 valid/invalid/mixed 模式", "line", null, "column", null));
        }
        Random random = new Random(seed);
        List<Map<String, Object>> cases = new ArrayList<>();
        int accepted = 0, rejected = 0, crashes = 0, wrongAccept = 0, wrongReject = 0, locationErrors = 0;
        boolean firstValid = random.nextBoolean();
        for (int i = 0; i < count; i++) {
            boolean valid = mode.equals("valid") || mode.equals("mixed") && ((i % 2 == 0) == firstValid);
            Sample sample = generate(random, valid);
            Map<String, Object> item = fields("index", i, "sql", sample.sql, "catalogSnapshot", snapshot(),
                    "expected", valid ? "accepted" : "rejected", "category", sample.category,
                    "expectedStage", sample.stage, "actual", null, "error", null, "exception", null, "locationError", false);
            try {
                ExtensionResponse response = compiler.apply(fields("requestId", "random-" + seed + "-" + i,
                        "sql", sample.sql, "catalogSnapshot", snapshot(), "optimize", true));
                if (response == null) throw new IllegalStateException("编译器返回 null");
                if (response.ok()) {
                    if (response.data() == null || !(response.data().get("statements") instanceof List<?> statements) || statements.isEmpty())
                        throw new IllegalStateException("成功响应缺少 statements");
                    for (Object raw : statements) {
                        if (!(raw instanceof Map<?, ?> statement) || !(statement.get("plan") instanceof Map<?, ?>)
                                || !(statement.get("optimizedPlan") instanceof Map<?, ?>)) throw new IllegalStateException("成功响应缺少完整计划");
                    }
                    accepted++; if (!valid) wrongAccept++;
                    item.put("actual", "accepted");
                } else {
                    if (response.error() == null) throw new IllegalStateException("失败响应缺少 error");
                    rejected++; if (valid) wrongReject++;
                    item.put("actual", "rejected"); item.put("error", response.error());
                    boolean badLocation = !locationValid(sample, response.error());
                    item.put("locationError", badLocation); if (badLocation) locationErrors++;
                }
            } catch (RuntimeException | AssertionError | StackOverflowError e) {
                crashes++; item.put("actual", "crashed");
                item.put("exception", fields("type", e.getClass().getName(), "message", e.getMessage()));
            }
            cases.add(item);
        }
        return new RandomSqlTestResponse(true, fields("total", count, "accepted", accepted, "rejected", rejected,
                "crashes", crashes, "wrongAccept", wrongAccept, "wrongReject", wrongReject,
                "locationErrors", locationErrors, "cases", cases), null);
    }

    private record Sample(String sql, String category, String stage, Integer exactOffset) {}
    private static Sample generate(Random r, boolean valid) {
        int n = r.nextInt(1000), m = r.nextInt(50) + 1;
        String column = r.nextBoolean() ? "id" : "v";
        String compare = List.of("=", "!=", "<", "<=", ">", ">=").get(r.nextInt(6));
        String direction = r.nextBoolean() ? "ASC" : "DESC";
        String prefix = r.nextBoolean() ? "" : "-- random SQL\n  ";
        if (valid) {
            String sql = switch (r.nextInt(15)) {
                case 0 -> "SELECT * FROM t;";
                case 1 -> "SELECT " + column + " FROM t WHERE " + column + compare + n + ";";
                case 2 -> "SELECT name FROM t WHERE (id>" + n + " AND " + m + "=" + m + ") OR NOT v<" + m + ";";
                case 3 -> "INSERT INTO t(id,v,name) VALUES(" + n + "," + m + ",'text; it''s " + n + "');";
                case 4 -> "DELETE FROM t WHERE id" + compare + n + ";";
                case 5 -> "UPDATE t SET v=v+" + m + " WHERE id=" + n + ";";
                case 6 -> "SELECT id FROM t WHERE v IS NULL ORDER BY id " + direction + ";";
                case 7 -> "SELECT v,COUNT(*) AS n FROM t GROUP BY v HAVING COUNT(*)>" + m + " ORDER BY v " + direction + ";";
                case 8 -> "SELECT SUM(v) AS total,COUNT(v) AS n FROM t;";
                case 9 -> "SELECT a.id,b.name FROM t a INNER JOIN t b ON a.id=b.id WHERE a.v>" + n + " ORDER BY a.id;";
                case 10 -> "CREATE TABLE fresh" + n + "(id INT,name VARCHAR); INSERT INTO fresh" + n + "(id,name) VALUES(" + n + ",'ok'); SELECT * FROM fresh" + n + ";";
                case 11 -> "INSERT INTO t(id,v,name) VALUES(" + n + ",NULL,'nullable');";
                case 12 -> "CREATE TABLE typed" + n + "(id BIGINT,d DECIMAL,b BOOL,day DATE); INSERT INTO typed" + n + "(id,d,b,day) VALUES(2147483648," + m + ".25,TRUE,DATE '2024-02-29');";
                case 13 -> "SELECT id FROM t WHERE NULL OR v>" + n + " ORDER BY id;";
                default -> "SELECT id FROM t WHERE " + n + "=" + n + "; DELETE FROM t WHERE id<" + m + ";";
            };
            return new Sample(prefix + sql, "valid-grammar", null, null);
        }
        String sql, stage, category; Integer offset = null;
        switch (r.nextInt(8)) {
            case 0 -> { sql = "SELECT @ FROM t;"; stage = "LEXER"; category = "illegal-character"; offset = prefix.length() + 7; }
            case 1 -> { sql = "SELECT name FROM t WHERE name='unclosed"; stage = "LEXER"; category = "unclosed-string"; offset = prefix.length() + sql.indexOf('\''); }
            case 2 -> { sql = "SELECT FROM t;"; stage = "PARSER"; category = "missing-projection"; }
            case 3 -> { sql = "UPDATE t SET v=;"; stage = "PARSER"; category = "missing-expression"; }
            case 4 -> { sql = "SELECT missing" + n + " FROM t;"; stage = "SEMANTIC"; category = "unknown-column"; }
            case 5 -> { sql = "SELECT * FROM absent" + n + ";"; stage = "SEMANTIC"; category = "unknown-table"; }
            case 6 -> { sql = "SELECT id FROM t"; stage = "PARSER"; category = "missing-semicolon"; offset = prefix.length() + sql.length(); }
            default -> { sql = "INSERT INTO t(id,v,name) VALUES('wrong'," + m + ",'ok');"; stage = "SEMANTIC"; category = "type-mismatch"; }
        }
        return new Sample(prefix + sql, category, stage, offset);
    }
    private static boolean locationValid(Sample sample, Map<String, Object> error) {
        try {
            int line = Math.toIntExact(integer(error.get("line"))), column = Math.toIntExact(integer(error.get("column")));
            String[] lines = sample.sql.split("\n", -1);
            if (line < 1 || line > lines.length || column < 1 || column > lines[line - 1].length() + 1) return false;
            if (sample.exactOffset != null) {
                int offset = column - 1; for (int i = 0; i < line - 1; i++) offset += lines[i].length() + 1;
                return offset == sample.exactOffset;
            }
            return true;
        } catch (IllegalArgumentException | ArithmeticException | NullPointerException e) { return false; }
    }
    private static Map<String, Object> snapshot() {
        return fields("tables", new ArrayList<>(List.of(fields("name", "t", "columns", new ArrayList<>(List.of(
                fields("name", "id", "dataType", "INT"), fields("name", "v", "dataType", "INT"), fields("name", "name", "dataType", "VARCHAR")))))));
    }
    private static long integer(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException();
        return new BigDecimal(value.toString()).longValueExact();
    }
    private static Map<String, Object> fields(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) result.put((String)fields[i], fields[i + 1]); return result;
    }
    public static void main(String[] args) {
        if (args.length != 3) throw new IllegalArgumentException("用法：RandomSqlTester seed count valid|invalid|mixed");
        System.out.println(JsonCodec.stringify(new RandomSqlTester().run(fields("seed", Long.parseLong(args[0]), "count", Integer.parseInt(args[1]), "mode", args[2])).toMap()));
    }
}

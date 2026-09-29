package com.chainpage.sqlcompiler.extension;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

/**
 * 拓展版语法分析器——与基础 LL(1) {@link com.chainpage.sqlcompiler.parser.Parser} 相互独立，
 * 采用<b>递归下降</b>实现（每个非终结符对应一个方法，方法间按调用链自然表达优先级）。
 *
 * <p>扩展文法覆盖：CREATE TABLE（含 NOT NULL/NULL 约束）、INSERT 多行 VALUES、
 * UPDATE ... SET、DELETE、SELECT（表达式投影 + 别名、JOIN、WHERE、GROUP BY、HAVING、
 * ORDER BY ... ASC/DESC [NULLS FIRST/LAST]）以及更完整的表达式体系
 * （OR &lt; AND &lt; NOT &lt; IS NULL/比较 &lt; 加减 &lt; 乘除 &lt; 一元正负 &lt; PRIMARY，
 * 含聚合函数、限定列名 t.c、t.*、NULL/TRUE/FALSE/DATE 字面量等）。</p>
 *
 * <p>输入仍为 {tokens: Token[]}（由 ExtensionLexer 产出，必须以唯一 EOF 结尾），
 * 输出为 {statements: Statement[]}（Map 形式的扩展 AST）。</p>
 */
public final class ExtensionParser {
    /** 语法分析入口：先在 Reader 构造器中校验 Token 流，再自顶向下解析出语句列表。 */
    public ExtensionResponse parse(Map<String, Object> request) {
        return ExtensionResponse.run("PARSER", () -> node("statements", new Reader(nodes(request.get("tokens"))).program()));
    }

    /** 递归下降解析器：token 游标 + 每个非终结符一个方法。 */
    private static final class Reader {
        final List<Map<String, Object>> tokens;
        int index;

        /**
         * 构造时校验 Token 流格式：每个 Token 的 type/lexeme/line/column 必须完整合法，
         * EOF 必须存在且只能出现在末尾，否则抛 PARSER/INVALID_REQUEST。
         */
        Reader(List<Map<String, Object>> tokens) {
            if (tokens == null || tokens.isEmpty()) throw fail("PARSER", "INVALID_REQUEST", "tokens 必须以唯一 EOF 结束", null);
            for (int i = 0; i < tokens.size(); i++) {
                Map<String, Object> t = tokens.get(i);
                String type = text(t, "type");
                if (!(t.get("lexeme") instanceof String) || !(t.get("line") instanceof Number row)
                        || !(t.get("column") instanceof Number col) || row.intValue() < 1 || col.intValue() < 1
                        || !Set.of("KEYWORD", "IDENTIFIER", "INT_LITERAL", "DECIMAL_LITERAL", "STRING_LITERAL", "OPERATOR", "DELIMITER", "EOF").contains(type)
                        || type.equals("EOF") != (i == tokens.size() - 1))
                    throw fail("PARSER", "INVALID_REQUEST", "Token 格式无效或 EOF 不在末尾", null);
            }
            this.tokens = tokens;
        }

        // ---- 游标辅助方法：与手写递归下降的标准记号一一对应 ----

        /** 当前 lookahead Token。 */
        Map<String, Object> current() { return tokens.get(index); }
        /** 当前 Token 的类别名。 */
        String type() { return text(current(), "type"); }
        /** 当前 Token 的词素（EOF 的词素统一视为 "EOF"，方便统一比较）。 */
        String word() { return type().equals("EOF") ? "EOF" : (String) current().get("lexeme"); }
        /** 当前词素是否等于给定关键字/符号。 */
        boolean at(String word) { return word().equals(word); }
        /** 无条件消费一个 Token 并返回它。 */
        Map<String, Object> take() { return tokens.get(index++); }
        /** 条件消费：匹配则消费并返回 true，否则不动游标。 */
        boolean accept(String word) { if (!at(word)) return false; take(); return true; }
        /** 必须匹配：匹配则消费，否则抛“意外 Token”错误。 */
        Map<String, Object> require(String word) { if (!at(word)) throw unexpected(word); return take(); }
        /** 消费一个标识符（词素统一小写，列名/表名大小写不敏感）。 */
        String identifier() {
            if (!type().equals("IDENTIFIER")) throw unexpected("IDENTIFIER");
            return ((String) take().get("lexeme")).toLowerCase(Locale.ROOT);
        }

        /** 构造扩展 AST 节点：kind + loc（取自定位 Token）+ 可变字段。 */
        Map<String, Object> ast(String kind, Map<String, Object> token, Object... fields) {
            Map<String, Object> result = node("kind", kind, "loc", node("line", token.get("line"), "column", token.get("column")));
            result.putAll(node(fields)); return result;
        }

        /** 构造“意外 Token”错误：携带 unexpected/expected 字段辅助错误诊断。 */
        Failure unexpected(String... expected) {
            Failure failure = fail("PARSER", "UNEXPECTED_TOKEN", "不支持的语法或意外 Token：" + word(), ast("", current()));
            failure.error.put("unexpected", word()); failure.error.put("expected", List.of(expected)); return failure;
        }

        /** 程序入口：零或多条语句，每条语句后必须跟分号，直至 EOF。 */
        List<Map<String, Object>> program() {
            List<Map<String, Object>> statements = new ArrayList<>();
            while (!at("EOF")) { statements.add(statement()); require(";"); }
            return statements;
        }

        /**
         * 语句分派：按首关键字选择 CREATE TABLE / INSERT / UPDATE / DELETE / SELECT。
         * CREATE TABLE 支持 NOT NULL 或 NULL 列约束（BOOLEAN 统一归一化为 BOOL）。
         */
        Map<String, Object> statement() {
            Map<String, Object> start = current();
            if (accept("SELECT")) return select(start);
            if (accept("CREATE")) {
                require("TABLE"); String table = identifier(); require("(");
                List<Map<String, Object>> columns = new ArrayList<>();
                do {
                    Map<String, Object> at = current(); String name = identifier();
                    if (!Set.of("INT", "BIGINT", "DECIMAL", "VARCHAR", "BOOL", "BOOLEAN", "DATE").contains(word()))
                        throw unexpected("INT", "BIGINT", "DECIMAL", "VARCHAR", "BOOL", "DATE");
                    String dataType = (String) take().get("lexeme");
                    if (dataType.equals("BOOLEAN")) dataType = "BOOL";   // 类型别名归一化
                    boolean nullable = true;                              // 默认允许 NULL
                    if (accept("NOT")) { require("NULL"); nullable = false; } else accept("NULL");
                    columns.add(ast("ColumnDef", at, "name", name, "dataType", dataType, "nullable", nullable));
                } while (accept(","));
                require(")"); return ast("CreateTableStmt", start, "table", table, "columns", columns);
            }
            if (accept("INSERT")) {                                       // INSERT INTO t (cols) VALUES (...), (...) 
                require("INTO"); String table = identifier(); require("(");
                List<String> columns = new ArrayList<>(); do { columns.add(identifier()); } while (accept(","));
                require(")"); require("VALUES"); List<List<Map<String, Object>>> rows = new ArrayList<>();
                do { require("("); rows.add(expressions()); require(")"); } while (accept(","));
                return ast("InsertStmt", start, "table", table, "columns", columns, "rows", rows);
            }
            if (accept("UPDATE")) {                                       // UPDATE t SET c = expr, ... [WHERE expr]
                String table = identifier(); require("SET"); List<Map<String, Object>> assignments = new ArrayList<>();
                do {
                    Map<String, Object> at = current(); String column = identifier(); require("=");
                    assignments.add(ast("Assignment", at, "column", column, "value", expression()));
                } while (accept(","));
                return ast("UpdateStmt", start, "table", table, "assignments", assignments, "where", accept("WHERE") ? expression() : null);
            }
            if (accept("DELETE")) {                                       // DELETE FROM t [WHERE expr]
                require("FROM"); String table = identifier();
                return ast("DeleteStmt", start, "table", table, "where", accept("WHERE") ? expression() : null);
            }
            throw unexpected("CREATE", "INSERT", "SELECT", "UPDATE", "DELETE");
        }

        /** 表引用：表名 + 可选别名（AS 或裸标识符），默认别名为表名本身。 */
        Map<String, Object> table() {
            Map<String, Object> start = current(); String name = identifier(), alias = name;
            if (accept("AS") || type().equals("IDENTIFIER")) alias = identifier();
            return ast("TableRef", start, "table", name, "alias", alias);
        }

        /**
         * SELECT 语句（扩展核心）：
         * SELECT 投影项列表（表达式 + 可选别名）FROM 表引用，后接零或多个 JOIN，
         * 再依次是可选的 WHERE / GROUP BY / HAVING / ORDER BY。
         * 排序默认 ASC 且 NULLS LAST（DESC 时 NULLS FIRST），可用 NULLS FIRST/LAST 覆盖。
         */
        Map<String, Object> select(Map<String, Object> start) {
            List<Map<String, Object>> items = new ArrayList<>();
            do {
                Map<String, Object> at = current(), expr = expression();
                String alias = accept("AS") ? identifier() : null;
                items.add(ast("SelectItem", at, "expression", expr, "alias", alias));
            } while (accept(","));
            require("FROM"); Map<String, Object> from = table(); List<Map<String, Object>> joins = new ArrayList<>();
            while (at("JOIN") || at("INNER") || at("LEFT")) {             // JOIN 类型：INNER（默认）或 LEFT [OUTER]
                Map<String, Object> at = current(); String joinType = "INNER";
                if (accept("LEFT")) { joinType = "LEFT"; accept("OUTER"); } else accept("INNER");
                require("JOIN"); Map<String, Object> right = table(); require("ON");
                joins.add(ast("Join", at, "joinType", joinType, "table", right, "on", expression()));
            }
            Map<String, Object> where = accept("WHERE") ? expression() : null, group = null, order = null;
            if (at("GROUP")) { Map<String, Object> at = take(); require("BY"); group = ast("GroupBy", at, "expressions", expressions()); }
            Map<String, Object> having = accept("HAVING") ? expression() : null;
            if (at("ORDER")) {
                Map<String, Object> at = take(); require("BY"); List<Map<String, Object>> sorts = new ArrayList<>();
                do {
                    Map<String, Object> sortAt = current(), expr = expression(); String direction = "ASC", nulls = "LAST";
                    if (accept("DESC")) direction = "DESC"; else accept("ASC");
                    nulls = direction.equals("ASC") ? "LAST" : "FIRST";   // 默认 NULLS 位置随方向确定
                    if (accept("NULLS")) { if (accept("FIRST")) nulls = "FIRST"; else require("LAST"); }
                    sorts.add(ast("SortItem", sortAt, "expression", expr, "direction", direction, "nulls", nulls));
                } while (accept(","));
                order = ast("OrderBy", at, "items", sorts);
            }
            return ast("SelectStmt", start, "items", items, "from", from, "joins", joins,
                    "where", where, "groupBy", group, "having", having, "orderBy", order);
        }

        /** 逗号分隔的表达式列表（用于 VALUES 行、GROUP BY 等）。 */
        List<Map<String, Object>> expressions() {
            List<Map<String, Object>> result = new ArrayList<>(); do { result.add(expression()); } while (accept(",")); return result;
        }

        // ---- 表达式层级：方法链自上而下，层级越深优先级越高 ----

        Map<String, Object> expression() { return or(); }
        /** OR 层（最低优先级）：左结合。 */
        Map<String, Object> or() {
            Map<String, Object> left = and(); while (at("OR")) { Map<String, Object> op = take(); left = binary(op, left, and()); } return left;
        }
        /** AND 层：左结合，优先级高于 OR。 */
        Map<String, Object> and() {
            Map<String, Object> left = not(); while (at("AND")) { Map<String, Object> op = take(); left = binary(op, left, not()); } return left;
        }
        /** NOT 层：前缀 NOT 可递归叠加（如 NOT NOT x）。 */
        Map<String, Object> not() {
            if (at("NOT")) { Map<String, Object> op = take(); return ast("UnaryExpr", op, "operator", "NOT", "operand", not()); }
            return comparison();
        }
        /** 比较层：IS [NOT] NULL 或 比较运算符（= != <> > >= < <=），各至多一个。 */
        Map<String, Object> comparison() {
            Map<String, Object> left = addition();
            if (at("IS")) { Map<String, Object> op = take(); boolean negated = accept("NOT"); require("NULL"); return ast("IsNullExpr", op, "operand", left, "negated", negated); }
            if (Set.of("=", "!=", "<>", ">", ">=", "<", "<=").contains(word())) { Map<String, Object> op = take(); return binary(op, left, addition()); }
            return left;
        }
        /** 加减层：左结合。 */
        Map<String, Object> addition() {
            Map<String, Object> left = product(); while (at("+") || at("-")) { Map<String, Object> op = take(); left = binary(op, left, product()); } return left;
        }
        /** 乘除层：左结合，优先级高于加减。 */
        Map<String, Object> product() {
            Map<String, Object> left = unary(); while (at("*") || at("/")) { Map<String, Object> op = take(); left = binary(op, left, unary()); } return left;
        }
        /**
         * 一元正负层：对字面量取负时直接折叠为负数字面量（如 -3 → LiteralExpr(value=-3)），
         * 其余生成 UnaryExpr。
         */
        Map<String, Object> unary() {
            if (at("-") || at("+")) {
                Map<String, Object> op = take(), operand = unary();
                if (operand.get("kind").equals("LiteralExpr") && operand.get("value") instanceof Number number) {
                    // 常量折叠：把一元负号并入字面量值（BigInteger 取负 / BigDecimal 取负）
                    Object value = op.get("lexeme").equals("-") ? (number instanceof BigInteger b ? b.negate() : new BigDecimal(number.toString()).negate()) : number;
                    String numericType = value instanceof BigInteger b ? (b.bitLength() < 32 ? "INT" : "BIGINT") : "DECIMAL";
                    return ast("LiteralExpr", op, "literalType", numericType, "value", value);
                }
                return ast("UnaryExpr", op, "operator", op.get("lexeme"), "operand", operand);
            }
            return primary();
        }
        /**
         * PRIMARY 层（最高优先级）：括号表达式、NULL/TRUE/FALSE/DATE'...'/字符串/数字字面量、
         * 聚合函数 COUNT/SUM/AVG/MIN/MAX、* 或 t.*、限定/非限定列名。
         * 整数字面量按位宽归一化为 INT 或 BIGINT，小数为 DECIMAL。
         */
        Map<String, Object> primary() {
            Map<String, Object> start = current();
            if (accept("(")) { Map<String, Object> expr = expression(); require(")"); return expr; }   // 括号改变优先级
            if (accept("NULL")) return ast("NullLiteralExpr", start, "value", null);
            if (at("TRUE") || at("FALSE")) return ast("LiteralExpr", start, "literalType", "BOOL", "value", take().get("lexeme").equals("TRUE"));
            if (accept("DATE")) {                                       // DATE 'yyyy-mm-dd' 字面量（拓展点）
                if (!type().equals("STRING_LITERAL")) throw unexpected("STRING_LITERAL");
                return ast("LiteralExpr", start, "literalType", "DATE", "value", string());
            }
            if (type().equals("STRING_LITERAL")) return ast("LiteralExpr", start, "literalType", "VARCHAR", "value", string());
            if (type().equals("INT_LITERAL") || type().equals("DECIMAL_LITERAL")) {
                String type = type(), lexeme = (String) take().get("lexeme");
                if (type.equals("DECIMAL_LITERAL")) return ast("LiteralExpr", start, "literalType", "DECIMAL", "value", new BigDecimal(lexeme));
                BigInteger number = new BigInteger(lexeme);             // 用大整数接收，再按位宽归类 INT/BIGINT
                return ast("LiteralExpr", start, "literalType", number.bitLength() < 32 ? "INT" : "BIGINT", "value", number);
            }
            if (Set.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(word())) {   // 聚合函数（拓展点）
                String function = (String) take().get("lexeme"); require("("); Map<String, Object> argument = expression(); require(")");
                return ast("AggregateExpr", start, "function", function, "argument", argument);
            }
            if (accept("*")) return ast("StarExpr", start, "qualifier", null);   // SELECT *
            if (type().equals("IDENTIFIER")) {                          // 列名，可带 t. 限定符（含 t.*）
                String name = identifier(), qualifier = null;
                if (accept(".")) {
                    qualifier = name;
                    if (accept("*")) return ast("StarExpr", start, "qualifier", qualifier);
                    name = identifier();
                }
                return ast("IdentifierExpr", start, "name", name, "qualifier", qualifier);
            }
            throw unexpected("IDENTIFIER", "INT_LITERAL", "DECIMAL_LITERAL", "STRING_LITERAL", "NULL", "(");
        }

        /** 提取字符串字面量的值：去掉两侧引号，并把 '' 还原为单个 '。 */
        String string() {
            String value = (String) take().get("lexeme");
            if (value.length() < 2 || !value.startsWith("'") || !value.endsWith("'")) throw new IllegalArgumentException("quoted literal");
            return value.substring(1, value.length() - 1).replace("''", "'");
        }

        /** 构建 BinaryExpr：<> 统一归一化为 !=。 */
        Map<String, Object> binary(Map<String, Object> op, Map<String, Object> left, Map<String, Object> right) {
            return ast("BinaryExpr", op, "operator", op.get("lexeme").equals("<>") ? "!=" : op.get("lexeme"), "left", left, "right", right);
        }
    }
}

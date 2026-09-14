package com.chainpage.sqlcompiler.extension;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import static com.chainpage.sqlcompiler.extension.SqlTree.*;

final class SqlTypes {
    static final Set<String> TYPES = Set.of("INT", "BIGINT", "DECIMAL", "VARCHAR", "BOOL", "DATE");
    static boolean numeric(String type) { return Set.of("INT", "BIGINT", "DECIMAL").contains(type); }
    static BigDecimal decimal(Object value) { return value instanceof BigDecimal d ? d : new BigDecimal(value.toString()); }
    static String widen(String a, String b) {
        if (a.equals("NULL")) return b.equals("NULL") ? "DECIMAL" : b;
        if (b.equals("NULL")) return a;
        return a.equals("DECIMAL") || b.equals("DECIMAL") ? "DECIMAL" : a.equals("BIGINT") || b.equals("BIGINT") ? "BIGINT" : "INT";
    }
    static boolean assignable(String from, String to) {
        return from.equals("NULL") || from.equals(to) || from.equals("INT") && Set.of("BIGINT", "DECIMAL").contains(to)
                || from.equals("BIGINT") && to.equals("DECIMAL");
    }
    static Object coerce(Object value, String type, boolean nullable, String stage, Map<String, Object> owner) {
        if (value == null) {
            if (!nullable) throw fail(stage, "NOT_NULL_VIOLATION", "NOT NULL 列不得写入 NULL", owner);
            return null;
        }
        try {
            return switch (type) {
                case "INT" -> { if (!(value instanceof Number)) throw new IllegalArgumentException(); yield decimal(value).intValueExact(); }
                case "BIGINT" -> { if (!(value instanceof Number)) throw new IllegalArgumentException(); yield decimal(value).longValueExact(); }
                case "DECIMAL" -> { if (!(value instanceof Number)) throw new IllegalArgumentException(); yield decimal(value); }
                case "VARCHAR" -> { if (!(value instanceof String)) throw new IllegalArgumentException(); yield value; }
                case "BOOL" -> { if (!(value instanceof Boolean)) throw new IllegalArgumentException(); yield value; }
                case "DATE" -> {
                    if (!(value instanceof String s) || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
                    LocalDate.parse(s); yield s;
                }
                default -> throw fail(stage, "UNSUPPORTED_TYPE", "不支持的数据类型：" + type, owner);
            };
        } catch (ArithmeticException | IllegalArgumentException | java.time.DateTimeException exception) {
            throw fail(stage, "INVALID_VALUE", "值不符合 " + type + " 类型或超出范围", owner);
        }
    }
    static Object groupValue(Object value) { return value instanceof Number ? decimal(value).stripTrailingZeros() : value; }
    @SuppressWarnings("unchecked") static int compare(Object left, Object right) {
        return left instanceof Number && right instanceof Number ? decimal(left).compareTo(decimal(right))
                : ((Comparable<Object>) left).compareTo(right);
    }
}

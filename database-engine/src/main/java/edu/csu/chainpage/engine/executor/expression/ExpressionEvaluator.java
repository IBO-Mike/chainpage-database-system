package edu.csu.chainpage.engine.executor.expression;

import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.executor.core.ExecutorSupport;
import edu.csu.chainpage.engine.storage.Row;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

// 递归求值执行计划中的标识符、字面量、比较、算术和逻辑表达式
public final class ExpressionEvaluator {

    // 递归计算任意一种基本表达式
    public DbResult<Object> evaluate(Object expression, Row row) {
        if (!(expression instanceof Map<?, ?> map)) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "表达式必须是JSON对象");
        }
        Object rawKind = map.get("kind");
        if (!(rawKind instanceof String kind) || kind.isBlank()) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "表达式缺少有效kind字段");
        }
        Map<String, Object> fields = toStringMap(map);
        return switch (kind) {
            case "IdentifierExpr" -> evaluateIdentifier(fields, row);
            case "LiteralExpr" -> evaluateLiteral(fields);
            case "BinaryExpr" -> evaluateBinary(fields, row);
            case "UnaryExpr" -> evaluateUnary(fields, row);
            default -> ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "不支持的表达式种类：" + kind);
        };
    }

    // 根据当前记录读取一个列标识符
    public DbResult<Object> evaluateIdentifier(Map<String, Object> expression, Row row) {
        if (expression == null || row == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "标识符表达式或记录不能为null");
        }
        Object rawName = expression.get("name");
        if (!(rawName instanceof String name) || name.isBlank()) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "标识符缺少有效列名");
        }
        if (!row.contains(name) || row.valueOf(name) == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "引用了不存在或为空的列：" + name);
        }
        return DbResult.ok(row.valueOf(name));
    }

    // 把LiteralExpr转换成Java基本值
    public DbResult<Object> evaluateLiteral(Map<String, Object> expression) {
        if (expression == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "字面量表达式不能为null");
        }
        Object rawType = expression.get("literalType");
        if (!(rawType instanceof String literalType) || literalType.isBlank()
                || !expression.containsKey("value")) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "字面量必须包含literalType和value");
        }
        Object value = expression.get("value");
        return switch (literalType.toUpperCase(Locale.ROOT)) {
            case "INT" -> {
                Integer integer = toInteger(value);
                yield integer == null
                        ? ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "INT字面量不是合法整数")
                        : DbResult.ok(integer);
            }
            case "VARCHAR" -> value instanceof String
                    ? DbResult.ok(value)
                    : ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "VARCHAR字面量必须是字符串");
            case "BOOL", "BOOLEAN" -> value instanceof Boolean
                    ? DbResult.ok(value)
                    : ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "BOOL字面量必须是布尔值");
            default -> ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "不支持的字面量类型：" + literalType);
        };
    }

    // 计算比较、算术和AND/OR二元表达式
    public DbResult<Object> evaluateBinary(Map<String, Object> expression, Row row) {
        if (expression == null || !expression.containsKey("operator")
                || !expression.containsKey("left") || !expression.containsKey("right")) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "二元表达式字段不完整");
        }
        Object rawOperator = expression.get("operator");
        if (!(rawOperator instanceof String operator) || operator.isBlank()) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "二元表达式缺少operator");
        }
        String normalizedOperator = operator.toUpperCase(Locale.ROOT);
        DbResult<Object> left = evaluate(expression.get("left"), row);
        if (!left.isOk()) {
            return left;
        }
        Object leftValue = left.data();
        if ("AND".equals(normalizedOperator) && Boolean.FALSE.equals(leftValue)) {
            return DbResult.ok(false);
        }
        if ("OR".equals(normalizedOperator) && Boolean.TRUE.equals(leftValue)) {
            return DbResult.ok(true);
        }
        DbResult<Object> right = evaluate(expression.get("right"), row);
        if (!right.isOk()) {
            return right;
        }
        Object rightValue = right.data();

        return switch (normalizedOperator) {
            case "AND" -> booleanResult(leftValue, rightValue, "AND");
            case "OR" -> booleanResult(leftValue, rightValue, "OR");
            case "=", "==", "!=", "<>", ">", ">=", "<", "<=" ->
                    compareResult(leftValue, rightValue, normalizedOperator);
            case "+", "-", "*", "/", "%" -> arithmeticResult(leftValue, rightValue, normalizedOperator);
            default -> ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "不支持的二元运算符：" + operator);
        };
    }

    // 计算NOT一元表达式
    public DbResult<Object> evaluateUnary(Map<String, Object> expression, Row row) {
        if (expression == null || !expression.containsKey("operator")
                || !expression.containsKey("operand")) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "一元表达式字段不完整");
        }
        Object rawOperator = expression.get("operator");
        if (!(rawOperator instanceof String operator)
                || !"NOT".equalsIgnoreCase(operator)) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "只支持NOT一元运算符");
        }
        DbResult<Object> operand = evaluate(expression.get("operand"), row);
        if (!operand.isOk()) {
            return operand;
        }
        if (!(operand.data() instanceof Boolean value)) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "NOT的操作数必须是布尔值");
        }
        return DbResult.ok(!value);
    }

    // 计算WHERE谓词并要求结果必须是Boolean
    public DbResult<Boolean> evaluatePredicate(Object expression, Row row) {
        DbResult<Object> value = evaluate(expression, row);
        if (!value.isOk()) {
            return DbResult.fail(value.error());
        }
        if (!(value.data() instanceof Boolean predicate)) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "WHERE表达式结果必须是布尔值");
        }
        return DbResult.ok(predicate);
    }

    // 将任意键对象转换成字符串键的表达式字段
    private Map<String, Object> toStringMap(Map<?, ?> source) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                return Map.of();
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    // 验证AND或OR的两个布尔操作数
    private DbResult<Object> booleanResult(Object left, Object right, String operator) {
        if (!(left instanceof Boolean leftValue) || !(right instanceof Boolean rightValue)) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", operator + "的操作数必须是布尔值");
        }
        return DbResult.ok("AND".equals(operator)
                ? leftValue && rightValue
                : leftValue || rightValue);
    }

    // 计算相等、不等和大小比较
    private DbResult<Object> compareResult(Object left, Object right, String operator) {
        if (left == null || right == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "比较操作数不能为null");
        }
        int comparison;
        if (left instanceof Number && right instanceof Number) {
            try {
                comparison = new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
            } catch (NumberFormatException exception) {
                return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "数字操作数无效");
            }
        } else if (left instanceof String leftText && right instanceof String rightText) {
            comparison = leftText.compareTo(rightText);
        } else if (left instanceof Boolean leftBoolean && right instanceof Boolean rightBoolean) {
            comparison = Boolean.compare(leftBoolean, rightBoolean);
        } else {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "比较操作数类型不一致");
        }

        boolean result = switch (operator) {
            case "=", "==" -> comparison == 0;
            case "!=", "<>" -> comparison != 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            default -> false;
        };
        return DbResult.ok(result);
    }

    // 计算只返回整数结果的基本算术表达式
    private DbResult<Object> arithmeticResult(Object left, Object right, String operator) {
        Integer leftInteger = toInteger(left);
        Integer rightInteger = toInteger(right);
        if (leftInteger == null || rightInteger == null) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "算术运算的操作数必须是INT");
        }
        if (("/".equals(operator) || "%".equals(operator)) && rightInteger == 0) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "算术运算不能除以零");
        }
        try {
            int result = switch (operator) {
                case "+" -> Math.addExact(leftInteger, rightInteger);
                case "-" -> Math.subtractExact(leftInteger, rightInteger);
                case "*" -> Math.multiplyExact(leftInteger, rightInteger);
                case "/" -> leftInteger / rightInteger;
                case "%" -> leftInteger % rightInteger;
                default -> throw new IllegalArgumentException("unsupported operator");
            };
            return DbResult.ok(result);
        } catch (ArithmeticException exception) {
            return ExecutorSupport.failure(null, "EXECUTOR_PREDICATE_ERROR", "算术结果超出INT范围");
        }
    }

    // 把整数型Java对象安全转换为32位整数
    private Integer toInteger(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).intValue();
        }
        if (value instanceof Long longValue) {
            return longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE
                    ? longValue.intValue()
                    : null;
        }
        if (value instanceof BigInteger bigInteger) {
            try {
                return bigInteger.intValueExact();
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        if (value instanceof BigDecimal bigDecimal) {
            try {
                return bigDecimal.intValueExact();
            } catch (ArithmeticException exception) {
                return null;
            }
        }
        return null;
    }
}

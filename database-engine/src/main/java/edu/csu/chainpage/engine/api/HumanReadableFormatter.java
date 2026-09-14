package edu.csu.chainpage.engine.api;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;
import edu.csu.chainpage.engine.contract.CompiledStatement;
import edu.csu.chainpage.engine.executor.CommandResult;
import edu.csu.chainpage.engine.explain.ExplainResult;
import edu.csu.chainpage.engine.plan.JsonPlanNode;
import edu.csu.chainpage.engine.plan.PlanNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

// 把数据库内部结果转换为普通用户容易阅读的命令行文本
public final class HumanReadableFormatter {

    // 把统一数据库结果转换为不带JSON包络的文本
    public String format(DbResult<?> response) {
        Objects.requireNonNull(response, "response cannot be null");
        if (!response.isOk()) {
            return formatError(response.error());
        }

        Object data = response.data();
        if (data instanceof DatabaseResponse databaseResponse) {
            return formatDatabaseResponse(databaseResponse);
        }
        if (data instanceof ExplainResult explainResult) {
            return formatExplainResult(explainResult);
        }
        if (data == null) {
            return "Query OK";
        }
        return formatUnknownValue(data);
    }

    // 把一组普通执行结果和可能存在的部分失败错误按顺序拼接
    private String formatDatabaseResponse(DatabaseResponse response) {
        List<String> sections = new ArrayList<>();
        for (Object item : response.getResults()) {
            String formatted = formatResultItem(item);
            if (!formatted.isBlank()) {
                sections.add(formatted);
            }
        }
        if (response.getError() != null) {
            sections.add(formatError(response.getError()));
        }
        return sections.isEmpty() ? "Query OK" : String.join("\n\n", sections);
    }

    // 格式化一条编译结果或一条执行结果
    private String formatResultItem(Object item) {
        if (item instanceof StatementExecutionResult executionResult) {
            return formatStatementExecution(executionResult);
        }
        if (item instanceof CompiledStatement compiledStatement) {
            return formatCompiledStatement(compiledStatement);
        }
        return formatUnknownValue(item);
    }

    // 格式化一条SQL语句的执行结果
    private String formatStatementExecution(StatementExecutionResult executionResult) {
        Object result = executionResult.getResult();
        if (result instanceof CommandResult commandResult) {
            return formatCommandResult(commandResult);
        }
        if (result instanceof Map<?, ?> map) {
            return formatCommandMap(executionResult.getKind(), map);
        }
        String kind = safeText(executionResult.getKind());
        if (result == null) {
            return "Query OK" + (kind.isBlank() ? "" : " (" + kind + ")");
        }
        return (kind.isBlank() ? "Result" : kind) + ": " + formatValue(result);
    }

    // 按MySQL常见样式格式化建表、增删改和查询结果
    public String formatCommandResult(CommandResult result) {
        Objects.requireNonNull(result, "result cannot be null");
        String kind = safeText(result.getKind()).toUpperCase(Locale.ROOT);
        if (isRowSetKind(kind) || !result.getColumns().isEmpty() || !result.getRows().isEmpty()) {
            return formatTable(result.getColumns(), result.getRows());
        }
        int affectedRows = result.getAffectedRows();
        return "Query OK, " + affectedRows + " "
                + (affectedRows == 1 ? "row" : "rows") + " affected";
    }

    // 兼容通过JSON反序列化得到的命令结果对象
    private String formatCommandMap(String kind, Map<?, ?> map) {
        List<String> columns = stringList(map.get("columns"));
        List<List<Object>> rows = rowList(map.get("rows"));
        String normalizedKind = safeText(kind).isBlank()
                ? safeText(map.get("kind"))
                : kind;
        if (isRowSetKind(normalizedKind.toUpperCase(Locale.ROOT))
                || !columns.isEmpty()
                || !rows.isEmpty()) {
            return formatTable(columns, rows);
        }
        int affectedRows = integerValue(map.get("affectedRows"), 0);
        return "Query OK, " + affectedRows + " "
                + (affectedRows == 1 ? "row" : "rows") + " affected";
    }

    // 判断结果是否属于返回二维行集的查询类语句
    private boolean isRowSetKind(String kind) {
        return "SELECT".equals(kind)
                || "SHOW".equals(kind)
                || "DESCRIBE".equals(kind)
                || "DESC".equals(kind)
                || "EXPLAIN".equals(kind);
    }

    // 将查询列和行渲染为带边框的ASCII表格
    public String formatTable(List<String> sourceColumns, List<? extends List<?>> sourceRows) {
        List<String> columns = sourceColumns == null
                ? List.of()
                : sourceColumns.stream().map(this::safeText).toList();
        List<? extends List<?>> rows = sourceRows == null ? List.of() : sourceRows;
        int columnCount = columns.size();
        for (List<?> row : rows) {
            if (row != null) {
                columnCount = Math.max(columnCount, row.size());
            }
        }

        if (columnCount == 0) {
            return rows.isEmpty() ? "Empty set" : formatRowsWithoutColumns(rows);
        }

        List<String> headers = new ArrayList<>();
        for (int index = 0; index < columnCount; index++) {
            headers.add(index < columns.size() && !columns.get(index).isBlank()
                    ? columns.get(index)
                    : "column" + (index + 1));
        }

        List<List<String>> values = new ArrayList<>();
        for (List<?> row : rows) {
            List<String> formattedRow = new ArrayList<>();
            for (int index = 0; index < columnCount; index++) {
                Object value = row != null && index < row.size() ? row.get(index) : null;
                formattedRow.add(formatCell(value));
            }
            values.add(formattedRow);
        }

        int[] widths = new int[columnCount];
        for (int index = 0; index < columnCount; index++) {
            widths[index] = headers.get(index).length();
        }
        for (List<String> row : values) {
            for (int index = 0; index < columnCount; index++) {
                widths[index] = Math.max(widths[index], row.get(index).length());
            }
        }

        StringBuilder output = new StringBuilder();
        String border = tableBorder(widths);
        output.append(border).append('\n');
        output.append(tableRow(headers, widths)).append('\n');
        output.append(border);
        for (List<String> row : values) {
            output.append('\n').append(tableRow(row, widths));
        }
        output.append('\n').append(border).append('\n');
        output.append(values.isEmpty()
                ? "Empty set"
                : values.size() + " " + (values.size() == 1 ? "row" : "rows") + " in set");
        return output.toString();
    }

    // 没有列名时仍然以可读文本显示返回的行
    private String formatRowsWithoutColumns(List<? extends List<?>> rows) {
        List<String> lines = new ArrayList<>();
        for (List<?> row : rows) {
            if (row == null) {
                lines.add("NULL");
            } else {
                StringJoiner joiner = new StringJoiner(" | ");
                for (Object value : row) {
                    joiner.add(formatCell(value));
                }
                lines.add(joiner.toString());
            }
        }
        lines.add(rows.size() + " " + (rows.size() == 1 ? "row" : "rows") + " in set");
        return String.join("\n", lines);
    }

    // 创建表格横线
    private String tableBorder(int[] widths) {
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) {
            border.append("-").append("-".repeat(width)).append("-+");
        }
        return border.toString();
    }

    // 创建表格的一行
    private String tableRow(List<String> row, int[] widths) {
        StringBuilder line = new StringBuilder("|");
        for (int index = 0; index < widths.length; index++) {
            String value = index < row.size() ? row.get(index) : "";
            line.append(' ').append(value);
            line.append(" ".repeat(widths[index] - value.length()));
            line.append(" |");
        }
        return line.toString();
    }

    // 格式化EXPLAIN计划树
    private String formatExplainResult(ExplainResult result) {
        return "EXPLAIN\n" + new ReadableExplainPlanFormatter().format(result.optimizedPlan());
    }

    // 格式化compile模式返回的计划摘要
    private String formatCompiledStatement(CompiledStatement statement) {
        List<String> lines = new ArrayList<>();
        lines.add("Statement " + (statement.getStatementIndex() + 1) + ": compilation OK");
        appendPlanSummary(lines, "Plan", statement.getPlan());
        appendPlanSummary(lines, "Optimized plan", statement.getOptimizedPlan());
        return String.join("\n", lines);
    }

    // 从原始计划对象提取普通用户能理解的摘要
    private void appendPlanSummary(List<String> lines, String label, Object plan) {
        if (plan == null) {
            lines.add("  " + label + ": none");
            return;
        }
        if (plan instanceof PlanNode planNode) {
            lines.add("  " + label + ": " + describePlanNode(planNode));
            return;
        }
        if (plan instanceof Map<?, ?> map) {
            lines.add("  " + label + ": " + describePlanMap(map));
            return;
        }
        lines.add("  " + label + ": " + formatValue(plan));
    }

    // 格式化引擎内部计划节点
    private String describePlanNode(PlanNode plan) {
        if (plan instanceof JsonPlanNode jsonPlan) {
            return describePlanMap(jsonPlan.fields());
        }
        return safeText(plan.kind());
    }

    // 格式化编译器返回的计划Map而不直接输出Map.toString
    private String describePlanMap(Map<?, ?> plan) {
        String kind = safeText(plan.get("kind"));
        if (kind.isBlank()) {
            kind = "unknown";
        }
        List<String> details = new ArrayList<>();
        for (String field : List.of("table", "index", "columns", "predicate", "assignments")) {
            if (plan.containsKey(field)) {
                details.add(field + "=" + formatValue(plan.get(field)));
            }
        }
        return details.isEmpty() ? kind : kind + " (" + String.join(", ", details) + ")";
    }

    // 格式化失败结果，保留错误码、阶段和定位信息
    public String formatError(DbError error) {
        Objects.requireNonNull(error, "error cannot be null");
        String code = safeText(error.getCode());
        String stage = safeText(error.getStage());
        StringBuilder output = new StringBuilder("ERROR");
        if (!code.isBlank()) {
            output.append(' ').append(code);
        }
        if (!stage.isBlank()) {
            output.append(" (").append(stage).append(')');
        }
        output.append(": ").append(safeText(error.getMessage()));
        List<String> location = new ArrayList<>();
        if (error.getStatementIndex() != null) {
            location.add("statement " + (error.getStatementIndex() + 1));
        }
        if (error.getLine() != null) {
            location.add("line " + error.getLine());
        }
        if (error.getColumn() != null) {
            location.add("column " + error.getColumn());
        }
        if (error.getPageId() != null) {
            location.add("page " + error.getPageId());
        }
        if (!location.isEmpty()) {
            output.append(" [").append(String.join(", ", location)).append(']');
        }
        return output.toString();
    }

    // 处理未识别的数据类型，避免回退到JSON编码
    private String formatUnknownValue(Object value) {
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "(no results)";
            }
            List<String> lines = new ArrayList<>();
            for (Object item : list) {
                lines.add(formatValue(item));
            }
            return String.join("\n", lines);
        }
        return formatValue(value);
    }

    // 将任意值转换为不包含JSON结构符号的简洁文本
    private String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Map<?, ?> map) {
            List<String> fields = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                fields.add(safeText(entry.getKey()) + "=" + formatValue(entry.getValue()));
            }
            return String.join(", ", fields);
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                items.add(formatValue(item));
            }
            return String.join(", ", items);
        }
        return formatCell(value);
    }

    // 将单元格转换为单行显示文字
    private String formatCell(Object value) {
        if (value == null) {
            return "NULL";
        }
        return safeText(value).replace('\r', ' ').replace('\n', ' ');
    }

    // 把对象安全转换为字符串
    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    // 从通用对象中读取字符串列表
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            result.add(safeText(item));
        }
        return result;
    }

    // 从通用对象中读取二维行列表
    private List<List<Object>> rowList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<List<Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof List<?> row) {
                result.add(new ArrayList<>(row));
            }
        }
        return result;
    }

    // 把数字对象安全转换为整数
    private int integerValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}

package edu.csu.chainpage.engine.plan;

import com.fasterxml.jackson.annotation.JsonValue;
import edu.csu.chainpage.engine.contract.ColumnSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// 保存从SQL编译器JSON计划转换而来的完整计划字段
public final class JsonPlanNode implements PlanNode {

    private final String kind; // 计划节点种类
    private final Map<String, Object> fields; // 计划原始字段
    private final List<PlanNode> children; // 子计划列表
    private final List<ColumnSchema> schema; // 输出列模式

    // 创建一个JSON计划节点
    public JsonPlanNode(
            String kind,
            Map<String, Object> fields,
            List<PlanNode> children,
            List<ColumnSchema> schema) {
        this.kind = Objects.requireNonNull(kind, "kind cannot be null");
        this.fields = deepUnmodifiableMap(Objects.requireNonNull(fields, "fields cannot be null"));
        this.children = List.copyOf(Objects.requireNonNull(children, "children cannot be null"));
        this.schema = List.copyOf(Objects.requireNonNull(schema, "schema cannot be null"));
    }

    // 返回计划节点种类
    @Override
    public String kind() {
        return kind;
    }

    // 读取计划中的任意字段
    public Object field(String name) {
        return fields.get(Objects.requireNonNull(name, "name cannot be null"));
    }

    // 返回完整字段的只读视图
    public Map<String, Object> fields() {
        return fields;
    }

    // 按编译器原始计划字段序列化该节点
    @JsonValue
    public Map<String, Object> toJson() {
        return fields;
    }

    // 返回子计划列表
    @Override
    public List<PlanNode> children() {
        return children;
    }

    // 返回输出列模式
    @Override
    public List<ColumnSchema> schema() {
        return schema;
    }

    // 递归复制JSON对象，避免调用方修改嵌套字段
    private Map<String, Object> deepUnmodifiableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "field name cannot be null"),
                    deepCopy(entry.getValue())
            );
        }
        return Collections.unmodifiableMap(copy);
    }

    // 递归复制JSON数组、对象和基本值
    @SuppressWarnings("unchecked")
    private Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> stringMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("plan field names must be strings");
                }
                stringMap.put(key, deepCopy(entry.getValue()));
            }
            return Collections.unmodifiableMap(stringMap);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return List.copyOf(copy);
        }
        return value;
    }
}

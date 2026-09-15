package edu.csu.chainpage.engine.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

// 表示目录快照中的一列定义
public final class ColumnSchema {

    private final String name; // 列名
    private final String dataType; // 列类型，只允许INT或VARCHAR

    // 创建列定义
    @JsonCreator
    public ColumnSchema(
            @JsonProperty("name") String name,
            @JsonProperty("dataType") String dataType) {
        this.name = name;
        this.dataType = dataType;
    }

    // 获取列名
    public String getName() {
        return name;
    }

    // 获取列类型
    public String getDataType() {
        return dataType;
    }
}

package edu.csu.chainpage.engine.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

// 数据库引擎统一的json转换工具
public final class JsonCodec {

    // 完成json序列化与反序列化的工具
    private final ObjectMapper objectMapper;

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    public JsonCodec() {
        this.objectMapper = new ObjectMapper();
    }

    // 把java对象转化为json
    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new JsonCodecException("JSON序列化失败", e);
        }
    }

    // 从json读取指定类型对象
    public <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new JsonCodecException("JSON解析失败", e);
        }
    }

    // 当暂时不知道json输入哪个类时，将其暂时读取为通用Map
    public Map<String, Object> readObject(String json) {
        try {
            return objectMapper.readValue(
                    json,
                    new TypeReference<Map<String, Object>>() {}
            );
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new JsonCodecException("JSON对象解析失败", e);
        }
    }

    // 将sql列名或表明统一小写化
    public String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        return identifier.toLowerCase(Locale.ROOT);
    }

}

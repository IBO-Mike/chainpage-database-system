package edu.csu.chainpage.engine.storage;

import edu.csu.chainpage.engine.common.DbError;
import edu.csu.chainpage.engine.common.DbResult;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

// 负责把一条逻辑记录稳定编码为页内字节并从字节还原记录
public final class PageRecordCodec {

    // 校验表结构本身是否合法
    public DbResult<Void> validateSchema(TableSchema schema) {
        if (schema == null) {
            return failure("INVALID_SCHEMA", "表结构不能为null");
        }

        Set<String> names = new HashSet<>();
        for (ColumnSchema column : schema.getColumns()) {
            if (column == null) {
                return failure("INVALID_SCHEMA", "表结构不能包含null列");
            }
            if (!names.add(column.getName())) {
                return failure("DUPLICATE_COLUMN", "表结构包含重复列：" + column.getName());
            }
            if (!"INT".equals(column.getDataType())
                    && !"VARCHAR".equals(column.getDataType())) {
                return failure(
                        "UNSUPPORTED_DATA_TYPE",
                        "不支持的数据类型：" + column.getDataType()
                );
            }
        }
        return DbResult.ok(null);
    }

    // 校验记录列是否完整，以及列值是否符合INT或VARCHAR类型
    public DbResult<Void> validateRow(TableSchema schema, Row row) {
        DbResult<Void> schemaResult = validateSchema(schema);
        if (!schemaResult.isOk()) {
            return schemaResult;
        }
        if (row == null) {
            return failure("INVALID_ROW", "记录不能为null");
        }

        for (ColumnSchema column : schema.getColumns()) {
            String name = column.getName();
            if (!row.contains(name)) {
                return failure("ROW_SCHEMA_MISMATCH", "记录缺少列：" + name);
            }
            Object value = row.valueOf(name);
            if (value == null) {
                return failure("ROW_VALUE_NULL", "列值不能为null：" + name);
            }
            if ("INT".equals(column.getDataType()) && toInteger(value) == null) {
                return failure("ROW_TYPE_MISMATCH", "列值不是合法INT：" + name);
            }
            if ("VARCHAR".equals(column.getDataType()) && !(value instanceof String)) {
                return failure("ROW_TYPE_MISMATCH", "列值不是合法VARCHAR：" + name);
            }
        }

        for (String name : row.values().keySet()) {
            if (schema.findColumn(name) == null) {
                return failure("ROW_SCHEMA_MISMATCH", "记录包含未知列：" + name);
            }
        }
        return DbResult.ok(null);
    }

    // 将一条记录按表结构列顺序编码为二进制记录
    public DbResult<byte[]> encode(TableSchema schema, Row row) {
        DbResult<Void> validation = validateRow(schema, row);
        if (!validation.isOk()) {
            return DbResult.fail(validation.error());
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            DataOutputStream dataOutput = new DataOutputStream(output);
            for (ColumnSchema column : schema.getColumns()) {
                Object value = row.valueOf(column.getName());
                if ("INT".equals(column.getDataType())) {
                    dataOutput.writeInt(toInteger(value));
                } else {
                    byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                    dataOutput.writeInt(bytes.length);
                    dataOutput.write(bytes);
                }
            }
            dataOutput.flush();
            return DbResult.ok(output.toByteArray());
        } catch (IOException | ArithmeticException exception) {
            return failure("RECORD_TOO_LARGE", "记录编码长度超出支持范围");
        }
    }

    // 从二进制记录中按同一表结构还原一条逻辑记录
    public DbResult<Row> decode(TableSchema schema, byte[] recordBytes) {
        DbResult<Void> schemaResult = validateSchema(schema);
        if (!schemaResult.isOk()) {
            return DbResult.fail(schemaResult.error());
        }
        if (recordBytes == null) {
            return failure("INVALID_RECORD", "记录字节不能为null");
        }

        ByteBuffer input = ByteBuffer.wrap(recordBytes);
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (ColumnSchema column : schema.getColumns()) {
            if ("INT".equals(column.getDataType())) {
                if (input.remaining() < Integer.BYTES) {
                    return failure("INVALID_RECORD", "INT记录字节不完整");
                }
                values.put(column.getName(), input.getInt());
                continue;
            }

            if (input.remaining() < Integer.BYTES) {
                return failure("INVALID_RECORD", "VARCHAR长度字段不完整");
            }
            int length = input.getInt();
            if (length < 0 || length > input.remaining()) {
                return failure("INVALID_RECORD", "VARCHAR长度字段非法");
            }
            byte[] bytes = new byte[length];
            input.get(bytes);
            values.put(column.getName(), new String(bytes, StandardCharsets.UTF_8));
        }

        if (input.hasRemaining()) {
            return failure("INVALID_RECORD", "记录包含未使用的尾部字节");
        }
        return DbResult.ok(new Row(values));
    }

    // 返回有效记录的编码长度；编码失败时返回-1
    public int encodedLength(TableSchema schema, Row row) {
        DbResult<byte[]> encoded = encode(schema, row);
        return encoded.isOk() ? encoded.data().length : -1;
    }

    // 把可接受的数字值转换成精确的32位整数
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

    // 创建统一格式的记录编码错误
    private <T> DbResult<T> failure(String code, String message) {
        return DbResult.fail(new DbError(
                null,
                null,
                "STORAGE",
                code,
                message,
                null,
                null,
                null
        ));
    }
}

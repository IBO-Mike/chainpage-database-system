package com.chainpage.sqlcompiler.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonCodec {
    private JsonCodec() { }

    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output);
        return output.toString();
    }

    private static void write(Object value, StringBuilder output) {
        if (value == null) output.append("null");
        else if (value instanceof String string) writeString(string, output);
        else if (value instanceof Number || value instanceof Boolean) output.append(value);
        else if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) output.append(',');
                first = false;
                writeString(String.valueOf(entry.getKey()), output);
                output.append(':');
                write(entry.getValue(), output);
            }
            output.append('}');
        } else if (value instanceof List<?> list) {
            output.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) output.append(',');
                write(list.get(i), output);
            }
            output.append(']');
        } else throw new JsonException("不支持的值类型：" + value.getClass().getName());
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (c < 0x20) output.append(String.format("\\u%04x", (int) c));
                    else output.append(c);
                }
            }
        }
        output.append('"');
    }

    public static Object parse(String json) {
        Parser parser = new Parser(json);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.end()) throw parser.error("根值之后存在多余字符");
        return value;
    }

    static final class JsonException extends RuntimeException {
        JsonException(String message) { super(message); }
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) { this.source = source; }

        private Object readValue() {
            skipWhitespace();
            if (end()) throw error("缺少 JSON 值");
            return switch (source.charAt(index)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> { expect("true"); yield true; }
                case 'f' -> { expect("false"); yield false; }
                case 'n' -> { expect("null"); yield null; }
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            index++;
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (take('}')) return result;
            while (true) {
                skipWhitespace();
                if (end() || source.charAt(index) != '"') throw error("对象键必须是字符串");
                String key = readString();
                skipWhitespace();
                require(':');
                if (result.containsKey(key)) throw error("对象键重复：" + key);
                result.put(key, readValue());
                skipWhitespace();
                if (take('}')) return result;
                require(',');
            }
        }

        private List<Object> readArray() {
            index++;
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (take(']')) return result;
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (take(']')) return result;
                require(',');
            }
        }

        private String readString() {
            index++;
            StringBuilder result = new StringBuilder();
            while (!end()) {
                char c = source.charAt(index++);
                if (c == '"') return result.toString();
                if (c == '\\') {
                    if (end()) throw error("字符串转义未完成");
                    char escaped = source.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(readUnicode());
                        default -> throw error("非法转义字符");
                    }
                } else {
                    if (c < 0x20) throw error("字符串包含控制字符");
                    result.append(c);
                }
            }
            throw error("字符串未闭合");
        }

        private char readUnicode() {
            if (index + 4 > source.length()) throw error("Unicode 转义不完整");
            try {
                char value = (char) Integer.parseInt(source.substring(index, index + 4), 16);
                index += 4;
                return value;
            } catch (NumberFormatException exception) {
                throw error("Unicode 转义非法");
            }
        }

        private Number readNumber() {
            int start = index;
            if (take('-') && end()) throw error("数字不完整");
            if (take('0')) {
                if (!end() && Character.isDigit(source.charAt(index))) throw error("数字不能有前导零");
            } else {
                if (end() || !Character.isDigit(source.charAt(index))) throw error("未知 JSON 值");
                while (!end() && Character.isDigit(source.charAt(index))) index++;
            }
            boolean decimal = false;
            if (take('.')) {
                decimal = true;
                if (end() || !Character.isDigit(source.charAt(index))) throw error("小数不完整");
                while (!end() && Character.isDigit(source.charAt(index))) index++;
            }
            if (!end() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (!end() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
                if (end() || !Character.isDigit(source.charAt(index))) throw error("指数不完整");
                while (!end() && Character.isDigit(source.charAt(index))) index++;
            }
            String text = source.substring(start, index);
            try {
                return decimal ? Double.parseDouble(text) : Long.parseLong(text);
            } catch (NumberFormatException exception) {
                throw error("数字超出范围");
            }
        }

        private void expect(String text) {
            if (!source.startsWith(text, index)) throw error("未知 JSON 值");
            index += text.length();
        }

        private void require(char expected) {
            skipWhitespace();
            if (!take(expected)) throw error("应为 " + expected);
        }

        private boolean take(char expected) {
            if (!end() && source.charAt(index) == expected) { index++; return true; }
            return false;
        }

        private void skipWhitespace() {
            while (!end() && Character.isWhitespace(source.charAt(index))) index++;
        }

        private boolean end() { return index >= source.length(); }
        private JsonException error(String message) { return new JsonException(message + "（位置 " + index + "）"); }
    }
}

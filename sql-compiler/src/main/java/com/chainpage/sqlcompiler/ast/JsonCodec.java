package com.chainpage.sqlcompiler.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON 编解码器：不依赖第三方库，服务于 AST 的序列化与反序列化。
 * <ul>
 *   <li>{@link #stringify}：把 Map/List/字符串/数字/布尔/null 序列化为 JSON 文本（按 RFC 8259
 *       转义引号、反斜杠与控制字符）；</li>
 *   <li>{@link #parse}：把 JSON 文本解析回 Map/List/字符串/Long 或 Double/布尔/null，
 *       严格执行 JSON 语法（拒绝前导零、重复键、未闭合字符串等）。</li>
 * </ul>
 */
public final class JsonCodec {
    private JsonCodec() { }   // 纯静态工具类，禁止实例化

    /** 序列化入口：把任意受支持的 Java 对象写入 JSON 文本。 */
    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output);
        return output.toString();
    }

    /** 递归序列化：按值的具体类型分派到对象/数组/字符串/标量的写出逻辑。 */
    private static void write(Object value, StringBuilder output) {
        if (value == null) output.append("null");
        else if (value instanceof String string) writeString(string, output);
        else if (value instanceof Number || value instanceof Boolean) output.append(value);
        else if (value instanceof Map<?, ?> map) {
            // 对象：键值对用逗号分隔，保持 LinkedHashMap 的插入顺序
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
            // 数组：元素用逗号分隔
            output.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) output.append(',');
                write(list.get(i), output);
            }
            output.append(']');
        } else throw new JsonException("不支持的值类型：" + value.getClass().getName());
    }

    /** 写出一个 JSON 字符串：转义引号、反斜杠与控制字符；小于 0x20 的字符输出为四位十六进制转义。 */
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

    /**
     * 反序列化入口：读取一个 JSON 值后必须到达输入末尾，否则视为多余字符。
     *
     * @throws JsonException 文本不符合 JSON 语法时
     */
    public static Object parse(String json) {
        Parser parser = new Parser(json);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.end()) throw parser.error("根值之后存在多余字符");
        return value;
    }

    /** JSON 解析异常：message 已包含出错位置的描述。 */
    static final class JsonException extends RuntimeException {
        JsonException(String message) { super(message); }
    }

    /**
     * 手写递归下降 JSON 解析器（与主 Parser 同属递归下降思想）：
     * readValue 按首字符分派到对象/数组/字符串/字面量/数字的子解析程序。
     */
    private static final class Parser {
        private final String source;
        private int index;   // 当前读取位置

        private Parser(String source) { this.source = source; }

        /** 读取任意 JSON 值：跳过空白后按首字符分派。 */
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

        /** 解析对象：键必须为字符串，禁止重复键，键后必须跟冒号。 */
        private Map<String, Object> readObject() {
            index++;   // 消费 '{'
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (take('}')) return result;   // 空对象
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
                require(',');   // 还有后续键值对则必须有逗号
            }
        }

        /** 解析数组：元素用逗号分隔，支持空数组。 */
        private List<Object> readArray() {
            index++;   // 消费 '['
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (take(']')) return result;   // 空数组
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (take(']')) return result;
                require(',');
            }
        }

        /** 解析字符串：处理全部标准转义序列，拒绝未转义的控制字符。 */
        private String readString() {
            index++;   // 消费起始 '"'
            StringBuilder result = new StringBuilder();
            while (!end()) {
                char c = source.charAt(index++);
                if (c == '"') return result.toString();   // 结束引号
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
                        case 'u' -> result.append(readUnicode());   // 4 位十六进制转义
                        default -> throw error("非法转义字符");
                    }
                } else {
                    if (c < 0x20) throw error("字符串包含控制字符");
                    result.append(c);
                }
            }
            throw error("字符串未闭合");
        }

        /** 解析 Unicode 转义（反斜杠 u 加 4 位十六进制数字）。 */
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

        /**
         * 解析数字：整数部分不允许前导零，可选小数与指数部分；
         * 含小数/指数时返回 Double，否则返回 Long。
         */
        private Number readNumber() {
            int start = index;
            if (take('-') && end()) throw error("数字不完整");
            if (take('0')) {
                // JSON 规范：前导零非法（0 后不能紧跟数字）
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

        /** 精确匹配 true/false/null 等关键字文本，不匹配即报错。 */
        private void expect(String text) {
            if (!source.startsWith(text, index)) throw error("未知 JSON 值");
            index += text.length();
        }

        /** 消费一个必需字符（先跳过空白），不存在则报错。 */
        private void require(char expected) {
            skipWhitespace();
            if (!take(expected)) throw error("应为 " + expected);
        }

        /** 尝试消费一个字符：命中返回 true 并前进，否则不动。 */
        private boolean take(char expected) {
            if (!end() && source.charAt(index) == expected) { index++; return true; }
            return false;
        }

        /** 跳过空白字符。 */
        private void skipWhitespace() {
            while (!end() && Character.isWhitespace(source.charAt(index))) index++;
        }

        private boolean end() { return index >= source.length(); }
        /** 构造携带出错下标的解析异常。 */
        private JsonException error(String message) { return new JsonException(message + "（位置 " + index + "）"); }
    }
}

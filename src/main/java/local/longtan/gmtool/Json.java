package local.longtan.gmtool;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() {
    }

    static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        write(output, value);
        return output.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String json) {
        Object value = new Parser(json).parse();
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("请求必须是 JSON 对象");
        }
        return (Map<String, Object>) value;
    }

    static Object parse(String json) {
        return new Parser(json).parse();
    }

    private static void write(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String || value instanceof Character) {
            quote(output, value.toString());
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) output.append(',');
                first = false;
                quote(output, String.valueOf(entry.getKey()));
                output.append(':');
                write(output, entry.getValue());
            }
            output.append('}');
        } else if (value instanceof Iterable) {
            output.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) output.append(',');
                first = false;
                write(output, item);
            }
            output.append(']');
        } else if (value.getClass().isArray()) {
            output.append('[');
            for (int i = 0; i < Array.getLength(value); i++) {
                if (i > 0) output.append(',');
                write(output, Array.get(value, i));
            }
            output.append(']');
        } else {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Field field : value.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                try {
                    field.setAccessible(true);
                    fields.put(field.getName(), field.get(value));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("无法序列化 " + field.getName(), e);
                }
            }
            write(output, fields);
        }
    }

    private static void quote(StringBuilder output, String text) {
        output.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (c < 0x20) output.append(String.format("\\u%04x", (int) c));
                    else output.append(c);
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String input;
        private int index;

        Parser(String input) {
            this.input = input;
        }

        Object parse() {
            Object value = value();
            whitespace();
            if (index != input.length()) throw error("JSON 尾部存在多余内容");
            return value;
        }

        private Object value() {
            whitespace();
            if (index >= input.length()) throw error("JSON 内容为空");
            char c = input.charAt(index);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') return literal("true", Boolean.TRUE);
            if (c == 'f') return literal("false", Boolean.FALSE);
            if (c == 'n') return literal("null", null);
            return number();
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return result;
            do {
                whitespace();
                String key = string();
                whitespace();
                expect(':');
                result.put(key, value());
                whitespace();
            } while (take(','));
            expect('}');
            return result;
        }

        private List<Object> array() {
            expect('[');
            List<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) return result;
            do {
                result.add(value());
                whitespace();
            } while (take(','));
            expect(']');
            return result;
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < input.length()) {
                char c = input.charAt(index++);
                if (c == '"') return result.toString();
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (index >= input.length()) throw error("字符串转义不完整");
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"': case '\\': case '/': result.append(escaped); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        if (index + 4 > input.length()) throw error("Unicode 转义不完整");
                        result.append((char) Integer.parseInt(input.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default: throw error("不支持的字符串转义");
                }
            }
            throw error("字符串未结束");
        }

        private Object number() {
            int start = index;
            if (take('-')) { }
            while (index < input.length() && Character.isDigit(input.charAt(index))) index++;
            if (take('.')) while (index < input.length() && Character.isDigit(input.charAt(index))) index++;
            if (index < input.length() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                index++;
                if (index < input.length() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
                while (index < input.length() && Character.isDigit(input.charAt(index))) index++;
            }
            if (start == index) throw error("数字格式无效");
            String token = input.substring(start, index);
            return token.contains(".") || token.contains("e") || token.contains("E")
                    ? Double.parseDouble(token) : Long.parseLong(token);
        }

        private Object literal(String token, Object value) {
            if (!input.startsWith(token, index)) throw error("JSON 字面量无效");
            index += token.length();
            return value;
        }

        private void whitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++;
        }

        private boolean take(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw error("需要字符 " + expected);
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + "，位置 " + index);
        }
    }
}

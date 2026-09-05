package com.froglike6.continuitybridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MiniJson {
    private final String text;
    private int offset;

    private MiniJson(String text) { this.text = text; }

    static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        Object value = parser.value();
        parser.space();
        if (parser.offset != text.length()) throw new IllegalArgumentException("trailing_json");
        return value;
    }

    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return quote((String) value);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Object item : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
                if (!first) out.append(',');
                first = false;
                out.append(quote((String) entry.getKey())).append(':').append(encode(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                out.append(encode(item));
            }
            return out.append(']').toString();
        }
        throw new IllegalArgumentException("unsupported_json_value");
    }

    private Object value() {
        space();
        if (offset >= text.length()) throw new IllegalArgumentException("truncated_json");
        char c = text.charAt(offset);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c == 't' && literal("true")) return Boolean.TRUE;
        if (c == 'f' && literal("false")) return Boolean.FALSE;
        if (c == 'n' && literal("null")) return null;
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> result = new LinkedHashMap<>();
        offset++;
        space();
        if (take('}')) return result;
        while (true) {
            space();
            if (offset >= text.length() || text.charAt(offset) != '"') throw new IllegalArgumentException("object_key");
            String key = string();
            if (result.containsKey(key)) throw new IllegalArgumentException("duplicate_key");
            space();
            require(':');
            result.put(key, value());
            space();
            if (take('}')) return result;
            require(',');
        }
    }

    private List<Object> array() {
        List<Object> result = new ArrayList<>();
        offset++;
        space();
        if (take(']')) return result;
        while (true) {
            result.add(value());
            space();
            if (take(']')) return result;
            require(',');
        }
    }

    private String string() {
        require('"');
        StringBuilder out = new StringBuilder();
        while (offset < text.length()) {
            char c = text.charAt(offset++);
            if (c == '"') return out.toString();
            if (c == '\\') {
                if (offset >= text.length()) throw new IllegalArgumentException("truncated_escape");
                char escaped = text.charAt(offset++);
                if (escaped == 'u') {
                    if (offset + 4 > text.length()) throw new IllegalArgumentException("truncated_unicode");
                    out.append((char) Integer.parseInt(text.substring(offset, offset + 4), 16));
                    offset += 4;
                } else {
                    String from = "\"\\/bfnrt";
                    String to = "\"\\/\b\f\n\r\t";
                    int index = from.indexOf(escaped);
                    if (index < 0) throw new IllegalArgumentException("invalid_escape");
                    out.append(to.charAt(index));
                }
            } else {
                if (c < 0x20) throw new IllegalArgumentException("control_character");
                out.append(c);
            }
        }
        throw new IllegalArgumentException("truncated_string");
    }

    private Number number() {
        int start = offset;
        if (take('-') && offset >= text.length()) throw new IllegalArgumentException("number");
        while (offset < text.length() && Character.isDigit(text.charAt(offset))) offset++;
        if (start == offset) throw new IllegalArgumentException("json_value");
        String value = text.substring(start, offset);
        try { return Long.valueOf(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("number", error); }
    }

    private boolean literal(String literal) {
        if (!text.startsWith(literal, offset)) return false;
        offset += literal.length();
        return true;
    }

    private void space() { while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++; }
    private boolean take(char expected) { if (offset < text.length() && text.charAt(offset) == expected) { offset++; return true; } return false; }
    private void require(char expected) { if (!take(expected)) throw new IllegalArgumentException("expected_" + expected); }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}

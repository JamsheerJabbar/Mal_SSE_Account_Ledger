package com.mal.ledger.io;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer so the event-stream files stay hand-editable and the
 * production code stays dependency free. Supports objects, arrays, strings,
 * numbers, booleans and null, plus {@code //} and {@code /* *}{@code /} comments so
 * stream files can be annotated.
 */
public final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- parsing

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonException("Trailing content at offset " + p.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("Expected a JSON object at the document root");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                    while (pos < src.length() && src.charAt(pos) != '\n') pos++;
                } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                    pos += 2;
                    while (pos + 1 < src.length() && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) pos++;
                    pos = Math.min(pos + 2, src.length());
                } else {
                    return;
                }
            }
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) throw new JsonException("Unexpected end of input");
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw new JsonException("Expected ',' or '}' at offset " + (pos - 1));
                skipWhitespace();
                if (peek() == '}') { pos++; return map; } // tolerate trailing comma
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw new JsonException("Expected ',' or ']' at offset " + (pos - 1));
                skipWhitespace();
                if (peek() == ']') { pos++; return list; } // tolerate trailing comma
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonException("Unterminated string");
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new JsonException("Bad escape \\" + esc);
                }
            }
        }

        Boolean readBoolean() {
            if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonException("Bad literal at offset " + pos);
        }

        Object readNull() {
            if (src.startsWith("null", pos)) { pos += 4; return null; }
            throw new JsonException("Bad literal at offset " + pos);
        }

        BigDecimal readNumber() {
            int start = pos;
            while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) pos++;
            if (start == pos) throw new JsonException("Unexpected character '" + src.charAt(pos) + "' at offset " + pos);
            return new BigDecimal(src.substring(start, pos));
        }

        char peek() {
            skipWhitespace();
            if (atEnd()) throw new JsonException("Unexpected end of input");
            return src.charAt(pos);
        }

        char next() {
            if (atEnd()) throw new JsonException("Unexpected end of input");
            return src.charAt(pos++);
        }

        void expect(char expected) {
            skipWhitespace();
            char c = next();
            if (c != expected) {
                throw new JsonException("Expected '" + expected + "' but found '" + c + "' at offset " + (pos - 1));
            }
        }
    }

    // ---------------------------------------------------------------- writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, indent);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, indent);
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean || value instanceof BigDecimal
                || value instanceof Integer || value instanceof Long) {
            sb.append(value);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, int indent) {
        if (map.isEmpty()) { sb.append("{}"); return; }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            pad(sb, indent + 1);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            writeValue(sb, e.getValue(), indent + 1);
            if (++i < map.size()) sb.append(',');
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) { sb.append("[]"); return; }
        boolean scalarsOnly = list.stream().noneMatch(v -> v instanceof Map || v instanceof List);
        if (scalarsOnly) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                writeValue(sb, list.get(i), indent);
            }
            sb.append(']');
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            pad(sb, indent + 1);
            writeValue(sb, list.get(i), indent + 1);
            if (i < list.size() - 1) sb.append(',');
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    // ------------------------------------------------------------- accessors

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        if (v == null) return Map.of();
        if (!(v instanceof Map)) throw new JsonException("'" + key + "' is not an object");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objList(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        if (v == null) return List.of();
        if (!(v instanceof List)) throw new JsonException("'" + key + "' is not an array");
        return (List<Map<String, Object>>) v;
    }

    public static String str(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> parent, String key, String fallback) {
        String v = str(parent, key);
        return v == null ? fallback : v;
    }

    public static BigDecimal dec(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(String.valueOf(v));
    }

    public static BigDecimal dec(Map<String, Object> parent, String key, BigDecimal fallback) {
        BigDecimal v = dec(parent, key);
        return v == null ? fallback : v;
    }

    public static Integer intVal(Map<String, Object> parent, String key) {
        BigDecimal v = dec(parent, key);
        return v == null ? null : v.intValueExact();
    }

    public static int intVal(Map<String, Object> parent, String key, int fallback) {
        Integer v = intVal(parent, key);
        return v == null ? fallback : v;
    }

    public static boolean boolVal(Map<String, Object> parent, String key, boolean fallback) {
        Object v = parent.get(key);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }
}

package com.hronline.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

/**
 * 宽松 JSON 格式化器。
 *
 * <p>请求调试场景里用户经常会手写 JSON，最常见的问题是对象字段之间漏写逗号、
 * 使用单引号或留下尾逗号。这里先做无损的词法修复，再交给 Gson 生成标准缩进 JSON；
 * 无法安全判断的内容仍然会抛出 Gson 原始异常，不会静默改变用户数据。</p>
 */
public final class LenientJsonFormatter {

    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private LenientJsonFormatter() {}

    /** 格式化严格或常见宽松 JSON。 */
    public static String format(String raw) {
        if (raw == null || raw.isBlank()) return raw == null ? "" : raw;
        String normalized = normalize(raw);
        return PRETTY_GSON.toJson(JsonParser.parseString(normalized));
    }

    /** 仅返回可交给 Gson 解析的修复后文本，供回显等场景复用。 */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String source = raw.replace("\uFEFF", "").trim();
        if (source.isEmpty()) return source;

        StringBuilder out = new StringBuilder(source.length() + 16);
        boolean inDouble = false;
        boolean inSingle = false;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (lineComment) {
                if (c == '\n' || c == '\r') {
                    lineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                } else if (c == '\n' || c == '\r') {
                    out.append(c);
                }
                continue;
            }
            if (!inDouble && !inSingle && c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (!inDouble && !inSingle && c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }

            if (inDouble) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inDouble = false;
                continue;
            }
            if (inSingle) {
                // 把单引号字符串转成 JSON 双引号字符串，并保留内部双引号。
                if (escaped) {
                    if (c == '\'' || c == '\\') out.append(c);
                    else out.append('\\').append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    out.append('"');
                    inSingle = false;
                } else {
                    if (c == '"') out.append('\\');
                    out.append(c);
                }
                continue;
            }

            if (c == '"') {
                inDouble = true;
                out.append(c);
            } else if (c == '\'') {
                inSingle = true;
                out.append('"');
            } else {
                out.append(c);
            }
        }

        // 常见 JS/JSON5 风格的无引号字段名：{ collectionId: 1 }。
        String quotedKeys = quoteUnquotedKeys(out.toString());
        // 在两个完整值之间补上漏写的逗号：100\n"next":、}\n"next":、"a"\n"b" 等。
        String withCommas = insertMissingCommas(quotedKeys);
        // 删除对象/数组结束前的尾逗号。
        return removeTrailingCommas(withCommas).trim();
    }

    private static String quoteUnquotedKeys(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < text.length()) {
                    char k = text.charAt(end);
                    if (Character.isLetterOrDigit(k) || k == '_' || k == '-' || k == '.') end++;
                    else break;
                }
                int colon = end;
                while (colon < text.length() && Character.isWhitespace(text.charAt(colon))) colon++;
                if (colon < text.length() && text.charAt(colon) == ':') {
                    out.append('"').append(text, i, end).append('"');
                    i = end;
                    continue;
                }
                out.append(text, i, end);
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String insertMissingCommas(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                i++;
                continue;
            }
            if (c == '"') {
                int end = i + 1;
                boolean esc = false;
                for (; end < text.length(); end++) {
                    char q = text.charAt(end);
                    if (esc) esc = false;
                    else if (q == '\\') esc = true;
                    else if (q == '"') { end++; break; }
                }
                out.append(text, i, end);
                i = end;
                i = appendCommaIfNeeded(text, i, out);
                continue;
            }
            if (c == '}' || c == ']') {
                out.append(c);
                i++;
                i = appendCommaIfNeeded(text, i, out);
                continue;
            }
            if (c == '-' || Character.isDigit(c) || c == 't' || c == 'f' || c == 'n') {
                int end = i + 1;
                while (end < text.length()) {
                    char token = text.charAt(end);
                    if (Character.isLetterOrDigit(token) || token == '.' || token == '+' || token == '-') end++;
                    else break;
                }
                out.append(text, i, end);
                i = appendCommaIfNeeded(text, end, out);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int appendCommaIfNeeded(String text, int index, StringBuilder out) {
        int p = index;
        while (p < text.length() && Character.isWhitespace(text.charAt(p))) p++;
        if (p >= text.length()) return p;
        char next = text.charAt(p);
        if (next == ',' || next == '}' || next == ']' || next == ':') return index;
        // 下一个 token 以 key/值开头，说明上一个值后漏了逗号；保留原空白并在其前插入。
        if (next == '"' || next == '\'' || next == '{' || next == '['
                || next == '-' || Character.isDigit(next) || next == 't' || next == 'f' || next == 'n') {
            out.append(',');
        }
        return index;
    }

    private static String removeTrailingCommas(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == ',') {
                int p = i + 1;
                while (p < text.length() && Character.isWhitespace(text.charAt(p))) p++;
                if (p < text.length() && (text.charAt(p) == '}' || text.charAt(p) == ']')) {
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}

package com.hronline.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * cURL 命令生成与解析工具
 *
 * 支持:
 * - 将当前请求配置导出为 cURL 命令
 * - 从 cURL 命令字符串解析请求配置
 */
public class CurlUtil {

    /**
     * 生成 cURL 命令
     */
    public static String generateCurl(String method, String url, Map<String, String> headers,
                                       String body, String contentType) {
        StringBuilder sb = new StringBuilder();
        sb.append("curl -X ").append(method).append(" \\\n");
        sb.append("  '").append(url).append("'");

        // Headers
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    sb.append(" \\\n  -H '").append(entry.getKey()).append(": ")
                      .append(entry.getValue().replace("'", "'\\''")).append("'");
                }
            }
        }

        // Body
        if (body != null && !body.isEmpty()) {
            String escapedBody = body.replace("'", "'\\''");
            if (body.contains("\n")) {
                sb.append(" \\\n  --data-raw '").append(escapedBody).append("'");
            } else {
                sb.append(" \\\n  -d '").append(escapedBody).append("'");
            }
        }

        return sb.toString();
    }

    /**
     * 从 cURL 命令解析请求配置
     * 返回 Map 包含: method, url, headers, body
     */
    public static Map<String, Object> parseCurl(String curlCommand) {
        Map<String, Object> result = new LinkedHashMap<>();
        String cmd = curlCommand.trim();

        // 去除开头的 curl
        if (cmd.toLowerCase().startsWith("curl")) {
            cmd = cmd.substring(4).trim();
        }

        String method = "GET";
        String url = "";
        Map<String, String> headers = new LinkedHashMap<>();
        String body = null;

        // Tokenize (simple state machine for quoted strings)
        List<String> tokens = tokenize(cmd);

        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);

            switch (token) {
                case "-X", "--request" -> {
                    if (i + 1 < tokens.size()) {
                        method = tokens.get(++i).toUpperCase();
                    }
                }
                case "-H", "--header" -> {
                    if (i + 1 < tokens.size()) {
                        String headerStr = tokens.get(++i);
                        int colonIdx = headerStr.indexOf(':');
                        if (colonIdx > 0) {
                            String name = headerStr.substring(0, colonIdx).trim();
                            String value = headerStr.substring(colonIdx + 1).trim();
                            headers.put(name, value);
                        }
                    }
                }
                case "-d", "--data", "--data-raw", "--data-binary" -> {
                    if (i + 1 < tokens.size()) {
                        body = tokens.get(++i);
                        if ("GET".equals(method)) method = "POST";
                    }
                }
                case "--data-urlencode" -> {
                    if (i + 1 < tokens.size()) {
                        String data = tokens.get(++i);
                        // URL encode and append
                        if (body == null) body = "";
                        if (!body.isEmpty()) body += "&";
                        int eq = data.indexOf('=');
                        if (eq > 0) {
                            body += URLEncoder.encode(data.substring(0, eq), StandardCharsets.UTF_8)
                                    + "=" + URLEncoder.encode(data.substring(eq + 1), StandardCharsets.UTF_8);
                        } else {
                            body += URLEncoder.encode(data, StandardCharsets.UTF_8);
                        }
                        if ("GET".equals(method)) method = "POST";
                    }
                }
                case "-F", "--form" -> {
                    // multipart form data (not fully supported, note in result)
                    if (i + 1 < tokens.size()) {
                        i++;
                        headers.put("Content-Type", "multipart/form-data");
                    }
                }
                default -> {
                    // URL or unknown flag
                    if (token.startsWith("-")) {
                        // Skip unknown flags and their values if needed
                        if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("-")) {
                            i++;
                        }
                    } else if (token.startsWith("'") || token.startsWith("\"") ||
                               token.startsWith("http") || token.startsWith("/")) {
                        // Probably URL
                        url = token;
                    }
                }
            }
            i++;
        }

        // Clean URL from quotes
        url = stripQuotes(url);

        // If body present and no explicit Content-Type, set urlencoded
        if (body != null && !headers.containsKey("Content-Type")) {
            headers.put("Content-Type", "application/x-www-form-urlencoded");
        }

        result.put("method", method);
        result.put("url", url);
        result.put("headers", headers);
        result.put("body", body);
        return result;
    }

    private static List<String> tokenize(String cmd) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;

        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\' && quote != '\'') {
                escaped = true;
                continue;
            }

            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                    // Include quoted content as token
                    tokens.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == '\'' || c == '"') {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                quote = c;
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            // Line continuation
            if (c == '\\' && i + 1 < cmd.length() && cmd.charAt(i + 1) == '\n') {
                i++; // skip newline
                continue;
            }

            current.append(c);
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}

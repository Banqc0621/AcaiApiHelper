package com.hronline.model;

/**
 * 参数位置枚举 - 标识HTTP请求中参数的传递位置
 *
 * 在REST API中，参数可以出现在不同的位置，解析和序列化方式各异：
 * - PATH:   URL路径中的占位符参数，如 /users/{id}
 * - QUERY:  URL查询字符串参数，如 ?page=1&size=10
 * - BODY:   请求体中的参数（通常为JSON格式的@RequestBody）
 * - HEADER: HTTP请求头中的参数，如 Authorization: Bearer xxx
 * - COOKIE: Cookie中的参数
 * - FORM:   表单参数（multipart/form-data 或 application/x-www-form-urlencoded）
 */
public enum ParameterLocation {
    PATH,
    QUERY,
    BODY,
    HEADER,
    COOKIE,
    FORM
}

package com.ban.acai.model

/**
 * 环境配置（原始Kotlin版本）
 */
data class Environment(
    var name: String = "开发环境",
    var baseUrl: String = "http://localhost:8080",
    var headersJson: String = "{}",
    var variablesJson: String = "{}"
)

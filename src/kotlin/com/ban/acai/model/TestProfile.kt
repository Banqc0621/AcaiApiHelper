package com.ban.acai.model

import java.util.*

/**
 * 测试配置档（原始Kotlin版本）
 */
data class TestProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "默认配置",
    var baseUrl: String = "http://localhost:8080",
    var headersJson: String = "{}",
    var variablesJson: String = "{}",
    var createdAt: Long = System.currentTimeMillis()
)

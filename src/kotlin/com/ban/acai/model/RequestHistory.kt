package com.ban.acai.model

import java.util.*

/**
 * 请求历史记录（原始Kotlin版本）
 */
data class RequestHistory(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "",
    val method: String = "GET",
    val timestamp: Long = System.currentTimeMillis(),
    val statusCode: Int = 0,
    val durationMs: Long = 0
)

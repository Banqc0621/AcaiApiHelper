package com.ban.acai.model

import java.util.*

/**
 * 单个接口测试结果（原始Kotlin版本）
 */
data class TestResult(
    val id: String = UUID.randomUUID().toString(),
    val apiDefinition: ApiDefinition? = null,
    var status: TestStatus = TestStatus.PENDING,
    var statusCode: Int = 0,
    var durationMs: Long = 0,
    var responseBody: String = "",
    var errorMessage: String = ""
)

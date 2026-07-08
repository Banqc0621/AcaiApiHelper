package com.ban.acai.model

/**
 * 响应断言配置（原始Kotlin版本）
 */
data class ResponseAssertion(
    val name: String = "",
    val type: AssertionType = AssertionType.STATUS_CODE,
    val expected: String = "200",
    val enabled: Boolean = true
) {
    enum class AssertionType {
        STATUS_CODE, BODY_CONTAINS, JSON_PATH, HEADER_VALUE, RESPONSE_TIME
    }
}

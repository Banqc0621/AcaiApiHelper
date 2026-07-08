package com.ban.acai.model

import java.util.*

/**
 * API接口定义（原始Kotlin版本）
 */
data class ApiDefinition(
    val id: String = UUID.randomUUID().toString(),
    var httpMethod: String = "GET",
    var url: String = "",
    var name: String = "",
    var description: String = "",
    var controllerName: String = "",
    var sourceFilePath: String = "",
    var sourceLineNumber: Int = 0,
    var consumes: String = "application/json",
    var produces: String = "application/json",
    var deprecated: Boolean = false,
    var source: String = "AUTO",
    var parameters: MutableList<ApiParameter> = mutableListOf(),
    var responseBodyType: String = "void",
    var scanTimestamp: Long = System.currentTimeMillis()
) {
    val isAutoDetected: Boolean get() = source == "AUTO"
    fun displayLabel(): String = "[$httpMethod] $url - $name"
    fun uniqueKey(): String = "$httpMethod::$url"
}

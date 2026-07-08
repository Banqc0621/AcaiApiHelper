package com.ban.acai.model

import java.util.*

/**
 * API参数定义（原始Kotlin版本）
 */
data class ApiParameter(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var type: String = "String",
    var location: ParameterLocation = ParameterLocation.QUERY,
    var required: Boolean = false,
    var defaultValue: String = "",
    var description: String = "",
    var example: String = "",
    var children: MutableList<ApiParameter> = mutableListOf()
) {
    val isComplexType: Boolean get() = children.isNotEmpty()
}

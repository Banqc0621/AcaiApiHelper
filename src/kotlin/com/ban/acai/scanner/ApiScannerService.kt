package com.ban.acai.scanner

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

/**
 * API扫描服务（原始Kotlin版本）
 * 基于IntelliJ PSI解析Java源码中的REST API定义
 */
class ApiScannerService(private val project: Project) {
    private var cachedApis: List<Any> = emptyList()
    private val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)

    fun scanProjectApisAsync() {
        // 原始Kotlin实现 - 异步扫描项目API
    }

    fun getCachedApis(): List<Any> = cachedApis
}

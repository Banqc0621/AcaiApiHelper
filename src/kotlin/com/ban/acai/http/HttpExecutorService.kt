package com.ban.acai.http

import com.intellij.openapi.project.Project
import java.net.http.HttpClient
import java.time.Duration

/**
 * HTTP请求执行服务（原始Kotlin版本）
 * 支持同步/异步请求、Cookie管理、批量测试
 */
class HttpExecutorService(private val project: Project) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun executeBatchTest(apis: List<Any>, profile: Any): Any {
        // 原始Kotlin实现 - 批量接口测试
        throw UnsupportedOperationException("Java版本完整实现")
    }

    companion object {
        fun getInstance(project: Project): HttpExecutorService {
            return project.getService(HttpExecutorService::class.java)
        }
    }
}

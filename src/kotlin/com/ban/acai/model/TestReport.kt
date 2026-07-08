package com.ban.acai.model

import java.util.*

/**
 * 测试报告（原始Kotlin版本）
 */
data class TestReport(
    val id: String = UUID.randomUUID().toString(),
    val profileName: String = "",
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long = 0,
    val results: MutableList<TestResult> = mutableListOf()
) {
    val totalDuration: Long get() = endTime - startTime
    val passedCount: Int get() = results.count { it.status == TestStatus.PASSED }
    val passRate: Double get() = if (results.isEmpty()) 0.0 else passedCount * 100.0 / results.size
    val isAllPassed: Boolean get() = results.all { it.status == TestStatus.PASSED }
}

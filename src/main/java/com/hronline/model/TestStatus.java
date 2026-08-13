package com.hronline.model;

/**
 * API测试结果枚举 - 标识单次接口测试的执行状态
 */
public enum TestStatus {
    /** 测试通过（HTTP状态码2xx） */
    PASSED,
    /** 测试失败（HTTP状态码非2xx） */
    FAILED,
    /** 测试出错（网络异常、超时等） */
    ERROR,
    /** 未执行 */
    PENDING,
    /** 正在执行中 */
    RUNNING,
    /** 已跳过（依赖接口失败等原因） */
    SKIPPED
}

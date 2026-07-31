package com.ban.acai.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 测试报告数据模型 - 汇总一批接口的测试结果
 */
public class TestReport {

    /** 测试名称 */
    private String testName = "";
    /** 所有接口的测试结果列表 */
    private List<TestResult> results = new ArrayList<>();
    /** 测试开始时间 */
    private long startTime = 0;
    /** 测试结束时间 */
    private long endTime = 0;

    public TestReport() {}

    public TestReport(String testName, long startTime) {
        this.testName = testName != null ? testName : "";
        this.startTime = startTime;
    }

    // ================================================================
    // Getters & Setters
    // ================================================================

    public String getTestName() { return testName; }
    public void setTestName(String testName) { this.testName = testName; }

    public List<TestResult> getResults() { return results; }
    public void setResults(List<TestResult> results) { this.results = results; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }

    // ================================================================
    // 计算属性
    // ================================================================

    /** 通过的测试数 */
    public int getPassedCount() {
        return (int) results.stream().filter(TestResult::isPassed).count();
    }

    /** 失败的测试数 */
    public int getFailedCount() {
        return (int) results.stream().filter(TestResult::isFailed).count();
    }

    /** 出错的测试数 */
    public int getErrorCount() {
        return (int) results.stream().filter(r -> r.getStatus() == TestStatus.ERROR).count();
    }

    /** 跳过的测试数（依赖接口失败导致跳过） */
    public int getSkippedCount() {
        return (int) results.stream().filter(r -> r.getStatus() == TestStatus.SKIPPED).count();
    }

    /** 总耗时（毫秒） */
    public long getTotalDuration() {
        return endTime - startTime;
    }

    /** 通过率百分比 */
    public double getPassRate() {
        if (results.isEmpty()) return 0.0;
        return ((double) getPassedCount() / results.size()) * 100;
    }

    /** 是否全部通过（用于Git预提交判定） */
    public boolean isAllPassed() {
        return results.stream().allMatch(TestResult::isPassed);
    }

    /**
     * 生成文本格式的测试报告摘要
     */
    public String generateSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════\n");
        sb.append("测试报告: ").append(testName).append("\n");
        sb.append("═══════════════════════════════════════\n");
        sb.append("总计: ").append(results.size()).append(" 个接口\n");
        sb.append("通过: ").append(getPassedCount())
          .append(" | 失败: ").append(getFailedCount())
          .append(" | 异常: ").append(getErrorCount());
        if (getSkippedCount() > 0) {
            sb.append(" | 跳过: ").append(getSkippedCount());
        }
        sb.append("\n");
        sb.append(String.format("通过率: %.1f%%\n", getPassRate()));
        sb.append("总耗时: ").append(getTotalDuration()).append("ms\n");
        sb.append("───────────────────────────────────────\n");
        for (TestResult result : results) {
            sb.append(result.summary()).append("  ").append(result.getApiDefinition().displayLabel()).append("\n");
        }
        sb.append("═══════════════════════════════════════\n");
        return sb.toString();
    }

    // ================================================================
    // equals / hashCode / toString
    // ================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestReport that = (TestReport) o;
        return startTime == that.startTime &&
                endTime == that.endTime &&
                Objects.equals(testName, that.testName) &&
                Objects.equals(results, that.results);
    }

    @Override
    public int hashCode() {
        return Objects.hash(testName, results, startTime, endTime);
    }

    @Override
    public String toString() {
        return "TestReport{" +
                "testName='" + testName + '\'' +
                ", results=" + results.size() +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                '}';
    }
}

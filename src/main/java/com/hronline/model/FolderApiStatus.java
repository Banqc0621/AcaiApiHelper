package com.hronline.model;

/**
 * 收藏文件夹内单个接口的测试状态（持久化）。
 *
 * <p>用于「批量测试」后记录通过/失败，并在收藏树中以红色标记失败接口。
 * 用户可手动取消警示（{@link #manuallyCleared}），取消后即使历史测试失败也不再标红。</p>
 */
public class FolderApiStatus {

    /** 是否通过 */
    private boolean passed;
    /** 用户是否手动取消了警示（取消后即使 passed=false 也不标红） */
    private boolean manuallyCleared;
    /** HTTP 状态码（-1 表示未测试/请求异常） */
    private int statusCode = -1;
    /** 失败原因/通过说明 */
    private String message = "";
    /** 最近一次测试时间戳 */
    private long testedAt = 0L;

    public FolderApiStatus() {}

    public static FolderApiStatus untested() {
        FolderApiStatus s = new FolderApiStatus();
        s.passed = false;
        s.manuallyCleared = true; // 未测试不标红
        s.statusCode = -1;
        s.message = "未测试";
        return s;
    }

    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }

    public boolean isManuallyCleared() { return manuallyCleared; }
    public void setManuallyCleared(boolean manuallyCleared) { this.manuallyCleared = manuallyCleared; }

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public long getTestedAt() { return testedAt; }
    public void setTestedAt(long testedAt) { this.testedAt = testedAt; }

    /** 是否应当标红：测试过、未通过、且未被手动取消警示 */
    public boolean shouldHighlightRed() {
        return !manuallyCleared && !passed && testedAt > 0;
    }
}
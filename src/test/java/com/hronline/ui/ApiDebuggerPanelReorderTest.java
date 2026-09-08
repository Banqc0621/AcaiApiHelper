package com.hronline.ui;

import com.hronline.model.RequestHistory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 一伦优化 #93 单测：覆盖 ApiDebuggerPanel.reorderBatchByKeyOrder。
 * <p>用户截图复现：4 条历史时间都是 09-08 15:16:00（同一秒），用 timestamp 比较
 * 永远相等，必须用收藏夹 apiKey 顺序作为终极 source of truth。本测试模拟
 * addToHistory 之后 requestHistory 的实际乱序状态（add(0, h) 倒序），断言
 * reorder 后严格按 apiKeyOrder 排好。</p>
 */
class ApiDebuggerPanelReorderTest {

    private static RequestHistory h(String apiKey, long ts) {
        RequestHistory r = new RequestHistory();
        r.setApiKey(apiKey);
        r.setTimestamp(ts);
        r.setUrl("http://localhost/" + apiKey);
        r.setMethod("GET");
        return r;
    }

    private static List<String> keys(List<RequestHistory> list) {
        List<String> out = new ArrayList<>();
        for (RequestHistory h : list) out.add(h.getApiKey());
        return out;
    }

    /**
     * 主用例：用户复现的 4 条同一秒（timestamp 完全相等），模拟 add(0) 倒序入栈后的乱序，
     * 断言 reorder 后严格按收藏夹顺序 A→B→C→D 排好。
     */
    @Test
    void reorderByApiKeyOrder_handlesIdenticalTimestamps() {
        // 收藏夹顺序
        List<String> order = Arrays.asList("A", "B", "C", "D");
        // addToHistory 4 次 add(0, h) 后的实际乱序：reverse 入参顺序
        List<RequestHistory> batch = new ArrayList<>(Arrays.asList(
                h("D", 1736387760000L),
                h("C", 1736387760000L),
                h("B", 1736387760000L),
                h("A", 1736387760000L)
        ));

        List<RequestHistory> reordered = ApiDebuggerPanel.reorderBatchByKeyOrder(batch, order);

        assertEquals(Arrays.asList("A", "B", "C", "D"), keys(reordered),
                "同一秒多次请求时仍应按 apiKeyOrder 严格排序");
    }

    /** reverse 入参顺序 + 倒序 apiKeyOrder，验证仍能正确还原。 */
    @Test
    void reorderByApiKeyOrder_reverseOrder() {
        List<String> order = Arrays.asList("D", "C", "B", "A");
        List<RequestHistory> batch = new ArrayList<>(Arrays.asList(
                h("A", 1L),
                h("B", 2L),
                h("C", 3L),
                h("D", 4L)
        ));

        List<RequestHistory> reordered = ApiDebuggerPanel.reorderBatchByKeyOrder(batch, order);

        assertEquals(Arrays.asList("D", "C", "B", "A"), keys(reordered));
    }

    /** apiKeyOrder 为 null 时退化为按 timestamp 升序。 */
    @Test
    void reorderByApiKeyOrder_nullApiKeyOrder_fallsBackToTimestamp() {
        List<RequestHistory> batch = new ArrayList<>(Arrays.asList(
                h("X", 30L),
                h("Y", 10L),
                h("Z", 20L)
        ));

        List<RequestHistory> reordered = ApiDebuggerPanel.reorderBatchByKeyOrder(batch, null);

        assertEquals(Arrays.asList("Y", "Z", "X"), keys(reordered),
                "apiKeyOrder=null 时按 timestamp 升序");
    }

    /** apiKeyOrder 为空列表时同样退化为按 timestamp 升序。 */
    @Test
    void reorderByApiKeyOrder_emptyApiKeyOrder_fallsBackToTimestamp() {
        List<RequestHistory> batch = new ArrayList<>(Arrays.asList(
                h("X", 30L),
                h("Y", 10L),
                h("Z", 20L)
        ));

        List<RequestHistory> reordered = ApiDebuggerPanel.reorderBatchByKeyOrder(batch, new ArrayList<>());

        assertEquals(Arrays.asList("Y", "Z", "X"), keys(reordered));
    }

    /** batch 里出现 apiKeyOrder 没有的条目（兜底：按 timestamp 升序追加到尾部）。 */
    @Test
    void reorderByApiKeyOrder_extraEntryInBatch_fallsBackToTimestamp() {
        List<String> order = Arrays.asList("A", "B");
        List<RequestHistory> batch = new ArrayList<>(Arrays.asList(
                h("Z", 30L),
                h("B", 20L),
                h("A", 10L),
                h("Y", 25L)
        ));

        List<RequestHistory> reordered = ApiDebuggerPanel.reorderBatchByKeyOrder(batch, order);

        // A 和 B 按 order 排前两位；Y/Z（不在 order 里）按 timestamp 升序追加
        assertEquals(Arrays.asList("A", "B", "Y", "Z"), keys(reordered),
                "不在 apiKeyOrder 里的条目按 timestamp 升序追加");
    }

    /** 空 batch 不崩。 */
    @Test
    void reorderByApiKeyOrder_emptyBatch() {
        List<RequestHistory> reordered = ApiDebuggerPanel.reorderBatchByKeyOrder(
                new ArrayList<>(), Arrays.asList("A", "B"));
        assertEquals(0, reordered.size());
    }
}

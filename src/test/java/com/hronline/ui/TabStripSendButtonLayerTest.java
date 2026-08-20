package com.hronline.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一伦优化 v35：「发起请求」覆盖层布局回归测试。
 * <p>验证按钮钉死容器内同一行最右端、不占布局宽度、不居中；content 占满整行。</p>
 */
class TabStripSendButtonLayerTest {

    private static ApiDebuggerPanel.TabStripSendButtonLayer buildLayer(JButton[] btnHolder) {
        JTabbedPane pane = new JTabbedPane();
        pane.addTab("参数", new JPanel());
        pane.addTab("请求头", new JPanel());
        pane.addTab("请求体", new JPanel());
        pane.addTab("历史", new JPanel());
        pane.addTab("AI 助手", new JPanel());
        JScrollPane scroll = new JScrollPane(pane);
        JButton btn = new JButton("发起请求");
        btnHolder[0] = btn;
        return new ApiDebuggerPanel.TabStripSendButtonLayer(scroll, btn);
    }

    @Test
    void buttonPinnedToRightEdgeWithoutTakingLayoutWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int width : new int[]{800, 400, 240}) {
                JButton[] btnHolder = new JButton[1];
                ApiDebuggerPanel.TabStripSendButtonLayer layer = buildLayer(btnHolder);
                JButton btn = btnHolder[0];
                layer.setSize(width, 400);
                layer.doLayout();

                // content 占满整行（按钮不挤占布局宽度）
                Component content = null;
                for (Component c : layer.getComponents()) {
                    if (c != btn) content = c;
                }
                assertEquals(0, content.getX());
                assertEquals(width, content.getWidth(), "content 应占满整行宽度");
                assertEquals(400, content.getHeight());

                // 按钮右缘与内容列可见右缘对齐（8px 内缩，与内容卡右内边距一致）
                Dimension ps = btn.getPreferredSize();
                assertTrue(ps.width > 0, "按钮 preferredSize 应有效");
                assertEquals(width - ps.width - 8, btn.getX(), "按钮右缘应对齐内容列右缘");
                assertEquals(ps.width, btn.getWidth());

                // 按钮不居中（右对齐，明显偏离水平中心）
                int centerX = (width - ps.width) / 2;
                assertTrue(btn.getX() > centerX, "按钮不应居中，应靠右");
            }
        });
    }
}

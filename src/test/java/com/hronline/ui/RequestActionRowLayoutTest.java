package com.hronline.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #89：请求行主操作布局回归。
 * 发送按钮必须属于请求行右侧固定区域，不能因窗口宽度变化漂移到左侧或居中。
 */
class RequestActionRowLayoutTest {

    @Test
    void sendButtonStaysAtRequestRowRightEdgeAcrossWidths() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel prefix = new JPanel();
            prefix.setPreferredSize(new Dimension(120, 28));
            JTextField url = new JTextField();
            url.setPreferredSize(new Dimension(460, 28));
            JButton action = new JButton("发起请求");
            action.setPreferredSize(new Dimension(112, 28));

            JPanel row = ApiDebuggerPanel.createRequestActionRow(prefix, url, action);
            for (int width : new int[]{800, 400, 240}) {
                row.setSize(width, 30);
                row.doLayout();

                assertEquals(width - action.getWidth(), action.getX(),
                        "发送按钮必须贴合请求行右缘，窗口宽度=" + width);
                assertEquals(0, prefix.getX());
                assertTrue(url.getWidth() >= 0, "URL 区域应可收缩而不产生负宽度");
                assertTrue(action.getY() >= 0 && action.getY() + action.getHeight() <= row.getHeight(),
                        "发送按钮必须垂直位于请求行内");
            }
        });
    }
}

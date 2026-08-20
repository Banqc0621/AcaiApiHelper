package com.hronline.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiStyleTest {

    @Test
    void parseAccentFallsBackToBlue() {
        assertEquals(UiStyle.AccentColor.BLUE, UiStyle.parseAccent(null));
        assertEquals(UiStyle.AccentColor.BLUE, UiStyle.parseAccent("unknown"));
        assertEquals(UiStyle.AccentColor.GREEN, UiStyle.parseAccent(" green "));
        assertEquals(UiStyle.AccentColor.HIGH_CONTRAST, UiStyle.parseAccent("high_contrast"));
    }

    @Test
    void standardButtonsReceiveDisableAndRecoveryFeedback() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton button = UiStyle.button("导出", null, null);
            Color originalBackground = button.getBackground();
            button.setEnabled(false);
            assertFalse(button.isEnabled());
            button.setEnabled(true);
            assertEquals(originalBackground.getRGB(), button.getBackground().getRGB());
        });
    }

    @Test
    void interactionFeedbackRestoresAccentColorsAfterDisableCycle() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton button = new JButton("发送");
            UiStyle.applyAccent(button, UiStyle.AccentColor.GREEN);
            Color originalBackground = button.getBackground();
            Color originalForeground = button.getForeground();

            UiStyle.attachInteractionFeedback(button);
            UiStyle.attachInteractionFeedback(button);
            button.setEnabled(false);
            assertFalse(button.isEnabled());

            button.setEnabled(true);
            assertEquals(originalBackground.getRGB(), button.getBackground().getRGB());
            assertEquals(originalForeground.getRGB(), button.getForeground().getRGB());
        });
    }

    @Test
    void primaryButtonKeepsWhiteTextOnAccentInAnyTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton button = UiStyle.primaryButton("发起请求", null, null, UiStyle.AccentColor.BLUE);
            // 一伦优化 #47：文字色必须是 onAccent（白字），浅色主题下不再被 LaF 前景覆盖
            Color fg = button.getForeground();
            assertTrue(fg.getRed() > 200 && fg.getGreen() > 200 && fg.getBlue() > 200,
                    "主按钮文字应为白色系，实际: " + fg);
            // 背景为 accent 实色（JBColor 明暗自适应）
            assertEquals(UiStyle.AccentColor.BLUE.color().getRGB(), button.getBackground().getRGB());
            // 自绘圆角实底 border 已挂上：背景不依赖 LaF 渲染，浅色主题也有 accent 底
            assertNotNull(button.getBorder());
            assertFalse(button.getBorder().isBorderOpaque());
        });
    }

    @Test
    void interactionFeedbackHoverUsesAccentBaseNotLafDefault() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton button = UiStyle.primaryButton("发送", null, null, UiStyle.AccentColor.GREEN);
            button.setVisible(true);
            Color accent = UiStyle.AccentColor.GREEN.color();
            for (java.awt.event.MouseListener l : button.getMouseListeners()) {
                l.mouseEntered(new java.awt.event.MouseEvent(button,
                        java.awt.event.MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(),
                        0, 1, 1, 0, false));
            }
            Color hovered = button.getBackground();
            // hover 底色必须从 accent 计算（约 accent*0.92），而不是 LaF 默认背景
            int expectR = Math.min(255, (int) (accent.getRed() * 0.92f));
            assertEquals(expectR, hovered.getRed(), "hover 底色应从 accent 计算");
        });
    }

    @Test
    void loadingStateRestoresTextAndCursor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton button = new JButton("发送");
            Cursor originalCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
            button.setCursor(originalCursor);

            UiStyle.startLoading(button, "发送中");
            assertFalse(button.isEnabled());
            assertTrue(button.getText().startsWith("发送中"));
            assertEquals(Cursor.WAIT_CURSOR, button.getCursor().getType());

            UiStyle.endLoading(button, "发送");
            assertTrue(button.isEnabled());
            assertEquals("发送", button.getText());
            assertEquals(originalCursor.getType(), button.getCursor().getType());
        });
    }
}

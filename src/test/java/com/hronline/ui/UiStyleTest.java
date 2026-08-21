package com.hronline.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            // 一伦优化 #47/#48：文字色必须是 onAccent（白字），浅色主题下不再被 LaF 前景覆盖
            Color fg = button.getForeground();
            assertTrue(fg.getRed() > 200 && fg.getGreen() > 200 && fg.getBlue() > 200,
                    "主按钮文字应为白色系，实际: " + fg);
            // 背景为 accent 实色（JBColor 明暗自适应）
            assertEquals(UiStyle.AccentColor.BLUE.color().getRGB(), button.getBackground().getRGB());
            // #48：LaF 背景/文字 client property 已设置 —— DarculaButtonUI 渲染时
            // 只看这两个 property（组件色被忽略），明暗主题都是「accent 底 + 白字」
            Object lafBg = button.getClientProperty(UiStyle.LAF_BUTTON_BG);
            assertTrue(lafBg instanceof Color, "应设置 LaF 背景 client property");
            assertEquals(UiStyle.AccentColor.BLUE.color().getRGB(), ((Color) lafBg).getRGB());
            Object lafFg = button.getClientProperty(UiStyle.LAF_BUTTON_FG);
            assertTrue(lafFg instanceof Color, "应设置 LaF 文字色 client property");
            Color lafFgColor = (Color) lafFg;
            assertTrue(lafFgColor.getRed() > 200 && lafFgColor.getGreen() > 200 && lafFgColor.getBlue() > 200,
                    "LaF 文字色应为白色系，实际: " + lafFgColor);
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
            // #48：hover 必须同步写 LaF client property，否则主按钮 hover 反馈不生效
            Object lafBg = button.getClientProperty(UiStyle.LAF_BUTTON_BG);
            assertTrue(lafBg instanceof Color, "hover 后 LaF 背景 client property 应同步");
            assertEquals(expectR, ((Color) lafBg).getRed(), "LaF 背景 client property 应跟随 hover 色");
            // mouseExited 后回到 base
            for (java.awt.event.MouseListener l : button.getMouseListeners()) {
                l.mouseExited(new java.awt.event.MouseEvent(button,
                        java.awt.event.MouseEvent.MOUSE_EXITED, System.currentTimeMillis(),
                        0, 1, 1, 0, false));
            }
            assertEquals(accent.getRGB(), button.getBackground().getRGB(), "移出后应回到 accent base");
            assertEquals(accent.getRGB(), ((Color) button.getClientProperty(UiStyle.LAF_BUTTON_BG)).getRGB(),
                    "移出后 LaF 背景 client property 应回到 accent base");
        });
    }

    @Test
    void disabledAccentButtonRestoresLafClientPropsOnEnable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton button = UiStyle.primaryButton("保存", null, null, UiStyle.AccentColor.BLUE);
            Color accent = UiStyle.AccentColor.BLUE.color();

            button.setEnabled(false);
            // #48：禁用时 LaF client property 被暂存清掉 —— 让 LaF 走标准灰底渲染，
            // 避免「accent 底 + 灰字」不可读
            assertNull(button.getClientProperty(UiStyle.LAF_BUTTON_BG),
                    "禁用态不应残留 accent 背景 client property");
            assertNull(button.getClientProperty(UiStyle.LAF_BUTTON_FG),
                    "禁用态不应残留 accent 文字 client property");

            button.setEnabled(true);
            // 恢复启用后 accent 底白字 client property 完整还原
            Object lafBg = button.getClientProperty(UiStyle.LAF_BUTTON_BG);
            assertTrue(lafBg instanceof Color, "恢复启用后应还原 LaF 背景 client property");
            assertEquals(accent.getRGB(), ((Color) lafBg).getRGB(), "还原的背景应是 accent 色");
            Object lafFg = button.getClientProperty(UiStyle.LAF_BUTTON_FG);
            assertTrue(lafFg instanceof Color, "恢复启用后应还原 LaF 文字 client property");
            Color fg = (Color) lafFg;
            assertTrue(fg.getRed() > 200 && fg.getGreen() > 200 && fg.getBlue() > 200,
                    "还原的文字色应是白色系，实际: " + fg);
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

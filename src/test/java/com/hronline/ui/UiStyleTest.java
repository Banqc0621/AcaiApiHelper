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
            Color originalForeground = button.getForeground();

            UiStyle.attachInteractionFeedback(button);
            UiStyle.attachInteractionFeedback(button);
            button.setEnabled(false);
            assertFalse(button.isEnabled());

            button.setEnabled(true);
            assertEquals(originalForeground.getRGB(), button.getForeground().getRGB());
        });
    }

    @Test
    void primaryButtonHasNoFillBackgroundAndUsesAccentText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton button = UiStyle.primaryButton("发起请求", null, null, UiStyle.AccentColor.BLUE);
            // #49：无背景填充色 —— 不再设置 accent 底色，也不写 LaF 背景 client property
            assertNull(button.getClientProperty(UiStyle.LAF_BUTTON_BG),
                    "主按钮不应有 LaF 背景填充 client property");
            // 文字色 = accent 色（JBColor 明暗自适应，两种主题都清晰）
            assertEquals(UiStyle.AccentColor.BLUE.color().getRGB(), button.getForeground().getRGB());
            Object lafFg = button.getClientProperty(UiStyle.LAF_BUTTON_FG);
            assertTrue(lafFg instanceof Color, "应设置 LaF 文字色 client property");
            assertEquals(UiStyle.AccentColor.BLUE.color().getRGB(), ((Color) lafFg).getRGB());
        });
    }

    @Test
    void applyAccentDoesNotTouchBackgroundAndClearsLegacyProps() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton button = new JButton("发送");
            Color before = button.getBackground();
            // 模拟旧版本残留的填充属性
            button.putClientProperty(UiStyle.LAF_BUTTON_BG, Color.RED);
            button.setOpaque(true);

            UiStyle.applyAccent(button, UiStyle.AccentColor.GREEN);
            assertEquals(before.getRGB(), button.getBackground().getRGB(),
                    "applyAccent 不应修改按钮背景色");
            assertNull(button.getClientProperty(UiStyle.LAF_BUTTON_BG),
                    "应清掉旧版本残留的 LaF 背景 client property");
            assertEquals(UiStyle.AccentColor.GREEN.color().getRGB(), button.getForeground().getRGB());
        });
    }

    @Test
    void disabledAccentButtonRestoresAccentTextOnEnable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JButton button = UiStyle.primaryButton("保存", null, null, UiStyle.AccentColor.BLUE);
            Color accent = UiStyle.AccentColor.BLUE.color();

            button.setEnabled(false);
            // #49：禁用时 accent 文字 property 被暂存清掉，LaF 走标准 disabledText
            assertNull(button.getClientProperty(UiStyle.LAF_BUTTON_FG),
                    "禁用态不应残留 accent 文字 client property");

            button.setEnabled(true);
            // 恢复启用后 accent 文字色完整还原（组件色 + client property）
            assertEquals(accent.getRGB(), button.getForeground().getRGB());
            Object lafFg = button.getClientProperty(UiStyle.LAF_BUTTON_FG);
            assertTrue(lafFg instanceof Color, "恢复启用后应还原 LaF 文字色 client property");
            assertEquals(accent.getRGB(), ((Color) lafFg).getRGB());
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

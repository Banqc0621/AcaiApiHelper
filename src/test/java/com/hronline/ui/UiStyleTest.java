package com.hronline.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiStyleTest {

    @Test
    void parseAccentFallsBackToBlue() {
        assertEquals(UiStyle.AccentColor.BLUE, UiStyle.parseAccent(null));
        assertEquals(UiStyle.AccentColor.BLUE, UiStyle.parseAccent("unknown"));
        assertEquals(UiStyle.AccentColor.GREEN, UiStyle.parseAccent(" green "));
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

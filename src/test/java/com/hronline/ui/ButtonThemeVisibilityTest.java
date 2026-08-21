package com.hronline.ui;

import com.intellij.ide.ui.laf.IntelliJLaf;
import com.intellij.icons.AllIcons;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * 一伦优化 #48：明暗主题按钮可见性渲染诊断/回归。
 * <p>在真实 LaF（Darcula / IntelliJ Light）下渲染主按钮到位图，
 * 检查中心区域像素确实是「accent 底 + 白字」，而不是透明/LaF 覆盖色。</p>
 */
class ButtonThemeVisibilityTest {

    /** 采样按钮中心区域，返回与给定颜色接近的像素占比 */
    private static double ratioCloseTo(BufferedImage img, Color target, int tolerance) {
        int w = img.getWidth(), h = img.getHeight();
        int cx = w / 2, cy = h / 2;
        int count = 0, hit = 0;
        for (int x = cx - 20; x < cx + 20; x++) {
            for (int y = cy - 8; y < cy + 8; y++) {
                int rgb = img.getRGB(x, y);
                int a = (rgb >> 24) & 0xFF;
                if (a < 16) continue; // 跳过透明像素
                count++;
                Color c = new Color(rgb, true);
                if (Math.abs(c.getRed() - target.getRed()) <= tolerance
                        && Math.abs(c.getGreen() - target.getGreen()) <= tolerance
                        && Math.abs(c.getBlue() - target.getBlue()) <= tolerance) {
                    hit++;
                }
            }
        }
        return count == 0 ? 0 : (double) hit / count;
    }

    private static BufferedImage render(JButton btn) {
        btn.setSize(btn.getPreferredSize());
        BufferedImage img = new BufferedImage(btn.getWidth(), btn.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        btn.paint(g);
        g.dispose();
        return img;
    }

    private static void checkInLaf(String lafClass, String label) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(lafClass);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            JButton btn = UiStyle.primaryButton("发起请求", AllIcons.Actions.Execute, null,
                    UiStyle.AccentColor.BLUE);
            BufferedImage img = render(btn);

            Color accentDark = new Color(0x42, 0xA5, 0xF5);
            Color accentLight = new Color(0x15, 0x65, 0xC0);
            double ratioDark = ratioCloseTo(img, accentDark, 28);
            double ratioLight = ratioCloseTo(img, accentLight, 28);
            boolean accentPainted = ratioDark > 0.5 || ratioLight > 0.5;

            System.out.println("[" + label + "] center accent coverage = dark:"
                    + String.format("%.2f", ratioDark) + " light:" + String.format("%.2f", ratioLight)
                    + " => painted=" + accentPainted);
            if (!accentPainted) {
                throw new AssertionError(label + " 主题下主按钮中心没有 accent 底色（图标文字可能看不见或没有背景）");
            }
        });
    }

    @Test
    void primaryButtonVisibleInDarcula() throws Exception {
        checkInLaf("com.intellij.ide.ui.laf.darcula.DarculaLaf", "Darcula");
    }

    @Test
    void primaryButtonVisibleInLight() throws Exception {
        checkInLaf(IntelliJLaf.class.getName(), "Light");
    }
}

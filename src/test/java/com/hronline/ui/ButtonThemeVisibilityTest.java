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
 * 一伦优化 #48/#49：明暗主题按钮可见性渲染诊断/回归。
 * <p>在真实 LaF（Darcula / IntelliJ Light）下渲染主按钮到位图：</p>
 * <ul>
 *   <li>#49：按钮<b>无背景填充色</b>——中心区域不应出现 accent 底色</li>
 *   <li>文字/图标以 accent 文字色渲染，明暗主题下像素可见（与面板背景不同色）</li>
 * </ul>
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

    /** 采样按钮中心区域中与底色差异明显的像素占比（即"有内容被画出来"的比例） */
    private static double ratioNonBackground(BufferedImage img, Color bg, int tolerance) {
        int w = img.getWidth(), h = img.getHeight();
        int cx = w / 2, cy = h / 2;
        int count = 0, hit = 0;
        for (int x = cx - 20; x < cx + 20; x++) {
            for (int y = cy - 8; y < cy + 8; y++) {
                int rgb = img.getRGB(x, y);
                int a = (rgb >> 24) & 0xFF;
                if (a < 16) continue;
                count++;
                Color c = new Color(rgb, true);
                if (Math.abs(c.getRed() - bg.getRed()) > tolerance
                        || Math.abs(c.getGreen() - bg.getGreen()) > tolerance
                        || Math.abs(c.getBlue() - bg.getBlue()) > tolerance) {
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

    private static void checkInLaf(String lafClass, String label, boolean dark) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(lafClass);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            JButton btn = UiStyle.primaryButton("发起请求", AllIcons.Actions.Execute, null,
                    UiStyle.AccentColor.BLUE);
            BufferedImage img = render(btn);

            // #49：无背景填充 —— 中心不应有 accent 底色
            Color accentDark = new Color(0x42, 0xA5, 0xF5);
            Color accentLight = new Color(0x15, 0x65, 0xC0);
            double ratioDark = ratioCloseTo(img, accentDark, 28);
            double ratioLight = ratioCloseTo(img, accentLight, 28);
            boolean filled = ratioDark > 0.5 || ratioLight > 0.5;
            if (filled) {
                throw new AssertionError(label + " 主题下主按钮仍有 accent 背景填充（用户要求去掉填充色）：dark="
                        + ratioDark + " light=" + ratioLight);
            }

            // 文字/图标可见：中心区域存在与 LaF 按钮底色差异明显的像素
            Color lafBg = dark ? new Color(0x4C, 0x50, 0x52) : new Color(0xE8, 0xE8, 0xE8);
            double content = ratioNonBackground(img, lafBg, 36);
            System.out.println("[" + label + "] accent fill ratio = dark:"
                    + String.format("%.2f", ratioDark) + " light:" + String.format("%.2f", ratioLight)
                    + ", content ratio = " + String.format("%.2f", content));
            if (content < 0.05) {
                throw new AssertionError(label + " 主题下主按钮中心几乎没有任何文字/图标像素");
            }
        });
    }

    @Test
    void primaryButtonVisibleInDarcula() throws Exception {
        checkInLaf("com.intellij.ide.ui.laf.darcula.DarculaLaf", "Darcula", true);
    }

    @Test
    void primaryButtonVisibleInLight() throws Exception {
        checkInLaf(IntelliJLaf.class.getName(), "Light", false);
    }
}

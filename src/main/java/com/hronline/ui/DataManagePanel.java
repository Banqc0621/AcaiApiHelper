package com.hronline.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 一伦优化 #3：把"数据管理"卡片列表抽成可复用 JPanel，
 * 让 {@link EnvAndDataManageDialog} 合并对话框在"数据" Tab 中直接嵌入本面板。
 * <p>原 {@code DataManagerDialog} 是 {@link ApiDebuggerPanel} 的私有内部类，
 * 它直接调用 {@code exportTestConfigAction} 等外部方法，与面板耦合。
 * 这里改为通过 {@code Consumer<Runnable>} 回调传递点击操作，
 * 让面板本身不知道上层是谁，方便在合并对话框中复用。</p>
 */
final class DataManagePanel {

    /** 一条操作卡片的数据：图标 + 标题 + 描述 + 点击回调 */
    static final class Action {
        final Icon icon;
        final String title;
        final String desc;
        final Runnable onClick;

        Action(Icon icon, String title, String desc, Runnable onClick) {
            this.icon = icon;
            this.title = title;
            this.desc = desc;
            this.onClick = onClick;
        }
    }

    /** 私有构造：纯静态工具类 */
    private DataManagePanel() {}

    /**
     * 构建数据管理卡片列表 JPanel。
     * <ul>
     *   <li>{@code sections}：每个 section 包含 (title, subtitle, actions)，渲染为一个分区</li>
     *   <li>{@code onAction}：每张卡片被点击时，把要执行的操作透传给上层（用于合并对话框的"先关弹窗、再 invokeLater 执行"）</li>
     * </ul>
     */
    static JPanel build(List<Section> sections, Consumer<Runnable> onAction) {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setPreferredSize(new Dimension(540, 320));
        panel.setBorder(JBUI.Borders.empty(4, 2, 2, 2));

        // 卡片列表容器
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(JBUI.Borders.empty(2, 2));

        for (Section sec : sections) {
            list.add(sectionHeader(sec.title, sec.subtitle));
            for (Action a : sec.actions) {
                list.add(actionCard(a.icon, a.title, a.desc, a.onClick, onAction));
            }
            list.add(Box.createVerticalStrut(8));
        }
        list.add(Box.createVerticalGlue());

        JBScrollPane scroll = new JBScrollPane(list);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /** 分区定义：标题 + 副标题 + 操作列表 */
    static final class Section {
        final String title;
        final String subtitle;
        final List<Action> actions;
        Section(String title, String subtitle, List<Action> actions) {
            this.title = title;
            this.subtitle = subtitle;
            this.actions = Objects.requireNonNull(actions);
        }
    }

    private static JComponent sectionHeader(String title, String subtitle) {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(8, 6, 4, 6));

        JPanel left = new JPanel(new BorderLayout(0, 0));
        left.setOpaque(false);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, UiStyle.FONT_SECTION));
        titleLabel.setForeground(JBColor.foreground());
        left.add(titleLabel, BorderLayout.CENTER);
        header.add(left, BorderLayout.WEST);

        JLabel subLabel = new JLabel(subtitle);
        UiStyle.hint(subLabel);
        header.add(subLabel, BorderLayout.EAST);

        return header;
    }

    /**
     * 构造一张可点击的操作卡片。
     * <p>点击时先通过 {@code onAction} 把操作回调透传给上层，再通知上层关闭对话框。
     * 上层通常会把"关闭对话框 + 执行操作"放在 {@code invokeLater} 中处理，
     * 避免 Windows 上模态弹窗未完全释放时原生文件对话框无法弹出。</p>
     */
    private static JComponent actionCard(Icon icon, String title, String desc,
                                         Runnable onClick, Consumer<Runnable> onAction) {
        JPanel card = new JPanel(new BorderLayout(10, 0));
        card.setOpaque(false);
        card.setBorder(JBUI.Borders.empty(8, 10));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel iconLabel = new JLabel(icon);
        iconLabel.setVerticalAlignment(SwingConstants.CENTER);
        card.add(iconLabel, BorderLayout.WEST);

        JPanel text = new JPanel(new BorderLayout(0, 3));
        text.setOpaque(false);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.PLAIN, UiStyle.FONT_SECTION));
        JLabel descLabel = new JLabel(desc);
        UiStyle.hint(descLabel);
        text.add(titleLabel, BorderLayout.CENTER);
        text.add(descLabel, BorderLayout.SOUTH);
        card.add(text, BorderLayout.CENTER);

        JLabel arrow = new JLabel(AllIcons.Icons.Ide.NextStep);
        arrow.setVerticalAlignment(SwingConstants.CENTER);
        arrow.setForeground(JBColor.GRAY);
        card.add(arrow, BorderLayout.EAST);

        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                // 通过回调把待执行操作透传给上层（EnvAndDataManageDialog / DataManagerDialog）
                onAction.accept(onClick);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                card.setOpaque(true);
                card.setBackground(JBColor.namedColor("Table.stripeColor", new Color(245, 246, 247)));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                card.setOpaque(false);
            }
        });

        return card;
    }
}

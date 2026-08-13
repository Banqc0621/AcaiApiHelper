package com.hronline.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 简单文本差异对比工具 (基于行的LCS)
 */
public class SimpleDiff {

    public enum DiffType { EQUAL, ADDED, DELETED, MODIFIED }

    public static class DiffLine {
        public final DiffType type;
        public final String text;
        public final int leftLine;
        public final int rightLine;

        public DiffLine(DiffType type, String text, int leftLine, int rightLine) {
            this.type = type;
            this.text = text;
            this.leftLine = leftLine;
            this.rightLine = rightLine;
        }
    }

    /**
     * 对比两段文本，返回差异行列表
     */
    public static List<DiffLine> diff(String left, String right) {
        String[] leftLines = left != null ? left.split("\n", -1) : new String[0];
        String[] rightLines = right != null ? right.split("\n", -1) : new String[0];
        return diffLines(leftLines, rightLines);
    }

    private static List<DiffLine> diffLines(String[] left, String[] right) {
        int m = left.length;
        int n = right.length;

        // LCS DP table
        int[][] dp = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (left[i].equals(right[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<DiffLine> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < m && j < n) {
            if (left[i].equals(right[j])) {
                result.add(new DiffLine(DiffType.EQUAL, left[i], i + 1, j + 1));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                result.add(new DiffLine(DiffType.DELETED, left[i], i + 1, -1));
                i++;
            } else {
                result.add(new DiffLine(DiffType.ADDED, right[j], -1, j + 1));
                j++;
            }
        }
        while (i < m) {
            result.add(new DiffLine(DiffType.DELETED, left[i], i + 1, -1));
            i++;
        }
        while (j < n) {
            result.add(new DiffLine(DiffType.ADDED, right[j], -1, j + 1));
            j++;
        }

        return result;
    }

    /**
     * 生成HTML格式的diff
     */
    public static String toHtml(List<DiffLine> diffs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-family:monospace;font-size:12px;white-space:pre-wrap;'>\n");
        for (DiffLine d : diffs) {
            String color;
            String prefix;
            switch (d.type) {
                case ADDED -> { color = "#E8F5E9"; prefix = "+ "; }
                case DELETED -> { color = "#FFEBEE"; prefix = "- "; }
                default -> { color = "transparent"; prefix = "  "; }
            }
            sb.append("<div style='background:").append(color)
              .append(";padding:1px 4px;'>");
            String lineNum = d.leftLine > 0 ? String.format("%4d", d.leftLine) : "    ";
            lineNum += d.rightLine > 0 ? String.format("%4d", d.rightLine) : "    ";
            sb.append("<span style='color:#999;'>").append(lineNum).append("</span> ");
            sb.append(prefix).append(escapeHtml(d.text)).append("</div>\n");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

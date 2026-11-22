package com.mcargus.util;

import java.util.regex.Pattern;

/**
 * 版本号比较：按"数字段/非数字段"分词后逐段比较。
 * 兼容 "2.17"、"1.21.1-21.1.77"、"47.2.0" 等格式；缺失段补 0。
 */
public final class VersionUtil {
    private static final Pattern SPLIT = Pattern.compile("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private VersionUtil() {
    }

    public static int compare(String a, String b) {
        if (a == null || a.trim().isEmpty()) {
            return (b == null || b.trim().isEmpty()) ? 0 : -1;
        }
        if (b == null || b.trim().isEmpty()) {
            return 1;
        }
        String[] ta = SPLIT.split(a.trim());
        String[] tb = SPLIT.split(b.trim());
        int n = Math.max(ta.length, tb.length);
        for (int i = 0; i < n; i++) {
            boolean hasX = i < ta.length;
            boolean hasY = i < tb.length;
            if (hasX && hasY) {
                String x = ta[i];
                String y = tb[i];
                boolean dx = DIGITS.matcher(x).matches();
                boolean dy = DIGITS.matcher(y).matches();
                int c;
                if (dx && dy) {
                    c = Long.compare(Long.parseLong(x), Long.parseLong(y));
                } else if (dx) {
                    c = 1; // 数字段视为大于非数字段（正式版 > 预发布标识）
                } else if (dy) {
                    c = -1;
                } else {
                    c = x.compareToIgnoreCase(y);
                }
                if (c != 0) {
                    return c;
                }
            } else {
                // 一侧耗尽：按更长一侧的剩余 token 判定
                //  - 存在非 0 数字段 → 更长一侧更新（"2.17" < "2.17.1"）
                //  - 存在字母段（预发布标识）→ 更短一侧（正式版）更新（"2.18" > "2.18-beta"）
                //  - 只剩分隔符和 0 → 视为相等（"1.20" == "1.20.0"）
                String[] rest = hasX ? ta : tb; // 更长一侧的剩余 token
                boolean longerWins = false;
                boolean shorterWins = false;
                for (int j = i; j < rest.length; j++) {
                    String t = rest[j];
                    if (DIGITS.matcher(t).matches()) {
                        if (Long.parseLong(t) != 0) {
                            longerWins = true;
                            break;
                        }
                    } else if (t.matches(".*[a-zA-Z].*")) {
                        shorterWins = true;
                    }
                }
                if (longerWins) {
                    return hasX ? 1 : -1;
                }
                if (shorterWins) {
                    return hasX ? -1 : 1;
                }
            }
        }
        return 0;
    }

    /** 是否为形如 "2.18" / "2.28.1" 的版本号。 */
    public static boolean isValidVersion(String s) {
        return s != null && s.matches("\\d+(\\.\\d+)+");
    }
}

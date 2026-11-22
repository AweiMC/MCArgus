package com.mcargus.i18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * 轻量级本地化（i18n）：内置简体中文（zh_cn，默认）与英语（en_us）。
 *
 * 语言由配置文件 mcargus.properties 的 locale 键选择（zh_cn / en_us），缺省为 zh_cn；
 * 必须在任何输出（含 --help 与用法错误提示）之前由 Main.initLocale 完成初始化。
 *
 * 消息资源位于 classpath:i18n/&lt;locale&gt;.properties，UTF-8 编码；
 * 查找顺序：当前语言 → 中文兜底 → 键名本身（便于发现漏译）。
 *
 * 占位符采用 {0} {1} 形式，按出现顺序以 String.valueOf 替换。
 * 不使用 MessageFormat：它会把英文中的撇号（'）当作语法字符要求转义，易错且难维护。
 */
public final class Messages {
    /** 默认语言，同时作为其他语言缺键时的兜底。 */
    public static final String DEFAULT_LOCALE = "zh_cn";
    /** 英语语言代码。 */
    public static final String LOCALE_EN = "en_us";

    private static volatile boolean loaded;
    private static volatile String locale = DEFAULT_LOCALE;
    private static volatile Properties current;
    private static volatile Properties fallback;

    private Messages() {
    }

    /** 设置界面语言：接受 zh_cn / en_us / zh-CN / en-US / zh / en 等写法，未知值回退中文。 */
    public static synchronized void setLocale(String loc) {
        locale = normalize(loc);
        fallback = loadBundle(DEFAULT_LOCALE);
        current = DEFAULT_LOCALE.equals(locale) ? fallback : loadBundle(locale);
        loaded = true;
    }

    /** 当前语言代码（zh_cn / en_us）。 */
    public static String locale() {
        ensureLoaded();
        return locale;
    }

    /** 是否为英语界面。 */
    public static boolean isEnglish() {
        return LOCALE_EN.equals(locale());
    }

    /** 取本地化消息；args 非空时按 {0} {1} 占位替换。 */
    public static String get(String key, Object... args) {
        ensureLoaded();
        return format(lookup(key), args);
    }

    private static void ensureLoaded() {
        if (!loaded) {
            setLocale(DEFAULT_LOCALE);
        }
    }

    private static String normalize(String loc) {
        if (loc == null) {
            return DEFAULT_LOCALE;
        }
        String s = loc.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (s.isEmpty() || s.startsWith("zh")) {
            return DEFAULT_LOCALE;
        }
        if (s.startsWith("en")) {
            return LOCALE_EN;
        }
        return DEFAULT_LOCALE;
    }

    private static Properties loadBundle(String name) {
        Properties p = new Properties();
        InputStream in = Messages.class.getResourceAsStream("/i18n/" + name + ".properties");
        if (in != null) {
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (Exception ignored) {
                // 资源损坏：保持空表，查找时逐级兜底
            }
        }
        return p;
    }

    private static String lookup(String key) {
        Properties c = current;
        if (c != null) {
            String v = c.getProperty(key);
            if (v != null) {
                return v;
            }
        }
        Properties f = fallback;
        if (f != null) {
            String v = f.getProperty(key);
            if (v != null) {
                return v;
            }
        }
        return key;
    }

    /** 将 {0} {1} 占位替换为对应参数；无法解析或下标越界的占位原样保留。 */
    private static String format(String template, Object... args) {
        if (args == null || args.length == 0 || template.indexOf('{') < 0) {
            return template;
        }
        StringBuilder sb = new StringBuilder(template.length() + 24);
        int i = 0;
        while (i < template.length()) {
            char ch = template.charAt(i);
            if (ch == '{') {
                int end = template.indexOf('}', i + 1);
                if (end > i + 1) {
                    int idx = -1;
                    boolean digits = true;
                    for (int j = i + 1; j < end; j++) {
                        char d = template.charAt(j);
                        if (d < '0' || d > '9') {
                            digits = false;
                            break;
                        }
                        idx = (idx < 0 ? 0 : idx) * 10 + (d - '0');
                    }
                    if (digits && idx >= 0 && idx < args.length) {
                        sb.append(args[idx]);
                        i = end + 1;
                        continue;
                    }
                }
            }
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }
}

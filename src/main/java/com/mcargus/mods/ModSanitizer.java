package com.mcargus.mods;

import com.mcargus.i18n.Messages;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * mods 目录文件名清理：去除下载站/汉化组添加的前缀装饰，例如
 * "[JEI背包管理器[Forge]]jei.jar" → "jei.jar"、"【公告】[汉化]appleskin.jar" → "appleskin.jar"。
 *
 * 规则：
 *  - 仅处理 mods/ 顶层以 .jar 结尾的常规文件（MC 不会加载子目录里的 jar，不越权处理）；
 *  - "前缀" = 文件名开头的一组或多组成对括号（[] 【】 （） () 『』 「」，支持嵌套），及其后的空白/连接符；
 *  - 清理后与现有文件重名 → 跳过并警告（直接覆盖会把重复下载变成重复模组，导致加载崩溃）；
 *  - 重命名失败（文件被占用、权限不足等）→ 警告并继续，绝不阻断启动；
 *  - 不修改文件内容，只做同目录重命名。
 */
public final class ModSanitizer {

    public static final class Report {
        public final List<String> renamed = new ArrayList<String>();
        public final List<String> collisions = new ArrayList<String>();
        public final List<String> skipped = new ArrayList<String>();
        public final List<String> failures = new ArrayList<String>();
        public int scanned;

        public boolean isEmpty() {
            return renamed.isEmpty() && collisions.isEmpty() && skipped.isEmpty() && failures.isEmpty();
        }
    }

    private static final String OPEN = "[【（(『「";
    private static final String CLOSE = "]】）)』」";

    private ModSanitizer() {
    }

    /** 清理 mods 目录。apply=false 时只探测不实际重命名（供 --doctor 预览）。永不抛异常。 */
    public static Report clean(Path serverDir, boolean apply) {
        Report r = new Report();
        try {
            Path mods = serverDir.resolve("mods");
            if (!Files.isDirectory(mods)) {
                return r;
            }
            List<Path> jars = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
                for (Path p : ds) {
                    if (!isJarName(p.getFileName().toString())) {
                        continue;
                    }
                    // 只接受常规文件（目录、符号链接等直接跳过）
                    if (Files.readAttributes(p, BasicFileAttributes.class).isRegularFile()) {
                        jars.add(p);
                    }
                }
            }
            jars.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
            r.scanned = jars.size();
            for (Path src : jars) {
                handleOne(mods, src, apply, r);
            }
        } catch (Exception e) {
            r.failures.add(Messages.get("sanitize.exception", String.valueOf(e)));
        }
        return r;
    }

    private static void handleOne(Path modsDir, Path src, boolean apply, Report r) {
        String name = src.getFileName().toString();
        String base = name.substring(0, name.length() - 4); // 去掉 ".jar"（大小写已由调用方过滤）
        String cleaned = customTrim(stripDecorPrefix(base));
        if (cleaned.equals(base)) {
            return; // 文件名本身正常
        }
        if (cleaned.isEmpty() || cleaned.startsWith(".")) {
            r.skipped.add(Messages.get("sanitize.noValidName", name));
            return;
        }
        if (cleaned.contains("/") || cleaned.contains("\\") || cleaned.indexOf('\0') >= 0) {
            r.skipped.add(Messages.get("sanitize.illegalChars", name));
            return;
        }
        String targetName = cleaned + ".jar";
        Path target = modsDir.resolve(targetName);
        String desc = name + " → " + targetName;
        if (Files.exists(target)) {
            r.collisions.add(Messages.get("sanitize.collision", desc));
            return;
        }
        if (!apply) {
            r.renamed.add(Messages.get("sanitize.preview", desc));
            return;
        }
        try {
            Files.move(src, target);
            r.renamed.add(desc);
        } catch (IOException e) {
            r.failures.add(Messages.get("sanitize.renameFailed", desc, e.getMessage()));
        }
    }

    /**
     * 去掉文件名开头的一组或多组成对括号前缀（支持嵌套，如 "[JEI背包管理器[Forge]]"），
     * 以及紧跟其后的空白、'-'、'_'。不匹配任何前缀时原样返回。
     */
    public static String stripDecorPrefix(String base) {
        String s = base;
        boolean changed = true;
        while (changed && !s.isEmpty() && OPEN.indexOf(s.charAt(0)) >= 0) {
            changed = false;
            int depth = 0;
            int end = -1;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (OPEN.indexOf(ch) >= 0) {
                    depth++;
                } else if (CLOSE.indexOf(ch) >= 0) {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                    if (depth < 0) {
                        break; // 括号不配对，放弃
                    }
                }
            }
            if (end < 0) {
                break; // 没有配对的闭合括号
            }
            String rest = trimLeadingSeparators(s.substring(end + 1));
            if (!rest.isEmpty()) {
                s = rest;
                changed = true;
            }
        }
        return s;
    }

    /** 去除首尾空白（含全角空格）。 */
    public static String customTrim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /** 去掉前缀括号后的空白与连接符，等价于原来的 ^[\\s\\-_\\u3000]+ 正则，但不重复编译正则。 */
    private static String trimLeadingSeparators(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '-' && c != '_' && !isSpace(c)) {
                break;
            }
            i++;
        }
        return i == 0 ? s : s.substring(i);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n'
                || c == '\u000B' || c == '\f' || c == '\u3000';
    }

    private static boolean isJarName(String name) {
        return name.length() >= 4 && name.regionMatches(true, name.length() - 4, ".jar", 0, 4);
    }
}

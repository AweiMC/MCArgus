package com.mcargus.mods;

import com.mcargus.Config;
import com.mcargus.i18n.Messages;
import com.mcargus.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * mods 目录扫描调度（scanCompleted 状态机）。
 *
 * - scan.scanCompleted=false（首次启动 / 上次扫描失败 / 配置缺失或损坏恢复）→ 完整扫描+文件名修复，
 *   全部成功后置 true 并记录目录指纹；
 * - scan.scanCompleted=true → 快速校验：对 mods 顶层 .jar 的"文件名+大小"计算 SHA-256 指纹，
 *   与缓存一致则跳过完整扫描，否则重扫并修复；
 * - 扫描/修复出现失败项 → 不置 scanCompleted=true（下次启动重新完整扫描）。
 *
 * 指纹能力边界（务必了解）：指纹覆盖"文件名+大小"，比仅比较数量强——同名数量不变但发生
 * 重命名/替换大小时指纹也会变化；但它不读取 JAR 内容，"内容被原地替换且大小恰好相同"的
 * 极端情况检测不到。指纹只统计顶层 .jar，mods 里的其他文件变化不会触发重扫（清理器只管 .jar）。
 */
public final class ScanManager {

    public static final class Result {
        public final boolean ranFullScan;
        public final String reason;
        public final ModSanitizer.Report report; // 仅 ranFullScan=true 时非空

        public Result(boolean ranFullScan, String reason, ModSanitizer.Report report) {
            this.ranFullScan = ranFullScan;
            this.reason = reason;
            this.report = report;
        }
    }

    private ScanManager() {
    }

    /** 确保 mods 目录处于"已扫描/已修复"状态。永不抛异常（内部吞掉一切并如实记录）。 */
    public static Result ensureScanned(Path serverDir, Config cfg, boolean forceFullScan) {
        boolean completed = cfg.getBool(Config.K_SCAN_COMPLETED, false);
        String storedFp = cfg.get(Config.K_SCAN_FINGERPRINT, "");
        Path modsDir = serverDir.resolve("mods");

        boolean needFullScan;
        String why;
        if (forceFullScan) {
            needFullScan = true;
            why = Messages.get("scan.whyRescan");
        } else if (!completed) {
            needFullScan = true;
            why = Messages.get("scan.whyFirstRun");
        } else {
            String fp = fingerprint(modsDir);
            if (fp == null) {
                needFullScan = true;
                why = Messages.get("scan.whyNoDir");
            } else if (!fp.equals(storedFp)) {
                needFullScan = true;
                why = Messages.get("scan.whyFingerprintChanged");
            } else {
                needFullScan = false;
                why = Messages.get("scan.whyFastOk");
                Log.info(Messages.get("scan.fastOk"));
            }
        }

        if (!needFullScan) {
            return new Result(false, why, null);
        }

        Log.info(Messages.get("scan.startFull", why));
        ModSanitizer.Report r = ModSanitizer.clean(serverDir, true);
        logReport(r);

        boolean failed = !r.failures.isEmpty();
        if (failed) {
            Log.warn(Messages.get("scan.hasFailures"));
            cfg.set(Config.K_SCAN_COMPLETED, "false");
        } else {
            String fp = fingerprint(modsDir);
            cfg.set(Config.K_SCAN_COMPLETED, "true");
            cfg.set(Config.K_SCAN_FINGERPRINT, fp == null ? "" : fp);
            Log.info(Messages.get("scan.done", r.scanned,
                    r.renamed.isEmpty() ? Messages.get("scan.noFixNeeded")
                            : Messages.get("scan.fixedCount", r.renamed.size())));
        }
        cfg.saveIfDirty();
        return new Result(true, why, r);
    }

    private static void logReport(ModSanitizer.Report r) {
        for (String s : r.renamed) {
            Log.info(Messages.get("scan.renamed", s));
        }
        for (String s : r.collisions) {
            Log.warn(Messages.get("scan.collision", s));
        }
        for (String s : r.skipped) {
            Log.warn(Messages.get("scan.skipped", s));
        }
        for (String s : r.failures) {
            Log.warn(Messages.get("scan.failed", s));
        }
    }

    /**
     * mods 目录指纹：仅统计顶层 .jar 的"文件名:大小"，按名称排序后计算 SHA-256，
     * 取前 16 个十六进制字符。不读取任何 JAR 内容。目录不存在/不可读返回 null。
     */
    public static String fingerprint(Path modsDir) {
        try {
            if (!Files.isDirectory(modsDir)) {
                return null;
            }
            File[] files = modsDir.toFile().listFiles();
            if (files == null) {
                return null;
            }
            List<String> lines = new ArrayList<String>();
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    lines.add(f.getName() + ":" + f.length());
                }
            }
            Collections.sort(lines);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String s : lines) {
                md.update((s + "\n").getBytes(StandardCharsets.UTF_8));
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

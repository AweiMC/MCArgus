package com.mcargus;

import com.mcargus.discovery.Discovery;
import com.mcargus.discovery.LaunchTarget;
import com.mcargus.i18n.Messages;
import com.mcargus.launch.JvmLocator;
import com.mcargus.mods.ModSanitizer;
import com.mcargus.util.Exec;
import com.mcargus.util.Log;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 环境诊断报告：尽力输出状态，不因个别环节失败而中断；全部输出经由统一日志（含文件）。 */
public final class Doctor {
    private Doctor() {
    }

    public static void run(Path serverDir, Config cfg, Main.Cli cli) {
        sec(Messages.get("doctor.secBasic"));
        kv(Messages.get("doctor.version"), Main.VERSION);
        kv(Messages.get("doctor.serverDir"), serverDir.toString());
        kv(Messages.get("doctor.os"), System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " (" + System.getProperty("os.version") + ")");
        kv(Messages.get("doctor.selfJar"), String.valueOf(Main.selfJarPath()));
        kv(Messages.get("doctor.configFile"), cfg.getFile().toString()
                + (cfg.exists() ? Messages.get("doctor.cfgExists") : Messages.get("doctor.cfgMissing"))
                + (cfg.isRecovered() ? Messages.get("doctor.cfgRecovered") : ""));

        sec(Messages.get("doctor.secJava"));
        JvmLocator jvm = JvmLocator.resolve(cfg.get(Config.K_JAVA_PATH, ""), cli.javaPath);
        kv(Messages.get("doctor.javaResolved"), jvm.display());
        kv(Messages.get("doctor.javaCurrent"), System.getProperty("java.version") + " / " + System.getProperty("java.vendor")
                + " / " + System.getProperty("java.home"));
        String ver = Exec.run(8000, jvm.executable(), "-version");
        kv(Messages.get("doctor.javaTargetVersion"), ver == null ? Messages.get("doctor.javaCallFailed")
                : ver.replace("\n", " | "));

        sec(Messages.get("doctor.secGlibc"));
        if (Main.isWindows()) {
            kv(Messages.get("doctor.crt"), Messages.get("doctor.crtNotLinux", System.getProperty("os.name")));
        } else {
            String ldd = Exec.run(5000, "ldd", "--version");
            if (ldd != null && !ldd.trim().isEmpty()) {
                kv(Messages.get("doctor.hostGlibc"), ldd.split("\n")[0].trim());
            } else {
                kv(Messages.get("doctor.hostGlibc"), Messages.get("doctor.glibcUnknown"));
            }
            Log.info("  " + Messages.get("doctor.glibcNote1"));
            Log.info("  " + Messages.get("doctor.glibcNote2"));
        }

        sec(Messages.get("doctor.secTarget"));
        try {
            LaunchTarget t = Discovery.resolveTarget(serverDir, cfg, cli.rescan,
                    cli.argsFileOverride, Main.selfJarPath(), Main.isWindows());
            kv(Messages.get("doctor.targetWillUse"), t.describe());
        } catch (Exception e) {
            printIndented(String.valueOf(e.getMessage()));
        }

        sec(Messages.get("doctor.secMods"));
        kv("mods.sanitize", Boolean.toString(cfg.getBool(Config.K_MODS_SANITIZE, true)));
        kv("scan.scanCompleted", Boolean.toString(cfg.getBool(Config.K_SCAN_COMPLETED, false)));
        kv("scan.modsFingerprint", cfg.get(Config.K_SCAN_FINGERPRINT, Messages.get("doctor.noValue")));
        try {
            ModSanitizer.Report mr = ModSanitizer.clean(serverDir, false);
            if (mr.scanned == 0) {
                Log.info("  " + Messages.get("doctor.modsNoJar"));
            } else if (mr.isEmpty()) {
                Log.info("  " + Messages.get("doctor.modsAllOk", mr.scanned));
            } else {
                for (String s : mr.renamed) {
                    Log.info("  " + Messages.get("doctor.modsWillRename", s));
                }
                for (String s : mr.collisions) {
                    Log.info("  " + Messages.get("doctor.modsConflict", s));
                }
                for (String s : mr.skipped) {
                    Log.info("  " + Messages.get("doctor.modsSkipped", s));
                }
                for (String s : mr.failures) {
                    Log.info("  " + Messages.get("doctor.modsError", s));
                }
            }
        } catch (Exception e) {
            Log.info("  " + Messages.get("doctor.modsProbeFailed", String.valueOf(e)));
        }

        sec(Messages.get("doctor.secNotes"));
        Log.info("  " + Messages.get("doctor.noteDryRun"));
        Log.info("  " + Messages.get("doctor.noteRescan"));
    }

    private static void printIndented(String multiLine) {
        for (String line : multiLine.split("\n")) {
            Log.info("  " + line);
        }
    }

    private static void sec(String title) {
        Log.info("== " + title + " ==");
    }

    private static void kv(String k, String v) {
        Log.info(k + ": " + v);
    }
}

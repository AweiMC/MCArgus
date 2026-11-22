package com.mcargus;

import com.mcargus.i18n.Messages;
import com.mcargus.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * MCArgus 配置（默认 <serverDir>/mcargus/mcargus.properties）。
 * 同时承载用户设置（java.path / mods.* / install.* 等）与启动目标缓存（cache.*，自动维护）。
 * 保存失败不致命（如只读文件系统），只警告并继续运行。
 */
public final class Config {
    public static final String K_LOCALE = "locale";
    public static final String K_JAVA_PATH = "java.path";
    public static final String K_JVM_EXTRA = "jvm.extraArgs";
    public static final String K_JVM_FORWARD = "jvm.forwardParentArgs";
    public static final String K_JVM_USERFILE = "jvm.useUserJvmArgs";
    public static final String K_PROG_ARGS = "program.args";
    public static final String K_MODS_SANITIZE = "mods.sanitize";
    public static final String K_SCAN_COMPLETED = "scan.scanCompleted";
    public static final String K_SCAN_FINGERPRINT = "scan.modsFingerprint";
    public static final String K_INSTALL_ENABLED = "install.enabled";
    public static final String K_INSTALL_COMPLETED = "install.completed";
    public static final String K_INSTALL_NOTE = "install.note";
    public static final String K_ZIP_ENCODING = "install.zipEncoding";

    public static final String K_CACHE_ARGS = "cache.argsFile";
    public static final String K_CACHE_ARGS_MTIME = "cache.argsFileMtime";
    public static final String K_CACHE_ARGS_SIZE = "cache.argsFileSize";
    public static final String K_CACHE_LOADER = "cache.loaderType";
    public static final String K_CACHE_LVER = "cache.loaderVersion";
    public static final String K_CACHE_JAR = "cache.jarFile";
    public static final String K_CACHE_JARKIND = "cache.jarKind";

    private final Path file;
    private final Properties p = new Properties();
    private boolean dirty;
    private boolean recovered; // 载入时发现文件损坏并已恢复默认

    private Config(Path file) {
        this.file = file;
    }

    public static Config load(Path file) {
        Config c = new Config(file);
        if (Files.isRegularFile(file)) {
            Reader r = null;
            boolean corrupt = false;
            String reason = null;
            try {
                r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8);
                c.p.load(r);
            } catch (IOException | IllegalArgumentException e) {
                // 损坏（含非法 Unicode 转义等格式错误）：备份原文件、恢复默认，并让上层触发完整重扫
                corrupt = true;
                reason = e.getMessage();
            } finally {
                if (r != null) {
                    try {
                        r.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            if (corrupt) {
                // 必须先关闭文件句柄再做备份（Windows 上打开的句柄会阻止重命名）
                Log.warn(Messages.get("config.corruptRecovered", reason));
                c.recovered = true;
                c.p.clear();
                c.backupCorrupt(file);
            }
        }
        return c;
    }

    private void backupCorrupt(Path file) {
        try {
            Path dst = file.resolveSibling("mcargus.properties.corrupt-" + System.currentTimeMillis());
            Files.move(file, dst);
            Log.warn(Messages.get("config.corruptBackedUp", dst));
        } catch (Exception e) {
            Log.warn(Messages.get("config.corruptBackupFailed", e.getMessage()));
        }
    }

    /** 载入时是否发生过"损坏→恢复默认"。 */
    public boolean isRecovered() {
        return recovered;
    }

    public Path getFile() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    public String get(String key, String def) {
        String v = p.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? def : v;
    }

    public boolean getBool(String key, boolean def) {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        v = v.trim();
        return v.equalsIgnoreCase("true") || v.equals("1");
    }

    /** 置空字符串等同于删除该键。 */
    public void set(String key, String value) {
        String nv = value == null ? "" : value;
        String old = p.getProperty(key);
        String oldNorm = old == null ? "" : old;
        if (oldNorm.equals(nv)) {
            return;
        }
        if (old == null && nv.isEmpty()) {
            return;
        }
        if (nv.isEmpty()) {
            p.remove(key);
        } else {
            p.setProperty(key, nv);
        }
        dirty = true;
    }

    public void setBool(String key, boolean v) {
        set(key, Boolean.toString(v));
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public synchronized void save() {
        Writer w = null;
        try {
            if (file.getParent() != null) {
                file.getParent().toFile().mkdirs();
            }
            w = new OutputStreamWriter(Files.newOutputStream(file.toFile().toPath()), StandardCharsets.UTF_8);
            for (String line : Messages.get("config.header").split("\n", -1)) {
                w.write("# " + line + "\n");
            }
            p.store(w, "MCArgus configuration");
            w.flush();
            dirty = false;
        } catch (IOException e) {
            Log.warn(Messages.get("config.saveFailed", e.getMessage()));
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}

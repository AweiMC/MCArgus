package com.mcargus.launch;

import com.mcargus.i18n.Messages;
import com.mcargus.util.Exec;
import com.mcargus.util.Log;
import com.mcargus.util.VersionUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YSM（Yes Steve Model）在 glibc 2.17 系统上的兼容层。
 *
 * <p>背景：YSM 的原生库 {@code libysm-core.so} 需要
 * {@code __cxa_thread_atexit_impl@GLIBC_2.18}，而 CentOS 7 的 glibc 2.17 没有这个版本节点。
 * YSM 的原生库带完整性自校验，直接改文件字节会在加载时触发
 * {@code "YSM lib corrupted."} 并退出，所以只能在运行时补。</p>
 *
 * <p>做法：启动服务端时给子进程设置 {@code LD_PRELOAD}，加载一个 15 KB 的
 * {@code libysmboot.so}：</p>
 * <ol>
 *   <li>constructor 在 libc 的 verdef 链尾补一个 {@code GLIBC_2.18} 节点
 *       （仅在进程内存中改写链尾 4 字节的 {@code vd_next} 指针，新节点放在 libc 附近
 *       新 mmap 的一页；不修改磁盘上的 libc 文件）；</li>
 *   <li>它自己实现 {@code __cxa_thread_atexit_impl} 并声明为 {@code GLIBC_2.18}。</li>
 * </ol>
 * <p>LD_PRELOAD 的库在符号查找链里排在 libc 之前，所以版本检查和符号解析同时命中。
 * 不改任何 jar/so 文件，也不替换系统 libc。</p>
 *
 * <p>只在需要时启用：Linux x86_64、glibc &lt; 2.18、{@code mods/} 里存在 YSM。
 * glibc ≥ 2.18 / musl / 非 x86_64 一律跳过，避免任何副作用。</p>
 *
 * <p>极简容器里读不到 glibc 版本时，可用 {@code -Dmcargus.ysm.glibc=2.17}
 * 在 MCArgus 自己的启动命令上手动指定（不要写进 jvm.extraArgs，那是传给服务端的）。</p>
 */
public final class YsmGlibcFix {

    /** 打进 jar 的兼容层（见 build.gradle 的 resources 默认打包规则）。 */
    private static final String RESOURCE = "/native/libysmboot.so";
    /** 解包位置：<serverDir>/mcargus/native/libysmboot.so（SFTP 可见，便于审计/替换）。 */
    private static final String DIR = "mcargus/native";
    private static final String NAME = "libysmboot.so";

    /** 准备结果；为 null 表示不需要或当前环境不支持。 */
    public static final class Prepared {
        public final String soPath;
        public final String glibcVersion;

        Prepared(String soPath, String glibcVersion) {
            this.soPath = soPath;
            this.glibcVersion = glibcVersion;
        }
    }

    private YsmGlibcFix() {
    }

    /**
     * 按需把兼容层前置到子进程的 {@code LD_PRELOAD}；不需要时返回 false 且不改环境。
     * 只影响传入的 ProcessBuilder，不改系统库、不改 mods/ 里的任何文件。
     */
    public static boolean apply(ProcessBuilder pb, Path serverDir) {
        Prepared ysm = prepare(serverDir);
        if (ysm == null) {
            return false;
        }
        String old = pb.environment().get("LD_PRELOAD");
        pb.environment().put("LD_PRELOAD",
                (old == null || old.trim().isEmpty()) ? ysm.soPath : ysm.soPath + ":" + old);
        Log.info(Messages.get("ysm.compat.enabled", ysm.glibcVersion));
        return true;
    }

    /**
     * 按需解包兼容层并返回加载信息；不需要时返回 {@code null}。
     * 任何异常都只记一条 warn，绝不影响服务器正常启动。
     */
    public static Prepared prepare(Path serverDir) {
        try {
            if (!isLinuxX64()) {
                return null;
            }
            // 先看 mods/，没有 YSM 就不必执行 getconf/ldd
            if (!hasYsmMod(serverDir)) {
                return null;
            }
            // 允许手动指定（极简容器里没有 getconf/ldd 时的兜底，也可用于测试）
            String glibc = System.getProperty("mcargus.ysm.glibc");
            if (glibc == null || glibc.trim().isEmpty()) {
                glibc = glibcVersion();
            }
            if (glibc == null) {
                return null; // musl（Alpine）或读不到版本
            }
            if (!VersionUtil.isValidVersion(glibc)) {
                Log.warn(Messages.get("ysm.compat.failed", "invalid glibc version: " + glibc));
                return null;
            }
            if (VersionUtil.compare(glibc, "2.18") >= 0) {
                return null; // 系统自带 GLIBC_2.18，不需要
            }

            Path dir = serverDir.resolve(DIR);
            Files.createDirectories(dir);
            Path so = dir.resolve(NAME);
            if (!Files.isRegularFile(so) || Files.size(so) == 0) {
                extract(dir, so);
            }
            try {
                so.toFile().setReadable(true, false);
            } catch (Throwable ignored) {
                // 只读文件系统等：文件本身仍可被加载
            }
            return new Prepared(so.toAbsolutePath().toString(), glibc);
        } catch (Throwable t) {
            Log.warn(Messages.get("ysm.compat.failed", String.valueOf(t)));
            return null;
        }
    }

    /**
     * 先写临时文件再原子替换，避免进程在复制中途被杀后留下半个 .so
     * （下次启动会把它当成可用文件而加载失败）。
     */
    private static void extract(Path dir, Path so) throws IOException {
        Path tmp = Files.createTempFile(dir, NAME + ".", ".tmp");
        boolean moved = false;
        try {
            InputStream in = YsmGlibcFix.class.getResourceAsStream(RESOURCE);
            if (in == null) {
                throw new IOException("missing resource " + RESOURCE);
            }
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                in.close();
            }
            try {
                Files.move(tmp, so, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, so, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    private static boolean isLinuxX64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.indexOf("linux") < 0) {
            return false;
        }
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.equals("amd64") || arch.equals("x86_64") || arch.indexOf("x86_64") >= 0;
    }

    /**
     * 读取宿主 glibc 版本；非 glibc（musl）或读不到时返回 null。
     *
     * <p>优先 {@code getconf GNU_LIBC_VERSION}（coreutils，输出 "glibc 2.17"），
     * 失败再退回 {@code ldd --version}（CentOS 7 输出 "ldd (GNU libc) 2.17"）。
     * 两条路都拿不到就返回 null，宁可不启用也不误判。</p>
     */
    private static String glibcVersion() {
        String text = Exec.run(3000, "getconf", "GNU_LIBC_VERSION");
        if (text == null || text.trim().isEmpty()) {
            text = Exec.run(3000, "ldd", "--version");
        }
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.indexOf("glibc") < 0 && lower.indexOf("gnu libc") < 0) {
            return null; // musl 等
        }
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)").matcher(text);
        return m.find() ? m.group(1) + "." + m.group(2) : null;
    }

    /** mods/ 里是否存在 YSM：ysm-2.6.5-...jar / yes_steve_model-...jar / YesSteveModel-...jar。 */
    private static boolean hasYsmMod(Path serverDir) {
        Path mods = serverDir.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return false;
        }
        DirectoryStream<Path> ds = null;
        try {
            ds = Files.newDirectoryStream(mods);
            for (Path p : ds) {
                String raw = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!raw.endsWith(".jar")) {
                    continue;
                }
                // 去掉空格/连字符/下划线后再认，兼容 "Yes Steve Model.jar" 这类命名
                String n = raw.replace(" ", "").replace("-", "").replace("_", "");
                if (n.startsWith("ysm") || n.indexOf("yesstevemodel") >= 0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        } finally {
            if (ds != null) {
                try {
                    ds.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return false;
    }
}

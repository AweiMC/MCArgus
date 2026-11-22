package com.mcargus.discovery;

import com.mcargus.Config;
import com.mcargus.Errors;
import com.mcargus.i18n.Messages;
import com.mcargus.util.Log;
import com.mcargus.util.VersionUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 启动目标自动发现。
 *
 * 优先级：CLI 指定(--args-file) &gt; 缓存(校验 mtime/size + 同家族目录版本升级检查) &gt; 全量扫描。
 * 扫描范围：服务器目录（默认深度 7）中的 unix_args.txt / win_args.txt；
 * 全部缺失时退化为 Jar 模式：fabric-server-launch.jar &gt; quilt-server-launch.jar &gt; server.jar（排除自身、排除 Forge 安装器）。
 */
public final class Discovery {
    public static final String UNIX_ARGS = "unix_args.txt";
    public static final String WIN_ARGS = "win_args.txt";
    public static final int MAX_SCAN_DEPTH = 7;

    private Discovery() {
    }

    /** 主入口。找不到任何可用目标时抛 PreflightException。 */
    public static LaunchTarget resolveTarget(Path serverDir, Config cfg, boolean rescan,
                                             Path pinOverride, Path selfJar, boolean isWindows) {
        if (pinOverride != null) {
            ArgsCandidate c = analyze(pinOverride, isWindows);
            if (c == null) {
                throw new Errors.PreflightException(Messages.get("discovery.argsFileUnavailable", pinOverride));
            }
            Log.info(Messages.get("discovery.usingCliArgs", c.describe()));
            return LaunchTarget.argsFile(c);
        }

        // 面板启动规则优先：根目录的 unix_args.txt / win_args.txt
        // （与翼龙 egg 的 [[ -f unix_args.txt ]] 判定语义一致：根层文件是整合包明确的启动入口）
        String[] rootNames = isWindows ? new String[]{WIN_ARGS, UNIX_ARGS} : new String[]{UNIX_ARGS, WIN_ARGS};
        for (String rootName : rootNames) {
            Path f = serverDir.resolve(rootName);
            if (Files.isRegularFile(f)) {
                ArgsCandidate c = analyze(f, rootName.equals(WIN_ARGS));
                if (c != null) {
                    Log.info(Messages.get("discovery.usingRootArgs", c.describe()));
                    saveArgsCache(cfg, c);
                    return LaunchTarget.argsFile(c);
                }
            }
        }

        LaunchTarget cached = tryCache(cfg, selfJar, isWindows);
        if (!rescan && cached != null) {
            Log.info(Messages.get("discovery.usingCache", cached.describe()));
            return cached;
        }

        Log.info(Messages.get("discovery.scanning", MAX_SCAN_DEPTH));
        List<ArgsCandidate> all = scan(serverDir);
        ArgsCandidate best = pickBest(all, isWindows);
        if (best != null) {
            Log.info(Messages.get("discovery.picked", all.size(), best.describe()));
            saveArgsCache(cfg, best);
            return LaunchTarget.argsFile(best);
        }

        JarCandidate jar = findJar(serverDir, selfJar);
        if (jar != null) {
            Log.info(Messages.get("discovery.jarFallback", jar.describe()));
            saveJarCache(cfg, jar);
            return LaunchTarget.jar(jar);
        }

        clearCache(cfg);
        throw new Errors.PreflightException(buildNotFoundMessage(serverDir));
    }

    // ---------------------------------------------------------------- 缓存

    private static LaunchTarget tryCache(Config cfg, Path selfJar, boolean isWindows) {
        String argsPath = cfg.get(Config.K_CACHE_ARGS, "");
        String jarPath = cfg.get(Config.K_CACHE_JAR, "");

        if (!argsPath.isEmpty()) {
            Path f = Paths(argsPath);
            if (!Files.isRegularFile(f)) {
                Log.warn(Messages.get("discovery.cacheGone", f));
                return null;
            }
            BasicFileAttributes attr = attrOf(f);
            long mtime = parseLong(cfg.get(Config.K_CACHE_ARGS_MTIME, "0"));
            long size = parseLong(cfg.get(Config.K_CACHE_ARGS_SIZE, "0"));
            if (attr == null || attr.lastModifiedTime().toMillis() != mtime || attr.size() != size) {
                Log.warn(Messages.get("discovery.cacheChanged", f));
                return null;
            }
            ArgsCandidate cached = analyze(f, isWindows);
            if (cached == null) {
                Log.warn(Messages.get("discovery.cacheInvalid", f));
                return null;
            }
            ArgsCandidate newer = newerSiblingOf(f, cached, isWindows);
            if (newer != null) {
                Log.info(Messages.get("discovery.cacheUpgrade", newer.describe()));
                saveArgsCache(cfg, newer);
                return LaunchTarget.argsFile(newer);
            }
            return LaunchTarget.argsFile(cached);
        }

        if (!jarPath.isEmpty()) {
            Path f = Paths(jarPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(f) && !isSelfJar(f, selfJar)) {
                return LaunchTarget.jar(new JarCandidate(f, cfg.get(Config.K_CACHE_JARKIND, "vanilla")));
            }
            Log.warn(Messages.get("discovery.jarCacheInvalid", f));
        }
        return null;
    }

    /**
     * 版本变化检测：若缓存文件位于 "…/&lt;加载器&gt;/&lt;版本&gt;/xxx_args.txt" 结构中，
     * 只检查该家族目录下是否出现更新的版本（廉价操作，避免每次全量扫描）。
     */
    private static ArgsCandidate newerSiblingOf(Path file, ArgsCandidate cached, boolean isWindows) {
        try {
            Path versionDir = file.getParent();
            Path familyDir = versionDir == null ? null : versionDir.getParent();
            if (versionDir == null || familyDir == null) {
                return null;
            }
            if (cached.version == null || !containsDigit(versionDir.getFileName().toString())) {
                return null;
            }
            List<ArgsCandidate> cands = new ArrayList<>();
            File[] subs = familyDir.toFile().listFiles();
            if (subs == null) {
                return null;
            }
            String[] names = isWindows ? new String[]{WIN_ARGS, UNIX_ARGS} : new String[]{UNIX_ARGS, WIN_ARGS};
            for (File sub : subs) {
                if (!sub.isDirectory()) {
                    continue;
                }
                for (String name : names) {
                    Path f2 = sub.toPath().resolve(name);
                    if (Files.isRegularFile(f2)) {
                        ArgsCandidate c = analyze(f2, isWindows);
                        if (c != null) {
                            cands.add(c);
                        }
                    }
                }
            }
            ArgsCandidate best = pickBest(cands, isWindows);
            if (best == null || best.version == null) {
                return null;
            }
            return VersionUtil.compare(best.version, cached.version) > 0 ? best : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveArgsCache(Config cfg, ArgsCandidate c) {
        cfg.set(Config.K_CACHE_ARGS, c.file.toString());
        cfg.set(Config.K_CACHE_ARGS_MTIME, Long.toString(c.mtime));
        cfg.set(Config.K_CACHE_ARGS_SIZE, Long.toString(c.size));
        cfg.set(Config.K_CACHE_LOADER, c.type.name());
        cfg.set(Config.K_CACHE_LVER, c.version == null ? "" : c.version);
        cfg.set(Config.K_CACHE_JAR, "");
        cfg.set(Config.K_CACHE_JARKIND, "");
        cfg.saveIfDirty();
    }

    private static void saveJarCache(Config cfg, JarCandidate j) {
        cfg.set(Config.K_CACHE_ARGS, "");
        cfg.set(Config.K_CACHE_ARGS_MTIME, "");
        cfg.set(Config.K_CACHE_ARGS_SIZE, "");
        cfg.set(Config.K_CACHE_LOADER, "");
        cfg.set(Config.K_CACHE_LVER, "");
        cfg.set(Config.K_CACHE_JAR, j.file.toString());
        cfg.set(Config.K_CACHE_JARKIND, j.kind);
        cfg.saveIfDirty();
    }

    /** 清空启动目标缓存（配置损坏恢复、找不到任何启动目标时调用）。 */
    public static void clearCache(Config cfg) {
        cfg.set(Config.K_CACHE_ARGS, "");
        cfg.set(Config.K_CACHE_ARGS_MTIME, "");
        cfg.set(Config.K_CACHE_ARGS_SIZE, "");
        cfg.set(Config.K_CACHE_LOADER, "");
        cfg.set(Config.K_CACHE_LVER, "");
        cfg.set(Config.K_CACHE_JAR, "");
        cfg.set(Config.K_CACHE_JARKIND, "");
        cfg.saveIfDirty();
    }

    // ---------------------------------------------------------------- 扫描

    private static List<ArgsCandidate> scan(Path serverDir) {
        final List<ArgsCandidate> out = new ArrayList<ArgsCandidate>();
        try {
            Files.walkFileTree(serverDir, EnumSet.noneOf(FileVisitOption.class), MAX_SCAN_DEPTH,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                            if (name.equalsIgnoreCase("mcargus") || name.startsWith(".")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (!attrs.isRegularFile()) {
                                return FileVisitResult.CONTINUE;
                            }
                            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (name.equals(UNIX_ARGS) || name.equals(WIN_ARGS)) {
                                ArgsCandidate c = analyze(file, name.equals(WIN_ARGS));
                                if (c != null) {
                                    out.add(c);
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            Log.warn(Messages.get("discovery.scanError", e.getMessage()));
        }
        return out;
    }

    /** 校验并解析一个参数文件；不合法返回 null。 */
    public static ArgsCandidate analyze(Path file, boolean windows) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) == 0) {
                return null;
            }
            if (!firstLineIsOption(file)) {
                return null;
            }
            BasicFileAttributes attr = Files.readAttributes(file, BasicFileAttributes.class);
            Path p = file.toAbsolutePath().normalize();

            LoaderType type = LoaderType.UNKNOWN;
            String version = null;
            List<String> segs = new ArrayList<String>();
            for (Path part : p) {
                segs.add(part.toString());
            }
            // 官方布局: libraries/net/<minecraftforge|neoforged>/<forge|neoforge>/<版本>/xxx_args.txt
            for (int i = 0; i + 3 < segs.size(); i++) {
                if (!segs.get(i).equalsIgnoreCase("net")) {
                    continue;
                }
                String group = segs.get(i + 1).toLowerCase(Locale.ROOT);
                String artifact = segs.get(i + 2).toLowerCase(Locale.ROOT);
                boolean forge = group.equals("minecraftforge") && artifact.equals("forge");
                boolean neo = group.equals("neoforged") && (artifact.equals("forge") || artifact.equals("neoforge"));
                if (forge || neo) {
                    type = neo ? LoaderType.NEOFORGE : LoaderType.FORGE;
                    version = segs.get(i + 3);
                    break;
                }
            }
            if (version == null) {
                Path parent = p.getParent();
                if (parent != null && parent.getFileName() != null) {
                    String n = parent.getFileName().toString();
                    if (n.matches(".*\\d.*")) {
                        version = n; // 非标准布局：用父目录名兜底当版本
                    }
                }
            }
            return new ArgsCandidate(p, type, version, windows,
                    attr.lastModifiedTime().toMillis(), attr.size());
        } catch (IOException e) {
            return null;
        }
    }

    /** 参数文件的第一个非空行必须是 JVM 选项（以 - 开头）。 */
    private static boolean firstLineIsOption(Path file) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(file.toFile()), StandardCharsets.UTF_8));
            String ln;
            for (int i = 0; i < 10 && (ln = r.readLine()) != null; i++) {
                ln = ln.trim();
                if (ln.isEmpty()) {
                    continue;
                }
                return ln.startsWith("-");
            }
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    static ArgsCandidate pickBest(List<ArgsCandidate> cands, boolean isWindows) {
        if (cands.isEmpty()) {
            return null;
        }
        List<ArgsCandidate> preferred = new ArrayList<ArgsCandidate>();
        for (ArgsCandidate c : cands) {
            if (c.windows == isWindows) {
                preferred.add(c);
            }
        }
        List<ArgsCandidate> pool = preferred;
        if (pool.isEmpty()) {
            Log.warn(Messages.get("discovery.noPlatformMatch", isWindows ? WIN_ARGS : UNIX_ARGS));
            pool = cands;
        }
        ArgsCandidate best = null;
        for (ArgsCandidate c : pool) {
            if (best == null || better(c, best)) {
                best = c;
            }
        }
        return best;
    }

    private static boolean better(ArgsCandidate a, ArgsCandidate b) {
        int vc = VersionUtil.compare(a.version, b.version);
        if (vc != 0) {
            return vc > 0;
        }
        if (a.mtime != b.mtime) {
            return a.mtime > b.mtime;
        }
        return a.file.toString().length() < b.file.toString().length();
    }

    // ---------------------------------------------------------------- Jar 回退

    static JarCandidate findJar(Path serverDir, Path selfJar) {
        String[] names = {"fabric-server-launch.jar", "quilt-server-launch.jar", "server.jar"};
        String[] kinds = {"fabric", "quilt", "vanilla"};
        for (int i = 0; i < names.length; i++) {
            Path f = serverDir.resolve(names[i]);
            if (!Files.isRegularFile(f)) {
                continue;
            }
            if (isSelfJar(f, selfJar)) {
                Log.info(Messages.get("discovery.skipSelf", names[i]));
                continue;
            }
            if (looksLikeForgeInstaller(f)) {
                Log.warn(Messages.get("discovery.forgeInstaller", names[i], names[i]));
                continue;
            }
            return new JarCandidate(f.toAbsolutePath().normalize(), kinds[i]);
        }
        return null;
    }

    static boolean isSelfJar(Path f, Path selfJar) {
        if (selfJar == null) {
            return false;
        }
        try {
            return f.toFile().getCanonicalPath().equalsIgnoreCase(selfJar.toFile().getCanonicalPath());
        } catch (IOException e) {
            return f.toAbsolutePath().normalize().equals(selfJar.toAbsolutePath().normalize());
        }
    }

    /** Forge 安装器 Zip 内含 data/ 目录（lzma 安装数据）；正常服务端 Jar 没有。 */
    private static boolean looksLikeForgeInstaller(Path jar) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(jar.toFile());
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.startsWith("data/") && name.length() > "data/".length()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false; // 不是合法 zip：交由后续启动过程自然报错
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private static Path Paths(String s) {
        return java.nio.file.Paths.get(s);
    }

    private static BasicFileAttributes attrOf(Path f) {
        try {
            return Files.readAttributes(f, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String buildNotFoundMessage(Path serverDir) {
        return Messages.get("discovery.notFound", serverDir, MAX_SCAN_DEPTH, UNIX_ARGS, WIN_ARGS);
    }
}

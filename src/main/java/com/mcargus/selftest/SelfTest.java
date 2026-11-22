package com.mcargus.selftest;

import com.mcargus.Config;
import com.mcargus.Errors;
import com.mcargus.Main;
import com.mcargus.discovery.Discovery;
import com.mcargus.discovery.LaunchTarget;
import com.mcargus.discovery.LoaderType;
import com.mcargus.install.Installer;
import com.mcargus.launch.JvmLocator;
import com.mcargus.launch.ServerLauncher;
import com.mcargus.launch.YsmGlibcFix;
import com.mcargus.mods.ModSanitizer;
import com.mcargus.mods.ScanManager;
import com.mcargus.util.Log;
import com.mcargus.util.VersionUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 零依赖自测试套件（不依赖 JUnit，便于在任何环境用 java -cp 直接运行）。
 * 覆盖：版本比较、配置往返、参数文件校验、发现与缓存策略、Jar 回退、
 * 自身排除与安装器识别、命令组装、user_jvm_args。
 */
public final class SelfTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        // 在 Logback 初始化前把日志目录指向临时目录，避免测试污染工作目录
        System.setProperty("MCARGUS_LOG_DIR",
                java.nio.file.Files.createTempDirectory("mcargus-selftest-logs").toString());
        Log.setQuiet(true);
        testVersionCompare();
        testConfigRoundtrip();
        testArgsValidation();
        testDiscoveryPickNewest();
        testOsPreference();
        testCacheUpgradeAndFastPath();
        testCacheInvalidRescan();
        testJarFallback();
        testSelfExclusionAndInstaller();
        testPlanAssembly();
        testUserJvmArgsAndFilter();
        testModSanitizer();
        testFingerprint();
        testScanManager();
        testConfigCorruption();
        testShutdownChild();
        testRootArgsPriority();
        testInstaller();
        testYsmCompat();
        testDashDForward();
        System.out.println("SelfTest: 通过 " + passed + "，失败 " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static Path write(Path f, String content) throws Exception {
        Files.createDirectories(f.getParent());
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static Path tempRoot() throws Exception {
        return Files.createTempDirectory("mcargus-selftest");
    }

    private static Config newConfig(Path root) {
        return Config.load(root.resolve("mcargus/mcargus.properties"));
    }

    // ---------------------------------------------------------------- 用例

    private static void testVersionCompare() {
        check("vcmp 2.17<2.18", VersionUtil.compare("2.17", "2.18") < 0);
        check("vcmp 2.18>2.17", VersionUtil.compare("2.18", "2.17") > 0);
        check("vcmp 2.2<2.17", VersionUtil.compare("2.2", "2.17") < 0);
        check("vcmp 2.17<2.17.1", VersionUtil.compare("2.17", "2.17.1") < 0);
        check("vcmp 21.1.77>21.1.9", VersionUtil.compare("1.21.1-21.1.77", "1.21.1-21.1.9") > 0);
        check("vcmp 相等", VersionUtil.compare("47.2.0", "47.2.0") == 0);
        check("vcmp null<有值", VersionUtil.compare(null, "2.0") < 0);
        check("vcmp 有值>null", VersionUtil.compare("2.0", null) > 0);
    }

    private static void testConfigRoundtrip() throws Exception {
        Path root = tempRoot();
        Config c = newConfig(root);
        c.set("test.path", "D:\\测试 目录\\x");
        c.setBool(Config.K_JVM_FORWARD, false);
        c.save();
        Config c2 = Config.load(root.resolve("mcargus/mcargus.properties"));
        check("config 中文路径往返", "D:\\测试 目录\\x".equals(c2.get("test.path", "")));
        check("config bool 往返", !c2.getBool(Config.K_JVM_FORWARD, true));
    }

    private static void testArgsValidation() throws Exception {
        Path root = tempRoot();
        Path good = write(root.resolve("a/unix_args.txt"), "-Dfoo=bar\n-p\nx.jar\n");
        Path bad = write(root.resolve("b/unix_args.txt"), "nogui\n");
        Path empty = write(root.resolve("c/unix_args.txt"), "");
        check("args 合法文件", Discovery.analyze(good, false) != null);
        check("args 首行非选项 → 拒绝", Discovery.analyze(bad, false) == null);
        check("args 空文件 → 拒绝", Discovery.analyze(empty, false) == null);
    }

    private static void testDiscoveryPickNewest() throws Exception {
        Path root = tempRoot();
        write(root.resolve("libraries/net/neoforged/neoforge/1.21.1-21.1.60/unix_args.txt"), "-Dfoo=bar\n");
        Path v77 = write(root.resolve("libraries/net/neoforged/neoforge/1.21.1-21.1.77/unix_args.txt"), "-Dfoo=bar\n");
        LaunchTarget t = Discovery.resolveTarget(root, newConfig(root), false, null, null, false);
        check("discovery 选最新版本", t.file.equals(v77.toAbsolutePath().normalize()));
        check("discovery 类型 NeoForge", t.loaderType == LoaderType.NEOFORGE);
        check("discovery 版本解析", "1.21.1-21.1.77".equals(t.loaderVersion));
        check("discovery 模式 ARGS_FILE", t.mode == LaunchTarget.Mode.ARGS_FILE);
    }

    private static void testOsPreference() throws Exception {
        Path root = tempRoot();
        Path unix = write(root.resolve("libraries/net/minecraftforge/forge/1.20.1-47.2.0/unix_args.txt"), "-Da=b\n");
        Path win = write(root.resolve("libraries/net/minecraftforge/forge/1.20.1-47.2.0/win_args.txt"), "-Da=b\n");
        LaunchTarget tw = Discovery.resolveTarget(root, newConfig(root), true, null, null, true);
        check("os 偏好 win_args（windows）", tw.file.equals(win.toAbsolutePath().normalize()));
        LaunchTarget tu = Discovery.resolveTarget(root, newConfig(root), true, null, null, false);
        check("os 偏好 unix_args（linux）", tu.file.equals(unix.toAbsolutePath().normalize()));
        check("discovery 类型 Forge", tw.loaderType == LoaderType.FORGE);
    }

    private static void testCacheUpgradeAndFastPath() throws Exception {
        Path root = tempRoot();
        Path v77 = write(root.resolve("libraries/net/neoforged/neoforge/1.21.1-21.1.77/unix_args.txt"), "-Da=b\n");
        Config cfg = newConfig(root);
        LaunchTarget t1 = Discovery.resolveTarget(root, cfg, false, null, null, false);
        check("cache 初次选择并写入", t1.file.equals(v77.toAbsolutePath().normalize()));
        Path v99 = write(root.resolve("libraries/net/neoforged/neoforge/1.21.1-21.1.99/unix_args.txt"), "-Da=b\n");
        LaunchTarget t2 = Discovery.resolveTarget(root, cfg, false, null, null, false);
        check("cache 家族目录升级到新版本", t2.file.equals(v99.toAbsolutePath().normalize()));
        LaunchTarget t3 = Discovery.resolveTarget(root, cfg, false, null, null, false);
        check("cache 命中快路径", t3.file.equals(v99.toAbsolutePath().normalize()));
    }

    private static void testCacheInvalidRescan() throws Exception {
        // 场景1：缓存文件被删且无其他目标 → 应报 PreflightException 而非崩溃
        Path root1 = tempRoot();
        Path v77 = write(root1.resolve("libraries/net/neoforged/neoforge/1.21.1-21.1.77/unix_args.txt"), "-Da=b\n");
        Config cfg1 = newConfig(root1);
        Discovery.resolveTarget(root1, cfg1, false, null, null, false);
        Files.delete(v77);
        try {
            Discovery.resolveTarget(root1, cfg1, false, null, null, false);
            check("cache 失效→重扫无目标→报错", false);
        } catch (Errors.PreflightException e) {
            check("cache 失效→重扫无目标→报错", true);
        }

        // 场景2：缓存文件被删但存在 fabric jar → 应退化到 Jar 模式
        Path root2 = tempRoot();
        Path v60 = write(root2.resolve("libraries/net/neoforged/neoforge/1.21.1-21.1.60/unix_args.txt"), "-Da=b\n");
        write(root2.resolve("fabric-server-launch.jar"), "placeholder");
        Config cfg2 = newConfig(root2);
        Discovery.resolveTarget(root2, cfg2, false, null, null, false);
        Files.delete(v60);
        LaunchTarget t = Discovery.resolveTarget(root2, cfg2, false, null, null, false);
        check("cache 失效→重扫退化 Jar", t != null && t.mode == LaunchTarget.Mode.JAR && "fabric".equals(t.jarKind));
    }

    private static void testJarFallback() throws Exception {
        Path root = tempRoot();
        write(root.resolve("fabric-server-launch.jar"), "placeholder");
        LaunchTarget t = Discovery.resolveTarget(root, newConfig(root), false, null, null, false);
        check("jar 回退 fabric", t != null && t.mode == LaunchTarget.Mode.JAR && "fabric".equals(t.jarKind));
    }

    private static void testSelfExclusionAndInstaller() throws Exception {
        // 自身排除：目录里唯一的 server.jar 就是 MCArgus 自己 → 必须拒绝，避免自我循环
        Path root1 = tempRoot();
        Path self = write(root1.resolve("server.jar"), "self-bytes");
        try {
            Discovery.resolveTarget(root1, newConfig(root1), false, null, self, false);
            check("排除自身 server.jar", false);
        } catch (Errors.PreflightException e) {
            check("排除自身 server.jar", true);
        }

        // Forge 安装器识别：zip 内含 data/ 条目
        Path root2 = tempRoot();
        Path inst = root2.resolve("server.jar");
        ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(inst));
        zos.putNextEntry(new ZipEntry("data/client.lzma"));
        zos.write(new byte[]{1});
        zos.closeEntry();
        zos.close();
        try {
            Discovery.resolveTarget(root2, newConfig(root2), false, null, null, false);
            check("识别 Forge 安装器并拒绝", false);
        } catch (Errors.PreflightException e) {
            check("识别 Forge 安装器并拒绝", true);
        }
    }

    private static void testPlanAssembly() throws Exception {
        Path root = tempRoot();
        Path argsFile = write(root.resolve("libraries/net/neoforged/neoforge/1.21.1-21.1.77/unix_args.txt"), "-Da=b\n");
        Config cfg = newConfig(root);
        LaunchTarget t = Discovery.resolveTarget(root, cfg, false, null, null, false);
        JvmLocator jvm = JvmLocator.resolve("", null);

        ServerLauncher.Plan p1 = ServerLauncher.buildPlan(root, cfg, null, null, null, t, jvm,
                new ArrayList<String>());
        check("plain 以 java 开头", p1.command.get(0).contains("java"));
        check("plain 含 @argsfile", p1.command.contains("@" + argsFile.toAbsolutePath().normalize()));
        check("plain 默认 nogui", p1.command.get(p1.command.size() - 1).equals("nogui"));
        check("plain 不含动态加载器前缀", !p1.command.get(0).contains("ld-linux"));

        // --jvm 覆盖 + --program-args 置空 + 父参数转发
        ServerLauncher.Plan p3 = ServerLauncher.buildPlan(root, cfg, "-Xmx9G", "", null, t, jvm,
                new ArrayList<String>(Arrays.asList("-Xmx2G")));
        check("cli jvm 覆盖生效", p3.command.contains("-Xmx9G"));
        check("cli program-args 置空", !p3.command.contains("nogui"));
        check("父参数转发 -Xmx2G", p3.command.contains("-Xmx2G"));
    }

    private static void testUserJvmArgsAndFilter() throws Exception {
        Path root = tempRoot();
        write(root.resolve("user_jvm_args.txt"), "# 注释\n\n-Xmx4G\n  -XX:+UseG1GC \n");
        List<String> l = ServerLauncher.readUserJvmArgs(root);
        check("user_jvm_args 解析", l.size() == 2 && l.get(0).equals("-Xmx4G") && l.get(1).equals("-XX:+UseG1GC"));

        List<String> f = Main.filterParentArgs(Arrays.asList("-Xmx2G", "-XX:+UseG1GC", "-jar", "--help", "-server"));
        check("父参数白名单过滤", f.size() == 3 && f.contains("-Xmx2G")
                && f.contains("-XX:+UseG1GC") && f.contains("-server"));
    }

    private static void testModSanitizer() throws Exception {
        // 前缀解析单元
        check("strip 嵌套括号前缀", "jei".equals(ModSanitizer.stripDecorPrefix("[JEI背包管理器[Forge]]jei")));
        check("strip 多组前缀", "appleskin".equals(ModSanitizer.stripDecorPrefix("【公告】[汉化]appleskin")));
        check("strip 带连接符前缀", "jei".equals(ModSanitizer.stripDecorPrefix("[MCMOD] - jei")));
        check("strip 不动正常名", "jei".equals(ModSanitizer.stripDecorPrefix("jei")));
        check("strip 不动中部括号", "jei[1.20]".equals(ModSanitizer.stripDecorPrefix("jei[1.20]")));
        check("strip 全括号名不剥空", "[x]".equals(ModSanitizer.stripDecorPrefix("[x]")));

        // 目录级清理
        Path root = tempRoot();
        java.nio.file.Path mods = root.resolve("mods");
        write(mods.resolve("[JEI背包管理器[Forge]]jei.jar"), "a");
        write(mods.resolve("【公告】[汉化]appleskin.jar"), "b");
        write(mods.resolve("(1)sodium.jar"), "c");
        write(mods.resolve("[中] - jei 2.jar"), "g");
        write(mods.resolve("normal.jar"), "d");
        write(mods.resolve("jei[1.20].jar"), "e");
        write(mods.resolve("[x].jar"), "f");
        write(mods.resolve("taken.jar"), "t1");
        write(mods.resolve("[xx]taken.jar"), "t2");
        write(mods.resolve("sub/[x]nested.jar"), "h");

        ModSanitizer.Report r = ModSanitizer.clean(root, true);
        check("sanitize 顶层扫描数(不含子目录)", r.scanned == 9);
        check("sanitize 嵌套括号→jei.jar", Files.exists(mods.resolve("jei.jar"))
                && !Files.exists(mods.resolve("[JEI背包管理器[Forge]]jei.jar")));
        check("sanitize 多重前缀→appleskin.jar", Files.exists(mods.resolve("appleskin.jar")));
        check("sanitize (1)前缀→sodium.jar", Files.exists(mods.resolve("sodium.jar")));
        check("sanitize 前缀后空格→jei 2.jar", Files.exists(mods.resolve("jei 2.jar")));
        check("sanitize 正常名不动", Files.exists(mods.resolve("normal.jar")));
        check("sanitize 中部括号不动", Files.exists(mods.resolve("jei[1.20].jar")));
        check("sanitize 全括号名不动", Files.exists(mods.resolve("[x].jar")));
        check("sanitize 子目录不动", Files.exists(mods.resolve("sub/[x]nested.jar")));
        check("sanitize 重名冲突跳过", Files.exists(mods.resolve("taken.jar"))
                && Files.exists(mods.resolve("[xx]taken.jar")) && r.collisions.size() == 1);
        check("sanitize 重命名记录数", r.renamed.size() == 4);

        // 预览模式：只报告不改动
        write(mods.resolve("[新]preview.jar"), "p");
        ModSanitizer.Report rp = ModSanitizer.clean(root, false);
        check("sanitize 预览不改文件", rp.renamed.size() == 1
                && Files.exists(mods.resolve("[新]preview.jar")));
        ModSanitizer.Report rf = ModSanitizer.clean(root, true);
        check("sanitize 预览后实际执行", Files.exists(mods.resolve("preview.jar"))
                && rf.renamed.size() == 1);

        // 无 mods 目录 → 空报告
        Path root2 = tempRoot();
        check("sanitize 无 mods 目录", ModSanitizer.clean(root2, true).isEmpty());
    }

    private static void testFingerprint() throws Exception {
        Path root = tempRoot();
        Path mods = root.resolve("mods");
        write(mods.resolve("a.jar"), "aaaa");
        write(mods.resolve("b.jar"), "bb");
        String fp1 = ScanManager.fingerprint(mods);
        check("fingerprint 非空且16位", fp1 != null && fp1.length() == 16);
        check("fingerprint 稳定", fp1.equals(ScanManager.fingerprint(mods)));
        // 数量相同但文件改名 → 指纹必须变化（弥补"只比数量"的盲区）
        Files.move(mods.resolve("a.jar"), mods.resolve("c.jar"));
        String fp2 = ScanManager.fingerprint(mods);
        check("fingerprint 对重命名敏感（数量不变）", !fp1.equals(fp2));
        // 大小变化 → 指纹变化
        write(mods.resolve("b.jar"), "bbb");
        check("fingerprint 对大小敏感", !fp2.equals(ScanManager.fingerprint(mods)));
        // 非 .jar 文件变化不影响（清理器只管 .jar，能力边界内一致）
        String fp3 = ScanManager.fingerprint(mods);
        write(mods.resolve("note.txt"), "hello");
        check("fingerprint 忽略非 jar 文件", fp3.equals(ScanManager.fingerprint(mods)));
        check("fingerprint 目录不存在→null", ScanManager.fingerprint(root.resolve("nope")) == null);
    }

    private static void testScanManager() throws Exception {
        // 场景1：首次启动（scanCompleted 缺失）→ 完整扫描+修复+持久化
        Path root = tempRoot();
        Path mods = root.resolve("mods");
        Path cfgFile = root.resolve("mcargus/mcargus.properties");
        write(mods.resolve("[x]appleskin.jar"), "a");
        Config cfg = newConfig(root);
        ScanManager.Result r1 = ScanManager.ensureScanned(root, cfg, false);
        check("scanManager 首次执行完整扫描", r1.ranFullScan);
        check("scanManager 首次修复文件名", Files.exists(mods.resolve("appleskin.jar")));
        Config persisted = Config.load(cfgFile);
        check("scanManager 持久化 scanCompleted=true", persisted.getBool(Config.K_SCAN_COMPLETED, false));
        check("scanManager 持久化指纹", !persisted.get(Config.K_SCAN_FINGERPRINT, "").isEmpty());

        // 场景2：无变化 → 快速校验通过，跳过完整扫描
        ScanManager.Result r2 = ScanManager.ensureScanned(root, cfg, false);
        check("scanManager 快速校验跳过", !r2.ranFullScan && r2.reason.contains("快速校验"));

        // 场景3：新增 jar（数量变化）→ 触发完整重扫并修复
        write(mods.resolve("[y]jei.jar"), "b");
        ScanManager.Result r3 = ScanManager.ensureScanned(root, cfg, false);
        check("scanManager 数量变化触发重扫", r3.ranFullScan && Files.exists(mods.resolve("jei.jar")));

        // 场景4：数量不变但文件改名 → 指纹仍能发现并重扫
        Files.move(mods.resolve("appleskin.jar"), mods.resolve("[z]appleskin.jar"));
        ScanManager.Result r4 = ScanManager.ensureScanned(root, cfg, false);
        check("scanManager 改名触发重扫（数量不变）", r4.ranFullScan && Files.exists(mods.resolve("appleskin.jar")));

        // 场景5：--rescan 强制完整扫描
        ScanManager.Result r5 = ScanManager.ensureScanned(root, cfg, true);
        check("scanManager --rescan 强制完整扫描", r5.ranFullScan);

        // 场景6：修复失败（文件被占用导致重命名失败）→ scanCompleted 不得为 true
        // 注：Windows 对被打开的文件禁止改名，可稳定构造失败；其他平台若重命名成功则跳过该断言
        Path lock = mods.resolve("[w]locked.jar");
        write(lock, "lock-content");
        java.io.FileOutputStream fos = new java.io.FileOutputStream(lock.toFile());
        try {
            ScanManager.Result r6 = ScanManager.ensureScanned(root, cfg, false);
            if (r6.report != null && !r6.report.failures.isEmpty()) {
                Config after = Config.load(cfgFile);
                check("scanManager 失败不置 scanCompleted",
                        !after.getBool(Config.K_SCAN_COMPLETED, true));
            } else {
                check("scanManager 失败不置 scanCompleted（本平台无法构造重命名失败）", true);
            }
        } finally {
            fos.close();
        }
        Files.deleteIfExists(mods.resolve("[w]locked.jar"));
        Files.deleteIfExists(mods.resolve("locked.jar"));

        // 场景7：scanCompleted=false 后再次运行 → 重新完整扫描
        ScanManager.Result r7 = ScanManager.ensureScanned(root, cfg, false);
        check("scanManager 未完成后重扫", r7.ranFullScan);
    }

    private static void testConfigCorruption() throws Exception {
        Path root = tempRoot();
        Path cfgFile = root.resolve("mcargus/mcargus.properties");
        // 非法 Unicode 转义（反斜杠+u+非十六进制）→ Properties.load 抛 IllegalArgumentException → 必须被捕获并恢复
        // 注意：源码里不能直接写字面量"反斜杠-u"（连注释都会被编译器预处理拒绝），所以运行期拼接
        String badEscape = "\\" + "u" + "ZZZZ";
        write(cfgFile, "java.path=" + badEscape + "\nscan.scanCompleted=true\nscan.modsFingerprint=deadbeef\n");
        Config c = Config.load(cfgFile);
        check("config 损坏→恢复默认", c.isRecovered());
        check("config 损坏→scanCompleted 复位", !c.getBool(Config.K_SCAN_COMPLETED, false));
        check("config 损坏→指纹清空", c.get(Config.K_SCAN_FINGERPRINT, "x").isEmpty()
                || "x".equals(c.get(Config.K_SCAN_FINGERPRINT, "x")));
        boolean backedUp = false;
        File[] files = root.resolve("mcargus").toFile().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("mcargus.properties.corrupt-")) {
                    backedUp = true;
                }
            }
        }
        check("config 损坏已备份原文件", backedUp);
        // 恢复后可正常读写
        c.set("k", "v");
        c.save();
        check("config 恢复后可正常保存加载", "v".equals(Config.load(cfgFile).get("k", "")));
    }

    /** 验证 shutdownChild：能终止一个沉睡中的子进程（与关闭钩子同一代码路径）。 */
    private static void testShutdownChild() throws Exception {
        String cp = Main.selfJarPath() != null
                ? Main.selfJarPath().toString()
                : System.getProperty("java.class.path");
        String javaBin = System.getProperty("java.home") + java.io.File.separator
                + "bin" + java.io.File.separator
                + (System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        java.io.File sink = java.io.File.createTempFile("mcargus-selftest-sink", ".log");
        sink.deleteOnExit();
        Process p = new ProcessBuilder(javaBin, "-cp", cp, "com.mcargus.selftest.SleepChild")
                .redirectErrorStream(true)
                .redirectOutput(sink)
                .start();
        Thread.sleep(800);
        boolean wasAlive = p.isAlive();
        boolean graceful = ServerLauncher.shutdownChild(p, 5, java.util.concurrent.TimeUnit.SECONDS);
        check("shutdownChild 先确认子进程存活", wasAlive);
        check("shutdownChild 在超时内终止子进程", graceful && !p.isAlive());
    }

    // ---------------------------------------------------------------- 压缩包安装与启动规则

    private static void zipEntries(Path zip, Map<String, String> entries) throws Exception {
        Files.createDirectories(zip.getParent());
        ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip));
        try {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } finally {
            zos.close();
        }
    }

    private static void testRootArgsPriority() throws Exception {
        Path root = tempRoot();
        // 根层参数文件（面板规则入口）+ libraries 深层更新版本
        Path rootArgs = write(root.resolve("unix_args.txt"), "-Droot=1\n");
        write(root.resolve("libraries/net/minecraftforge/forge/1.20.1-47.9.9/unix_args.txt"), "-Ddeep=1\n");
        LaunchTarget t = Discovery.resolveTarget(root, newConfig(root), false, null, null, false);
        check("根层参数文件优先于深层新版本（面板规则）", t.file.equals(rootArgs.toAbsolutePath().normalize()));
        // 根层只有 win 文件时：Windows 用 win，Linux 退回 unix
        Path root2 = tempRoot();
        write(root2.resolve("win_args.txt"), "-Dw=1\n");
        LaunchTarget tw = Discovery.resolveTarget(root2, newConfig(root2), false, null, null, true);
        check("根层 win_args（windows）", tw.file.equals(root2.resolve("win_args.txt").toAbsolutePath().normalize()));
        LaunchTarget tu = Discovery.resolveTarget(root2, newConfig(root2), false, null, null, false);
        check("根层仅 win 时 Linux 也可用", tu.file.equals(root2.resolve("win_args.txt").toAbsolutePath().normalize()));
    }

    private static void testInstaller() throws Exception {
        // 场景A：压缩包根层即服务端（flat）→ 安装 → 发现根层参数文件
        Path a = tempRoot();
        Map<String, String> flat = new LinkedHashMap<String, String>();
        flat.put("unix_args.txt", "-Dpack=1\n-p\nlibraries/x.jar\n");
        flat.put("libraries/net/x/y.jar", "jarc");
        flat.put("eula.txt", "eula=true");
        flat.put("server.properties", "server-port=25565");
        zipEntries(a.resolve("server-pack.zip"), flat);
        Config ca = newConfig(a);
        Installer.ensureInstalled(a, ca);
        check("install flat: 参数文件已安装到根", Files.exists(a.resolve("unix_args.txt")));
        check("install flat: 原 zip 保留", Files.exists(a.resolve("server-pack.zip")));
        LaunchTarget ta = Discovery.resolveTarget(a, ca, false, null, null, false);
        check("install flat: 发现为参数文件模式", ta.mode == LaunchTarget.Mode.ARGS_FILE);

        // 场景B：单层包裹目录
        Path b = tempRoot();
        Map<String, String> wrap = new LinkedHashMap<String, String>();
        wrap.put("ServerPack/unix_args.txt", "-Dpack=2\n");
        wrap.put("ServerPack/libraries/x.jar", "j");
        wrap.put("ServerPack/mods/a.jar", "m");
        zipEntries(b.resolve("pack.zip"), wrap);
        Installer.ensureInstalled(b, newConfig(b));
        check("install 单层包裹: 根定位正确", Files.exists(b.resolve("unix_args.txt"))
                && Files.exists(b.resolve("mods/a.jar")));

        // 场景C：多层包裹目录
        Path c = tempRoot();
        Map<String, String> deep = new LinkedHashMap<String, String>();
        deep.put("MC/1.9/Final/ServerPack/win_args.txt", "-Dpack=3\n");
        deep.put("MC/1.9/Final/ServerPack/eula.txt", "eula=true");
        zipEntries(c.resolve("deep.zip"), deep);
        Installer.ensureInstalled(c, newConfig(c));
        check("install 多层包裹: 根定位正确", Files.exists(c.resolve("win_args.txt")));

        // 场景D：CurseForge 客户端整合包 → 明确拒绝
        Path d = tempRoot();
        Map<String, String> cf = new LinkedHashMap<String, String>();
        cf.put("manifest.json", "{\"minecraft\":{}}");
        cf.put("modlist.html", "<html></html>");
        cf.put("overrides/config/a.toml", "x");
        zipEntries(d.resolve("client-pack.zip"), cf);
        try {
            Installer.ensureInstalled(d, newConfig(d));
            check("install CF 客户端包被拒绝", false);
        } catch (Errors.PreflightException e) {
            check("install CF 客户端包被拒绝", String.valueOf(e.getMessage()).contains("CurseForge"));
        }

        // 场景E：zip-slip 路径穿越被阻断
        Path e = tempRoot();
        Map<String, String> slip = new LinkedHashMap<String, String>();
        slip.put("good/unix_args.txt", "-Dok=1\n");
        slip.put("evil/../../evil.txt", "bad");
        zipEntries(e.resolve("slip.zip"), slip);
        try {
            Installer.ensureInstalled(e, newConfig(e));
            check("install zip-slip 被阻断且正常文件已装",
                    !Files.exists(e.getParent().resolve("evil.txt")) && !Files.exists(e.resolve("evil.txt"))
                            && Files.exists(e.resolve("unix_args.txt")));
        } catch (Errors.PreflightException ex) {
            check("install zip-slip 被阻断且正常文件已装", !Files.exists(e.resolve("evil.txt")));
        }

        // 场景F：不覆盖已存在文件
        Path f = tempRoot();
        write(f.resolve("eula.txt"), "MINE-ORIGINAL");
        Map<String, String> packF = new LinkedHashMap<String, String>();
        packF.put("eula.txt", "PACK-VERSION");
        packF.put("unix_args.txt", "-Dpack=4\n");
        zipEntries(f.resolve("pack.zip"), packF);
        Installer.ensureInstalled(f, newConfig(f));
        check("install 不覆盖已存在文件", "MINE-ORIGINAL".equals(new String(Files.readAllBytes(f.resolve("eula.txt")), StandardCharsets.UTF_8))
                && Files.exists(f.resolve("unix_args.txt")));

        // 场景G：已安装记录 → 重复调用给出明确提示而非重复解压
        try {
            Installer.ensureInstalled(f, newConfig(f));
            check("install 幂等（不重复解压）", false);
        } catch (Errors.PreflightException ex) {
            check("install 幂等（不重复解压）", String.valueOf(ex.getMessage()).contains("已解压安装过"));
        }

        // 场景H：多压缩包 —— 垃圾包+服务端包 → 选服务端包
        Path h = tempRoot();
        zipEntries(h.resolve("junk.zip"), single("readme.txt", "not a server"));
        zipEntries(h.resolve("real-pack.zip"), flat);
        Installer.ensureInstalled(h, newConfig(h));
        check("install 多包正确选择服务端包", Files.exists(h.resolve("unix_args.txt")));

        // 场景I：多个都是服务端包 → 中止并列出
        Path i = tempRoot();
        zipEntries(i.resolve("pack1.zip"), flat);
        zipEntries(i.resolve("pack2.zip"), flat);
        try {
            Installer.ensureInstalled(i, newConfig(i));
            check("install 多服务端包中止", false);
        } catch (Errors.PreflightException ex) {
            check("install 多服务端包中止", String.valueOf(ex.getMessage()).contains("无法判定"));
        }

        // 场景J：tar.gz 支持
        Path j = tempRoot();
        makeTarGz(j.resolve("pack.tar.gz"), flat);
        Installer.ensureInstalled(j, newConfig(j));
        check("install tar.gz 支持", Files.exists(j.resolve("unix_args.txt")));
    }

    private static Map<String, String> single(String k, String v) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k, v);
        return m;
    }

    private static void makeTarGz(Path out, Map<String, String> entries) throws Exception {
        Files.createDirectories(out.getParent());
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tos =
                new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
                        new java.util.zip.GZIPOutputStream(Files.newOutputStream(out)));
        try {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                byte[] data = e.getValue().getBytes(StandardCharsets.UTF_8);
                org.apache.commons.compress.archivers.tar.TarArchiveEntry te =
                        new org.apache.commons.compress.archivers.tar.TarArchiveEntry(e.getKey());
                te.setSize(data.length);
                tos.putArchiveEntry(te);
                tos.write(data);
                tos.closeArchiveEntry();
            }
        } finally {
            tos.close();
        }
    }

    private static void testYsmCompat() throws Exception {
        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        String glibc = System.getProperty("mcargus.ysm.glibc");
        try {
            Path root = tempRoot();
            Files.createDirectories(root.resolve("mods"));
            Files.write(root.resolve("mods/ysm-test.jar"), new byte[0]);

            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.arch", "amd64");
            System.setProperty("mcargus.ysm.glibc", "2.17");
            check("ysm 非 Linux 跳过", YsmGlibcFix.prepare(root) == null);

            System.setProperty("os.name", "Linux");
            System.setProperty("mcargus.ysm.glibc", "2.18");
            check("ysm glibc>=2.18 跳过", YsmGlibcFix.prepare(root) == null);

            Path noYsm = tempRoot();
            System.setProperty("mcargus.ysm.glibc", "2.17");
            check("ysm 无 YSM 跳过", YsmGlibcFix.prepare(noYsm) == null);

            System.setProperty("os.arch", "aarch64");
            check("ysm 非 x86_64 跳过", YsmGlibcFix.prepare(root) == null);

            System.setProperty("os.arch", "amd64");
            YsmGlibcFix.Prepared p = YsmGlibcFix.prepare(root);
            check("ysm glibc 2.17 启用", p != null && "2.17".equals(p.glibcVersion));
            Path so = root.resolve("mcargus/native/libysmboot.so");
            check("ysm 解包 so", Files.isRegularFile(so) && Files.size(so) > 0);
            check("ysm so 路径", p != null && p.soPath.equals(so.toAbsolutePath().toString()));
            check("ysm 资源已打包", YsmGlibcFix.class.getResource("/native/libysmboot.so") != null);

            ProcessBuilder pb = new ProcessBuilder();
            check("ysm 注入 LD_PRELOAD", YsmGlibcFix.apply(pb, root));
            check("ysm LD_PRELOAD 值", p != null && p.soPath.equals(pb.environment().get("LD_PRELOAD")));
            pb.environment().put("LD_PRELOAD", "/opt/existing.so");
            YsmGlibcFix.apply(pb, root);
            check("ysm 保留原 LD_PRELOAD",
                    p != null && (p.soPath + ":/opt/existing.so").equals(pb.environment().get("LD_PRELOAD")));
            check("ysm 不需要时不注入", !YsmGlibcFix.apply(new ProcessBuilder(), noYsm));
        } finally {
            restoreProperty("os.name", osName);
            restoreProperty("os.arch", osArch);
            restoreProperty("mcargus.ysm.glibc", glibc);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void testDashDForward() {
        List<String> f = Main.filterParentArgs(Arrays.asList(
                "-Xmx20480M", "-XX:MaxRAMPercentage=95.0", "-XX:+UseG1GC",
                "-Dterminal.jline=false", "-Dterminal.ansi=true", "-Dfile.encoding=UTF-8",
                "-Dmcargus.secret=1", "-jar", "server.jar"));
        check("转发: -Xmx 保留", f.contains("-Xmx20480M"));
        check("转发: -XX 全保留", f.contains("-XX:MaxRAMPercentage=95.0") && f.contains("-XX:+UseG1GC"));
        check("转发: -D 保留", f.contains("-Dterminal.ansi=true") && f.contains("-Dfile.encoding=UTF-8"));
        check("转发: mcargus 自有 -D 不外传", !f.contains("-Dmcargus.secret=1"));
        check("转发: 非 JVM 参数不外传", !f.contains("-jar") && !f.contains("server.jar"));
    }
}

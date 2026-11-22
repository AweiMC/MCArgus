package com.mcargus;

import com.mcargus.discovery.Discovery;
import com.mcargus.discovery.LaunchTarget;
import com.mcargus.i18n.Messages;
import com.mcargus.install.Installer;
import com.mcargus.launch.JvmLocator;
import com.mcargus.launch.ServerLauncher;
import com.mcargus.mods.ScanManager;
import com.mcargus.util.Log;

import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * MCArgus — Minecraft 服务端智能启动器。
 *
 * 流程：解析 CLI → 加载配置 → 定位 Java → 发现启动目标（缓存优先）→
 * 组装命令 → dry-run 输出或透传运行子进程。
 */
public final class Main {
    public static final String VERSION = "1.3.0";

    private Main() {
    }

    public static void main(String[] rawArgs) {
        int code;
        try {
            setLogDirProperty(rawArgs); // 必须最先执行：在任何 Log 调用（=Logback 初始化）之前确定日志目录
            initLocale(rawArgs);        // 其次：在任何输出（含 --help 与用法错误）之前确定界面语言
            code = run(rawArgs);
        } catch (Errors.UsageException e) {
            Log.error(e.getMessage());
            System.out.println();
            printHelp(System.out);
            code = 2;
        } catch (Errors.PreflightException e) {
            Log.error(e.getMessage());
            code = 3;
        } catch (Throwable t) {
            Log.error(Messages.get("main.unexpectedError"), t);
            code = 1;
        }
        System.exit(code);
    }

    /**
     * 设置日志目录系统属性（logback.xml 引用）。
     * 必须放在 Main.main 里而非 Log 类中：触碰 Log 类的静态方法本身就会触发其静态初始化，
     * 导致 Logback 在属性写入前完成初始化、日志落到错误位置。
     */
    private static void setLogDirProperty(String[] rawArgs) {
        String dir = null;
        for (int i = 0; rawArgs != null && i < rawArgs.length - 1; i++) {
            if ("--dir".equals(rawArgs[i])) {
                dir = rawArgs[i + 1];
                break;
            }
        }
        java.nio.file.Path base = (dir != null ? Paths.get(dir) : Paths.get("")).toAbsolutePath().normalize();
        System.setProperty("MCARGUS_LOG_DIR", base.resolve("mcargus").resolve("logs").toString());
    }

    /**
     * 在任何输出之前从配置文件读取 locale 并初始化界面语言。
     * 只做一次轻量的 Properties 读取（不经 Config.load，避免触发损坏恢复等副作用与日志输出）。
     * 配置文件缺失或 locale 未设置时保持默认中文。
     */
    private static void initLocale(String[] rawArgs) {
        try {
            String dir = null;
            String configOverride = null;
            for (int i = 0; rawArgs != null && i < rawArgs.length - 1; i++) {
                if ("--dir".equals(rawArgs[i])) {
                    dir = rawArgs[i + 1];
                } else if ("--config".equals(rawArgs[i])) {
                    configOverride = rawArgs[i + 1];
                }
            }
            Path configPath;
            if (configOverride != null) {
                configPath = Paths.get(configOverride);
            } else {
                Path base = (dir != null ? Paths.get(dir) : Paths.get("")).toAbsolutePath().normalize();
                configPath = base.resolve("mcargus").resolve("mcargus.properties");
            }
            if (Files.isRegularFile(configPath)) {
                Properties p = new Properties();
                try (InputStreamReader r = new InputStreamReader(
                        Files.newInputStream(configPath), StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                Messages.setLocale(p.getProperty(Config.K_LOCALE));
            }
        } catch (Throwable ignored) {
            // 读取失败一律回退默认语言，绝不阻断启动
        }
    }

    static int run(String[] rawArgs) throws Exception {
        Cli cli = Cli.parse(rawArgs);
        if (cli.help) {
            printHelp(System.out);
            return 0;
        }
        if (cli.versionFlag) {
            System.out.println("MCArgus v" + VERSION);
            return 0;
        }

        Path serverDir = Paths.get(cli.dir != null ? cli.dir : ".").toAbsolutePath().normalize();
        if (!Files.isDirectory(serverDir)) {
            throw new Errors.UsageException(Messages.get("main.serverDirMissing", serverDir));
        }

        Path configPath = cli.configPath != null
                ? Paths.get(cli.configPath)
                : serverDir.resolve("mcargus").resolve("mcargus.properties");

        Config cfg = Config.load(configPath);
        if (cfg.isRecovered()) {
            // 配置损坏恢复：清除一切缓存/扫描状态，本次走完整流程
            Log.warn(Messages.get("main.configRecovered"));
            Discovery.clearCache(cfg);
            cfg.set(Config.K_SCAN_COMPLETED, "false");
            cfg.set(Config.K_SCAN_FINGERPRINT, "");
            cfg.save();
        }
        if (!cfg.exists()) {
            // 首次运行生成带注释的默认配置模板，便于用户后续修改
            cfg.set(Config.K_LOCALE, Messages.DEFAULT_LOCALE);
            cfg.set(Config.K_JVM_FORWARD, "true");
            cfg.set(Config.K_JVM_USERFILE, "true");
            cfg.set(Config.K_PROG_ARGS, "nogui");
            cfg.set(Config.K_MODS_SANITIZE, "true");
            cfg.set(Config.K_YSM_COMPAT, "true");
            cfg.save();
            Log.info(Messages.get("main.configCreated", configPath));
        }
        Log.info(Messages.get("main.banner", VERSION, serverDir));

        if (cli.doctor) {
            Doctor.run(serverDir, cfg, cli);
            return 0;
        }

        // mods 扫描/修复调度：首次或指纹变化→完整扫描+修复；快速校验通过→跳过
        // （--sanitize 单独执行完整扫描后退出；--no-sanitize 跳过；mods.sanitize=false 关闭功能）
        if (cli.sanitizeOnly) {
            ScanManager.ensureScanned(serverDir, cfg, true);
            return 0;
        }
        if (cli.noSanitize) {
            Log.info(Messages.get("main.skipSanitizeFlag"));
        } else if (cfg.getBool(Config.K_MODS_SANITIZE, true)) {
            ScanManager.ensureScanned(serverDir, cfg, cli.rescan);
        } else {
            Log.info(Messages.get("main.sanitizeDisabled"));
        }

        JvmLocator jvm = JvmLocator.resolve(cfg.get(Config.K_JAVA_PATH, ""), cli.javaPath);

        LaunchTarget target;
        try {
            target = Discovery.resolveTarget(serverDir, cfg, cli.rescan,
                    cli.argsFileOverride, selfJarPath(), isWindows());
        } catch (Errors.PreflightException noTarget) {
            // 没有任何启动目标：尝试从服务端压缩包自动安装，然后重新发现
            if (!cfg.getBool(Config.K_INSTALL_ENABLED, true)) {
                throw noTarget;
            }
            Log.info(Messages.get("main.tryInstall"));
            Installer.ensureInstalled(serverDir, cfg);
            target = Discovery.resolveTarget(serverDir, cfg, false,
                    cli.argsFileOverride, selfJarPath(), isWindows());
        }

        List<String> parentArgs = cfg.getBool(Config.K_JVM_FORWARD, true)
                ? filterParentArgs(ManagementFactory.getRuntimeMXBean().getInputArguments())
                : new ArrayList<String>();
        if (!parentArgs.isEmpty()) {
            Log.info(Messages.get("main.forwardedArgs", parentArgs.size(), join(parentArgs)));
        }

        ServerLauncher.Plan plan = ServerLauncher.buildPlan(serverDir, cfg, cli.jvm, cli.programArgs,
                cli.passthrough, target, jvm, parentArgs);

        Log.info(Messages.get("main.target", target.describe()));

        if (cli.dryRun) {
            Log.info(Messages.get("main.dryRunCmd", plan.commandLineForDisplay()));
            Log.info(Messages.get("main.dryRunDir", plan.workDir));
            Log.info(Messages.get("main.dryRunDone"));
            return 0;
        }

        return ServerLauncher.run(plan);
    }

    /**
     * 转发白名单：面板/启动命令给 MCArgus 的内存与 GC 参数原样传给服务端，
     * 其余（-jar、-D、--add-* 等）不转发，避免与参数文件内容冲突。
     */
    public static List<String> filterParentArgs(List<String> input) {
        List<String> out = new ArrayList<String>();
        if (input == null) {
            return out;
        }
        for (String a : input) {
            if (a == null) {
                continue;
            }
            String low = a.toLowerCase();
            boolean keepD = low.startsWith("-d") && !low.startsWith("-dmcargus");
            boolean keep = low.startsWith("-xmx") || low.startsWith("-xms") || low.startsWith("-xss")
                    || low.startsWith("-xmn") || low.startsWith("-xx:") || a.equals("-server") || keepD;
            if (keep && !low.contains("mcargus")) {
                out.add(a);
            }
        }
        return out;
    }

    public static Path selfJarPath() {
        try {
            Path p = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    static void printHelp(java.io.PrintStream out) {
        out.println(Messages.get("help.title", VERSION));
        out.println();
        out.println(Messages.get("help.usage"));
        out.println(Messages.get("help.usageAlt"));
        out.println();
        out.println(Messages.get("help.capabilities"));
        out.println(Messages.get("help.cap1"));
        out.println(Messages.get("help.cap1cont"));
        out.println(Messages.get("help.cap1cont2"));
        out.println(Messages.get("help.cap2"));
        out.println(Messages.get("help.cap2cont"));
        out.println(Messages.get("help.cap2cont2"));
        out.println(Messages.get("help.cap3"));
        out.println(Messages.get("help.cap3cont"));
        out.println();
        out.println(Messages.get("help.options"));
        out.println(Messages.get("help.opt.dir"));
        out.println(Messages.get("help.opt.java"));
        out.println(Messages.get("help.opt.argsFile"));
        out.println(Messages.get("help.opt.jvm"));
        out.println(Messages.get("help.opt.programArgs"));
        out.println(Messages.get("help.opt.rescan"));
        out.println(Messages.get("help.opt.sanitize"));
        out.println(Messages.get("help.opt.noSanitize"));
        out.println(Messages.get("help.opt.dryRun"));
        out.println(Messages.get("help.opt.doctor"));
        out.println(Messages.get("help.opt.config"));
        out.println(Messages.get("help.opt.versionHelp"));
        out.println();
        out.println(Messages.get("help.exitCodes"));
        out.println();
        out.println(Messages.get("help.logs"));
    }

    static final class Cli {
        String dir, javaPath, jvm, programArgs, configPath;
        java.nio.file.Path argsFileOverride;
        boolean rescan, dryRun, doctor, sanitizeOnly, noSanitize, help, versionFlag;
        final List<String> passthrough = new ArrayList<String>();

        static Cli parse(String[] a) {
            Cli c = new Cli();
            label:
            for (int i = 0; i < a.length; i++) {
                String s = a[i];
                switch (s) {
                    case "--":
                        c.passthrough.addAll(Arrays.asList(a).subList(i + 1, a.length));
                        break label;
                    case "--help":
                    case "-h":
                        c.help = true;
                        break;
                    case "--version":
                    case "-v":
                        c.versionFlag = true;
                        break;
                    case "--doctor":
                        c.doctor = true;
                        break;
                    case "--dry-run":
                        c.dryRun = true;
                        break;
                    case "--rescan":
                        c.rescan = true;
                        break;
                    case "--sanitize":
                        c.sanitizeOnly = true;
                        break;
                    case "--no-sanitize":
                        c.noSanitize = true;
                        break;
                    case "--dir":
                        c.dir = needValue(a, ++i, s);
                        break;
                    case "--java":
                        c.javaPath = needValue(a, ++i, s);
                        break;
                    case "--jvm":
                        c.jvm = needValue(a, ++i, s);
                        break;
                    case "--program-args":
                        c.programArgs = needValue(a, ++i, s);
                        break;
                    case "--config":
                        c.configPath = needValue(a, ++i, s);
                        break;
                    case "--args-file":
                        c.argsFileOverride = Paths.get(needValue(a, ++i, s));
                        break;
                    default:
                        throw new Errors.UsageException(Messages.get("cli.unknownArg", s));
                }
            }
            if (c.sanitizeOnly && c.noSanitize) {
                throw new Errors.UsageException(Messages.get("cli.conflictSanitize"));
            }
            return c;
        }

        private static String needValue(String[] a, int idx, String opt) {
            if (idx >= a.length) {
                throw new Errors.UsageException(Messages.get("cli.needValue", opt));
            }
            return a[idx];
        }
    }
}

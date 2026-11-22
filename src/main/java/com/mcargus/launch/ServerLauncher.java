package com.mcargus.launch;

import com.mcargus.Config;
import com.mcargus.discovery.LaunchTarget;
import com.mcargus.i18n.Messages;
import com.mcargus.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 组装并运行最终启动命令。
 *
 * 命令结构：java <jvm参数> @参数文件 <程序参数>（或 -jar 服务端 Jar）
 * 始终使用系统原生 Java 与宿主动态链接器启动，不替换、不干预宿主机的 libc。
 *
 * 控制台输入输出使用 inheritIO 完全透传：Pterodactyl 面板向 stdin 发送的 stop 命令
 * 会直接到达服务端进程；MCArgus 自身收到 SIGTERM 时通过 shutdown hook 向服务端转发停止信号。
 */
public final class ServerLauncher {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public static final class Plan {
        public final List<String> command;
        public final List<String> jvmArgs;
        public final List<String> programArgs;
        public final LaunchTarget target;
        public final String javaExecutable;
        public final Path workDir;
        public final boolean ysmCompat;

        public Plan(List<String> command, List<String> jvmArgs, List<String> programArgs,
                    LaunchTarget target, String javaExecutable, Path workDir, boolean ysmCompat) {
            this.command = command;
            this.jvmArgs = jvmArgs;
            this.programArgs = programArgs;
            this.target = target;
            this.javaExecutable = javaExecutable;
            this.workDir = workDir;
            this.ysmCompat = ysmCompat;
        }

        public String commandLineForDisplay() {
            StringBuilder sb = new StringBuilder();
            for (String s : command) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                if (s.indexOf(' ') >= 0) {
                    sb.append('"').append(s).append('"');
                } else {
                    sb.append(s);
                }
            }
            return sb.toString();
        }
    }

    private ServerLauncher() {
    }

    public static Plan buildPlan(Path serverDir, Config cfg, String cliJvm, String cliProgramArgs,
                                 List<String> cliPassthrough, LaunchTarget target, JvmLocator jvm,
                                 List<String> forwardedParentArgs) throws IOException {
        List<String> jvmArgs = new ArrayList<String>(forwardedParentArgs);

        if (cfg.getBool(Config.K_JVM_USERFILE, true)) {
            List<String> fromFile = readUserJvmArgs(serverDir);
            if (!fromFile.isEmpty()) {
                Log.info(Messages.get("launch.loadedUserJvmArgs", fromFile.size()));
                jvmArgs.addAll(fromFile);
            }
        }

        String extra = cliJvm != null ? cliJvm : cfg.get(Config.K_JVM_EXTRA, "");
        jvmArgs.addAll(splitArgs(extra));

        List<String> progArgs = new ArrayList<String>();
        String pa = cliProgramArgs != null ? cliProgramArgs : cfg.get(Config.K_PROG_ARGS, "nogui");
        progArgs.addAll(splitArgs(pa));
        if (cliPassthrough != null) {
            progArgs.addAll(cliPassthrough);
        }

        List<String> cmd = new ArrayList<String>();
        cmd.add(jvm.executable());
        cmd.addAll(jvmArgs);
        if (target.mode == LaunchTarget.Mode.ARGS_FILE) {
            // java 启动器原生支持 @argfile，与官方 run.sh/run.bat 的行为完全一致
            cmd.add("@" + target.file.toAbsolutePath());
        } else {
            cmd.add("-jar");
            cmd.add(target.file.toAbsolutePath().toString());
        }
        cmd.addAll(progArgs);

        return new Plan(cmd, jvmArgs, progArgs, target, jvm.executable(), serverDir,
                cfg.getBool(Config.K_YSM_COMPAT, true));
    }

    public static int run(Plan plan) throws IOException, InterruptedException {
        Log.info(Messages.get("launch.command", plan.commandLineForDisplay()));
        Log.info(Messages.get("launch.workDir", plan.workDir));
        Log.info(Messages.get("launch.ioNote"));

        ProcessBuilder pb = new ProcessBuilder(plan.command);
        pb.directory(plan.workDir.toFile());

        // YSM + glibc < 2.18：把兼容层挂到子进程的 LD_PRELOAD 上。
        // 只影响这个子进程，不改系统库、不改 mods/ 里的任何文件。
        if (plan.ysmCompat) {
            YsmGlibcFix.apply(pb, plan.workDir);
        }

        // 子进程直接继承 MCArgus 的 stdin/stdout/stderr：
        //  - 面板控制台输入（Pterodactyl 发送的 stop 等指令）直达服务端；
        //  - 服务端 stdout/stderr 原样出现在面板控制台；
        //  - 无中间缓冲线程，不存在消息丢失或乱序风险。
        pb.inheritIO();
        final Process proc = pb.start();

        Thread hook = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.warn(Messages.get("launch.shutdownSignal"));
                shutdownChild(proc);
            }
        }, "mcargus-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        int code = proc.waitFor(); // MCArgus 自身始终等待子进程退出后才结束
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // 正处于关闭流程中，无需移除
        }
        Log.info(Messages.get("launch.serverExited", code));
        if (code != 0) {
            Log.warn(Messages.get("launch.nonZeroHint"));
        }
        return code;
    }

    /**
     * 停止子进程：先温和 destroy（Linux 下为 SIGTERM，Minecraft 会走存盘流程），
     * 最多等待 timeoutMillis，仍未退出则 destroyForcibly。
     * 返回子进程是否在超时内退出。供关闭钩子与自测试复用。
     */
    public static boolean shutdownChild(Process proc) {
        return shutdownChild(proc, 10, TimeUnit.SECONDS);
    }

    public static boolean shutdownChild(Process proc, long timeout, TimeUnit unit) {
        proc.destroy();
        try {
            if (proc.waitFor(timeout, unit)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Log.warn(Messages.get("launch.forceKill"));
        proc.destroyForcibly();
        try {
            proc.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /** 解析 user_jvm_args.txt：每行一个参数，跳过空行与 # 注释。 */
    public static List<String> readUserJvmArgs(Path serverDir) throws IOException {
        Path f = serverDir.resolve("user_jvm_args.txt");
        List<String> out = new ArrayList<String>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f.toFile()), StandardCharsets.UTF_8));
        try {
            String ln;
            while ((ln = r.readLine()) != null) {
                ln = ln.trim();
                if (ln.isEmpty() || ln.startsWith("#")) {
                    continue;
                }
                out.add(ln);
            }
        } finally {
            r.close();
        }
        return out;
    }

    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) {
            return out;
        }
        for (String t : WHITESPACE.split(s.trim())) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}

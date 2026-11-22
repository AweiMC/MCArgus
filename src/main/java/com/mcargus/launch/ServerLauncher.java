package com.mcargus.launch;

import com.mcargus.Config;
import com.mcargus.discovery.LaunchTarget;
import com.mcargus.i18n.Messages;
import com.mcargus.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
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
 * 默认使用 inheritIO 完全透传；开启 console.shell 后改为接管 stdin：
 * shell 前缀行本地执行，控制前缀行走 MCArgus 内部功能，其余输入原样转发给服务端。
 * MCArgus 自身收到 SIGTERM 时通过 shutdown hook 向服务端转发停止信号。
 * {@code @restart} 会在当前进程退出后由同一 MCArgus 进程重新启动服务端。
 */
public final class ServerLauncher {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** @restart 时重新发现启动目标并构建新的启动计划。 */
    public interface PlanFactory {
        Plan create() throws Exception;
    }

    public static final class Plan {
        public final List<String> command;
        public final List<String> jvmArgs;
        public final List<String> programArgs;
        public final LaunchTarget target;
        public final String javaExecutable;
        public final Path workDir;
        public final boolean ysmCompat;
        public final boolean consoleShell;
        public final String consolePrefix;
        public final boolean consoleAdmin;
        public final String consoleAdminPrefix;

        public Plan(List<String> command, List<String> jvmArgs, List<String> programArgs,
                    LaunchTarget target, String javaExecutable, Path workDir, boolean ysmCompat,
                    boolean consoleShell, String consolePrefix,
                    boolean consoleAdmin, String consoleAdminPrefix) {
            this.command = command;
            this.jvmArgs = jvmArgs;
            this.programArgs = programArgs;
            this.target = target;
            this.javaExecutable = javaExecutable;
            this.workDir = workDir;
            this.ysmCompat = ysmCompat;
            this.consoleShell = consoleShell;
            this.consolePrefix = consolePrefix;
            this.consoleAdmin = consoleAdmin;
            this.consoleAdminPrefix = consoleAdminPrefix;
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
                cfg.getBool(Config.K_YSM_COMPAT, true),
                cfg.getBool(Config.K_CONSOLE_SHELL, true),
                cfg.get(Config.K_CONSOLE_PREFIX, "!"),
                cfg.getBool(Config.K_CONSOLE_ADMIN, true),
                cfg.get(Config.K_CONSOLE_ADMIN_PREFIX, "@"));
    }

    public static int run(Plan plan) throws IOException, InterruptedException {
        return run(plan, null);
    }

    /**
     * @param factory 重启时重新解析启动目标/Java 并构建新 Plan；为 null 时 @restart 不可用。
     *                安装 Forge/NeoForge 后必须重新发现 args 文件，不能复用旧命令。
     */
    public static int run(Plan plan, PlanFactory factory) throws IOException, InterruptedException {
        // stdin 读取线程必须贯穿整个 MCArgus 生命周期：
        // @restart 只切换它绑定的服务端进程，不能每次重启都新建一个线程抢读 System.in。
        ConsoleInput input = plan.consoleShell ? new ConsoleInput(System.in) : null;
        Plan current = plan;

        while (true) {
            Log.info(Messages.get("launch.command", current.commandLineForDisplay()));
            Log.info(Messages.get("launch.workDir", current.workDir));
            if (current.consoleShell) {
                Log.info(Messages.get("launch.ioNoteConsole"));
            } else {
                Log.info(Messages.get("launch.ioNote"));
            }

            ProcessBuilder pb = new ProcessBuilder(current.command);
            pb.directory(current.workDir.toFile());

            // YSM + glibc < 2.18：把兼容层挂到子进程的 LD_PRELOAD 上。
            // 只影响这个子进程，不改系统库、不改 mods/ 里的任何文件。
            if (current.ysmCompat) {
                YsmGlibcFix.apply(pb, current.workDir);
            }

            // 两条 IO 路径：
            //  1) 关闭 console.shell（或非面板环境）时用 inheritIO 完全透传，零中间线程；
            //  2) 开启时由 MCArgus 接管 stdin，shell/控制前缀本地处理，其余原样转发。
            //     输出仍按字节流实时回显，ANSI 颜色与进度条不受影响。
            final Process proc;
            Thread tOut = null;
            Thread tErr = null;
            ProcessControl control = null;
            ConsoleRouter router = null;
            if (current.consoleShell) {
                proc = pb.start();
                control = new ProcessControl(proc);
                ConsoleShell shell = new ConsoleShell(current.workDir, current.consolePrefix, System.out);
                ConsoleAdmin admin = null;
                if (current.consoleAdmin) {
                    admin = new ConsoleAdmin(current.workDir, current.consoleAdminPrefix, System.out,
                            current.javaExecutable, control, shell);
                    Log.info(Messages.get("console.admin.enabled", admin.prefix()));
                    if (admin.prefix().equals(shell.prefix())) {
                        Log.warn(Messages.get("console.admin.prefixConflict"));
                    }
                }
                router = new ConsoleRouter(shell, admin);
                Log.info(Messages.get("console.shell.enabled", shell.prefix()));
                tOut = pumpStream(proc.getInputStream(), System.out, "mcargus-stdout");
                tErr = pumpStream(proc.getErrorStream(), System.err, "mcargus-stderr");
                input.bind(control, router);
            } else {
                pb.inheritIO();
                proc = pb.start();
            }

            final ConsoleRouter currentRouter = router;
            Thread hook = new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.warn(Messages.get("launch.shutdownSignal"));
                    if (currentRouter != null) {
                        currentRouter.cancelAll();
                    }
                    shutdownChild(proc);
                }
            }, "mcargus-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);

            int code = proc.waitFor(); // MCArgus 自身始终等待子进程退出后才结束
            if (input != null) {
                input.unbind();
            }
            // 给回显线程一点时间把最后几行日志刷出去，避免面板看不到退出原因
            if (tOut != null) {
                tOut.join(2000);
            }
            if (tErr != null) {
                tErr.join(2000);
            }
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // 正处于关闭流程中，无需移除
            }

            if (control != null && control.isRestartRequested()) {
                Log.info(Messages.get("console.admin.restartingProcess"));
                if (factory == null) {
                    Log.warn(Messages.get("console.admin.restartUnavailable"));
                    return code;
                }
                try {
                    current = factory.create();
                } catch (Exception e) {
                    Log.warn(Messages.get("console.admin.restartFailed", String.valueOf(e)));
                    return 1;
                }
                continue;
            }
            Log.info(Messages.get("launch.serverExited", code));
            if (code != 0) {
                Log.warn(Messages.get("launch.nonZeroHint"));
            }
            return code;
        }
    }

    /**
     * 当前服务端子进程的控制实现：同步写 stdin，并在重启/停止时先发 MC 的 stop，
     * 30 秒仍未退出则强制终止，避免面板永久卡在“停止中”。
     */
    private static final class ProcessControl implements ServerControl {
        private final Process process;
        private final OutputStream stdin;
        private final long startedAt = System.currentTimeMillis();
        private volatile boolean restartRequested;
        private volatile boolean stopRequested;

        ProcessControl(Process process) {
            this.process = process;
            this.stdin = process.getOutputStream();
        }

        @Override
        public synchronized void send(String line) throws IOException {
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        @Override
        public void requestRestart() {
            if (restartRequested) {
                return;
            }
            restartRequested = true;
            stopWithWatchdog();
        }

        @Override
        public void requestStop() {
            if (stopRequested) {
                return;
            }
            stopRequested = true;
            stopWithWatchdog();
        }

        private void stopWithWatchdog() {
            try {
                send("stop");
            } catch (IOException e) {
                process.destroy();
            }
            Thread watchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!process.waitFor(30, TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "mcargus-stop-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }

        @Override
        public boolean isRestartRequested() {
            return restartRequested;
        }

        @Override
        public boolean isStopRequested() {
            return stopRequested;
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public long uptimeSeconds() {
            return isAlive() ? (System.currentTimeMillis() - startedAt) / 1000 : 0;
        }
    }

    /**
     * 把子进程的输出按字节流实时搬到面板控制台。
     * 不按行读取，避免破坏 ANSI 颜色、\r 进度条与超长行。
     */
    private static Thread pumpStream(final InputStream in, final PrintStream out, String name) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // 子进程退出或管道关闭
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
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

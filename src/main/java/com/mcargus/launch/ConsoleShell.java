package com.mcargus.launch;

import com.mcargus.i18n.Messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 面板控制台命令通道。
 *
 * <p>Pterodactyl 一类的面板只能把文本写进进程 stdin，没有办法执行 shell。
 * 服务端跑在容器里、只给 SFTP 时，查进程、看磁盘、批量下载模组都很别扭。
 *
 * <p>这里拦截以 {@code !} 开头的一行，交给 {@code /bin/sh -c} 在服务端目录下执行，
 * 输出直接写回 stdout（也就是面板控制台）；其余输入原样转发给 Minecraft。
 * 不监听任何端口，不引入额外依赖，命令以容器内运行用户身份执行。
 *
 * <p>命令在单独的守护线程里执行，读取 stdin 的线程永远不被占住：
 * 面板发出 stop 时可以先 {@link #cancel()} 掉正在跑的命令，再转发停止指令，
 * 不会出现「wget 没跑完，stop 送不进去」导致面板超时强杀的情况。
 */
public final class ConsoleShell {

    /** 单条命令最多回显 4 MiB，超出部分继续读取但丢弃，避免刷爆面板。 */
    private static final int MAX_OUTPUT_BYTES = 4 << 20;
    /** 单条命令最长执行时间。下载大模组够用，又不至于把守护线程永久占死。 */
    private static final long TIMEOUT_SECONDS = 600;

    private final Path serverDir;
    private final String prefix;
    private final PrintStream out;
    private final ExecutorService worker;
    private volatile Path cwd;
    private volatile Process running;

    public ConsoleShell(Path serverDir, String prefix, PrintStream out) {
        this.serverDir = serverDir.toAbsolutePath().normalize();
        this.cwd = this.serverDir;
        String p = prefix == null ? "" : prefix.trim();
        this.prefix = p.isEmpty() ? "!" : p;
        this.out = out;
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mcargus-shell");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public String prefix() {
        return prefix;
    }

    /** 该行是否属于命令通道。 */
    public boolean isCommand(String line) {
        return line != null && line.startsWith(prefix);
    }

    /** 执行一行命令。调用方需先用 {@link #isCommand(String)} 判断。 */
    public void handle(String line) {
        String raw = line.substring(prefix.length()).trim();
        if (raw.isEmpty() || raw.equals("help") || raw.equals("?")) {
            printHelp();
            return;
        }
        if (raw.equals("pwd")) {
            println(cwd.toString());
            return;
        }
        if (raw.equals("cd")) {
            cwd = serverDir;
            println(cwd.toString());
            return;
        }
        if (raw.startsWith("cd ")) {
            changeDir(raw.substring(3).trim());
            return;
        }
        println("[sh] " + raw);
        final String cmd = raw;
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    ConsoleShell.this.run(cmd);
                }
            });
        } catch (RejectedExecutionException e) {
            println("[sh] " + Messages.get("console.shell.rejected"));
        }
    }

    /**
     * 中止当前正在执行的命令。面板发来 stop 等真实服务端指令时调用，
     * 保证停止信号不会被一条长命令堵住。
     */
    public void cancel() {
        Process p = running;
        if (p != null) {
            p.destroyForcibly();
        }
    }

    private void changeDir(String arg) {
        Path target;
        try {
            Path p = java.nio.file.Paths.get(arg);
            target = p.isAbsolute() ? p : cwd.resolve(p);
            target = target.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            println("[cd] " + e.getMessage());
            return;
        }
        if (!Files.isDirectory(target)) {
            println("[cd] " + Messages.get("console.shell.notDir", target.toString()));
            return;
        }
        cwd = target;
        println(cwd.toString());
    }

    private void run(String cmd) {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = null;
        try {
            p = pb.start();
            running = p;
            pump(p.getInputStream());
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                println("[sh] " + Messages.get("console.shell.timeout", TIMEOUT_SECONDS));
            }
            println("[exit " + p.exitValue() + "]");
        } catch (IOException e) {
            println("[sh] " + Messages.get("console.shell.failed", String.valueOf(e.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
        } finally {
            if (running == p) {
                running = null;
            }
        }
    }

    private void pump(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            if (total < MAX_OUTPUT_BYTES) {
                int keep = Math.min(n, MAX_OUTPUT_BYTES - total);
                write(buf, keep);
                total += keep;
            }
        }
    }

    private void write(byte[] buf, int len) {
        out.write(buf, 0, len);
        out.flush();
    }

    private void println(String s) {
        out.println(s);
        out.flush();
    }

    private void printHelp() {
        String[] lines = Messages.get("console.shell.help", prefix).split("\\n");
        for (String s : lines) {
            println(s);
        }
    }
}

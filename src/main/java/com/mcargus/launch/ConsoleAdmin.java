package com.mcargus.launch;

import com.mcargus.i18n.Messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * MCArgus 内部控制命令（默认前缀 {@code @}）。
 *
 * <p>与 {@link ConsoleShell} 的 shell 通道分开：这里执行的是 MCArgus 自己的能力，
 * 例如重启服务端、下载文件、调用 Forge/NeoForge 安装器。命令同样在独立守护线程执行，
 * 不阻塞读取 stdin 的线程，面板发来的 stop 仍能立即送达服务端。</p>
 */
public final class ConsoleAdmin {

    private static final int MAX_OUTPUT_BYTES = 4 << 20;
    private static final long INSTALL_TIMEOUT_SECONDS = 1800;

    private final Path serverDir;
    private final String prefix;
    private final PrintStream out;
    private final String javaExecutable;
    private final ServerControl control;
    private final ConsoleShell shell;
    private final String shellPrefix;
    private final ExecutorService worker;

    private volatile Process jobProcess;
    private volatile HttpURLConnection downloadConnection;
    private volatile boolean cancelRequested;
    private volatile boolean jobRunning;
    private volatile boolean menuOpen;

    public ConsoleAdmin(Path serverDir, String prefix, PrintStream out, String javaExecutable,
                        ServerControl control, ConsoleShell shell) {
        this.serverDir = serverDir.toAbsolutePath().normalize();
        String p = prefix == null ? "" : prefix.trim();
        this.prefix = p.isEmpty() ? "@" : p;
        this.out = out;
        this.javaExecutable = (javaExecutable == null || javaExecutable.trim().isEmpty())
                ? "java" : javaExecutable;
        this.control = control;
        this.shell = shell;
        this.shellPrefix = shell.prefix();
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mcargus-admin");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public String prefix() {
        return prefix;
    }

    public boolean isCommand(String line) {
        return line != null && line.startsWith(prefix);
    }

    public boolean isCapturingInput() {
        return menuOpen;
    }

    public boolean isMenuChoice(String line) {
        return menuOpen && isDigits(line == null ? "" : line.trim());
    }

    /** 处理一行控制命令（调用方需先用 {@link #isCommand(String)} 判断）。 */
    public void handle(String line) {
        menuOpen = false;
        String raw = line.substring(prefix.length()).trim();
        if (raw.isEmpty() || raw.equals("help") || raw.equals("?")) {
            printMultiline(Messages.get("console.admin.help", prefix, shellPrefix));
            return;
        }
        if (raw.equals("status")) {
            printStatus();
            return;
        }
        if (raw.equals("restart") || raw.equals("reboot")) {
            restart();
            return;
        }
        if (raw.equals("stop")) {
            stop();
            return;
        }
        if (raw.equals("menu") || raw.equals("tui")) {
            openMenu();
            return;
        }
        if (raw.equals("download")) {
            println(Messages.get("console.admin.argMissing", prefix + "download <url> [path]"));
            return;
        }
        if (raw.startsWith("download ")) {
            submitDownload(raw.substring("download ".length()).trim());
            return;
        }
        if (raw.equals("installforge") || raw.startsWith("installforge ")) {
            submitInstall("Forge", raw.substring("installforge".length()).trim(), "installforge");
            return;
        }
        if (raw.equals("installneoforge") || raw.startsWith("installneoforge ")) {
            submitInstall("NeoForge", raw.substring("installneoforge".length()).trim(), "installneoforge");
            return;
        }
        println(Messages.get("console.admin.invalid", raw, prefix));
    }

    /** 菜单模式下处理数字选择；非数字输入由 ConsoleInput 退出菜单并转发给服务端。 */
    public void handleMenuInput(String line) {
        String s = line == null ? "" : line.trim();
        if (!isDigits(s)) {
            println(Messages.get("console.admin.menuInvalid"));
            printMenu();
            return;
        }
        int choice = Integer.parseInt(s);
        switch (choice) {
            case 0:
                menuOpen = false;
                println(Messages.get("console.admin.menuExit"));
                break;
            case 1:
                printStatus();
                printMenu();
                break;
            case 2:
                menuOpen = false;
                restart();
                break;
            case 3:
                menuOpen = false;
                stop();
                break;
            case 4:
            case 5:
                println(Messages.get("console.admin.menuInstallHint", prefix));
                printMenu();
                break;
            case 6:
                println(Messages.get("console.admin.menuDownloadHint", prefix));
                printMenu();
                break;
            default:
                println(Messages.get("console.admin.menuInvalid"));
                printMenu();
                break;
        }
    }

    public void closeMenu() {
        menuOpen = false;
    }

    /** 面板发来 stop / @stop / @restart 时中止正在执行的下载或安装。 */
    public void cancelJob() {
        cancelRequested = true;
        Process p = jobProcess;
        if (p != null) {
            p.destroyForcibly();
        }
        HttpURLConnection c = downloadConnection;
        if (c != null) {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- 控制命令

    private void restart() {
        cancelJob();
        shell.cancel();
        println(Messages.get("console.admin.restart"));
        control.requestRestart();
    }

    private void stop() {
        cancelJob();
        shell.cancel();
        println(Messages.get("console.admin.stop"));
        control.requestStop();
    }

    private void printStatus() {
        String version = ConsoleAdmin.class.getPackage() == null
                ? null : ConsoleAdmin.class.getPackage().getImplementationVersion();
        if (version == null || version.trim().isEmpty()) {
            version = Messages.get("console.admin.dev");
        }
        String running = control.isAlive()
                ? Messages.get("console.admin.running") : Messages.get("console.admin.notRunning");
        println(Messages.get("console.admin.status", version, serverDir, javaExecutable,
                running, control.uptimeSeconds(), shellPrefix, prefix));
    }

    private void openMenu() {
        menuOpen = true;
        printMenu();
    }

    private void printMenu() {
        println(Messages.get("console.admin.menu", prefix));
        print(Messages.get("console.admin.menuPrompt"));
    }

    // ---------------------------------------------------------------- 下载

    private void submitDownload(final String args) {
        int sp = args.indexOf(' ');
        final String url = sp < 0 ? args : args.substring(0, sp).trim();
        final String dest = sp < 0 ? null : args.substring(sp + 1).trim();
        if (url.isEmpty()) {
            println(Messages.get("console.admin.argMissing", prefix + "download <url> [path]"));
            return;
        }
        submitJob(new Runnable() {
            @Override
            public void run() {
                download(url, dest);
            }
        });
    }

    private Path download(String urlText, String destArg) {
        URL url;
        try {
            url = new URL(urlText);
        } catch (Exception e) {
            println(Messages.get("console.admin.downloadFailed", String.valueOf(e.getMessage())));
            return null;
        }
        String proto = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);
        if (!proto.equals("http") && !proto.equals("https")) {
            println(Messages.get("console.admin.badUrl", urlText));
            return null;
        }
        Path dest;
        if (destArg == null || destArg.isEmpty()) {
            dest = serverDir.resolve(fileNameFromUrl(url));
        } else {
            dest = resolveInside(destArg);
            if (dest == null) {
                return null;
            }
        }
        dest = dest.toAbsolutePath().normalize();
        if (!dest.startsWith(serverDir) || dest.equals(serverDir)) {
            println(Messages.get("console.admin.pathOutside", dest));
            return null;
        }
        println(Messages.get("console.admin.downloadStart", urlText));
        Path tmp = null;
        HttpURLConnection conn = null;
        try {
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            tmp = Files.createTempFile(dest.getParent(), "mcargus-download-", ".part");
            conn = (HttpURLConnection) url.openConnection();
            downloadConnection = conn;
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "MCArgus/console");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            long total = conn.getContentLengthLong();
            byte[] buf = new byte[65536];
            long done = 0;
            long nextReport = 0;
            try (InputStream in = conn.getInputStream();
                 OutputStream os = Files.newOutputStream(tmp)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelRequested) {
                        throw new IOException("cancelled");
                    }
                    os.write(buf, 0, n);
                    done += n;
                    if (done >= nextReport) {
                        println(Messages.get("console.admin.downloadProgress",
                                formatBytes(done), total > 0 ? formatBytes(total) : "?"));
                        nextReport += 1 << 20;
                    }
                }
            }
            move(tmp, dest);
            println(Messages.get("console.admin.downloadDone", dest, done));
            return dest;
        } catch (Exception e) {
            if (cancelRequested) {
                println(Messages.get("console.admin.downloadCancelled"));
            } else {
                println(Messages.get("console.admin.downloadFailed", String.valueOf(e.getMessage())));
            }
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            downloadConnection = null;
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    private static String fileNameFromUrl(URL url) {
        String path = url.getPath() == null ? "" : url.getPath();
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        if (name.isEmpty()) {
            name = "download.bin";
        }
        try {
            name = URLDecoder.decode(name, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            // 保留原始名称
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "download.bin";
        }
        return name;
    }

    private static void move(Path tmp, Path dest) throws IOException {
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------------------------------------------------------------- 安装器

    private void submitInstall(final String kind, final String args, final String command) {
        if (args.isEmpty()) {
            println(Messages.get("console.admin.argMissing", prefix + command + " <jar|url>"));
            return;
        }
        submitJob(new Runnable() {
            @Override
            public void run() {
                install(kind, args);
            }
        });
    }

    private void install(String kind, String arg) {
        Path jar;
        if (arg.startsWith("http://") || arg.startsWith("https://")) {
            jar = download(arg, null);
            if (jar == null) {
                return;
            }
        } else {
            jar = resolveInside(arg);
            if (jar == null) {
                return;
            }
        }
        if (!Files.isRegularFile(jar)) {
            println(Messages.get("console.admin.installMissing", jar));
            return;
        }
        println(Messages.get("console.admin.installStart", kind, jar));
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(javaExecutable, "-jar", jar.toString(), "--installServer");
            pb.directory(serverDir.toFile());
            pb.redirectErrorStream(true);
            p = pb.start();
            jobProcess = p;
            // 安装器不需要交互输入；显式关闭 stdin，避免它在异常情况下等待输入直到超时
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
                // ignore
            }
            pump(p.getInputStream());
            if (cancelRequested) {
                p.destroyForcibly();
            }
            if (!p.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                println(Messages.get("console.admin.installTimeout", INSTALL_TIMEOUT_SECONDS));
                return;
            }
            if (cancelRequested) {
                println(Messages.get("console.admin.cancelled"));
                return;
            }
            int code = p.exitValue();
            if (code == 0) {
                println(Messages.get("console.admin.installDone", code, prefix));
            } else {
                println(Messages.get("console.admin.installFailed", code));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            println(Messages.get("console.admin.failed", String.valueOf(e)));
        } finally {
            if (jobProcess == p) {
                jobProcess = null;
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private void submitJob(final Runnable task) {
        if (jobRunning) {
            println(Messages.get("console.admin.busy"));
            return;
        }
        jobRunning = true;
        cancelRequested = false;
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        println(Messages.get("console.admin.failed", String.valueOf(t)));
                    } finally {
                        jobRunning = false;
                        jobProcess = null;
                        downloadConnection = null;
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            jobRunning = false;
            println(Messages.get("console.admin.rejected"));
        }
    }

    private Path resolveInside(String s) {
        try {
            Path p = Paths.get(s);
            Path abs = (p.isAbsolute() ? p : serverDir.resolve(p)).toAbsolutePath().normalize();
            if (!abs.startsWith(serverDir)) {
                println(Messages.get("console.admin.pathOutside", s));
                return null;
            }
            return abs;
        } catch (RuntimeException e) {
            println(Messages.get("console.admin.pathOutside", s));
            return null;
        }
    }

    private void pump(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            if (cancelRequested) {
                return;
            }
            if (total < MAX_OUTPUT_BYTES) {
                int keep = Math.min(n, MAX_OUTPUT_BYTES - total);
                out.write(buf, 0, keep);
                out.flush();
                total += keep;
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private void printMultiline(String s) {
        String[] lines = s.split("\\n");
        for (String line : lines) {
            println(line);
        }
    }

    private void print(String s) {
        out.print(s);
        out.flush();
    }

    private void println(String s) {
        out.println(s);
        out.flush();
    }
}

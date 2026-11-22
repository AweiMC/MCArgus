package com.mcargus.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 短命外部命令执行（用于探测环境：getconf / ldd / java -version / bundle libc 版本横幅）。
 * 永不抛异常，失败返回 null；输出在守护线程中消费，超时强制销毁进程。
 */
public final class Exec {
    private Exec() {
    }

    public static String run(long timeoutMs, String... cmd) {
        final StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            Thread reader = getThread(p, sb);

            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            reader.join(1000);
            if (p.exitValue() != 0) {
                return null;
            }
            synchronized (sb) {
                return sb.toString();
            }
        } catch (Exception e) {
            if (p != null) {
                p.destroyForcibly();
            }
            return null;
        }
    }

    private static Thread getThread(Process p, StringBuilder sb) {
        final Process proc = p;
        Thread reader = new Thread(() -> {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                String ln;
                while ((ln = r.readLine()) != null) {
                    synchronized (sb) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(ln);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (r != null) {
                    try {
                        r.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "mcargus-exec-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }
}

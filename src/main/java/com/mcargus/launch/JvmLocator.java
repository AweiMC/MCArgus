package com.mcargus.launch;

import com.mcargus.Errors;
import com.mcargus.i18n.Messages;
import com.mcargus.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 解析用于启动服务端的 Java 可执行文件。
 * 优先级：CLI/配置 &gt; 当前 JVM 自身（java.home，Pterodactyl egg 保证可用）&gt; JAVA_HOME &gt; PATH 中的 java。
 */
public final class JvmLocator {
    private final Path javaFile; // null = 使用 PATH 中的 java

    private JvmLocator(Path javaFile) {
        this.javaFile = javaFile;
    }

    public String executable() {
        return javaFile == null ? "java" : javaFile.toString();
    }

    public String display() {
        return javaFile == null ? Messages.get("jvm.fromPath") : javaFile.toString();
    }

    public static JvmLocator resolve(String configValue, String cliValue) {
        String v = cliValue != null && !cliValue.trim().isEmpty()
                ? cliValue.trim()
                : (configValue == null ? "" : configValue.trim());
        if (!v.isEmpty()) {
            Path p = Paths.get(v);
            if (!Files.isRegularFile(p)) {
                Path binJava = p.resolve("bin").resolve(javaBin());
                if (Files.isRegularFile(binJava)) {
                    p = binJava; // 允许传 JAVA_HOME 风格的目录
                } else {
                    throw new Errors.PreflightException(Messages.get("jvm.notFound", v));
                }
            }
            Log.info(Messages.get("jvm.usingConfigured", p.toAbsolutePath()));
            return new JvmLocator(p.toAbsolutePath().normalize());
        }

        String jh = System.getProperty("java.home");
        if (jh != null && !jh.trim().isEmpty()) {
            Path p = Paths.get(jh, "bin", javaBin());
            if (Files.isRegularFile(p)) {
                return new JvmLocator(p.toAbsolutePath().normalize());
            }
        }
        String env = System.getenv("JAVA_HOME");
        if (env != null && !env.trim().isEmpty()) {
            Path p = Paths.get(env.trim(), "bin", javaBin());
            if (Files.isRegularFile(p)) {
                return new JvmLocator(p.toAbsolutePath().normalize());
            }
        }
        Log.warn(Messages.get("jvm.fallbackPath"));
        return new JvmLocator(null);
    }

    private static String javaBin() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
    }
}

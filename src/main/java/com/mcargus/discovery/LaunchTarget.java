package com.mcargus.discovery;

import com.mcargus.i18n.Messages;

import java.nio.file.Path;

/** 解析出的启动目标：优先参数文件模式（Forge/NeoForge），退化为 Jar 模式（Fabric/vanilla 等）。 */
public final class LaunchTarget {
    public enum Mode { ARGS_FILE, JAR }

    public final Mode mode;
    public final Path file;
    public final LoaderType loaderType;   // ARGS_FILE 模式有效
    public final String loaderVersion;    // 可能为 null
    public final String jarKind;          // JAR 模式有效

    private LaunchTarget(Mode mode, Path file, LoaderType loaderType, String loaderVersion, String jarKind) {
        this.mode = mode;
        this.file = file;
        this.loaderType = loaderType;
        this.loaderVersion = loaderVersion;
        this.jarKind = jarKind;
    }

    public static LaunchTarget argsFile(ArgsCandidate c) {
        return new LaunchTarget(Mode.ARGS_FILE, c.file, c.type, c.version, null);
    }

    public static LaunchTarget jar(JarCandidate j) {
        return new LaunchTarget(Mode.JAR, j.file, LoaderType.UNKNOWN, null, j.kind);
    }

    public String describe() {
        if (mode == Mode.ARGS_FILE) {
            String ver = loaderVersion == null ? "" : " " + loaderVersion;
            return Messages.get("target.argsFileMode", loaderType.display() + ver, file);
        }
        return Messages.get("target.jarMode", jarKind, file);
    }
}

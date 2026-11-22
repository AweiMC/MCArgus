package com.mcargus.discovery;

import java.nio.file.Path;

/** 扫描到的 Forge/NeoForge 启动参数文件候选（unix_args.txt / win_args.txt）。 */
public final class ArgsCandidate {
    public final Path file;
    public final LoaderType type;
    public final String version;   // 从路径推断的加载器版本，可能为 null
    public final boolean windows;  // win_args.txt = true
    public final long mtime;
    public final long size;

    public ArgsCandidate(Path file, LoaderType type, String version, boolean windows, long mtime, long size) {
        this.file = file;
        this.type = type;
        this.version = version;
        this.windows = windows;
        this.mtime = mtime;
        this.size = size;
    }

    public String describe() {
        return type.display() + (version == null ? "" : " " + version)
                + " [" + (windows ? "win" : "unix") + "] " + file;
    }
}

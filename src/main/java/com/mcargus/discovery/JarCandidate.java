package com.mcargus.discovery;

import com.mcargus.i18n.Messages;

import java.nio.file.Path;

/** 无参数文件时的退化启动目标：Fabric / Quilt / vanilla 等的可执行服务器 Jar。 */
public final class JarCandidate {
    public final Path file;
    public final String kind; // fabric / quilt / vanilla

    public JarCandidate(Path file, String kind) {
        this.file = file;
        this.kind = kind;
    }

    public String describe() {
        return Messages.get("target.serverJar", kind, file);
    }
}

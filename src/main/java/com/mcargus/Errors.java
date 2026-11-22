package com.mcargus;

/**
 * MCArgus 的两种受控失败：
 * UsageException —— 命令行用法错误，退出码 2；
 * PreflightException —— 启动前置检查失败（找不到启动目标、压缩包安装被拒等），退出码 3。
 */
public final class Errors {
    private Errors() {
    }

    public static class UsageException extends RuntimeException {
        public UsageException(String msg) {
            super(msg);
        }
    }

    public static class PreflightException extends RuntimeException {
        public PreflightException(String msg) {
            super(msg);
        }
    }
}

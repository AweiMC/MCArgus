package com.mcargus.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一日志门面：SLF4J API + Logback 后端（配置见 classpath:logback.xml）。
 * 输出两路：控制台 + 滚动文件 <serverDir>/mcargus/logs/mcargus.log（按天滚动，保留 14 天，总量 20MB 上限）。
 *
 * 注意：本类首次被触碰时即完成 Logback 初始化，因此日志目录系统属性 MCARGUS_LOG_DIR
 * 必须由 Main.main 在任何 Log 调用之前直接设置（不能经由本类方法设置）。
 */
public final class Log {
    private static final Logger LOG = LoggerFactory.getLogger("MCArgus");

    private Log() {
    }

    public static void info(String msg) {
        LOG.info(msg);
    }

    public static void warn(String msg) {
        LOG.warn(msg);
    }

    public static void error(String msg) {
        LOG.error(msg);
    }

    public static void error(String msg, Throwable t) {
        LOG.error(msg, t);
    }

    /** 供自测试使用：关闭全部日志输出；恢复时回到 INFO 级别。 */
    public static void setQuiet(boolean q) {
        try {
            ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.setLevel(q ? ch.qos.logback.classic.Level.OFF : ch.qos.logback.classic.Level.INFO);
        } catch (Throwable ignored) {
            // 非 logback 后端时无法调节级别，忽略
        }
    }
}

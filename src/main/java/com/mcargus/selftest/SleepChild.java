package com.mcargus.selftest;

/** 测试用僵尸子进程：启动后沉默 60 秒，用于验证 shutdownChild 能终止它。 */
public final class SleepChild {
    public static void main(String[] args) throws Exception {
        Thread.sleep(60000);
    }
}

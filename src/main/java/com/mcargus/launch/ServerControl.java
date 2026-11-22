package com.mcargus.launch;

import java.io.IOException;

/**
 * 当前服务端子进程的控制接口。
 *
 * <p>把“向服务端写一行”“优雅停止”“请求重启”从 {@link ConsoleAdmin} 中抽象出来，
 * 便于自测时用假实现验证命令路由，而不需要真的启动 Minecraft。</p>
 */
public interface ServerControl {

    /** 向服务端 stdin 写一行（自动补换行）。 */
    void send(String line) throws IOException;

    /** 请求停止服务端并在退出后重新启动。 */
    void requestRestart();

    /** 请求停止服务端，MCArgus 随后退出。 */
    void requestStop();

    boolean isRestartRequested();

    boolean isStopRequested();

    boolean isAlive();

    /** 子进程已运行秒数；未运行或未知时返回 0。 */
    long uptimeSeconds();
}

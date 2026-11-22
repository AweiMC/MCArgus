package com.mcargus.launch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 单一 stdin 读取线程。
 *
 * <p>{@code @restart} 会先让服务端退出、再由 ServerLauncher 启动新进程。如果每次启动都新建
 * stdin 读取线程，重启后会同时存在两个线程抢读 System.in；所以这里让读取线程贯穿 MCArgus
 * 的整个生命周期，只切换当前绑定的服务端控制句柄与命令路由器。</p>
 */
public final class ConsoleInput {

    private final BufferedReader reader;
    private volatile ServerControl control;
    private volatile ConsoleRouter router;

    public ConsoleInput(InputStream in) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "mcargus-stdin");
        t.setDaemon(true);
        t.start();
    }

    /** 绑定当前服务端进程与命令路由器。 */
    public void bind(ServerControl control, ConsoleRouter router) {
        this.control = control;
        this.router = router;
    }

    /** 当前服务端已退出：停止向旧管道写入，等待下一次 bind。 */
    public void unbind() {
        this.control = null;
        this.router = null;
    }

    /** 关闭钩子调用：中止 shell / 下载 / 安装任务。 */
    public void cancelAll() {
        ConsoleRouter r = router;
        if (r != null) {
            r.cancelAll();
        }
    }

    private void loop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                ServerControl c = control;
                ConsoleRouter r = router;
                if (c == null || r == null) {
                    continue; // 服务端重启的短暂间隙，直接忽略
                }
                if (r.isCapturingInput() && r.isMenuChoice(line)) {
                    r.handleMenuInput(line);
                    continue;
                }
                if (r.isCommand(line)) {
                    r.handle(line);
                    continue;
                }
                // 菜单打开时输入真实 MC 命令：退出菜单并照常转发，保证 stop 不被吞掉
                if (r.isCapturingInput()) {
                    r.closeMenu();
                }
                // 面板发来的真实指令优先：先中止可能正在跑的长命令
                r.cancelShell();
                try {
                    c.send(line);
                } catch (IOException ignored) {
                    // 子进程已退出或管道关闭
                }
            }
        } catch (IOException ignored) {
            // stdin 关闭
        }
    }
}

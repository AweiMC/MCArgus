package com.mcargus.launch;

/**
 * 控制台前缀分发器。
 *
 * <p>支持两个前缀：shell 通道（默认 {@code !}）与 MCArgus 控制通道（默认 {@code @}）。
 * 两个前缀重叠时按最长前缀匹配；长度相同则由控制通道优先。
 * 不匹配任何前缀的行由 {@link ConsoleInput} 直接转发给 Minecraft。</p>
 */
public final class ConsoleRouter {

    private final ConsoleShell shell;
    private final ConsoleAdmin admin;
    private final String shellPrefix;
    private final String adminPrefix;

    public ConsoleRouter(ConsoleShell shell, ConsoleAdmin admin) {
        this.shell = shell;
        this.admin = admin;
        this.shellPrefix = shell.prefix();
        this.adminPrefix = admin == null ? null : admin.prefix();
    }

    public boolean isCommand(String line) {
        return line != null && (line.startsWith(shellPrefix)
                || (adminPrefix != null && line.startsWith(adminPrefix)));
    }

    public void handle(String line) {
        if (admin != null) {
            admin.closeMenu();
        }
        boolean shellMatch = line.startsWith(shellPrefix);
        boolean adminMatch = adminPrefix != null && line.startsWith(adminPrefix);
        if (adminMatch && (!shellMatch || adminPrefix.length() >= shellPrefix.length())) {
            admin.handle(line);
        } else {
            shell.handle(line);
        }
    }

    public boolean isCapturingInput() {
        return admin != null && admin.isCapturingInput();
    }

    public boolean isMenuChoice(String line) {
        return admin != null && admin.isMenuChoice(line);
    }

    public void handleMenuInput(String line) {
        if (admin != null) {
            admin.handleMenuInput(line);
        }
    }

    public void closeMenu() {
        if (admin != null) {
            admin.closeMenu();
        }
    }

    public void cancelShell() {
        shell.cancel();
    }

    public void cancelAll() {
        shell.cancel();
        if (admin != null) {
            admin.cancelJob();
        }
    }
}

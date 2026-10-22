package com.jtfa.notificationfixer.privileged;

/**
 * 一次特权命令的结果。
 *
 * <p>{@link #ran} 只说「命令跑完了」，不代表退出码是 0 —— `grep` 没匹配到东西也会返回 1，
 * 那仍然是一次成功的执行。所以两个字段分开看。
 */
public final class ShellResult {

    /** 命令原文，写日志用。 */
    public final String command;

    /** 命令是否真的执行完（false = 通道本身出了问题：没授权、服务没起来、进程起不来）。 */
    public final boolean ran;

    /** 退出码，{@link #ran} 为 false 时无意义。 */
    public final int exitCode;

    /** 标准输出与标准错误合并后的文本。 */
    public final String out;

    public ShellResult(String command, boolean ran, int exitCode, String out) {
        this.command = command;
        this.ran = ran;
        this.exitCode = exitCode;
        this.out = out == null ? "" : out;
    }

    public static ShellResult failure(String command, String reason) {
        return new ShellResult(command, false, -1, reason);
    }

    /** 压成一行，供日志与对话框显示。 */
    public String brief(int maxChars) {
        String flat = out.replace('\r', ' ').replace('\n', ' ').trim();
        while (flat.contains("  ")) {
            flat = flat.replace("  ", " ");
        }
        if (flat.isEmpty()) {
            flat = ran ? "（无输出，退出码 " + exitCode + "）" : "命令未执行";
        }
        if (flat.length() > maxChars) {
            flat = flat.substring(0, maxChars) + "…";
        }
        return flat;
    }
}

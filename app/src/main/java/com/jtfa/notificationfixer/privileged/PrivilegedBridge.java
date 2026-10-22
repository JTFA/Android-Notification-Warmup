package com.jtfa.notificationfixer.privileged;

/**
 * 特权通道（M4）：以 shell uid（Shizuku）或 root uid 执行一条命令。
 *
 * <p>非 root 路径完全不走这里；通道不可用时，应用行为与 M2 一致。
 *
 * <p>实现必须保证 {@link #run} 是同步的、可被串行调用的，调用方负责放到后台线程
 * （见 {@link Privileged#post}）。
 */
public interface PrivilegedBridge {

    /** 通道名，日志里用。 */
    String name();

    /** 通道是否可用（服务活着且已授权 / su 能拿到 root）。 */
    boolean isAvailable();

    /** 一句话状态，界面直接显示。 */
    String status();

    /** 同步执行一条 shell 命令。 */
    ShellResult run(String command);
}

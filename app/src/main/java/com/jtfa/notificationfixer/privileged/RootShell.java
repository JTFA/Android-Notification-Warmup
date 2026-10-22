package com.jtfa.notificationfixer.privileged;

import java.io.InputStream;

/**
 * root 通道：`su -c <命令>`。
 *
 * <p>零依赖，但需要设备真的 root 过（Magisk 等）并且用户给过本应用 root 授权。
 * 与 Shizuku 的区别只在身份：root uid 能拉起非导出组件，而 Shizuku 的 adb 模式（shell uid）不能。
 *
 * <p>设备没 root 时 {@code su} 不存在，{@link #isAvailable()} 在第一次探测时就返回 false，
 * 不会反复弹授权框（结果在本进程内缓存）。
 */
public final class RootShell implements PrivilegedBridge {

    private volatile Boolean mAvailable;

    @Override
    public String name() {
        return "root";
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = mAvailable;
        if (cached == null) {
            ShellResult probe = exec("id");
            cached = probe.ran && probe.out.contains("uid=0");
            mAvailable = cached;
        }
        return cached;
    }

    @Override
    public String status() {
        ShellResult probe = exec("id");
        if (probe.ran && probe.out.contains("uid=0")) {
            return "root 可用（" + probe.brief(60) + "）";
        }
        return "root 不可用（su 拿不到 uid=0）";
    }

    @Override
    public ShellResult run(String command) {
        return exec(command);
    }

    private ShellResult exec(String command) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("su", "-c", command);
            // 合并 stderr：两条管道分开读容易互相等死
            builder.redirectErrorStream(true);
            process = builder.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = ShellStreams.read(in);
            }
            int exitCode = process.waitFor();
            return new ShellResult(command, true, exitCode, output);
        } catch (Throwable t) {
            String message = t.getMessage();
            return ShellResult.failure(command,
                    message == null || message.isEmpty() ? "su 不可用" : message);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}

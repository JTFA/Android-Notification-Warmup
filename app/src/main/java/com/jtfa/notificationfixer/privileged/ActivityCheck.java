package com.jtfa.notificationfixer.privileged;

import android.content.ComponentName;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Activity 路的只读读数（M4 特权通道，只在手动测试 Activity 那条路之后跑一次）。
 *
 * <p>要回答的是「刚才那一下到底发生了什么」，而 {@code pidof} 只能回答「进程起来了没有」：
 *
 * <ul>
 *   <li><b>本次创建窗口数量</b>：0 通常表示没建窗口，但读数只作参考，实际有没有界面
 *       以用户看到为准；</li>
 *   <li><b>当前顶层是谁</b>：用来判断它有没有把对方带到前台；</li>
 *   <li><b>后台启动限制有没有拦</b>：本应用 uid 启动时被拦是<b>静默</b>的，logcat 里
 *       {@code Background activity launch blocked} / {@code Abort background activity starts}
 *       是唯一的证据（这两行只有被拦时才有）；</li>
 * </ul>
 *
 * <p>三条命令都是只读的，而且只在用户手动点「试一次」时执行 —— 自动触发路径上不跑它们，
 * 一次预热不应该多花三次 shell 往返。
 */
public final class ActivityCheck {

    private static final long TIMEOUT_MS = 8_000L;

    private ActivityCheck() {
    }

    /**
     * 读三条原始事实。必须在<b>非界面线程</b>调用（它会等特权线程）；没有特权通道时返回 null。
     */
    public static String read(ComponentName component) {
        if (component == null) {
            return null;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return null;
        }
        final PrivilegedBridge bridge = Privileged.active();
        if (bridge == null) {
            return null;
        }
        final List<String> lines = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        Privileged.post(() -> {
            try {
                lines.addAll(readNow(bridge, component));
            } catch (Throwable t) {
                lines.add("读数失败：" + t);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "等特权通道超过 " + TIMEOUT_MS + "ms，没读到";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (lines.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(text.length() == 0 ? "" : "\n").append(line);
        }
        return text.toString();
    }

    /** 已在特权线程上时直接读。 */
    private static List<String> readNow(PrivilegedBridge bridge, ComponentName component) {
        List<String> lines = new ArrayList<>();
        String full = component.getPackageName() + "/" + component.getClassName();
        String pattern = escapeForEre(full) + "([^A-Za-z0-9_.$]|$)";

        ShellResult windows = bridge.run(
                "dumpsys window windows | grep -cE \"" + pattern + "\"");
        String count = firstToken(windows.out);
        boolean hasCount = windows.ran && !count.isEmpty();
        lines.add("本次创建窗口数量："
                + (hasCount ? count : "读不到（" + windows.brief(60) + "）")
                + (hasCount ? "（数据仅供参考，请以实际表现为准）" : ""));

        ShellResult top = bridge.run(
                "dumpsys activity activities | grep -m1 -E \"topResumedActivity|mResumedActivity\"");
        lines.add("当前顶层：" + (top.out.trim().isEmpty()
                ? "读不到" : oneLine(top.out, 140)));

        ShellResult bal = bridge.run(
                "logcat -d -t 300 | grep -iE \"background activity\" | tail -2");
        String balance = bal.out.trim();
        if (!balance.isEmpty()) {
            lines.add("后台启动限制的日志：" + oneLine(balance, 200));
        }
        return lines;
    }

    private static String firstToken(String out) {
        String text = out == null ? "" : out.trim();
        int space = text.indexOf(' ');
        return space < 0 ? text : text.substring(0, space);
    }

    private static String oneLine(String text, int maxChars) {
        String flat = text.replace('\r', ' ').replace('\n', ' ').trim();
        while (flat.contains("  ")) {
            flat = flat.replace("  ", " ");
        }
        return flat.length() > maxChars ? flat.substring(0, maxChars) + "…" : flat;
    }

    /** 组件名里的正则元字符（主要就是 {@code .} 与内部类的 {@code $}）。 */
    private static String escapeForEre(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}

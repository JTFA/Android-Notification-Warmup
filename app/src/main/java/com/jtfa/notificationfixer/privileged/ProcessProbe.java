package com.jtfa.notificationfixer.privileged;

import android.os.Looper;

import com.jtfa.notificationfixer.core.EventLog;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 精确探活（M4）：目标应用的进程此刻到底在不在。
 *
 * <p>为什么要它：免 root 下应用看不到别的进程，唤醒引擎只能用「每包冷却 + 幂等预热」近似 ——
 * 进程明明活着也照预热一次，日志里只有耗时，分不清那几百毫秒是「真的把进程拉起来了」
 * 还是「进程本来就在，这只是一次幂等调用」。有了 {@code pidof}，预热前后各探一次，
 * 这件事就从推断变成了可核对的事实。
 *
 * <p>实测（2026-10-16，本机 Shizuku adb 模式 = shell uid 2000）：{@code pidof <包名>} 可用，
 * 且认得出比 15 字符长的主进程名 —— Android 的 toybox pidof 比的是完整命令行，
 * 不是被截断到 15 字符的 comm；我们自己的 {@code sh -c} 进程名是 {@code sh}，不会自己命中自己。
 *
 * <p>已知边界：极少数应用的主进程名与包名不同（{@code android:process} 改了名字），
 * 这时 pidof 按包名探不到。先按现状记录，等真机上出现这种样本再补。
 *
 * <p>命令一律在特权线程上跑（{@link Privileged#post}）。{@link #probeBlocking} 允许工作线程
 * 同步等结果，但绝不能在界面线程调用。
 */
public final class ProcessProbe {

    /** pidof 一般几毫秒就回来了，等 3 秒还没回就当探活失败（绝不卡死调用方）。 */
    private static final long TIMEOUT_MS = 3_000L;

    /** 「探活不可用」这类结论最多每 60 秒写一行日志，免得没有特权通道时刷屏。 */
    private static final long UNAVAILABLE_LOG_GAP_MS = 60_000L;

    private static final Object LOG_LOCK = new Object();
    private static long sLastUnavailableLogAt;

    public enum State {
        /** 进程在。 */
        RUNNING,
        /** 进程不在 —— 这就是「冷」的定义。 */
        NOT_RUNNING,
        /** 探不了：没有可用特权通道，或命令本身出错。 */
        UNAVAILABLE
    }

    /** 一次探活的结果。 */
    public static final class Probe {

        public final String packageName;
        public final State state;
        /** {@link State#RUNNING} 时的 pid（同一包多个进程时取第一个）。 */
        public final String pid;
        /** 原始输出 / 失败原因，写日志用。 */
        public final String detail;

        private Probe(String packageName, State state, String pid, String detail) {
            this.packageName = packageName;
            this.state = state;
            this.pid = pid;
            this.detail = detail;
        }

        static Probe running(String packageName, String pid, String detail) {
            return new Probe(packageName, State.RUNNING, pid, detail);
        }

        static Probe notRunning(String packageName, String detail) {
            return new Probe(packageName, State.NOT_RUNNING, null, detail);
        }

        static Probe unavailable(String packageName, String reason) {
            return new Probe(packageName, State.UNAVAILABLE, null, reason);
        }

        public boolean isRunning() {
            return state == State.RUNNING;
        }

        public boolean isNotRunning() {
            return state == State.NOT_RUNNING;
        }

        /** 日志与界面里显示的一句话。 */
        public String describe() {
            switch (state) {
                case RUNNING:
                    return pid == null || pid.isEmpty() ? "进程在运行" : "进程在运行（PID " + pid + "）";
                case NOT_RUNNING:
                    return "进程未运行";
                default:
                    return "探活不可用（" + detail + "）";
            }
        }
    }

    private ProcessProbe() {
    }

    /** 现在有没有可用的特权通道（没有就完全不做探活，行为与 M2 一致）。 */
    public static boolean channelAvailable() {
        try {
            return Privileged.active() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 同步探活：把命令丢到特权线程，在这里等工作线程等到结果。
     *
     * <p>只在预热工作线程 / 手动测试里用 —— 它会把调用方阻塞几十毫秒，界面线程绝不能调。
     */
    public static Probe probeBlocking(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return Probe.unavailable(String.valueOf(packageName), "包名为空");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 防呆：真在界面线程调用会直接 ANR，这里宁可返回一个明确的失败
            return Probe.unavailable(packageName, "探活不能在界面线程等待");
        }
        final Probe[] box = new Probe[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Privileged.post(() -> {
            try {
                box[0] = probeNow(packageName);
            } catch (Throwable t) {
                box[0] = Probe.unavailable(packageName, String.valueOf(t));
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return Probe.unavailable(packageName, "等特权通道超过 " + TIMEOUT_MS + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Probe.unavailable(packageName, "等待被中断");
        }
        Probe probe = box[0];
        return probe == null ? Probe.unavailable(packageName, "特权线程没有返回结果") : probe;
    }

    /** 预热前那一次探活，写日志。 */
    public static void logBefore(String packageName, Probe before) {
        if (before == null || before.state == State.UNAVAILABLE) {
            logUnavailable(packageName, before);
            return;
        }
        EventLog.get().log(EventLog.CAT_WARM,
                "探活 " + packageName + "（预热前）：" + before.describe(), before.detail);
    }

    /**
     * 预热之后复探一次，把「预热前 → 预热后」这条证据补齐。
     *
     * <p>{@code before} 由闭包直接带过来，所以不需要按包名去关联两次探活，也不会串包。
     * 延迟期间进程才把 {@code Application.onCreate} 跑完，所以不能探得太早。
     */
    public static void scheduleAfterProbe(String packageName, Probe before,
                                          String outcome, long delayMs) {
        Privileged.postDelayed(() -> {
            Probe after = probeNow(packageName);
            logAfter(packageName, before, after, "预热后 " + (delayMs / 1000) + " 秒", outcome);
        }, delayMs);
    }

    /** 随手再探一次（已在特权线程上则直接跑）。 */
    public static void logAfter(String packageName, Probe before, Probe after,
                                String label, String outcome) {
        if (after == null) {
            return;
        }
        if (after.state == State.UNAVAILABLE) {
            logUnavailable(packageName, after);
            return;
        }
        EventLog.get().log(EventLog.CAT_WARM,
                "探活 " + packageName + "（" + label + "）：" + after.describe() + verdict(before, after),
                outcome);
    }

    /** 探活的结论：这是「预热到底有没有用」唯一能核对的一句话。 */
    public static String verdict(Probe before, Probe after) {
        if (before == null || after == null
                || before.state == State.UNAVAILABLE || after.state == State.UNAVAILABLE) {
            return "";
        }
        if (before.isNotRunning() && after.isRunning()) {
            return " —— 这次预热确实把进程拉起来了";
        }
        if (before.isNotRunning() && after.isNotRunning()) {
            return " —— 预热之后进程仍然不在，这次预热没生效";
        }
        if (before.isRunning() && after.isRunning()) {
            return " —— 进程预热前就在运行，本次预热只是一次幂等调用";
        }
        return " —— 预热前在、预热后探不到了（可能刚被系统回收）";
    }

    // ---------------------------------------------------------------- 特权线程上执行

    /** 真正的探活命令。必须在特权线程上调用。 */
    private static Probe probeNow(String packageName) {
        PrivilegedBridge bridge = Privileged.active();
        if (bridge == null) {
            return Probe.unavailable(packageName, "没有可用的特权通道");
        }
        ShellResult pidof = bridge.run("pidof " + packageName);
        if (!pidof.ran) {
            return Probe.unavailable(packageName, pidof.brief(80));
        }
        String pid = firstToken(pidof.out);
        if (!pid.isEmpty()) {
            return Probe.running(packageName, pid, "pidof 输出：" + pidof.brief(80));
        }
        // pidof 正常结束但没匹配到 = 进程确实不在（它找不到就是退出码 1）。
        // 只有「连 pidof 这个命令都没有」才退回 ps。
        String out = pidof.out.toLowerCase(Locale.ROOT);
        boolean commandMissing = pidof.exitCode == 127 || out.contains("not found");
        if (!commandMissing) {
            return Probe.notRunning(packageName,
                    "pidof 无输出（退出码 " + pidof.exitCode + "）");
        }
        ShellResult ps = bridge.run("ps -A -o PID,NAME");
        if (!ps.ran) {
            return Probe.unavailable(packageName, "pidof 不可用，ps 也失败：" + ps.brief(80));
        }
        String psPid = findPid(ps.out, packageName);
        if (psPid != null) {
            return Probe.running(packageName, psPid, "pidof 缺失，ps 输出：" + ps.brief(120));
        }
        return Probe.notRunning(packageName, "pidof 缺失，ps 里也没有这个名字的进程");
    }

    private static void logUnavailable(String packageName, Probe probe) {
        synchronized (LOG_LOCK) {
            long now = System.currentTimeMillis();
            if (now - sLastUnavailableLogAt < UNAVAILABLE_LOG_GAP_MS) {
                return;
            }
            sLastUnavailableLogAt = now;
        }
        EventLog.get().log(EventLog.CAT_DIAG, "探活不可用（" + packageName + "）",
                probe == null ? "没有结果" : probe.detail);
    }

    private static String firstToken(String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (String token : trimmed.split("\\s+")) {
                if (!token.isEmpty()) {
                    return token;
                }
            }
        }
        return "";
    }

    /**
     * 从 {@code ps -A -o PID,NAME} 的输出里按完整进程名找 pid。
     *
     * <p>要求整列相等，不能用 contains：包名 {@code com.a} 会被 {@code com.ab} 命中。
     */
    private static String findPid(String psOutput, String packageName) {
        for (String line : psOutput.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            boolean nameMatched = false;
            for (String part : parts) {
                if (part.equals(packageName)) {
                    nameMatched = true;
                    break;
                }
            }
            if (!nameMatched) {
                continue;
            }
            for (String part : parts) {
                if (part.matches("\\d+")) {
                    return part;
                }
            }
        }
        return null;
    }
}

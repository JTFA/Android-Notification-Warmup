package com.jtfa.notificationfixer.core;

/**
 * 当前前台应用。
 *
 * <p>免 root 下无法查询别的进程状态，只能靠无障碍的窗口事件推断，
 * 所以这里只是一个粗略信号，用于「来源应用正在前台时不预热」这类判断。
 */
public final class ForegroundAppTracker {

    private static volatile String sPackageName;
    private static volatile long sUpdatedAtMs;

    private ForegroundAppTracker() {
    }

    public static void set(String packageName) {
        sPackageName = packageName;
        sUpdatedAtMs = System.currentTimeMillis();
    }

    /** 最近一次记录的前台包名，可能为 null。 */
    public static String get() {
        return sPackageName;
    }

    /** 距上次更新经过的毫秒数。 */
    public static long ageMs() {
        return System.currentTimeMillis() - sUpdatedAtMs;
    }
}

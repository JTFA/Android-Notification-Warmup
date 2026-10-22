package com.jtfa.notificationfixer.core;

import android.app.ActivityManager;
import android.content.Context;

import java.util.List;

/**
 * 本应用自己在最近任务（概览）里那张卡片：按设置把它标成隐藏 / 显示。
 *
 * <p>为什么不用清单里的 {@code android:excludeFromRecents}：那是启动时就定死的，开关就没法
 * 在运行时拨动。公开 API 里能做这件事的是
 * {@link ActivityManager.AppTask#setExcludeFromRecents(boolean)}（API 21 起）—— 它改的是
 * <b>本应用自己的任务</b>，不需要任何权限，也不需要 Shizuku / root。
 *
 * <p>它只影响最近任务列表：常驻服务、无障碍与通知监听与它无关，系统也不会因此更愿意或更不愿意
 * 回收本应用。任务被系统重建 / 从桌面重新启动时会拿到新任务（那个标志在新任务上不成立），所以
 * <b>每次回到主界面都按设置重写一次</b>；用户在设置页拨动开关时也立刻写一次。
 *
 * <p>日志只在「值变了」或「写失败」时写一行 —— 回到界面是个高频动作，每次都写会把日志淹掉；
 * 而失败必须留证据（厂商 ROM 有没有照做，只有这里看得出来）。
 */
public final class RecentsVisibility {

    /** 上一次真正下发过的值；null = 本次进程还没下发过。 */
    private static Boolean sApplied;

    private RecentsVisibility() {
    }

    /**
     * 按当前设置下发一次。可以随便调：写不下去、取不到任务都只记日志，不抛。
     */
    public static void apply(Context context) {
        if (context == null) {
            return;
        }
        boolean hide = Prefs.isHideFromRecents();
        ActivityManager manager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            logFailure(hide, 0, 0, "取不到 ActivityManager");
            return;
        }
        List<ActivityManager.AppTask> tasks;
        try {
            tasks = manager.getAppTasks();
        } catch (Throwable t) {
            logFailure(hide, 0, 0, "getAppTasks() 抛了 " + t);
            return;
        }
        int total = tasks == null ? 0 : tasks.size();
        int touched = 0;
        String failure = null;
        if (tasks != null) {
            for (ActivityManager.AppTask task : tasks) {
                try {
                    task.setExcludeFromRecents(hide);
                    touched++;
                } catch (Throwable t) {
                    failure = "setExcludeFromRecents(" + hide + ") 抛了 " + t;
                }
            }
        }
        if (failure != null) {
            logFailure(hide, total, touched, failure);
            return;
        }
        boolean changed = sApplied == null || sApplied != hide;
        sApplied = hide;
        if (changed) {
            EventLog.get().log(EventLog.CAT_SERVICE,
                    "最近任务：" + (hide ? "隐藏" : "显示") + "本应用（任务 " + touched + " 个）",
                    "AppTask.setExcludeFromRecents(" + hide + ")：只影响此后拉起的概览列表；"
                            + "任务被重建或从桌面重开后会再写一次");
        }
    }

    private static void logFailure(boolean hide, int total, int touched, String reason) {
        EventLog.get().log(EventLog.CAT_SERVICE,
                "最近任务：" + (hide ? "隐藏" : "显示") + "本应用没成（任务 "
                        + touched + "/" + total + " 个）",
                reason);
    }
}

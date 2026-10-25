package com.jtfa.notificationfixer.core;

import android.content.Context;

import com.jtfa.notificationfixer.R;
import com.jtfa.notificationfixer.detect.NotificationAccessService;
import com.jtfa.notificationfixer.service.AppNotifications;

import java.util.ArrayList;
import java.util.List;

/**
 * 「监听能力现在健不健康」的唯一判据（0.7.25-M6 / 46；0.7.27-M6 / 48 去掉通知档位之后，
 * 它只服务那四枚开关）。
 *
 * <p>它只服务一件事：设置页「其他设置」里「服务异常通知」那四枚开关要先有人回答
 * <b>什么算异常</b>。四类异常、四枚开关（{@link Prefs#isWatchListener()} 等）：
 *
 * <ul>
 *   <li>通知监听：没授通知使用权，或授了但监听器没接上 —— 新通知触发点直接失效；</li>
 *   <li>无障碍：<b>见过它开着</b>（{@link Prefs#isAccessibilityEverOn()}，我们见到「开着」时记一笔、
 *       见「关着」不会把它翻回去）、现在系统里是关的 —— 面板 / 锁屏触发点失效。从没开启过的用户
 *       不算异常（那是还没配，不是掉了），与 {@code AccessibilityAutostart} 的界线一致；</li>
 *   <li>常驻服务：本次开机里被系统杀过（{@link #onServiceStart} 用「上次有没有正常走完
 *       {@code onDestroy}」认出来；这一笔按<b>开机坐标</b>记，换了开机自己作废，
 *       见 {@link Prefs#markServiceKilled}）—— 锁屏触发依赖它活着。用户打开一次本应用就
 *       收起这条结论（{@link #acknowledge}），下次再被杀会重新记一笔；</li>
 *   <li>特权通道：见过 Shizuku 的 binder、现在收不到了（只盯 Shizuku 这条；root 通道要 spawn
 *       {@code su} 才能查，代价太大，不在这一项里）。</li>
 * </ul>
 *
 * <p><b>开关只管「要不要叫醒用户」，不管判据</b>：关掉某一项，这一类异常照样算、照样写日志，
 * 只是不弹出那条提醒。
 *
 * <p>评估时机都是现成的回调（常驻服务启动 / 通知监听连上断开 / 无障碍连上断开 / Shizuku binder
 * 收发 / 每次无障碍观察的 tick），不额外起轮询。只有「异常项集合变了」才动通知，
 * 所以用户把提醒划掉之后不会每 15 分钟又被顶回来。
 */
public final class HealthWatch {

    /** 「同一次开机」的容差：墙钟会被对时微调，几秒的差不算跨开机。 */
    private static final long SAME_BOOT_TOLERANCE_MS = 5L * 60L * 1000L;

    private static final int WATCH_LISTENER = 0;
    private static final int WATCH_ACCESSIBILITY = 1;
    private static final int WATCH_SERVICE = 2;
    private static final int WATCH_PRIVILEGED = 3;

    /** 上一次报出去的异常项，变了才动通知（进程重启后第一次一定会对齐一次）。 */
    private static String sLastState;
    private static boolean sEvaluated;

    private HealthWatch() {
    }

    // ------------------------------------------------------------------ 事件入口

    /**
     * 常驻服务起来了。
     *
     * <p>先认「上一次是不是被杀掉的」：上一次的运行记录落在<b>同一次开机</b>里、并且没走完
     * {@code onDestroy}，那就是被杀（跨开机的情况是「重启」，不是「被杀」）。认完再记下这一次。
     */
    public static void onServiceStart(Context context) {
        long boot = AccessibilityAutostart.bootWallClockMs();
        long lastBoot = Prefs.serviceRunBoot();
        boolean sameBoot = lastBoot > 0L && Math.abs(boot - lastBoot) <= SAME_BOOT_TOLERANCE_MS;
        if (sameBoot && !Prefs.serviceStoppedCleanly()) {
            Prefs.markServiceKilled(boot);
        }
        Prefs.markServiceRunning(boot);
        evaluate(context);
    }

    /** 常驻服务正常结束（{@code onDestroy} 走到了）。 */
    public static void onServiceStopCleanly() {
        Prefs.markServiceStoppedCleanly();
    }

    /** 用户打开本应用看过了：收起「被杀过」这条结论。 */
    public static void acknowledge(Context context) {
        if (Prefs.serviceKilledBoot() == 0L) {
            return;
        }
        Prefs.clearServiceKilled();
        evaluate(context);
    }

    /** Shizuku 的 binder 到了（它同时也说明「我们见过特权通道」）。 */
    public static void onPrivilegedAvailable(Context context) {
        boolean changed = !Prefs.privilegedSeen() || Prefs.privilegedLost();
        Prefs.markPrivilegedAvailable();
        if (changed) {
            evaluate(context);
        }
    }

    /** Shizuku 的 binder 断了。 */
    public static void onPrivilegedLost(Context context) {
        Prefs.markPrivilegedLost();
        evaluate(context);
    }

    // ------------------------------------------------------------------ 判据

    private static final class Item {
        final int watch;
        final String text;

        Item(int watch, String text) {
            this.watch = watch;
            this.text = text;
        }
    }

    /** 现在成立的异常项。 */
    private static List<Item> items(Context context) {
        List<Item> items = new ArrayList<>(4);
        if (!Permissions.hasNotificationAccess(context)) {
            items.add(new Item(WATCH_LISTENER,
                    context.getString(R.string.notify_item_listener_permission)));
        } else if (!NotificationAccessService.isConnected()) {
            items.add(new Item(WATCH_LISTENER,
                    context.getString(R.string.notify_item_listener)));
        }
        boolean accessibilityOn = AccessibilityGrant.isOn(context);
        if (accessibilityOn) {
            // 见过它开着就记一笔（只写一次盘）：这一笔用来把「配过、现在掉了」与「从没配过」分开
            Prefs.markAccessibilityEverOn();
        } else if (Prefs.isAccessibilityEverOn()) {
            items.add(new Item(WATCH_ACCESSIBILITY,
                    context.getString(R.string.notify_item_accessibility)));
        }
        if (killedThisBoot()) {
            items.add(new Item(WATCH_SERVICE, context.getString(R.string.notify_item_service)));
        }
        if (Prefs.privilegedSeen() && Prefs.privilegedLost()) {
            items.add(new Item(WATCH_PRIVILEGED, context.getString(R.string.notify_item_privileged)));
        }
        return items;
    }

    /**
     * 「本次开机里常驻服务被杀过」：记的那一次开机就是现在这一次。
     *
     * <p>按开机坐标比对，于是「上一次开机被杀过」这种旧账在换开机时自动作废，不必等谁来清理
     * （0.7.26 及以前记的是时刻、没有这一层，重启后还会拿上一辈子的事提醒 —— 48 修的缺陷）。
     */
    private static boolean killedThisBoot() {
        long killedBoot = Prefs.serviceKilledBoot();
        return killedBoot != 0L
                && Math.abs(killedBoot - AccessibilityAutostart.bootWallClockMs()) <= SAME_BOOT_TOLERANCE_MS;
    }

    /** 四枚开关过滤之后、真正会报出来的异常项。 */
    private static List<Item> watchedItems(Context context) {
        List<Item> watched = new ArrayList<>(4);
        for (Item item : items(context)) {
            if (watched(item.watch)) {
                watched.add(item);
            }
        }
        return watched;
    }

    private static boolean watched(int watch) {
        switch (watch) {
            case WATCH_LISTENER:
                return Prefs.isWatchListener();
            case WATCH_ACCESSIBILITY:
                return Prefs.isWatchAccessibility();
            case WATCH_SERVICE:
                return Prefs.isWatchService();
            case WATCH_PRIVILEGED:
                return Prefs.isWatchPrivileged();
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ 汇报

    /**
     * 评估一次：异常项变了才动那条提醒（发 / 撤），变化写一行日志。
     *
     * <p>「变了才动」有两个用处：用户把提醒划掉之后它不会自己回来；每 15 分钟的常规观察也不会
     * 反复重发同一条通知。
     */
    public static void evaluate(Context context) {
        Context app = context.getApplicationContext();
        List<Item> enabled = watchedItems(app);

        StringBuilder text = new StringBuilder();
        for (Item item : enabled) {
            if (text.length() > 0) {
                text.append("、");
            }
            text.append(item.text);
        }

        String state = text.toString();
        boolean first = !sEvaluated;
        sEvaluated = true;
        if (!first && state.equals(sLastState)) {
            return;
        }
        sLastState = state;

        if (!enabled.isEmpty()) {
            AppNotifications.postAlert(app,
                    app.getString(R.string.notify_alert_text, text.toString()));
        } else {
            AppNotifications.clearAlert(app);
        }

        if (enabled.isEmpty()) {
            EventLog.get().log(EventLog.CAT_SERVICE, "监听能力：四项都在位"
                    + (first ? "（本次启动第一眼）" : ""),
                    rawData(app));
        } else {
            EventLog.get().log(EventLog.CAT_SERVICE, "监听能力异常：" + text + "（已发提醒）",
                    rawData(app));
        }
    }

    /** 一行原始读数：四个判据各自看到什么，不看开关。 */
    private static String rawData(Context context) {
        return "通知使用权=" + (Permissions.hasNotificationAccess(context) ? "已授予" : "未授予")
                + "；通知监听器=" + (NotificationAccessService.isConnected() ? "已连接" : "未连接")
                + "；无障碍=" + (AccessibilityGrant.isOn(context) ? "开着" : "关着")
                + "（配过=" + (Prefs.isAccessibilityEverOn() ? "是" : "否")
                + "，上次看到=" + (Prefs.isAccessibilitySeenOn() ? "开着" : "关着/没看过") + "）"
                + "；服务被杀=" + killedRawText()
                + "；特权通道=" + (Prefs.privilegedSeen()
                        ? (Prefs.privilegedLost() ? "断开了" : "在") : "没见过");
    }

    /** 「服务被杀」这一项的原始读数（把上一次开机的旧账说清楚，免得看着像判据失灵）。 */
    private static String killedRawText() {
        if (killedThisBoot()) {
            return "本次开机有过";
        }
        return Prefs.serviceKilledBoot() != 0L ? "没有（上次开机有过，已作废）" : "没有";
    }
}

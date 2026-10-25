package com.jtfa.notificationfixer.detect;

import android.app.KeyguardManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.Display;

import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 屏幕状态与锁屏点亮。
 *
 * <p>ACTION_SCREEN_ON / OFF / USER_PRESENT 只能动态注册接收，
 * 所以这个接收者由 KeepAliveService 注册 —— 前台服务没起来，锁屏触发就完全失效。
 *
 * <p>这里同时维护一份「屏幕真正点亮」的状态（{@link #isScreenOn()}）：以
 * ACTION_SCREEN_ON / OFF 为准，AOD 未点亮算熄灭。通知监听、无障碍面板判定与
 * 唤醒引擎都用它作为自动触发的硬门禁；{@code PowerManager.isInteractive()} 在
 * 部分 ROM 的 AOD / 唤醒过程中会提前变成 true，不能单独拿来做这个判断。
 *
 * <p><b>锁屏这条触发点怎么挑候选应用，见 {@link Prefs#getLockscreenMode()} 的两档</b>
 * （0.7.34-M6 / 55 起由用户在设置页选）：
 *
 * <ul>
 *   <li><b>精确（默认）</b>：点亮锁屏时读一下锁屏上真的画了哪几行通知（{@link LockscreenWatch}），
 *       再按每一行的读数认出它属于哪个应用（{@link LockscreenMatch}）—— 认出谁就预热谁，
 *       认不出的行不预热；读不到锁屏结构（无障碍没开等）也不预热，第一次读不到会隔一拍重读一次；
 *       原始读数（窗口串、容器、每一行的类名 / view id / 行里包名 / 行里文字）与结论都写进
 *       「诊断」日志。</li>
 *   <li><b>老办法</b>：0.7.32 及以前那一套 —— <b>不看</b>锁屏窗口，按系统设置（每条通知的锁屏
 *       可见性 + {@code lock_screen_allow_private_notifications}）把通知中心里的应用当作
 *       「锁屏可见」。它存在的理由：真机数据（2026-10-18）显示「锁屏可见 47 / 活动通知 76」而锁屏
 *       什么都没画 —— ColorOS 的「锁屏通知」开关不落到 AOSP 的设置表里，老办法会把没画出来的
 *       应用也预热掉，所以它是退路而不是默认。</li>
 * </ul>
 *
 * <p>走了哪条路、读数是什么，都写进「诊断」日志 —— 判定不靠猜。
 */
public class ScreenStateReceiver extends BroadcastReceiver {

    /** 点亮后等 SystemUI 把锁屏通知排好再统计。 */
    private static final long LOCKSCREEN_PROBE_DELAY_MS = 400L;

    /** 精确路第一次读不到锁屏窗口时的重读间隔：SystemUI 可能还没把锁屏窗口挂上来。 */
    private static final long LOCKSCREEN_RETRY_DELAY_MS = 500L;

    /** 精确路最多读几次（第一次 + 重读一次）。 */
    private static final int LOCKSCREEN_MAX_ATTEMPTS = 2;

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private static Runnable sPendingProbe;

    /** 屏幕是否真正点亮。收到广播前先用当前显示状态初始化，见 {@link #refresh(Context)}。 */
    private static volatile boolean sScreenOn = true;

    /**
     * 屏幕是否真正点亮。
     *
     * <p>AOD / 熄屏都返回 false。所有自动触发在发起前都检查这个值；已经排队的任务
     * 在 {@code WarmEngine} 执行前还会再检查一次，避免亮屏时排队、延迟期间熄屏后仍执行。
     */
    public static boolean isScreenOn() {
        return sScreenOn;
    }

    /**
     * 用当前显示状态初始化一次。动态接收器只会在变化时收到广播，服务刚起来时必须先读一次；
     * 优先看 {@link Display#getState()}（AOD 是 DOZE，不是 ON），读不到再退回
     * {@link PowerManager#isInteractive()}。
     */
    public static void refresh(Context context) {
        if (context == null) {
            return;
        }
        boolean on = false;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            on = power != null && power.isInteractive();
        } catch (Exception ignored) {
            // 读不到就保持下面的显示状态判断
        }
        try {
            DisplayManager manager =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = manager == null
                    ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                int state = display.getState();
                if (state == Display.STATE_ON) {
                    on = true;
                } else if (state == Display.STATE_OFF
                        || state == Display.STATE_DOZE
                        || state == Display.STATE_DOZE_SUSPEND) {
                    on = false;
                }
            }
        } catch (Exception ignored) {
            // 显示服务读不到时保留 isInteractive 的结果
        }
        sScreenOn = on;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        switch (intent.getAction()) {
            case Intent.ACTION_SCREEN_ON:
                sScreenOn = true;
                EventLog.get().log(EventLog.CAT_SERVICE, "屏幕点亮");
                // 息屏期间收到的新通知：先立刻预热一次，不等下面 400ms 的锁屏窗口排版。
                // 只受「锁屏点亮时」与「息屏时记录…」两个开关控制；精确度滑杆在下面过滤。
                emitScreenOffWarm(appContext);
                cancelPendingProbe();
                sPendingProbe = () -> probeLockscreen(appContext);
                sMain.postDelayed(sPendingProbe, LOCKSCREEN_PROBE_DELAY_MS);
                break;
            case Intent.ACTION_SCREEN_OFF:
                sScreenOn = false;
                EventLog.get().log(EventLog.CAT_SERVICE, "屏幕熄灭");
                cancelPendingProbe();
                // 通知中心与锁屏都会挂 notification_stack_scroller；熄屏时面板状态必须作废，
                // 否则下一次亮屏可能沿用旧状态，把锁屏通知当成通知中心新通知。
                ShadeAccessibilityService.resetShadeState();
                break;
            case Intent.ACTION_USER_PRESENT:
                sScreenOn = true;
                EventLog.get().log(EventLog.CAT_SERVICE, "用户解锁");
                break;
            default:
                break;
        }
    }

    private static void cancelPendingProbe() {
        if (sPendingProbe != null) {
            sMain.removeCallbacks(sPendingProbe);
            sPendingProbe = null;
        }
    }

    /**
     * 屏幕点亮瞬间的快速通道：息屏期间收到过新通知的应用先排一次队，不等 400ms 的锁屏探测。
     *
     * <p>只由「锁屏点亮时」和「息屏时尝试记录新锁屏通知，点亮屏幕快速预热」两个开关控制，
     * 与全局「收到新通知时」、内层「仅横幅通知触发」无关。息屏期间记录的是每条通知的
     * 锁屏可见置信度，这里按用户当前的精确度阈值过滤；然后要求当前确实在锁屏，并且
     * 该应用还有活动通知，避免把已经点掉或已经消失的通知再预热一次。
     *
     * <p>开关关闭或不在锁屏时只清空记录，不发触发。400ms 后的精确探测仍会跑；
     * 同一个包还在队列/执行中或落在冷却窗口内时，唤醒引擎会跳过，不会预热两次。
     */
    private static void emitScreenOffWarm(Context context) {
        if (!Prefs.isTriggerLockscreen() || !Prefs.isScreenOffFastWarm()) {
            NotificationAccessService.clearScreenOffNotices();
            return;
        }
        if (!isLocked(context)) {
            NotificationAccessService.clearScreenOffNotices();
            EventLog.get().log(EventLog.CAT_DIAG,
                    "亮屏补预热：当前未处于锁屏，跳过");
            return;
        }

        int precision = Prefs.getLockscreenFilterPrecision();
        List<String> targets = NotificationAccessService.takeScreenOffNotices(precision);
        if (targets.isEmpty()) {
            return;
        }

        if (NotificationAccessService.isConnected()) {
            Set<String> active = new LinkedHashSet<>();
            for (StatusBarNotification sbn : NotificationAccessService.activeNotifications()) {
                if (sbn != null && sbn.getPackageName() != null) {
                    active.add(sbn.getPackageName());
                }
            }
            int before = targets.size();
            targets.removeIf(packageName -> !active.contains(packageName));
            if (targets.size() != before) {
                EventLog.get().log(EventLog.CAT_DIAG,
                        "亮屏补预热：其中 " + (before - targets.size())
                                + " 个应用已不在活动通知里，跳过");
            }
        } else {
            EventLog.get().log(EventLog.CAT_DIAG,
                    "亮屏补预热：通知监听未连接，无法核对仍活动，保留全部");
        }
        if (targets.isEmpty()) {
            return;
        }

        int globalLockscreen = readSecureInt(context, "lock_screen_show_notifications", 1);
        int allowPrivate = readSecureInt(context, "lock_screen_allow_private_notifications", 1);
        EventLog.get().log(EventLog.CAT_DIAG,
                "亮屏补预热：精确度 " + precision + "，系统锁屏通知总开关="
                        + (globalLockscreen == 1 ? "开" : "关")
                        + "，允许私密=" + allowPrivate
                        + "，预热 " + targets.size() + " 个应用",
                "应用：" + targets);
        TriggerBus.emit(new TriggerEvent(TriggerType.SCREEN_ON_PENDING, null,
                "息屏期间可能上锁屏的新通知（精确度 " + precision + "）", targets));
    }

    /** 当前是否处于锁屏。通知监听也用它判断“锁屏停留期间的新通知”。 */
    static boolean isLocked(Context context) {
        KeyguardManager keyguardManager =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguardManager != null && keyguardManager.isKeyguardLocked();
    }

    /** 屏幕点亮后的锁屏探测是否还在等待或重试。 */
    static boolean isLockscreenProbePending() {
        return sPendingProbe != null;
    }

    /** 屏幕点亮后：处于锁屏且开关打开时，按设置里选的「锁屏预热方式」挑候选应用。 */
    private void probeLockscreen(Context context) {
        sPendingProbe = null;

        if (!isScreenOn()) {
            EventLog.get().log(EventLog.CAT_DIAG, "锁屏点亮探测：屏幕已经熄灭，跳过");
            return;
        }
        if (!isLocked(context)) {
            EventLog.get().log(EventLog.CAT_DIAG, "锁屏点亮探测：当前未处于锁屏，跳过");
            return;
        }
        if (!Prefs.isTriggerLockscreen()) {
            EventLog.get().log(EventLog.CAT_DIAG, "锁屏点亮探测：触发开关已关闭");
            return;
        }

        boolean withDetail = Prefs.isDiagLog();
        if (Prefs.getLockscreenMode() == Prefs.LOCKSCREEN_MODE_LEGACY) {
            probeLegacy(context, withDetail);
            return;
        }
        // 「实时通知」这条不依赖锁屏结构（读的是通知中心与媒体会话），先算一次，下面三条路都用它
        probePrecise(context, withDetail, 1,
                LiveNotifications.collect(context, Prefs.getLiveWarmMode(), withDetail));
    }

    // ------------------------------------------------------- 精确：读锁屏上真正画出来的每一行

    /**
     * 精确路：读锁屏窗口，按行认应用；再把「实时通知」那一条并进来（0.7.35-M6 / 56 起）。
     *
     * <p>读不到窗口时（{@link LockscreenWatch#UNKNOWN}，比如无障碍服务没连上、或 SystemUI 还没把锁屏
     * 窗口挂好）先隔一拍重读一次，再读不到就<b>只发实时集合</b> —— 这一档的立场是「只按事实办事」，
     * 而实时集合的事实来自通知中心，本来就不依赖锁屏结构（媒体卡片 / 流体云卡片那两行正是这么漏的）。
     * 想回到按设置估算就改选「老办法」。
     */
    private void probePrecise(Context context, boolean withDetail, int attempt,
                              LiveNotifications.Report live) {
        if (!isScreenOn()) {
            EventLog.get().log(EventLog.CAT_DIAG,
                    "锁屏点亮探测（精确匹配）：屏幕已经熄灭，放弃这一次");
            return;
        }
        if (attempt > 1 && (!isLocked(context) || !Prefs.isTriggerLockscreen())) {
            EventLog.get().log(EventLog.CAT_DIAG,
                    "锁屏点亮探测（精确匹配）：重读前已经离开锁屏或开关被关掉，放弃这一次");
            return;
        }

        LockscreenWatch.Reading drawing = LockscreenWatch.readNow(withDetail);
        if (drawing.state == LockscreenWatch.UNKNOWN && attempt < LOCKSCREEN_MAX_ATTEMPTS) {
            EventLog.get().log(EventLog.CAT_DIAG,
                    "锁屏点亮探测（精确匹配）：第 " + attempt + " 次读不到锁屏窗口（" + drawing.note
                            + "），" + LOCKSCREEN_RETRY_DELAY_MS + " ms 后重读一次",
                    "窗口=" + drawing.signature);
            sPendingProbe = () -> probePrecise(context, withDetail, attempt + 1, live);
            sMain.postDelayed(sPendingProbe, LOCKSCREEN_RETRY_DELAY_MS);
            return;
        }
        sPendingProbe = null;

        String liveDetail = withDetail && !live.detail.isEmpty() ? "；" + live.detail : "";

        if (drawing.state == LockscreenWatch.UNKNOWN) {
            String why = "读不到锁屏窗口（" + drawing.note + "）";
            if (live.isEmpty()) {
                EventLog.get().log(EventLog.CAT_DIAG,
                        "锁屏点亮探测（精确匹配）：" + why + "，" + live.summary + " → 不预热"
                                + "（想改成按设置估算，到设置里把「锁屏预热方式」换成「老办法」）",
                        "窗口=" + drawing.signature + liveDetail);
                return;
            }
            EventLog.get().log(EventLog.CAT_DIAG,
                    "锁屏点亮探测（精确匹配）：" + why + " → 只发 " + live.summary,
                    "窗口=" + drawing.signature + liveDetail);
            if (!isScreenOn()) {
                EventLog.get().log(EventLog.CAT_DIAG,
                        "锁屏点亮探测（精确匹配）：准备发触发时屏幕已经熄灭，放弃");
                return;
            }
            TriggerBus.emit(new TriggerEvent(TriggerType.LOCKSCREEN_LIT, null,
                    "读不到锁屏窗口，只发实时通知", live.packages));
            return;
        }

        if (drawing.state == LockscreenWatch.NONE) {
            String why = "锁屏上没有画出通知（" + drawing.note + "）";
            String windowDetail = "窗口=" + drawing.signature
                    + (drawing.detail.isEmpty() ? "" : " 明细：" + drawing.detail) + liveDetail;
            if (live.isEmpty()) {
                EventLog.get().log(EventLog.CAT_DIAG,
                        "锁屏点亮探测（精确匹配）：" + why + "，" + live.summary + " → 不预热",
                        windowDetail);
                return;
            }
            EventLog.get().log(EventLog.CAT_DIAG,
                    "锁屏点亮探测（精确匹配）：" + why + " → 只发 " + live.summary,
                    windowDetail);
            if (!isScreenOn()) {
                EventLog.get().log(EventLog.CAT_DIAG,
                        "锁屏点亮探测（精确匹配）：准备发触发时屏幕已经熄灭，放弃");
                return;
            }
            TriggerBus.emit(new TriggerEvent(TriggerType.LOCKSCREEN_LIT, null,
                    "锁屏上没有画出通知，只发实时通知", live.packages));
            return;
        }

        // 画了 N 条：逐行认，再与实时集合并起来（实时排前面，单次上限先保它们）
        List<LockscreenMatch.Candidate> candidates = LockscreenMatch.candidates(context);
        PackageManager packageManager = context.getPackageManager();
        String self = context.getPackageName();
        List<String> targets = new ArrayList<>(live.packages);
        List<String> byRows = new ArrayList<>();
        StringBuilder rows = withDetail ? new StringBuilder() : null;
        if (rows != null) {
            rows.append("候选：").append(LockscreenMatch.describeCandidates(candidates)).append("；");
        }
        int unidentified = 0;
        for (LockscreenWatch.Row row : drawing.rows) {
            if (!row.visible) {
                if (rows != null) {
                    rows.append("第 ").append(row.index).append(" 行[").append(row.describe())
                            .append("]（没画出来，跳过）；");
                }
                continue;
            }
            StringBuilder why = new StringBuilder();
            String target = LockscreenMatch.match(row, candidates, why);
            if (target == null) {
                // 候选集合可能因为分组摘要 / 活动通知快照漏掉应用；再按全机唯一应用名兜底一次
                target = LockscreenMatch.matchByInstalledLabel(row, packageManager, self, why);
            }
            if (target == null) {
                unidentified++;
            } else {
                if (!byRows.contains(target)) {
                    byRows.add(target);
                }
                if (!targets.contains(target)) {
                    targets.add(target);
                }
            }
            if (rows != null) {
                rows.append("第 ").append(row.index).append(" 行[").append(row.describe())
                        .append("] → ").append(target == null ? "认不出，不预热" : target)
                        .append("（").append(why).append("）；");
            }
        }

        EventLog.get().log(EventLog.CAT_DIAG,
                "锁屏点亮探测（精确匹配）：锁屏画了 " + drawing.visibleRows() + " 条通知，行内认出 "
                        + byRows.size() + " 个应用"
                        + (unidentified > 0 ? "（认不出 " + unidentified + " 条，不预热）" : "")
                        + "；" + live.summary + "；合计候选 " + targets.size() + " 个",
                "窗口=" + drawing.signature + " 容器=" + drawing.container + liveDetail
                        + (rows == null ? "" : "；逐行：" + rows));
        if (targets.isEmpty()) {
            return;
        }
        if (!isScreenOn()) {
            EventLog.get().log(EventLog.CAT_DIAG,
                    "锁屏点亮探测（精确匹配）：准备发触发时屏幕已经熄灭，放弃");
            return;
        }
        TriggerBus.emit(new TriggerEvent(TriggerType.LOCKSCREEN_LIT, null,
                "锁屏画了 " + drawing.visibleRows() + " 条通知，行内认出 " + byRows.size()
                        + " 个应用，实时通知 " + live.packages.size() + " 个", targets));
    }

    // ------------------------------------------------------- 老办法：按系统设置估算

    /**
     * 老办法：0.7.32 及以前的算法 —— 不看锁屏窗口，把「按可见性允许出现在锁屏上」的活动通知
     * 所属应用当作候选，最近发布的在前。
     *
     * <p>只有一条系统设置参与判断：{@code lock_screen_allow_private_notifications}
     * （私密通知允不允许上锁屏）。0.7.33-M6 / 54 加过的「锁屏总开关」「无声通知」两个读法跟着
     * 这一档一起去掉了：精确档直接用「画没画出来」的事实覆盖它们，而这一档要的就是 0.7.32 的行为。
     */
    private void probeLegacy(Context context, boolean withDetail) {
        if (!isScreenOn()) {
            EventLog.get().log(EventLog.CAT_DIAG,
                    "锁屏点亮探测（老办法）：屏幕已经熄灭，放弃这一次");
            return;
        }
        int allowPrivate = readSecureInt(context, "lock_screen_allow_private_notifications", 1);
        List<StatusBarNotification> active = NotificationAccessService.activeNotifications();
        List<StatusBarNotification> sorted = new ArrayList<>(active);
        Collections.sort(sorted, new Comparator<StatusBarNotification>() {
            @Override
            public int compare(StatusBarNotification left, StatusBarNotification right) {
                return Long.compare(right.getPostTime(), left.getPostTime());
            }
        });

        String self = context.getPackageName();
        Set<String> packages = new LinkedHashSet<>();
        StringBuilder detail = withDetail ? new StringBuilder() : null;
        for (StatusBarNotification sbn : sorted) {
            if (sbn == null || sbn.getPackageName() == null) {
                continue;
            }
            // 分组摘要点开只是展开分组，不会启动应用
            if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                continue;
            }
            int visibility = Heuristics.effectiveLockscreenVisibility(context, sbn);
            boolean secret = visibility == Notification.VISIBILITY_SECRET;
            boolean privateNotification = visibility == Notification.VISIBILITY_PRIVATE;
            boolean shown = !secret && (!privateNotification || allowPrivate == 1);
            if (shown && !self.equals(sbn.getPackageName())) {
                packages.add(sbn.getPackageName());
            }
            if (detail != null) {
                detail.append(sbn.getPackageName())
                        .append('(').append(Heuristics.visibilityName(visibility))
                        .append(shown ? " 可见" : " 隐藏").append(") ");
            }
        }

        EventLog.get().log(EventLog.CAT_DIAG,
                "锁屏点亮探测（老办法）：锁屏可见 " + packages.size() + " 个应用 / 活动通知 "
                        + active.size() + " 条（允许私密通知=" + allowPrivate + "）",
                detail == null ? "" : "明细：" + detail);
        if (packages.isEmpty()) {
            return;
        }
        if (!isScreenOn()) {
            EventLog.get().log(EventLog.CAT_DIAG,
                    "锁屏点亮探测（老办法）：准备发触发时屏幕已经熄灭，放弃");
            return;
        }
        TriggerBus.emit(new TriggerEvent(TriggerType.LOCKSCREEN_LIT, null,
                "按设置估算锁屏可见 " + packages.size() + " 个应用", new ArrayList<>(packages)));
    }

    private static int readSecureInt(Context context, String key, int defaultValue) {
        try {
            return Settings.Secure.getInt(context.getContentResolver(), key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}

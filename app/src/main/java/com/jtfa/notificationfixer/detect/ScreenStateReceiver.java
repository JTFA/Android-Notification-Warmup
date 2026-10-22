package com.jtfa.notificationfixer.detect;

import android.app.KeyguardManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

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

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        switch (intent.getAction()) {
            case Intent.ACTION_SCREEN_ON:
                EventLog.get().log(EventLog.CAT_SERVICE, "屏幕点亮");
                cancelPendingProbe();
                sPendingProbe = () -> probeLockscreen(appContext);
                sMain.postDelayed(sPendingProbe, LOCKSCREEN_PROBE_DELAY_MS);
                break;
            case Intent.ACTION_SCREEN_OFF:
                EventLog.get().log(EventLog.CAT_SERVICE, "屏幕熄灭");
                cancelPendingProbe();
                break;
            case Intent.ACTION_USER_PRESENT:
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
            TriggerBus.emit(new TriggerEvent(TriggerType.LOCKSCREEN_LIT, null,
                    "锁屏上没有画出通知，只发实时通知", live.packages));
            return;
        }

        // 画了 N 条：逐行认，再与实时集合并起来（实时排前面，单次上限先保它们）
        List<LockscreenMatch.Candidate> candidates = LockscreenMatch.candidates(context);
        List<String> targets = new ArrayList<>(live.packages);
        List<String> byRows = new ArrayList<>();
        StringBuilder rows = withDetail ? new StringBuilder() : null;
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

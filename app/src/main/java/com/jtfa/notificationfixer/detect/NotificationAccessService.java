package com.jtfa.notificationfixer.detect;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.ForegroundAppTracker;
import com.jtfa.notificationfixer.core.HealthWatch;
import com.jtfa.notificationfixer.core.Permissions;
import com.jtfa.notificationfixer.core.Prefs;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 通知监听：横幅判定，以及为锁屏触发提供活动通知快照。
 *
 * <p>0.7.55-M6 / 76 起，系统解绑后主动调用 {@code requestRebind(...)}；只在通知使用权仍授予时尝试，
 * 并由常驻服务与状态页一起兜底（见 {@link #ensureConnected(Context)}）。
 */
public class NotificationAccessService extends NotificationListenerService {

    private static final long REBIND_MIN_INTERVAL_MS = 10_000L;
    private static final long REBIND_DELAY_MS = 1_000L;
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private static volatile NotificationAccessService sInstance;
    private static volatile boolean sConnected;
    private static boolean sRebindScheduled;
    private static long sLastRebindUptimeMs;

    /**
     * 通知中心或锁屏停留期间已经触发过预热的通知 key。
     *
     * <p>同一个 key 的更新（进度条、消息追加）不重复排队；新 key 才说明是这次停留期间
     * 新出现的通知。两个场景都不活跃时清空；下一次展开/点亮由原有事件负责。
     */
    private final Set<String> mSceneNotifiedKeys = new HashSet<>();

    public static boolean isConnected() {
        return sConnected;
    }

    /**
     * 通知监听器被系统解绑后主动请求重绑。
     *
     * <p>{@code NotificationListenerService} 不是普通 bindService：系统解绑后不会因为我们还活着就
     * 自动接回来，{@link #onListenerDisconnected()} 里也不该只写一行日志。这里做三件事：
     * 只在未连接时请求；没有通知使用权时不动（重绑也不会成功）；短时间内的重复调用只发一次。
     */
    public static void ensureConnected(Context context) {
        ensureConnected(context, false);
    }

    /** 用户点「重启监听服务」时用：绕过短时间节流，保证这一次真的向系统发请求。 */
    public static void requestRebindNow(Context context) {
        ensureConnected(context, true);
    }

    private static void ensureConnected(Context context, boolean force) {
        if (context == null || sConnected) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!Permissions.hasNotificationAccess(app)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        synchronized (NotificationAccessService.class) {
            if (!force && sLastRebindUptimeMs != 0L
                    && now - sLastRebindUptimeMs < REBIND_MIN_INTERVAL_MS) {
                return;
            }
            sLastRebindUptimeMs = now;
        }
        try {
            requestRebind(new ComponentName(app, NotificationAccessService.class));
            EventLog.get().log(EventLog.CAT_SERVICE, "请求重绑通知监听器");
        } catch (Throwable t) {
            EventLog.get().log(EventLog.CAT_SERVICE, "请求重绑通知监听器失败：" + t);
        }
    }

    /** 断开回调里不要立刻重绑：给系统一点解绑收尾的时间，也避免同一轮重复请求。 */
    private static void scheduleRebind(Context context) {
        if (context == null || sRebindScheduled) {
            return;
        }
        sRebindScheduled = true;
        Context app = context.getApplicationContext();
        sMain.postDelayed(() -> {
            sRebindScheduled = false;
            ensureConnected(app);
        }, REBIND_DELAY_MS);
    }

    /** 当前活动通知，未连接时返回空列表。 */
    public static List<StatusBarNotification> activeNotifications() {
        NotificationAccessService service = sInstance;
        if (service == null || !sConnected) {
            return Collections.emptyList();
        }
        try {
            StatusBarNotification[] array = service.getActiveNotifications();
            if (array == null || array.length == 0) {
                return Collections.emptyList();
            }
            List<StatusBarNotification> result = new ArrayList<>(array.length);
            Collections.addAll(result, array);
            return result;
        } catch (Exception e) {
            EventLog.get().log(EventLog.CAT_SERVICE, "读取活动通知失败：" + e);
            return Collections.emptyList();
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
        sConnected = true;
        EventLog.get().log(EventLog.CAT_SERVICE,
                "通知监听器已连接，当前活动通知 " + activeNotifications().size() + " 条");
        // 监听能力变了就重估一次（0.7.25-M6 / 46）：异常提醒跟着这一眼里的事实时起时落
        HealthWatch.evaluate(this);
    }

    @Override
    public void onListenerDisconnected() {
        sConnected = false;
        sInstance = null;
        EventLog.get().log(EventLog.CAT_SERVICE, "通知监听器已断开");
        HealthWatch.evaluate(this);
        scheduleRebind(this);
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        if (sInstance == this) {
            sInstance = null;
            sConnected = false;
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (sbn == null) {
            return;
        }
        // 0.7.31-M6 / 52：重要度改从系统排名表读。
        // 注意必须覆写这个双参数版本 —— 框架调的就是它（单参数那版只是它的默认实现）。
        Ranking ranking = rankingOf(sbn, rankingMap);
        Heuristics.BannerSignals signals = Heuristics.judgeBanner(
                this, sbn, ranking, ForegroundAppTracker.get(), Prefs.getBannerMode());

        EventLog.get().log(EventLog.CAT_NOTIF,
                (signals.isBanner ? "★ " : "· ")
                        + (signals.pkg == null ? "(无包名)" : signals.pkg),
                signals.describe());

        if (signals.pkg != null) {
            // 记下通知来源（只有包名），供「状态位采样」当观察集
            Prefs.addNotifiedPackage(signals.pkg);
        }

        // 0.7.69-M6 / 90：通知中心或锁屏已经处于停留状态时，新通知不会再产生一次
        // 「面板展开」/「锁屏点亮」事件。这里在通知真正到达时补一次当前场景的触发。
        // 同一通知只走一个场景，顺序为：通知中心 > 锁屏 > 横幅；同一个 key 只触发一次。
        String packageName = sbn.getPackageName();
        boolean shadeOpen = ShadeAccessibilityService.isShadeOpen();
        boolean lockscreenActive = signals.screenInteractive
                && ScreenStateReceiver.isLocked(this);
        synchronized (mSceneNotifiedKeys) {
            if (!shadeOpen && !lockscreenActive) {
                mSceneNotifiedKeys.clear();
            }
        }

        // 分组摘要点开只是展开分组；5 秒内的重复更新也不是新通知。两者都不补场景触发，
        // 但仍允许后面的横幅分支按原规则处理（例如严格档的高重要度更新）。
        boolean sceneCandidate = packageName != null
                && !signals.groupSummary
                && !signals.isUpdate;

        if (shadeOpen && Prefs.isTriggerShade() && sceneCandidate) {
            if (markSceneNotification(sbn)) {
                TriggerBus.emit(new TriggerEvent(
                        TriggerType.SHADE_NOTIFICATION,
                        packageName,
                        "通知中心内新通知",
                        Collections.singletonList(packageName)));
            }
        } else if (lockscreenActive && Prefs.isTriggerLockscreen() && sceneCandidate) {
            // 屏幕刚点亮时的 400ms 锁屏探测会把这条通知一起算进去。探测还在等待/重试时
            // 只记 key、不单独发触发，避免探测和新通知各排一次。
            boolean first = markSceneNotification(sbn);
            if (first && !ScreenStateReceiver.isLockscreenProbePending()) {
                TriggerBus.emit(new TriggerEvent(
                        TriggerType.LOCKSCREEN_NOTIFICATION,
                        packageName,
                        "锁屏停留时新通知",
                        Collections.singletonList(packageName)));
            }
        } else if (signals.isBanner && Prefs.isTriggerBanner()) {
            TriggerBus.emit(new TriggerEvent(
                    TriggerType.BANNER_NOTIFICATION,
                    signals.pkg,
                    signals.reason,
                    Collections.singletonList(signals.pkg)));
        }
    }

    /** 标记本次场景停留期间第一次见到这个通知 key；key 为空时按新通知处理。 */
    private boolean markSceneNotification(StatusBarNotification sbn) {
        String key = sbn.getKey();
        if (key == null) {
            return true;
        }
        synchronized (mSceneNotifiedKeys) {
            return mSceneNotifiedKeys.add(key);
        }
    }

    /** 从排名表里取这条通知的读数；取不到返回 null（判定层会按老规则处理并写日志）。 */
    private static Ranking rankingOf(StatusBarNotification sbn, RankingMap rankingMap) {
        String key = sbn.getKey();
        if (rankingMap == null || key == null) {
            return null;
        }
        try {
            Ranking ranking = new Ranking();
            return rankingMap.getRanking(key, ranking) ? ranking : null;
        } catch (Exception e) {
            EventLog.get().log(EventLog.CAT_SERVICE, "读取通知排名失败：" + e);
            return null;
        }
    }

    /**
     * 当前的排名表。
     *
     * <p>锁屏那边要读「别人的渠道」只能靠它（{@code NotificationManager.getNotificationChannel} 只认
     * 自己的包）。没连上监听器时为 null，调用方退回旧路径。
     */
    public static RankingMap currentRanking() {
        NotificationAccessService service = sInstance;
        if (service == null || !sConnected) {
            return null;
        }
        try {
            return service.getCurrentRanking();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 系统把通知从状态栏移除时回调。
     *
     * <p>{@link #REASON_CLICK} 是用户点开通知的唯一公开信号，且发生在点击之后。它不走
     * 「记录通知移除事件」开关：点击是低频、用来解释卡顿时序的关键证据，默认就要留下；
     * 滑动清除等其它原因仍然只在用户打开那枚开关时记录。
     */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        handleNotificationRemoved(sbn, reason);
    }

    /** 兼容只回调单参数版本的系统；这里拿不到 reason，按普通移除处理。 */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        handleNotificationRemoved(sbn, 0);
    }

    private void handleNotificationRemoved(StatusBarNotification sbn, int reason) {
        if (sbn == null) {
            return;
        }
        if (reason == REASON_CLICK) {
            logNotificationClick(sbn);
            return;
        }
        if (!Prefs.isLogRemoved()) {
            return;
        }
        String key = sbn.getKey();
        EventLog.get().log(EventLog.CAT_NOTIF,
                "移除 " + sbn.getPackageName(),
                "key=" + Integer.toHexString(key == null ? 0 : key.hashCode()));
    }

    /**
     * 记录一次点击。日志页的时间由 {@link EventLog} 自动打，数据里再写通知的发布时刻与
     * 点击距发布的毫秒数，方便和相邻的「触发 / 预热」记录对齐，判断是预热还没完成就点了，
     * 还是预热完成后点开仍然慢。
     *
     * <p>按要求不记录通知标题与正文，只用应用名、包名、key 哈希与渠道定位。
     */
    private void logNotificationClick(StatusBarNotification sbn) {
        long clickedAt = System.currentTimeMillis();
        String packageName = sbn.getPackageName();
        String key = sbn.getKey();
        String channelId = null;
        try {
            if (sbn.getNotification() != null) {
                channelId = sbn.getNotification().getChannelId();
            }
        } catch (Throwable ignored) {
            // 读不到渠道就只记能读到的部分
        }

        String label = labelOf(packageName);
        String text = "点击通知 " + (packageName == null
                ? "(无包名)"
                : (label.isEmpty() ? packageName : label + "（" + packageName + "）"));

        StringBuilder data = new StringBuilder();
        data.append("key=").append(Integer.toHexString(key == null ? 0 : key.hashCode()));
        if (channelId != null) {
            data.append(" 渠道=").append(channelId);
        }
        long postTime = sbn.getPostTime();
        if (postTime > 0L) {
            long elapsed = Math.max(0L, clickedAt - postTime);
            data.append(" 发布=")
                    .append(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                            .format(new Date(postTime)))
                    .append(" 距发布=").append(elapsed).append("ms");
        }
        EventLog.get().log(EventLog.CAT_NOTIF, text, data.toString());
    }

    /** 应用名；读不到时返回空串，由调用方退回包名。 */
    private String labelOf(String packageName) {
        if (packageName == null) {
            return "";
        }
        try {
            PackageManager manager = getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
            CharSequence label = manager.getApplicationLabel(info);
            return label == null ? "" : label.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

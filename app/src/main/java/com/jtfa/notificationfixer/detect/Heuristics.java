package com.jtfa.notificationfixer.detect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;

import com.jtfa.notificationfixer.core.Prefs;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判定规则。
 *
 * <p>M1 的关键点不是「判定得多准」，而是把所有中间信号都写进日志，
 * 事后可以据此判断是规则太严还是太松。因此这里返回的是信号集合，
 * 而不是一个 boolean。
 *
 * <p><b>0.7.31-M6 / 52 换掉了「重要度」的读法。</b>原来用
 * {@link NotificationManager#getNotificationChannel(String)} 读渠道重要度，可这个公开 API 的文档
 * 写得很直：渠道必须属于<b>本应用自己的包</b>（或本应用是它的通知代理），否则不返回 —— 第三方应用
 * 一律 null。于是仅横幅档对第三方通知永远判否，而日志里只有一个「重要度=?」，看不出是
 * 「没读到」还是「真低」。现在改用通知监听回调里那份 {@link Ranking}：系统<b>实际使用</b>的有效
 * 重要度、渠道对象（任何包的渠道都在里面）、ambient、打断过滤、视觉抑制位，旧读法只留作对照。
 */
public final class Heuristics {

    /** 同一 key 在这个时间内再次出现，视为「重复更新」而非新通知。 */
    private static final long UPDATE_WINDOW_MS = 5000L;

    /** 渠道未指定锁屏可见性时的取值，等价于 NotificationManager 内部的 NO_OVERRIDE。 */
    private static final int VISIBILITY_NO_OVERRIDE = -1000;

    private static final int SEEN_CACHE_LIMIT = 400;

    private static final Map<String, Long> sSeenKeys = new LinkedHashMap<>();

    private Heuristics() {
    }

    /** 一次横幅判定的全部输入与结论。 */
    public static final class BannerSignals {

        public String pkg;
        public String keyHash;
        public String channelId;
        public String category;

        /** 系统排名表给的有效重要度（用户 / 厂商设置覆盖之后的那份）。 */
        public int importance = NotificationManager.IMPORTANCE_NONE;

        /** 有效重要度有没有取到。取不到时按老规则：仅横幅档判否、所有新通知档判是。 */
        public boolean importanceKnown;

        /** 非空 = 这份有效重要度被用户或系统改过，内容就是那句解释。 */
        public CharSequence importanceExplanation;

        /** 渠道对象自己的重要度（与有效重要度并列写日志，两者不一致时一眼能看出来）。 */
        public int channelImportance = NotificationManager.IMPORTANCE_NONE;

        /** 渠道对象有没有读到（任何包的渠道都能从排名表里读到）。 */
        public boolean channelKnown;

        /** 旧的跨包读法（{@link NotificationManager#getNotificationChannel}）读到了没有。 */
        public boolean legacyChannelKnown;

        public int legacyImportance = NotificationManager.IMPORTANCE_NONE;

        /** 系统认为这条通知「不需要用户立刻注意」。 */
        public boolean ambient;

        /** 是否符合当前的打断过滤（勿扰 / 静音等）。 */
        public boolean matchesInterruptionFilter = true;

        /** 视觉抑制位，见 {@link NotificationManager.Policy#SUPPRESSED_EFFECT_PEEK}。 */
        public int suppressedVisualEffects;

        /** 判定那一刻屏幕是否真正点亮（AOD 算灭；只写日志，门禁在触发层与引擎）。 */
        public boolean screenInteractive;

        public int visibility = Notification.VISIBILITY_PRIVATE;
        public boolean groupSummary;
        public boolean ongoing;
        public boolean fullScreenIntent;
        public boolean appInForeground;
        public boolean isUpdate;
        public boolean noPackage;

        /** 系统排名表里的「锁屏可见性覆盖」；NO_OVERRIDE 表示没有单独覆盖。 */
        public int lockscreenOverride = VISIBILITY_NO_OVERRIDE;
        public boolean lockscreenOverrideKnown;

        /** 按「覆盖 → 渠道 → 通知自身」算出的有效锁屏可见性。 */
        public int lockscreenVisibility = Notification.VISIBILITY_PRIVATE;

        /** 0–100：这条通知在锁屏上出现的置信度，供息屏记录与亮屏过滤使用。 */
        public int lockscreenConfidence;

        /** 是否满足全局新通知的触发条件：内层开 = 只认横幅，内层关 = 所有新通知。 */
        public boolean isBanner;
        public String reason;

        /** 一行式描述，直接进日志。 */
        public String describe() {
            return "app=" + (pkg == null ? "(null)" : pkg)
                    + " key=" + keyHash
                    + " 渠道=" + (channelId == null ? "(无)" : channelId)
                    + " 有效重要度=" + (importanceKnown ? importanceName(importance) : "?")
                    + (importanceExplanation == null ? "" : "（" + importanceExplanation + "）")
                    + " 渠道重要度=" + (channelKnown ? importanceName(channelImportance) : "?")
                    + " 摘要=" + groupSummary
                    + " 常驻=" + ongoing
                    + " 全屏=" + fullScreenIntent
                    + " 前台=" + appInForeground
                    + " 重复=" + isUpdate
                    + " 可见性=" + visibilityName(visibility)
                    + " 锁屏可见性=" + visibilityName(lockscreenVisibility)
                    + (lockscreenOverrideKnown
                            ? " 锁屏覆盖=" + visibilityName(lockscreenOverride) : "")
                    + " 锁屏置信度=" + lockscreenConfidence
                    + " 类别=" + (category == null ? "-" : category)
                    + " ambient=" + ambient
                    + " 过滤=" + (matchesInterruptionFilter ? "符合" : "被挡")
                    + " 抑制=" + suppressedName()
                    + " 屏幕=" + (screenInteractive ? "亮" : "灭")
                    + " 旧读=" + (legacyChannelKnown ? importanceName(legacyImportance) : "null")
                    + " → 判定=" + (isBanner ? "是" : "否")
                    + "（" + reason + "）";
        }

        private String suppressedName() {
            StringBuilder text = new StringBuilder("0x")
                    .append(Integer.toHexString(suppressedVisualEffects));
            if ((suppressedVisualEffects
                    & NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK) != 0) {
                text.append("[PEEK 被抑制]");
            }
            if ((suppressedVisualEffects
                    & NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF) != 0) {
                text.append("[息屏被抑制]");
            }
            return text.toString();
        }
    }

    /**
     * 判定一条刚到的通知是否满足全局新通知的触发条件。
     *
     * <p>内层「仅横幅通知触发」开 = 只认有效重要度为高及以上的横幅；关 = 所有非重复的新通知。
     *
     * @param ranking 系统排名表里这条通知的读数；取不到时为 null（此时按老规则：仅横幅档判否）
     */
    public static BannerSignals judgeBanner(Context context,
                                            StatusBarNotification sbn,
                                            Ranking ranking,
                                            String foregroundPackage,
                                            int mode) {
        BannerSignals signals = new BannerSignals();
        Notification notification = sbn.getNotification();

        signals.pkg = sbn.getPackageName();
        String key = sbn.getKey();
        signals.keyHash = Integer.toHexString(key == null ? 0 : key.hashCode());
        signals.noPackage = signals.pkg == null;

        int flags = notification.flags;
        signals.groupSummary = (flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        signals.ongoing = (flags & Notification.FLAG_ONGOING_EVENT) != 0;
        signals.fullScreenIntent = notification.fullScreenIntent != null;
        signals.visibility = notification.visibility;
        //noinspection deprecation
        signals.category = notification.category;
        signals.channelId = notification.getChannelId();
        signals.appInForeground = signals.pkg != null && signals.pkg.equals(foregroundPackage);

        // 系统排名表：有效重要度、渠道对象（别人的渠道只有这里读得到）、打断相关的几个位
        NotificationChannel channel = null;
        if (ranking != null) {
            signals.importance = ranking.getImportance();
            signals.importanceKnown = true;
            signals.importanceExplanation = ranking.getImportanceExplanation();
            signals.ambient = ranking.isAmbient();
            signals.matchesInterruptionFilter = ranking.matchesInterruptionFilter();
            signals.suppressedVisualEffects = ranking.getSuppressedVisualEffects();
            signals.lockscreenOverride = ranking.getLockscreenVisibilityOverride();
            signals.lockscreenOverrideKnown = true;
            channel = ranking.getChannel();
        }
        signals.screenInteractive = isScreenInteractive();

        // 锁屏可见性：覆盖 > 渠道 > 通知自身。息屏记录只存置信度，亮屏时再按滑杆过滤。
        Ranking lockRanking = ranking != null ? ranking : rankingOf(sbn);
        if (lockRanking != null) {
            signals.lockscreenOverride = lockRanking.getLockscreenVisibilityOverride();
            signals.lockscreenOverrideKnown = true;
        }
        signals.lockscreenVisibility = effectiveLockscreenVisibility(context, sbn, lockRanking);
        signals.lockscreenConfidence = lockscreenConfidence(context, sbn, lockRanking,
                signals.lockscreenVisibility);

        // 旧读法：0.7.31 起只是证据写进日志（第三方应用这里一直是 null），判定不再用它
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && signals.channelId != null) {
            NotificationChannel legacy = manager.getNotificationChannel(signals.channelId);
            signals.legacyChannelKnown = legacy != null;
            if (legacy != null) {
                signals.legacyImportance = legacy.getImportance();
                if (channel == null) {
                    channel = legacy;
                }
            }
        }
        signals.channelKnown = channel != null;
        if (channel != null) {
            signals.channelImportance = channel.getImportance();
        }

        signals.isUpdate = markSeen(key, System.currentTimeMillis());

        if (signals.noPackage) {
            signals.isBanner = false;
            signals.reason = "没有包名";
        } else if (signals.appInForeground) {
            signals.isBanner = false;
            signals.reason = "来源应用正在前台";
        } else if (signals.fullScreenIntent) {
            signals.isBanner = true;
            signals.reason = "带全屏 Intent（来电/闹钟类）";
        } else if (signals.groupSummary) {
            signals.isBanner = false;
            signals.reason = "分组摘要";
        } else if (!signals.importanceKnown) {
            // 排名表里没有这条（极少见）：按老规则，仅横幅档判否、所有新通知档判是
            signals.isBanner = mode == Prefs.MODE_LOOSE;
            signals.reason = "有效重要度未知";
        } else if (mode == Prefs.MODE_STRICT) {
            signals.isBanner = signals.importance >= NotificationManager.IMPORTANCE_HIGH;
            signals.reason = "仅横幅档：有效重要度 " + importanceName(signals.importance);
        } else {
            signals.isBanner = !signals.isUpdate;
            signals.reason = signals.isUpdate ? "所有新通知档：重复更新" : "所有新通知档：新通知";
        }
        return signals;
    }

    /**
     * 通知在锁屏上的实际可见性：应用级覆盖优先，其次渠道设置，最后是通知自身设置。
     *
     * <p>0.7.31-M6 / 52 之前这里读的是 {@link NotificationManager#getNotificationChannel(String)}，
     * 对第三方应用永远是 null —— 也就是说「渠道优先」这条规则从来没生效过，一直退回通知自身的值。
     * 现在改从系统排名表里取渠道对象，读不到（比如本应用的通知在排名表里没有）才退回旧路径。
     */
    public static int effectiveLockscreenVisibility(Context context, StatusBarNotification sbn) {
        return effectiveLockscreenVisibility(context, sbn, rankingOf(sbn));
    }

    /**
     * 锁屏可见性：系统排名表里的「应用级覆盖」优先，其次是渠道设置，最后才是通知自身。
     *
     * <p>{@code Ranking.getLockscreenVisibilityOverride()} 是 Android 11 起公开的逐应用覆盖；
     * 渠道的 {@code getLockscreenVisibility()} 仍按原顺序作为第二层。只有活动通知才读得到
     * 排名表，读不到就退回通知自身的值。
     */
    public static int effectiveLockscreenVisibility(Context context, StatusBarNotification sbn,
                                                    Ranking ranking) {
        Notification notification = sbn.getNotification();
        if (ranking != null) {
            int override = ranking.getLockscreenVisibilityOverride();
            if (override != VISIBILITY_NO_OVERRIDE) {
                return override;
            }
        }
        NotificationChannel channel = ranking != null ? ranking.getChannel() : rankingChannel(sbn);
        if (channel == null) {
            String channelId = notification.getChannelId();
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && channelId != null) {
                channel = manager.getNotificationChannel(channelId);
            }
        }
        if (channel != null
                && channel.getLockscreenVisibility() != VISIBILITY_NO_OVERRIDE) {
            return channel.getLockscreenVisibility();
        }
        return notification.visibility;
    }

    /**
     * 估计一条通知在锁屏上出现的置信度（0–100）。
     *
     * <p>这不是「已经画出来」的事实，只是到达时的公开信号；亮屏快速预热用它做过滤，
     * 精确度滑杆就是这条分数的阈值。分数越高越可能上锁屏：
     * PUBLIC 100、PRIVATE 60、未知 40、SECRET 0；全屏 Intent 直接 100；常驻 +10；
     * PRIVATE 且系统不允许私密通知上锁屏 -10；息屏被系统抑制 -30；勿扰挡住 -10。
     * 用户把精确度调低时阈值跟着降低，调到 0 就等于全部预热；调高则只留下强证据。
     */
    public static int lockscreenConfidence(Context context, StatusBarNotification sbn,
                                           Ranking ranking) {
        return lockscreenConfidence(context, sbn, ranking,
                effectiveLockscreenVisibility(context, sbn, ranking));
    }

    private static int lockscreenConfidence(Context context, StatusBarNotification sbn,
                                            Ranking ranking, int visibility) {
        Notification notification = sbn.getNotification();
        int confidence;
        if (visibility == Notification.VISIBILITY_PUBLIC) {
            confidence = 100;
        } else if (visibility == Notification.VISIBILITY_PRIVATE) {
            confidence = 60;
        } else if (visibility == Notification.VISIBILITY_SECRET) {
            confidence = 0;
        } else {
            confidence = 40;
        }
        if (notification.fullScreenIntent != null) {
            confidence = 100;
        }
        if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            confidence += 10;
        }
        if (visibility == Notification.VISIBILITY_PRIVATE
                && readSecureInt(context, "lock_screen_allow_private_notifications", 1) == 0) {
            confidence -= 10;
        }
        if (ranking != null) {
            if ((ranking.getSuppressedVisualEffects()
                    & NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF) != 0) {
                confidence -= 30;
            }
            if (!ranking.matchesInterruptionFilter()) {
                confidence -= 10;
            }
        }
        return Math.max(0, Math.min(100, confidence));
    }

    private static int readSecureInt(Context context, String key, int defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        try {
            return android.provider.Settings.Secure.getInt(
                    context.getContentResolver(), key, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /** 从系统排名表里取这条通知的读数；取不到返回 null。 */
    private static Ranking rankingOf(StatusBarNotification sbn) {
        String key = sbn.getKey();
        if (key == null) {
            return null;
        }
        RankingMap map = NotificationAccessService.currentRanking();
        if (map == null) {
            return null;
        }
        try {
            Ranking ranking = new Ranking();
            return map.getRanking(key, ranking) ? ranking : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从系统排名表里取这条通知的渠道对象。
     *
     * <p>这是普通应用能读到<b>别人</b>渠道的唯一一条路：
     * {@link NotificationManager#getNotificationChannel(String)} 只认自己的包（第三方一律 null），
     * 能按包名读的 {@code NotificationListenerService.getNotificationChannels(pkg, user)} 要
     * 「关联设备 / 通知助手」身份，普通监听器调用会抛 SecurityException。
     * 读不到返回 null，由调用方退回旧路径（不在这里写日志，免得每条通知刷两行）。
     */
    private static NotificationChannel rankingChannel(StatusBarNotification sbn) {
        Ranking ranking = rankingOf(sbn);
        return ranking == null ? null : ranking.getChannel();
    }

    /**
     * 屏幕是否真正点亮。这里是日志读数，判定本身由触发层与唤醒引擎的门禁负责。
     *
     * <p>用 {@link ScreenStateReceiver#isScreenOn()} 而不是 {@code PowerManager.isInteractive()}：
     * AOD / 唤醒过程中后者可能提前变成 true，日志会与「屏幕点亮」广播对不上。
     */
    private static boolean isScreenInteractive() {
        return ScreenStateReceiver.isScreenOn();
    }

    public static String importanceName(int importance) {
        switch (importance) {
            case NotificationManager.IMPORTANCE_MAX:
                return "MAX";
            case NotificationManager.IMPORTANCE_HIGH:
                return "HIGH";
            case NotificationManager.IMPORTANCE_DEFAULT:
                return "DEFAULT";
            case NotificationManager.IMPORTANCE_LOW:
                return "LOW";
            case NotificationManager.IMPORTANCE_MIN:
                return "MIN";
            case NotificationManager.IMPORTANCE_NONE:
                return "NONE";
            default:
                return String.valueOf(importance);
        }
    }

    public static String visibilityName(int visibility) {
        switch (visibility) {
            case Notification.VISIBILITY_PUBLIC:
                return "PUBLIC";
            case Notification.VISIBILITY_PRIVATE:
                return "PRIVATE";
            case Notification.VISIBILITY_SECRET:
                return "SECRET";
            case VISIBILITY_NO_OVERRIDE:
                return "NO_OVERRIDE";
            default:
                return String.valueOf(visibility);
        }
    }

    /** 记录该 key 的出现时间，返回它是否在 {@link #UPDATE_WINDOW_MS} 内出现过。 */
    private static synchronized boolean markSeen(String key, long now) {
        if (key == null) {
            return false;
        }
        Long lastSeen = sSeenKeys.put(key, now);
        if (sSeenKeys.size() > SEEN_CACHE_LIMIT) {
            Iterator<Map.Entry<String, Long>> iterator = sSeenKeys.entrySet().iterator();
            while (iterator.hasNext()) {
                if (now - iterator.next().getValue() > UPDATE_WINDOW_MS * 12) {
                    iterator.remove();
                }
            }
        }
        return lastSeen != null && now - lastSeen < UPDATE_WINDOW_MS;
    }
}

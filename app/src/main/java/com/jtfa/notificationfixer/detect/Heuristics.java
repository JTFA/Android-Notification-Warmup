package com.jtfa.notificationfixer.detect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.PowerManager;
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
 * 一律 null。于是严格档对第三方通知永远判「非横幅」，而日志里只有一个「重要度=?」，看不出是
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

        /** 有效重要度有没有取到。取不到时按老规则：严格档判否、宽松档判是。 */
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

        /** 判定那一刻屏幕亮不亮（横幅只可能在亮屏时弹）。 */
        public boolean screenInteractive;

        public int visibility = Notification.VISIBILITY_PRIVATE;
        public boolean groupSummary;
        public boolean ongoing;
        public boolean fullScreenIntent;
        public boolean appInForeground;
        public boolean isUpdate;
        public boolean noPackage;

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
                    + " 类别=" + (category == null ? "-" : category)
                    + " ambient=" + ambient
                    + " 过滤=" + (matchesInterruptionFilter ? "符合" : "被挡")
                    + " 抑制=" + suppressedName()
                    + " 屏幕=" + (screenInteractive ? "亮" : "灭")
                    + " 旧读=" + (legacyChannelKnown ? importanceName(legacyImportance) : "null")
                    + " → " + (isBanner ? "判定为横幅" : "非横幅")
                    + "（" + reason + "）";
        }

        private String suppressedName() {
            StringBuilder text = new StringBuilder("0x")
                    .append(Integer.toHexString(suppressedVisualEffects));
            if ((suppressedVisualEffects
                    & NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK) != 0) {
                text.append("[PEEK 被抑制]");
            }
            return text.toString();
        }
    }

    /**
     * 判定一条刚到的通知是不是横幅。
     *
     * @param ranking 系统排名表里这条通知的读数；取不到时为 null（此时按老规则：严格档判否）
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
            channel = ranking.getChannel();
        }
        signals.screenInteractive = isScreenInteractive(context);

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
            // 排名表里没有这条（极少见）：按老规则，严格档判否、宽松档判是
            signals.isBanner = mode == Prefs.MODE_LOOSE;
            signals.reason = "有效重要度未知";
        } else if (mode == Prefs.MODE_STRICT) {
            signals.isBanner = signals.importance >= NotificationManager.IMPORTANCE_HIGH;
            signals.reason = "严格档：有效重要度 " + importanceName(signals.importance);
        } else {
            signals.isBanner = !signals.isUpdate;
            signals.reason = signals.isUpdate ? "宽松档：重复更新" : "宽松档：新通知";
        }
        return signals;
    }

    /**
     * 通知在锁屏上的实际可见性：渠道设置优先于通知自身设置。
     *
     * <p>0.7.31-M6 / 52 之前这里读的是 {@link NotificationManager#getNotificationChannel(String)}，
     * 对第三方应用永远是 null —— 也就是说「渠道优先」这条规则从来没生效过，一直退回通知自身的值。
     * 现在改从系统排名表里取渠道对象，读不到（比如本应用的通知在排名表里没有）才退回旧路径。
     */
    public static int effectiveLockscreenVisibility(Context context, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        NotificationChannel channel = rankingChannel(sbn);
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
     * 从系统排名表里取这条通知的渠道对象。
     *
     * <p>这是普通应用能读到<b>别人</b>渠道的唯一一条路：
     * {@link NotificationManager#getNotificationChannel(String)} 只认自己的包（第三方一律 null），
     * 能按包名读的 {@code NotificationListenerService.getNotificationChannels(pkg, user)} 要
     * 「关联设备 / 通知助手」身份，普通监听器调用会抛 SecurityException。
     * 读不到返回 null，由调用方退回旧路径（不在这里写日志，免得每条通知刷两行）。
     */
    private static NotificationChannel rankingChannel(StatusBarNotification sbn) {
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
            if (map.getRanking(key, ranking)) {
                return ranking.getChannel();
            }
        } catch (Exception e) {
            // 读不到就退回旧路径
        }
        return null;
    }

    /** 屏幕亮着吗。只写日志、不参与判定：横幅只可能在亮屏时弹。 */
    private static boolean isScreenInteractive(Context context) {
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return power != null && power.isInteractive();
        } catch (Exception e) {
            return false;
        }
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

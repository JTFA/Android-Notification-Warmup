package com.jtfa.notificationfixer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.jtfa.notificationfixer.R;
import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.ui.MainActivity;

/**
 * 本应用自己的两条通知与两条渠道。
 *
 * <ul>
 *   <li>{@link #CHANNEL_MONITOR}（重要度最低）：前台服务那条常驻通知挂它，一直是这样；</li>
 *   <li>{@link #CHANNEL_ALERT}（重要度默认，有提示音）：服务异常时那条提醒挂它，可划掉。</li>
 * </ul>
 *
 * <p>0.7.25-M6 / 46 曾给「本应用自己的通知」加过三个档位（始终显示 / 仅在异常时 / 不显示），
 * 靠第三条 <b>静默渠道</b>（请求重要度「不显示」）实现「平时不显示那条常驻通知」。真机实测
 * ColorOS <b>不接受</b> {@code IMPORTANCE_NONE}：它把那条渠道定成「低」，通知照样列在通知栏里。
 * 而前台服务按系统要求必须带一条通知，这个「不显示」在应用侧做不到 —— 于是 0.7.27-M6 / 48 把三个
 * 档位整块撤掉：常驻那条回到一直显示（挂 {@link #CHANNEL_MONITOR}），只留四枚「什么算服务异常」
 * 的开关管那条异常提醒。真想看不见那条常驻通知，只有用户自己在系统设置里关掉它所属的渠道
 * （设置页那个「系统通知设置」按钮直达），关掉之后前台服务照常跑。
 *
 * <p>老版本建出来的静默渠道 {@code keepalive_silent} 已经没人用了，见到就顺手删掉
 * （{@link #ensureChannels}），免得它一直挂在系统设置里让人以为还有什么用处。
 */
public final class AppNotifications {

    /** 常驻监控通知的渠道。0.7.24 及以前只有这一条。 */
    public static final String CHANNEL_MONITOR = "keepalive";

    /** 异常提醒渠道。 */
    public static final String CHANNEL_ALERT = "keepalive_alert";

    /** 前台服务那条通知的 id（与 0.7.24 及以前一致）。 */
    public static final int ID_MONITOR = 1001;

    /** 异常提醒那条通知的 id。 */
    public static final int ID_ALERT = 1002;

    /** 0.7.25 / 46 到 0.7.26 / 47 用过的那条静默渠道；48 起没人用了，见到就删。 */
    private static final String CHANNEL_SILENT_GONE = "keepalive_silent";

    /** 上一次写进日志的监控渠道重要度（-1 = 这条渠道还不存在）。 */
    private static int sLastLoggedMonitorImportance = Integer.MIN_VALUE;

    private AppNotifications() {
    }

    /**
     * 建好两条渠道，并清掉不再使用的那条老渠道。
     *
     * <p>已经存在的渠道只会更新名字与说明（用户在系统设置里改过的开关/重要度不会被覆盖回来，
     * 平台行为），所以每次服务启动都调一次是安全的，也是「用户自己关掉某条渠道」这条路的前提。
     */
    public static void ensureChannels(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        create(manager, CHANNEL_MONITOR,
                context.getString(R.string.keepalive_channel_name),
                context.getString(R.string.keepalive_channel_desc),
                NotificationManager.IMPORTANCE_MIN);
        create(manager, CHANNEL_ALERT,
                context.getString(R.string.notify_channel_alert_name),
                context.getString(R.string.notify_channel_alert_desc),
                NotificationManager.IMPORTANCE_DEFAULT);
        removeUnusedSilent(manager, context);
        logMonitorImportance(context);
    }

    private static void create(NotificationManager manager, String id,
                               CharSequence name, CharSequence desc, int importance) {
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        channel.setDescription(desc == null ? "" : desc.toString());
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /**
     * 删掉 0.7.25 / 46 建出来、现在已经没人用的那条静默渠道。
     *
     * <p>只在它确实还在的时候动手，删完写一行日志。这条渠道是应用自己建的、语义已经不存在，
     * 删掉比留在系统设置里干净。
     */
    private static void removeUnusedSilent(NotificationManager manager, Context context) {
        try {
            if (manager.getNotificationChannel(CHANNEL_SILENT_GONE) == null) {
                return;
            }
            manager.deleteNotificationChannel(CHANNEL_SILENT_GONE);
            EventLog.get().log(EventLog.CAT_SERVICE, "删掉了不再使用的那条渠道",
                    "渠道 " + CHANNEL_SILENT_GONE + "；areNotificationsEnabled="
                            + systemEnabled(context));
        } catch (Throwable t) {
            EventLog.get().log(EventLog.CAT_SERVICE, "删老渠道失败：" + t);
        }
    }

    /**
     * 每次服务启动都如实读回一次监控渠道的重要度，变了才写一行。
     *
     * <p>这是「那条常驻通知到底有多安静」唯一可信的原始读数（本应用不声明 INTERNET，用户手上又
     * 不一定有 adb，界面里那句「重要度=2」也没人看得懂），数值的意思照实说，不替系统美化。
     */
    private static void logMonitorImportance(Context context) {
        int actual = channelImportance(context, CHANNEL_MONITOR);
        if (actual == sLastLoggedMonitorImportance) {
            return;
        }
        sLastLoggedMonitorImportance = actual;
        EventLog.get().log(EventLog.CAT_SERVICE, "监控渠道重要度：" + importanceText(actual),
                "渠道 " + CHANNEL_MONITOR + "；areNotificationsEnabled=" + systemEnabled(context));
    }

    /** 渠道读回来这个重要度意味着什么。 */
    private static String importanceText(int importance) {
        switch (importance) {
            case NotificationManager.IMPORTANCE_NONE:
                return "不显示（这条渠道在系统里被关掉了）";
            case NotificationManager.IMPORTANCE_MIN:
                return "最低（安静折叠在通知栏底部，不出图标、不发声）";
            case NotificationManager.IMPORTANCE_LOW:
                return "低 —— 安静（无声音、无横幅）但仍会列在通知栏里、可以划掉；"
                        + "想彻底没有，到系统通知设置里关掉「监听服务」这条渠道";
            case -1:
                return "读不到（这条渠道还没建立）";
            default:
                return "重要度 " + importance + "（不是我们请求的值，系统自己定的）";
        }
    }

    /** 前台服务那条常驻通知（内容与 0.7.24 及以前一致）。 */
    public static Notification buildMonitor(Context context) {
        return new NotificationCompat.Builder(context, CHANNEL_MONITOR)
                .setSmallIcon(R.drawable.ic_stat_prewarm)
                .setContentTitle(context.getString(R.string.keepalive_title))
                .setContentText(context.getString(R.string.keepalive_text))
                .setContentIntent(contentIntent(context))
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 服务异常那条提醒。
     *
     * <p>它不是前台服务通知：可以划掉、可以点开（进本应用），不再异常时由
     * {@link #clearAlert} 撤掉。同一时刻只会有一条，异常项变化时是就地更新。
     */
    public static void postAlert(Context context, CharSequence text) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_stat_prewarm)
                .setContentTitle(context.getString(R.string.notify_alert_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent(context))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build();
        try {
            manager.notify(ID_ALERT, notification);
        } catch (Throwable t) {
            EventLog.get().log(EventLog.CAT_SERVICE, "异常提醒发不出去：" + t);
        }
    }

    /** 撤掉异常提醒（现在没有成立的异常项）。 */
    public static void clearAlert(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(ID_ALERT);
        }
    }

    /** 系统里本应用的通知总开关（用户在系统设置里关掉整个应用的通知时为 false）。 */
    public static boolean systemEnabled(Context context) {
        try {
            return NotificationManagerCompat.from(context).areNotificationsEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读回某条渠道的重要度；渠道不存在或读不到返回 -1。 */
    private static int channelImportance(Context context, String channelId) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return -1;
        }
        try {
            NotificationChannel channel = manager.getNotificationChannel(channelId);
            return channel == null ? -1 : channel.getImportance();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static PendingIntent contentIntent(Context context) {
        return PendingIntent.getActivity(
                context,
                0,
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

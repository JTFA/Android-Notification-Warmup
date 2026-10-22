package com.jtfa.notificationfixer.detect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import com.jtfa.notificationfixer.core.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「实时通知」这一个读数（0.7.35-M6 / 56）。
 *
 * <p>为什么需要它：ColorOS 的流体云、vivo 的原子岛、华为的实况窗都是厂商自己渲染的卡片。
 * 55 刚学会从锁屏行里认应用，可真机读数（2026-10-18 23:30，55 版）说明这条路对它们无效 ——
 * 那两行的行内既没有来源应用的包名，也没有应用名：
 *
 * <pre>
 * 第 0 行[FrameLayout 包名=- 文字=终有一天我们会相逢/04:45/progress_bar/05:06/收藏/扬声器/…]  ← 媒体卡片
 * 第 1 行[FrameLayout 包名=- 文字=剩余645米，约10分钟/沿太仆寺街/直行174米/目的地]        ← 导航卡片（流体云）
 * 第 2 行[FrameLayout expandableNotificationRow 包名=- 文字=OPPO 健康/刚刚/…]            ← 普通通知，靠应用名认出
 * </pre>
 *
 * <p>所以改从<b>通知中心那一侧</b>认：这类通知本身在通知里就带着凭据（系统提升位、媒体会话、样式、
 * 常驻、进度……），卡片是谁画的都不影响我们读那些字段。按档位取并集：
 *
 * <ul>
 *   <li>{@link Prefs#LIVE_WARM_STRICT}：系统提升位 / 媒体 / 通话 —— 有凭据的硬信号；</li>
 *   <li>{@link Prefs#LIVE_WARM_NORMAL}（默认）：再加「符合 Android 官方 Live Updates 提升条件」的
 *       那批（常驻 + 有标题 + 无自定义布局 + 非摘要 + 非彩色 + 渠道非 MIN + 样式在可提升名单里），
 *       导航 / 外卖 / 打车这类卡片背后的通知就是这一批；</li>
 *   <li>{@link Prefs#LIVE_WARM_WIDE}：再加所有常驻 / 前台服务通知（会把「只为保活挂常驻通知」的
 *       应用也算进来）。</li>
 * </ul>
 *
 * <p>凭据都是通知自己的公开字段（{@code flags} 与 {@code extras}），读法在
 * {@link #signalsOf}；活跃媒体会话另走 {@link MediaSessionManager#getActiveSessions}（我们是通知
 * 监听，不需要额外权限），它能覆盖「通知被划掉但还在放」的情况。逐条凭据都写进「诊断」日志，
 * 包括「差一条就没进」的原因，所以档位选错能一眼看出来 —— 判断只用这些字段，日志里也只有包名与
 * 这些布尔量，不读通知内容。
 *
 * <p>与锁屏行读数（{@link LockscreenWatch}）的关系：那一条认「画出来的那一行是谁」，这一条认
 * 「现在哪些应用有实时通知」，两者取并集，本条排前面（单次上限先保它们）。
 */
final class LiveNotifications {

    /** {@code FLAG_PROMOTED_ONGOING}（{@code Notification.java:788}）：系统已经把它提升成实时通知。 */
    private static final int FLAG_PROMOTED_ONGOING = 0x00040000;

    /** 应用要在清单里声明它才可能被提升（读法只写日志，不当条件 —— 走厂商私有通道的都不声明）。 */
    private static final String PERMISSION_POST_PROMOTED = "android.permission.POST_PROMOTED_NOTIFICATIONS";

    /** 官方「可提升」的样式名单，见 Android 开发者文档 Create live update notifications。 */
    private static final Set<String> PROMOTABLE_STYLES = new LinkedHashSet<>();

    static {
        Collections.addAll(PROMOTABLE_STYLES, "BigTextStyle", "CallStyle", "ProgressStyle", "MetricStyle");
    }

    /** 诊断明细里最多列多少条「像实时」的通知。 */
    private static final int MAX_DETAIL_ITEMS = 40;

    private LiveNotifications() {
    }

    /** 一次「实时通知」读数。 */
    static final class Report {

        /** 实时集合（最近发布在前），就是无条件预热的那几个应用。 */
        final List<String> packages = new ArrayList<>();

        /** 一句话结论，例如「实时通知（中档）2 个应用」，或「实时通知：已关闭」。 */
        String summary = "";

        /** 逐条凭据的明细，只在「记录诊断日志」打开时非空。 */
        String detail = "";

        boolean isEmpty() {
            return packages.isEmpty();
        }
    }

    /**
     * 按档位算这一轮的实时集合。
     *
     * @param withDetail 是否连带把逐条凭据写进 {@link Report#detail}（供真机核对与适配）
     */
    static Report collect(Context context, int mode, boolean withDetail) {
        Report report = new Report();
        if (mode == Prefs.LIVE_WARM_OFF) {
            report.summary = "实时通知：已关闭";
            return report;
        }

        List<StatusBarNotification> active = sortedNewestFirst(NotificationAccessService.activeNotifications());
        RankingMap rankingMap = NotificationAccessService.currentRanking();
        PackageManager packageManager = context.getPackageManager();
        String self = context.getPackageName();
        Sessions sessions = activeMediaSessions(context);

        StringBuilder detail = withDetail ? new StringBuilder() : null;
        List<String> hitReasons = new ArrayList<>();
        int likeLive = 0;
        int listed = 0;

        for (StatusBarNotification sbn : active) {
            if (sbn == null || sbn.getPackageName() == null) {
                continue;
            }
            String packageName = sbn.getPackageName();
            if (self.equals(packageName)) {
                continue;                       // 本应用自己不算
            }
            Signals signals = signalsOf(sbn, rankingMap);
            if (signals.live(mode)) {
                if (!report.packages.contains(packageName)) {
                    report.packages.add(packageName);
                    hitReasons.add(packageName + "（" + signals.reason(mode) + "）");
                }
            }
            if (signals.looksLive()) {
                likeLive++;
                if (detail != null && listed < MAX_DETAIL_ITEMS) {
                    listed++;
                    // 只有「像实时」的这几条才值得去问应用清单（一次一包，走 IPC）
                    signals.declaresPromotion = declaresPromotion(packageManager, packageName);
                    detail.append(signals.describe(mode)).append("；");
                }
            }
        }

        // 活跃媒体会话：通知被划掉、但还真的在放的那些应用，只有这一条路认得出来
        for (Map.Entry<String, String> entry : sessions.playing.entrySet()) {
            String packageName = entry.getKey();
            if (self.equals(packageName) || report.packages.contains(packageName)) {
                continue;
            }
            report.packages.add(packageName);
            hitReasons.add(packageName + "（活跃媒体会话 " + entry.getValue() + "）");
        }

        report.summary = "实时通知（" + modeName(mode) + "）" + report.packages.size() + " 个应用"
                + (report.packages.isEmpty() ? "" : "：" + join(report.packages, 5));

        if (detail != null) {
            StringBuilder text = new StringBuilder();
            if (!hitReasons.isEmpty()) {
                text.append("实时集合：").append(join(hitReasons, 5)).append("；");
            }
            if (listed > 0) {
                text.append("实时凭据：").append(detail);
                if (likeLive > listed) {
                    text.append("（还有 ").append(likeLive - listed).append(" 条像实时的通知未列出）");
                }
            }
            if (!sessions.playing.isEmpty() || !sessions.note.isEmpty()) {
                text.append("活跃媒体会话：");
                if (sessions.playing.isEmpty()) {
                    text.append("无");
                } else {
                    List<String> names = new ArrayList<>();
                    for (Map.Entry<String, String> entry : sessions.playing.entrySet()) {
                        names.add(entry.getKey() + "(" + entry.getValue() + ")");
                    }
                    text.append(join(names, 5));
                }
                if (!sessions.note.isEmpty()) {
                    text.append("；").append(sessions.note);
                }
            }
            report.detail = text.toString();
        }
        return report;
    }

    // ------------------------------------------------------------------ 逐条凭据

    /** 一条通知的实时凭据（全是公开字段）。 */
    private static final class Signals {

        String packageName = "";
        String styleName = "标准";
        boolean promotableStyle;
        boolean promoted;
        boolean ongoing;
        boolean foregroundService;
        boolean mediaNotification;
        boolean callStyle;
        boolean hasProgress;
        boolean hasTitle;
        boolean customViews;
        boolean groupSummary;
        boolean colorized;
        int importance = -1;
        boolean declaresPromotion;
        boolean promotable;

        /** 有凭据的硬信号：系统已提升 / 媒体 / 通话。 */
        boolean hard() {
            return promoted || mediaNotification || callStyle;
        }

        boolean live(int mode) {
            if (hard()) {
                return true;
            }
            if (mode == Prefs.LIVE_WARM_STRICT) {
                return false;
            }
            if (promotable) {
                return true;
            }
            return mode == Prefs.LIVE_WARM_WIDE && (ongoing || foregroundService);
        }

        /** 值不值得写进诊断明细：像实时的那几条。 */
        boolean looksLive() {
            return promoted || ongoing || foregroundService || mediaNotification || callStyle
                    || hasProgress;
        }

        /** 凭什么进集合 / 为什么没进。 */
        String reason(int mode) {
            if (promoted) {
                return "系统已提升";
            }
            if (mediaNotification) {
                return "媒体通知";
            }
            if (callStyle) {
                return "通话通知";
            }
            if (mode == Prefs.LIVE_WARM_STRICT) {
                return "严档只认这三类";
            }
            if (promotable) {
                return "符合提升条件";
            }
            if (mode == Prefs.LIVE_WARM_WIDE && (ongoing || foregroundService)) {
                return "常驻 / 前台服务";
            }
            return "没进：" + firstMiss();
        }

        private String firstMiss() {
            if (!ongoing) {
                return "不是常驻";
            }
            if (!hasTitle) {
                return "没有标题";
            }
            if (customViews) {
                return "带自定义布局";
            }
            if (groupSummary) {
                return "是分组摘要";
            }
            if (colorized) {
                return "设了彩色";
            }
            if (importance == NotificationManager.IMPORTANCE_MIN) {
                return "渠道重要度是 MIN";
            }
            if (!promotableStyle) {
                return "样式是 " + styleName;
            }
            return "符合提升条件（不该走到这里）";
        }

        String describe(int mode) {
            return packageName + "[提升=" + flag(promoted)
                    + " 媒体=" + flag(mediaNotification)
                    + " 通话=" + flag(callStyle)
                    + " 常驻=" + flag(ongoing)
                    + " 前台=" + flag(foregroundService)
                    + " 进度=" + flag(hasProgress)
                    + " 标题=" + flag(hasTitle)
                    + " 自定义布局=" + flag(customViews)
                    + " 摘要=" + flag(groupSummary)
                    + " 彩色=" + flag(colorized)
                    + " 渠道=" + (importance < 0 ? "?" : String.valueOf(importance))
                    + " 样式=" + styleName
                    + " 声明提升权限=" + flag(declaresPromotion)
                    + "] → " + reason(mode);
        }
    }

    private static Signals signalsOf(StatusBarNotification sbn, RankingMap rankingMap) {
        Notification notification = sbn.getNotification();
        Signals signals = new Signals();
        signals.packageName = sbn.getPackageName();
        int flags = notification.flags;
        signals.promoted = (flags & FLAG_PROMOTED_ONGOING) != 0;
        signals.ongoing = (flags & Notification.FLAG_ONGOING_EVENT) != 0;
        signals.foregroundService = (flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
        signals.groupSummary = (flags & Notification.FLAG_GROUP_SUMMARY) != 0;

        signals.mediaNotification = notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
        signals.hasProgress = notification.extras.containsKey(Notification.EXTRA_PROGRESS);
        signals.hasTitle = !TextUtils.isEmpty(notification.extras.getCharSequence(Notification.EXTRA_TITLE));
        signals.colorized = notification.extras.getBoolean(Notification.EXTRA_COLORIZED, false);
        signals.customViews = notification.contentView != null
                || notification.bigContentView != null
                || notification.headsUpContentView != null;

        CharSequence template = notification.extras.getCharSequence(Notification.EXTRA_TEMPLATE);
        signals.styleName = styleName(template);
        signals.callStyle = "CallStyle".equals(signals.styleName);
        signals.promotableStyle = template == null || PROMOTABLE_STYLES.contains(signals.styleName);

        signals.importance = importanceOf(rankingMap, sbn.getKey());

        // 官方那套「符合提升条件」自己算一遍（不含应用权限与用户开关，那两项只写日志）
        signals.promotable = signals.ongoing
                && signals.hasTitle
                && signals.promotableStyle
                && !signals.customViews
                && !signals.groupSummary
                && !signals.colorized
                && signals.importance != NotificationManager.IMPORTANCE_MIN;
        return signals;
    }

    /** {@code androidx} / 平台都把样式类名写进 {@code EXTRA_TEMPLATE}，这里只留短名。 */
    private static String styleName(CharSequence template) {
        if (template == null || template.length() == 0) {
            return "标准";
        }
        String value = template.toString();
        int cut = Math.max(value.lastIndexOf('$'), value.lastIndexOf('.'));
        return cut >= 0 && cut + 1 < value.length() ? value.substring(cut + 1) : value;
    }

    /** 有效渠道重要度；读不到排名表给 -1。 */
    private static int importanceOf(RankingMap rankingMap, String key) {
        if (rankingMap == null || key == null) {
            return -1;
        }
        try {
            Ranking ranking = new Ranking();
            if (!rankingMap.getRanking(key, ranking)) {
                return -1;
            }
            NotificationChannel channel = ranking.getChannel();
            return channel == null ? -1 : channel.getImportance();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 应用有没有在清单里声明提升权限（只作日志线索，不当条件）。 */
    private static boolean declaresPromotion(PackageManager packageManager, String packageName) {
        try {
            PackageInfo info = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
            String[] requested = info.requestedPermissions;
            if (requested == null) {
                return false;
            }
            for (String permission : requested) {
                if (PERMISSION_POST_PROMOTED.equals(permission)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 查不到就当没有
        }
        return false;
    }

    // ------------------------------------------------------------------ 活跃媒体会话

    private static final class Sessions {

        /** 包名 → 播放态（只收真在放的）。 */
        final Map<String, String> playing = new LinkedHashMap<>();

        /** 读不到时的一句话（ColorOS 有可能不放行）。 */
        String note = "";
    }

    /**
     * 活跃媒体会话。
     *
     * <p>不需要额外权限：我们是通知监听服务，正是 {@code getActiveSessions} 允许的两类调用者之一。
     * 只把 <b>PLAYING / BUFFERING</b> 这类真在放（或正要放）的算命中；暂停 / 停止的只在日志里出现
     * 一次，不当命中 —— 一个挂在后台好几小时、早已暂停的会话不值得为它预热。
     */
    private static Sessions activeMediaSessions(Context context) {
        Sessions sessions = new Sessions();
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) {
                sessions.note = "读不到（系统没有媒体会话服务）";
                return sessions;
            }
            List<MediaController> controllers = manager.getActiveSessions(
                    new ComponentName(context, NotificationAccessService.class));
            if (controllers == null) {
                sessions.note = "读不到（返回空）";
                return sessions;
            }
            for (MediaController controller : controllers) {
                if (controller == null || controller.getPackageName() == null) {
                    continue;
                }
                String state = playbackName(controller.getPlaybackState());
                if (state == null) {
                    continue;
                }
                sessions.playing.put(controller.getPackageName(), state);
            }
        } catch (Throwable t) {
            sessions.note = "读不到（" + t.getClass().getSimpleName() + "）";
        }
        return sessions;
    }

    private static String playbackName(PlaybackState state) {
        if (state == null) {
            return null;
        }
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
                return "PLAYING";
            case PlaybackState.STATE_BUFFERING:
                return "BUFFERING";
            case PlaybackState.STATE_FAST_FORWARDING:
                return "FAST_FORWARDING";
            case PlaybackState.STATE_REWINDING:
                return "REWINDING";
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
                return "SKIPPING_TO_NEXT";
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                return "SKIPPING_TO_PREVIOUS";
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ 小工具

    static String modeName(int mode) {
        switch (mode) {
            case Prefs.LIVE_WARM_STRICT:
                return "严";
            case Prefs.LIVE_WARM_WIDE:
                return "宽";
            default:
                return "中";
        }
    }

    private static List<StatusBarNotification> sortedNewestFirst(List<StatusBarNotification> source) {
        List<StatusBarNotification> sorted = new ArrayList<>(source);
        Collections.sort(sorted, new Comparator<StatusBarNotification>() {
            @Override
            public int compare(StatusBarNotification left, StatusBarNotification right) {
                return Long.compare(right.getPostTime(), left.getPostTime());
            }
        });
        return sorted;
    }

    private static String flag(boolean value) {
        return value ? "1" : "0";
    }

    /** 拼一段不超过 {@code max} 项的列表，多出来的用「等 N 个」收尾。 */
    static String join(List<String> values, int max) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size() && i < max; i++) {
            if (i > 0) {
                builder.append('、');
            }
            builder.append(values.get(i));
        }
        if (values.size() > max) {
            builder.append(" 等 ").append(values.size()).append(" 个");
        }
        return builder.toString();
    }
}

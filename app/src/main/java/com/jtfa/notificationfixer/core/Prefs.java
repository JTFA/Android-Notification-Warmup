package com.jtfa.notificationfixer.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 设置存储。
 *
 * <p>M1 只需要三个触发开关和判定档位，用 SharedPreferences 足够；
 * 名单与统计（M2）再引入更重的存储。
 */
public final class Prefs {

    private static final String NAME = "notification_fixer_settings";

    private static final String KEY_TRIGGER_BANNER = "trigger_banner";
    private static final String KEY_TRIGGER_SHADE = "trigger_shade";
    private static final String KEY_TRIGGER_LOCKSCREEN = "trigger_lockscreen";
    private static final String KEY_SCREEN_OFF_FAST_WARM = "screen_off_fast_warm";
    private static final String KEY_LOCKSCREEN_MODE = "lockscreen_mode";
    private static final String KEY_LOCKSCREEN_FILTER_PRECISION = "lockscreen_filter_precision";
    private static final String KEY_LIVE_WARM_MODE = "live_warm_mode";
    private static final String KEY_BANNER_MODE = "banner_mode";
    private static final String KEY_BANNER_ACTIVITY_MODE = "banner_activity_mode";
    private static final String KEY_IME_POLL_MS = "ime_poll_ms";
    private static final String KEY_IME_DEFER_MAX_MS = "ime_defer_max_ms";
    private static final String KEY_DIAG_LOG = "diag_log";
    private static final String KEY_LOG_REMOVED = "log_removed";
    private static final String KEY_WARM_ENABLED = "warm_enabled";
    private static final String KEY_WARM_COOLDOWN_SEC = "warm_cooldown_sec";
    private static final String KEY_WARM_GAP_MS = "warm_gap_ms";
    private static final String KEY_HIDE_FROM_RECENTS = "hide_from_recents";
    private static final String KEY_BOOT_AUTOSTART = "boot_autostart";
    private static final String KEY_ACCESSIBILITY_SEEN_ON = "accessibility_seen_on";
    private static final String KEY_ACCESSIBILITY_EVER_ON = "accessibility_ever_on";
    private static final String KEY_ACCESSIBILITY_SEEN_AT = "accessibility_seen_at";
    private static final String KEY_ACCESSIBILITY_WROTE_ELAPSED = "accessibility_wrote_elapsed";
    private static final String KEY_ACCESSIBILITY_WRITE_COUNT = "accessibility_write_count";
    private static final String KEY_ACCESSIBILITY_WRITE_AT = "accessibility_write_at";
    private static final String KEY_ACTIVITY_WINDOWING = "activity_windowing";
    private static final String KEY_ACTIVITY_WINDOWING_VALUE = "activity_windowing_value";
    /** 0.7.61-M6 / 82 之前的全局开关，只用于升级时清掉；现在逐应用保存。 */
    private static final String KEY_ACTIVITY_AVOID_MOVE_TO_FRONT_LEGACY =
            "activity_avoid_move_to_front";
    private static final String KEY_APP_ACTIVITY_AVOID_MOVE_TO_FRONT =
            "app_activity_avoid_move_to_front";
    private static final String KEY_APP_WAKE_MODE = "app_wake_mode";
    private static final String KEY_APP_ACTIVITIES = "app_activities";
    private static final String KEY_SKIP_RUNNING = "warm_skip_running";
    private static final String KEY_LIST_MODE = "list_mode";
    private static final String KEY_WHITELIST = "list_whitelist";
    private static final String KEY_BLACKLIST = "list_blacklist";
    private static final String KEY_WARM_MAX_APPS = "warm_max_apps";
    private static final String KEY_APP_WEIGHTS = "app_weights";
    private static final String KEY_NOTIFIED = "notified_packages";
    private static final String KEY_PROBE_RECEIVER_AT = "probe_receiver_at";
    private static final String KEY_PROBE_SERVICE_AT = "probe_service_at";
    private static final String KEY_STATE_SNAPSHOT = "state_snapshot";
    private static final String KEY_STATE_SAMPLE_AT = "state_sample_at";
    private static final String KEY_WATCH_LISTENER = "watch_listener";
    private static final String KEY_WATCH_ACCESSIBILITY = "watch_accessibility";
    private static final String KEY_WATCH_SERVICE = "watch_service";
    private static final String KEY_WATCH_PRIVILEGED = "watch_privileged";
    private static final String KEY_SERVICE_RUN_BOOT = "service_run_boot";
    private static final String KEY_SERVICE_STOPPED_CLEAN = "service_stopped_clean";
    private static final String KEY_SERVICE_KILLED_BOOT = "service_killed_boot";
    private static final String KEY_PRIVILEGED_SEEN = "privileged_seen";
    private static final String KEY_PRIVILEGED_LOST = "privileged_lost";
    /** 首次安装声明是否已同意；更新应用不会清掉这个值。 */
    private static final String KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted";

    /** 通知来源记录的上限，防止长期使用无限增长。 */
    private static final int MAX_NOTIFIED = 400;

    /** 「仅横幅通知触发」：有效重要度（系统排名表给的那份）必须为「高」及以上。 */
    public static final int MODE_STRICT = 0;

    /** 「仅横幅通知触发」关闭：任意非重复的新通知。 */
    public static final int MODE_LOOSE = 1;

    /**
     * 全局新通知触发时，「拉起 Activity」那一路怎么办（0.7.30-M6 / 51）。
     *
     * <p>只有被设成「拉起 Activity」的应用受影响；另外三条路（Provider / Service / 广播）不启动
     * 界面组件，不抢焦点，所以任何时候都不受这三个档位影响。面板与锁屏两个触发点也不受影响
     * （那两件事发生时输入法本来就是收起的），闸门只装在全局新通知上。
     */

    /** 一律不拉起：全局新通知下这条路整条不上场（这一类应用在新通知场景里等于不预热）。 */
    public static final int BANNER_ACTIVITY_NEVER = 0;

    /** 输入法可见时不拉起：等输入法收起再拉，最多等 {@link #getImeDeferMaxMs()}（默认档）。 */
    public static final int BANNER_ACTIVITY_WHEN_NO_IME = 1;

    /** 一律拉起：0.7.29 及以前的行为（会把输入法顶回去）。 */
    public static final int BANNER_ACTIVITY_ALWAYS = 2;

    /** 输入法可见时复看的间隔（毫秒）：越小越贴近「输入法收起的那一刻」。 */
    public static final int IME_POLL_MS_DEFAULT = 300;
    public static final int IME_POLL_MS_MIN = 100;
    public static final int IME_POLL_MS_MAX = 2_000;

    /** 延后上限（毫秒）：等这么久输入法还可见就放弃这一次拉起。20 秒是硬顶。 */
    public static final int IME_DEFER_MAX_MS_DEFAULT = 10_000;
    public static final int IME_DEFER_MAX_MS_MIN = 1_000;
    public static final int IME_DEFER_MAX_MS_MAX = 20_000;

    /**
     * 息屏记录 + 亮屏快速预热的锁屏可见性过滤精确度。
     *
     * <p>0 = 最宽松（息屏期间所有新通知都算可能上锁屏）；100 = 最严格（只算强证据，
     * 例如 PUBLIC 可见性、全屏 Intent）。到达时先算置信度，亮屏时再按当时的这个值过滤，
     * 所以息屏期间拖动滑杆也会在本次亮屏生效。界面只提供 10 分一档，存取值也按 10 对齐。
     */
    public static final int LOCKSCREEN_FILTER_PRECISION_DEFAULT = 40;
    public static final int LOCKSCREEN_FILTER_PRECISION_MIN = 0;
    public static final int LOCKSCREEN_FILTER_PRECISION_MAX = 100;
    public static final int LOCKSCREEN_FILTER_PRECISION_STEP = 10;

    /** 名单模式：黑名单（预热所有应用；被勾选的除外）。 */
    public static final int LIST_MODE_BLACKLIST = 0;

    /**
     * 名单模式：白名单（默认，只预热被勾选的应用）。
     *
     * <p>默认给白名单是保守选择：免 root 的预热对每个应用都有代价（一次 binder 往返、
     * 目标进程可能被拉起），先让人明确挑出要预热的那个名单，比默认动全机安全。
     * 代价是<b>一个都没勾时不会预热任何应用</b>，所以名单页与状态页都会把这件事说出来。
     */
    public static final int LIST_MODE_WHITELIST = 1;

    /** 单次预热的数量上限：0 表示不限（把这次触发能排的候选全部排上队）。 */
    public static final int WARM_MAX_UNLIMITED = 0;

    /** 单次数量上限的默认值（保守档：面板场景原本就是 3 个）。 */
    public static final int WARM_MAX_DEFAULT = 3;

    /** 界面上可选的数量上限档位，顺序即对话框里的顺序，0 是「不限」。 */
    public static final int[] WARM_MAX_CHOICES = {1, 2, 3, 5, 8, WARM_MAX_UNLIMITED};

    /** 同应用冷却的默认值与允许范围（秒）。0 = 不冷却：每次触发都重新预热。 */
    public static final int WARM_COOLDOWN_SEC_DEFAULT = 60;
    public static final int WARM_COOLDOWN_SEC_MIN = 0;
    public static final int WARM_COOLDOWN_SEC_MAX = 3_600;

    /** 串行间隔的默认值与允许范围（毫秒）。0 = 两次预热之间不等。 */
    public static final int WARM_GAP_MS_DEFAULT = 120;
    public static final int WARM_GAP_MS_MIN = 0;
    public static final int WARM_GAP_MS_MAX = 10_000;

    /** 每应用权重的最高档（0 = 普通，1 = 较高，2 = 最高）。 */
    public static final int WEIGHT_MAX = 2;

    public interface Listener {
        void onPrefsChanged();
    }

    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();
    private static SharedPreferences sPrefs;

    private Prefs() {
    }

    public static void init(Context context) {
        if (sPrefs == null) {
            sPrefs = context.getApplicationContext()
                    .getSharedPreferences(NAME, Context.MODE_PRIVATE);
            // 82 起「尝试后台启动activity」改成逐应用设置。旧全局开关一律不再沿用，
            // 只把它清掉，避免升级后出现一个界面上看不到、却仍影响启动的遗留值。
            if (sPrefs.contains(KEY_ACTIVITY_AVOID_MOVE_TO_FRONT_LEGACY)) {
                sPrefs.edit().remove(KEY_ACTIVITY_AVOID_MOVE_TO_FRONT_LEGACY).apply();
            }
        }
    }

    @NonNull
    private static SharedPreferences store() {
        SharedPreferences prefs = sPrefs;
        if (prefs == null) {
            throw new IllegalStateException("Prefs.init() 未被调用");
        }
        return prefs;
    }

    public static void addListener(Listener listener) {
        if (!sListeners.contains(listener)) {
            sListeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        sListeners.remove(listener);
    }

    private static void notifyChanged() {
        for (Listener listener : sListeners) {
            listener.onPrefsChanged();
        }
    }

    public static boolean isTriggerBanner() {
        return store().getBoolean(KEY_TRIGGER_BANNER, true);
    }

    public static void setTriggerBanner(boolean enabled) {
        store().edit().putBoolean(KEY_TRIGGER_BANNER, enabled).apply();
        notifyChanged();
    }

    public static boolean isTriggerShade() {
        return store().getBoolean(KEY_TRIGGER_SHADE, true);
    }

    public static void setTriggerShade(boolean enabled) {
        store().edit().putBoolean(KEY_TRIGGER_SHADE, enabled).apply();
        notifyChanged();
    }

    public static boolean isTriggerLockscreen() {
        return store().getBoolean(KEY_TRIGGER_LOCKSCREEN, true);
    }

    public static void setTriggerLockscreen(boolean enabled) {
        store().edit().putBoolean(KEY_TRIGGER_LOCKSCREEN, enabled).apply();
        notifyChanged();
    }

    /**
     * 息屏时记录新通知、亮屏瞬间快速预热。
     *
     * <p>默认开启，保持升级前的行为。关掉后不再记录息屏通知，也不发
     * {@code SCREEN_ON_PENDING}；400ms 后的锁屏精确探测不受影响。
     */
    public static boolean isScreenOffFastWarm() {
        return store().getBoolean(KEY_SCREEN_OFF_FAST_WARM, true);
    }

    public static void setScreenOffFastWarm(boolean enabled) {
        store().edit().putBoolean(KEY_SCREEN_OFF_FAST_WARM, enabled).apply();
        notifyChanged();
    }

    /** 锁屏可见性过滤精确度：越低越宽松，越高越严格。 */
    public static int getLockscreenFilterPrecision() {
        return clampLockscreenFilterPrecision(store().getInt(
                KEY_LOCKSCREEN_FILTER_PRECISION, LOCKSCREEN_FILTER_PRECISION_DEFAULT));
    }

    public static void setLockscreenFilterPrecision(int precision) {
        store().edit().putInt(KEY_LOCKSCREEN_FILTER_PRECISION,
                clampLockscreenFilterPrecision(precision)).apply();
        notifyChanged();
    }

    public static int clampLockscreenFilterPrecision(int precision) {
        int clamped = Math.max(LOCKSCREEN_FILTER_PRECISION_MIN,
                Math.min(precision, LOCKSCREEN_FILTER_PRECISION_MAX));
        int stepped = Math.round(clamped / (float) LOCKSCREEN_FILTER_PRECISION_STEP)
                * LOCKSCREEN_FILTER_PRECISION_STEP;
        return Math.max(LOCKSCREEN_FILTER_PRECISION_MIN,
                Math.min(stepped, LOCKSCREEN_FILTER_PRECISION_MAX));
    }

    /**
     * 锁屏点亮这条触发点怎么挑候选应用（0.7.34-M6 / 55）。
     *
     * <p>两档的差别只有一件事：<b>要不要相信锁屏上真正画出来的那几行</b>。
     */

    /**
     * 精确（默认）：点亮锁屏时读一下锁屏上真的画了哪几行通知，按行认出应用，认不出的行不预热；
     * 读不到锁屏结构（比如无障碍服务没开）也不预热 —— 结论与原始读数都写进「诊断」日志。
     */
    public static final int LOCKSCREEN_MODE_PRECISE = 0;

    /**
     * 老办法：0.7.32 及以前那一套 —— <b>不看</b>锁屏窗口，直接按系统设置
     * （每条通知的可见性 + {@code lock_screen_allow_private_notifications}）把通知中心里的应用
     * 当作「锁屏可见」。
     *
     * <p>它存在的理由很具体：ColorOS 上「锁屏通知」那个开关不落到 AOSP 的设置表里，所以老办法在
     * 那台机器上会把锁屏没画出来的应用也预热掉（而精确档要的正是「不预热它们」）。留着它是给
     * 「精确档认不出结构」时一个已知行为的退路，由用户自己选。
     */
    public static final int LOCKSCREEN_MODE_LEGACY = 1;

    public static int getLockscreenMode() {
        int mode = store().getInt(KEY_LOCKSCREEN_MODE, LOCKSCREEN_MODE_PRECISE);
        if (mode < LOCKSCREEN_MODE_PRECISE || mode > LOCKSCREEN_MODE_LEGACY) {
            return LOCKSCREEN_MODE_PRECISE;   // 脏数据收回默认档，不让界面与引擎各自解释一个没定义的值
        }
        return mode;
    }

    public static void setLockscreenMode(int mode) {
        store().edit().putInt(KEY_LOCKSCREEN_MODE, clampLockscreenMode(mode)).apply();
        notifyChanged();
    }

    public static int clampLockscreenMode(int mode) {
        if (mode < LOCKSCREEN_MODE_PRECISE) {
            return LOCKSCREEN_MODE_PRECISE;
        }
        return Math.min(mode, LOCKSCREEN_MODE_LEGACY);
    }

    /**
     * 「实时通知无条件预热」档位（0.7.35-M6 / 56）。
     *
     * <p>为什么有这一项：ColorOS 的流体云、vivo 的原子岛这类卡片是厂商自己渲染的，真机读数
     * （2026-10-18，55 版）显示锁屏上那两行（媒体卡片、导航卡片）行内既没有来源应用的包名、也没有
     * 应用名，按行认不出来。所以另开一条路：从<b>通知中心那一侧</b>认「哪些应用有实时通知」，
     * 认出来的无条件预热（不要求锁屏上画了它）。档位只决定「算哪些」——
     * 凭据全部来自通知的公开字段，逐条写进「诊断」日志。
     */

    /** 关：不加这一条（就是 0.7.34-M6 / 55 的行为）。 */
    public static final int LIVE_WARM_OFF = 0;

    /** 严：只认系统提升位 / 媒体 / 通话。 */
    public static final int LIVE_WARM_STRICT = 1;

    /** 中（默认）：严 + 符合 Android 官方 Live Updates 提升条件的常驻通知。 */
    public static final int LIVE_WARM_NORMAL = 2;

    /** 宽：中 + 所有常驻 / 前台服务通知。 */
    public static final int LIVE_WARM_WIDE = 3;

    public static int getLiveWarmMode() {
        return clampLiveWarmMode(store().getInt(KEY_LIVE_WARM_MODE, LIVE_WARM_NORMAL));
    }

    public static void setLiveWarmMode(int mode) {
        store().edit().putInt(KEY_LIVE_WARM_MODE, clampLiveWarmMode(mode)).apply();
        notifyChanged();
    }

    public static int clampLiveWarmMode(int mode) {
        if (mode < LIVE_WARM_OFF) {
            return LIVE_WARM_OFF;
        }
        return Math.min(mode, LIVE_WARM_WIDE);
    }

    public static int getBannerMode() {
        return store().getInt(KEY_BANNER_MODE, MODE_STRICT);
    }

    public static void setBannerMode(int mode) {
        store().edit().putInt(KEY_BANNER_MODE, mode).apply();
        notifyChanged();
    }

    /**
     * 全局新通知触发时「拉起 Activity」这一路的三档（见 {@link #BANNER_ACTIVITY_NEVER} 等）。
     *
     * <p>读到的脏数据一律收回默认档，不让界面与引擎各自解释一个没定义的值。
     */
    public static int getBannerActivityMode() {
        int mode = store().getInt(KEY_BANNER_ACTIVITY_MODE, BANNER_ACTIVITY_WHEN_NO_IME);
        if (mode < BANNER_ACTIVITY_NEVER || mode > BANNER_ACTIVITY_ALWAYS) {
            return BANNER_ACTIVITY_WHEN_NO_IME;
        }
        return mode;
    }

    public static void setBannerActivityMode(int mode) {
        store().edit().putInt(KEY_BANNER_ACTIVITY_MODE, clampBannerActivityMode(mode)).apply();
        notifyChanged();
    }

    public static int clampBannerActivityMode(int mode) {
        if (mode < BANNER_ACTIVITY_NEVER) {
            return BANNER_ACTIVITY_NEVER;
        }
        return Math.min(mode, BANNER_ACTIVITY_ALWAYS);
    }

    /** 输入法可见时复看一次的间隔（毫秒）。 */
    public static int getImePollMs() {
        return clampImePollMs(store().getInt(KEY_IME_POLL_MS, IME_POLL_MS_DEFAULT));
    }

    public static void setImePollMs(int millis) {
        store().edit().putInt(KEY_IME_POLL_MS, clampImePollMs(millis)).apply();
        notifyChanged();
    }

    public static int clampImePollMs(int millis) {
        if (millis < IME_POLL_MS_MIN) {
            return IME_POLL_MS_MIN;
        }
        return Math.min(millis, IME_POLL_MS_MAX);
    }

    /** 等输入法收起的上限（毫秒，最多 20 秒）。 */
    public static int getImeDeferMaxMs() {
        return clampImeDeferMaxMs(store().getInt(KEY_IME_DEFER_MAX_MS, IME_DEFER_MAX_MS_DEFAULT));
    }

    public static void setImeDeferMaxMs(int millis) {
        store().edit().putInt(KEY_IME_DEFER_MAX_MS, clampImeDeferMaxMs(millis)).apply();
        notifyChanged();
    }

    public static int clampImeDeferMaxMs(int millis) {
        if (millis < IME_DEFER_MAX_MS_MIN) {
            return IME_DEFER_MAX_MS_MIN;
        }
        return Math.min(millis, IME_DEFER_MAX_MS_MAX);
    }

    public static boolean isDiagLog() {
        return store().getBoolean(KEY_DIAG_LOG, true);
    }

    public static void setDiagLog(boolean enabled) {
        store().edit().putBoolean(KEY_DIAG_LOG, enabled).apply();
        notifyChanged();
    }

    public static boolean isLogRemoved() {
        return store().getBoolean(KEY_LOG_REMOVED, false);
    }

    public static void setLogRemoved(boolean enabled) {
        store().edit().putBoolean(KEY_LOG_REMOVED, enabled).apply();
        notifyChanged();
    }

    /** 预热总开关。 */
    public static boolean isWarmEnabled() {
        return store().getBoolean(KEY_WARM_ENABLED, true);
    }

    public static void setWarmEnabled(boolean enabled) {
        store().edit().putBoolean(KEY_WARM_ENABLED, enabled).apply();
        notifyChanged();
    }

    /**
     * 首次安装声明是否已同意。
     *
     * <p>只表示用户点过「同意」；升级安装时由 {@code MainActivity} 按
     * {@code firstInstallTime / lastUpdateTime} 判断为老用户后补写，避免更新后重新弹窗。
     */
    public static boolean isDisclaimerAccepted() {
        return store().getBoolean(KEY_DISCLAIMER_ACCEPTED, false);
    }

    public static void setDisclaimerAccepted() {
        store().edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply();
    }

    /**
     * 是否允许用「给导出广播接收器发一条空广播」来预热。
     *
     * <p>默认打开：它是最后一道门，只在应用既没有导出 Provider、也没有导出 Service 时才会用到。
     * 代价是它真的会执行对方的 {@code onReceive}，而前两条路只是让进程起来。
     *
     * <p>0.7.39-M6 / 60 起界面上去掉了这个开关（那条路固定开启）：设置页不再提供关闭入口，
     * 所以这里直接给 {@code true}，以前手动关过的人也随之恢复。{@code WarmEngine} 那边的
     * 判读原样保留，不因为界面改版去动预热逻辑。
     */
    public static boolean isReceiverWarmEnabled() {
        return true;
    }

    /**
     * 本应用自己的任务要不要在最近任务（概览）里隐藏（默认<b>关</b>）。
     *
     * <p>只看那张卡片：常驻服务、无障碍与通知监听与它无关，隐藏之后系统也不会因此更愿意
     * 或更不愿意回收本应用。真正的下发在 {@code core.RecentsVisibility}：每次回到主界面、
     * 以及用户在这一页拨动开关时各写一次。
     */
    public static boolean isHideFromRecents() {
        return store().getBoolean(KEY_HIDE_FROM_RECENTS, false);
    }

    public static void setHideFromRecents(boolean enabled) {
        store().edit().putBoolean(KEY_HIDE_FROM_RECENTS, enabled).apply();
        notifyChanged();
    }

    /**
     * 开机后是否自动拉起监听服务（默认<b>开</b>，也就是 0.7.19 之前一直的行为）。
     *
     * <p>关掉只影响「开机那一次」：{@code BootReceiver} 收到开机广播也不再拉起常驻服务，
     * 手动打开本应用照常能起。ROM 自己的「自启动」名单是另一道门，本应用读不到也改不了。
     */
    public static boolean isBootAutostart() {
        return store().getBoolean(KEY_BOOT_AUTOSTART, true);
    }

    public static void setBootAutostart(boolean enabled) {
        store().edit().putBoolean(KEY_BOOT_AUTOSTART, enabled).apply();
        notifyChanged();
    }

    // ---------------------------------------------------------------- 本应用的通知（0.7.27-M6 / 48）

    /**
     * 「什么算服务异常」的四枚开关。
     *
     * <p>它们只决定<b>异常提醒那条通知要不要出现</b>（判据本身照样算、照样写日志）：
     * 关掉某一项 = 这一类异常不把它叫醒，不代表本应用不去看它。
     *
     * <p>前三项默认开（通知监听 / 无障碍 / 常驻服务被杀，都是会让触发点直接失效的事），
     * 特权通道那一项默认关 —— 它只影响「拉起 Activity」这条路，平时不出声更省心。
     */
    public static boolean isWatchListener() {
        return store().getBoolean(KEY_WATCH_LISTENER, true);
    }

    public static void setWatchListener(boolean enabled) {
        store().edit().putBoolean(KEY_WATCH_LISTENER, enabled).apply();
        notifyChanged();
    }

    public static boolean isWatchAccessibility() {
        return store().getBoolean(KEY_WATCH_ACCESSIBILITY, true);
    }

    public static void setWatchAccessibility(boolean enabled) {
        store().edit().putBoolean(KEY_WATCH_ACCESSIBILITY, enabled).apply();
        notifyChanged();
    }

    public static boolean isWatchService() {
        return store().getBoolean(KEY_WATCH_SERVICE, true);
    }

    public static void setWatchService(boolean enabled) {
        store().edit().putBoolean(KEY_WATCH_SERVICE, enabled).apply();
        notifyChanged();
    }

    public static boolean isWatchPrivileged() {
        return store().getBoolean(KEY_WATCH_PRIVILEGED, false);
    }

    public static void setWatchPrivileged(boolean enabled) {
        store().edit().putBoolean(KEY_WATCH_PRIVILEGED, enabled).apply();
        notifyChanged();
    }

    // 「常驻服务上次是不是被杀掉的」这三笔账（判据见 core/HealthWatch）：
    // 时间戳一律用「这次开机的墙钟起点」记（当前时间 − 开机以来毫秒），跨开机比对时自己就能分开。

    /** 上一次跑常驻服务时的「开机墙钟起点」；0 = 还没跑过。 */
    public static long serviceRunBoot() {
        return store().getLong(KEY_SERVICE_RUN_BOOT, 0L);
    }

    /** 上一次跑有没有正常走完 {@code onDestroy}（被杀时不会走，这就是「被杀」的可观测形式）。 */
    public static boolean serviceStoppedCleanly() {
        return store().getBoolean(KEY_SERVICE_STOPPED_CLEAN, true);
    }

    /** 服务起来了：记下这是哪一次开机，并把「正常结束」的标记清掉。 */
    public static void markServiceRunning(long bootWallClockMs) {
        store().edit()
                .putLong(KEY_SERVICE_RUN_BOOT, bootWallClockMs)
                .putBoolean(KEY_SERVICE_STOPPED_CLEAN, false)
                .apply();
    }

    /** 服务正常结束（{@code onDestroy} 走到了）。 */
    public static void markServiceStoppedCleanly() {
        store().edit().putBoolean(KEY_SERVICE_STOPPED_CLEAN, true).apply();
    }

    /**
     * 记一笔「常驻服务被杀过」，记的是<b>被杀时那次开机的墙钟起点</b>（不是被杀的时刻）。
     *
     * <p>这样这条结论天生带开机坐标：换了开机就对不上，判据自己就作废了，不用等谁来清理
     * （0.7.26 及以前这里记的是时刻，于是上一次开机被杀过、之后一直没打开本应用的话，
     * 重启后还会拿上一辈子的事来提醒 —— 0.7.27-M6 / 48 修的缺陷）。
     */
    public static void markServiceKilled(long bootWallClockMs) {
        store().edit().putLong(KEY_SERVICE_KILLED_BOOT, bootWallClockMs).apply();
    }

    /** 被杀时那次开机的墙钟起点；0 = 没记过（或记的是别的开机，见 {@link #markServiceKilled}）。 */
    public static long serviceKilledBoot() {
        return store().getLong(KEY_SERVICE_KILLED_BOOT, 0L);
    }

    /** 用户来看过了：收起「被杀过」这条结论（下次再被杀会重新记一笔并再提醒一次）。 */
    public static void clearServiceKilled() {
        store().edit().remove(KEY_SERVICE_KILLED_BOOT).apply();
    }

    /** 我们见过特权通道（Shizuku 的 binder 到过本进程）：没有它就不谈「断了」。 */
    public static boolean privilegedSeen() {
        return store().getBoolean(KEY_PRIVILEGED_SEEN, false);
    }

    /** Shizuku 的 binder 收不到（服务停了 / 被系统结束 / 手机重启后还没起来）。 */
    public static boolean privilegedLost() {
        return store().getBoolean(KEY_PRIVILEGED_LOST, false);
    }

    public static void markPrivilegedAvailable() {
        store().edit()
                .putBoolean(KEY_PRIVILEGED_SEEN, true)
                .putBoolean(KEY_PRIVILEGED_LOST, false)
                .apply();
    }

    public static void markPrivilegedLost() {
        store().edit()
                .putBoolean(KEY_PRIVILEGED_SEEN, true)
                .putBoolean(KEY_PRIVILEGED_LOST, true)
                .apply();
    }

    /**
     * 上一次观察到的「无障碍服务开着没有」（0.7.20-M6 / 41）。
     *
     * <p>它服务两件事：开机时发现无障碍不在，判断这次是 <b>ROM 掉的</b>（上次看到是开着 →
     * 补写回去）还是 <b>用户自己关的</b>（上次看到就是关着 → 不替他打开）；以及配合
     * {@link #accessibilitySeenAt()} 判断那次「开着」是在<b>这次开机之前</b>看到的还是运行期间
     * 看到的（0.7.21-M6 / 42，见 {@code AccessibilityAutostart#poll}）。写入方是
     * {@code AccessibilityAutostart.poll}，进程每次启动、常驻服务开机窗口内每 30 秒与之后每
     * 15 分钟各刷一次。
     */
    public static boolean isAccessibilitySeenOn() {
        return store().getBoolean(KEY_ACCESSIBILITY_SEEN_ON, false);
    }

    /** 上次观察到的时刻，0 = 还没观察过。 */
    public static long accessibilitySeenAt() {
        return store().getLong(KEY_ACCESSIBILITY_SEEN_AT, 0L);
    }

    /**
     * 这台机器上<b>曾经见过</b>无障碍服务开着（0.7.25-M6 / 46）。
     *
     * <p>与 {@link #isAccessibilitySeenOn()} 是两笔账：那一笔是补写判据的原料，看到「关着」就会被
     * 改写成 false（{@code AccessibilityAutostart.poll} 要的就是最新一眼的读数）；这一笔只由
     * {@code core.HealthWatch} 见到「开着」时写下、<b>只能被清除不会自己翻回 false</b>，用来回答
     * 「这个用户到底配过无障碍没有」—— 「通知」卡里那一项要区分的正是「配过、现在掉了」与
     * 「从没配过」。两笔账都是只读诊断 + 通知判断，不参与补写。
     */
    public static boolean isAccessibilityEverOn() {
        return store().getBoolean(KEY_ACCESSIBILITY_EVER_ON, false);
    }

    /** 见过无障碍开着（幂等，重复调用不写盘）。 */
    public static void markAccessibilityEverOn() {
        if (isAccessibilityEverOn()) {
            return;
        }
        store().edit().putBoolean(KEY_ACCESSIBILITY_EVER_ON, true).apply();
    }

    /** 「上次写成功」的时刻用开机以来的毫秒记（0.7.22-M6 / 43），它能自己回答「是这次开机写的吗」。 */
    public static long accessibilityWroteElapsed() {
        return store().getLong(KEY_ACCESSIBILITY_WROTE_ELAPSED, 0L);
    }

    /** 记一次「写成功」的时刻（开机以来的毫秒；重启后这个值会大于当前值，据此认出上一次开机）。 */
    public static void markAccessibilityWrote() {
        store().edit()
                .putLong(KEY_ACCESSIBILITY_WROTE_ELAPSED, SystemClock.elapsedRealtime())
                .apply();
    }

    /**
     * 本次开机累计写了几次无障碍相关设置（0.7.23-M6 / 44）。
     *
     * <p>它只用来给「和 ROM 抢」这件事封顶：写几次就不再写（上限见
     * {@code AccessibilityAutostart.MAX_WRITES_PER_BOOT}）。上一次开机的计数会被认出来
     * （记的时间戳比当前 {@link SystemClock#elapsedRealtime()} 大 ⇒ 重启过）并从头数。
     */
    public static int accessibilityWriteCount() {
        if (store().getLong(KEY_ACCESSIBILITY_WRITE_AT, 0L) == 0L
                || store().getLong(KEY_ACCESSIBILITY_WRITE_AT, 0L) > SystemClock.elapsedRealtime()) {
            return 0;
        }
        return store().getInt(KEY_ACCESSIBILITY_WRITE_COUNT, 0);
    }

    /** 记一次「本次开机写了第几次」（返回新值）。 */
    public static int bumpAccessibilityWriteCount() {
        int next = accessibilityWriteCount() + 1;
        store().edit()
                .putInt(KEY_ACCESSIBILITY_WRITE_COUNT, next)
                .putLong(KEY_ACCESSIBILITY_WRITE_AT, SystemClock.elapsedRealtime())
                .apply();
        return next;
    }

    /** 记一次观察结果（状态位 + 时刻）。 */
    public static void markAccessibilitySeen(boolean on) {
        store().edit()
                .putBoolean(KEY_ACCESSIBILITY_SEEN_ON, on)
                .putLong(KEY_ACCESSIBILITY_SEEN_AT, System.currentTimeMillis())
                .apply();
    }

    /**
     * 某个应用的<b>唤醒方式</b>：{@code true} = 拉起 Activity（第 ④ 条路），
     * {@code false} = 唤醒后台（Provider → Service → 广播接收器）。
     *
     * <p>0.7.5 起从「全局开关 + 永远排最后」改成<b>逐应用二选一</b>：选了 Activity 的应用
     * <b>不再</b>尝试另外三条路，那三条也不再抢在它前面。默认（没有条目的应用）走另外三条，
     * 也就是 0.7.4 之前的行为一字不变。
     *
     * <p>它同时是第 ④ 条路的开关：没有任何应用选它，就等于这条路整条关着。
     */
    public static boolean isActivityWakeMode(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        return store().getStringSet(KEY_APP_WAKE_MODE, Collections.emptySet())
                .contains(packageName);
    }

    public static void setActivityWakeMode(String packageName, boolean activityWake) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        Set<String> current = new HashSet<>(
                store().getStringSet(KEY_APP_WAKE_MODE, Collections.emptySet()));
        boolean changed = activityWake ? current.add(packageName) : current.remove(packageName);
        if (changed) {
            store().edit().putStringSet(KEY_APP_WAKE_MODE, current).apply();
            notifyChanged();
        }
    }

    /**
     * 选了「拉起 Activity」的那些应用（名单页每行的标签要显示当前唤醒方式）。
     *
     * <p>整份一次性取出来交给列表：逐行去读 SharedPreferences 会在滚动时反复解析同一个集合。
     */
    public static Set<String> getActivityWakePackages() {
        return new HashSet<>(store().getStringSet(KEY_APP_WAKE_MODE, Collections.emptySet()));
    }

    /**
     * 本应用 uid 那条 {@code startActivity} 是否带上一份 {@code ActivityOptions}，里面放
     * {@code android.activity.windowingMode = 用户填的值}（默认<b>关</b>）。
     *
     * <p>目的不是藏窗口（那一类已实测无效），而是试着让系统不把这次启动当成「应用拉起应用」：
     * ROM 在那种情况下可能弹一次确认框，换个「显示区域」形态后，一些 ROM 的拦截器认不出来。
     * 它能不能用完全看机器，所以默认关、由用户自己开、自己填值。
     *
     * <p><b>只作用于本应用 uid 那条 {@code startActivity}</b>。0.7.46-M6 / 67 起 Activity 路只剩这一条
     * 通道：原来的 {@code am start}（shell / root）已随设置页开关一起撤掉。
     */
    public static boolean isActivityWindowingEnabled() {
        return store().getBoolean(KEY_ACTIVITY_WINDOWING, false);
    }

    public static void setActivityWindowingEnabled(boolean enabled) {
        store().edit().putBoolean(KEY_ACTIVITY_WINDOWING, enabled).apply();
        notifyChanged();
    }

    /**
     * 某个应用是否启用「尝试后台启动activity」（默认<b>关</b>）。
     *
     * <p>只影响这个应用走「拉起 Activity」时的正式触发与手动「试一次」：打开后优先用公开 API
     * {@code ActivityOptions.makeTaskLaunchBehind()} + {@code FLAG_ACTIVITY_NEW_DOCUMENT}；
     * 不支持 launch-behind 的目标再按进程状态回退到 {@code avoidMoveToFront} 或普通启动。
     * 主路径不需要特权，也不依赖探活。
     *
     * <p>这个开关<b>不会</b>绕过「应用进程存在时不预热」：自动触发仍先探活，命中就跳过；
     * 手动「试一次」始终不跳过。探活只用于这道旧闸门与结果展示。
     *
     * <p>名单页的「正常打开」是用户点名要看界面，不走这一项；「尝试后台打开」测试按钮则强制
     * 打开这条路径，不读本开关。windowingMode 与它互不影响。
     */
    public static boolean isActivityAvoidMoveToFrontEnabled(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        return store().getStringSet(KEY_APP_ACTIVITY_AVOID_MOVE_TO_FRONT, Collections.emptySet())
                .contains(packageName);
    }

    public static void setActivityAvoidMoveToFrontEnabled(String packageName, boolean enabled) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        Set<String> current = new HashSet<>(store().getStringSet(
                KEY_APP_ACTIVITY_AVOID_MOVE_TO_FRONT, Collections.emptySet()));
        boolean changed = enabled ? current.add(packageName) : current.remove(packageName);
        if (changed) {
            store().edit().putStringSet(KEY_APP_ACTIVITY_AVOID_MOVE_TO_FRONT, current).apply();
            notifyChanged();
        }
    }

    /** windowingMode 的默认值（{@code WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW} = 6）。 */
    public static final int WINDOWING_MODE_DEFAULT = 6;

    /**
     * 允许用户输入的 windowingMode 范围：与 {@code Bundle.putInt} 一致，是整个有符号 32 位整数。
     *
     * <p>Android 自己只定义了 0–6 几个常用值，但不同 ROM 可能认别的数；这里不替系统设上限，
     * 只要没超出 int 范围就原样递过去，是否接受由目标系统决定。
     */
    public static final int WINDOWING_MODE_MIN = Integer.MIN_VALUE;
    public static final int WINDOWING_MODE_MAX = Integer.MAX_VALUE;

    /** 用户填的 windowingMode 值；没有存过时用默认值，但任何 int 都原样返回。 */
    public static int getActivityWindowingValue() {
        return store().getInt(KEY_ACTIVITY_WINDOWING_VALUE, WINDOWING_MODE_DEFAULT);
    }

    public static void setActivityWindowingValue(int value) {
        store().edit().putInt(KEY_ACTIVITY_WINDOWING_VALUE, value).apply();
        notifyChanged();
    }

    /**
     * 用户在名单页给某个应用指定的唤醒 Activity。
     *
     * <p>两态：{@code null} = 没指定（有候选时自动挑一个推断成不显示的），
     * 其余 = 组件名（{@code 包/类}）。
     *
     * <p>0.7.4 还多一个空串档（「明确不用 Activity」）；0.7.5 起「用不用 Activity」由唤醒方式
     * 本身决定，所以旧的空串一律当成「没指定」，免得升级上来的应用卡在一个界面上再也改不回的档。
     */
    public static String getAppActivity(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        for (String line : store().getStringSet(KEY_APP_ACTIVITIES, Collections.emptySet())) {
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (line.substring(0, equals).equals(packageName)) {
                String component = line.substring(equals + 1);
                return component.isEmpty() ? null : component;
            }
        }
        return null;
    }

    /** 指定（传 null = 恢复自动挑）某个应用的唤醒 Activity。 */
    public static void setAppActivity(String packageName, String component) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        Set<String> lines = new HashSet<>();
        for (String line : store().getStringSet(KEY_APP_ACTIVITIES, Collections.emptySet())) {
            int equals = line.indexOf('=');
            if (equals <= 0 || !line.substring(0, equals).equals(packageName)) {
                lines.add(line);
            }
        }
        if (component != null) {
            lines.add(packageName + "=" + component);
        }
        store().edit().putStringSet(KEY_APP_ACTIVITIES, lines).apply();
        notifyChanged();
    }

    /**
     * 预热前探到目标进程已经在跑时，这次还要不要预热（默认<b>关</b>：照常预热）。
     *
     * <p>打开后，自动触发在探活说 {@code RUNNING} 时直接跳过；手动「试一次」不看这一项 ——
     * 那是用户点名要看结果的实验。没有特权通道时探不到，这一项自然不起作用。
     *
     * <p>0.7.55-M6 / 76 起「拉起 Activity」那条路也服从这个开关：开关关着时即使进程已在运行，
     * 仍会启动界面组件。以前那条路固定跳过，导致开关看起来对 Activity 路无效。
     *
     * <p>探活本身（0.7.40-M6 / 61 起）不再有开关：有特权通道就做，没有就跳过。
     */
    public static boolean isSkipIfRunning() {
        return store().getBoolean(KEY_SKIP_RUNNING, false);
    }

    public static void setSkipIfRunning(boolean enabled) {
        store().edit().putBoolean(KEY_SKIP_RUNNING, enabled).apply();
        notifyChanged();
    }

    /** 名单模式，见 {@link #LIST_MODE_BLACKLIST} / {@link #LIST_MODE_WHITELIST}。 */
    public static int getListMode() {
        return store().getInt(KEY_LIST_MODE, LIST_MODE_WHITELIST);
    }

    public static void setListMode(int mode) {
        store().edit().putInt(KEY_LIST_MODE, mode).apply();
        notifyChanged();
    }

    public static boolean isWhitelistMode() {
        return getListMode() == LIST_MODE_WHITELIST;
    }

    /** 当前模式下被勾选的应用集合（白名单模式下为允许，黑名单模式下为排除）。 */
    public static Set<String> getSelectedPackages() {
        Set<String> stored = store().getStringSet(
                isWhitelistMode() ? KEY_WHITELIST : KEY_BLACKLIST, Collections.emptySet());
        return new HashSet<>(stored);
    }

    public static boolean isPackageSelected(String packageName) {
        return getSelectedPackages().contains(packageName);
    }

    public static void setPackageSelected(String packageName, boolean selected) {
        String key = isWhitelistMode() ? KEY_WHITELIST : KEY_BLACKLIST;
        Set<String> current = new HashSet<>(store().getStringSet(key, Collections.emptySet()));
        boolean changed = selected ? current.add(packageName) : current.remove(packageName);
        if (changed) {
            // 必须写入新的集合实例，SharedPreferences 不会复制传入的 Set
            store().edit().putStringSet(key, current).apply();
            notifyChanged();
        }
    }

    /** 当前模式下的名单大小，用于界面提示。 */
    public static int selectedCount() {
        return getSelectedPackages().size();
    }

    // ---------------------------------------------------------------- 数量上限与权重

    /**
     * 一次触发最多把几个应用排上队（0 = 不限）。
     *
     * <p>以前是写死在引擎里的三个数（面板 3 / 锁屏 5 / 横幅 1），现在由用户定一个统一值；
     * 横幅场景的候选永远只有发通知的那一个应用，所以这一项对它实际上没有影响。
     */
    public static int getWarmMaxApps() {
        return store().getInt(KEY_WARM_MAX_APPS, WARM_MAX_DEFAULT);
    }

    public static void setWarmMaxApps(int value) {
        store().edit().putInt(KEY_WARM_MAX_APPS, value).apply();
        notifyChanged();
    }

    /**
     * 传给队列的上限：0（不限）换算成一个足够大的数，队列那边不必再判特例。
     */
    public static int warmMaxAppsOrUnlimited() {
        int value = getWarmMaxApps();
        return value <= WARM_MAX_UNLIMITED ? Integer.MAX_VALUE : value;
    }

    /**
     * 同应用冷却窗口（秒，0 = 不冷却）。
     *
     * <p>免 root 下查不到别的进程状态，这个窗口是「别在同一个应用上反复空跑」的主要办法：
     * 窗口内再触发同一个应用直接算「冷却中」跳过。调小它会让同一个应用被更频繁地预热
     * （更灵敏，也更费电、日志更密），调到 0 就是每次都预热一遍。
     */
    public static int getWarmCooldownSec() {
        return clampWarmCooldownSec(store().getInt(KEY_WARM_COOLDOWN_SEC,
                WARM_COOLDOWN_SEC_DEFAULT));
    }

    public static void setWarmCooldownSec(int seconds) {
        store().edit().putInt(KEY_WARM_COOLDOWN_SEC, clampWarmCooldownSec(seconds)).apply();
        notifyChanged();
    }

    /** 冷却窗口的毫秒形式（引擎按毫秒算）。 */
    public static long getWarmCooldownMs() {
        return getWarmCooldownSec() * 1000L;
    }

    public static int clampWarmCooldownSec(int seconds) {
        if (seconds < WARM_COOLDOWN_SEC_MIN) {
            return WARM_COOLDOWN_SEC_MIN;
        }
        return Math.min(seconds, WARM_COOLDOWN_SEC_MAX);
    }

    /**
     * 两次预热之间的串行间隔（毫秒，0 = 不等）。
     *
     * <p>预热队列是串行的：这个间隔决定「一批候选里第一条要等多久才开始」。调大它更温和、
     * 但第一条明显变慢；调到 0 就是一条接一条立刻发出去（候选多时会给系统更密的 binder）。
     */
    public static int getWarmGapMs() {
        return clampWarmGapMs(store().getInt(KEY_WARM_GAP_MS, WARM_GAP_MS_DEFAULT));
    }

    public static void setWarmGapMs(int millis) {
        store().edit().putInt(KEY_WARM_GAP_MS, clampWarmGapMs(millis)).apply();
        notifyChanged();
    }

    public static int clampWarmGapMs(int millis) {
        if (millis < WARM_GAP_MS_MIN) {
            return WARM_GAP_MS_MIN;
        }
        return Math.min(millis, WARM_GAP_MS_MAX);
    }

    /**
     * 每应用权重：包名 → 0/1/2。
     *
     * <p>只用来决定<b>同一批候选里谁先预热</b>：一次触发能预热的个数受数量上限限制，
     * 权重高的先排上队。它不改变「这个应用会不会被预热」（那是名单与状态位的事）。
     * 只存非 0 的条目，所以这个集合很小。
     */
    public static Map<String, Integer> getAppWeights() {
        Map<String, Integer> weights = new HashMap<>();
        for (String line : store().getStringSet(KEY_APP_WEIGHTS, Collections.emptySet())) {
            int equals = line.indexOf('=');
            if (equals <= 0 || equals >= line.length() - 1) {
                continue;
            }
            try {
                int value = Integer.parseInt(line.substring(equals + 1));
                if (value > 0) {
                    weights.put(line.substring(0, equals), Math.min(value, WEIGHT_MAX));
                }
            } catch (NumberFormatException ignored) {
                // 手工改坏了的条目，忽略
            }
        }
        return weights;
    }

    public static int getAppWeight(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return 0;
        }
        Integer weight = getAppWeights().get(packageName);
        return weight == null ? 0 : weight;
    }

    /** 设权重；0 表示普通（就把条目删掉）。 */
    public static void setAppWeight(String packageName, int weight) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        Map<String, Integer> weights = getAppWeights();
        if (weight <= 0) {
            weights.remove(packageName);
        } else {
            weights.put(packageName, Math.min(weight, WEIGHT_MAX));
        }
        Set<String> lines = new HashSet<>();
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            lines.add(entry.getKey() + "=" + entry.getValue());
        }
        store().edit().putStringSet(KEY_APP_WEIGHTS, lines).apply();
        notifyChanged();
    }

    /**
     * 记下「这个应用发过通知」。
     *
     * <p>只存包名，和名单存的是同一类信息，不涉及通知内容；用途是给「状态位采样」
     * 提供观察集（收到过通知的应用）。
     */
    public static void addNotifiedPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        Set<String> current = new HashSet<>(store().getStringSet(KEY_NOTIFIED, Collections.emptySet()));
        if (current.contains(packageName) || current.size() >= MAX_NOTIFIED) {
            return;
        }
        current.add(packageName);
        store().edit().putStringSet(KEY_NOTIFIED, current).apply();
    }

    /** 收到过通知的应用包名集合。 */
    public static Set<String> getNotifiedPackages() {
        return new HashSet<>(store().getStringSet(KEY_NOTIFIED, Collections.emptySet()));
    }

    public static int notifiedCount() {
        return store().getStringSet(KEY_NOTIFIED, Collections.emptySet()).size();
    }

    public static void clearNotifiedPackages() {
        store().edit().remove(KEY_NOTIFIED).apply();
        notifyChanged();
    }

    /**
     * 特权自检的两个靶子被打中的时间戳（M4）。
     *
     * <p>用 apply 就够：读写都在同一个进程里，apply 会同步更新内存中的值，
     * 自检那边隔几百毫秒读回来一定看得到，不必 commit 去等磁盘。
     */
    public static void markReceiverProbe() {
        store().edit().putLong(KEY_PROBE_RECEIVER_AT, System.currentTimeMillis()).apply();
    }

    public static long receiverProbeAt() {
        return store().getLong(KEY_PROBE_RECEIVER_AT, 0L);
    }

    public static void markServiceProbe() {
        store().edit().putLong(KEY_PROBE_SERVICE_AT, System.currentTimeMillis()).apply();
    }

    public static long serviceProbeAt() {
        return store().getLong(KEY_PROBE_SERVICE_AT, 0L);
    }

    public static void clearProbeMarkers() {
        store().edit()
                .remove(KEY_PROBE_RECEIVER_AT)
                .remove(KEY_PROBE_SERVICE_AT)
                .apply();
    }

    /**
     * 状态位采样开关。
     *
     * <p>0.7.40-M6 / 61 起界面上去掉了这个开关（连同「立即采样一次」），于是固定<b>关闭</b>：
     * 采样器不再启动，也不再读以前存下的值。{@code StateWatch} 与快照相关的方法先留着，
     * 只是没有入口把它打开。
     */
    public static boolean isStateWatchEnabled() {
        return false;
    }

    /** 上一次采样时每个应用的状态快照，元素形如「包名 enabled=true stopped=false suspended=false setting=2」。 */
    public static Set<String> getStateSnapshot() {
        return new HashSet<>(store().getStringSet(KEY_STATE_SNAPSHOT, Collections.emptySet()));
    }

    public static void setStateSnapshot(Set<String> snapshot) {
        store().edit().putStringSet(KEY_STATE_SNAPSHOT, new HashSet<>(snapshot)).apply();
    }

    /** 丢掉快照：下次采样重新取基线（中途停了多久不知道，比对就没有意义了）。 */
    public static void clearStateSnapshot() {
        store().edit().remove(KEY_STATE_SNAPSHOT).apply();
    }

    public static void markStateSample() {
        store().edit().putLong(KEY_STATE_SAMPLE_AT, System.currentTimeMillis()).apply();
    }

    /** 上一次采样的时刻，0 表示还没采样过。 */
    public static long stateSampleAt() {
        return store().getLong(KEY_STATE_SAMPLE_AT, 0L);
    }

}

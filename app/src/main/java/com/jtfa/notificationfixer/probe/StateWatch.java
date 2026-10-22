package com.jtfa.notificationfixer.probe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.Prefs;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 状态位采样器：定时读一遍「收到过通知的应用」的那几个状态位，只在<b>变化</b>时写日志。
 *
 * <p>为什么需要它：能不能预热，最后落在四个状态位上（{@code FLAG_STOPPED}、{@code enabledSetting}、
 * {@code enabled}、{@code FLAG_SUSPENDED}），而 2026-10-17 的复核已经看到同一个应用前后读数不同 ——
 * 说明这些位会被 ROM 自己改写，可我们不知道<b>是谁在什么时候改的</b>。这是只能靠时间回答的问题：
 * 手动读一次只能得到那一刻的快照，采几天才知道变化长什么样。
 *
 * <p>它要回答的具体问题：
 *
 * <ul>
 *   <li>把长期没打开、被冻结的应用放行（自启动 / 关联启动）并打开一次之后，ROM 会不会过几天
 *       又把它冻回去？如果不会再冻，那么「放行一次」对用户就是长期有效的建议；</li>
 *   <li>状态位的变化与什么同步发生（用户打开应用、收到推送、系统清理）；</li>
 *   <li>「打开一次」到底清掉的是 {@code FLAG_STOPPED}，还是顺带把 {@code enabledSetting} 也改了。</li>
 * </ul>
 *
 * <p><b>只读</b>：全程只调 {@code getApplicationInfo} / {@code getApplicationEnabledSetting}，
 * 不改任何状态，不启动任何应用，不经过特权通道。日志里只有包名与状态位原值。
 *
 * <p>采样间隔是 {@link #INTERVAL_MS}，由常驻前台服务驱动（见 {@code KeepAliveService}）。
 * 本应用自己也可能被系统延迟或冻结，所以间隔只是意图，实际时刻以日志里的时间为准 ——
 * 这也是日志一定带时间戳的原因。
 */
public final class StateWatch {

    /** 采样间隔。15 分钟足够看清「天级」的变化，又不会把日志塞满。 */
    public static final long INTERVAL_MS = 15L * 60L * 1000L;

    /**
     * 观察集上限。
     *
     * <p>观察集取「收到过通知的应用」；上限只为了防止极端情况下
     * 一次采样做几十上百次 PackageManager 往返。
     */
    public static final int MAX_PACKAGES = 40;

    /** 状态串里字段的顺序，也是日志里 diff 的书写顺序。 */
    private static final String[] STATE_FIELDS = {"enabled", "stopped", "suspended", "setting"};

    private static HandlerThread sThread;
    private static Handler sHandler;

    /** 只存 Application 上下文（进程级单例，不会泄漏 Activity），采样线程靠它读 PackageManager。 */
    @SuppressLint("StaticFieldLeak")
    private static Context sContext;

    /** 一次采样的结果，给界面提示用。 */
    public static final class Result {

        /** 这次实际读到的应用数。 */
        public final int watched;

        /** 状态位发生变化的个数。 */
        public final int changed;

        /** 观察集为空：还没有记录到任何通知来源。 */
        public final boolean empty;

        /** 这次取的是基线（此前没有快照）。 */
        public final boolean baseline;

        Result(int watched, int changed, boolean empty, boolean baseline) {
            this.watched = watched;
            this.changed = changed;
            this.empty = empty;
            this.baseline = baseline;
        }
    }

    /** 采样结果回调，在主线程上被调用。 */
    public interface Callback {
        void onDone(Result result);
    }

    private StateWatch() {
    }

    /**
     * 周期任务：读一遍，再排下一次。
     *
     * <p>每次运行前都重新查一次开关：采样器跟着服务活，开关关掉后这一轮跑完就不再排下一次。
     */
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            final Context context = sContext;
            final Handler handler = sHandler;
            if (context == null || handler == null || !Prefs.isStateWatchEnabled()) {
                return;
            }
            try {
                sample(context);
            } catch (Throwable t) {
                EventLog.get().log(EventLog.CAT_DIAG, "状态位采样失败：" + t);
            }
            handler.removeCallbacks(this);
            if (Prefs.isStateWatchEnabled()) {
                handler.postDelayed(this, INTERVAL_MS);
            }
        }
    };

    /** 常驻服务启动时调用：开关开着就起线程并立刻取一次基线。 */
    public static void start(Context context) {
        sContext = context.getApplicationContext();
        if (!Prefs.isStateWatchEnabled()) {
            return;
        }
        ensureThread();
        Handler handler = sHandler;
        if (handler != null) {
            handler.removeCallbacks(TICK);
            handler.post(TICK);
        }
    }

    /** 常驻服务停止时调用：撤掉周期任务。快照保留，服务再起来时接着比。 */
    public static void stop() {
        Handler handler = sHandler;
        if (handler != null) {
            handler.removeCallbacks(TICK);
        }
        HandlerThread thread = sThread;
        sThread = null;
        sHandler = null;
        if (thread != null) {
            thread.quitSafely();
        }
    }

    /**
     * 设置页开关变化时调用。
     *
     * <p>关掉开关会丢掉基线：中途停了多久不知道，下次打开重新取一次基线才说得清
     * 「这段时间里变了什么」。
     */
    public static void onSettingChanged(Context context, boolean enabled) {
        if (enabled) {
            start(context);
            return;
        }
        stop();
        Prefs.clearStateSnapshot();
    }

    /** 手动采一次（与开关无关），结果回调到主线程。 */
    public static void sampleNow(Context context, @Nullable final Callback callback) {
        final Context appContext = context.getApplicationContext();
        sContext = appContext;
        ensureThread();
        Handler handler = sHandler;
        if (handler == null) {
            return;
        }
        handler.post(() -> {
            Result result = null;
            try {
                result = sample(appContext);
            } catch (Throwable t) {
                EventLog.get().log(EventLog.CAT_DIAG, "状态位采样失败：" + t);
            }
            if (!Prefs.isStateWatchEnabled()) {
                // 手动采样把线程借起来了，开关关着就还回去
                stop();
            }
            final Result done = result;
            if (callback == null) {
                return;
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                if (done != null) {
                    callback.onDone(done);
                }
            });
        });
    }

    /** 设置页那行状态文字：上次采样时间与观察集大小。 */
    public static String statusText() {
        long at = Prefs.stateSampleAt();
        if (at <= 0L) {
            return "还没有采样过";
        }
        String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(at));
        return "最近一次采样：" + time + "（观察 " + Prefs.getStateSnapshot().size() + " 个应用）";
    }

    // ---------------------------------------------------------------- 采样本体

    /**
     * 读一遍观察集，与上次快照比对，只把变化写进日志。
     *
     * <p>第一次（没有快照）写一条基线：一次日志条目带上所有应用的原始读数，
     * 免得把 40 行塞进 800 条的环形缓冲里。
     */
    private static Result sample(Context context) {
        Set<String> packages = watchedPackages();
        if (packages.isEmpty()) {
            EventLog.get().log(EventLog.CAT_DIAG, "状态位采样：还没有记录到通知来源，没有可采样的应用");
            return new Result(0, 0, true, false);
        }

        PackageManager packageManager = context.getPackageManager();
        Map<String, String> current = new TreeMap<>();
        Map<String, String> raw = new TreeMap<>();
        for (String packageName : packages) {
            Read read = read(packageManager, packageName);
            current.put(packageName, read.state);
            raw.put(packageName, read.raw);
        }

        Map<String, String> previous = split(Prefs.getStateSnapshot());
        boolean baseline = previous.isEmpty();

        Set<String> snapshot = new HashSet<>();
        List<String> changed = new ArrayList<>();
        StringBuilder baselineDetail = new StringBuilder();
        for (Map.Entry<String, String> entry : current.entrySet()) {
            String packageName = entry.getKey();
            String state = entry.getValue();
            snapshot.add(packageName + " " + state);
            if (baseline) {
                if (baselineDetail.length() > 0) {
                    baselineDetail.append('\n');
                }
                baselineDetail.append(packageName).append("  ").append(raw.get(packageName));
                continue;
            }
            String before = previous.get(packageName);
            if (before == null || before.equals(state)) {
                continue;
            }
            changed.add(packageName);
            EventLog.get().log(EventLog.CAT_DIAG,
                    "状态位变化 " + packageName + "：" + diff(before, state),
                    raw.get(packageName));
        }

        if (baseline) {
            EventLog.get().log(EventLog.CAT_DIAG,
                    "状态位采样基线：" + current.size() + " 个应用"
                            + (packages.size() < Prefs.notifiedCount()
                            ? "（观察集上限 " + MAX_PACKAGES + "，只取包名前 " + MAX_PACKAGES + " 个）" : "")
                            + "，此后只在状态位变化时记录",
                    baselineDetail.toString());
        } else if (!changed.isEmpty()) {
            EventLog.get().log(EventLog.CAT_DIAG,
                    "状态位采样：本轮 " + changed.size() + " 个应用发生变化（观察 " + current.size() + " 个）");
        }

        Prefs.setStateSnapshot(snapshot);
        Prefs.markStateSample();
        return new Result(current.size(), changed.size(), false, baseline);
    }

    /** 观察集：收到过通知的应用，按包名排序后截断。 */
    private static Set<String> watchedPackages() {
        Set<String> capped = new TreeSet<>();
        for (String packageName : new TreeSet<>(Prefs.getNotifiedPackages())) {
            if (capped.size() >= MAX_PACKAGES) {
                break;
            }
            if (packageName != null && !packageName.isEmpty()) {
                capped.add(packageName);
            }
        }
        return capped;
    }

    /** 一个应用这次读到的状态：{@code state} 用来比对，{@code raw} 写进日志。 */
    private static final class Read {

        final String state;
        final String raw;

        Read(String state, String raw) {
            this.state = state;
            this.raw = raw;
        }
    }

    private static Read read(PackageManager packageManager, String packageName) {
        ApplicationInfo application;
        try {
            application = packageManager.getApplicationInfo(
                    packageName, PackageManager.ApplicationInfoFlags.of(0));
        } catch (Exception e) {
            return new Read("unreadable", "读不到（已卸载，或对本应用不可见）："
                    + e.getClass().getSimpleName());
        }
        int setting;
        try {
            setting = packageManager.getApplicationEnabledSetting(packageName);
        } catch (Exception e) {
            setting = -1;
        }
        boolean stopped = (application.flags & ApplicationInfo.FLAG_STOPPED) != 0;
        boolean suspended = (application.flags & ApplicationInfo.FLAG_SUSPENDED) != 0;
        String state = "enabled=" + application.enabled
                + " stopped=" + stopped
                + " suspended=" + suspended
                + " setting=" + setting;
        String raw = "enabled=" + application.enabled
                + " flags=0x" + Integer.toHexString(application.flags)
                + (stopped ? "[强制停止]" : "")
                + (suspended ? "[挂起]" : "")
                + " enabledSetting=" + setting
                + "(" + ComponentInventory.enabledSettingName(setting) + ")";
        return new Read(state, raw);
    }

    /** 把快照集合还原成「包名 → 状态串」。包名里没有空格，所以按第一个空格切。 */
    private static Map<String, String> split(Set<String> snapshot) {
        Map<String, String> map = new TreeMap<>();
        for (String line : snapshot) {
            int space = line.indexOf(' ');
            if (space > 0 && space < line.length() - 1) {
                map.put(line.substring(0, space), line.substring(space + 1));
            }
        }
        return map;
    }

    /** 逐字段比对两个状态串，写成「FLAG_STOPPED true → false；enabledSetting 2 → 1」。 */
    private static String diff(String before, String after) {
        Map<String, String> oldFields = fields(before);
        Map<String, String> newFields = fields(after);
        StringBuilder text = new StringBuilder();
        for (String field : STATE_FIELDS) {
            String oldValue = oldFields.get(field);
            String newValue = newFields.get(field);
            if (oldValue == null || oldValue.equals(newValue)) {
                continue;
            }
            if (text.length() > 0) {
                text.append('；');
            }
            text.append(fieldLabel(field)).append(' ')
                    .append(oldValue).append(" → ").append(newValue);
        }
        return text.length() == 0 ? "（原始读数变化）" : text.toString();
    }

    private static Map<String, String> fields(String state) {
        Map<String, String> map = new TreeMap<>();
        for (String part : state.split(" ")) {
            int equals = part.indexOf('=');
            if (equals > 0 && equals < part.length() - 1) {
                map.put(part.substring(0, equals), part.substring(equals + 1));
            }
        }
        return map;
    }

    private static String fieldLabel(String field) {
        switch (field) {
            case "stopped":
                return "FLAG_STOPPED";
            case "suspended":
                return "FLAG_SUSPENDED";
            case "setting":
                return "enabledSetting";
            default:
                return field;
        }
    }

    private static void ensureThread() {
        if (sHandler != null) {
            return;
        }
        HandlerThread thread = new HandlerThread("nf-state-watch");
        thread.start();
        sThread = thread;
        sHandler = new Handler(thread.getLooper());
    }
}

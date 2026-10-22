package com.jtfa.notificationfixer.warm;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import com.jtfa.notificationfixer.R;
import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.Prefs;
import com.jtfa.notificationfixer.probe.ActivityInventory;

/**
 * 第 ④ 条预热路：启动一个「启动时不显示界面」的 Activity（见 {@link ActivityInventory}）。
 *
 * <p>0.7.46-M6 / 67 起只剩一条通道：本应用 uid 的 {@code startActivity}。原来的特权通道
 * （{@code am start}，shell / root）随设置页那一枚开关一起撤掉了。Android 10 起后台启动界面
 * 默认被拒，而本应用在面板 / 锁屏 / 横幅三个场景里都没有可见窗口，所以很可能被<b>静默</b>拦下
 * —— 没有异常、没有返回值提示，只有 {@code pidof} 与 logcat 里的
 * {@code Background activity launch blocked} 能说明问题。
 *
 * <p>因此这条路的结果类型是 {@link WarmResult.Outcome#DELIVERED} 而不是 WARMED：
 * 「下发成功了」不等于「进程起来了」，后者只能靠探活（与广播路同一个道理）。
 *
 * <p>本应用 uid 那条 {@code startActivity} 可以额外带一份 {@code ActivityOptions}，里面放
 * {@code android.activity.windowingMode = <值>}（设置页的开关，默认关、默认值 6，见
 * {@link Prefs#isActivityWindowingEnabled()}）。目的不是藏窗口 —— 那一类已实测无效 —— 而是试着
 * 让 ROM 别把这次启动当成「应用拉起应用」而弹确认框。公开 SDK 里没有
 * {@code ActivityOptions.setLaunchWindowingMode}（@hide），但 system_server 读的就是这个 bundle 键，
 * 所以自己造一份交进去，效果一样、不碰隐藏 API。认不认由 ROM 决定，日志只说明这次带没带。
 *
 * <p>某个 ROM 不认这些值时，{@code startActivity} 可能直接抛异常。0.7.46-M6 / 67 起统一兜底：
 * 先记日志，再改用<b>不带启动选项</b>的普通启动重开一次；手动且 windowingMode 失败时还会弹一条
 * Toast 提示可能是这个值有误。异常也可能来自权限、组件或 ROM 的其他限制，不能只凭异常类型
 * 确定是 windowingMode；自动触发与仅 avoidMoveToFront 失败时不打扰。
 *
 * <p>0.7.58-M6 / 79 起，逐应用设置「尝试后台启动activity」改用 AOSP 的公开 API
 * {@link ActivityOptions#makeTaskLaunchBehind()}，并配合
 * {@link Intent#FLAG_ACTIVITY_NEW_DOCUMENT}：AOSP 会把这次 Activity 当成「忽略遮挡也可见」，
 * 仍走 {@code startSpecificActivity()} 启动目标进程，但新任务留在后面，不把页面带到前台。
 * 主路径不再依赖探活；“应用进程存在时不预热”仍是另一道独立闸门，自动触发先探活，命中就跳过。
 *
 * <p>0.7.56 / 77 的 {@code android.activity.avoidMoveToFront} 只把 {@code mDoResume} 置为
 * false，没有 {@code mLaunchTaskBehind}，Activity 不可见，部分系统因此根本不启动进程
 * （0.7.57 / 78 的实测结论）。所以它只保留给不支持 launch-behind 的 Activity 做回退：
 * {@code singleTask} / {@code singleInstance} / {@code documentLaunchMode="never"} 会被 AOSP
 * 清掉 NEW_DOCUMENT，这些目标在探活确认进程已运行时才带旧键，否则普通启动。
 * 名单页的「打开」始终普通启动；windowingMode 两项开关互不影响，可以同时带。
 *
 * <p>候选列表的「尝试后台打开」不读该应用的开关，但和正式触发共用同一套决策：
 * 支持 launch-behind 才后台打开，否则按正式路径回退（进程已在运行用 avoidMoveToFront，
 * 冷进程普通启动）。所以冷/热进程下同一个 Activity 的表现可能不同；结果里会写明
 * 本次到底是后台打开还是普通打开。
 *
 * <p>副作用（必须记住的代价）：目标 Activity 会被真的启动一次，且可能以 document task 出现在
 * 最近任务里。挑中的是「不建窗口 / 透明」、或者用户自己点了「打开」验过的那个，所以通常看不见；
 * 但它仍可能触发对方中转页里的网络请求。launch-behind 不保证所有 ROM 都认。
 */
public final class ActivityWarmStrategy {

    /**
     * {@code ActivityOptions.KEY_WINDOWING_MODE}（@hide，但值本身是稳定的公开约定：
     * system_server 读 options bundle 时认的就是这一串）。
     */
    private static final String KEY_WINDOWING_MODE = "android.activity.windowingMode";

    /**
     * {@code ActivityOptions.KEY_AVOID_MOVE_TO_FRONT}（@hide）：不把目标 Activity 所在的任务栈
     * 移到前台。0.7.58 / 79 起只作为不支持 launch-behind 时的回退，不再是主路径。
     */
    private static final String KEY_AVOID_MOVE_TO_FRONT = "android.activity.avoidMoveToFront";

    /**
     * Activity 路「已投递」的尾句。它只对日志有价值：结果弹窗里会去掉，
     * 因为用户此刻要的是这次实验的结论，不是后台限制的通用说明。
     */
    public static final String LOG_DETAIL_DELIVERED_SUFFIX =
            "；后台启动限制会静默拦下它，只能靠探活判断";

    /** 结果弹窗与日志里写的「本次实际怎么打开」。 */
    private static final String LAUNCH_MODE_BEHIND = "后台打开（launch-behind）";
    private static final String LAUNCH_MODE_NORMAL = "普通打开";
    private static final String LAUNCH_MODE_AVOID_MOVE =
            "普通打开（avoidMoveToFront 回退，不切前台）";
    private static final String LAUNCH_MODE_COLD_FALLBACK =
            "普通打开（不支持 launch-behind，未确认进程在运行）";
    private static final String LAUNCH_MODE_OPTIONS_FALLBACK =
            "普通打开（启动选项失败后回退）";
    private static final String LAUNCH_MODE_FAILED = "未打开（启动失败）";

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private final Context mContext;

    public ActivityWarmStrategy(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * 在预热工作线程上调用。{@code manual} = 用户亲手点的「试一次」；
     * {@code noFront} = 该应用自己的「尝试后台启动activity」；
     * {@code processRunning} 只用于不支持 launch-behind 时的回退判断。
     */
    public WarmResult warm(String packageName, ComponentName component, long start, boolean manual,
                           boolean noFront, boolean processRunning) {
        if (component == null) {
            return WarmResult.failed(packageName, "Activity", 0L, "组件为空");
        }
        boolean launchBehind = noFront
                && ActivityInventory.supportsLaunchBehind(mContext, component);
        boolean avoidMove = noFront && !launchBehind && processRunning;
        return viaStartActivity(packageName, component, start, manual, noFront, launchBehind,
                avoidMove);
    }

    /**
     * 名单页某一行「正常打开」：用户点名要看这一个界面，所以永远普通启动；
     * windowingMode 仍按设置带上，保持原来的实验行为。
     */
    public WarmResult openForInspection(String packageName, ComponentName component, long start) {
        if (component == null) {
            return WarmResult.failed(packageName, "Activity", 0L, "组件为空");
        }
        return viaStartActivity(packageName, component, start, true, false, false, false);
    }

    /**
     * 候选列表的「尝试后台打开」：强制 noFront，但和正式触发共用同一套
     * launch-behind / avoidMoveToFront / 普通启动决策。
     *
     * <p>它不是严格的能力测试：不支持 launch-behind 的目标会按正式路径回退。
     * {@code processRunning} 来自预热前探活，所以冷进程与后台已有进程时结果可能不同。
     */
    public WarmResult openBehindForInspection(String packageName, ComponentName component,
                                              long start, boolean processRunning) {
        if (component == null) {
            return WarmResult.failed(packageName, "Activity", 0L, "组件为空")
                    .withLaunchMode(LAUNCH_MODE_FAILED);
        }
        return warm(packageName, component, start, true, true, processRunning);
    }

    /** 本应用 uid：startActivity。 */
    private WarmResult viaStartActivity(String packageName, ComponentName component, long start,
                                        boolean manual, boolean noFront, boolean launchBehind,
                                        boolean avoidMove) {
        Intent intent = ActivityInventory.intentFor(component);
        if (launchBehind) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        boolean windowing = Prefs.isActivityWindowingEnabled();
        int mode = Prefs.getActivityWindowingValue();
        boolean hasOptions = windowing || launchBehind || avoidMove;
        String optionsLabel = optionsLabel(windowing, mode, launchBehind, avoidMove);
        try {
            if (hasOptions) {
                mContext.startActivity(intent,
                        launchOptions(windowing, mode, launchBehind, avoidMove));
            } else {
                mContext.startActivity(intent);
            }
        } catch (Throwable t) {
            long elapsed = SystemClock.uptimeMillis() - start;
            String error = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "：" + t.getMessage());
            if (!hasOptions) {
                return WarmResult.failed(packageName, "Activity", elapsed, error)
                        .withLaunchMode(LAUNCH_MODE_FAILED);
            }
            // 启动选项在这台机器上没能把 Activity 打开：先记下来，再退回普通启动。
            // 正式触发与「尝试后台打开」都走这条兜底，保证两条入口的行为一致。
            EventLog.get().log(EventLog.CAT_WARM,
                    "启动选项（" + optionsLabel + "）打开 Activity 失败：" + packageName,
                    error + "；已改用普通 startActivity");
            if (manual && windowing) {
                sMain.post(() -> Toast.makeText(mContext, R.string.toast_windowing_open_failed,
                        Toast.LENGTH_LONG).show());
            }
            try {
                // 回退必须用干净的 intent：launch-behind 的 NEW_DOCUMENT 还留在原 intent 上的话，
                // 这次“普通启动”仍可能新建 document task 并把页面带到前台。
                mContext.startActivity(ActivityInventory.intentFor(component));
                long elapsed2 = SystemClock.uptimeMillis() - start;
                return WarmResult.delivered(packageName, "Activity", elapsed2,
                        "启动选项（" + optionsLabel + "）失败（" + error
                                + "），已改用普通 startActivity")
                        .withLaunchMode(LAUNCH_MODE_OPTIONS_FALLBACK);
            } catch (Throwable t2) {
                long elapsed2 = SystemClock.uptimeMillis() - start;
                return WarmResult.failed(packageName, "Activity", elapsed2,
                        "启动选项失败后的普通启动也失败："
                                + t2.getClass().getSimpleName()
                                + (t2.getMessage() == null ? "" : "：" + t2.getMessage()))
                        .withLaunchMode(LAUNCH_MODE_FAILED);
            }
        }
        long elapsed = SystemClock.uptimeMillis() - start;
        StringBuilder detail = new StringBuilder();
        if (windowing) {
            detail.append("已带 windowingMode ").append(mode).append("（ActivityOptions bundle）；");
        }
        if (launchBehind) {
            detail.append("已用 makeTaskLaunchBehind + NEW_DOCUMENT 启动（不切前台）；");
        }
        if (avoidMove) {
            detail.append("已带 avoidMoveToFront（不支持 launch-behind 的回退）；");
        }
        if (noFront && !launchBehind && !avoidMove) {
            detail.append("该 Activity 不支持 launch-behind，且未确认进程在运行，已普通启动；");
        }
        detail.append("startActivity 已下发").append(LOG_DETAIL_DELIVERED_SUFFIX);
        return WarmResult.delivered(packageName, "Activity", elapsed, detail.toString())
                .withLaunchMode(launchModeLabel(noFront, launchBehind, avoidMove));
    }

    /** 本次实际怎么打开的：后台打开，或带原因的普通打开。 */
    private static String launchModeLabel(boolean noFront, boolean launchBehind,
                                          boolean avoidMove) {
        if (launchBehind) {
            return LAUNCH_MODE_BEHIND;
        }
        if (avoidMove) {
            return LAUNCH_MODE_AVOID_MOVE;
        }
        if (noFront) {
            return LAUNCH_MODE_COLD_FALLBACK;
        }
        return LAUNCH_MODE_NORMAL;
    }

    /** 日志里写清楚这次带了哪些启动选项。 */
    private static String optionsLabel(boolean windowing, int mode, boolean launchBehind,
                                      boolean avoidMove) {
        StringBuilder label = new StringBuilder();
        if (windowing) {
            label.append("windowingMode ").append(mode);
        }
        if (launchBehind) {
            if (label.length() > 0) {
                label.append(" + ");
            }
            label.append("makeTaskLaunchBehind");
        }
        if (avoidMove) {
            if (label.length() > 0) {
                label.append(" + ");
            }
            label.append("avoidMoveToFront");
        }
        return label.toString();
    }

    /**
     * 把启动选项塞进 {@code ActivityOptions} 的 bundle。
     *
     * <p>launch-behind 走公开的 {@link ActivityOptions#makeTaskLaunchBehind()}；它自己会写入
     * {@code animType=7}，system_server 据此把 {@code mLaunchTaskBehind} 置为 true。windowingMode
     * 与旧的回退键仍是隐藏 bundle 约定，直接放进同一份 options：两个开关都开时互不覆盖。
     */
    private static Bundle launchOptions(boolean windowing, int mode, boolean launchBehind,
                                        boolean avoidMove) {
        ActivityOptions base = launchBehind
                ? ActivityOptions.makeTaskLaunchBehind()
                : ActivityOptions.makeBasic();
        Bundle options = base.toBundle();
        if (options == null) {
            options = new Bundle();
        }
        if (windowing) {
            options.putInt(KEY_WINDOWING_MODE, mode);
        }
        if (avoidMove) {
            options.putBoolean(KEY_AVOID_MOVE_TO_FRONT, true);
        }
        return options;
    }
}

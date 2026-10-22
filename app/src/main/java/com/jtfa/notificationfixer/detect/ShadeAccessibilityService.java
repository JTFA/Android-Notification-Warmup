package com.jtfa.notificationfixer.detect;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.ForegroundAppTracker;
import com.jtfa.notificationfixer.core.HealthWatch;
import com.jtfa.notificationfixer.core.Prefs;

import java.util.List;

/**
 * 通知面板与锁屏判定。
 *
 * <p>下拉通知面板没有公开 API，只能从 SystemUI 的窗口结构推断。
 * 不同 ROM 的 view id 与窗口标题都不一样，所以这里给了多条候选规则，
 * 并且在全部未命中时把真实的窗口结构写进「诊断」日志，便于补规则。
 */
public class ShadeAccessibilityService extends AccessibilityService {

    private static final String SYSTEM_UI = "com.android.systemui";

    /** 判定间隔，避免窗口事件风暴把 binder 打满。 */
    private static final long EVALUATE_THROTTLE_MS = 120L;

    /** 诊断日志间隔。 */
    private static final long DIAG_THROTTLE_MS = 1500L;

    /** 输入法读数的刷新节流（见 {@link #refreshIme}）。 */
    private static final long IME_REFRESH_THROTTLE_MS = 60L;

    /** 表示「通知面板已展开」的候选 view id（省略包名前缀）。 */
    private static final String[] SHADE_VIEW_IDS = {
            "notification_stack_scroller",
            "notification_panel",
            "notification_shade",
            "status_bar_expanded",
            "qs_panel",
            "shade_header_background",
            "notification_container_parent",
            "expanded_view",
    };

    /** 备选规则：窗口标题包含这些关键字。 */
    private static final String[] SHADE_TITLE_HINTS = {
            "NotificationShade",
            "Notification Shade",
    };

    private static volatile ShadeAccessibilityService sInstance;
    private static volatile boolean sConnected;
    private static volatile boolean sHasEvaluated;

    private volatile boolean mShadeOpen;
    private long mLastEvaluateUptime;
    private long mLastDiagUptime;
    private long mLastImeRefreshUptime;
    private String mLastWindowSignature = "";
    private String mLastForegroundPackage;

    public static boolean isConnected() {
        return sConnected;
    }

    /**
     * 当前服务实例，未连接时为 null。
     *
     * <p>同包内的 {@code ImeWatch} 用它当场要一份新窗口读数（延后复看那条路）。
     */
    static ShadeAccessibilityService instanceOrNull() {
        return sInstance;
    }

    /** 是否已经成功读取过一次窗口结构。 */
    public static boolean hasEvaluated() {
        return sHasEvaluated;
    }

    public static boolean isShadeOpen() {
        ShadeAccessibilityService service = sInstance;
        return service != null && service.mShadeOpen;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        sConnected = true;
        EventLog.get().log(EventLog.CAT_SERVICE, "无障碍服务已连接");
        HealthWatch.evaluate(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        refreshIme(event);
        trackForegroundApp(event);

        CharSequence packageName = event.getPackageName();
        if (packageName == null || !SYSTEM_UI.contentEquals(packageName)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - mLastEvaluateUptime < EVALUATE_THROTTLE_MS) {
            return;
        }
        mLastEvaluateUptime = now;
        evaluateShade(now);
    }

    @Override
    public void onInterrupt() {
        // 不需要处理
    }

    @Override
    public void onDestroy() {
        sConnected = false;
        sInstance = null;
        EventLog.get().log(EventLog.CAT_SERVICE, "无障碍服务已断开");
        HealthWatch.evaluate(this);
        super.onDestroy();
    }

    /**
     * 输入法窗口刷新（0.7.30-M6 / 51）。
     *
     * <p>放在最前面：输入法窗口的包名不是 systemui，下面那句过滤根本不会为它走到；而
     * 「输入法现在可不可见」必须在每一个窗口事件上更新一次 —— 它是横幅那条「拉起 Activity」路的
     * 闸门（见 {@link ImeWatch}）。60ms 的节流只是同一批窗口事件的抖动，延后复看那边每次
     * 都会当场另读一份，所以错过一次事件不会让状态卡住。
     */
    private void refreshIme(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - mLastImeRefreshUptime < IME_REFRESH_THROTTLE_MS) {
            return;
        }
        mLastImeRefreshUptime = now;
        try {
            ImeWatch.refresh(getWindows(), now);
        } catch (Exception e) {
            EventLog.get().log(EventLog.CAT_DIAG, "读取输入法窗口失败：" + e);
        }
    }

    private void trackForegroundApp(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (packageName == null) {
            return;
        }
        String name = packageName.toString();
        if (SYSTEM_UI.equals(name) || getPackageName().equals(name)) {
            return;
        }
        if (name.equals(mLastForegroundPackage)) {
            return;
        }
        mLastForegroundPackage = name;
        if (ImeWatch.isImePackage(name)) {
            // 输入法自己的窗口也会走到这里（0.7.30-M6 / 51）。把它当成「当前前台应用」会让两条
            // 判定在打字时失效：横幅里那句「来源应用正在前台 → 不算横幅」，以及预热前那句
            // 「目标已在前台 → 跳过」。所以只更新去重标记，不写进前台应用 —— 原始读数照旧进日志。
            // 判定依据是「真的见过它的输入法窗口」（ImeWatch），不是按包名猜的。
            EventLog.get().log(EventLog.CAT_DIAG,
                    "前台应用 ← " + name + "（输入法窗口，不计入）");
            return;
        }
        ForegroundAppTracker.set(name);
        if (Prefs.isDiagLog()) {
            EventLog.get().log(EventLog.CAT_DIAG, "前台应用 → " + name);
        }
    }

    private void evaluateShade(long now) {
        boolean open = false;
        String rule = "未命中";
        StringBuilder signature = new StringBuilder();

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) {
                    continue;
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    continue;
                }
                CharSequence packageName = root.getPackageName();
                if (packageName == null || !SYSTEM_UI.contentEquals(packageName)) {
                    continue;
                }
                CharSequence titleSequence = window.getTitle();
                String title = titleSequence == null ? "" : titleSequence.toString();
                signature.append('{')
                        .append(windowTypeName(window.getType()))
                        .append(' ').append(title)
                        .append(" / ").append(root.getClassName())
                        .append('}');

                if (!open) {
                    for (String id : SHADE_VIEW_IDS) {
                        List<AccessibilityNodeInfo> nodes =
                                root.findAccessibilityNodeInfosByViewId(SYSTEM_UI + ":id/" + id);
                        if (nodes != null && !nodes.isEmpty()) {
                            open = true;
                            rule = "viewId " + id;
                            break;
                        }
                    }
                }
                if (!open) {
                    for (String hint : SHADE_TITLE_HINTS) {
                        if (title.contains(hint)) {
                            open = true;
                            rule = "窗口标题 " + title;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            EventLog.get().log(EventLog.CAT_DIAG, "读取窗口结构失败：" + e);
            return;
        }

        sHasEvaluated = true;
        String windowSignature = signature.length() == 0
                ? "(没有 systemui 窗口)" : signature.toString();

        if (open != mShadeOpen) {
            mShadeOpen = open;
            if (open) {
                if (Prefs.isDiagLog()) {
                    EventLog.get().log(EventLog.CAT_DIAG, "面板状态 → 展开（规则：" + rule + "）");
                }
                if (Prefs.isTriggerShade()) {
                    List<String> targets = NotificationTargets.visibleInShade(this);
                    if (targets.isEmpty()) {
                        EventLog.get().log(EventLog.CAT_DIAG, "面板已展开，但面板里没有可预热的通知");
                    } else {
                        TriggerBus.emit(new TriggerEvent(
                                TriggerType.SHADE_OPENED, SYSTEM_UI, rule, targets));
                    }
                }
            } else if (Prefs.isDiagLog()) {
                EventLog.get().log(EventLog.CAT_DIAG, "面板状态 → 收起");
            }
        } else if (!open
                && Prefs.isDiagLog()
                && !windowSignature.equals(mLastWindowSignature)
                && now - mLastDiagUptime > DIAG_THROTTLE_MS) {
            mLastDiagUptime = now;
            EventLog.get().log(EventLog.CAT_DIAG, "面板判定未命中，systemui 窗口：" + windowSignature);
        }

        mLastWindowSignature = windowSignature;
    }

    /** 窗口类型名；面板诊断与 {@link ImeWatch} 共用这一份映射。 */
    static String windowTypeName(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION:
                return "APP";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD:
                return "IME";
            case AccessibilityWindowInfo.TYPE_SYSTEM:
                return "SYS";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
                return "A11Y";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER:
                return "SPLIT";
            default:
                return String.valueOf(type);
        }
    }
}

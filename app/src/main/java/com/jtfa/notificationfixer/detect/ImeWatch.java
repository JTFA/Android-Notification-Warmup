package com.jtfa.notificationfixer.detect;

import android.view.accessibility.AccessibilityWindowInfo;

import com.jtfa.notificationfixer.core.EventLog;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「输入法现在可见吗」这一个信号（0.7.30-M6 / 51）。
 *
 * <p>为什么需要它：横幅到达时那一批应用里，被设成「拉起 Activity」的会真的启动一个界面组件，
 * 而正在打字时拉起界面会把焦点抢走、把输入法顶回去。面板与锁屏两个触发点不受影响
 * （那两件事发生时输入法本来就是收起的），另外三条路（Provider / Service / 广播）不启动界面，
 * 所以这道闸门只用在「横幅 + Activity 路」这一个组合上。
 *
 * <p>读数来源就是无障碍服务已有的窗口列表（配置里本来就有
 * {@code flagRetrieveInteractiveWindows}）：只看窗口类型与标题，不读节点树，比面板判定那次
 * 读结构还轻。三个状态：
 *
 * <ul>
 *   <li>{@link #VISIBLE}：窗口列表里有 {@code TYPE_INPUT_METHOD} 的窗口；</li>
 *   <li>{@link #HIDDEN}：读到过窗口，里面没有输入法窗口；</li>
 *   <li>{@link #UNKNOWN}：无障碍没连上或一次都没读到过 —— <b>按原行为处理</b>（照拉），
 *       绝不因为读不到就悄悄改成「不拉」；原始读数照旧进日志交给真机数据决定。</li>
 * </ul>
 *
 * <p>顺带记下见过的输入法窗口包名，给 {@code ForegroundAppTracker} 用：输入法自己的窗口也会发
 * 窗口状态事件，把它当成「当前前台应用」会让两条判定在打字时失效 —— 横幅里那句
 * 「来源应用正在前台 → 不算横幅」，以及预热前那句「目标已在前台 → 跳过」。
 * 这个集合只在真的见过输入法窗口（窗口类型为 {@code TYPE_INPUT_METHOD}）之后才起作用，
 * 是数据说话，不是猜包名。
 */
public final class ImeWatch {

    /** 还没有过读数（无障碍没连上、或一次都没读到）。 */
    public static final int UNKNOWN = -1;

    /** 读到过窗口，里面没有输入法窗口。 */
    public static final int HIDDEN = 0;

    /** 窗口列表里有输入法窗口。 */
    public static final int VISIBLE = 1;

    private static volatile int sState = UNKNOWN;
    private static volatile long sUpdatedUptimeMs;
    private static volatile String sSignature = "";
    private static volatile String sImePackage;

    /** 见过输入法窗口的包名。只在真读到 {@code TYPE_INPUT_METHOD} 窗口时才写。 */
    private static final Set<String> sImePackages = ConcurrentHashMap.newKeySet();

    private ImeWatch() {
    }

    /**
     * 拿一份新读数。由无障碍服务在窗口事件上调用（节流后的缓存），也可能由延后复看临时要一次。
     *
     * @param windows  {@code AccessibilityService#getWindows()} 的结果；null 表示这次没读到
     * @param uptimeMs 拿到这份列表的时刻（{@code SystemClock.uptimeMillis()}）
     * @return 这次的状态：{@link #VISIBLE} 或 {@link #HIDDEN}
     */
    public static int refresh(List<AccessibilityWindowInfo> windows, long uptimeMs) {
        if (windows == null) {
            return sState;
        }
        boolean visible = false;
        String imePackage = null;
        StringBuilder signature = new StringBuilder();
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) {
                continue;
            }
            if (signature.length() > 0) {
                signature.append(' ');
            }
            CharSequence title = window.getTitle();
            signature.append(ShadeAccessibilityService.windowTypeName(window.getType()))
                    .append('/').append(title == null ? "" : title);
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                continue;
            }
            visible = true;
            // 只有输入法窗口才去读 root（一次 binder）：包名用来把输入法从「前台应用」里排掉，
            // 也把这次读到的原始事实写进日志，免得下次还要靠猜它是谁。
            String pkg = rootPackage(window);
            if (pkg != null) {
                imePackage = pkg;
                sImePackages.add(pkg);
                signature.append('[').append(pkg).append(']');
            }
        }

        int state = visible ? VISIBLE : HIDDEN;
        int previous = sState;
        sState = state;
        sUpdatedUptimeMs = uptimeMs;
        sSignature = signature.length() == 0 ? "(没有窗口)" : signature.toString();
        if (imePackage != null) {
            sImePackage = imePackage;
        }
        if (previous != state) {
            // 只在变化时写：这是原始读数，不跟诊断开关走（它一次横幅决策就要用一次）
            EventLog.get().log(EventLog.CAT_DIAG,
                    visible ? "输入法窗口 → 可见" : "输入法窗口 → 收起",
                    "窗口=" + sSignature);
        }
        return state;
    }

    /**
     * 当场要一个新读数（延后复看用）。
     *
     * <p>无障碍服务不在（没连上 / 被系统断了）时返回 {@link #UNKNOWN}，而不是拿一份可能的旧读数
     * 接着延后 —— 读不到就按原行为拉起，这一点与其它读数一致。
     */
    public static int stateNow() {
        ShadeAccessibilityService service = ShadeAccessibilityService.instanceOrNull();
        if (service == null) {
            return UNKNOWN;
        }
        try {
            return refresh(service.getWindows(), android.os.SystemClock.uptimeMillis());
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }

    /** 最近一次读数（不会去读新的）。 */
    public static int state() {
        return sState;
    }

    public static boolean visible() {
        return sState == VISIBLE;
    }

    /** 上次读数的原始窗口串，写日志用。 */
    public static String signature() {
        return sSignature;
    }

    /** 距上次读数经过的毫秒数（一次都没读过时是很大的数，日志里据此能看出读数新不新）。 */
    public static long ageMs() {
        long updated = sUpdatedUptimeMs;
        if (updated == 0L) {
            return -1L;
        }
        return android.os.SystemClock.uptimeMillis() - updated;
    }

    /** 见过的输入法包名（可能为 null）。 */
    public static String imePackage() {
        return sImePackage;
    }

    /** 这个包名是不是被当成输入法看待（见过它的输入法窗口）。 */
    public static boolean isImePackage(String packageName) {
        return packageName != null && sImePackages.contains(packageName);
    }

    public static String stateName(int state) {
        switch (state) {
            case VISIBLE:
                return "可见";
            case HIDDEN:
                return "收起";
            default:
                return "未知";
        }
    }

    /** 窗口的 root 包名；读不到就返回 null（不猜）。 */
    private static String rootPackage(AccessibilityWindowInfo window) {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
            if (root == null) {
                return null;
            }
            CharSequence packageName = root.getPackageName();
            return packageName == null ? null : packageName.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}

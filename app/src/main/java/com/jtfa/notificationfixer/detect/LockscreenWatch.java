package com.jtfa.notificationfixer.detect;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「锁屏上到底画了哪几行通知、每一行是谁画的」这一个读数（0.7.33-M6 / 54 起，0.7.34-M6 / 55 起认行）。
 *
 * <p>为什么需要它：锁屏触发原来只用「系统设置 + 每条通知的可见性」<b>估算</b>锁屏上有没有东西
 * （{@code ScreenStateReceiver} 的老办法路）。真机数据把这个估算推翻了：2026-10-18 的一行日志是
 * 「锁屏可见 47 / 活动通知 76」，而当时锁屏上一条通知都没有 —— 用户在系统里关掉了锁屏通知，
 * 而那个开关在 ColorOS 上<b>不落到</b> AOSP 的 {@code lock_screen_show_notifications}（读到的是 1），
 * 我们既看不见它，也算不出「锁屏只精简显示几条」。
 *
 * <p>54 先做到「数行」：锁屏那一刻去看 SystemUI 窗口里那条通知列表容器，数它<b>真正可见</b>的子节点。
 * 三态：
 *
 * <ul>
 *   <li>{@link #DRAWN}：找到容器，里面有 N 行可见的通知（{@link Reading#rows} 逐行带着读数）；</li>
 *   <li>{@link #NONE}：SystemUI 的窗口读到了、也按同一套规则找过容器 —— 容器在但一行都没画，
 *       或者这个结构里根本没有通知列表容器（都按「锁屏上没有通知」处理，理由见 {@link #readNow}）；</li>
 *   <li>{@link #UNKNOWN}：无障碍没连上 / 取窗口列表失败 / 连 SystemUI 窗口都没有。</li>
 * </ul>
 *
 * <p><b>55 起还要回答「这一行是谁」</b>，靠两条线索（见 {@link Row}）：
 *
 * <ol>
 *   <li><b>行里的包名（精确）</b>：行本身是 SystemUI 画的（节点包名是 {@code com.android.systemui}），
 *       但通知的布局是<b>发通知那个应用</b>的 RemoteViews，而 RemoteViews 渲染出来的视图带的是属主的
 *       context —— {@code RemoteViewsContextWrapper.getPackageName()} 返回 RemoteViews 的属主包名
 *       （{@code RemoteViews.java} 里 {@code getContextForResourcesEnsuringCorrectCachedApkPaths}
 *       在「宿主包 ≠ 属主包」时会 {@code createApplicationContext(属主应用)}），而
 *       {@code View.onInitializeAccessibilityNodeInfo} 把 {@code mContext.getPackageName()} 写进节点。
 *       所以行进子树里那些非 systemui 的包名，就是这一行的来源应用。</li>
 *   <li><b>行里的文字（兜底）</b>：行里画出来的应用名。给媒体通知这类「整行都是 SystemUI 自己画的」
 *       情况兜底 —— 那种行的子树里一个应用包名都没有，只剩应用名。</li>
 * </ol>
 *
 * <p>两条线索都只是<b>读数</b>，怎么用它们（怎么认行、认不出怎么办）在 {@link LockscreenMatch} 与
 * {@code ScreenStateReceiver} 里决定。容器是按候选 view id 与类名找的（AOSP 那一套名字）；ColorOS
 * 若用自己的名字，诊断日志会把 SystemUI 窗口里的 view id 全列出来（{@link Reading#detail}），
 * 据此补候选即可 —— 与当初适配面板判定同一套做法。
 */
public final class LockscreenWatch {

    /** 没读到（无障碍没连上、取窗口失败、没有 systemui 窗口）。 */
    public static final int UNKNOWN = -1;

    /** 读到了：锁屏上一条通知都没画。 */
    public static final int NONE = 0;

    /** 读到了：锁屏上画了通知，逐行读数在 {@link Reading#rows} 里。 */
    public static final int DRAWN = 1;

    private static final String SYSTEM_UI = "com.android.systemui";

    /** 框架自己的视图（不是来源应用），不算线索。 */
    private static final String ANDROID = "android";

    /** 通知列表容器的候选 view id（省略包名前缀）。 */
    private static final String[] LIST_ID_HINTS = {
            "notification_stack_scroller",
            "notification_list",
            "notification_container_parent",
            "keyguard_notification_container",
    };

    /** 备选规则：类名里含这些片段。 */
    private static final String[] LIST_CLASS_HINTS = {
            "NotificationStackScrollLayout",
            "NotificationList",
    };

    /** 一次读数的节点预算：窗口树再大也不会让这一趟失控（一次锁屏点亮才读一次）。 */
    private static final int MAX_NODES = 3000;

    /** 一行的节点预算（数包名与文字用）。 */
    private static final int MAX_ROW_NODES = 150;

    /** 诊断明细里最多列多少个容器的子节点 / 多少个见过的 view id。 */
    private static final int MAX_DETAIL_ITEMS = 30;

    /** 一行最多记几段文字 / 几个包名，以及每段文字留多长 —— 只为了日志不失控。 */
    private static final int MAX_ROW_TEXTS = 8;
    private static final int MAX_ROW_PACKAGES = 6;
    private static final int MAX_TEXT_CHARS = 40;

    private LockscreenWatch() {
    }

    /**
     * 锁屏上的一行通知（容器的一个子节点）。
     *
     * <p>{@link #packages} 与 {@link #texts} 是这一行的两条线索，都在可见的行上收集：前者是行进子树里
     * 非 systemui 的包名（来源应用自己画的节点），后者是行里画出来的文字（应用名就在这里）。
     */
    public static final class Row {

        /** 在容器里排第几（0 是最上面那条）。 */
        public int index;

        public boolean visible;

        public String className = "";

        public String viewId = "";

        /** 行里由应用自己画出来的节点带的包名（去重、最多 {@link #MAX_ROW_PACKAGES} 个）。 */
        public final List<String> packages = new ArrayList<>();

        /** 行里画出来的文字（去重、最多 {@link #MAX_ROW_TEXTS} 段，每段最多 {@link #MAX_TEXT_CHARS} 字）。 */
        public final List<String> texts = new ArrayList<>();

        /** 写日志用的一行读数。 */
        public String describe() {
            StringBuilder builder = new StringBuilder();
            if (!visible) {
                builder.append("不可见 ");
            }
            builder.append(className).append(' ').append(viewId);
            builder.append(" 包名=").append(packages.isEmpty() ? "-" : join(packages));
            builder.append(" 文字=").append(texts.isEmpty() ? "-" : join(texts));
            return builder.toString();
        }

        private static String join(List<String> values) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    builder.append('/');
                }
                builder.append(values.get(i));
            }
            return builder.toString();
        }
    }

    /** 一次锁屏读数。 */
    public static final class Reading {

        public int state = UNKNOWN;

        /** 容器里的每一行（含不可见的），按从上到下排。 */
        public final List<Row> rows = new ArrayList<>();

        /** 命中的容器（类名 / view id / 子节点数），写日志用。 */
        public String container = "";

        /** 这一刻 systemui 窗口的窗口串。 */
        public String signature = "";

        /** 原始明细：容器的子节点，或没找到容器时见过的 view id。 */
        public String detail = "";

        /** 这次读数为什么是这个结论（读不到时说明原因）。 */
        public String note = "";

        /** 真正画出来的行数。 */
        public int visibleRows() {
            int count = 0;
            for (Row row : rows) {
                if (row.visible) {
                    count++;
                }
            }
            return count;
        }

        public String stateName() {
            switch (state) {
                case DRAWN:
                    return "画了 " + visibleRows() + " 条";
                case NONE:
                    return "没有画通知";
                default:
                    return "未知";
            }
        }
    }

    /**
     * 当场读一份锁屏事实。
     *
     * <p>「没有找到通知列表容器」也算 {@link #NONE}：ColorOS 把锁屏通知整个关掉时，很可能就是
     * 这个容器不在（或空着）。反过来，如果它用的是我们不认识的结构，真机日志里
     * {@link Reading#detail} 会把 SystemUI 窗口里的 view id 全列出来，补上候选即可 ——
     * 这也是为什么「找不到容器」时要把那串 id 记下来。<b>刻意偏向「宁可先不预热」</b>：
     * 锁屏上明明有通知却漏预热，日志一行就能看出来（而且用户已经明确不要「锁屏没东西还预热」）。
     *
     * @param withDetail 是否顺手把原始明细（容器子节点 / view id 串）打出来，供真机适配
     */
    public static Reading readNow(boolean withDetail) {
        Reading reading = new Reading();

        ShadeAccessibilityService service = ShadeAccessibilityService.instanceOrNull();
        if (service == null) {
            reading.note = "无障碍服务未连接";
            return reading;
        }
        List<AccessibilityWindowInfo> windows;
        try {
            windows = service.getWindows();
        } catch (Throwable t) {
            reading.note = "取窗口列表失败：" + t;
            return reading;
        }
        if (windows == null) {
            reading.note = "窗口列表为空";
            return reading;
        }

        StringBuilder signature = new StringBuilder();
        boolean sawSystemUiWindow = false;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) {
                continue;
            }
            AccessibilityNodeInfo root = null;
            try {
                root = window.getRoot();
            } catch (Throwable ignored) {
                // 读不到就跳过这个窗口
            }
            if (root == null) {
                continue;
            }
            CharSequence packageName = root.getPackageName();
            if (packageName == null || !SYSTEM_UI.contentEquals(packageName)) {
                continue;
            }
            sawSystemUiWindow = true;
            CharSequence title = window.getTitle();
            if (signature.length() > 0) {
                signature.append(' ');
            }
            signature.append('{')
                    .append(ShadeAccessibilityService.windowTypeName(window.getType()))
                    .append(' ').append(title == null ? "" : title)
                    .append(" / ").append(root.getClassName())
                    .append('}');

            AccessibilityNodeInfo container = findContainer(root);
            if (container == null) {
                continue;
            }

            int childCount = container.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = container.getChild(i);
                if (child == null) {
                    continue;
                }
                Row row = new Row();
                row.index = i;
                row.visible = child.isVisibleToUser();
                row.className = brief(child.getClassName());
                row.viewId = shortId(child);
                if (row.visible) {
                    collectRow(child, row);
                }
                reading.rows.add(row);
            }
            reading.state = reading.visibleRows() > 0 ? DRAWN : NONE;
            reading.container = brief(container.getClassName()) + " " + shortId(container)
                    + "，子节点 " + childCount;
            reading.signature = signature.toString();
            if (withDetail) {
                StringBuilder detail = new StringBuilder();
                for (Row row : reading.rows) {
                    if (row.index >= MAX_DETAIL_ITEMS) {
                        break;
                    }
                    detail.append('[').append(row.describe()).append(']');
                }
                reading.detail = detail.toString();
            }
            return reading;
        }

        reading.signature = signature.length() == 0 ? "(没有 systemui 窗口)" : signature.toString();
        if (!sawSystemUiWindow) {
            reading.note = "没有 systemui 窗口";
            return reading;
        }
        reading.state = NONE;
        reading.note = "没找到通知列表容器（见明细里的 view id）";
        reading.detail = withDetail ? dumpViewIds() : "";
        return reading;
    }

    /**
     * 把一行子树走一遍，收两条线索：非 systemui 的包名与画出来的文字。
     *
     * <p>走的是广度优先、有节点预算；收满了就不再往里看（只影响日志长度与命中率，不影响判定本身）。
     */
    private static void collectRow(AccessibilityNodeInfo rowRoot, Row row) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(rowRoot);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_ROW_NODES
                && (row.packages.size() < MAX_ROW_PACKAGES || row.texts.size() < MAX_ROW_TEXTS)) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;

            CharSequence nodePackage = node.getPackageName();
            if (nodePackage != null) {
                String value = nodePackage.toString();
                if (!SYSTEM_UI.equals(value) && !ANDROID.equals(value)
                        && !row.packages.contains(value) && row.packages.size() < MAX_ROW_PACKAGES) {
                    row.packages.add(value);
                }
            }
            addText(row, node.getText());
            addText(row, node.getContentDescription());

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
    }

    /** 记一段行内文字（去空白、去重、截断）。 */
    private static void addText(Row row, CharSequence text) {
        if (text == null || row.texts.size() >= MAX_ROW_TEXTS) {
            return;
        }
        String value = text.toString().trim();
        if (value.isEmpty()) {
            return;
        }
        if (value.length() > MAX_TEXT_CHARS) {
            value = value.substring(0, MAX_TEXT_CHARS) + "…";
        }
        if (!row.texts.contains(value)) {
            row.texts.add(value);
        }
    }

    /** 按候选 view id、再按类名在窗口树里找通知列表容器；找不到返回 null。 */
    private static AccessibilityNodeInfo findContainer(AccessibilityNodeInfo root) {
        for (String id : LIST_ID_HINTS) {
            AccessibilityNodeInfo node = find(root, id, true);
            if (node != null) {
                return node;
            }
        }
        for (String hint : LIST_CLASS_HINTS) {
            AccessibilityNodeInfo node = find(root, hint, false);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    /** 广度优先找第一个匹配的节点（节点预算见 {@link #MAX_NODES}）。 */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String needle,
                                              boolean byViewId) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;
            if (byViewId) {
                String id = viewId(node);
                if (id.endsWith("/" + needle) || id.equals(needle)) {
                    return node;
                }
            } else {
                String className = brief(node.getClassName());
                if (className.contains(needle)) {
                    return node;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    /**
     * 把 SystemUI 窗口里见过的 view id 列出来（最多 {@link #MAX_DETAIL_ITEMS} 个，去重）。
     *
     * <p>找不到容器时唯一的线索就是它：ColorOS 的自有命名一眼就能看出来，补进
     * {@link #LIST_ID_HINTS} 即可。
     */
    private static String dumpViewIds() {
        ShadeAccessibilityService service = ShadeAccessibilityService.instanceOrNull();
        if (service == null) {
            return "";
        }
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) {
                return "";
            }
            Set<String> ids = new LinkedHashSet<>();
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
                collectIds(root, ids);
            }
            return ids.isEmpty() ? "(窗口树里没有带 id 的节点)" : String.valueOf(ids);
        } catch (Throwable t) {
            return "列出 view id 失败：" + t;
        }
    }

    private static void collectIds(AccessibilityNodeInfo root, Set<String> ids) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES && ids.size() < MAX_DETAIL_ITEMS) {
            AccessibilityNodeInfo node = queue.poll();
            visited++;
            String id = viewId(node);
            int slash = id.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < id.length()) {
                ids.add(id.substring(slash + 1));
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
    }

    private static String viewId(AccessibilityNodeInfo node) {
        CharSequence id = node.getViewIdResourceName();
        return id == null ? "" : id.toString();
    }

    /** view id 只留 ":id/" 后面那一段，写日志用。 */
    private static String shortId(AccessibilityNodeInfo node) {
        String id = viewId(node);
        int slash = id.lastIndexOf('/');
        return slash >= 0 && slash + 1 < id.length() ? id.substring(slash + 1) : id;
    }

    private static String brief(CharSequence text) {
        if (text == null) {
            return "";
        }
        String value = text.toString();
        int dot = value.lastIndexOf('.');
        return dot >= 0 && dot + 1 < value.length() ? value.substring(dot + 1) : value;
    }
}

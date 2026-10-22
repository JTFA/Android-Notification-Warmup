package com.jtfa.notificationfixer.probe;

import android.content.ComponentName;

/**
 * 一个应用「怎么预热」的结论。
 *
 * <p>免 root 下唯一能创建别的应用进程、又完全不碰任务栈的手段只有两种：
 * 借它自己的导出 ContentProvider（进程起来时 Application.onCreate 一定会跑），
 * 或者 BIND_AUTO_CREATE 绑它的导出 Service。
 */
public final class WarmTarget {

    public enum Kind {
        /** 借导出 ContentProvider 拉起进程，副作用最小。 */
        PROVIDER,
        /** 兜底：绑定导出 Service，绑完立刻解绑。 */
        SERVICE,
        /**
         * 第三道门：给导出的广播接收器发一条显式空广播。
         *
         * <p>比前两条重——广播会执行对方的 {@code onReceive}，而 provider / service 只是让进程
         * 起来、不触发业务逻辑。所以它排在后面，只在没有导出 provider / service 时才用。
         */
        RECEIVER,
        /**
         * 第 ④ 条路：借对方一个「启动时不显示界面」的 Activity（见 {@link ActivityInventory}）。
         *
         * <p>0.7.5 起它是<b>逐应用选出来</b>的一条路（名单页点「设置」→ 唤醒方式 → 拉起 Activity），
         * 不是兜底：选了的应用只走它、不再走前三条；没选的应用从不碰它。它要启动界面组件，
         * 所以有「闪一下」与「把对方任务拉到前台」的风险。
         */
        ACTIVITY,
        /** 没有可用组件，或者应用处于无法拉起的状态。 */
        UNAVAILABLE
    }

    public final Kind kind;

    /** {@link Kind#PROVIDER} 时的 authority。 */
    public final String authority;

    /** {@link Kind#SERVICE} / {@link Kind#RECEIVER} 时的组件名。 */
    public final ComponentName component;

    /** {@link Kind#UNAVAILABLE} 时的原因，直接展示给用户。 */
    public final String reason;

    /**
     * 补充信息，写进「诊断」日志用：
     *
     * <ul>
     *   <li>不可预热时，是原始状态 + 候选组件被排除的原因；</li>
     *   <li>可用但应用整体状态可疑时，是一句提醒。</li>
     * </ul>
     */
    public final String note;

    /**
     * 界面上给这条结论加的一句限定语（目前只有 {@link Kind#ACTIVITY} 用）：
     * 「你指定」/「自动挑的：不建窗口」。null = 不需要。
     */
    public final String qualifier;

    private WarmTarget(Kind kind, String authority, ComponentName component,
                       String reason, String note) {
        this(kind, authority, component, reason, note, null);
    }

    private WarmTarget(Kind kind, String authority, ComponentName component,
                       String reason, String note, String qualifier) {
        this.kind = kind;
        this.authority = authority;
        this.component = component;
        this.reason = reason;
        this.note = note;
        this.qualifier = qualifier;
    }

    public static WarmTarget provider(String authority, String note) {
        return new WarmTarget(Kind.PROVIDER, authority, null, null, note);
    }

    public static WarmTarget service(ComponentName component, String note) {
        return new WarmTarget(Kind.SERVICE, null, component, null, note);
    }

    public static WarmTarget receiver(ComponentName component, String note) {
        return new WarmTarget(Kind.RECEIVER, null, component, null, note);
    }

    /** 第 ④ 条路：组件是一个 Activity，{@code qualifier} 说明是谁挑的、推断成什么。 */
    public static WarmTarget activity(ComponentName component, String qualifier, String note) {
        return new WarmTarget(Kind.ACTIVITY, null, component, null, note, qualifier);
    }

    public static WarmTarget unavailable(String reason, String note) {
        return new WarmTarget(Kind.UNAVAILABLE, null, null, reason, note);
    }

    public boolean isAvailable() {
        return kind != Kind.UNAVAILABLE;
    }

    /** 界面与日志里显示的一句话说明。 */
    public String describe() {
        switch (kind) {
            case PROVIDER:
                return "Provider：" + authority;
            case SERVICE:
                return "Service：" + (component == null ? "?" : component.flattenToShortString());
            case RECEIVER:
                return "Receiver：" + (component == null ? "?" : component.flattenToShortString());
            case ACTIVITY:
                return "Activity：" + (component == null ? "?" : component.flattenToShortString())
                        + (qualifier == null ? "" : "（" + qualifier + "）");
            default:
                return "不可预热：" + reason;
        }
    }

    /** 列表行里显示的一句话。 */
    public String describeShort() {
        switch (kind) {
            case PROVIDER:
                return "可预热：ContentProvider（需要开启关联启动，已开启请忽略）";
            case SERVICE:
                return "可预热：Service（需要开启关联启动，已开启请忽略）";
            case RECEIVER:
                return "可预热：广播接收器（会执行对方 onReceive，请自行测试预热效果）";
            case ACTIVITY:
                return "可预热：Activity " + shortComponent()
                        + (qualifier == null ? "" : "（" + qualifier + "）");
            default:
                return "不可预热：" + reason;
        }
    }

    private String shortComponent() {
        if (component == null) {
            return "?";
        }
        String full = component.getClassName();
        String packageName = component.getPackageName();
        if (full.startsWith(packageName + ".")) {
            full = full.substring(packageName.length() + 1);
        }
        return "." + full;
    }
}

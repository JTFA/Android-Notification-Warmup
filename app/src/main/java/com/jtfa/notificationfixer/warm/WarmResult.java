package com.jtfa.notificationfixer.warm;

/** 一次预热尝试的结果。 */
public final class WarmResult {

    public enum Outcome {
        /** 成功拉起（或进程本来就在，调用瞬间返回）。 */
        WARMED,
        /** 广播已投递，但异步执行，能不能拉起只能靠随后的「广播回执」确认。 */
        DELIVERED,
        /** 按规则跳过：冷却中、已在前台、名单排除等。 */
        SKIPPED,
        /** 尝试过但失败：没有可用组件、被强制停止、SecurityException 等。 */
        FAILED,
        /**
         * 预热前探活已经确认进程在运行，这次幂等调用却没完成（例如拿不到 provider 引用）。
         *
         * <p>它和 {@link #FAILED} 的区别只在读法：进程不需要再拉起，写成「预热失败」会
         * 和探活结论自相矛盾；内部仍按失败处理（不改变冷却、重试等规则）。
         */
        IDEMPOTENT_UNFINISHED
    }

    public final String packageName;
    public final Outcome outcome;
    public final String strategy;
    public final long elapsedMs;
    public final String detail;

    /** 预热前后的探活证据（M4）；没有特权通道时为 null。 */
    public final WarmEvidence evidence;

    /**
     * Activity 路的额外读数（本次创建窗口数量 / 顶层是谁 / 后台启动限制拦没拦），
     * 只有手动测 Activity 且特权通道可用时才有；null = 没有这一段。
     */
    public final String activityReadout;

    /**
     * Activity 路这次实际怎么打开的：后台打开 / 普通打开（含回退原因）。
     * 非 Activity 路为 null。结果弹窗与日志都会明确写出来。
     */
    public final String launchMode;

    private WarmResult(String packageName, Outcome outcome, String strategy,
                       long elapsedMs, String detail) {
        this(packageName, outcome, strategy, elapsedMs, detail, null, null, null);
    }

    private WarmResult(String packageName, Outcome outcome, String strategy,
                       long elapsedMs, String detail, WarmEvidence evidence) {
        this(packageName, outcome, strategy, elapsedMs, detail, evidence, null, null);
    }

    private WarmResult(String packageName, Outcome outcome, String strategy, long elapsedMs,
                       String detail, WarmEvidence evidence, String activityReadout) {
        this(packageName, outcome, strategy, elapsedMs, detail, evidence, activityReadout, null);
    }

    private WarmResult(String packageName, Outcome outcome, String strategy, long elapsedMs,
                       String detail, WarmEvidence evidence, String activityReadout,
                       String launchMode) {
        this.packageName = packageName;
        this.outcome = outcome;
        this.strategy = strategy;
        this.elapsedMs = elapsedMs;
        this.detail = detail;
        this.evidence = evidence;
        this.activityReadout = activityReadout;
        this.launchMode = launchMode;
    }

    /** 附上探活证据（只有手动「试一次」会等到复探结束才回调，所以证据是齐的）。 */
    public WarmResult withEvidence(WarmEvidence value) {
        return new WarmResult(packageName, outcome, strategy, elapsedMs, detail, value,
                activityReadout, launchMode);
    }

    /** 附上 Activity 路的原始读数（同样是手动测试才有的东西）。 */
    public WarmResult withActivityReadout(String value) {
        return new WarmResult(packageName, outcome, strategy, elapsedMs, detail, evidence, value,
                launchMode);
    }

    /** 附上本次 Activity 的打开方式（后台打开 / 普通打开）。 */
    public WarmResult withLaunchMode(String value) {
        return new WarmResult(packageName, outcome, strategy, elapsedMs, detail, evidence,
                activityReadout, value);
    }

    /**
     * 把一次「预热前进程已在运行」的失败改读成「幂等调用未完成」。
     *
     * <p>只换展示用的 outcome，detail 原样保留：provider 引用为什么没拿到仍然重要。
     * 调用方只在探活明确 {@code RUNNING}、且走的是 Provider / Service 这类无可见副作用的
     * 后台路时才该用它；Activity 路失败仍是一次真正的启动失败。
     */
    public WarmResult asIdempotentUnfinished() {
        return new WarmResult(packageName, Outcome.IDEMPOTENT_UNFINISHED, strategy, elapsedMs,
                detail, evidence, activityReadout, launchMode);
    }

    public static WarmResult warmed(String packageName, String strategy, long elapsedMs, String detail) {
        return new WarmResult(packageName, Outcome.WARMED, strategy, elapsedMs, detail);
    }

    /** 广播这条路发出去就返回，耗时没有意义，所以单独算一种结果。 */
    public static WarmResult delivered(String packageName, String strategy, long elapsedMs, String detail) {
        return new WarmResult(packageName, Outcome.DELIVERED, strategy, elapsedMs, detail);
    }

    public static WarmResult skipped(String packageName, String detail) {
        return new WarmResult(packageName, Outcome.SKIPPED, "-", 0L, detail);
    }

    public static WarmResult failed(String packageName, String strategy, long elapsedMs, String detail) {
        return new WarmResult(packageName, Outcome.FAILED, strategy, elapsedMs, detail);
    }

    /**
     * 一行式描述。
     *
     * <p>耗时曾经是「进程本来就在」还是「真的冷启动」的判断依据（冷启动几百毫秒、
     * 存活时几毫秒），但那只是间接推断。现在有探活就直接说事实，
     * 见 {@link #describeWithEvidence()}。
     */
    public String describe() {
        switch (outcome) {
            case WARMED:
                return "预热成功 " + packageName + "  " + strategy + "  " + elapsedMs + "ms";
            case DELIVERED:
                return "已投递 " + packageName + "  " + strategy + "  " + detail;
            case SKIPPED:
                return "跳过 " + packageName + "  " + detail;
            case IDEMPOTENT_UNFINISHED:
                return "幂等调用未完成 " + packageName + "  " + strategy + "  " + elapsedMs
                        + "ms  " + detail;
            default:
                return "预热失败 " + packageName + "  " + strategy + "  " + elapsedMs + "ms  " + detail;
        }
    }

    /** 状态页显示用：在 {@link #describe()} 后面附上探活结论与 Activity 读数（有的话）。 */
    public String describeWithEvidence() {
        StringBuilder text = new StringBuilder(describe());
        if (launchMode != null) {
            text.append('\n').append("本次打开方式：").append(launchMode);
        }
        if (evidence != null) {
            String verdict = evidence.verdict();
            if (verdict != null) {
                text.append('\n').append(verdict);
            }
        }
        if (activityReadout != null && !activityReadout.isEmpty()) {
            text.append('\n').append(activityReadout);
        }
        return text.toString();
    }

    /** 给界面提示用的一句话。 */
    public String shortText() {
        switch (outcome) {
            case WARMED:
                return strategy + " 成功，用时 " + elapsedMs + "ms";
            case DELIVERED:
                return "已投递广播，" + detail;
            case SKIPPED:
                return "已跳过：" + detail;
            case IDEMPOTENT_UNFINISHED:
                return "进程已在运行，幂等调用未完成：" + detail;
            default:
                return "失败：" + detail;
        }
    }
}

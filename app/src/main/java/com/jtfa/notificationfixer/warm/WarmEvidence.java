package com.jtfa.notificationfixer.warm;

import com.jtfa.notificationfixer.privileged.ProcessProbe;

/**
 * 一次预热的探活证据（M4）：预热前 / 预热后目标进程在不在。
 *
 * <p>为什么值得单独一个类：<b>耗时证明不了任何事</b>。冷启动是几百毫秒，但进程本来就在时
 * 一次幂等调用也可能要几十毫秒，两者在日志里长得一样。只有「预热前 pidof 无 → 预热后 pid」
 * 这一对照，才真的说明这次预热把进程拉起来了。
 *
 * <p>拿不到证据时（没有特权通道、探活失败）字段为 null，界面与日志都按「未测」显示，
 * 绝不用耗时去假装。
 */
public final class WarmEvidence {

    /** 预热前的探活，可能为 null。 */
    public final ProcessProbe.Probe before;

    /** 预热后的探活，可能为 null。 */
    public final ProcessProbe.Probe after;

    public WarmEvidence(ProcessProbe.Probe before, ProcessProbe.Probe after) {
        this.before = before;
        this.after = after;
    }

    /** 多行文本，给「试一次」的结果对话框用。 */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("预热前探活：").append(before == null ? "未测" : before.describe());
        text.append('\n').append("预热后探活：").append(after == null ? "未测" : after.describe());
        String verdict = verdict();
        if (verdict != null) {
            text.append('\n').append(verdict);
        }
        return text.toString();
    }

    /** 一句话结论；证据不足时返回 null（不猜）。 */
    public String verdict() {
        if (before == null || after == null) {
            return null;
        }
        if (before.isNotRunning() && after.isRunning()) {
            return "结论：这次预热确实把进程拉起来了。";
        }
        if (before.isNotRunning() && after.isNotRunning()) {
            return "结论：预热之后进程仍然不在，这次预热没生效。";
        }
        if (before.isRunning() && after.isRunning()) {
            return "结论：进程预热前就在运行，本次预热只是一次幂等调用。";
        }
        if (before.isRunning()) {
            return "结论：进程预热前在运行，预热后探不到了（可能刚被系统回收）。";
        }
        return null;
    }
}

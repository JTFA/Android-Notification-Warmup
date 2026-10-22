package com.jtfa.notificationfixer.warm;

import android.content.ContentProviderClient;
import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import com.jtfa.notificationfixer.probe.ComponentInventory;

/**
 * 主预热手段：借目标应用自己的导出 ContentProvider。
 *
 * <p>`acquireUnstableContentProviderClient` 只做一件事：让 AMS 把 provider 准备好并
 * 返回一个客户端引用。要准备好 provider，目标进程就必须先起来、
 * `Application.onCreate` 必须先跑完 —— 这正是我们要的那部分耗时。
 *
 * <p>比 `ContentResolver.call(uri, "warmup", ...)` 更好的地方在于：完全不调用目标
 * provider 的任何方法，因此不存在被自定义 `call()` 实现当成未知请求处理的风险。
 * 拿到引用后立刻关闭，不会把目标进程的 oom_adj 抬上去，也不会留下常驻连接。
 *
 * <p>全程不碰 Activity / 任务栈，所以从桌面图标进应用时仍停留在上次的页面。
 *
 * <p><b>2026-10-17 实测补上的第二条取法</b>：目标进程<b>不在</b>时，不稳定取法返回了 null，
 * 而同一刻组件明明解析得到（`explainProviderFailure` 就是靠这一点判的）。「不稳定」在 AOSP 里
 * 承诺的是「不把目标进程按住」，它到底会不会<b>请求创建</b>进程并没有写死；而微信那次 19ms
 * 是「进程本来就在」的幂等调用，证明不了这一条。所以现在 unstable 拿不到时，会再用
 * <b>稳定取法</b>试一次，并把「是哪一种取到的」如实写进结果 —— 两种都拿不到，才说明挡在
 * 前面的是系统（见 README 的自启动 / 关联启动那一段）。
 *
 * <p><b>当天晚些时候这个 A/B 有答案了</b>（用户打开「自启动 / 关联启动」后回传）：成功的那一次
 * 日志写的是 {@code Provider} —— <b>拿引用走的是首选取法</b>。
 *
 * <ul>
 *   <li>之前那批失败<b>不是取法问题，是 ROM 的门</b>：同一份代码、同一个组件、同一种取法，
 *       中间只有那两个开关变了。失败文案里「不稳定与稳定两种取法都拿不到」是准确的
 *       —— 两种都拿不到 = 系统不让起；</li>
 *   <li>探活还写明<b>预热前进程未运行</b>：于是「unstable 会请求创建进程」这条推断在本机得到
 *       实测确认 —— 门关着时它同样请求、同样返回 null，区别只在 ROM 放不放行；</li>
 *   <li>稳定取法这条兜底在本机还没派上用场，保留它是给别的 ROM 留的保险，
 *       顺序不必改（代价只是首选返回 null 时多一次调用）。</li>
 * </ul>
 *
 * <p>读法边界：「预热成功」只说明<b>拿到了引用</b>，不等于进程真的被拉起来（进程本来就在时也是
 * 成功），后者要看探活的前后对比。2026-10-17 的记录是本项目第一条完整的正面证据链：
 * 「预热前 未运行」→ 一次预热 → 「预热后 进程在运行（PID …）」。
 *
 * <p><b>2026-10-22</b>：探活说进程在运行时，两种取法仍可能拿不到引用（ROM 冻住已存在的进程、
 * 或暂时挡住 provider）。这时失败读成「幂等调用未完成」，不再说「卡在进程起不来」；见
 * {@link #warm(String, String, long, boolean)} 的 {@code processWasRunning}。
 */
public final class ProviderWarmStrategy {

    private final Context mContext;

    public ProviderWarmStrategy(Context context) {
        mContext = context.getApplicationContext();
    }

    public WarmResult warm(String packageName, String authority, long startUptimeMs,
                           boolean processWasRunning) {
        ContentProviderClient client = null;
        boolean stable = false;
        try {
            Uri uri = Uri.parse("content://" + authority);
            client = mContext.getContentResolver().acquireUnstableContentProviderClient(uri);
            if (client == null) {
                stable = true;
                client = mContext.getContentResolver().acquireContentProviderClient(uri);
            }
            long elapsed = SystemClock.uptimeMillis() - startUptimeMs;
            if (client == null) {
                // 系统只说「没拿到」，原因得自己问一次
                return WarmResult.failed(packageName, "Provider", elapsed,
                        ComponentInventory.explainProviderFailure(
                                mContext, packageName, authority, processWasRunning)
                                + "（不稳定与稳定两种取法都拿不到）");
            }
            return WarmResult.warmed(packageName,
                    stable ? "Provider/稳定取法" : "Provider",
                    elapsed,
                    stable ? authority + "（不稳定取法拿不到，稳定取法拿到）" : authority);
        } catch (Throwable t) {
            long elapsed = SystemClock.uptimeMillis() - startUptimeMs;
            return WarmResult.failed(packageName, "Provider", elapsed, brief(t));
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响预热结果
                }
            }
        }
    }

    static String brief(Throwable throwable) {
        String message = throwable.getMessage();
        String name = throwable.getClass().getSimpleName();
        return message == null || message.isEmpty() ? name : name + ": " + message;
    }
}

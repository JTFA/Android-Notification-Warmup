package com.jtfa.notificationfixer.warm;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.jtfa.notificationfixer.core.EventLog;

import java.util.List;

/**
 * 最后一道门：给目标应用的导出广播接收器发一条显式空广播。
 *
 * <p>为什么它排在 provider / service 之后：广播会真的执行对方的 {@code onReceive}，
 * 而前两条路只是让进程起来、不触发业务逻辑。只在没有导出 provider / service 时才用。
 *
 * <p>为什么用<b>有序广播</b>：普通 `sendBroadcast` 发完就返回，连「系统把这条广播处理掉了没有」
 * 都无从观察；有序广播至少把最终结果回调给我们，能看出它是不是被挂住了。
 *
 * <p><b>回执不是证据（2026-10-17 实测确认）</b>：有序广播的最终结果<b>一定</b>会回调给结果接收器 ——
 * 包括对方压根不应答、等满 10 秒超时的情况，也包括<b>没有任何接收器匹配</b>的情况
 *（酷安实测：预检解析到 0 个接收器，回执照样在 <b>2ms</b> 后回来）。
 * 所以「回执到了」与「对方跑过了」之间没有因果关系。回归速度只是一条弱信号，实测三档：
 * <b>0 个接收器 2ms、匹配到 1 个但进程没起 9ms、真的起了进程 313ms</b>。
 * 真正能证明进程被拉起来的是探活 {@code pidof}（见 {@link WarmEvidence}）。
 *
 * <p>投递前会先做一次 {@code queryBroadcastReceivers} 预检并写日志（<b>只写日志、不改行为</b>）：
 * 预检说「解析不到任何接收器」而回执照样回来，就当场证明上面那条。预检同时把目标组件、
 * 对方 intent filter 声明的 action、接收器与应用的原始状态位一起写进「诊断」，便于和
 * `dumpsys`、logcat 对照。
 *
 * <p>广播内容是一个<b>没有 action、没有 extras</b> 的显式 Intent：导出的组件按定义就得
 * 容忍任意调用方发来的东西，绝大多数接收器看到不认识的 action 会立刻返回，
 * 这样副作用最小。
 */
public final class ReceiverWarmStrategy {

    /**
     * 超过这个延迟才到的回执，按「有序广播超时」算，不算证据。
     *
     * <p>系统给有序广播接收器的超时是 10 秒；取 9.5 秒留一点余量，
     * 免得把「对方真的跑了 9.6 秒」误判成超时 —— 那种慢接收器本身就该被当成不可用。
     */
    private static final long TIMEOUT_SUSPECT_MS = 9500L;

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private final Context mContext;

    public ReceiverWarmStrategy(Context context) {
        mContext = context.getApplicationContext();
    }

    public WarmResult warm(String packageName, ComponentName component, long startUptimeMs) {
        if (component == null) {
            return WarmResult.failed(packageName, "Receiver", 0L, "组件为空");
        }
        try {
            Intent intent = new Intent();
            intent.setComponent(component);
            // 前景优先级：投递更快，也不会被排在别人后面
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            // 停止包（FLAG_STOPPED）照收这条广播 —— 0.7.15-M6 / 36 补。
            // 系统给**每一条**广播都隐式加了 FLAG_EXCLUDE_STOPPED_PACKAGES（AMS broadcastIntentLocked），
            // 而这个标记的文档写明：两个都在时 FLAG_INCLUDE_STOPPED_PACKAGES 赢，它就是留给
            // 「框架自己加了排除、但我确实想投给停止包」的场合用的。2024 年 AOSP 还把
            // ApplicationInfo#FLAG_STOPPED 的文档改成「停止包收不到的是**隐式**广播」——
            // 显式指定组件的这一条照收。所以这里补上这个标记：不补的话，停止包上的广播接收器
            // 会被系统悄悄丢掉，而我们自己的判据（探活）看起来就像「这条路不管用」。
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);

            // 投递前预检：这条广播到底解析到谁。只写日志，不改投递行为。
            final int resolved = logPreflight(intent, packageName, component);

            final long sentAt = SystemClock.uptimeMillis();
            BroadcastReceiver receipt = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent received) {
                    long elapsed = SystemClock.uptimeMillis() - sentAt;
                    boolean timedOut = elapsed >= TIMEOUT_SUSPECT_MS;
                    EventLog.get().log(EventLog.CAT_WARM,
                            "广播回执 " + packageName + "  投递后 " + elapsed + "ms"
                                    + "（预检解析到 " + resolved + " 个接收器）",
                            (timedOut
                                    ? "接近有序广播的 10 秒超时：接收器被选中但没能跑完，进程多半没起来"
                                    : "回执一定会回来（实测：解析到 0 个接收器时 2ms 就回了），"
                                            + "所以它证明不了对方跑过；但它快得离谱时通常意味着对方没跑 —— "
                                            + "实测三档：0 个接收器 2ms、匹配到 1 个但进程没起 9ms、"
                                            + "真的起了进程 313ms。判据仍是探活那两行")
                                    + "：" + component.flattenToShortString());
                }
            };
            mContext.sendOrderedBroadcast(intent, null, receipt, sMain,
                    0, null, null);
            long elapsed = SystemClock.uptimeMillis() - startUptimeMs;
            return WarmResult.delivered(packageName, "Receiver", elapsed,
                    "已投递有序广播（投递成功不等于进程起来了，看探活那两行）");
        } catch (Throwable t) {
            long elapsed = SystemClock.uptimeMillis() - startUptimeMs;
            return WarmResult.failed(packageName, "Receiver", elapsed,
                    ProviderWarmStrategy.brief(t));
        }
    }

    /**
     * 投递前问一次包管理器：我们这条广播解析到谁。只写日志，不影响投递。
     *
     * <p>用的就是系统投递清单接收器时走的那套匹配规则（显式 intent 也会被对方的
     * intent filter 判一遍，Android 13 起收紧、Android 16 起可用
     * {@code android:intentMatchingFlags="enforceIntentFilter"} 拒收没有 action 的 intent）。
     * 所以「解析到 0 个」= 系统不会投递这条广播，投出去也是空转。
     *
     * @return 按真实匹配规则解析到的接收器个数（不含被停用的组件）
     */
    private int logPreflight(Intent intent, String packageName, ComponentName component) {
        int resolved = 0;
        String detail;
        try {
            PackageManager pm = mContext.getPackageManager();
            List<ResolveInfo> matched = pm.queryBroadcastReceivers(intent,
                    PackageManager.GET_RESOLVED_FILTER);
            resolved = matched == null ? 0 : matched.size();
            StringBuilder text = new StringBuilder();
            text.append("目标组件 ").append(component.flattenToShortString());
            text.append("\n解析到 ").append(resolved).append(" 个接收器");
            if (resolved > 0) {
                text.append("：").append(describe(matched));
            } else {
                // 0 个要分清「被 intent filter 拦下」还是「组件被停用」—— 两种成因的修法完全不同
                List<ResolveInfo> includingDisabled = pm.queryBroadcastReceivers(intent,
                        PackageManager.GET_RESOLVED_FILTER
                                | PackageManager.MATCH_DISABLED_COMPONENTS);
                int disabled = includingDisabled == null ? 0 : includingDisabled.size();
                text.append(disabled > 0
                        ? "（连被停用的组件一起算有 " + disabled + " 个：是组件被停用，不是 filter 拦下）"
                        : "（连被停用的组件一起算也是 0 个：系统不会投递这条广播 —— "
                                + "多半是对方声明了 intentMatchingFlags=enforceIntentFilter）");
            }
            text.append("\n接收器 ").append(receiverBits(pm, component));
            text.append("\n应用 ").append(stateBits(pm, packageName));
            detail = text.toString();
        } catch (Throwable t) {
            detail = "预检失败（不影响投递）：" + ProviderWarmStrategy.brief(t);
        }
        EventLog.get().log(EventLog.CAT_DIAG, "投递前预检 " + packageName, detail);
        return resolved;
    }

    /** 解析结果里每个接收器，以及它的 filter 声明了哪些 action（只列前几个）。 */
    private static String describe(List<ResolveInfo> matched) {
        StringBuilder text = new StringBuilder();
        for (ResolveInfo info : matched) {
            if (info.activityInfo == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append('；');
            }
            text.append(info.activityInfo.packageName).append('/').append(info.activityInfo.name);
            if (info.filter == null) {
                text.append("（没有 intent filter，只接显式广播）");
                continue;
            }
            int count = info.filter.countActions();
            text.append("（filter 声明 ").append(count).append(" 个 action");
            for (int i = 0; i < count && i < 3; i++) {
                text.append(i == 0 ? "：" : "、").append(info.filter.getAction(i));
            }
            if (count > 3) {
                text.append(" …");
            }
            text.append("）");
        }
        return text.toString();
    }

    /** 接收器本身能不能被外人调用（含运行时组件级覆盖 —— 这一项清单里看不出来）。 */
    private static String receiverBits(PackageManager pm, ComponentName component) {
        try {
            ActivityInfo info = pm.getReceiverInfo(component,
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            return "exported=" + info.exported + " enabled=" + info.enabled
                    + " 运行时覆盖=" + enabledSettingName(pm, component)
                    + " permission=" + (info.permission == null ? "无" : info.permission)
                    + " process=" + (info.processName == null ? "(默认)" : info.processName);
        } catch (Throwable t) {
            return "读不到：" + ProviderWarmStrategy.brief(t);
        }
    }

    /**
     * 运行时的组件级启用覆盖值（清单里那份 `enabled` 之外的第二个来源）。
     *
     * <p>0 = 没被覆盖（听清单的）、1 = 被显式启用、2/3/4 = 被停用。系统投递只看这个有效值，
     * 所以「清单 enabled=true、运行时覆盖=2」的接收器一样收不到广播 —— 2026-10-17 酷安实测。
     */
    private static String enabledSettingName(PackageManager pm, ComponentName component) {
        try {
            switch (pm.getComponentEnabledSetting(component)) {
                case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                    return "启用(1)";
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                    return "停用(2)";
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                    return "用户停用(3)";
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                    return "停用至使用(4)";
                default:
                    return "默认(0)";
            }
        } catch (Throwable t) {
            return "读不到";
        }
    }

    /** 目标的原始状态位，与 `dumpsys package` 的 `User 0:` 那一行同一批事实。 */
    private static String stateBits(PackageManager pm, String packageName) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return "stopped=" + ((info.flags & ApplicationInfo.FLAG_STOPPED) != 0)
                    + " enabled=" + info.enabled
                    + " suspended=" + ((info.flags & ApplicationInfo.FLAG_SUSPENDED) != 0);
        } catch (Throwable t) {
            return "读不到：" + ProviderWarmStrategy.brief(t);
        }
    }
}

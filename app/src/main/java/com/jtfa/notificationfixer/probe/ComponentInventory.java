package com.jtfa.notificationfixer.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.util.LruCache;

import com.jtfa.notificationfixer.core.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 枚举并缓存「目标应用能不能预热、用哪个组件预热」。
 *
 * <p>判定顺序是刻意的：<b>先找组件，再用应用状态解释为什么不能用</b>。
 * 反过来的坏处是给错建议 —— 一个既被强制停止、又根本没有导出组件的应用，
 * 若先判状态就会说「打开一次即可」，而用户打开一次返回来看还是不可预热。
 * 所以「打开一次即可」这类承诺只在<b>确实存在可借组件</b>时才给。
 *
 * <p>两个关键筛选条件，缺一个就会预热到没用的地方：
 *
 * <ul>
 *   <li>组件必须在<b>主进程</b>里。应用界面跑在主进程，只有把主进程拉起来才有意义；</li>
 *   <li>组件必须导出且不要求权限。非导出组件的调用会直接抛 SecurityException，
 *       进程也不会起来。</li>
 * </ul>
 *
 * <p>查询一律带上「连被停用组件一起返回」的标记：不是为了提高预热成功率，而是为了让
 * <b>诊断日志里的数量是真的</b> —— 应用被停用 / 被冻结时，不带这个标记会数出
 * {@code provider=0 service=0}，看起来像「这应用没有 provider」，实际是被系统过滤了。
 */
public final class ComponentInventory {

    private static final int QUERY_FLAGS =
            PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS
                    | PackageManager.GET_RECEIVERS;

    /** 连被停用 / 禁用直到使用的组件一起返回，只用于诊断与强制尝试。 */
    private static final int QUERY_FLAGS_ALL = QUERY_FLAGS
            | PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;

    /** 诊断信息里最多列几个被排除的候选组件。 */
    private static final int MAX_REJECTIONS = 4;

    private static final LruCache<String, WarmTarget> sCache = new LruCache<>(256);

    private ComponentInventory() {
    }

    /**
     * 正常探测：按当前真实状态给结论。
     *
     * <p><b>只有「可用」的结论会缓存</b>（2026-10-18 改）：不可预热的原因（被强停 / 被停用 /
     * 被冻结）会随着用户打开一次应用、ROM 自己解冻而消失，而**自动触发这条路没有「回到名单页
     * 就重探」的机会** —— 一条旧否定会一直挡在那里，现象就是「刚才不行、现在突然行了」
     * 「点了试一次就好了」。重扫一遍只是一次包查询（Activity 路重一点，但那种应用本来也没东西
     * 可预热），换来的是每条日志都代表当时那一刻。
     */
    public static WarmTarget resolve(Context context, String packageName) {
        WarmTarget cached = sCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        WarmTarget target = scan(context, packageName, false);
        if (target.isAvailable()) {
            sCache.put(packageName, target);
        } else {
            sCache.remove(packageName);
        }
        return target;
    }

    /**
     * 强制探测：忽略「应用被强制停止 / 被停用 / 被挂起」，只要存在导出的主进程组件就返回，
     * 让调用方真的去试一次。
     *
     * <p>用途是回答一个只能靠实测回答的问题：系统拒绝的到底是组件的可用性，
     * 还是那几个状态位？结论不缓存，免得一次偶然的失败变成永久判定。
     */
    public static WarmTarget forceResolve(Context context, String packageName) {
        return scan(context, packageName, true);
    }

    public static void invalidate(String packageName) {
        sCache.remove(packageName);
    }

    /**
     * 清空全部缓存。
     *
     * <p>「不可预热」的几个原因（强制停止、禁用直到使用）会随着用户打开一次应用而消失，
     * 所以名单页每次重新可见时都重探一遍，结论不会僵在旧状态上。
     */
    public static void clear() {
        sCache.evictAll();
    }

    /** 把一句状态提醒接成一句话（Activity 路可能同时命中两条状态位）。 */
    private static void appendNote(StringBuilder note, String text) {
        if (note.length() > 0) {
            note.append('；');
        }
        note.append(text);
    }

    private static WarmTarget scan(Context context, String packageName, boolean force) {
        PackageManager packageManager = context.getPackageManager();
        PackageInfo info;
        try {
            info = packageManager.getPackageInfo(
                    packageName, PackageManager.PackageInfoFlags.of(QUERY_FLAGS_ALL));
        } catch (Exception e) {
            return WarmTarget.unavailable("读取包信息失败（可能未安装）",
                    "getPackageInfo 失败：" + e);
        }

        ApplicationInfo application = info.applicationInfo;
        if (application == null) {
            return WarmTarget.unavailable("缺少应用信息", "PackageInfo.applicationInfo 为 null");
        }

        String mainProcess = application.processName != null && !application.processName.isEmpty()
                ? application.processName : packageName;
        String appState = describeAppState(packageManager, packageName, application);

        // 唤醒方式是逐应用选的（0.7.5）：这个应用选了「拉起 Activity」就只走第 ④ 条路，
        // 另外三条一概不试；没选（默认）就只走另外三条，行为与 0.7.4 之前一字不差。
        boolean activityMode = Prefs.isActivityWakeMode(packageName);
        ActivityInventory.Result activityResult = activityMode
                ? ActivityInventory.resolve(context, packageName) : null;

        // 先找组件：结论与建议都以它为准
        List<String> rejections = new ArrayList<>();
        Candidate candidate = activityMode ? null
                : findCandidate(packageManager, info, packageName, mainProcess, rejections);

        // 第 ④ 条路（借一个「启动时不显示界面」的 Activity）：只在用户给这个应用选了
        // 「拉起 Activity」时才成立，并且此时它是唯一的路。
        WarmTarget activityTarget = activityResult == null
                ? null : ActivityInventory.target(activityResult);
        if (activityMode) {
            addRejection(rejections, "拉起 Activity", activityTarget == null
                    ? ActivityInventory.unavailableReason(activityResult)
                    : "可用（" + activityTarget.qualifier + "）");
            addRejection(rejections, "唤醒后台", "按你的选择跳过（这个应用选的是「拉起 Activity」）");
        } else {
            addRejection(rejections, "拉起 Activity", "这个应用没选它（默认走「唤醒后台」）");
        }

        String rejected = rejections.isEmpty() ? " 没有导出的组件" : " 被排除的候选：" + join(rejections);

        // 导出的广播接收器本轮不用，只记录：给「没有组件」的应用留一条备选路（发一条空
        // 显式广播同样能拉起进程，代价是会执行对方 onReceive 的代码，需要单独权衡）
        List<String> receivers = exportedReceivers(info, packageName, mainProcess);
        String diag = "pkg=" + packageName + " " + appState + " 主进程=" + mainProcess
                + " provider=" + count(info.providers) + " service=" + count(info.services)
                + " receiver=" + count(info.receivers) + "（导出 " + receivers.size() + "）"
                + (receivers.isEmpty() ? "" : " 导出的接收器：" + join(receivers));

        // 应用状态位的解释（「打开一次即可」那一类）只在**确实有东西可借**时才给：
        // 一个连导出 Activity 都没有的应用说「打开一次即可」，用户打开一次回来还是不可预热。
        // 见本类开头关于「先找组件，再用状态解释」的那段。
        boolean borrowable = activityResult != null
                ? !activityResult.candidates.isEmpty() : candidate != null;
        String targetHint = activityResult != null
                ? " " + activityResult.summaryLine()
                : candidate != null ? " 组件：" + candidate.describe() : rejected;

        // 状态位能不能用来拒答，取决于这次的调用是**显式**还是**隐式**，与走哪条路无关：
        // Activity 是显式启动，桌面点图标走的就是这条；「唤醒后台」借的 provider author / 组件 / 广播
        // 同样是显式的（真的按名字点它，不靠 intent filter 匹配）。而 FLAG_STOPPED 管的是**隐式**拉起
        // —— 系统给所有广播加了 FLAG_EXCLUDE_STOPPED_PACKAGES、隐式 intent 解析不收停止包。
        // 2026-10-18 用户实测证实了这一点：名单页写着「已被系统强制停止」的应用，「试一次」与真实
        // 触发都唤得醒（0.7.15-M6 / 36 起这一条不再拒答，只写提醒，见 README）。
        // 拒答的只剩三类：结构性不可能（未安装到此用户）、**试一下本身就会弹出系统界面**的
        // （已被系统挂起 —— 系统会显示自己的「应用已暂停」页，那正是本项目最不想要的打扰），
        // 以及**停用 / 冻结**（enabled=false：实测 bindService 直接返回 false，且与「打开过一次」无关）。
        StringBuilder stateNote = new StringBuilder();

        if (!force && borrowable) {
            if ((application.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                return WarmTarget.unavailable("未安装到此用户", diag + targetHint);
            }
            if ((application.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) {
                return WarmTarget.unavailable(
                        "已被系统挂起，需在系统设置里解除（试一下会弹出系统的「应用已暂停」页）",
                        diag + targetHint);
            }
            if (activityMode) {
                if (!application.enabled) {
                    appendNote(stateNote, "应用被系统停用（"
                            + disabledReason(packageManager, packageName)
                            + "）：Activity 是显式启动，桌面点图标也是这么进去的，通常仍能拉起，实测为准");
                }
                if ((application.flags & ApplicationInfo.FLAG_STOPPED) != 0) {
                    appendNote(stateNote, "应用处于「已被强停」状态：那条限制挡的是广播与隐式 intent，"
                            + "Activity 是显式启动，通常仍能拉起（这一次启动本身就会清掉这个位），实测为准");
                }
            } else {
                // ③ 强停（FLAG_STOPPED）**不挡这三条**（0.7.15-M6 / 36 改）：
                //   · 取 provider 引用：AOSP `ContentProviderHelper.getContentProviderImpl` 里写着
                //     「Content provider is now in use, its package can't be stopped」，并且真的调用
                //     `setPackageStoppedState(pkg, false, userId)` —— 停止包照拉起，还顺手清掉这个位；
                //   · 绑定 service：`ApplicationInfo#FLAG_STOPPED` 的文档只「建议不要」绑定停止包，
                //     系统这条路上没有停止位这道检查（挡后台起服务的是「后台启动策略」，与这个位无关）；
                //   · 显式广播：2024 年 AOSP 把文档改成「停止包收不到的是**隐式**广播」，显式广播照收。
                // 所以这里只把状态写成提醒，成没成由探活那两行说了算。此前一律写「已被系统强制停止，
                // 打开一次即可」是拿一条没验证过的假设替真机下结论 —— 与 0.7.6-M6 / 27 拆掉 Activity
                // 路那道错闸门是同一类错误（用户实测：这么写的应用，「试一次」和真实触发都唤得醒）。
                if ((application.flags & ApplicationInfo.FLAG_STOPPED) != 0) {
                    appendNote(stateNote, "应用处于「已被强停」状态：这个位管的是隐式拉起"
                            + "（隐式广播 / 隐式 intent），这三条用的都是显式组件，通常照旧能拉起，实测为准");
                }
                if (!application.enabled) {
                    // 这一条留着拒答：停用 / 冻结是实测挡得住绑定的（bindService 直接返回 false）。
                    // 0.7.48-M6 / 69：用户实测「系统停用」与「被强停 + 系统停用」两种组合都能靠
                    // 手动打开一次恢复 —— ROM 在启动应用时会把 enabledSetting=2/4 一并解冻。
                    // 所以两种组合不再分开措辞，只给同一条恢复建议；用户停用（state=3）例外，
                    // 打开一次不会恢复，仍要指明去系统设置里启用。
                    return WarmTarget.unavailable(
                            disabledUnavailableReason(packageManager, packageName),
                            diag + " 有东西可借但应用未启用：" + targetHint);
                }
            }
        }

        if (activityTarget != null) {
            if (stateNote.length() == 0) {
                return activityTarget;
            }
            String note = activityTarget.note == null || activityTarget.note.isEmpty()
                    ? "注意：" + stateNote
                    : activityTarget.note + "；注意：" + stateNote;
            return WarmTarget.activity(activityTarget.component, activityTarget.qualifier, note);
        }
        if (activityResult != null) {
            // 选了「拉起 Activity」就只走这条路：挑不出来也不回头试另外三条（用户的选择是排他的），
            // 而是把「为什么挑不出来」原样交出去，让用户自己再挑一个。
            return WarmTarget.unavailable(ActivityInventory.unavailableReason(activityResult),
                    diag + targetHint + rejected);
        }
        if (candidate == null) {
            // 这里已经没有接收器可选了（接收器也是候选之一），只说明结构性事实。
            // 0.7.48-M6 / 69：不再写「免 root 拉不起来」这种把第四条路也说死的结论，
            // 而是告诉用户还能改走「拉起 Activity」逐个试。
            return WarmTarget.unavailable(
                    "该应用没有可导出的组件，仅可使用拉起activity方式预热",
                    diag + rejected);
        }

        // 组件级停用是结构性的：系统不会把广播 / 绑定 / provider 引用交给停用的组件，
        // 所以「强制尝试」也绕不过它，必须单独判。强制尝试原本要绕的是**应用级**的停止位与
        // 停用状态（用户打开一次、或 ROM 放行就能变的那种），而组件级停用只有 root 能改；
        // 再投一次只会得到一次注定无效的投递，日志还会写「已投递」，反而误导。
        // 2026-10-17 酷安实测：唯一可借的接收器（com.tencent.android.mipush.XMPushMessageReceiver）
        // 处于停用状态 → 预检解析到 0 个接收器，探活前后都是「未运行」。
        if (!candidate.enabled) {
            return WarmTarget.unavailable(
                    force ? "导出的组件本身处于停用状态，强制尝试也无效（系统不会投递给停用的组件）"
                            : "导出的组件本身处于停用状态",
                    diag + " 候选：" + candidate.describe() + rejected);
        }

        if (force) {
            return candidate.asTarget(
                    "强制尝试（忽略应用级的停止位与停用状态；组件级停用绕不过）：" + appState);
        }
        // 走「唤醒后台」这三条时，状态位一律只写提醒（见上面那段），所以这里要把提醒带上
        StringBuilder note = new StringBuilder();
        if (stateNote.length() > 0) {
            note.append(stateNote);
        }
        if (!application.enabled) {
            appendNote(note, "应用 enabled=false，本次预热可能被系统拒绝");
        }
        return candidate.asTarget(note.length() == 0 ? null : note.toString());
    }

    // ---------------------------------------------------------------- 组件探测

    /** 一个通过全部筛选的候选组件。 */
    private static final class Candidate {

        final WarmTarget.Kind kind;
        final String authority;
        final ComponentName component;
        final boolean enabled;

        Candidate(WarmTarget.Kind kind, String authority, ComponentName component, boolean enabled) {
            this.kind = kind;
            this.authority = authority;
            this.component = component;
            this.enabled = enabled;
        }

        WarmTarget asTarget(String note) {
            switch (kind) {
                case PROVIDER:
                    return WarmTarget.provider(authority, note);
                case RECEIVER:
                    return WarmTarget.receiver(component, note);
                default:
                    return WarmTarget.service(component, note);
            }
        }

        String describe() {
            String name = component == null ? authority : component.flattenToShortString();
            return kind == WarmTarget.Kind.PROVIDER ? "ContentProvider " + name
                    : kind == WarmTarget.Kind.RECEIVER ? "广播接收器 " + name
                    : "Service " + name;
        }
    }

    /**
     * 找出第一个可用的组件，Provider 优先。
     *
     * <p>「组件已停用」不会直接判死：先记住它，只在没有任何启用组件时才用它兜底，
     * 免得一次停用状态让整个应用看起来没有组件。注意判据是
     * {@link #enabled(PackageManager, boolean, ComponentName) 有效启用状态}，
     * 不是清单里的 `enabled` —— 后者漏掉运行时用 `setComponentEnabledSetting` 关掉的组件。
     */
    private static Candidate findCandidate(PackageManager pm, PackageInfo info, String packageName,
                                           String mainProcess, List<String> rejections) {
        Candidate disabled = null;

        if (info.providers != null) {
            for (ProviderInfo provider : info.providers) {
                String rejection = rejectProvider(provider, mainProcess);
                if (rejection == null) {
                    String authority = provider.authority.split(";")[0].trim();
                    if (!authority.isEmpty()) {
                        boolean on = enabled(pm, provider.enabled,
                                new ComponentName(provider.packageName, provider.name));
                        Candidate found = new Candidate(WarmTarget.Kind.PROVIDER,
                                authority, null, on);
                        if (on) {
                            return found;
                        }
                        if (disabled == null) {
                            disabled = found;
                        }
                        continue;
                    }
                    rejection = "没有 authority";
                }
                addRejection(rejections, "provider " + shortName(provider, packageName), rejection);
            }
        }

        if (info.services != null) {
            for (ServiceInfo service : info.services) {
                String rejection = rejectService(service, mainProcess);
                if (rejection == null) {
                    ComponentName component = new ComponentName(service.packageName, service.name);
                    boolean on = enabled(pm, service.enabled, component);
                    Candidate found = new Candidate(WarmTarget.Kind.SERVICE, null, component, on);
                    if (on) {
                        return found;
                    }
                    if (disabled == null) {
                        disabled = found;
                    }
                    continue;
                }
                addRejection(rejections, "service " + shortName(service, packageName), rejection);
            }
        }

        // 广播接收器排最后：它是唯一会真的执行对方业务代码的一条路
        if (info.receivers != null) {
            for (ActivityInfo receiver : info.receivers) {
                if (receiver == null || receiver.name == null) {
                    continue;
                }
                if (!receiver.exported || receiver.permission != null) {
                    continue;
                }
                if (rejectByProcess(receiver, mainProcess) != null) {
                    continue;
                }
                ComponentName component = new ComponentName(receiver.packageName, receiver.name);
                boolean on = enabled(pm, receiver.enabled, component);
                Candidate found = new Candidate(WarmTarget.Kind.RECEIVER, null, component, on);
                if (on) {
                    return found;
                }
                if (disabled == null) {
                    disabled = found;
                }
            }
        }

        return disabled;
    }

    /**
     * 组件的<b>有效</b>启用状态：清单里的 {@code android:enabled} 与运行时的组件级覆盖合起来判。
     *
     * <p>只看 {@code ProviderInfo.enabled} / {@code ServiceInfo.enabled} / {@code ActivityInfo.enabled}
     * 会漏掉最常见的一种停用：应用（或 ROM）在运行时用 {@code setComponentEnabledSetting()} 关掉
     * 某个组件 —— 清单里仍然是 {@code enabled=true}，`getReceiverInfo` 也照样报 true，
     * 但系统已经不把广播 / 绑定 / provider 引用交给它了。
     *
     * <p>2026-10-17 酷安实测（`com.coolapk.market`，正是这个坑）：预检
     * `queryBroadcastReceivers` 解析到 <b>0</b> 个接收器（连被停用的组件一起算才 1 个），
     * 而同一时刻 `getReceiverInfo` 报 {@code enabled=true} —— 于是 0.7.2 新加的那道
     * 「组件级停用」判定根本没触发，广播照发、注定空转。现在用
     * {@code getComponentEnabledSetting()} 读运行时覆盖值补上这一面。
     *
     * <p>读不到时按清单值处理：探测失败不该被误判成「已停用」（那会让应用白丢一条路）。
     */
    private static boolean enabled(PackageManager pm, boolean manifestEnabled, ComponentName component) {
        if (!manifestEnabled) {
            return false;
        }
        try {
            switch (pm.getComponentEnabledSetting(component)) {
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                    return false;
                default:
                    return true;
            }
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 主进程里导出、不要求权限的广播接收器。
     *
     * <p>本轮<b>只记录、不使用</b>：给一条显式广播同样能让目标进程起来，但代价是会执行对方的
     * {@code onReceive}，而 provider / service 那两条路只是让进程起来、不触发业务逻辑。
     * 先把名单摸清楚，再决定值不值得换这条路。
     */
    private static List<String> exportedReceivers(PackageInfo info, String packageName,
                                                  String mainProcess) {
        List<String> found = new ArrayList<>();
        if (info.receivers == null) {
            return found;
        }
        for (ActivityInfo receiver : info.receivers) {
            if (receiver == null || receiver.name == null
                    || !receiver.exported || receiver.permission != null) {
                continue;
            }
            if (rejectByProcess(receiver, mainProcess) != null) {
                continue;
            }
            if (found.size() < MAX_REJECTIONS) {
                found.add(shortName(receiver, packageName));
            }
        }
        return found;
    }

    private static String rejectProvider(ProviderInfo provider, String mainProcess) {
        if (provider == null) {
            return "空条目";
        }
        if (provider.authority == null || provider.authority.isEmpty()) {
            return "没有 authority";
        }
        if (!provider.exported) {
            return "非导出";
        }
        if (provider.readPermission != null || provider.writePermission != null) {
            return "要求权限";
        }
        return rejectByProcess(provider, mainProcess);
    }

    private static String rejectService(ServiceInfo service, String mainProcess) {
        if (service == null) {
            return "空条目";
        }
        if (service.name == null) {
            return "没有类名";
        }
        if (!service.exported) {
            return "非导出";
        }
        if (service.permission != null) {
            return "要求权限";
        }
        return rejectByProcess(service, mainProcess);
    }

    /**
     * 组件必须跑在应用主进程里。
     *
     * <p>组件的 {@code processName} 为 null 表示没写 {@code android:process}，
     * 也就是跟着应用默认进程走，即主进程，不算问题。
     */
    private static String rejectByProcess(ComponentInfo component, String mainProcess) {
        String process = component.processName;
        if (process == null || process.isEmpty()) {
            return null;
        }
        if (process.equals(mainProcess)) {
            return null;
        }
        return "进程 " + process + "（不是主进程）";
    }

    private static void addRejection(List<String> rejections, String component, String rejection) {
        if (rejection != null && rejections.size() < MAX_REJECTIONS) {
            rejections.add(component + "：" + rejection);
        }
    }

    // ---------------------------------------------------------------- 应用状态

    /**
     * 应用状态的原始读数（{@code enabled} / {@code flags} 解码 / {@code enabledSetting}），只读。
     *
     * <p>{@link AppStateCheck} 与诊断日志共用这一份，避免两处说法不一致；结果弹窗里那个
     * 「看它到底是什么状态」入口已在 0.7.51-M6 / 72 下线。
     */
    public static String appStateDetail(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            ApplicationInfo application = packageManager.getApplicationInfo(
                    packageName, PackageManager.ApplicationInfoFlags.of(0));
            return describeAppState(packageManager, packageName, application);
        } catch (Exception e) {
            return "读不到这个应用（未安装，或对本应用不可见）：" + e.getClass().getSimpleName();
        }
    }

    /**
     * 应用级启用状态的原值（{@code PackageManager.COMPONENT_ENABLED_STATE_*}）。
     *
     * <p>只看这一个数字就能分清「系统停用（2/4）」和「系统设置里那个用户停用（3）」——
     * 后者的应用才在系统设置里有「启用」按钮。
     */
    public static int appEnabledSetting(Context context, String packageName) {
        return enabledSetting(context.getPackageManager(), packageName);
    }

    /**
     * 应用整体未启用时的说法。
     *
     * <p>刻意把三种「停用」分开说，因为它们不是一回事：
     *
     * <ul>
     *   <li>{@code enabledSetting=3}（用户停用）：系统设置里那个「停用 / 启用」按钮写的，</li>
     *   <li>{@code enabledSetting=2}（系统停用）：系统 / ROM / 安装器写的 ——
     *       系统设置里通常<b>没有</b>启用按钮，而应用照样可能被 ROM 自己拉起（本机实测：MIUI 冻结的
     *       应用点桌面图标就能打开）。所以「没有启用按钮」不是「没被停用」的证据；</li>
     *   <li>{@code enabledSetting=4}（禁用直到使用）：从没打开过的预装应用，打开一次即可。</li>
     * </ul>
     *
     * <p>这些值都只由显式调用写入：进程被系统回收、应用「没活跃」都不会写它们。
     * 至于是谁写的，系统不记录，所以这里只说事实、不猜。
     */
    private static String disabledReason(PackageManager packageManager, String packageName) {
        switch (enabledSetting(packageManager, packageName)) {
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return "系统停用（禁用直到使用，打开一次即可）";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                return "系统停用（冻结，enabledSetting=2）";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                return "用户停用（enabledSetting=3）";
            default:
                return "应用未启用（enabledSetting=" + enabledSetting(packageManager, packageName) + "）";
        }
    }

    /**
     * 名单行上「应用整体未启用」时给用户的恢复建议。
     *
     * <p>0.7.48-M6 / 69 起，{@code enabledSetting=2}（系统停用）、{@code 4}（禁用直到使用）
     * 以及它们与 {@code FLAG_STOPPED} 的组合都写同一句：用户在本机实测，手动打开一次应用，
     * ROM 会把停用位和强停位一起清掉。文案是「通常」，因为最终仍取决于 ROM，不能承诺。
     *
     * <p>{@code enabledSetting=3}（用户停用）是例外：打开一次不会恢复，必须去系统设置里
     * 点「启用」，所以单独给一句，避免把用户停用误写成 ROM 会自动解冻。
     */
    private static String disabledUnavailableReason(PackageManager packageManager, String packageName) {
        return enabledSetting(packageManager, packageName)
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                ? "应用已被用户停用，请在系统设置中启用"
                : "应用已被系统停用，通常手动打开一次后可恢复";
    }

    private static String describeAppState(PackageManager packageManager,
                                          String packageName,
                                          ApplicationInfo application) {
        StringBuilder text = new StringBuilder();
        text.append("enabled=").append(application.enabled);
        text.append(" flags=0x").append(Integer.toHexString(application.flags));
        if ((application.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            text.append("[系统应用]");
        }
        if ((application.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
            text.append("[预装已更新]");
        }
        if ((application.flags & ApplicationInfo.FLAG_STOPPED) != 0) {
            text.append("[强制停止]");
        }
        if ((application.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) {
            text.append("[挂起]");
        }
        if ((application.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
            text.append("[未安装]");
        }
        int setting = enabledSetting(packageManager, packageName);
        text.append(" enabledSetting=").append(setting)
                .append('(').append(enabledSettingName(setting)).append(')');
        return text.toString();
    }

    private static int enabledSetting(PackageManager packageManager, String packageName) {
        try {
            return packageManager.getApplicationEnabledSetting(packageName);
        } catch (Exception e) {
            return -1;
        }
    }

    /** {@code enabledSetting} 原值的中文说法，日志与界面共用（{@link StateWatch} 也用它）。 */
    public static String enabledSettingName(int setting) {
        switch (setting) {
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                return "默认";
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                return "启用";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                return "停用";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                return "用户停用";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return "禁用直到使用";
            default:
                return "未知";
        }
    }

    // ---------------------------------------------------------------- 失败原因

    /**
     * 预热失败时补一句「系统为什么拒绝」。
     *
     * <p>取不到 provider 引用、`bindService` 返回 false 时，系统只给一个 null / false，
     * 没有原因。这里用<b>本应用自己的视角</b>再解析一次同一个组件：
     *
     * <ul>
     *   <li>解析得到 → 问题出在进程启动或权限，组件本身没问题；</li>
     *   <li>只有带上「连停用组件一起返回」才解析得到 → 组件处于停用状态；</li>
     *   <li>怎么都解析不到 → 本应用看不到它（包可见性）或它根本没生效。</li>
     * </ul>
     */
    public static String explainProviderFailure(Context context, String packageName,
                                               String authority) {
        return explainProviderFailure(context, packageName, authority, false);
    }

    /**
     * 见 {@link #explainProviderFailure(Context, String, String)}。
     *
     * @param processWasRunning 预热前探活是否明确说目标主进程已经在运行。为 {@code true}
     *                          时不能再把「组件可解析但拿不到引用」解释成「进程起不来」——
     *                          进程已经在了，挡住的可能是 provider 本身或 ROM 对已存在进程的冻结。
     */
    public static String explainProviderFailure(Context context, String packageName,
                                               String authority, boolean processWasRunning) {
        PackageManager packageManager = context.getPackageManager();
        String appState = appStateBrief(packageManager, packageName);
        if (appState != null) {
            return appState;
        }
        if (authority == null) {
            return "没有 authority";
        }
        if (resolvesProvider(packageManager, authority, 0)) {
            if (processWasRunning) {
                return "取不到 provider 引用（预热前进程已在运行，所以不是进程没起来；"
                        + "这次幂等调用没完成，可能是 provider 被系统 / ROM 暂时挡住，"
                        + "或进程被冻结）";
            }
            return "取不到 provider 引用（组件可解析，说明卡在「进程起不来」这一步；"
                    + "应用状态干净时，先去查它的厂商「自启动 / 关联启动」门）";
        }
        if (resolvesProvider(packageManager, authority, QUERY_FLAGS_ALL)) {
            return "该 provider 处于停用状态";
        }
        return "本应用解析不到这个 provider（包可见性，或它没生效）";
    }

    /** 见 {@link #explainProviderFailure}，这里是 Service 版本。 */
    public static String explainServiceFailure(Context context, String packageName,
                                               ComponentName component) {
        return explainServiceFailure(context, packageName, component, false);
    }

    /** 见 {@link #explainProviderFailure(Context, String, String, boolean)}，这里是 Service 版本。 */
    public static String explainServiceFailure(Context context, String packageName,
                                               ComponentName component,
                                               boolean processWasRunning) {
        PackageManager packageManager = context.getPackageManager();
        String appState = appStateBrief(packageManager, packageName);
        if (appState != null) {
            return appState;
        }
        if (component == null) {
            return "组件为空";
        }
        if (resolvesComponent(packageManager, component, 0)) {
            if (processWasRunning) {
                return "系统拒绝绑定（预热前进程已在运行，所以不是进程没起来；"
                        + "这次幂等调用没完成，可能是组件被系统 / ROM 暂时挡住，"
                        + "或进程被冻结）";
            }
            return "系统拒绝绑定（组件可解析，说明卡在「进程起不来」这一步；"
                    + "应用状态干净时，先去查它的厂商「自启动 / 关联启动」门）";
        }
        if (resolvesComponent(packageManager, component, QUERY_FLAGS_ALL)) {
            return "目标组件处于停用状态";
        }
        return "本应用解析不到这个组件（包可见性，或它没生效）";
    }

    /**
     * 结果弹窗会把这两条换成短句，但日志仍按这里的原文输出。
     *
     * <p>界面映射在 {@code ui/AppListFragment#firstParagraphForUi}，所以不要为了让界面变短
     * 而改这里的字；要改日志原文时，两边一起看。
     */
    public static final String LOG_DETAIL_STOPPED =
            "目标应用处于「已被强停」状态（FLAG_STOPPED）：这个位管的是隐式拉起"
                    + "（隐式广播 / 隐式 intent），显式的取 provider 引用 / 绑定 / 广播通常照旧能拉起"
                    + " —— 若这次确实没起来，先去查它的厂商「自启动 / 关联启动」门";

    /** 见 {@link #LOG_DETAIL_STOPPED}。 */
    public static final String LOG_DETAIL_STOPPED_AND_DISABLED =
            "目标应用被强停（FLAG_STOPPED），并且已被系统停用："
                    + "手动打开一次通常两样都会清掉";

    private static String appStateBrief(PackageManager packageManager, String packageName) {
        ApplicationInfo application;
        try {
            application = packageManager.getApplicationInfo(
                    packageName, PackageManager.ApplicationInfoFlags.of(0));
        } catch (Exception e) {
            return "读不到目标应用（已卸载，或对本应用不可见）";
        }
        if ((application.flags & ApplicationInfo.FLAG_STOPPED) != 0) {
            if (application.enabled) {
                return LOG_DETAIL_STOPPED;
            }
            return enabledSetting(packageManager, packageName)
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    ? "目标应用被强停（FLAG_STOPPED），并且已被用户停用（enabledSetting=3）："
                            + "打开一次清不掉用户停用，需在系统设置中启用"
                    : LOG_DETAIL_STOPPED_AND_DISABLED;
        }
        if (!application.enabled) {
            return enabledSetting(packageManager, packageName)
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    ? "目标应用已被用户停用（enabledSetting=3），本次尝试已被系统拒绝，"
                            + "请在系统设置中启用"
                    : "目标应用处于系统停用状态（enabledSetting="
                            + enabledSetting(packageManager, packageName)
                            + "），本次尝试已被系统拒绝；手动打开一次通常可恢复";
        }
        return null;
    }

    private static boolean resolvesProvider(PackageManager packageManager,
                                           String authority, int flags) {
        try {
            return packageManager.resolveContentProvider(authority, flags) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean resolvesComponent(PackageManager packageManager,
                                            ComponentName component, int flags) {
        try {
            return packageManager.resolveService(
                    new Intent().setComponent(component), flags) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static int count(Object[] array) {
        return array == null ? 0 : array.length;
    }

    /** 去掉包名前缀，日志里短一些。 */
    private static String shortName(ComponentInfo component, String packageName) {
        if (component == null || component.name == null) {
            return "?";
        }
        return component.name.startsWith(packageName)
                ? component.name.substring(packageName.length()) : component.name;
    }

    private static String join(List<String> parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (text.length() > 0) {
                text.append("；");
            }
            text.append(part);
        }
        return text.toString();
    }
}

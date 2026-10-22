package com.jtfa.notificationfixer.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.TypedValue;

import com.jtfa.notificationfixer.core.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 第 ④ 条预热路：借目标应用自己的一个「启动时不显示界面」的 Activity 把进程叫起来。
 *
 * <p>为什么需要它：「唤醒后台」那三条路（导出 Provider / Service / 广播接收器）要的是「对方留了一个能被
 * 外部调用的组件」。有些应用一个都没留（或者唯一的组件被运行时停用了，见 ①′），但它<b>总有
 * 中转用的 Activity</b> —— 分享跳转、scheme 入口、推送落地页，这类 Activity 由系统主题声明成
 * 不显示界面（{@code Theme.NoDisplay}）或透明，用户点通知后本来看不见它们。
 * 拿它当唤醒手段，代价就是「可能闪一下」。
 *
 * <p>0.7.5 起这条路是<b>逐应用选出来的主路</b>，不再是「另外三条都不行时才试的兜底」：
 * 名单页点一行右边的「设置」（或长按一行）可以给这个应用选「拉起 Activity」，选了以后就
 * <b>不再</b>尝试另外三条。
 * 名单页给出的候选是<b>这个应用所有导出的 Activity</b>（不再预先滤掉非主进程 / 要求权限的），
 * 每一行都标出推断结果与风险，并且有「正常打开」/「尝试后台打开」两个按钮，
 * 让用户当场自己看有没有界面。
 *
 * <p><b>和「秒开秒关的 startActivity」不是一回事</b>：那一次否掉是因为「藏窗口的开关不在我们
 * 手里」（透明 / NoDisplay 是对方声明的，我们读得到却改不了，也更没权限去关掉刚拉起的窗口）。
 * 这条路不藏任何东西 —— 它只用对方<b>本来就不可见</b>的那些 Activity，我们做的事是把「不可见」
 * 这件事从清单里读出来。
 *
 * <p>推断依据（全部来自 {@code PackageManager} 与对方包里的主题资源，不需要任何权限）：
 *
 * <ul>
 *   <li>{@code windowNoDisplay=true} → 连窗口都不建；</li>
 *   <li>{@code windowIsTranslucent=true} 或 {@code windowBackground} 全透明 → 建窗口但看不见内容；</li>
 *   <li>主题资源名里带 NoDisplay / Translucent / Transparent（读属性失败时的兜底）；</li>
 *   <li>推断不出来的（读不到主题）单独一档，不参与自动挑，交给用户自己试。</li>
 * </ul>
 *
 * <p>这只能是<b>推断</b>：Activity 还有可能在 {@code onCreate} 里自己 {@code setTheme()}、
 * 或者只是个会静态显示内容的中转页。所以界面上的「打开 / 试一次」才是判据 —— 进程有没有起来
 * 靠探活，有没有界面靠用户自己那双眼睛（有特权通道时还会把「窗口有没有建」摆出来）。
 */
public final class ActivityInventory {

    /** Activity 启动时「看不看得见」的推断档位。 */
    public enum Visibility {
        /** 连窗口都不建（windowNoDisplay）。 */
        NO_DISPLAY,
        /** 建窗口但透明，看不见内容。 */
        TRANSLUCENT,
        /** 读不出主题，推断不了。 */
        UNKNOWN,
        /** 看着是会显示界面的（自动挑不会选它，用户可以自己指定）。 */
        VISIBLE
    }

    /** 一个导出的候选 Activity。 */
    public static final class Candidate {

        public final ComponentName component;
        public final Visibility visibility;
        /** 一句话证据：主题名 + 命中的属性 + 清单标记。 */
        public final String evidence;
        /** 有效启用状态（清单 enabled 且没有运行时停用）。 */
        public final boolean enabled;
        /** 是不是跑在应用主进程里（预热的意义就在主进程）。 */
        public final boolean mainProcess;
        /** 声明的调用权限；null = 不要求。 */
        public final String permission;

        Candidate(ComponentName component, Visibility visibility, String evidence,
                  boolean enabled, boolean mainProcess, String permission) {
            this.component = component;
            this.visibility = visibility;
            this.evidence = evidence;
            this.enabled = enabled;
            this.mainProcess = mainProcess;
            this.permission = permission;
        }

        /**
         * 能不能真的把主进程叫起来：导出（列表里都是）、在主进程、不要求权限、启用中。
         *
         * <p>不满足的候选项照样列给用户（他可能就是想试它），但自动挑只在这些里面挑。
         */
        public boolean usable() {
            return enabled && mainProcess && permission == null;
        }

        /** 一句话风险说明；null = 没有。 */
        public String warning() {
            if (!mainProcess) {
                return "不在主进程，起来了也不是应用界面那个进程";
            }
            if (permission != null) {
                return "要求权限 " + permission + "，调用会被拒";
            }
            if (!enabled) {
                return "已停用，系统不会交给它";
            }
            return null;
        }

        /** 类名短写（对话框里太长的全名放不下）。 */
        public String shortName() {
            if (component == null) {
                return "?";
            }
            String full = component.getClassName();
            String packageName = component.getPackageName();
            if (full.startsWith(packageName + ".")) {
                full = full.substring(packageName.length() + 1);
            }
            return full;
        }

        /** 诊断日志里的一行：带上证据。 */
        public String detailLine() {
            return component.flattenToShortString() + " [" + visibilityLabel(visibility)
                    + (enabled ? " · 已启用" : " · 已停用")
                    + (mainProcess ? "" : " · 非主进程")
                    + (permission == null ? "" : " · 要求权限 " + permission)
                    + " · " + evidence + "]";
        }
    }

    /** 一次探测的全部结果。 */
    public static final class Result {

        /** 这个应用<b>所有导出</b>的 Activity，按「能用的 / 越不像会显示的」排好。 */
        public final List<Candidate> candidates;

        /** 用户明确指定的那个组件名；null = 没指定（用自动挑的）。 */
        public final String setting;

        /** 用户指定的候选（在候选列表里能找到才有）。 */
        public final Candidate picked;

        /** 自动挑的：第一个「能用 + 不显示 / 透明」的。 */
        public final Candidate auto;

        Result(List<Candidate> candidates, String setting, Candidate picked, Candidate auto) {
            this.candidates = candidates;
            this.setting = setting;
            this.picked = picked;
            this.auto = auto;
        }

        /** 真正会用的那个：用户指定优先，否则自动挑；都没有就是 null。 */
        public Candidate chosen() {
            if (setting != null && !setting.isEmpty()) {
                return picked;
            }
            return auto;
        }

        /** 是不是用户指定的（界面上要说清是谁挑的）。 */
        public boolean chosenByUser() {
            return setting != null && !setting.isEmpty() && picked != null;
        }

        /** 用户指定了，但那个组件在这台机器上已经找不到 / 不导出了。 */
        public boolean settingMissing() {
            return setting != null && !setting.isEmpty() && picked == null;
        }

        public int count(Visibility visibility) {
            int total = 0;
            for (Candidate candidate : candidates) {
                if (candidate.visibility == visibility) {
                    total++;
                }
            }
            return total;
        }

        /** 诊断要的一行。 */
        public String summaryLine() {
            StringBuilder text = new StringBuilder();
            text.append("Activity 候选 ").append(candidates.size()).append(" 个（不显示 ")
                    .append(count(Visibility.NO_DISPLAY)).append(" · 透明 ")
                    .append(count(Visibility.TRANSLUCENT)).append(" · 推断不出 ")
                    .append(count(Visibility.UNKNOWN)).append(" · 会显示 ")
                    .append(count(Visibility.VISIBLE)).append("）");
            if (settingMissing()) {
                text.append("；你指定：").append(setting).append("（这台机器上找不到或已不导出）");
            } else if (chosenByUser()) {
                text.append("；你指定：").append(picked.shortName())
                        .append("（").append(visibilityLabel(picked.visibility)).append("）");
            } else {
                text.append("；没指定，自动挑：")
                        .append(auto == null ? "没有" : auto.shortName() + "（"
                                + visibilityLabel(auto.visibility) + "）");
            }
            return text.toString();
        }
    }

    private ActivityInventory() {
    }

    /**
     * 完整的候选列表与当前选择。不缓存：调用它的地方只有「引擎挑目标」与「用户打开选择器」，
     * 两者都是一次性的，缓存反而要多写一套失效逻辑。
     */
    public static Result resolve(Context context, String packageName) {
        List<Candidate> candidates = new ArrayList<>();
        String mainProcess = packageName;
        PackageManager packageManager = context.getPackageManager();
        Resources appResources = null;
        try {
            PackageInfo info = packageManager.getPackageInfo(packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS));
            ApplicationInfo application = info.applicationInfo;
            if (application != null) {
                mainProcess = application.processName != null && !application.processName.isEmpty()
                        ? application.processName : packageName;
            }
            // 对方包的资源在外面取一次就够：每个 Activity 都要读它的主题，逐个取太浪费
            try {
                appResources = packageManager.getResourcesForApplication(packageName);
            } catch (Throwable ignored) {
                // 取不到就退化成「读不出主题」，候选依旧列出来
            }
            if (info.activities != null) {
                for (ActivityInfo activity : info.activities) {
                    Candidate candidate = inspect(packageManager, appResources, activity, mainProcess);
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                }
            }
        } catch (Throwable t) {
            // 读不到就没有候选：调用方会退回到「另外三条路都没有」的结论
            return new Result(candidates, Prefs.getAppActivity(packageName), null, null);
        }

        Collections.sort(candidates, (left, right) -> {
            // 能用的排前面：不能用的（非主进程 / 要权限 / 已停用）标着风险列在后面
            int byUsable = Boolean.compare(right.usable(), left.usable());
            if (byUsable != 0) {
                return byUsable;
            }
            int byVisibility = left.visibility.ordinal() - right.visibility.ordinal();
            if (byVisibility != 0) {
                return byVisibility;
            }
            return left.shortName().compareToIgnoreCase(right.shortName());
        });

        String setting = Prefs.getAppActivity(packageName);
        Candidate picked = null;
        if (setting != null && !setting.isEmpty()) {
            for (Candidate candidate : candidates) {
                if (candidate.component.flattenToString().equals(setting)
                        || candidate.component.flattenToShortString().equals(setting)) {
                    picked = candidate;
                    break;
                }
            }
        }
        Candidate auto = null;
        for (Candidate candidate : candidates) {
            if (candidate.usable()
                    && (candidate.visibility == Visibility.NO_DISPLAY
                    || candidate.visibility == Visibility.TRANSLUCENT)) {
                auto = candidate;
                break;
            }
        }
        return new Result(candidates, setting, picked, auto);
    }

    /**
     * 引擎要的那个结论：挑得出一个组件时才返回目标。
     *
     * <p>用户<b>明确指定</b>的组件一律照用（哪怕它要求权限 / 已停用 / 不在主进程）：
     * 那是用户自己看过多候选之后的决定，值不值得用由实测回答，我们只在日志与界面上把风险说清。
     * 自动挑的则严格限定在「能用 + 不显示 / 透明」。
     */
    public static WarmTarget target(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        return target(resolve(context, packageName));
    }

    /** 调用方手上已经有一份 {@link Result} 时用这个（少解析一次）。 */
    public static WarmTarget target(Result result) {
        if (result == null) {
            return null;
        }
        Candidate chosen = result.chosen();
        if (chosen == null) {
            return null;
        }
        StringBuilder qualifier = new StringBuilder(
                result.chosenByUser() ? "你指定" : "自动挑的：" + visibilityLabel(chosen.visibility));
        String warning = chosen.warning();
        if (warning != null) {
            qualifier.append("（").append(warning).append("）");
        }
        StringBuilder note = new StringBuilder(result.summaryLine())
                .append("；选中 ").append(chosen.detailLine());
        if (warning != null) {
            note.append("；注意：").append(warning);
        }
        return WarmTarget.activity(chosen.component, qualifier.toString(), note.toString());
    }

    /**
     * 挑不出目标时的说法，直接当「不可预热」的原因交给用户。
     *
     * <p>三种情况必须分开说，因为它们要用户做的事完全不同。
     */
    public static String unavailableReason(Result result) {
        if (result == null) {
            return "读不到这个应用的 Activity 清单";
        }
        if (result.settingMissing()) {
            return "你指定的 Activity 在这台机器上找不到或已不再导出（可在名单页长按一行重挑）";
        }
        if (result.candidates.isEmpty()) {
            return "这个应用一个导出的 Activity 都没有";
        }
        return "没有推断成「不显示 / 透明」的候选（共 " + result.candidates.size()
                + " 个导出 Activity，可在名单页长按一行自己挑一个，并用「打开」验证）";
    }

    /** 界面与日志用的档位名字。 */
    public static String visibilityLabel(Visibility visibility) {
        switch (visibility) {
            case NO_DISPLAY:
                return "不建窗口";
            case TRANSLUCENT:
                return "透明";
            case VISIBLE:
                return "会显示界面";
            default:
                return "推断不出";
        }
    }

    // ---------------------------------------------------------------- 单个候选的筛选与推断

    /** 非导出返回 null；导出的都收，能不能用得看 {@link Candidate#usable()}。 */
    private static Candidate inspect(PackageManager packageManager, Resources appResources,
                                     ActivityInfo activity, String mainProcess) {
        if (activity == null || activity.name == null || activity.packageName == null) {
            return null;
        }
        // 只有这一道硬条件：导出。0.7.5 起非主进程 / 要求权限的也列出来（标上风险），
        // 因为用户要的是「这个应用所有已导出的 Activity」，而不是我们替他先筛一遍。
        if (!activity.exported) {
            return null;
        }
        String process = activity.processName;
        boolean runsInMain = process == null || process.isEmpty() || process.equals(mainProcess);
        ComponentName component = new ComponentName(activity.packageName, activity.name);
        boolean enabled;
        try {
            enabled = activity.enabled && !isRuntimeDisabled(packageManager, component);
        } catch (Throwable t) {
            enabled = activity.enabled;
        }
        StringBuilder evidence = new StringBuilder();
        Visibility visibility = classify(appResources, activity, evidence);
        if ((activity.flags & ActivityInfo.FLAG_NO_HISTORY) != 0) {
            evidence.append(evidence.length() == 0 ? "" : " · ").append("noHistory");
        }
        if ((activity.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0) {
            evidence.append(evidence.length() == 0 ? "" : " · ").append("excludeFromRecents");
        }
        if (evidence.length() == 0) {
            evidence.append("清单里没有任何相关标记");
        }
        return new Candidate(component, visibility, evidence.toString(), enabled, runsInMain,
                activity.permission);
    }

    private static boolean isRuntimeDisabled(PackageManager packageManager, ComponentName component) {
        switch (packageManager.getComponentEnabledSetting(component)) {
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return true;
            default:
                return false;
        }
    }

    /**
     * 「启动时看不看得见」的推断。
     *
     * <p>读的是对方包里的主题资源（{@code getResourcesForApplication} 只给到一个 Resources 引用，
     * 不需要任何权限）：把主题当样式应用到一个空 Theme 上，再逐个问那几个窗口属性。读不到就是
     * {@link Visibility#UNKNOWN}，绝不猜成「不显示」。
     */
    private static Visibility classify(Resources appResources, ActivityInfo activity,
                                       StringBuilder evidence) {
        int themeResource = 0;
        try {
            themeResource = activity.getThemeResource();
        } catch (Throwable t) {
            themeResource = activity.theme;
        }
        String themeName = null;
        Boolean noDisplay = null;
        Boolean translucent = null;
        Boolean transparentBackground = null;
        if (themeResource != 0 && appResources != null) {
            try {
                try {
                    themeName = appResources.getResourceName(themeResource);
                } catch (Throwable ignored) {
                    // 名字只是证据的一部分
                }
                Resources.Theme theme = appResources.newTheme();
                theme.applyStyle(themeResource, true);
                TypedValue value = new TypedValue();
                noDisplay = readBoolean(theme, android.R.attr.windowNoDisplay, value);
                translucent = readBoolean(theme, android.R.attr.windowIsTranslucent, value);
                transparentBackground = readTransparentBackground(theme, value);
            } catch (Throwable t) {
                // 读不出来就靠下面的名字兜底，再不行就是 UNKNOWN
            }
        }
        if (themeName != null) {
            evidence.append("主题 ").append(themeName);
        }
        appendBool(evidence, "windowNoDisplay", noDisplay);
        appendBool(evidence, "windowIsTranslucent", translucent);
        appendBool(evidence, "背景透明", transparentBackground);

        if (Boolean.TRUE.equals(noDisplay) || nameSays(themeName, "nodisplay")) {
            return Visibility.NO_DISPLAY;
        }
        if (Boolean.TRUE.equals(translucent) || Boolean.TRUE.equals(transparentBackground)
                || nameSays(themeName, "translucent") || nameSays(themeName, "transparent")) {
            return Visibility.TRANSLUCENT;
        }
        if (themeResource == 0) {
            return Visibility.UNKNOWN;
        }
        if (noDisplay == null && translucent == null && transparentBackground == null) {
            return Visibility.UNKNOWN;
        }
        return Visibility.VISIBLE;
    }

    private static void appendBool(StringBuilder evidence, String name, Boolean value) {
        if (value == null) {
            return;
        }
        if (evidence.length() > 0) {
            evidence.append(" · ");
        }
        evidence.append(name).append('=').append(value);
    }

    private static boolean nameSays(String themeName, String keyword) {
        return themeName != null && themeName.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /** 主题里的布尔属性；读不到（属性没定义 / 不是布尔）返回 null，不等于 false。 */
    private static Boolean readBoolean(Resources.Theme theme, int attribute, TypedValue value) {
        try {
            if (!theme.resolveAttribute(attribute, value, true)) {
                return null;
            }
            if (value.type == TypedValue.TYPE_INT_BOOLEAN) {
                return value.data != 0;
            }
            if (value.type == TypedValue.TYPE_INT_DEC || value.type == TypedValue.TYPE_INT_HEX) {
                return value.data != 0;
            }
        } catch (Throwable ignored) {
            // 属性读不到 = 不知道
        }
        return null;
    }

    /** 窗口背景是不是全透明（{@code windowIsTranslucent} 没写时的第二手证据）。 */
    private static Boolean readTransparentBackground(Resources.Theme theme, TypedValue value) {
        try {
            if (!theme.resolveAttribute(android.R.attr.windowBackground, value, true)) {
                return null;
            }
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return Color.alpha(value.data) == 0;
            }
            if (value.type == TypedValue.TYPE_STRING && value.string != null) {
                return value.string.toString().toLowerCase(Locale.ROOT).contains("transparent");
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return null;
    }

    /** 界面提示用：这个应用选了「拉起 Activity」时现在会打开哪个组件。 */
    public static String stateText(Context context, String packageName) {
        Result result = resolve(context, packageName);
        if (result.settingMissing()) {
            return "你指定的那个组件在这台机器上已经找不到或不导出了 —— 请重新挑一个";
        }
        Candidate chosen = result.chosen();
        if (chosen == null) {
            if (result.candidates.isEmpty()) {
                return "这个应用没有导出的 Activity，这条路借不到东西";
            }
            return "没猜到哪个不显示（共 " + result.candidates.size()
                    + " 个导出 Activity）：自己挑一个，点「正常打开」或「尝试后台打开」看有没有界面";
        }
        StringBuilder text = new StringBuilder(
                result.chosenByUser() ? "你指定的 " : "自动挑的 ")
                .append('.').append(chosen.shortName())
                .append("（").append(visibilityLabel(chosen.visibility));
        String warning = chosen.warning();
        if (warning != null) {
            text.append(" · ").append(warning);
        }
        return text.append("）").toString();
    }

    /** 给外部（诊断日志）用的：把候选列表原样交出去。 */
    public static List<String> detailLines(Result result) {
        List<String> lines = new ArrayList<>();
        lines.add(result.summaryLine());
        for (Candidate candidate : result.candidates) {
            lines.add(candidate.detailLine());
        }
        return lines;
    }

    /** 组件名规范化：接受完整名与短名两种写法。 */
    public static String flatten(ComponentName component) {
        return component == null ? null : component.flattenToString();
    }

    /** 启动 Activity 用的 intent —— 放在这里是为了「用同一份参数」这条保证。 */
    public static Intent intentFor(ComponentName component) {
        Intent intent = new Intent();
        intent.setComponent(component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return intent;
    }

    /**
     * 这个 Activity 能不能用 {@code ActivityOptions.makeTaskLaunchBehind()} + NEW_DOCUMENT
     * 留在后台启动（0.7.58-M6 / 79）。
     *
     * <p>AOSP 的 ActivityStarter 对两种情况会忽略 NEW_DOCUMENT：Activity 声明为
     * {@code singleTask} / {@code singleInstance}，或者 {@code documentLaunchMode="never"}。
     * 这时 launchTaskBehind 不成立，系统仍会把任务带到前台，所以必须先判断再选路。
     *
     * <p>读不到清单时返回 false：宁可退回旧策略，也不要在没把握时把对方页面弹出来。
     */
    public static boolean supportsLaunchBehind(Context context, ComponentName component) {
        if (context == null || component == null) {
            return false;
        }
        try {
            PackageManager packageManager = context.getPackageManager();
            ActivityInfo info = packageManager.getActivityInfo(component, 0);
            if (!launchBehindCompatible(info)) {
                return false;
            }
            // Activity 别名还要看它真正指向的那个 Activity：ActivityStarter 会用 targetActivity
            // 替换组件名，再按目标声明判断 launchMode / documentLaunchMode。
            if (info.targetActivity != null) {
                ActivityInfo target = packageManager.getActivityInfo(
                        new ComponentName(info.packageName, info.targetActivity), 0);
                return launchBehindCompatible(target);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean launchBehindCompatible(ActivityInfo info) {
        return info != null
                && info.launchMode != ActivityInfo.LAUNCH_SINGLE_TASK
                && info.launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE
                && info.documentLaunchMode != ActivityInfo.DOCUMENT_LAUNCH_NEVER;
    }
}

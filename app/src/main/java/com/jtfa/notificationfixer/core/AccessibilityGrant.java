package com.jtfa.notificationfixer.core;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import com.jtfa.notificationfixer.detect.ShadeAccessibilityService;

import java.util.List;

/**
 * 无障碍服务的授权与读写（0.7.20-M6 / 41 重做）。
 *
 * <p>0.7.19 那一版是「拼两条 {@code settings put secure} 命令，交给特权通道或用户去跑」。
 * 那条路做不到「开机就把无障碍补回来」：adb 模式的 Shizuku 服务端自己也随重启没了，
 * 开机那一刻没有能跑 shell 的身份。0.7.20 换成另一条 adb 语句：
 *
 * <pre>adb shell pm grant com.jtfa.notificationfixer android.permission.WRITE_SECURE_SETTINGS</pre>
 *
 * <p>{@code WRITE_SECURE_SETTINGS} 的保护级别是 {@code signature|privileged|development}，
 * 而 development 这一类<b>可以</b>由 shell（adb / Shizuku，uid 2000）授给普通应用；授完之后
 * <b>本应用自己</b>就能写 secure 设置 —— 于是开机广播一进来（同一进程里、开机那一刻就在）
 * 就能把无障碍写回系统设置，不再需要任何通道。这就是「开机自启」里无障碍那一半能成立的原因。
 *
 * <p>写的时候仍然只在<b>本应用这一项</b>上动手：先读当前列表，没有本应用就<b>追加</b>一项，
 * 免得把用户开着的别的无障碍服务（例如 TalkBack）一起关掉；读不到当前列表时<b>不写</b> ——
 * 覆盖写等于关掉别人，宁可什么都不做并留下日志。
 *
 * <p><b>这个权限不等于打开无障碍</b>：它只是让本应用有资格写设置，本身不打开任何东西。
 * 「从没开启过的用户不由应用代开」这条边界见 {@code AccessibilityAutostart}。
 */
public final class AccessibilityGrant {

    /** 电脑终端里的前缀，给用户自己粘的那份命令用。 */
    private static final String ADB = "adb shell ";

    /** ROM 的「无障碍不要自动关」豁免名单（ColorOS 那一套，见 {@link #joinRomSkipList}）。 */
    public static final String KEY_TURN_OFF_SKIP_PACKAGE = "accessibility_turn_off_skip_package";

    private AccessibilityGrant() {
    }

    /** 本应用无障碍服务的组件名（短形式，与系统设置里写的同一套写法）。 */
    public static String component(Context context) {
        return self(context).flattenToShortString();
    }

    private static ComponentName self(Context context) {
        return new ComponentName(context.getPackageName(),
                ShadeAccessibilityService.class.getName());
    }

    // ---------------------------------------------------------------- 授权（WRITE_SECURE_SETTINGS）

    /**
     * 本应用是否已拿到「写入安全设置」权限。
     *
     * <p>这是个 development 权限，只能由 adb / Shizuku / root 授进来（见类注释）。读的是真实的
     * 授权状态（{@code checkSelfPermission} 直接问 PackageManager），不是猜的，也不依赖任何通道。
     */
    public static boolean permissionGranted(Context context) {
        try {
            return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 授权命令，交给特权通道执行（<b>不带</b> {@code adb shell} 前缀）。 */
    public static String grantCommand(Context context) {
        return "pm grant " + context.getPackageName()
                + " " + Manifest.permission.WRITE_SECURE_SETTINGS;
    }

    /** 给用户自己粘到电脑终端的版本（带上 {@code adb shell} 前缀）。 */
    public static String grantDisplayText(Context context) {
        return ADB + grantCommand(context);
    }

    // ---------------------------------------------------------------- 读写系统设置

    /**
     * 无障碍现在是不是「开着的」。
     *
     * <p>两个来源任一说是开就算开：系统报的已启用服务（{@link Permissions#hasAccessibility}，
     * 也就是系统设置页里那个开关的读数），以及 secure 原始值（列表里有本应用、总开关非 0）。
     *
     * <p>为什么要看原始值：刚把设置写进去的那一瞬间，system_server 那边（AMS）还没跟上，
     * 只看 {@link AccessibilityManager} 会把「刚写好」误判成「还关着」，于是「开机补写成功」
     * 下来又被记成「未启用」，下次开机就不再补写了。
     */
    public static boolean isOn(Context context) {
        if (Permissions.hasAccessibility(context)) {
            return true;
        }
        return masterFlag(context) != 0 && listContains(context, currentList(context));
    }

    /**
     * 系统里当前的「已启用无障碍服务」列表。
     *
     * <p>读到了但列表是空的返回空串；两条路都读不到返回 {@code null}（写入路径据此拒绝覆盖写）。
     *
     * <p>先读 {@link Settings.Secure}（那是这份列表的原文）；个别 ROM 收紧这个读时，退一步用
     * {@link AccessibilityManager#getEnabledAccessibilityServiceList} 把系统报的已启用服务拼回来。
     */
    public static String currentList(Context context) {
        try {
            String raw = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (raw == null || "null".equals(raw.trim())) {
                return "";   // 读到了，就是空的
            }
            return raw;
        } catch (Throwable t) {
            return composedList(context);
        }
    }

    /** 退路：把系统报的「已启用服务」拼成同一种写法（冒号分隔）。 */
    private static String composedList(Context context) {
        AccessibilityManager manager = (AccessibilityManager)
                context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        try {
            List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : enabled) {
                String id = info == null ? null : info.getId();
                if (id == null || id.isEmpty()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(':');
                }
                builder.append(id);
            }
        } catch (Throwable t) {
            return null;
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    /** 「无障碍」总开关当前的值（1 = 开），读不到返回 -1（写日志用，不当唯一判据）。 */
    public static int masterFlag(Context context) {
        try {
            return Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 列表里是否已经有本应用这一项（按解析后的组件比，见类注释）。 */
    public static boolean listContains(Context context, String list) {
        if (list == null) {
            return false;
        }
        ComponentName self = self(context);
        for (String item : list.split(":")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            ComponentName name = ComponentName.unflattenFromString(trimmed);
            if (name != null && name.equals(self)) {
                return true;
            }
        }
        return false;
    }

    /** 一次「把无障碍服务写回系统设置」的结果，字段就是写日志要用的原始值。 */
    public static final class WriteResult {

        /** 写完再读回来：列表里有本应用，且总开关是开的。 */
        public final boolean ok;

        /** 改前的列表原文（读不到是 null）。 */
        public final String before;

        /** 改后读回来的列表原文（没写成是 null）。 */
        public final String after;

        /** 改后读回来的 {@code accessibility_enabled}（没写成是 -1）。 */
        public final int masterAfter;

        /** 没成的原因（{@link #ok} 为 true 时是 null）。 */
        public final String error;

        WriteResult(boolean ok, String before, String after, int masterAfter, String error) {
            this.ok = ok;
            this.before = before;
            this.after = after;
            this.masterAfter = masterAfter;
            this.error = error;
        }
    }

    /**
     * 把本应用的无障碍服务写回系统设置（需要 {@link #permissionGranted}）。
     *
     * <p>直接走 {@link Settings.Secure}：本应用持有 WRITE_SECURE_SETTINGS 时，这就是一次普通的
     * binder 写，不需要特权通道、不需要任何进程配合 —— 开机广播里也能做。
     *
     * <p>写完立刻读回原始值当作证据（{@link WriteResult#ok} 判的就是读回来的结果，不是「命令跑完了」）。
     */
    public static WriteResult writeEnabled(Context context) {
        String before = currentList(context);
        if (before == null) {
            return new WriteResult(false, null, null, -1,
                    "读不到当前的无障碍服务列表，不覆盖写");
        }
        ContentResolver resolver = context.getContentResolver();
        try {
            Settings.Secure.putString(resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, mergedList(context, before));
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (Throwable t) {
            return new WriteResult(false, before, null, -1, "写系统设置被拒绝：" + t);
        }
        String after = currentList(context);
        int master = masterFlag(context);
        boolean ok = after != null && listContains(context, after) && master != 0;
        return new WriteResult(ok, before, after, master, ok ? null : "写完读回来还是不在列表里");
    }

    /** 把本应用追加进列表；已经在里面就原样写回（幂等），空列表时只写本应用。 */
    private static String mergedList(Context context, String current) {
        String trimmed = current.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed)) {
            return component(context);
        }
        if (listContains(context, trimmed)) {
            return trimmed;
        }
        return trimmed + ":" + component(context);
    }

    // ---------------------------------------------------------------- ROM 的「无障碍不要自动关」名单

    /**
     * ColorOS / realme / 一加那套「无障碍自动关闭」功能的豁免名单（0.7.23-M6 / 44）。
     *
     * <p>2026-10-18 的真机数据把它认了出来：重启后 {@code enabled_accessibility_services} 里<b>本来
     * 还有本应用</b>，但 {@code accessibility_enabled=0}；再过一会儿连列表里那一项也没了（只剩 GKD），
     * 而 secure 里躺着这么两组键：
     *
     * <pre>
     * accessibility_turn_off_switch=1
     * accessibility_turn_off_skip_package=…:li.songe.gkd:…      ← 名单里的不动
     * accessibility_turn_off_skip_install_source=com.heytap.market:com.oplus.exsystemservice:…
     * </pre>
     *
     * <p>也就是：ROM 会关掉<b>安装来源不可信</b>的应用的无障碍（本应用是 adb / Android Studio 装的，
     * 正落在这一档），除非那个包在这份豁免名单里 —— GKD、李跳跳、自动跳过这些平时也是侧装的应用都在
     * 里面，所以它们重启后活着，我们不在，所以我们被关。这解释了两轮真机现象，也是「GKD 为什么行」
     * 的答案。
     *
     * <p>这里只做一件事：<b>把本应用这一个包名追加进那份名单</b>（读原文、只追加、写完读回确认），
     * 别的一项不动，也不去碰总开关 {@code accessibility_turn_off_switch}（那是 ROM 的全局策略，
     * 的全局策略，
     * 不是本应用该替用户决定的事）。键不存在时什么都不做 —— 别的 ROM 没有这道门。
     */

    /** 一次「把本应用加进 ROM 豁免名单」的结果。 */
    public static final class SkipListResult {

        /** 本应用现在在名单里（本来就是，或刚写进去并读回确认）。 */
        public final boolean ok;

        /** 这台机器没有这个键：ROM 没这道门，不需要做什么（{@link #ok} 也算成立）。 */
        public final boolean noSuchKey;

        /** 本来就在名单里，没有动过它。 */
        public final boolean already;

        /** 改前的名单（键不存在时是 null）。 */
        public final String before;

        /** 改后读回来的名单（没写成是 null）。 */
        public final String after;

        /** 名单里的项数（只给日志用：那条名单很长，不能让日志变成一堵墙）。 */
        public final int beforeCount;

        /** 改后的项数。 */
        public final int afterCount;

        /** 没成的原因（{@link #ok} 为 true 时是 null）。 */
        public final String error;

        SkipListResult(boolean ok, boolean noSuchKey, boolean already,
                       String before, String after, int beforeCount, int afterCount, String error) {
            this.ok = ok;
            this.noSuchKey = noSuchKey;
            this.already = already;
            this.before = before;
            this.after = after;
            this.beforeCount = beforeCount;
            this.afterCount = afterCount;
            this.error = error;
        }
    }

    /**
     * 把本应用加进 ROM 的「无障碍不要自动关」名单（需要 {@link #permissionGranted}）。
     *
     * <p>幂等：已经在里面就什么都不做。键不存在（别的 ROM）同样什么都不做。
     */
    public static SkipListResult joinRomSkipList(Context context) {
        String before;
        try {
            before = Settings.Secure.getString(context.getContentResolver(),
                    KEY_TURN_OFF_SKIP_PACKAGE);
        } catch (Throwable t) {
            return new SkipListResult(false, false, false, null, null, 0, 0,
                    "读不到 " + KEY_TURN_OFF_SKIP_PACKAGE + "：" + t);
        }
        if (before == null || before.trim().isEmpty()) {
            return new SkipListResult(true, true, false, before, before, 0, 0, null);
        }
        int beforeCount = countItems(before);
        if (containsPackage(context, before)) {
            return new SkipListResult(true, false, true, before, before, beforeCount, beforeCount, null);
        }
        String after;
        try {
            Settings.Secure.putString(context.getContentResolver(),
                    KEY_TURN_OFF_SKIP_PACKAGE, before.trim() + ":" + context.getPackageName());
            after = Settings.Secure.getString(context.getContentResolver(),
                    KEY_TURN_OFF_SKIP_PACKAGE);
        } catch (Throwable t) {
            return new SkipListResult(false, false, false, before, null, beforeCount, 0,
                    "写 " + KEY_TURN_OFF_SKIP_PACKAGE + " 被拒绝：" + t);
        }
        boolean ok = after != null && containsPackage(context, after);
        return new SkipListResult(ok, false, false, before, after,
                beforeCount, after == null ? 0 : countItems(after),
                ok ? null : "写完读回来，名单里还是没有本应用");
    }

    /** 名单里有没有本应用这一个包名（这份名单只有包名，没有组件名）。 */
    private static boolean containsPackage(Context context, String list) {
        String self = context.getPackageName();
        for (String item : list.split(":")) {
            if (self.equals(item.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 名单里有几项（日志里只报数字，免得那一长串把日志刷满）。 */
    private static int countItems(String list) {
        int count = 0;
        for (String item : list.split(":")) {
            if (!item.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 名单里现在有没有本应用，给日志的原始读数用（只读，不写）。
     *
     * <p>它回答的是「ROM 下一次要不要把本应用关掉」：写着「含本应用」时，重启之后本来就不该被关。
     */
    public static String skipListState(Context context) {
        String raw;
        try {
            raw = Settings.Secure.getString(context.getContentResolver(), KEY_TURN_OFF_SKIP_PACKAGE);
        } catch (Throwable t) {
            return "读不到";
        }
        if (raw == null || raw.trim().isEmpty()) {
            return "本机没这道门";
        }
        return containsPackage(context, raw) ? "含本应用" : "不含本应用";
    }
}

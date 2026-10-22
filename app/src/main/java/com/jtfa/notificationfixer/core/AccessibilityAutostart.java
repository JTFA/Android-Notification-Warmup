package com.jtfa.notificationfixer.core;

import android.content.Context;
import android.os.SystemClock;

/**
 * 开机后把无障碍服务写回来（0.7.20-M6 / 41 起，0.7.21-M6 / 42 改成「一眼 + 窗口内重试」）。
 *
 * <p>无障碍不是进程，是系统里的一条设置（{@code enabled_accessibility_services} +
 * {@code accessibility_enabled}）：正常情况下它<b>跨重启保留</b>，开机时由 system_server 自己按
 * 这份名单去 bind 我们的服务。但部分 ROM 会在重启后把第三方无障碍服务关掉，那时唯一的办法是把
 * 这条设置再写一遍 —— 而这件事在 0.7.19 做不到：adb 模式的 Shizuku 服务端自己也随重启没了，
 * 开机那一刻没有能写设置的通道。0.7.20 把 {@code WRITE_SECURE_SETTINGS} 授给本应用之后
 * （见 {@link AccessibilityGrant}），我们自己进程就能写。
 *
 * <h2>0.7.21 为什么要改：一次写入可能不够</h2>
 *
 * <p>0.7.20 只写了<b>开机那一趟</b>（{@code BootReceiver}）。真机上「权限授予成功、重启后无障碍
 * 还是关的」有三种可能，其中两种是那时的写法挡不住的：
 *
 * <ol>
 *   <li>本应用在开机那一刻根本没被唤醒（ROM 的自启动名单挡住了 {@code BOOT_COMPLETED}）
 *       —— 那时候没有任何代码在跑，只能靠「进程因别的原因起来时顺手看一眼」兜住；</li>
 *   <li><b>ROM 的清理发生在开机广播之后</b>：我们写进去、system_server 也 bind 上了，几十秒或
 *       一两分钟后 ROM 又把它关掉 —— 一次性的写在时间上够不着；</li>
 *   <li>写进去没留住（写被拒 / 写完立刻被顶掉）—— 那时需要的不是重试，而是把这条原始信号看清楚。</li>
 * </ol>
 *
 * <p>所以现在只有一个入口 {@link #poll}，它在<b>每一次「本应用进程起来」和此后每 15 分钟</b>各跑
 * 一次：先判断要不要写（判据是上一次的观察结果，必须先判后记），再更新观察记录。
 *
 * <h2>什么时候才会写（其余一律不写）</h2>
 *
 * <p>写的条件（要求「开机自启」开关是开的、有 {@code WRITE_SECURE_SETTINGS}、上次观察到它是开着的）：
 *
 * <ul>
 *   <li><b>① 那次「开」是在这次开机之前看到的</b>：状态跨了一次重启就变成了「关」，这个变化发生
 *       在我们死着的时候 —— 只可能是重启带来的（ROM 的清理），写回去。这一条同时兜住了上面第 1
 *       种可能：进程被 ROM 拦到开机后很久才起来，第一眼看到「关着」也会补写。</li>
 *   <li><b>② 还在开机窗口内</b>（{@link #BOOT_WINDOW_MS}）：兜住上面第 2 种可能 —— ROM 的清理
 *       晚于开机广播时，窗口内每 {@code BOOT_WINDOW_TICK_MS} 看一眼，关掉就再写一次，最多
 *       {@link #BOOT_WINDOW_MAX_TRIES} 次。</li>
 *   <li><b>③ 本次开机我们写成功过、之后它又没了</b>（0.7.23-M6 / 44）：那次「开着」是我们自己写
 *       出来的，被关掉的是别人；这条不限时间，但受 {@link #MAX_WRITES_PER_BOOT} 与两次之间
 *       {@code GUARD_MIN_GAP_MS} 的约束，写满还是被关就写一行结论收手。</li>
 * </ul>
 *
 * <p>两条边界不变，都是「不替用户做主」：
 *
 * <ul>
 *   <li><b>只补写、不开首次</b>：上一次观察到的状态不是「开着」时什么都不做。从没开启过的用户
 *       不会在开机时被我们打开一个系统级开关。</li>
 *   <li><b>用户自己在运行期间关掉的不写回去</b>：那种「关」是我们<b>看着它发生</b>的
 *       （观察时刻晚于这次开机），两条条件都不成立。要它常开就别关，或者关掉「开机自启」这个开关。</li>
 * </ul>
 *
 * <h2>0.7.22 只补诊断，不改判据（真机结果推着走）</h2>
 *
 * <p>2026-10-18 的真机结果是：开机广播<b>送到了</b>（常驻服务自己起来了，通知栏能看见），写也
 * <b>有效</b>（进一次本应用，无障碍服务立刻就起来了），可重启后系统「无障碍」页里本应用仍是关着、
 * 而同样授过权限的 GKD 是开着的。所以问题不在权限、不在广播，而在「我们写进去之后，有谁又把它关掉」。
 *
 * <p>0.7.21 的日志分不开两种「关着」：secure 列表里真的没有我们（写进去被顶掉），还是列表里有我们、
 * 只是 AMS 没把它算成已启用（ROM 的界面读的是它自己那份记录）—— 这两者的出路完全不同。所以 0.7.22
 * 只做一件事：把每一行日志钉在开机时刻上（「开机后 N 秒」），把三个来源分开摊出来
 * （{@code accessibility_enabled}、AMS 报的、secure 原始列表），并记下「上次写成功在开机后 N 秒」。
 * 判据与写入时机<b>一个都没动</b>，就是为了让下一次开机的那份日志能直接回答：是写不进去，还是被顶掉；
 * 是被顶掉，还是用户关的。
 *
 * <h2>0.7.23：真凶是 ROM 的「无障碍自动关闭」，出路是它的豁免名单</h2>
 *
 * <p>2026-10-18 那份 0.7.22 日志把机制钉死了：开机后 24 秒，{@code enabled_accessibility_services}
 * 里<b>还有本应用</b>，但 {@code accessibility_enabled=0}；到 167 秒，连列表里那一项都没了
 * （只剩 {@code li.songe.gkd}）。而 secure 里躺着：
 *
 * <pre>
 * accessibility_turn_off_switch=1
 * accessibility_turn_off_skip_package=…:li.songe.gkd:…
 * accessibility_turn_off_skip_install_source=com.heytap.market:com.oplus.exsystemservice:…
 * </pre>
 *
 * <p>也就是 ColorOS 会关掉<b>安装来源不可信</b>的应用的无障碍（本应用是 adb / Android Studio 装的，
 * 正落在这一档），除非那个包在豁免名单里 —— GKD 与李跳跳、自动跳过那些侧装应用都在名单里，所以我们
 * 被关、它们活着。出路因此不是「写更多次」，而是<b>进那份名单</b>：{@link AccessibilityGrant#joinRomSkipList}
 * 只把本应用这一个包名追加进去（别的一项不动，也不碰 ROM 的总开关）。写无障碍之前先做这一步 ——
 * 顺序反了，ROM 下一步照样按「不可信来源」把我们关掉。
 */
public final class AccessibilityAutostart {

    /**
     * 开机后这段是「补写窗口」（0.7.21-M6 / 42）。
     *
     * <p>判据是 {@link SystemClock#elapsedRealtime()}（从开机算起、含休眠，重启时归零），
     * 所以窗口跟「谁在什么时候起来」无关：进程早起来晚起来都落在同一个窗口里。
     * ROM 对无障碍的清理多在开机后的一两分钟里，5 分钟够覆盖，又短到不会和用户当面关掉的操作打架。
     */
    public static final long BOOT_WINDOW_MS = 5L * 60L * 1000L;

    /** 窗口内看几次就收手（含补写）。一直关不掉多半是 ROM 在管，得去厂商名单里放行，硬按没用。 */
    public static final int BOOT_WINDOW_MAX_TRIES = 8;

    /**
     * 每次开机最多写几次（含窗口内重试，0.7.23-M6 / 44）。
     *
     * <p>封顶是给「和 ROM 抢」这件事留个体面：三次抢不回来就说明对面不是「漏了一次」，而是
     * 一直在按自己的规矩关（那时该去改 ROM 的名单，不是加次数）。
     */
    public static final int MAX_WRITES_PER_BOOT = 8;

    /** 「我们写成功过、它又被关掉」这条路两次写之间的最小间隔：太密就是纯抢，先把人看清楚。 */
    private static final long GUARD_MIN_GAP_MS = 60_000L;

    /** 一次 {@link #poll} 的结果。 */
    public enum Outcome {

        /** 无障碍现在开着，什么都不用做。 */
        ON,

        /** 关着，但没有补写的理由或条件（见类注释；具体哪一条在日志里）。 */
        SKIPPED,

        /** 关着，写回去了。 */
        REPAIRED,

        /** 关着，写了但没留住（写被拒 / 写完读回来还是没有）。 */
        FAILED
    }

    private AccessibilityAutostart() {
    }

    /** 现在是不是还在「刚开机」那一段（{@link SystemClock#elapsedRealtime()} 的零点是开机那一刻）。 */
    public static boolean inBootWindow() {
        return SystemClock.elapsedRealtime() < BOOT_WINDOW_MS;
    }

    /**
     * 看一眼无障碍，需要就写回系统设置。
     *
     * <p>调用点：应用进程每次启动（{@code App.onCreate}，开机广播、通知监听被系统 bind、用户打开
     * 应用都会走这里），以及常驻服务启动后每 30 秒（开机窗口内）或 15 分钟一次。
     *
     * <p>{@code attempt} 是「本次进程/服务启动以来第几次看」，只影响日志：解释「为什么不写」的
     * 那几行只在第 1 次说，免得每 30 秒刷一条。
     *
     * <p><b>顺序不能反</b>：①②③三条判据都用「上一次」的观察结果，所以先判、后记。
     */
    public static Outcome poll(Context context, int attempt) {
        boolean seenOn = Prefs.isAccessibilitySeenOn();
        long seenAt = Prefs.accessibilitySeenAt();
        boolean firstLook = attempt <= 1;
        boolean now = AccessibilityGrant.isOn(context);

        if (now) {
            boolean changed = !seenOn || seenAt <= 0L;
            Prefs.markAccessibilitySeen(true);
            if (changed || firstLook) {
                EventLog.get().log(EventLog.CAT_PERM,
                        "无障碍服务：现在开着" + (firstLook ? "（本次启动第一眼）" : ""),
                        stateData(context));
            }
            return Outcome.ON;
        }

        // 关着。下面每一步都可能「今天到这儿」，但状态照样要记下来（时刻同样更新，否则下次开机
        // 会把「用户刚才关的」当成「重启掉的」）。
        boolean changed = seenOn || seenAt <= 0L;
        boolean wroteThisBoot = Prefs.accessibilityWroteElapsed() > 0L
                && Prefs.accessibilityWroteElapsed() <= SystemClock.elapsedRealtime();
        Prefs.markAccessibilitySeen(false);
        if (changed) {
            EventLog.get().log(EventLog.CAT_PERM,
                    "无障碍服务：现在关着"
                            + (wroteThisBoot ? "（本次开机我们写成功过，又被关掉了）" : ""),
                    stateData(context));
        }

        if (!Prefs.isBootAutostart()) {
            if (firstLook) {
                EventLog.get().log(EventLog.CAT_PERM,
                        "无障碍服务不在，但「开机自启」是关的：不管它");
            }
            return Outcome.SKIPPED;
        }
        if (!seenOn) {
            if (firstLook) {
                EventLog.get().log(EventLog.CAT_PERM,
                        "无障碍服务不在，但上次看到它就不是启用状态：不替用户打开它"
                                + "（要它跟着开机自启，先在系统「无障碍」页里开启一次）");
            }
            return Outcome.SKIPPED;
        }
        if (!AccessibilityGrant.permissionGranted(context)) {
            if (firstLook) {
                EventLog.get().log(EventLog.CAT_PERM,
                        "无障碍服务不在，但没有写入安全设置的权限：跳过"
                                + "（到设置页「开机自启」卡里点「授予权限」）");
            }
            return Outcome.SKIPPED;
        }

        boolean sinceBoot = inBootWindow();
        boolean acrossBoot = seenAt > 0L && seenAt < bootWallClockMs();
        // ③ 本次开机我们写成功过、之后它又没了（0.7.23-M6 / 44）
        long wroteElapsed = Prefs.accessibilityWroteElapsed();
        boolean removedAfterOurWrite = wroteThisBoot && seenOn
                && SystemClock.elapsedRealtime() - wroteElapsed >= GUARD_MIN_GAP_MS;
        if (!sinceBoot && !acrossBoot && !removedAfterOurWrite) {
            if (firstLook) {
                EventLog.get().log(EventLog.CAT_PERM,
                        "无障碍服务：本次开机之后被关掉的（不是重启掉的），不代写"
                                + "（要它常开就别关，或关掉「开机自启」这个开关）");
            }
            return Outcome.SKIPPED;
        }
        int writeNo = Prefs.bumpAccessibilityWriteCount();
        if (writeNo > MAX_WRITES_PER_BOOT) {
            // 每一次都写回去了，还是被关掉：这就是结论，别再抢了
            EventLog.get().log(EventLog.CAT_PERM,
                    "本次开机已写回 " + MAX_WRITES_PER_BOOT + " 次，无障碍服务还是被关掉："
                            + "是 ROM 在按自己的规矩管它（不是我们漏了）",
                    "去厂商的「自启动 / 后台运行」名单里放行本应用；"
                            + "菜单名与层级随 ROM 版本变化，在设置里搜「自启动」「后台运行」「关联启动」，"
                            + "README 里有本机的对照");
            return Outcome.SKIPPED;
        }

        // 先把自己放进 ROM 的「无障碍不要自动关」名单，再写无障碍本身 —— 顺序有讲究：
        // 不然 ROM 下一步照样按「不可信来源」把我们关掉（2026-10-18 真机数据，见 joinRomSkipList）
        AccessibilityGrant.SkipListResult skip = AccessibilityGrant.joinRomSkipList(context);
        if (!skip.noSuchKey && !skip.already) {
            EventLog.get().log(EventLog.CAT_PERM,
                    skip.ok ? "已把本应用加进 ROM 的「无障碍不要自动关」名单"
                            : "没能加进 ROM 的「无障碍不要自动关」名单：" + skip.error,
                    AccessibilityGrant.KEY_TURN_OFF_SKIP_PACKAGE + " "
                            + skip.beforeCount + " 项 → " + skip.afterCount + " 项；"
                            + "开机后 " + (SystemClock.elapsedRealtime() / 1000L) + " 秒");
        }

        AccessibilityGrant.WriteResult result = AccessibilityGrant.writeEnabled(context);
        Prefs.markAccessibilitySeen(result.ok);
        if (result.ok) {
            Prefs.markAccessibilityWrote();
        }
        String reason = acrossBoot ? "上次看到它开着是这次开机之前，中间只隔了一次重启"
                : removedAfterOurWrite ? "本次开机我们写成功过，之后它又被关掉"
                : "还在开机后 " + (BOOT_WINDOW_MS / 60000L) + " 分钟内";
        if (result.ok) {
            EventLog.get().log(EventLog.CAT_PERM,
                    "补写无障碍服务成功（第 " + attempt + " 次：" + reason + "）："
                            + AccessibilityGrant.component(context),
                    "列表 " + result.before + " → " + result.after
                            + "；accessibility_enabled=" + result.masterAfter
                            + "；" + bootAge());
            return Outcome.REPAIRED;
        }
        EventLog.get().log(EventLog.CAT_PERM,
                "补写无障碍服务没留住（第 " + attempt + " 次：" + reason + "）：" + result.error,
                "列表 " + result.before + " → " + result.after
                        + "；accessibility_enabled=" + result.masterAfter
                        + "；" + bootAge());
        return Outcome.FAILED;
    }

    /**
     * 一行原始读数，放在日志的第二段（0.7.22-M6 / 43）。
     *
     * <p>0.7.21 的正文只写了「现在关着」，于是真机上分不开两种「关着」：系统设置里那条
     * {@code enabled_accessibility_services} 真的没有我们（写进去被顶掉了），还是设置里有、只是
     * AMS 没把它算成已启用（ROM 的界面读的是它自己那份记录）。这两者的出路完全不同，所以这里把
     * 三个来源分开摊出来：总开关、{@code AMS} 报的、secure 原始列表。
     *
     * <p>「上次写成功在开机后 N 秒」也在这里：它一出现、紧接着又是「现在关着」，那次「关」就是被
     * 顶掉的，不是用户关的。
     */
    private static String stateData(Context context) {
        String list = AccessibilityGrant.currentList(context);
        StringBuilder data = new StringBuilder();
        data.append(bootAge());
        data.append("；accessibility_enabled=").append(AccessibilityGrant.masterFlag(context));
        data.append("；AMS=").append(Permissions.hasAccessibility(context) ? "已启用" : "未启用");
        data.append("；列表 ").append(list == null ? "(读不到)"
                : (list.isEmpty() ? "(空)" : list));
        data.append("；ROM 不自动关名单=").append(AccessibilityGrant.skipListState(context));
        long wrote = Prefs.accessibilityWroteElapsed();
        if (wrote > 0L) {
            data.append("；上次写成功在");
            if (wrote <= SystemClock.elapsedRealtime()) {
                data.append("开机后 ").append(wrote / 1000L).append(" 秒");
            } else {
                data.append("上一次开机（开机后 ").append(wrote / 1000L).append(" 秒）");
            }
        }
        return data.toString();
    }

    /** 「开机后 N 秒」：把日志里的每一行都钉在开机时刻上，事后不用再猜先后。 */
    private static String bootAge() {
        return "开机后 " + (SystemClock.elapsedRealtime() / 1000L) + " 秒";
    }

    /**
     * 这次开机的时间（墙钟）。
     *
     * <p>按「当前时间 − 开机以来毫秒」推出来，与时区、与 {@code SharedPreferences} 里记的墙钟
     * 时间是同一个坐标系：上次观察的时刻比它早，就说明那次观察发生在上一次开机期间。
     *
     * <p>公开是给 {@code core.HealthWatch} 用的（0.7.25-M6 / 46）：它判断「常驻服务上一次是在
     * 同一次开机里被杀掉的，还是跨了一次重启」用的是同一个坐标。判据本身一个字没动。
     */
    public static long bootWallClockMs() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }
}

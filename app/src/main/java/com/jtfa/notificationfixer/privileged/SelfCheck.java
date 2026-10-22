package com.jtfa.notificationfixer.privileged;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.Prefs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 特权自检（M4 第一步）。
 *
 * <p>为什么先做自检而不是先做功能：免 root 的覆盖上限由「应用导出了什么」决定，
 * 而特权能突破到哪一步，取决于身份是 root（uid 0）还是 shell（uid 2000）——
 * AOSP 的 {@code checkComponentPermission} 只对 root / system 免掉「非导出」检查。
 * 这件事推断不出来，只能测出来，所以先把它测清楚，再决定特权预热值不值得做。
 *
 * <p>六条命令都不碰第三方应用：⑤⑥ 的靶子是本应用自己两个 {@code exported="false"} 的组件，
 * 每个靶子收了东西就写时间戳（{@link Prefs#receiverProbeAt()}），自检隔一会儿读它 ——
 * 收到 = 这条路通；③ 的靶子是本应用自己的一个专用组件（真的改一次状态再改回来）。
 * 命令原文与系统输出全部写进「诊断」日志。
 *
 * <p><b>一条教训（0.5.0-M4 的假「可用」）</b>：能力探针必须真的做一次那个操作。
 * ③ 原来拿「本应用自己」当靶子跑 {@code pm enable} —— 它本来就是启用的，系统对
 * 「新状态与当前一致」的调用会短路掉权限检查，于是命令以 0 退出、输出漂亮，
 * 却完全没证明 shell 有权改状态；直到真机上第一次真的去改一个别人的状态（当时名单页上的
 * 「解冻」按钮）才暴露 —— 那个按钮在 0.7.11-M6 / 32 已经下线，③ 这道检查留着，因为它测的是
 * 「这台机器放不放行改状态」这件事本身。
 */
public final class SelfCheck {

    /** 等靶子落地的时长：广播 / 服务启动都是异步的。 */
    private static final long PROBE_WAIT_MS = 900L;

    public interface Listener {
        void onFinished(List<String> lines);
    }

    private SelfCheck() {
    }

    /**
     * 跑完整套自检。
     *
     * <p>必须在特权线程上调用（{@link Privileged#post}）：会阻塞读命令输出，也会 sleep。
     * 结果通过 {@link Listener} 回到界面线程。
     */
    public static void runOnWorker(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        List<String> lines = new ArrayList<>();
        boolean receiverReached = false;
        boolean serviceReached = false;
        boolean stateCapable = false;
        try {
            PrivilegedBridge bridge = Privileged.active();
            if (bridge == null) {
                lines.add("特权通道：不可用");
                lines.add("原因：" + Privileged.statusText(app));
                log(app, "特权通道：不可用", Privileged.statusText(app));
                return;
            }
            lines.add("通道：" + bridge.name() + " —— " + bridge.status());

            // ① 身份：root 还是 shell，决定后面两条靶子该期待什么结果
            ShellResult identity = bridge.run("id");
            add(app, lines, "① 特权身份",
                    identity.ran ? "可用（" + firstLine(identity.out) + "）" : "不可用（" + identity.brief(80) + "）",
                    identity);

            String self = app.getPackageName();

            // ② 精确探活：替掉现在「每包冷却 + 幂等」的近似判断
            ShellResult pidof = bridge.run("pidof " + self);
            boolean alive = pidof.ran && !pidof.out.trim().isEmpty();
            if (!alive) {
                ShellResult ps = bridge.run("ps -A -o PID,NAME");
                if (ps.ran && ps.out.contains(self)) {
                    alive = true;
                    pidof = ps;
                }
            }
            add(app, lines, "② 精确探活（pidof）",
                    alive ? "可用（本应用 PID=" + firstLine(pidof.out) + "）" : "不可用（" + pidof.brief(80) + "）",
                    pidof);

            // ③ 改状态：pm enable 到底能不能改到东西（见 checkStateChange 里的教训）
            stateCapable = checkStateChange(app, bridge, lines, self);

            // ④ 冷启动加速通道：命令行 dexopt。这是免 root 下唯一可能帮到
            //    「什么都不导出」那类应用的办法（不启动进程，只是先编译好）
            ShellResult compile = bridge.run("cmd package compile -m verify -f " + self);
            boolean dexopt = compile.ran
                    && (compile.out.toLowerCase(Locale.ROOT).contains("success") || compile.exitCode == 0);
            add(app, lines, "④ 冷启动加速（cmd package compile）",
                    dexopt ? "可用（对自身做了一次 verify 编译：" + compile.brief(50) + "）"
                            : "不可用（" + compile.brief(80) + "）",
                    compile);

            // ⑤ 非导出广播接收器：shell uid 预期会被挡，root 预期能过
            String receiver = self + "/" + SelfCheckReceiver.class.getName();
            Prefs.clearProbeMarkers();
            ShellResult broadcast = bridge.run(
                    "am broadcast -n " + receiver + " -a " + SelfCheckReceiver.ACTION);
            sleep(PROBE_WAIT_MS);
            receiverReached = Prefs.receiverProbeAt() > 0;
            add(app, lines, "⑤ 非导出接收器",
                    receiverReached ? "可达 —— 广播真的把非导出接收器叫起来了"
                            : "不可达 —— " + rejectHint(broadcast),
                    broadcast);

            // ⑥ 非导出 Service：广播与服务走的是两套放行规则，所以两条都测
            String service = self + "/" + SelfCheckService.class.getName();
            ShellResult start = bridge.run("am start-service -n " + service);
            if (start.ran && start.out.contains("nknown command")) {
                start = bridge.run("am startservice -n " + service);
            }
            sleep(PROBE_WAIT_MS);
            serviceReached = Prefs.serviceProbeAt() > 0;
            add(app, lines, "⑥ 非导出 Service",
                    serviceReached ? "可达 —— start-service 真的把非导出服务启动了"
                            : "不可达 —— " + rejectHint(start),
                    start);

            lines.add(summary(receiverReached || serviceReached, stateCapable));
            log(app, "特权自检结论", lines.get(lines.size() - 1));
        } catch (Throwable t) {
            lines.add("自检异常：" + t);
            log(app, "特权自检异常", String.valueOf(t));
        } finally {
            Privileged.onMain(() -> listener.onFinished(lines));
        }
    }

    /**
     * ③ 改状态（{@code pm enable}）：真的把状态改一次，再改回来，看系统放不放行。
     *
     * <p>只动本应用自己的一个专用探针组件（{@link SelfCheckStateProbe}），不碰任何第三方应用。
     * 就算「启用」那一步被拒，也只是那个探针组件停用着，应用本身毫无影响。
     *
     * @return 停用与启用都真的改到了状态才算 true
     */
    private static boolean checkStateChange(Context app, PrivilegedBridge bridge,
                                            List<String> lines, String self) {
        ComponentName probe = new ComponentName(self, SelfCheckStateProbe.class.getName());
        String shortName = probe.flattenToShortString();
        String before = componentState(app, probe);

        ShellResult disable = bridge.run("pm disable " + shortName);
        String afterDisable = componentState(app, probe);
        ShellResult enable = bridge.run("pm enable " + shortName);
        String afterEnable = componentState(app, probe);

        log(app, "特权自检 ③ 停用探针组件（" + shortName + "）", disable);
        log(app, "特权自检 ③ 启用探针组件（" + shortName + "）", enable);

        String conclusion;
        boolean capable;
        if (!"停用".equals(afterDisable)) {
            capable = false;
            conclusion = "不可用 —— shell 在这台机器上改不了组件状态（" + compact(disable) + "）；"
                    + "应用级的 pm enable 走同一道权限检查，所以同样不行（需要 root）";
        } else if (!"启用".equals(afterEnable)) {
            capable = false;
            conclusion = "只成功一半 —— 能停用但启用被拒（" + compact(enable) + "）；"
                    + "应用级的 pm enable 也一样被拒，且这个探针组件会一直停用着（不影响应用本身）";
        } else {
            capable = true;
            conclusion = "可用 —— 状态真的被改了又改回来（停用 → 启用）";
        }

        lines.add("③ 改状态（pm enable）：" + conclusion);
        log(app, "特权自检 ③ 改状态（pm enable）：" + conclusion,
                "探针组件 " + shortName + "：停用前=" + before + " 停用后=" + afterDisable + " 启用后=" + afterEnable);
        return capable;
    }

    /** 组件当前的启用状态，读我们自己应用的 PackageManager（binder 直读，不经过缓存）。 */
    private static String componentState(Context app, ComponentName component) {
        switch (app.getPackageManager().getComponentEnabledSetting(component)) {
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return "停用";
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
            default:
                return "启用";
        }
    }

    /** 从命令输出里挑最有信息量的一句（结论里要短，原始全文在日志里）。 */
    private static String compact(ShellResult result) {
        String line = firstLine(result.out);
        return "（无输出）".equals(line) ? result.brief(80) : line;
    }

    private static String summary(boolean componentsReachable, boolean stateCapable) {
        if (componentsReachable) {
            return "结论：当前身份能拉起非导出组件 —— 「什么都不导出」的应用（本机实测车来了这类）也能预热。";
        }
        return "结论：当前身份拉不起非导出组件（多半是 shell uid 2000，只有 root 才免掉这道检查）。"
                + (stateCapable
                ? "但 ①②③④ 仍然可用：精确探活、改状态、给下次冷启动先编译好（④）。"
                : "但 ①②④ 仍然可用：精确探活、给下次冷启动先编译好（④）；"
                + "③ 改状态在这台机器上被拒，需要 root。");
    }

    private static String rejectHint(ShellResult result) {
        if (!result.ran) {
            return result.out;
        }
        String out = result.out.toLowerCase(Locale.ROOT);
        if (out.contains("permission denial") || out.contains("not exported")
                || out.contains("securityexception") || out.contains("permissionexception")) {
            return "系统拒绝投递给非导出组件（" + firstLine(result.out) + "）";
        }
        return "命令执行完了但没有回执，投递被系统挡掉了（" + firstLine(result.out) + "）";
    }

    private static void add(Context app, List<String> lines, String title, String conclusion,
                            ShellResult result) {
        lines.add(title + "：" + conclusion);
        log(app, "特权自检 " + title + "：" + conclusion, result);
    }

    private static void log(Context app, String text, ShellResult result) {
        log(app, text, "命令: " + result.command + "  →  " + result.brief(400));
    }

    private static void log(Context app, String text, String data) {
        EventLog.get().log(EventLog.CAT_DIAG, text, data == null ? null : data.replace('\n', ' '));
    }

    private static String firstLine(String text) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 120 ? trimmed.substring(0, 120) + "…" : trimmed;
            }
        }
        return "（无输出）";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

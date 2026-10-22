package com.jtfa.notificationfixer;

import android.app.Application;
import android.os.Process;

import com.jtfa.notificationfixer.core.AccessibilityAutostart;
import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.Prefs;
import com.jtfa.notificationfixer.detect.TriggerBus;
import com.jtfa.notificationfixer.detect.TriggerEvent;
import com.jtfa.notificationfixer.privileged.Privileged;
import com.jtfa.notificationfixer.warm.WarmEngine;

import java.util.List;

/**
 * 应用入口。
 *
 * <p>这里在 TriggerBus 上挂两个订阅者：唤醒引擎负责动手，日志记录器负责留证。
 * 检测层只发事件，完全不知道谁在消费。
 */
public class App extends Application {

    private static App sInstance;

    public static App get() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Prefs.init(this);
        EventLog.init(this);

        // 无障碍的补写（0.7.21-M6 / 42）：进程因任何原因起来都先看一眼。开机广播、通知监听被系统
        // bind、用户打开应用、常驻服务被重新拉起，都会走到这里 —— 只在这一眼里「该写才写」，
        // 判据见 AccessibilityAutostart。
        AccessibilityAutostart.poll(this, 1);

        WarmEngine.install(this);
        TriggerBus.addListener(this::logTrigger);

        // Shizuku / root 特权通道（M4）：挂上 binder 监听，收到没收到都留一行诊断
        Privileged.install(this);

        EventLog.get().log(EventLog.CAT_SERVICE, "应用进程启动 (pid=" + Process.myPid() + ")");
    }

    /** 把触发事件连同目标应用一起记下来，便于按日志核对每一步。 */
    private void logTrigger(TriggerEvent event) {
        List<String> targets = event.targetPackages;
        StringBuilder text = new StringBuilder();
        text.append("触发 ").append(event.type.label());
        if (event.sourcePackage != null) {
            text.append(" ← ").append(event.sourcePackage);
        }
        if (event.detail != null) {
            text.append("（").append(event.detail).append("）");
        }

        String data = null;
        if (!targets.isEmpty()) {
            StringBuilder list = new StringBuilder();
            int shown = 0;
            for (String target : targets) {
                if (shown == 5) {
                    list.append(" 等 ").append(targets.size()).append(" 个");
                    break;
                }
                if (shown > 0) {
                    list.append(", ");
                }
                list.append(target);
                shown++;
            }
            data = "候选目标 " + targets.size() + " 个：" + list;
        }
        EventLog.get().log(EventLog.CAT_TRIGGER, text.toString(), data);
    }
}

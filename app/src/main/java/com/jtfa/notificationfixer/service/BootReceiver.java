package com.jtfa.notificationfixer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.jtfa.notificationfixer.core.AccessibilityAutostart;
import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.Prefs;

/**
 * 开机后重新拉起监听服务，需要时把无障碍服务也写回来。
 *
 * <p>0.7.19-M6 / 40 起先看「开机自启」这个开关（默认开，也就是以前的行为）；
 * 关掉时这里什么都不做，并且照样写一行日志 —— 「机器重启后为什么没有预热」这类问题，
 * 有这一行就能直接看出是开关关着，还是开机广播根本没送到（后者要去看 ROM 的自启动名单）。
 *
 * <p>0.7.20-M6 / 41 起，开机这一趟还要管无障碍：部分 ROM 会在重启后把第三方无障碍服务关掉，
 * 所以我们读一次、必要时用 {@code WRITE_SECURE_SETTINGS} 写回系统设置
 * （见 {@link AccessibilityAutostart}）。0.7.21-M6 / 42 起这一眼挪到了 {@code App.onCreate}：
 * 进程因为<b>任何</b>原因起来（开机广播、通知监听被系统 bind、用户打开应用、服务被重新拉起）
 * 都会先看一眼，开机广播被 ROM 的自启动名单挡住时也还有救。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!Prefs.isBootAutostart()) {
            EventLog.get().log(EventLog.CAT_SERVICE,
                    "收到开机完成广播，但「开机自启」是关的：不拉起监听服务");
            return;
        }
        EventLog.get().log(EventLog.CAT_SERVICE, "收到开机完成广播，拉起监听服务");
        AccessibilityAutostart.poll(context, 1);
        KeepAliveService.start(context);
    }
}

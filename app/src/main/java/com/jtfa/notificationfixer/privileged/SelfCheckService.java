package com.jtfa.notificationfixer.privileged;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.Prefs;

/**
 * 特权自检用的第二个靶子：一个 {@code exported="false"} 的 Service。
 *
 * <p>与 {@link SelfCheckReceiver} 同一个问题：非导出组件能不能被 shell / root 拉起来。
 * 接收器走的是广播路径、Service 走的是 start-service 路径，两者的放行规则并不完全一样，
 * 所以两条都测。
 */
public final class SelfCheckService extends Service {

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Prefs.markServiceProbe();
        EventLog.get().log(EventLog.CAT_DIAG, "自检服务被启动：非导出 Service 被拉起来了",
                "说明 shell / root 能绕过导出检查，这条预热路可用");
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}

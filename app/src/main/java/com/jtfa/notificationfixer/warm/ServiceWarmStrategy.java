package com.jtfa.notificationfixer.warm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.jtfa.notificationfixer.probe.ComponentInventory;

/**
 * 兜底预热手段：绑定目标应用的导出 Service。
 *
 * <p>`BIND_AUTO_CREATE` 会创建 Service 实例，因此目标进程必须启动；
 * 加上 `BIND_NOT_FOREGROUND` 保证我们不会把目标进程提升成前台服务进程
 * （那样会长期占资源，不是「预热」而是「驻留」了）。
 *
 * <p>绑定后约 1.5 秒解绑：这段时间足够进程完成启动并把状态稳定在 cached，
 * 解绑后又不会被我们一直拴住。
 *
 * <p>探活已确认进程在运行时，绑定失败只读成「幂等调用未完成」，不再解释成「进程起不来」；
 * 见 {@link #warm(String, ComponentName, long, boolean)}。
 */
public final class ServiceWarmStrategy {

    private static final long UNBIND_DELAY_MS = 1500L;

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private final Context mContext;

    public ServiceWarmStrategy(Context context) {
        mContext = context.getApplicationContext();
    }

    public WarmResult warm(String packageName, ComponentName component, long startUptimeMs,
                           boolean processWasRunning) {
        if (component == null) {
            return WarmResult.failed(packageName, "Service", 0L, "组件为空");
        }
        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // 只需要进程起来，不需要真正用这个 Service
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // 目标进程被回收，与我们无关
            }
        };
        try {
            Intent intent = new Intent();
            intent.setComponent(component);
            boolean bound = mContext.bindService(
                    intent, connection, Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND);
            long elapsed = SystemClock.uptimeMillis() - startUptimeMs;
            if (!bound) {
                return WarmResult.failed(packageName, "Service", elapsed,
                        "bindService 被拒：" + ComponentInventory.explainServiceFailure(
                                mContext, packageName, component, processWasRunning));
            }
            sMain.postDelayed(() -> {
                try {
                    mContext.unbindService(connection);
                } catch (Exception ignored) {
                    // 目标进程已退出时解绑会失败，忽略
                }
            }, UNBIND_DELAY_MS);
            return WarmResult.warmed(packageName, "Service", elapsed,
                    component.flattenToShortString());
        } catch (Throwable t) {
            long elapsed = SystemClock.uptimeMillis() - startUptimeMs;
            return WarmResult.failed(packageName, "Service", elapsed,
                    ProviderWarmStrategy.brief(t));
        }
    }
}

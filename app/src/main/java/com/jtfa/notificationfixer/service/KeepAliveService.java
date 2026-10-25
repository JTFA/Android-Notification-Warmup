package com.jtfa.notificationfixer.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.jtfa.notificationfixer.core.AccessibilityAutostart;
import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.HealthWatch;
import com.jtfa.notificationfixer.detect.NotificationAccessService;
import com.jtfa.notificationfixer.detect.ScreenStateReceiver;
import com.jtfa.notificationfixer.probe.StateWatch;

/**
 * 常驻前台服务。
 *
 * <p>它做五件事：一是让监听进程不被系统随手回收，二是承载只能动态注册的
 * 屏幕状态广播接收者（锁屏点亮触发点完全依赖它），三是给状态位采样器提供
 * 一个活着的进程（采样是纯只读的诊断工具，开关默认关闭），四是看无障碍服务还在不在
 * —— 开机窗口内每 30 秒一次，之后每 15 分钟一次（见 {@link AccessibilityAutostart}），
 * 五是顺带检查通知监听器，断线时请求系统重绑。
 *
 * <p>从 0.7.25-M6 / 46 起，它还会在每次启动时按四枚「什么算服务异常」的开关评估一次、必要时发一条
 * 异常提醒（见 {@code core.HealthWatch}）；那条提醒不影响服务本身的运行方式 —— 它始终是前台服务。
 *
 * <p>0.7.27-M6 / 48 撤掉了 46 那三个「通知档位」：那条常驻通知回到一直显示（挂重要度最低的
 * {@link AppNotifications#CHANNEL_MONITOR}），因为静默渠道在真机上做不到「不显示」。
 */
public class KeepAliveService extends Service {

    /**
     * 平时看无障碍的间隔（0.7.20-M6 / 41）。
     *
     * <p>「开机补写」的判据是「上次看到它开着」，所以那条观察必须新鲜：用户自己关掉的
     * （我们看得到）不该在下次开机被写回来。15 分钟与 {@code StateWatch} 同频，但两者互不依赖
     * （那个采样器的开关默认是关的）。
     */
    private static final long ACCESSIBILITY_TICK_MS = 15L * 60L * 1000L;

    /**
     * 开机窗口内看无障碍的间隔（0.7.21-M6 / 42）。
     *
     * <p>ROM 对无障碍的清理有时晚于开机广播：我们开机写进去的那一下它还没动手，几十秒后它才把
     * 服务关掉。所以窗口内看勤一点（{@link AccessibilityAutostart#poll} 只在真需要写的时候才写，
     * 看这一眼本身只是几次只读 binder）。
     */
    private static final long BOOT_WINDOW_TICK_MS = 30_000L;

    private static volatile boolean sRunning;

    private final ScreenStateReceiver mScreenStateReceiver = new ScreenStateReceiver();
    private boolean mReceiverRegistered;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** 本服务实例启动以来看了几次无障碍（只用来写日志与窗口内的收手上限）。 */
    private int mAccessibilityTries;

    private final Runnable mAccessibilityTick = new Runnable() {
        @Override
        public void run() {
            mAccessibilityTries++;
            AccessibilityAutostart.poll(KeepAliveService.this, mAccessibilityTries);
            // 通知监听器被系统解绑后不会自己回来；常驻服务每次 tick 顺带补一次重绑请求。
            NotificationAccessService.ensureConnected(KeepAliveService.this);
            // 每看一眼无障碍就重新评估一次「监听能力」（0.7.25-M6 / 46）：无障碍刚被 ROM 关掉、
            // 或刚被我们补写回来，异常提醒就在同一秒跟上，不用等下一次连接回调。
            HealthWatch.evaluate(KeepAliveService.this);
            boolean inWindow = AccessibilityAutostart.inBootWindow();
            if (inWindow && mAccessibilityTries < AccessibilityAutostart.BOOT_WINDOW_MAX_TRIES) {
                mHandler.postDelayed(this, BOOT_WINDOW_TICK_MS);
                return;
            }
            mHandler.postDelayed(this, ACCESSIBILITY_TICK_MS);
        }
    };

    public static boolean isRunning() {
        return sRunning;
    }

    /**
     * 启动服务。在后台调用（例如开机广播里）可能被系统拒绝，
     * 这里吞掉异常并留下日志，不影响调用方。
     */
    public static void start(Context context) {
        Context appContext = context.getApplicationContext();
        try {
            ContextCompat.startForegroundService(
                    appContext, new Intent(appContext, KeepAliveService.class));
        } catch (Exception e) {
            EventLog.get().log(EventLog.CAT_SERVICE, "启动监听服务失败：" + e);
        }
    }

    public static void stop(Context context) {
        Context appContext = context.getApplicationContext();
        appContext.stopService(new Intent(appContext, KeepAliveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppNotifications.ensureChannels(this);
        startForegroundInternal();
        sRunning = true;
        registerScreenStateReceiver();
        StateWatch.start(this);
        NotificationAccessService.ensureConnected(this);
        mHandler.post(mAccessibilityTick);
        EventLog.get().log(EventLog.CAT_SERVICE, "监听服务已启动");
        // 「上一次是不是被杀掉的」在这一眼里认出来（见 core.HealthWatch），顺带评估一次异常项
        HealthWatch.onServiceStart(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        // 走了 onDestroy 就是「正常结束」：下一次启动不把这次当成「被杀」（0.7.25-M6 / 46）
        HealthWatch.onServiceStopCleanly();
        StateWatch.stop();
        mHandler.removeCallbacks(mAccessibilityTick);
        unregisterScreenStateReceiver();
        EventLog.get().log(EventLog.CAT_SERVICE, "监听服务已停止");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // 0.7.23-M6 / 44 起，「抢不过 ROM」的结论行搬到 AccessibilityAutostart.poll 里 ——
    // 那里才知道是「写了几次被顶掉几次」还是「窗口里看满了还在关」。

    /**
     * 把那条常驻通知挂上前台。
     *
     * <p>通知与渠道都交给 {@link AppNotifications}：内容与 0.7.24 及以前一致，挂重要度最低的
     * {@link AppNotifications#CHANNEL_MONITOR}（0.7.27-M6 / 48 起只有这一条路 —— 46 那三个档位
     * 已撤掉，静默渠道在真机上是可见的，档位换不来「不显示」）。
     */
    private void startForegroundInternal() {
        Notification notification = AppNotifications.buildMonitor(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(AppNotifications.ID_MONITOR, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(AppNotifications.ID_MONITOR, notification);
        }
    }

    private void registerScreenStateReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mScreenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        mReceiverRegistered = true;
        // 动态接收器只收变化；服务刚起来时先把当前显示状态读进来，避免第一条通知
        // 因为还没收到 ACTION_SCREEN_ON/OFF 而沿用默认值。
        ScreenStateReceiver.refresh(this);
    }

    private void unregisterScreenStateReceiver() {
        if (!mReceiverRegistered) {
            return;
        }
        mReceiverRegistered = false;
        try {
            unregisterReceiver(mScreenStateReceiver);
        } catch (Exception ignored) {
            // 注册未完成时被销毁，忽略
        }
    }
}

package com.jtfa.notificationfixer.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.jtfa.notificationfixer.R;
import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.ForegroundAppTracker;
import com.jtfa.notificationfixer.core.Permissions;
import com.jtfa.notificationfixer.core.Prefs;
import com.jtfa.notificationfixer.detect.NotificationAccessService;
import com.jtfa.notificationfixer.detect.ShadeAccessibilityService;
import com.jtfa.notificationfixer.service.KeepAliveService;
import com.jtfa.notificationfixer.warm.WarmEngine;
import com.jtfa.notificationfixer.warm.WarmResult;

/**
 * 权限与运行状态。可见时每秒刷新一次，权限变化写进「权限」日志。
 *
 * <p>0.7.29-M6 / 50：权限四格（2 × 2）、运行状态五格（2 + 2 + 1），都改成
 * {@link StatusBlockView}；「无障碍服务连接」那一格按用户要求去掉 —— 那条信息
 * 仍在日志里（连接状态一翻转就写一行），见 {@link #logPermissionTransitions}。
 * 0.7.53-M6 / 74 起去掉「预热覆盖」与「现场验证」两段，页面只剩权限、运行状态和
 * 「重启监听服务」。
 */
public class StatusFragment extends Fragment {

    private static final long REFRESH_INTERVAL_MS = 1000L;

    // 权限：2 × 2 四格
    private StatusBlockView mBlockListener;
    private StatusBlockView mBlockAccessibility;
    private StatusBlockView mBlockPostNotifications;
    private StatusBlockView mBlockBattery;

    // 运行状态：2 + 2 + 1 五格
    private StatusBlockView mBlockService;
    private StatusBlockView mBlockNls;
    private StatusBlockView mBlockShade;
    private StatusBlockView mBlockForeground;
    private StatusBlockView mBlockWarm;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            refresh();
            mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private boolean mLastNotificationAccess = true;
    private boolean mLastAccessibility = true;
    private boolean mLastPostNotifications = true;
    private boolean mLastBattery = true;
    private boolean mLastServiceRunning = true;
    private boolean mLastListenerConnected = true;
    private boolean mLastA11yConnected = true;

    private int mTickCount;
    private int mCachedActiveNotificationCount;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_status, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        setupPermissionBlocks(view);
        setupRuntimeBlocks(view);

        view.findViewById(R.id.btn_restart_service).setOnClickListener(v -> {
            Toast.makeText(requireContext(), R.string.toast_service_restarting, Toast.LENGTH_SHORT)
                    .show();
            KeepAliveService.stop(requireContext());
            KeepAliveService.start(requireContext());
            // 通知监听器是系统绑定的另一个服务，重启常驻服务不会把它接回来；这里强制补一次重绑请求。
            NotificationAccessService.requestRebindNow(requireContext());
        });

        refresh();
    }

    /**
     * 权限四格：整格可点 = 去对应的系统设置页，「去授权」只是把这件事说出来的一行提示
     * （已授予时收起，但格子照样能点开系统页 —— 想改回去随时可以）。
     */
    private void setupPermissionBlocks(View view) {
        mBlockListener = view.findViewById(R.id.block_listener);
        mBlockListener.setTitle(getString(R.string.perm_listener_title));
        mBlockListener.setDetail(getString(R.string.perm_listener_desc));
        mBlockListener.setAction(getString(R.string.action_grant));
        mBlockListener.setOnClickListener(
                v -> openSettings(Permissions.notificationListenerSettings()));

        mBlockAccessibility = view.findViewById(R.id.block_accessibility);
        mBlockAccessibility.setTitle(getString(R.string.perm_accessibility_title));
        mBlockAccessibility.setDetail(getString(R.string.perm_accessibility_desc));
        mBlockAccessibility.setAction(getString(R.string.action_grant));
        mBlockAccessibility.setOnClickListener(
                v -> openSettings(Permissions.accessibilitySettings()));

        mBlockPostNotifications = view.findViewById(R.id.block_post_notifications);
        mBlockPostNotifications.setTitle(getString(R.string.perm_post_notifications_title));
        mBlockPostNotifications.setDetail(getString(R.string.perm_post_notifications_desc));
        mBlockPostNotifications.setAction(getString(R.string.action_grant));
        mBlockPostNotifications.setOnClickListener(
                v -> openSettings(Permissions.appNotificationSettings(requireContext())));

        mBlockBattery = view.findViewById(R.id.block_battery);
        mBlockBattery.setTitle(getString(R.string.perm_battery_title));
        mBlockBattery.setDetail(getString(R.string.perm_battery_desc));
        mBlockBattery.setNote(getString(R.string.perm_battery_advice));
        mBlockBattery.setAction(getString(R.string.action_grant));
        mBlockBattery.setOnClickListener(
                v -> openSettings(Permissions.batteryOptimizationSettings(requireContext())));
    }

    /** 运行状态五格：标题是小字（值才是主角），值按状态取色，补充说明放小字那行。 */
    private void setupRuntimeBlocks(View view) {
        mBlockService = view.findViewById(R.id.block_service);
        mBlockService.setCaption(getString(R.string.row_service_title));

        mBlockNls = view.findViewById(R.id.block_nls);
        mBlockNls.setCaption(getString(R.string.row_nls_title));

        mBlockShade = view.findViewById(R.id.block_shade);
        mBlockShade.setCaption(getString(R.string.row_shade_title));

        mBlockForeground = view.findViewById(R.id.block_foreground);
        mBlockForeground.setCaption(getString(R.string.row_foreground_title));

        mBlockWarm = view.findViewById(R.id.block_warm);
        mBlockWarm.setCaption(getString(R.string.row_warm_title));
    }

    @Override
    public void onResume() {
        super.onResume();
        // 打开状态页时顺手补一次：如果昨晚被 ROM 解绑了，不必等 15 分钟后的 tick。
        NotificationAccessService.ensureConnected(requireContext());
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    private void refresh() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        mTickCount++;

        boolean notificationAccess = Permissions.hasNotificationAccess(context);
        boolean accessibility = Permissions.hasAccessibility(context);
        boolean postNotifications = Permissions.hasPostNotifications(context);
        boolean battery = Permissions.ignoresBatteryOptimizations(context);

        mBlockListener.setValue(
                getString(notificationAccess ? R.string.value_granted : R.string.value_denied),
                notificationAccess ? StatusLevel.OK : StatusLevel.BAD);
        mBlockListener.setActionVisible(!notificationAccess);
        mBlockAccessibility.setValue(
                getString(accessibility ? R.string.value_granted : R.string.value_denied),
                accessibility ? StatusLevel.OK : StatusLevel.BAD);
        mBlockAccessibility.setActionVisible(!accessibility);
        mBlockPostNotifications.setValue(
                getString(postNotifications ? R.string.value_granted : R.string.value_denied),
                postNotifications ? StatusLevel.OK : StatusLevel.BAD);
        mBlockPostNotifications.setActionVisible(!postNotifications);
        mBlockBattery.setValue(
                getString(battery ? R.string.value_granted : R.string.value_denied),
                battery ? StatusLevel.OK : StatusLevel.WARN);
        mBlockBattery.setActionVisible(!battery);

        boolean serviceRunning = KeepAliveService.isRunning();
        mBlockService.setValue(
                getString(serviceRunning ? R.string.value_running : R.string.value_stopped),
                serviceRunning ? StatusLevel.OK : StatusLevel.BAD);

        boolean listenerConnected = NotificationAccessService.isConnected();
        if (listenerConnected) {
            if (mTickCount % 2 == 0) {
                mCachedActiveNotificationCount = NotificationAccessService.activeNotifications().size();
            }
            mBlockNls.setValue(getString(R.string.value_connected), StatusLevel.OK);
            mBlockNls.setDetail(getString(R.string.row_nls_detail, mCachedActiveNotificationCount));
        } else {
            mBlockNls.setValue(getString(R.string.value_disconnected), StatusLevel.BAD);
            mBlockNls.setDetail(null);
        }

        // 无障碍服务连接状态只写日志（0.7.29-M6 / 50 起界面上不再单列一格）
        boolean a11yConnected = ShadeAccessibilityService.isConnected();

        if (!ShadeAccessibilityService.hasEvaluated()) {
            mBlockShade.setValue(getString(R.string.shade_state_unknown), StatusLevel.NEUTRAL);
            mBlockShade.setDetail(getString(R.string.shade_state_unknown_detail));
        } else {
            boolean open = ShadeAccessibilityService.isShadeOpen();
            mBlockShade.setValue(
                    getString(open ? R.string.shade_state_open : R.string.shade_state_closed),
                    StatusLevel.NEUTRAL);
            mBlockShade.setDetail(null);
        }

        String foreground = ForegroundAppTracker.get();
        if (foreground == null) {
            mBlockForeground.setValue(getString(R.string.value_unknown), StatusLevel.NEUTRAL);
            mBlockForeground.setDetail(null);
        } else {
            mBlockForeground.setValue(foreground, StatusLevel.NEUTRAL);
            mBlockForeground.setDetail(
                    getString(R.string.row_foreground_age, ForegroundAppTracker.ageMs() / 1000L));
        }

        if (!Prefs.isWarmEnabled()) {
            mBlockWarm.setValue(getString(R.string.row_warm_off), StatusLevel.WARN);
        } else {
            WarmResult last = WarmEngine.get() == null ? null : WarmEngine.get().lastResult();
            if (last == null) {
                mBlockWarm.setValue(getString(R.string.row_warm_idle), StatusLevel.OK);
            } else {
                mBlockWarm.setValue(getString(R.string.row_warm_last, last.describeWithEvidence()),
                        last.outcome == WarmResult.Outcome.WARMED
                                ? StatusLevel.OK : StatusLevel.NEUTRAL);
            }
        }
        // 名单与数量上限：白名单空着时「什么都没发生」要在这里被看见
        mBlockWarm.setDetail(WarmText.listSummary(context));

        logPermissionTransitions(notificationAccess, accessibility, postNotifications, battery,
                serviceRunning, listenerConnected, a11yConnected);
    }

    private void logPermissionTransitions(boolean notificationAccess,
                                          boolean accessibility,
                                          boolean postNotifications,
                                          boolean battery,
                                          boolean serviceRunning,
                                          boolean listenerConnected,
                                          boolean a11yConnected) {
        if (notificationAccess != mLastNotificationAccess) {
            mLastNotificationAccess = notificationAccess;
            logState("通知使用权", notificationAccess);
        }
        if (accessibility != mLastAccessibility) {
            mLastAccessibility = accessibility;
            logState("无障碍服务", accessibility);
        }
        if (postNotifications != mLastPostNotifications) {
            mLastPostNotifications = postNotifications;
            logState("通知权限", postNotifications);
        }
        if (battery != mLastBattery) {
            mLastBattery = battery;
            logState("电池优化白名单", battery);
        }
        if (serviceRunning != mLastServiceRunning) {
            mLastServiceRunning = serviceRunning;
            EventLog.get().log(EventLog.CAT_SERVICE,
                    "监听服务 → " + (serviceRunning ? "运行中" : "已停止"));
        }
        if (listenerConnected != mLastListenerConnected) {
            mLastListenerConnected = listenerConnected;
            EventLog.get().log(EventLog.CAT_SERVICE,
                    "通知监听器 → " + (listenerConnected ? "已连接" : "未连接"));
        }
        if (a11yConnected != mLastA11yConnected) {
            mLastA11yConnected = a11yConnected;
            EventLog.get().log(EventLog.CAT_SERVICE,
                    "无障碍服务连接 → " + (a11yConnected ? "已连接" : "未连接"));
        }
    }

    private void logState(String what, boolean granted) {
        EventLog.get().log(EventLog.CAT_PERM,
                what + " → " + getString(granted ? R.string.value_granted : R.string.value_denied));
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            EventLog.get().log(EventLog.CAT_PERM, "打开系统设置失败：" + e);
        }
    }
}

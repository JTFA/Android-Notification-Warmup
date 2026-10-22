package com.jtfa.notificationfixer.core;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

/** 权限检测与跳转入口。 */
public final class Permissions {

    private Permissions() {
    }

    /** 是否已授予通知使用权。 */
    public static boolean hasNotificationAccess(Context context) {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.getPackageName());
    }

    /** 无障碍服务是否已启用（只看系统设置，不代表服务当前已连接）。 */
    public static boolean hasAccessibility(Context context) {
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        List<AccessibilityServiceInfo> enabled =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            if (context.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    /** 通知权限（minSdk 33，无需再做版本判断）。 */
    public static boolean hasPostNotifications(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 是否已在电池优化白名单内。 */
    public static boolean ignoresBatteryOptimizations(Context context) {
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static Intent notificationListenerSettings() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }

    public static Intent accessibilitySettings() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    /**
     * 跳转到「忽略电池优化」申请页。
     *
     * <p>该权限受 Play 政策限制，但本应用只做侧载分发，且监听服务被杀会直接
     * 导致锁屏触发点失效，所以必须保留。
     */
    @SuppressLint("BatteryLife")
    public static Intent batteryOptimizationSettings(Context context) {
        return new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.getPackageName()));
    }

    public static Intent appNotificationSettings(Context context) {
        return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
    }
}

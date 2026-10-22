package com.jtfa.notificationfixer.detect;

import android.app.Notification;
import android.content.Context;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从活动通知里挑出「通知面板里看得见、可能去点」的目标应用。
 *
 * <p>面板里点到的可能是私密通知（显示为「内容已隐藏」），点开同样要启动应用，所以这里不按可见性
 * 过滤；分组摘要点开只是展开分组，不会启动应用，所以排除。
 *
 * <p>锁屏那条路<b>不在这里</b>（0.7.33-M6 / 54）：它要先把「锁屏上真的画了几条」读出来
 * （见 {@link LockscreenWatch}），读不到才退回按系统设置估算 —— 那份估算与这里的规则相似但
 * 结果不同（还要按画出来的条数截断），所以留在 {@code ScreenStateReceiver} 里，不在这里
 * 复制一份。原来这里还有一个 {@code visibleOnLockscreen()}，那是 54 之前的旧写法、无人调用，
 * 一并删掉，免得以后改一处漏一处。
 */
public final class NotificationTargets {

    private NotificationTargets() {
    }

    /** 通知面板里的候选应用，最近发布的在前。 */
    public static List<String> visibleInShade(Context context) {
        List<StatusBarNotification> active = NotificationAccessService.activeNotifications();
        if (active.isEmpty()) {
            return Collections.emptyList();
        }
        List<StatusBarNotification> sorted = new ArrayList<>(active);
        Collections.sort(sorted, new Comparator<StatusBarNotification>() {
            @Override
            public int compare(StatusBarNotification left, StatusBarNotification right) {
                return Long.compare(right.getPostTime(), left.getPostTime());
            }
        });

        String self = context.getPackageName();
        Set<String> packages = new LinkedHashSet<>();
        for (StatusBarNotification sbn : sorted) {
            if (sbn == null || sbn.getPackageName() == null) {
                continue;
            }
            Notification notification = sbn.getNotification();
            if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                continue;
            }
            String packageName = sbn.getPackageName();
            if (self.equals(packageName)) {
                continue;
            }
            packages.add(packageName);
        }
        return new ArrayList<>(packages);
    }
}

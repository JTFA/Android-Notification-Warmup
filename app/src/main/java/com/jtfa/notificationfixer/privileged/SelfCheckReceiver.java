package com.jtfa.notificationfixer.privileged;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.Prefs;

/**
 * 特权自检用的靶子：一个 {@code exported="false"} 的广播接收器。
 *
 * <p>它存在的唯一目的是回答一个问题：shell / root 身份能不能绕过「非导出」这道门。
 * 拿自己的应用当靶子，既不碰第三方应用，结论又和第三方应用完全一样
 * （uid 不同、组件非导出，系统判据一模一样）。
 *
 * <p>收到就写一个时间戳，自检那边隔一会儿读它 —— 收到 = 这条路通。
 */
public final class SelfCheckReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.jtfa.notificationfixer.SELF_CHECK";

    @Override
    public void onReceive(@NonNull Context context, Intent intent) {
        Prefs.markReceiverProbe();
        EventLog.get().log(EventLog.CAT_DIAG, "自检广播已送达：非导出接收器被拉起来了",
                "说明 shell / root 能绕过导出检查，这条预热路可用");
    }
}

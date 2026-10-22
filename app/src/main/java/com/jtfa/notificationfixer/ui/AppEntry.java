package com.jtfa.notificationfixer.ui;

import android.graphics.drawable.Drawable;

import com.jtfa.notificationfixer.probe.WarmTarget;

/** 名单页里的一行应用。 */
public final class AppEntry {

    public final String packageName;
    public final String label;
    public final Drawable icon;

    /**
     * 预热方式结论。在后台线程跟着列表一起探好，避免在滚动时逐行做
     * PackageManager 调用（那是 binder 往返，滚动时会掉帧）。
     */
    public final WarmTarget target;

    public AppEntry(String packageName, String label, Drawable icon, WarmTarget target) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
        this.target = target;
    }
}

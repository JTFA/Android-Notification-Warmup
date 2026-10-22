package com.jtfa.notificationfixer.core;

import java.util.Set;

/**
 * 名单过滤。
 *
 * <p>白名单模式（默认）：只预热被勾选的应用 —— 一个都没勾时谁都不预热，这是刻意的保守默认。
 * 黑名单模式：预热所有应用，被勾选的除外。
 */
public final class AppFilter {

    private AppFilter() {
    }

    public static boolean shouldWarm(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        Set<String> selected = Prefs.getSelectedPackages();
        return Prefs.isWhitelistMode() ? selected.contains(packageName) : !selected.contains(packageName);
    }
}

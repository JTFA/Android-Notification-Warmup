package com.jtfa.notificationfixer.ui;

import android.content.Context;

import com.jtfa.notificationfixer.R;
import com.jtfa.notificationfixer.core.Prefs;

/**
 * 预热设置项在界面上的说法。
 *
 * <p>设置页与状态页念的是同一个值（单次数量上限、名单大小），文案放一处，
 * 免得改了一个忘另一个。{@link Prefs} 里不放中文界面文字，它只存值。
 */
public final class WarmText {

    private WarmText() {
    }

    /** 「最多 3 个」/「不限数量」。 */
    public static String maxApps(Context context) {
        int value = Prefs.getWarmMaxApps();
        return value <= Prefs.WARM_MAX_UNLIMITED
                ? context.getString(R.string.settings_warm_max_unlimited)
                : context.getString(R.string.settings_warm_max_count, value);
    }

    /**
     * 「预热参数」那行的小字（0.7.39-M6 / 60）：三个值的现状。
     *
     * <p>三项里的 0 都不是「零」：数量上限 0 = 不限，冷却 0 = 不冷却，间隔 0 = 不等 ——
     * 按这个说法写，比在界面上写「0秒」准确。
     */
    public static String paramsSummary(Context context) {
        int max = Prefs.getWarmMaxApps();
        String maxText = max <= Prefs.WARM_MAX_UNLIMITED
                ? context.getString(R.string.settings_warm_params_max_unlimited)
                : context.getString(R.string.settings_warm_params_max_count, max);
        int cooldown = Prefs.getWarmCooldownSec();
        String cooldownText = cooldown == 0
                ? context.getString(R.string.settings_warm_params_cooldown_off)
                : context.getString(R.string.settings_warm_params_cooldown, cooldown);
        int gap = Prefs.getWarmGapMs();
        String gapText = gap == 0
                ? context.getString(R.string.settings_warm_params_gap_off)
                : context.getString(R.string.settings_warm_params_gap, gap);
        return context.getString(R.string.settings_warm_params_summary,
                maxText, cooldownText, gapText);
    }

    /**
     * 状态页「预热引擎」那行的说明：名单模式、名单大小、数量上限。
     *
     * <p>白名单空着时这里要说得刺眼一点 —— 那是一个「什么都没发生」的静默状态。
     */
    public static String listSummary(Context context) {
        int count = Prefs.selectedCount();
        if (!Prefs.isWhitelistMode()) {
            return context.getString(R.string.row_warm_list_blacklist, count, maxApps(context));
        }
        return count == 0
                ? context.getString(R.string.row_warm_list_whitelist_empty)
                : context.getString(R.string.row_warm_list_whitelist, count, maxApps(context));
    }
}

package com.jtfa.notificationfixer.ui;

import androidx.annotation.ColorRes;

import com.jtfa.notificationfixer.R;

/**
 * 状态色档（0.7.29-M6 / 50）：状态格里「好 / 提醒 / 坏 / 中性」的取色。
 *
 * <p>原来这套常量与颜色映射长在旧的行式组件 StatusRowView 里；状态页模块化之后块也要用，
 * 就提到这里。0.7.53-M6 / 74 移除「预热覆盖」后，行式组件已删除，这里只服务
 * {@link StatusBlockView}。
 */
public final class StatusLevel {

    public static final int NEUTRAL = 0;
    public static final int OK = 1;
    public static final int WARN = 2;
    public static final int BAD = 3;

    @ColorRes
    public static int color(int level) {
        switch (level) {
            case OK:
                return R.color.state_ok;
            case WARN:
                return R.color.state_warn;
            case BAD:
                return R.color.state_bad;
            default:
                return R.color.state_neutral;
        }
    }

    private StatusLevel() {
    }
}

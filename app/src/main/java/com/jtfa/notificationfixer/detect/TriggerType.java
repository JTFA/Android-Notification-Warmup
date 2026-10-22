package com.jtfa.notificationfixer.detect;

/** 触发源。M2 起由唤醒引擎消费。 */
public enum TriggerType {

    /** 收到横幅通知。 */
    BANNER_NOTIFICATION("横幅通知"),

    /** 下拉通知面板。 */
    SHADE_OPENED("通知面板展开"),

    /** 通知面板已经展开时又收到的新通知。 */
    SHADE_NOTIFICATION("通知中心新通知"),

    /** 锁屏停留时收到的新通知。 */
    LOCKSCREEN_NOTIFICATION("锁屏新通知"),

    /** 锁屏点亮。 */
    LOCKSCREEN_LIT("锁屏点亮");

    private final String label;

    TriggerType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

package com.jtfa.notificationfixer.detect;

/** 触发源。M2 起由唤醒引擎消费。 */
public enum TriggerType {

    /** 收到新通知（全局触发；内层「仅横幅通知触发」只影响它的判定范围）。 */
    BANNER_NOTIFICATION("收到新通知"),

    /** 下拉通知面板。 */
    SHADE_OPENED("通知面板展开"),

    /** 通知面板已经展开时又收到的新通知。 */
    SHADE_NOTIFICATION("通知中心新通知"),

    /** 锁屏停留时收到的新通知。 */
    LOCKSCREEN_NOTIFICATION("锁屏新通知"),

    /** 锁屏点亮。 */
    LOCKSCREEN_LIT("锁屏点亮"),

    /**
     * 亮屏补预热：屏幕点亮瞬间，对息屏期间收到、且按锁屏可见性估计可能上锁屏的新通知立刻预热，
     * 不等 400ms 的锁屏探测。
     *
     * <p>只由「锁屏点亮时」和「息屏时尝试记录新锁屏通知，点亮屏幕快速预热」两个开关控制；
     * 精确度滑杆在亮屏时按当前值过滤，全局「收到新通知时」与「仅横幅通知触发」不参与。
     */
    SCREEN_ON_PENDING("亮屏补预热");

    private final String label;

    TriggerType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

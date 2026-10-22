package com.jtfa.notificationfixer.detect;

import java.util.Collections;
import java.util.List;

/** 一次触发。 */
public final class TriggerEvent {

    public final TriggerType type;
    public final long timeMs;

    /** 触发来源包名，锁屏点亮时为 null（可能对应多个应用）。 */
    public final String sourcePackage;

    /** 判定依据，写进日志便于核对。 */
    public final String detail;

    /**
     * 本次触发希望预热的候选应用，已按「用户最可能点击」的顺序排好。
     *
     * <p>检测层只负责给出候选，是否预热、预热几个、要多快，由唤醒引擎按
     * 冷却、名单和限流规则决定。
     */
    public final List<String> targetPackages;

    public TriggerEvent(TriggerType type, String sourcePackage, String detail,
                        List<String> targetPackages) {
        this.type = type;
        this.sourcePackage = sourcePackage;
        this.detail = detail;
        this.timeMs = System.currentTimeMillis();
        this.targetPackages = targetPackages == null ? Collections.emptyList() : targetPackages;
    }

    /** 目标应用数量。 */
    public int targetCount() {
        return targetPackages.size();
    }
}

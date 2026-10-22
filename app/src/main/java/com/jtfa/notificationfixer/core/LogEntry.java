package com.jtfa.notificationfixer.core;

/** 一条事件日志。 */
public final class LogEntry {

    public final long id;
    public final long timeMs;
    public final String category;
    public final String text;

    /** 结构化补充信息，可能为 null。不记录通知正文。 */
    public final String data;

    public LogEntry(long id, long timeMs, String category, String text, String data) {
        this.id = id;
        this.timeMs = timeMs;
        this.category = category;
        this.text = text;
        this.data = data;
    }
}

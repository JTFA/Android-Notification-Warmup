package com.jtfa.notificationfixer.detect;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 触发事件总线：检测层与消费层之间的唯一接缝。
 *
 * <p>M1 唯一的订阅者是日志记录器（见 App）。M2 的唤醒引擎挂在同一位置，
 * 检测层不需要改动。事件统一在主线程分发，订阅者不必自己切线程。
 */
public final class TriggerBus {

    public interface Listener {
        void onTrigger(TriggerEvent event);
    }

    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private TriggerBus() {
    }

    public static void addListener(Listener listener) {
        if (!sListeners.contains(listener)) {
            sListeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        sListeners.remove(listener);
    }

    public static void emit(final TriggerEvent event) {
        sMain.post(() -> {
            for (Listener listener : sListeners) {
                listener.onTrigger(event);
            }
        });
    }
}

package com.jtfa.notificationfixer.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 事件日志：内存环形缓冲 + JSONL 落盘。
 *
 * <p>只写本机文件，不联网；不记录通知标题与正文，只记包名与判定信号。
 */
public final class EventLog {

    public static final String CAT_TRIGGER = "触发";
    public static final String CAT_WARM = "预热";
    public static final String CAT_NOTIF = "通知";
    public static final String CAT_DIAG = "诊断";
    public static final String CAT_SERVICE = "服务";
    public static final String CAT_PERM = "权限";

    /** 日志页筛选顺序，与 {@link #CAT_TRIGGER} 等保持一致。 */
    public static final String[] CATEGORIES =
            {CAT_TRIGGER, CAT_WARM, CAT_NOTIF, CAT_DIAG, CAT_SERVICE, CAT_PERM};

    private static final String TAG = "NF-EventLog";
    private static final int MAX_ENTRIES = 800;
    private static final String FILE_NAME = "events.jsonl";

    private static EventLog sInstance;

    public interface Listener {
        void onLogChanged(List<LogEntry> snapshot);
    }

    private final ArrayDeque<LogEntry> mEntries = new ArrayDeque<>();
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "nf-eventlog");
        thread.setDaemon(true);
        return thread;
    });
    private final SimpleDateFormat mFileTimeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private final File mDir;
    private final File mFile;
    private long mNextId = 1L;

    private EventLog(Context appContext) {
        mDir = new File(appContext.getFilesDir(), "logs");
        if (!mDir.exists() && !mDir.mkdirs()) {
            Log.w(TAG, "无法创建日志目录: " + mDir);
        }
        mFile = new File(mDir, FILE_NAME);
    }

    public static synchronized void init(Context context) {
        if (sInstance == null) {
            sInstance = new EventLog(context.getApplicationContext());
        }
    }

    @NonNull
    public static EventLog get() {
        EventLog instance = sInstance;
        if (instance == null) {
            throw new IllegalStateException("EventLog.init() 未被调用");
        }
        return instance;
    }

    public void log(String category, String text) {
        log(category, text, null);
    }

    public void log(final String category, final String text, final String data) {
        final LogEntry entry;
        synchronized (this) {
            entry = new LogEntry(mNextId++, System.currentTimeMillis(), category, text, data);
            mEntries.addFirst(entry);
            while (mEntries.size() > MAX_ENTRIES) {
                mEntries.removeLast();
            }
        }
        Log.i(TAG, "[" + category + "] " + text + (data == null ? "" : " " + data));
        mIo.execute(() -> appendToFile(entry));
        mMain.post(this::notifyListeners);
    }

    /** 返回快照，最新的在前。 */
    @NonNull
    public List<LogEntry> snapshot() {
        synchronized (this) {
            return new ArrayList<>(mEntries);
        }
    }

    public void clear() {
        synchronized (this) {
            mEntries.clear();
        }
        mIo.execute(() -> {
            if (mFile.exists() && !mFile.delete()) {
                Log.w(TAG, "删除日志文件失败: " + mFile);
            }
        });
        mMain.post(this::notifyListeners);
    }

    /**
     * 导出一份人类可读的日志文件，失败返回 null。
     *
     * @return 供分享的文件
     */
    public File exportFile() {
        List<LogEntry> entries = snapshot();
        SimpleDateFormat nameFormat = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        File out = new File(mDir, "export-" + nameFormat.format(new Date()) + ".txt");
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(out), StandardCharsets.UTF_8)) {
            writer.write("# 通知预热 M1 事件日志导出\n");
            writer.write("# 设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " / Android " + android.os.Build.VERSION.RELEASE
                    + " (API " + android.os.Build.VERSION.SDK_INT + ")\n");
            writer.write("# 条数: " + entries.size() + "\n\n");
            for (int i = entries.size() - 1; i >= 0; i--) {
                LogEntry entry = entries.get(i);
                writer.write(mFileTimeFormat.format(new Date(entry.timeMs))
                        + " [" + entry.category + "] " + entry.text
                        + (entry.data == null ? "" : "  " + entry.data) + "\n");
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "导出日志失败", e);
            return null;
        }
    }

    public void addListener(Listener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    private void appendToFile(LogEntry entry) {
        String line = mFileTimeFormat.format(new Date(entry.timeMs))
                + " [" + entry.category + "] " + entry.text
                + (entry.data == null ? "" : "  " + entry.data) + "\n";
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(mFile, true), StandardCharsets.UTF_8)) {
            writer.write(line);
        } catch (Exception e) {
            Log.w(TAG, "写入日志文件失败", e);
        }
    }

    private void notifyListeners() {
        List<LogEntry> snapshot = snapshot();
        for (Listener listener : mListeners) {
            listener.onLogChanged(snapshot);
        }
    }
}

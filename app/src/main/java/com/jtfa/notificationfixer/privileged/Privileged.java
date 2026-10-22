package com.jtfa.notificationfixer.privileged;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.HealthWatch;

import rikka.shizuku.Shizuku;

/**
 * 特权通道的唯一入口（M4）。
 *
 * <p>三件事：挑出当前可用的通道、保证所有命令都在同一条后台线程上串行执行、
 * 给界面提供一句话状态。
 *
 * <p>通道优先 Shizuku：它的可用性检查只是 binder 调用，不 spawn 进程；
 * root 的检查要真的跑一次 `su -c id`（可能弹 Magisk 授权框），所以放后面。
 */
public final class Privileged {

    private static final String TAG = "NF-Privileged";

    private static final Object LOCK = new Object();

    private static HandlerThread sThread;
    private static Handler sWorker;
    private static Handler sMain;

    private static ShizukuShell sShizuku;
    private static RootShell sRoot;

    private Privileged() {
    }

    public static ShizukuShell shizuku() {
        synchronized (LOCK) {
            if (sShizuku == null) {
                sShizuku = new ShizukuShell();
            }
            return sShizuku;
        }
    }

    public static RootShell root() {
        synchronized (LOCK) {
            if (sRoot == null) {
                sRoot = new RootShell();
            }
            return sRoot;
        }
    }

    /** 当前能用的通道；都没有返回 null。 */
    public static PrivilegedBridge active() {
        ShizukuShell shizuku = shizuku();
        if (shizuku.isAvailable()) {
            return shizuku;
        }
        RootShell root = root();
        if (root.isAvailable()) {
            return root;
        }
        return null;
    }

    /**
     * 一句话状态：通道不可用时，要把「卡在哪一步」说清楚。
     *
     * <p>「Shizuku 应用里已授权，但本应用一直没收到 binder」是这条路最容易踩的坑，
     * 所以这里不笼统说「未运行」，而是把「Shizuku 装没装」「binder 收到没有」分开讲。
     */
    public static String statusText(Context context) {
        ShizukuShell shizuku = shizuku();
        if (shizuku.granted()) {
            return shizuku.status();
        }
        RootShell root = root();
        if (root.isAvailable()) {
            return root.status();
        }
        String manager = ShizukuShell.managerVersion(context);
        if (manager == null) {
            return "没有可用的特权通道：Shizuku 未安装，root 也不可用";
        }
        if (shizuku.serviceAlive()) {
            return "Shizuku " + manager + " 已连接，但本应用还没被授权 —— 点「申请授权」，"
                    + "或在 Shizuku 应用里给它打开权限";
        }
        return "已安装 Shizuku " + manager + "，但本进程没收到它的 binder："
                + "先在 Shizuku 应用里确认服务真的在运行（无线调试 / root 模式）";
    }

    /**
     * 进程启动时挂上 binder 监听：收到 / 断开各写一行诊断日志。
     *
     * <p>「一直没收到 binder」这种失败以前只能靠猜，有了这两行一眼就看得出来。
     */
    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        try {
            Shizuku.addBinderReceivedListenerSticky(() -> {
                // 特权通道这一项（0.7.25-M6 / 46）：收得到 binder = 通道在，也说明「我们见过它」
                HealthWatch.onPrivilegedAvailable(app);
                EventLog.get().log(
                        EventLog.CAT_DIAG, "收到 Shizuku binder（特权通道可用）",
                        ShizukuShell.diagnose(app));
            });
            Shizuku.addBinderDeadListener(() -> {
                HealthWatch.onPrivilegedLost(app);
                EventLog.get().log(
                        EventLog.CAT_DIAG, "Shizuku binder 断开（Shizuku 服务停了或被系统结束）");
            });
        } catch (Throwable t) {
            Log.w(TAG, "挂 binder 监听失败", t);
        }
    }

    /** 丢到特权线程执行（会阻塞，不要在界面线程直接跑命令）。 */
    public static void post(Runnable task) {
        worker().post(task);
    }

    /** 延迟丢到特权线程执行（预热后的复探用，不阻塞预热队列）。 */
    public static void postDelayed(Runnable task, long delayMs) {
        worker().postDelayed(task, delayMs);
    }

    /** 回到界面线程。 */
    public static void onMain(Runnable task) {
        main().post(task);
    }

    private static synchronized Handler worker() {
        if (sWorker == null) {
            sThread = new HandlerThread("nf-privileged");
            sThread.start();
            sWorker = new Handler(sThread.getLooper());
        }
        return sWorker;
    }

    private static synchronized Handler main() {
        if (sMain == null) {
            sMain = new Handler(Looper.getMainLooper());
        }
        return sMain;
    }
}

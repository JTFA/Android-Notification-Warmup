package com.jtfa.notificationfixer.privileged;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.InputStream;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Shizuku 通道：命令以 Shizuku 的身份执行。
 *
 * <p>Shizuku 的 adb 模式身份是 shell（uid 2000），root 模式是 root（uid 0）——
 * 两者能做的事不一样，所以自检第一条就是打印 {@code id}。
 *
 * <p>API 13 起官方去掉了 `Shizuku.newProcess`，但服务端的
 * {@code IShizukuService.newProcess} 还在，生成的 AIDL 代理就在 `dev.rikka.shizuku:aidl` 里，
 * 于是这里走 {@code IShizukuService.Stub.asInterface(Shizuku.getBinder())} 直接调用，
 * 不自己拼 Parcel（拼错事务号是这条路最常见的翻车点）。
 */
public final class ShizukuShell implements PrivilegedBridge {

    private static final String TAG = "NF-Shizuku";

    /** 申请权限的请求码，回调里靠它认领。 */
    public static final int REQUEST_CODE = 0x4E46;

    /** Shizuku 应用（manager）的包名。 */
    private static final String MANAGER_APPLICATION_ID = "moe.shizuku.privileged.api";

    @Override
    public String name() {
        return "Shizuku";
    }

    /** Shizuku 服务是否活着（活着才有 binder，才谈得上授权）。 */
    public boolean serviceAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean granted() {
        return serviceAlive() && ShellStreams.shizukuGranted();
    }

    @Override
    public boolean isAvailable() {
        return granted();
    }

    @Override
    public String status() {
        try {
            if (Shizuku.pingBinder()) {
                int version = Shizuku.getVersion();
                if (!ShellStreams.shizukuGranted()) {
                    return "Shizuku 已连接（API " + version + "），尚未授权给本应用";
                }
                int uid = Shizuku.getUid();
                return "Shizuku 已授权（API " + version + "，身份 uid=" + uid
                        + (uid == 0 ? " root" : uid == 2000 ? " shell" : "") + "）";
            }
            return "本进程还没收到 Shizuku 的 binder：先在 Shizuku 应用里确认服务在运行"
                    + "（无线调试 / root 模式），再回本页点「刷新状态」";
        } catch (Throwable t) {
            return "Shizuku 状态读取失败：" + t;
        }
    }

    /** Shizuku 应用（manager）的版本名；没装返回 null。 */
    public static String managerVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(MANAGER_APPLICATION_ID, 0);
            return info.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 本应用声明的 Shizuku provider 是否真的在已安装的包里。
     *
     * <p>false 只可能是「装的是没有这个 provider 的旧包」——权限写错不会让它变 false，
     * 只会让 Shizuku 服务端送不进来 binder（这正是 0.4.0-M4 首次实测踩到的坑）。
     */
    public static boolean providerDeclared(Context context) {
        try {
            return context.getPackageManager().resolveContentProvider(
                    context.getPackageName() + ".shizuku", 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 排查用的详细状态，写进「诊断」日志。
     *
     * <p>专治「Shizuku 应用里明明已授权，本应用却一直没收到 binder」：把本应用版本、
     * provider 声明情况、Shizuku 应用版本一次列全，省得靠猜。
     */
    public static String diagnose(Context context) {
        String manager = managerVersion(context);
        String self;
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            self = info.versionName + "（versionCode " + info.getLongVersionCode() + "）";
        } catch (Throwable t) {
            self = "未知";
        }
        return "本应用 " + self
                + "；已收到 binder = " + Shizuku.pingBinder()
                + "；Shizuku provider 已声明 = " + providerDeclared(context)
                + "；Shizuku 应用 = " + (manager == null ? "未安装" : manager);
    }

    /** 弹出 Shizuku 的授权对话框（要在主线程调用）。 */
    public void requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable t) {
            Log.w(TAG, "申请权限失败", t);
        }
    }

    public void addPermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener);
        } catch (Throwable t) {
            Log.w(TAG, "注册授权回调失败", t);
        }
    }

    public void removePermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener);
        } catch (Throwable t) {
            Log.w(TAG, "注销授权回调失败", t);
        }
    }

    @Override
    public ShellResult run(String command) {
        if (!serviceAlive()) {
            return ShellResult.failure(command, "Shizuku 服务未运行");
        }
        if (!ShellStreams.shizukuGranted()) {
            return ShellResult.failure(command, "尚未授予 Shizuku 权限");
        }
        try {
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (service == null) {
                return ShellResult.failure(command, "拿不到 Shizuku service");
            }
            // 2>&1：把 stderr 并进 stdout。分两条管道读容易互相等死（写满缓冲区）
            IRemoteProcess process = service.newProcess(
                    new String[]{"sh", "-c", command + " 2>&1"}, null, null);
            if (process == null) {
                return ShellResult.failure(command, "Shizuku 未能创建进程");
            }
            String output = ShellStreams.read(process.getInputStream());
            int exitCode = process.waitFor();
            return new ShellResult(command, true, exitCode, output);
        } catch (Throwable t) {
            Log.w(TAG, "执行失败: " + command, t);
            return ShellResult.failure(command, describe(t));
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            message = t.getClass().getSimpleName();
        }
        return message;
    }
}

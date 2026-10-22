package com.jtfa.notificationfixer.privileged;

import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

/**
 * 读命令输出的小工具。
 *
 * <p>只读前 {@link #MAX_BYTES} 字节：`pm list packages` 这类命令输出可能几百 KB，
 * 而我们要的只是「跑通了没有」和几个关键字符串，读满上限就够，流由调用方关闭。
 */
final class ShellStreams {

    private static final int MAX_BYTES = 64 * 1024;

    private ShellStreams() {
    }

    static String read(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return "";
        }
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            return read(in);
        } catch (Throwable t) {
            Log.w("NF-Shell", "读输出失败", t);
            return "（读取输出失败：" + t + "）";
        }
    }

    static String read(InputStream in) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        try {
            int read;
            while (total < MAX_BYTES && (read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                total += read;
            }
        } catch (Throwable t) {
            Log.w("NF-Shell", "读取中断", t);
        }
        String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        if (total >= MAX_BYTES) {
            text = text + "\n（输出已截断）";
        }
        return text;
    }

    /** 命令是否真是通过 Shizuku 跑的（授权判断的统一入口）。 */
    static boolean shizukuGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }
}

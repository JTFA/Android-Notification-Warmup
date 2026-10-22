package com.jtfa.notificationfixer.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 「这个应用到底是什么状态」的当场读数：全部只读，不需要任何特权通道。
 *
 * <p>为什么要有它：从 M2 起，我们把读到的状态位翻译成一句人话摆在界面上
 * （「应用已被停用或冻结，第三方无法拉起」）。那句翻译是<b>我们的结论</b>，不是系统说的 ——
 * 一旦有人问「它真的被停用了？还是只是没活跃 / 被杀掉了后台」，光看那句翻译没法核对，
 * 只能靠嘴争。所以这里把系统能给的原始读数一次摆出来，让结论可以被推翻：
 *
 * <ul>
 *   <li>{@code getApplicationEnabledSetting()} 的原值 —— 只有<b>显式</b>的停用
 *       （{@code pm disable}、ROM 的冻结、安装器）会写它，进程被系统回收<b>不会</b>；</li>
 *   <li>{@code getLaunchIntentForPackage()} —— 系统还给不给「启动它」的入口。
 *       它非 null 就说明桌面点得开，而这可以与「系统里的停用状态」同时成立
 *       （ROM 可以在启动时自己解冻）；</li>
 *   <li>桌面入口在「默认问法 / 连停用组件一起返回」两种问法下的差别 ——
 *       差别本身就是「它被当成停用组件滤掉了」的直接证据。</li>
 * </ul>
 *
 * <p>它回答不了的是「谁写的这个状态」（系统不记录这个），所以那部分仍然只说事实、不猜。
 */
public final class AppStateCheck {

    private AppStateCheck() {
    }

    /** 读一组原始状态行，界面直接显示。绝不在主线程之外调用会阻塞的 PackageManager 查询之外做别的事。 */
    public static List<String> read(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        List<String> lines = new ArrayList<>();
        lines.add("应用状态：" + ComponentInventory.appStateDetail(context, packageName));
        lines.add("启动入口（getLaunchIntentForPackage）：" + launchIntent(packageManager, packageName));
        lines.add("桌面入口，默认问法（滤掉停用组件）：" + countText(launcherCount(packageManager, packageName, 0)));
        lines.add("桌面入口，连停用组件一起返回：" + countText(launcherCount(packageManager, packageName,
                PackageManager.MATCH_DISABLED_COMPONENTS
                        | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS)));
        lines.add("（全部只读：没有执行特权命令，也没有改任何状态）");
        return lines;
    }

    private static String launchIntent(PackageManager packageManager, String packageName) {
        try {
            Intent intent = packageManager.getLaunchIntentForPackage(packageName);
            if (intent == null) {
                return "null（系统不提供启动入口）";
            }
            ComponentName component = intent.getComponent();
            return component == null ? "有入口" : component.flattenToShortString();
        } catch (Exception e) {
            return "读不到（" + e.getClass().getSimpleName() + "）";
        }
    }

    private static int launcherCount(PackageManager packageManager, String packageName, int flags) {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            int count = 0;
            for (ResolveInfo info : packageManager.queryIntentActivities(launcher, flags)) {
                if (info.activityInfo != null && packageName.equals(info.activityInfo.packageName)) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String countText(int count) {
        if (count < 0) {
            return "查询失败";
        }
        return count == 0 ? "解析不到" : "解析得到 " + count + " 个";
    }
}

package com.jtfa.notificationfixer.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 系统栏外观：状态栏图标与手势提示条（导航栏）跟着当前主题的明暗走。
 *
 * <p>0.7.16-M6 / 37 及以前的实测现象：深色、浅色两种模式下状态栏文字都是白的，底部的
 * 手势提示条 / 导航栏也不跟界面同色。前者是主题的事 ——
 * {@code Theme.Material3.DayNight} 自己<b>不设</b> {@code windowLightStatusBar} 与
 * {@code windowLightNavigationBar}（整个 Material AAR 里都搜不到这两项），平台的默认值是
 * false，而 false 的意思就是「图标用<b>浅色</b>」：浅色主题下就是白字压白底。
 *
 * <p>分工：主题那两份（{@code values/themes.xml} 与 {@code values-night/themes.xml}）负责
 * <b>启动窗口那几帧</b> —— 进程还没起来的启动画面期间只有主题值可用，等代码跑起来再设已经太晚，
 * 浅色主题会先闪一下白字。这里再按当前 {@code uiMode} 设一次，兜住「主题解析结果与实际明暗不一致」
 * 的情况；两处必须一致。
 *
 * <p>系统栏背景不由系统画：窗口背景（{@code ?attr/colorSurface}）铺满整屏，顶栏用的是同一个色，
 * 底部那一带（含手势提示条）自 0.7.24-M6 / 45 起由底栏容器的底色铺过去（见
 * {@code activity_main.xml} 里 {@code bottom_bar_area} 那一段），所以整屏一色。要这样就得把系统的
 * <b>对比度蒙层</b>关掉 —— 安卓 15 起，三键导航默认带一层不透明的垫底
 * （{@code setNavigationBarContrastEnforced} 只管三键导航，手势模式系统不垫底），状态栏那一份同理，
 * 这就是「底部不沉浸 / 上下两截色」的来源之一。两个 setter 从 API 35 起标了 deprecated，但官方
 * 边到边文档仍把它当成三键导航那一层垫底的开关（「set {@code setNavigationBarContrastEnforced}
 * to false otherwise there will be a translucent scrim applied」），所以继续留着。
 *
 * <p>反过来，{@code window.setNavigationBarColor} / 主题里的 {@code android:navigationBarColor}
 * 这类「告诉系统栏底下该是什么色」的做法在 targetSdk 35+ 已经不算数了：系统强制边到边、栏本身透明，
 * 垫底那层由应用自己画 —— 那也是本应用一直在做的事（也是为什么这里不去设那个色）。
 *
 * <p>手势模式下要看的是那根手势条<b>本身</b>的颜色，它跟着 {@code windowLightNavigationBar} 走
 * —— 和状态栏是同一个开关，所以浅色主题下它此前也是浅色（这条小横线本来是深色才看得清）。
 */
public final class SystemBars {

    private SystemBars() {
    }

    /** 按当前主题明暗设一次系统栏图标，并把系统的对比度蒙层关掉。可以随便调。 */
    // 对比度那两个 setter 从 API 35 起标了 deprecated，但仍生效且没有替代品（见类注释）。
    @SuppressWarnings("deprecation")
    public static void apply(Activity activity) {
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        boolean light = !isNight(activity);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(light);
            controller.setAppearanceLightNavigationBars(light);
        }
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
    }

    /** 当前是否深色主题。 */
    public static boolean isNight(Activity activity) {
        int mode = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
}

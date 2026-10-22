package com.jtfa.notificationfixer.ui;

import android.Manifest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.jtfa.notificationfixer.R;
import com.jtfa.notificationfixer.core.HealthWatch;
import com.jtfa.notificationfixer.core.Permissions;
import com.jtfa.notificationfixer.core.Prefs;
import com.jtfa.notificationfixer.core.RecentsVisibility;
import com.jtfa.notificationfixer.service.KeepAliveService;

/** 主界面：状态 / 名单 / 日志 / 设置。 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_POST_NOTIFICATIONS = 1001;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 状态栏图标 / 手势提示条（导航栏）的明暗跟着主题走，并关掉系统那层对比度蒙层。
        // 启动窗口那几帧由主题负责（values/themes.xml 与 values-night/themes.xml），这里补运行期的。
        SystemBars.apply(this);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        View root = findViewById(R.id.root);
        View bottomArea = findViewById(R.id.bottom_bar_area);
        // 内容避开状态栏；底部那条不留给 root，改加在底栏容器的 paddingBottom 上 ——
        // 容器底色（?attr/colorSurfaceContainer，与底栏同色）因此一直铺到屏幕下沿，
        // 手势提示条（导航栏）那一带也是它，见图纸上底栏那一段的说明。
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, 0);
            bottomArea.setPadding(bottomArea.getPaddingLeft(), bottomArea.getPaddingTop(),
                    bottomArea.getPaddingRight(), bars.bottom);
            return insets;
        });

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            showFragment(item.getItemId());
            return true;
        });
        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_status);
        }

        if (shouldShowDisclaimer()) {
            showDisclaimer();
        } else {
            enterApp();
        }
    }

    /**
     * 首次安装的声明只在「安装后第一次打开」出现。
     *
     * <p>更新安装时 {@code lastUpdateTime} 会晚于 {@code firstInstallTime}，即使旧版本没有写过
     * 声明标记，也按老用户处理：直接补写标记，不再弹窗。这样更新后首次打开也不会被打断。
     */
    private boolean shouldShowDisclaimer() {
        if (Prefs.isDisclaimerAccepted()) {
            return false;
        }
        if (isFreshInstall()) {
            return true;
        }
        Prefs.setDisclaimerAccepted();
        return false;
    }

    private boolean isFreshInstall() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            // 正常安装时这两个时间相同；留一点余量，避免个别 ROM 的毫秒级差异。
            return Math.abs(info.lastUpdateTime - info.firstInstallTime) < 5_000L;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void showDisclaimer() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.disclaimer_title)
                .setMessage(R.string.disclaimer_message)
                .setCancelable(false)
                .setNegativeButton(R.string.disclaimer_disagree, (d, which) -> {
                    KeepAliveService.stop(this);
                    finishAndRemoveTask();
                })
                .setPositiveButton(R.string.disclaimer_agree, (d, which) -> {
                    Prefs.setDisclaimerAccepted();
                    d.dismiss();
                    enterApp();
                })
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    /** 声明同意后再启动常驻服务、申请通知权限，避免系统权限框压住声明。 */
    private void enterApp() {
        KeepAliveService.start(this);
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 主题在运行期变了（系统切深色、或按应用切深色）时再对一遍系统栏图标；值没变不会写日志。
        SystemBars.apply(this);
        // 「在最近任务中隐藏」：任务被系统重建、或从桌面重新启动后拿到的是新任务，那个标志要重写一次。
        // 高频动作，所以这个方法只在值变了或写失败时落日志（见 RecentsVisibility）。
        RecentsVisibility.apply(this);
        // 用户来看过了：「监听服务上次被系统杀掉」这条结论收起（0.7.25-M6 / 46）。
        // 放在 onResume 而不是 onCreate：从后台回到前台也算看过了，别让它一直挂着。
        HealthWatch.acknowledge(this);
    }

    private void showFragment(int itemId) {
        Fragment fragment;
        if (itemId == R.id.nav_apps) {
            fragment = new AppListFragment();
        } else if (itemId == R.id.nav_log) {
            fragment = new LogFragment();
        } else if (itemId == R.id.nav_settings) {
            fragment = new SettingsFragment();
        } else {
            fragment = new StatusFragment();
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    /**
     * 申请通知权限。
     *
     * <p>0.7.25 / 46 曾按「不显示通知」那一档跳过申请，那一档已于 0.7.27-M6 / 48 撤掉
     * （异常提醒要它，本应用的通知一直开着才是现在唯一的行为），所以又回到「没授予就申请」。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Permissions.hasPostNotifications(this)) {
            return;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_POST_NOTIFICATIONS);
    }
}

package com.jtfa.notificationfixer.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.jtfa.notificationfixer.R;
import com.jtfa.notificationfixer.core.AccessibilityAutostart;
import com.jtfa.notificationfixer.core.AccessibilityGrant;
import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.HealthWatch;
import com.jtfa.notificationfixer.core.Markup;
import com.jtfa.notificationfixer.core.Prefs;
import com.jtfa.notificationfixer.core.RecentsVisibility;
import com.jtfa.notificationfixer.privileged.Privileged;
import com.jtfa.notificationfixer.privileged.PrivilegedBridge;
import com.jtfa.notificationfixer.privileged.ShellResult;
import com.jtfa.notificationfixer.privileged.ShizukuShell;
import com.jtfa.notificationfixer.warm.WarmEngine;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import rikka.shizuku.Shizuku;

/** 触发场景开关、判定档位与预热参数。 */
public class SettingsFragment extends Fragment {

    private MaterialSwitch mSwitchBanner;
    private MaterialSwitch mSwitchShade;
    private MaterialSwitch mSwitchLockscreen;
    private MaterialSwitch mSwitchWarm;
    private MaterialSwitch mSwitchWindowing;
    private TextInputLayout mTilWindowing;
    private TextInputEditText mEditWindowing;
    /** 「预热参数」那行的小字（0.7.39-M6 / 60）：随弹窗里的三项刷新。 */
    private TextView mTextWarmParams;
    private MaterialSwitch mSwitchRunningNoWarm;
    private MaterialSwitch mSwitchHideRecents;
    private MaterialSwitch mSwitchBootAutostart;
    private TextView mTextWarmState;
    /** 触发场景三行里的小字（0.7.38-M6 / 59）：横幅与锁屏两行写着各自的现状，随弹窗里的改动刷新。 */
    private TextView mTextBannerSummary;
    private TextView mTextLockscreenSummary;
    /** 横幅 / 锁屏两行小字右端的「高级设置＞」。 */
    private TextView mTextBannerAdvanced;
    private TextView mTextLockscreenAdvanced;
    private MaterialSwitch mSwitchWatchListener;
    private MaterialSwitch mSwitchWatchAccessibility;
    private MaterialSwitch mSwitchWatchService;
    private MaterialSwitch mSwitchWatchPrivileged;
    private TextView mTextPrivilegedStatus;
    private TextView mTextAccessibilityState;
    private MaterialButton mButtonAccessibilityGrant;

    private CompoundButton.OnCheckedChangeListener mCheckedListener;

    /** 设置页的全部开关；载入值后统一取消滑块动画，见 {@link #loadValues()}。 */
    private MaterialSwitch[] mSwitches;

    /** 是否已经在 onResume 里按 Prefs 绑定过一遍（视图重建时复位）。 */
    private boolean mSwitchesBound;

    /** Shizuku 授权结果回调：结果可能是用户点了对话框之后才到的。 */
    private final Shizuku.OnRequestPermissionResultListener mPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != ShizukuShell.REQUEST_CODE || !isAdded()) {
                    return;
                }
                refreshPrivilegedStatus(false);
                Toast.makeText(requireContext(),
                        grantResult == PackageManager.PERMISSION_GRANTED
                                ? R.string.toast_privileged_granted
                                : R.string.toast_privileged_denied,
                        Toast.LENGTH_SHORT).show();
            };

    /** 载入初始值时抑制回调，避免把界面初始化当成用户操作。 */
    private boolean mBinding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mSwitchBanner = view.findViewById(R.id.sw_banner);
        mSwitchShade = view.findViewById(R.id.sw_shade);
        mSwitchLockscreen = view.findViewById(R.id.sw_lockscreen);
        mSwitchWarm = view.findViewById(R.id.sw_warm);
        mSwitchWindowing = view.findViewById(R.id.sw_activity_windowing);
        mTilWindowing = view.findViewById(R.id.til_activity_windowing);
        mEditWindowing = view.findViewById(R.id.et_activity_windowing);
        mTextWarmParams = view.findViewById(R.id.tv_warm_params);
        mSwitchRunningNoWarm = view.findViewById(R.id.sw_running_no_warm);
        mSwitchHideRecents = view.findViewById(R.id.sw_hide_recents);
        mSwitchBootAutostart = view.findViewById(R.id.sw_boot_autostart);
        mTextWarmState = view.findViewById(R.id.tv_warm_state);
        mTextBannerSummary = view.findViewById(R.id.tv_banner_summary);
        mTextLockscreenSummary = view.findViewById(R.id.tv_lockscreen_summary);
        mTextBannerAdvanced = view.findViewById(R.id.tv_banner_advanced);
        mTextLockscreenAdvanced = view.findViewById(R.id.tv_lockscreen_advanced);
        mSwitchWatchListener = view.findViewById(R.id.sw_watch_listener);
        mSwitchWatchAccessibility = view.findViewById(R.id.sw_watch_accessibility);
        mSwitchWatchService = view.findViewById(R.id.sw_watch_service);
        mSwitchWatchPrivileged = view.findViewById(R.id.sw_watch_privileged);
        mButtonAccessibilityGrant = view.findViewById(R.id.btn_accessibility_grant);
        mTextAccessibilityState = view.findViewById(R.id.tv_accessibility_state);

        // 这段说明里有 <b>，而 TextView 不解析 HTML：不处理的话界面上显示的就是字面的
        // 「<b>不需要</b>」。统一走 Markup（只认 <b> / </b>，换行原样保留）。
        ((TextView) view.findViewById(R.id.tv_accessibility_desc))
                .setText(Markup.of(requireContext(), R.string.settings_accessibility_desc));

        mSwitches = new MaterialSwitch[]{
                mSwitchBanner, mSwitchShade, mSwitchLockscreen, mSwitchWarm,
                mSwitchWindowing, mSwitchRunningNoWarm,
                mSwitchHideRecents, mSwitchBootAutostart,
                mSwitchWatchListener, mSwitchWatchAccessibility,
                mSwitchWatchService, mSwitchWatchPrivileged
        };
        // 开关的唯一事实来源是 Prefs。setSaveEnabled(false) 只挡住「开关自己保存」，
        // 但父容器在恢复整棵视图树时仍可能把旧条目喂回来。65 再补 setSaveFromParentEnabled(false)，
        // 让父容器在保存 / 恢复两个方向都直接跳过它们。绑定则挪到 onResume（见下方说明）。
        for (MaterialSwitch sw : mSwitches) {
            sw.setSaveEnabled(false);
            sw.setSaveFromParentEnabled(false);
        }

        mCheckedListener = (buttonView, isChecked) -> {
            if (mBinding) {
                return;
            }
            saveSwitch(buttonView.getId(), isChecked);
        };

        // 两行的「高级设置＞」：横幅与锁屏各自的档位都搬进弹窗里（0.7.38-M6 / 59）
        mTextBannerAdvanced.setOnClickListener(v -> showBannerAdvancedDialog());
        mTextLockscreenAdvanced.setOnClickListener(v -> showLockscreenAdvancedDialog());

        // 「预热参数」整行可点（0.7.39-M6 / 60）：三个值收进弹窗，右端圆圈箭头只是提示
        view.findViewById(R.id.row_warm_params)
                .setOnClickListener(v -> showWarmParamsDialog());

        mButtonAccessibilityGrant.setOnClickListener(v -> showGrantDialog());

        // 「填整数」的字段：回车（或点别处失焦）时保存；不合法就退回已保存的那个值，不做猜测
        bindNumberField(mEditWindowing,
                Prefs.WINDOWING_MODE_MIN, Prefs.WINDOWING_MODE_MAX,
                Prefs::getActivityWindowingValue, Prefs::setActivityWindowingValue,
                R.string.toast_windowing_saved, R.string.toast_windowing_invalid);

        // 状态位采样（0.7.40-M6 / 61）：界面入口撤掉，采样器固定关闭，这里不再有手动采样

        mTextPrivilegedStatus = view.findViewById(R.id.tv_privileged_status);

        MaterialButton grant = view.findViewById(R.id.btn_privileged_grant);
        grant.setOnClickListener(v -> {
            if (!Privileged.shizuku().serviceAlive()) {
                // requestPermission 内部要求已经有 service（requireService()），
                // 没收到 binder 时点了只会静默失败，所以这里直接说清楚该去哪儿点。
                Toast.makeText(requireContext(), R.string.toast_privileged_no_binder,
                        Toast.LENGTH_LONG).show();
                return;
            }
            Privileged.shizuku().requestPermission();
            Toast.makeText(requireContext(), R.string.toast_privileged_grant_sent,
                    Toast.LENGTH_SHORT).show();
        });

        MaterialButton refresh = view.findViewById(R.id.btn_privileged_refresh);
        refresh.setOnClickListener(v -> refreshPrivilegedStatus(true));

        Privileged.shizuku().addPermissionListener(mPermissionListener);
    }

    private void attachSwitchListeners() {
        mSwitchBanner.setOnCheckedChangeListener(mCheckedListener);
        mSwitchShade.setOnCheckedChangeListener(mCheckedListener);
        mSwitchLockscreen.setOnCheckedChangeListener(mCheckedListener);
        mSwitchWarm.setOnCheckedChangeListener(mCheckedListener);
        mSwitchWindowing.setOnCheckedChangeListener(mCheckedListener);
        mSwitchRunningNoWarm.setOnCheckedChangeListener(mCheckedListener);
        mSwitchHideRecents.setOnCheckedChangeListener(mCheckedListener);
        mSwitchBootAutostart.setOnCheckedChangeListener(mCheckedListener);
        mSwitchWatchListener.setOnCheckedChangeListener(mCheckedListener);
        mSwitchWatchAccessibility.setOnCheckedChangeListener(mCheckedListener);
        mSwitchWatchService.setOnCheckedChangeListener(mCheckedListener);
        mSwitchWatchPrivileged.setOnCheckedChangeListener(mCheckedListener);
    }

    /**
     * 开关的首次绑定放在 onResume（0.7.44-M6 / 65）。
     *
     * <p>Fragment 自己的顺序是 onViewCreated → onViewStateRestored → onStart，但 Activity 的
     * onRestoreInstanceState 在 onStart 之后、onResume 之前。64 把绑定放在 onStart，理论上已躲开
     * Fragment 的恢复，却仍可能被 Activity 那一层恢复（或其它更晚的恢复路径）在监听挂上之后
     * setChecked(旧值)，于是旧值被当成用户操作写回 SharedPreferences。
     *
     * <p>onResume 一定在所有状态恢复结束之后。配合 setSaveFromParentEnabled(false)，开关既不会被
     * 父容器保存，也不会被父容器恢复，页面显示的值只来自 Prefs。
     */
    @Override
    public void onResume() {
        super.onResume();
        if (!mSwitchesBound) {
            mSwitchesBound = true;
            loadValues();
            attachSwitchListeners();
        }
        refreshPrivilegedStatus(false);
        refreshAccessibilityState();
    }

    @Override
    public void onDestroyView() {
        Privileged.shizuku().removePermissionListener(mPermissionListener);
        mSwitchesBound = false;
        if (mSwitches != null) {
            for (MaterialSwitch sw : mSwitches) {
                sw.setOnCheckedChangeListener(null);
            }
            mSwitches = null;
        }
        super.onDestroyView();
    }

    /**
     * 刷新特权状态。
     *
     * <p>状态检查里可能真的去跑一次 `su -c id`（root 通道的可用性判定），所以放到特权线程上做，
     * 不在界面线程 spawn 进程。withDetail = 用户主动点了「刷新状态」：顺手把排查信息
     * （本应用版本 / binder 收到没有 / provider 声明情况 / Shizuku 应用版本）写进诊断日志。
     */
    private void refreshPrivilegedStatus(boolean withDetail) {
        if (mTextPrivilegedStatus == null) {
            return;
        }
        mTextPrivilegedStatus.setText(R.string.settings_privileged_checking);
        final android.content.Context appContext = requireContext().getApplicationContext();
        Privileged.post(() -> {
            String status = Privileged.statusText(appContext);
            String detail = withDetail ? ShizukuShell.diagnose(appContext) : null;
            Privileged.onMain(() -> {
                if (isAdded() && mTextPrivilegedStatus != null) {
                    mTextPrivilegedStatus.setText(status);
                }
                if (detail != null) {
                    EventLog.get().log(EventLog.CAT_DIAG, "特权通道状态：" + status, detail);
                }
            });
        });
    }

    private void loadValues() {
        mBinding = true;
        mSwitchBanner.setChecked(Prefs.isTriggerBanner());
        mSwitchShade.setChecked(Prefs.isTriggerShade());
        mSwitchLockscreen.setChecked(Prefs.isTriggerLockscreen());
        mSwitchWarm.setChecked(Prefs.isWarmEnabled());
        updateWarmStateText();
        mSwitchWindowing.setChecked(Prefs.isActivityWindowingEnabled());
        mEditWindowing.setText(String.valueOf(Prefs.getActivityWindowingValue()));
        updateWindowingEnabled();
        mSwitchRunningNoWarm.setChecked(Prefs.isSkipIfRunning());
        mSwitchHideRecents.setChecked(Prefs.isHideFromRecents());
        mSwitchBootAutostart.setChecked(Prefs.isBootAutostart());
        mSwitchWatchListener.setChecked(Prefs.isWatchListener());
        mSwitchWatchAccessibility.setChecked(Prefs.isWatchAccessibility());
        mSwitchWatchService.setChecked(Prefs.isWatchService());
        mSwitchWatchPrivileged.setChecked(Prefs.isWatchPrivileged());
        updateTriggerSummaries();
        updateWarmParamsSummary();
        mBinding = false;
        snapSwitchAnimations();
        // 首次载入时部分 Material 版本会把滑块动画推迟到首帧；再补一次，
        // 进页面时看到的就是终态，而不是从 XML 默认的「关」滑过去。
        mSwitchRunningNoWarm.post(this::snapSwitchAnimations);
    }

    /** 把开关的滑块直接放到当前状态，不播「关 → 开 / 开 → 关」那一段动画。 */
    private void snapSwitchAnimations() {
        if (mSwitches == null) {
            return;
        }
        for (MaterialSwitch sw : mSwitches) {
            sw.jumpDrawablesToCurrentState();
        }
    }

    /**
     * 「预热参数」弹窗（0.7.39-M6 / 60）。
     *
     * <p>三项都即改即生效：下拉框选中就落库；两个数字框沿用「回车 / 失焦时收下、越界退回原值」
     * 那一套（关弹窗时补收一次，免得刚打完字就点关闭丢掉）。底栏左边「清除冷却记录」、右边「关闭」。
     */
    private void showWarmParamsDialog() {
        Context context = requireContext();
        View body = LayoutInflater.from(context)
                .inflate(R.layout.dialog_warm_params, null, false);
        MaterialAutoCompleteTextView max = body.findViewById(R.id.dlg_et_warm_max);
        TextInputEditText cooldown = body.findViewById(R.id.dlg_et_warm_cooldown);
        TextInputEditText gap = body.findViewById(R.id.dlg_et_warm_gap);

        final int[] choices = Prefs.WARM_MAX_CHOICES;
        String[] labels = getResources().getStringArray(R.array.warm_max_choices);
        int current = 0;
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == Prefs.getWarmMaxApps()) {
                current = i;
                break;
            }
        }
        max.setSimpleItems(R.array.warm_max_choices);
        max.setText(labels[current], false);
        cooldown.setText(String.valueOf(Prefs.getWarmCooldownSec()));
        gap.setText(String.valueOf(Prefs.getWarmGapMs()));

        // 下拉框不是输入框：点它就展开；选中即落库，并刷上面那行小字
        max.setOnClickListener(v -> max.showDropDown());
        max.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < choices.length) {
                Prefs.setWarmMaxApps(choices[position]);
                updateWarmParamsSummary();
            }
        });

        IntConsumer saveCooldown = value -> {
            Prefs.setWarmCooldownSec(value);
            updateWarmParamsSummary();
        };
        IntConsumer saveGap = value -> {
            Prefs.setWarmGapMs(value);
            updateWarmParamsSummary();
        };
        bindNumberField(cooldown,
                Prefs.WARM_COOLDOWN_SEC_MIN, Prefs.WARM_COOLDOWN_SEC_MAX,
                Prefs::getWarmCooldownSec, saveCooldown,
                R.string.toast_warm_cooldown_saved, R.string.toast_warm_cooldown_invalid);
        bindNumberField(gap,
                Prefs.WARM_GAP_MS_MIN, Prefs.WARM_GAP_MS_MAX,
                Prefs::getWarmGapMs, saveGap,
                R.string.toast_warm_gap_saved, R.string.toast_warm_gap_invalid);

        // 关闭、返回键、点外面，三条路都会走 dismiss：在这里补收一次，免得手改的数没落库
        Runnable collect = () -> {
            if (!isAdded()) {
                return;
            }
            applyNumberField(cooldown,
                    Prefs.WARM_COOLDOWN_SEC_MIN, Prefs.WARM_COOLDOWN_SEC_MAX,
                    Prefs::getWarmCooldownSec, saveCooldown,
                    R.string.toast_warm_cooldown_saved, R.string.toast_warm_cooldown_invalid);
            applyNumberField(gap,
                    Prefs.WARM_GAP_MS_MIN, Prefs.WARM_GAP_MS_MAX,
                    Prefs::getWarmGapMs, saveGap,
                    R.string.toast_warm_gap_saved, R.string.toast_warm_gap_invalid);
            updateWarmParamsSummary();
        };

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.settings_warm_params_title)
                .setView(body)
                .create();

        body.findViewById(R.id.btn_warm_params_close).setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> collect.run());

        body.findViewById(R.id.btn_clear_cooldown).setOnClickListener(v -> {
            WarmEngine engine = WarmEngine.get();
            if (engine != null) {
                engine.clearCooldowns();
            }
            Toast.makeText(context, R.string.toast_cooldown_cleared, Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    /** 「预热参数」那行的小字（0.7.39-M6 / 60）：三个值的现状。 */
    private void updateWarmParamsSummary() {
        if (mTextWarmParams == null || !isAdded()) {
            return;
        }
        mTextWarmParams.setText(WarmText.paramsSummary(requireContext()));
    }

    private void saveSwitch(int viewId, boolean checked) {
        if (viewId == R.id.sw_banner) {
            Prefs.setTriggerBanner(checked);
        } else if (viewId == R.id.sw_shade) {
            Prefs.setTriggerShade(checked);
        } else if (viewId == R.id.sw_lockscreen) {
            Prefs.setTriggerLockscreen(checked);
        } else if (viewId == R.id.sw_warm) {
            Prefs.setWarmEnabled(checked);
            updateWarmStateText();
        } else if (viewId == R.id.sw_activity_windowing) {
            Prefs.setActivityWindowingEnabled(checked);
            updateWindowingEnabled();
        } else if (viewId == R.id.sw_running_no_warm) {
            Prefs.setSkipIfRunning(checked);
            // 这枚开关之前出现过「自己来回跳」的报告：留一行日志，万一复现时能区分是
            // 真的写了一次，还是只是滑块动画（见 README 63 小节）。
            // 66 起 view id 不再带 skip：GKD 的自动跳过规则会按 vid 匹配 skip，误点这枚开关。
            EventLog.get().log(EventLog.CAT_SERVICE,
                    "设置：应用进程存在时不预热 → " + (checked ? "开" : "关"));
        } else if (viewId == R.id.sw_hide_recents) {
            Prefs.setHideFromRecents(checked);
            // 立刻下发一次：本应用的任务就在眼前，不用等下一次回到前台才生效
            RecentsVisibility.apply(requireContext());
        } else if (viewId == R.id.sw_boot_autostart) {
            Prefs.setBootAutostart(checked);
            // 只影响「开机那一次」：这几天不用重启，下一次开机广播里就能看到这一行
            EventLog.get().log(EventLog.CAT_SERVICE,
                    checked ? "「开机自启」已打开：下次开机收到广播就拉起监听服务"
                            : "「开机自启」已关闭：下次开机不自动拉起监听服务");
        } else if (viewId == R.id.sw_watch_listener) {
            Prefs.setWatchListener(checked);
            applyWatchChange();
        } else if (viewId == R.id.sw_watch_accessibility) {
            Prefs.setWatchAccessibility(checked);
            applyWatchChange();
        } else if (viewId == R.id.sw_watch_service) {
            Prefs.setWatchService(checked);
            applyWatchChange();
        } else if (viewId == R.id.sw_watch_privileged) {
            Prefs.setWatchPrivileged(checked);
            applyWatchChange();
        }
    }

    /**
     * 四枚「什么算服务异常」的开关刚动过：让常驻服务那边当场重估一次（异常提醒随之出现或消失）。
     */
    private void applyWatchChange() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        HealthWatch.evaluate(context);
    }

    /**
     * windowingMode 那个开关：关着时输入框收起来（内容留着），免得看起来「改了没生效」。
     *
     * <p>值本身在 {@link Prefs} 里始终有效，开关只决定它上不上场。
     */
    private void updateWindowingEnabled() {
        boolean enabled = mSwitchWindowing.isChecked();
        mTilWindowing.setEnabled(enabled);
        mEditWindowing.setEnabled(enabled);
        if (!enabled) {
            mEditWindowing.setText(String.valueOf(Prefs.getActivityWindowingValue()));
        }
    }

    /** 「启用预热」下面那行小字（0.7.38-M6 / 59）：开关换了说法就换。 */
    private void updateWarmStateText() {
        if (mTextWarmState == null || mSwitchWarm == null) {
            return;
        }
        mTextWarmState.setText(mSwitchWarm.isChecked()
                ? R.string.settings_warm_state_on
                : R.string.settings_warm_state_off);
    }

    /**
     * 触发场景三行里的小字（0.7.38-M6 / 59）。
     *
     * <p>横幅与锁屏两行写的是这一条的<b>现状</b> —— 档位直接摊在行里，省得为了看一眼档位去点开弹窗。
     * 下拉那条是固定的一句话，不在这里刷。
     */
    private void updateTriggerSummaries() {
        if (mTextBannerSummary == null || mTextLockscreenSummary == null) {
            return;
        }
        String on = getString(R.string.settings_trigger_summary_on);
        String off = getString(R.string.settings_trigger_summary_off);

        String activityPart;
        int activity = Prefs.getBannerActivityMode();
        if (activity == Prefs.BANNER_ACTIVITY_ALWAYS) {
            activityPart = getString(R.string.settings_trigger_banner_always);
        } else if (activity == Prefs.BANNER_ACTIVITY_NEVER) {
            activityPart = getString(R.string.settings_trigger_banner_never);
        } else {
            activityPart = getString(R.string.settings_trigger_banner_defer);
        }
        mTextBannerSummary.setText(getString(R.string.settings_trigger_summary,
                Prefs.getBannerMode() == Prefs.MODE_LOOSE ? on : off, activityPart));

        String livePart;
        int live = Prefs.getLiveWarmMode();
        if (live == Prefs.LIVE_WARM_OFF) {
            livePart = getString(R.string.settings_trigger_live_off);
        } else if (live == Prefs.LIVE_WARM_STRICT) {
            livePart = getString(R.string.settings_trigger_live_strict);
        } else if (live == Prefs.LIVE_WARM_WIDE) {
            livePart = getString(R.string.settings_trigger_live_wide);
        } else {
            livePart = getString(R.string.settings_trigger_live_normal);
        }
        mTextLockscreenSummary.setText(getString(R.string.settings_trigger_summary,
                Prefs.getLockscreenMode() == Prefs.LOCKSCREEN_MODE_LEGACY ? on : off, livePart));
    }

    /**
     * 「横幅触发高级设置」弹窗（0.7.38-M6 / 59）。
     *
     * <p>即改即生效，只有一个「关闭」：这一页本来就是这个风格（开关与单选都是当场落库）。弹窗里改过
     * 之后，下面那行小字也跟着换说法。
     */
    private void showBannerAdvancedDialog() {
        Context context = requireContext();
        View body = LayoutInflater.from(context)
                .inflate(R.layout.dialog_banner_advanced, null, false);
        MaterialSwitch loose = body.findViewById(R.id.dlg_sw_banner_loose);
        RadioGroup activity = body.findViewById(R.id.dlg_rg_banner_act);
        TextInputLayout tilPoll = body.findViewById(R.id.dlg_til_ime_poll);
        TextInputEditText editPoll = body.findViewById(R.id.dlg_et_ime_poll);
        TextInputLayout tilDefer = body.findViewById(R.id.dlg_til_ime_defer);
        TextInputEditText editDefer = body.findViewById(R.id.dlg_et_ime_defer);

        // 先把现值摆上、再挂监听：不然「摆值」会被当成用户操作
        loose.setChecked(Prefs.getBannerMode() == Prefs.MODE_LOOSE);
        int activityMode = Prefs.getBannerActivityMode();
        activity.check(activityMode == Prefs.BANNER_ACTIVITY_NEVER
                ? R.id.dlg_rb_act_never
                : activityMode == Prefs.BANNER_ACTIVITY_ALWAYS
                        ? R.id.dlg_rb_act_always
                        : R.id.dlg_rb_act_defer);
        editPoll.setText(String.valueOf(Prefs.getImePollMs()));
        editDefer.setText(String.valueOf(Prefs.getImeDeferMaxMs()));
        applyImeFieldsEnabled(tilPoll, editPoll, tilDefer, editDefer);

        loose.setOnCheckedChangeListener((view, checked) -> {
            Prefs.setBannerMode(checked ? Prefs.MODE_LOOSE : Prefs.MODE_STRICT);
            updateTriggerSummaries();
        });
        activity.setOnCheckedChangeListener((group, checkedId) -> {
            Prefs.setBannerActivityMode(checkedId == R.id.dlg_rb_act_never
                    ? Prefs.BANNER_ACTIVITY_NEVER
                    : checkedId == R.id.dlg_rb_act_always
                            ? Prefs.BANNER_ACTIVITY_ALWAYS
                            : Prefs.BANNER_ACTIVITY_WHEN_NO_IME);
            applyImeFieldsEnabled(tilPoll, editPoll, tilDefer, editDefer);
            updateTriggerSummaries();
        });
        bindNumberField(editPoll,
                Prefs.IME_POLL_MS_MIN, Prefs.IME_POLL_MS_MAX,
                Prefs::getImePollMs, Prefs::setImePollMs,
                R.string.toast_ime_poll_saved, R.string.toast_ime_poll_invalid);
        bindNumberField(editDefer,
                Prefs.IME_DEFER_MAX_MS_MIN, Prefs.IME_DEFER_MAX_MS_MAX,
                Prefs::getImeDeferMaxMs, Prefs::setImeDeferMaxMs,
                R.string.toast_ime_defer_saved, R.string.toast_ime_defer_invalid);

        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_banner_advanced_title)
                .setView(body)
                .setPositiveButton(R.string.dialog_close, (dialog, which) -> {
                    // 弹窗一关，输入框跟着窗口一起没了，「失焦保存」那一路不一定来得及：关的时候补收一次
                    applyNumberField(editPoll,
                            Prefs.IME_POLL_MS_MIN, Prefs.IME_POLL_MS_MAX,
                            Prefs::getImePollMs, Prefs::setImePollMs,
                            R.string.toast_ime_poll_saved, R.string.toast_ime_poll_invalid);
                    applyNumberField(editDefer,
                            Prefs.IME_DEFER_MAX_MS_MIN, Prefs.IME_DEFER_MAX_MS_MAX,
                            Prefs::getImeDeferMaxMs, Prefs::setImeDeferMaxMs,
                            R.string.toast_ime_defer_saved, R.string.toast_ime_defer_invalid);
                })
                .show();
    }

    /**
     * 「锁屏触发高级设置」弹窗（0.7.38-M6 / 59）。
     *
     * <p>两块：普通通知的宽松判定，实时通知（流体云）预热的开关 + 复看范围三档。实时通知那一块只对
     * 「锁屏点亮时」这条触发点生效（值本来就是这个用途），所以它跟着这里的开关走。
     */
    private void showLockscreenAdvancedDialog() {
        Context context = requireContext();
        View body = LayoutInflater.from(context)
                .inflate(R.layout.dialog_lockscreen_advanced, null, false);
        MaterialSwitch loose = body.findViewById(R.id.dlg_sw_lockscreen_loose);
        MaterialSwitch live = body.findViewById(R.id.dlg_sw_live_warm);
        View rangeBlock = body.findViewById(R.id.dlg_ll_live_range);
        Slider slider = body.findViewById(R.id.dlg_slider_live_range);
        TextView labelStrict = body.findViewById(R.id.dlg_tv_live_strict);
        TextView labelNormal = body.findViewById(R.id.dlg_tv_live_normal);
        TextView labelWide = body.findViewById(R.id.dlg_tv_live_wide);

        loose.setChecked(Prefs.getLockscreenMode() == Prefs.LOCKSCREEN_MODE_LEGACY);
        int mode = Prefs.getLiveWarmMode();
        live.setChecked(mode != Prefs.LIVE_WARM_OFF);
        slider.setValue(liveWarmSliderValue(mode));
        rangeBlock.setVisibility(live.isChecked() ? View.VISIBLE : View.GONE);
        tintLiveRangeLabels(labelStrict, labelNormal, labelWide, (int) slider.getValue());

        loose.setOnCheckedChangeListener((view, checked) -> {
            Prefs.setLockscreenMode(checked
                    ? Prefs.LOCKSCREEN_MODE_LEGACY
                    : Prefs.LOCKSCREEN_MODE_PRECISE);
            updateTriggerSummaries();
        });
        live.setOnCheckedChangeListener((view, checked) -> {
            // 关掉就是「关闭」这一档；打开时按滑杆当前停的地方算（默认停在「中等」）
            Prefs.setLiveWarmMode(checked
                    ? liveWarmModeFromSlider((int) slider.getValue())
                    : Prefs.LIVE_WARM_OFF);
            rangeBlock.setVisibility(checked ? View.VISIBLE : View.GONE);
            updateTriggerSummaries();
        });
        slider.addOnChangeListener((view, value, fromUser) -> {
            if (live.isChecked()) {
                Prefs.setLiveWarmMode(liveWarmModeFromSlider((int) value));
            }
            tintLiveRangeLabels(labelStrict, labelNormal, labelWide, (int) value);
            updateTriggerSummaries();
        });

        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_lockscreen_advanced_title)
                .setView(body)
                .setPositiveButton(R.string.dialog_close, null)
                .show();
    }

    /** 三档 → 滑杆上的位置（0 严格 / 1 中等 / 2 宽松）；关着时停在默认的「中等」。 */
    private static int liveWarmSliderValue(int mode) {
        if (mode == Prefs.LIVE_WARM_STRICT) {
            return 0;
        }
        if (mode == Prefs.LIVE_WARM_WIDE) {
            return 2;
        }
        return 1;
    }

    /** 滑杆上的位置 → 三档。 */
    private static int liveWarmModeFromSlider(int value) {
        if (value <= 0) {
            return Prefs.LIVE_WARM_STRICT;
        }
        if (value >= 2) {
            return Prefs.LIVE_WARM_WIDE;
        }
        return Prefs.LIVE_WARM_NORMAL;
    }

    /** 三个档位标签里，当前这一档用主色，另外两个回到灰字。 */
    private void tintLiveRangeLabels(TextView strict, TextView normal, TextView wide, int value) {
        tintLabel(strict, value == 0);
        tintLabel(normal, value == 1);
        tintLabel(wide, value == 2);
    }

    /** 读主题里的一个颜色属性；读不到就保持原样（不改颜色）。 */
    private void tintLabel(TextView label, boolean selected) {
        int color = themeColor(selected
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurfaceVariant);
        if (color != 0) {
            label.setTextColor(color);
        }
    }

    private int themeColor(int attribute) {
        Context context = requireContext();
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true)) {
            return 0;
        }
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        if (value.resourceId != 0) {
            return context.getColor(value.resourceId);
        }
        return 0;
    }

    /**
     * 两个数值框只在「待输入法收回后拉起 activity」这一档上有意义，其余两档收起来（禁掉）。
     *
     * <p>与改动前同一套做法：值本身留在 {@link Prefs} 里，切回默认档时按已保存的值重填一次，免得
     * 框里留着一个看起来生效、其实没上场的手改数。
     */
    private static void applyImeFieldsEnabled(TextInputLayout tilPoll, TextInputEditText editPoll,
                                              TextInputLayout tilDefer, TextInputEditText editDefer) {
        boolean enabled = Prefs.getBannerActivityMode() == Prefs.BANNER_ACTIVITY_WHEN_NO_IME;
        tilPoll.setEnabled(enabled);
        editPoll.setEnabled(enabled);
        tilDefer.setEnabled(enabled);
        editDefer.setEnabled(enabled);
        if (!enabled) {
            editPoll.setText(String.valueOf(Prefs.getImePollMs()));
            editDefer.setText(String.valueOf(Prefs.getImeDeferMaxMs()));
        }
    }

    /**
     * 给一个「填整数」的字段装上去：回车（或点别处失焦）时收下这个值。
     *
     * <p>三个字段（windowingMode 值、同应用冷却、串行间隔）共用这一套：
     * 只看给定范围内的整数，越界或空就退回<b>已保存</b>的那个值并当场说清楚 ——
     * 这些值都会被原样用出去（拼进命令或当成时间），宁可不改也不能猜。
     */
    private void bindNumberField(TextInputEditText field, int min, int max,
                                 IntSupplier current, IntConsumer save,
                                 int savedToast, int invalidToast) {
        field.setOnEditorActionListener((v, actionId, event) -> {
            applyNumberField(field, min, max, current, save, savedToast, invalidToast);
            return false;   // 交回默认处理：软键盘该收就收
        });
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyNumberField(field, min, max, current, save, savedToast, invalidToast);
            }
        });
    }

    private void applyNumberField(TextInputEditText field, int min, int max,
                                  IntSupplier current, IntConsumer save,
                                  int savedToast, int invalidToast) {
        if (field == null) {
            return;
        }
        String raw = field.getText() == null ? "" : field.getText().toString().trim();
        int now = current.getAsInt();
        int value = 0;
        boolean parsed = false;
        try {
            value = Integer.parseInt(raw);
            parsed = true;
        } catch (NumberFormatException e) {
            // 解析失败由下面的 !parsed 处理；不能再用 Integer.MIN_VALUE 当哨兵，
            // 因为 windowingMode 现在允许整个 int 范围，MIN_VALUE 本身也是合法输入。
        }
        if (raw.isEmpty() || !parsed || value < min || value > max) {
            field.setText(String.valueOf(now));
            field.setSelection(field.getText().length());
            Toast.makeText(requireContext(), getString(invalidToast, now),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (value != now) {
            save.accept(value);
            Toast.makeText(requireContext(), getString(savedToast, value),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 「开机自启」卡的状态行：写安全设置的权限有没有，以及无障碍服务现在开着没有。
     *
     * <p>按钮<b>不</b>变灰（0.7.20-M6 / 41 起）：那条 {@code pm grant} 是幂等的，已授予也照样可以
     * 再点一次，界面上那句「如已执行请忽略」就是为它写的。
     *
     * <p>这里<b>只读不记</b>（0.7.21-M6 / 42 起）：观察与补写都在开进程那一刻由
     * {@code App.onCreate} 的 {@link AccessibilityAutostart#poll} 统一做掉，手动打开应用永远
     * 不改系统设置 —— 走到这一页的用户，可能正是来关它的。
     */
    private void refreshAccessibilityState() {
        if (mButtonAccessibilityGrant == null || mTextAccessibilityState == null) {
            return;
        }
        Context context = requireContext();
        mTextAccessibilityState.setText(getString(R.string.settings_accessibility_state_line,
                getString(AccessibilityGrant.permissionGranted(context)
                        ? R.string.settings_accessibility_state_on
                        : R.string.settings_accessibility_state_off),
                getString(AccessibilityGrant.isOn(context)
                        ? R.string.settings_accessibility_service_on
                        : R.string.settings_accessibility_service_off)));
    }

    /**
     * 「授予权限」：把那条 adb 语句摊开给用户看。
     *
     * <p>通道可用性是现读的：Shizuku 的检查只是一次 binder，root 的要 spawn 一次 {@code su -c id}
     * （可能弹 Magisk 授权框），所以放到特权线程上查完再弹对话框 —— 有通道就右下角给「执行」，
     * 没有就给「复制」，由用户自己粘到电脑的终端跑。
     */
    private void showGrantDialog() {
        Privileged.post(() -> {
            PrivilegedBridge bridge = Privileged.active();
            Privileged.onMain(() -> {
                if (isAdded()) {
                    showGrantDialog(bridge);
                }
            });
        });
    }

    private void showGrantDialog(@Nullable PrivilegedBridge bridge) {
        Context context = requireContext();
        View body = LayoutInflater.from(context)
                .inflate(R.layout.dialog_accessibility_grant, null, false);
        ((TextView) body.findViewById(R.id.tv_grant_body))
                .setText(Markup.of(context, R.string.dialog_accessibility_body));
        ((TextView) body.findViewById(R.id.tv_grant_command))
                .setText(AccessibilityGrant.grantDisplayText(context));
        ((TextView) body.findViewById(R.id.tv_grant_state)).setText(
                AccessibilityGrant.permissionGranted(context)
                        ? R.string.dialog_accessibility_state_done
                        : R.string.dialog_accessibility_state_todo);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_accessibility_title)
                .setView(body)
                .setNegativeButton(R.string.dialog_close, null);
        if (bridge != null) {
            builder.setPositiveButton(R.string.action_accessibility_run,
                    (dialog, which) -> runGrant(bridge));
        } else {
            builder.setPositiveButton(R.string.action_accessibility_copy,
                    (dialog, which) -> copyGrantCommand());
        }
        builder.show();
    }

    /**
     * 代跑那条 {@code pm grant}：跑完关掉对话框，弹成功 / 失败，原始输出进「权限」日志。
     *
     * <p>「成没成」不看出参：{@link AccessibilityGrant#permissionGranted} 直接问 PackageManager ——
     * `pm grant` 对 shell（也就是 Shizuku、以及 adb 用户自己跑）和 root 都是放行的，
     * 授完本应用这一个进程立刻就能读到。
     */
    private void runGrant(PrivilegedBridge bridge) {
        final Context appContext = requireContext().getApplicationContext();
        final String command = AccessibilityGrant.grantCommand(appContext);
        Privileged.post(() -> {
            ShellResult result = bridge.run(command);
            boolean granted = AccessibilityGrant.permissionGranted(appContext);
            EventLog.get().log(EventLog.CAT_PERM,
                    "授予写入安全设置权限：" + (granted ? "已授予" : "仍未授予")
                            + "（" + bridge.name() + "）",
                    "命令 " + command + " → "
                            + (result.ran ? "退出码 " + result.exitCode : "未执行")
                            + " " + result.brief(200)
                            + "；checkSelfPermission=" + granted);
            Privileged.onMain(() -> {
                if (!isAdded()) {
                    return;
                }
                refreshAccessibilityState();
                Toast.makeText(requireContext(),
                        granted ? R.string.toast_accessibility_grant_done
                                : R.string.toast_accessibility_grant_failed,
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    /** 复制给用户自己粘：命令已经带好 adb shell 前缀。 */
    private void copyGrantCommand() {
        ClipboardManager clipboard = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;   // 拿不到剪贴板：命令就在对话框上面，长按选中也能复制
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("NotificationFixer",
                AccessibilityGrant.grantDisplayText(requireContext())));
        Toast.makeText(requireContext(), R.string.toast_accessibility_copied,
                Toast.LENGTH_LONG).show();
    }
}

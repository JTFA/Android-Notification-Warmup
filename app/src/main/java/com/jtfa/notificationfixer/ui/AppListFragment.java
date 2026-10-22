package com.jtfa.notificationfixer.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.jtfa.notificationfixer.R;
import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.Markup;
import com.jtfa.notificationfixer.core.Prefs;
import com.jtfa.notificationfixer.probe.ActivityInventory;
import com.jtfa.notificationfixer.probe.ComponentInventory;
import com.jtfa.notificationfixer.probe.WarmTarget;
import com.jtfa.notificationfixer.warm.ActivityWarmStrategy;
import com.jtfa.notificationfixer.warm.WarmEngine;
import com.jtfa.notificationfixer.warm.WarmEvidence;
import com.jtfa.notificationfixer.warm.WarmResult;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 名单页：选模式、挑应用、顺手试一次预热。
 *
 * <p>列表只列有桌面图标的应用 —— 这是用户真正会点开、也就真正需要预热的那批。
 */
public class AppListFragment extends Fragment implements AppListAdapter.Listener {

    /** 读取应用列表是重活（要遍历 PackageManager），固定放一条后台线程。 */
    private static final ExecutorService sLoader = Executors.newSingleThreadExecutor();

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private final List<AppEntry> mAll = new ArrayList<>();

    private AppListAdapter mAdapter;
    private TextInputEditText mSearch;
    private TextView mEmpty;
    private TextView mCount;
    private TextView mModeHint;
    private View mProgress;
    private View mHeader;
    private MaterialSwitch mBlacklistSwitch;

    private String mQuery = "";

    /** 列表是否已经载入过一次（用于决定 onResume 要不要重探）。 */
    private boolean mLoadedOnce;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_app_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mSearch = view.findViewById(R.id.search_input);
        mEmpty = view.findViewById(R.id.app_empty);
        mProgress = view.findViewById(R.id.app_progress);

        // 黑名单开关与计数住在标题栏那一行（activity_main.xml 的 app_list_controls），
        // 不在本页布局里；只有名单页露出它们。
        mHeader = requireActivity().findViewById(R.id.app_list_controls);
        if (mHeader != null) {
            mCount = mHeader.findViewById(R.id.tv_selected_count);
            mModeHint = mHeader.findViewById(R.id.tv_mode_hint);
            mBlacklistSwitch = mHeader.findViewById(R.id.sw_blacklist_mode);
            if (mBlacklistSwitch != null) {
                // 模式只认 Prefs：挡住开关自己保存，别让 Activity 重建时先恢复成旧状态。
                mBlacklistSwitch.setSaveEnabled(false);
                mBlacklistSwitch.setSaveFromParentEnabled(false);
            }
            mHeader.setVisibility(View.VISIBLE);
        }

        mAdapter = new AppListAdapter(this);
        RecyclerView list = view.findViewById(R.id.app_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(mAdapter);

        bindModeSwitch();
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mQuery = s == null ? "" : s.toString();
                applyFilter();
            }
        });

        loadApps(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mHeader != null) {
            mHeader.setVisibility(View.VISIBLE);
        }
        bindModeSwitch();
        // 「强制停止 / 禁用直到使用」这两个结论会随用户打开一次应用而消失，
        // 所以每次回到本页都重探一遍，行里的提示不会僵在旧状态上。
        if (!mLoadedOnce) {
            return;
        }
        ComponentInventory.clear();
        loadApps(false);
    }

    @Override
    public void onPause() {
        super.onPause();
        // 标题栏这一行是 Activity 的，离开本页就收起，别让日志 / 设置 / 状态页也看到它。
        if (mHeader != null) {
            mHeader.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        if (mBlacklistSwitch != null) {
            mBlacklistSwitch.setOnCheckedChangeListener(null);
        }
        if (mHeader != null) {
            mHeader.setVisibility(View.GONE);
        }
        mHeader = null;
        mBlacklistSwitch = null;
        mCount = null;
        mModeHint = null;
        super.onDestroyView();
    }

    // ---------------------------------------------------------------- 模式

    private void bindModeSwitch() {
        if (mBlacklistSwitch == null) {
            return;
        }
        // 先摘监听再填初值：setChecked() 不能被当成用户切换写库。
        mBlacklistSwitch.setOnCheckedChangeListener(null);
        mBlacklistSwitch.setChecked(!Prefs.isWhitelistMode());
        mBlacklistSwitch.jumpDrawablesToCurrentState();
        mBlacklistSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setListMode(isChecked
                    ? Prefs.LIST_MODE_BLACKLIST : Prefs.LIST_MODE_WHITELIST);
            // 两个模式各有一份名单，切换后整表重画勾选状态、顺便重排（勾过的在前）
            applyFilter();
        });
        updateModeTexts();
    }

    private void updateModeTexts() {
        if (mCount == null) {
            return;
        }
        boolean whitelist = Prefs.isWhitelistMode();
        int count = Prefs.selectedCount();
        mCount.setText(getString(whitelist
                        ? R.string.applist_selected_count_whitelist
                        : R.string.applist_selected_count_blacklist,
                count));
        if (mModeHint != null) {
            mModeHint.setText(whitelist
                    ? R.string.applist_mode_hint_whitelist
                    : R.string.applist_mode_hint_blacklist);
        }
    }

    // ---------------------------------------------------------------- 列表

    private void loadApps(boolean showProgress) {
        if (showProgress) {
            mProgress.setVisibility(View.VISIBLE);
        }
        mEmpty.setVisibility(View.GONE);
        final Context appContext = requireContext().getApplicationContext();
        sLoader.execute(() -> {
            final List<AppEntry> entries = load(appContext);
            sMain.post(() -> {
                if (!isAdded() || mAdapter == null) {
                    return;
                }
                mAll.clear();
                mAll.addAll(entries);
                mLoadedOnce = true;
                mProgress.setVisibility(View.GONE);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        String query = mQuery.trim().toLowerCase(Locale.ROOT);
        Set<String> selected = Prefs.getSelectedPackages();
        List<AppEntry> filtered = new ArrayList<>();
        for (AppEntry entry : mAll) {
            if (query.isEmpty()
                    || entry.label.toLowerCase(Locale.ROOT).contains(query)
                    || entry.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(entry);
            }
        }
        // 勾过的排最前面：那才是「我挑出来的」那一批，不用在几百行里翻。
        // 稳定排序 ⇒ 组内仍是应用名顺序，跟没勾的那一段同一套排法。
        Collections.sort(filtered, (left, right) -> Boolean.compare(
                selected.contains(right.packageName), selected.contains(left.packageName)));

        // 唤醒方式整份交给适配器：逐行去读 SharedPreferences 会在滚动时反复解析同一个集合
        mAdapter.submitAll(filtered, selected, Prefs.getAppWeights(),
                Prefs.getActivityWakePackages());
        updateModeTexts();
        mEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 只收有桌面图标的应用，按中文习惯排序。 */
    private static List<AppEntry> load(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(launcher, 0);
        String self = context.getPackageName();

        Map<String, AppEntry> unique = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null || activity.packageName == null) {
                continue;
            }
            if (self.equals(activity.packageName) || unique.containsKey(activity.packageName)) {
                continue;
            }
            CharSequence label;
            try {
                label = info.loadLabel(packageManager);
            } catch (Exception e) {
                label = activity.packageName;
            }
            Drawable icon = null;
            try {
                icon = info.loadIcon(packageManager);
            } catch (Exception ignored) {
                // 少数应用图标读不出来，列表里留空即可
            }
            // 探测放在这里（后台线程，跟列表一起做完），滚动时才不会有 binder 往返
            WarmTarget target = ComponentInventory.resolve(context, activity.packageName);
            unique.put(activity.packageName, new AppEntry(activity.packageName,
                    label == null ? activity.packageName : label.toString(), icon, target));
        }

        List<AppEntry> entries = new ArrayList<>(unique.values());
        Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(entries, (left, right) -> collator.compare(left.label, right.label));
        return entries;
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public void onSelectedChanged(String packageName, boolean selected) {
        Prefs.setPackageSelected(packageName, selected);
        // 勾上就把这一行挪到最前面（取消勾选则退回它按应用名该在的位置），
        // 所以这里要重排整表，不只是刷新那一个勾选框。
        applyFilter();
    }

    /**
     * 点行右边的「设置」（或长按一行）：这个应用的设置卡。
     *
     * <p>三块内容 —— 完整包名、预热权重，以及<b>唤醒方式</b>（0.7.5 起逐应用二选一）：
     * 「唤醒后台（Provider → Service → 广播接收器）」或「拉起 Activity」。
     * 选了后者就不再尝试另外三条，选哪个 Activity 由「选 Activity…」定。
     * 0.7.61-M6 / 82 起「尝试后台启动activity」也在这里逐应用设置，位于「选 Activity…」下方；
     * 它不是当前的唤醒方式，只在选择「拉起 Activity」时可操作。
     * 这不是「点了就关」的对话框：这是设置卡，改完自己按关闭。
     */
    @Override
    public void onSettingsRequested(String packageName) {
        View body = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_app_settings, null, false);
        // 行里的包名最多两行、再长省略，所以全名放这里；可选可复制，拿去跑 adb 最省事
        TextView packageLine = body.findViewById(R.id.tv_settings_package);
        packageLine.setText(getString(R.string.applist_settings_package, packageName));
        // 一句说明：权重只影响一次触发里谁先排上队
        TextView weightMessage = body.findViewById(R.id.tv_settings_weight_message);
        weightMessage.setText(R.string.applist_settings_weight_message);

        String[] weightNames = getResources().getStringArray(R.array.applist_weight_names);
        int[] weightLabelIds = {R.id.tv_weight_0, R.id.tv_weight_1, R.id.tv_weight_2};
        for (int i = 0; i < weightLabelIds.length; i++) {
            TextView label = body.findViewById(weightLabelIds[i]);
            label.setText(weightNames.length == 0
                    ? String.valueOf(i)
                    : weightNames[Math.min(i, weightNames.length - 1)]);
        }
        final SeekBar weightBar = body.findViewById(R.id.sb_weight);
        weightBar.setMax(Math.max(1, weightNames.length - 1));
        weightBar.setProgress(Math.max(0,
                Math.min(Prefs.getAppWeight(packageName), weightBar.getMax())), false);
        weightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                Prefs.setAppWeight(packageName, progress);
                applyFilter();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        final RadioGroup methodGroup = body.findViewById(R.id.rg_method);
        final TextView activityState = body.findViewById(R.id.tv_activity_state);
        final MaterialButton pick = body.findViewById(R.id.btn_activity_pick);
        final MaterialSwitch behindSwitch = body.findViewById(R.id.sw_app_activity_behind);

        // 初值：先 check 再挂监听（顺序反了会把「填初值」当成用户改设置）
        methodGroup.check(Prefs.isActivityWakeMode(packageName)
                ? R.id.rb_method_activity : R.id.rb_method_routes);
        behindSwitch.setChecked(Prefs.isActivityAvoidMoveToFrontEnabled(packageName));
        behindSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            Prefs.setActivityAvoidMoveToFrontEnabled(packageName, checked);
            EventLog.get().log(EventLog.CAT_SERVICE,
                    "设置：" + packageName + " 尝试后台启动activity → "
                            + (checked ? "开" : "关"));
        });
        refreshActivitySection(activityState, behindSwitch, packageName);

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.applist_settings_title, labelOf(packageName)))
                .setView(body)
                .setNegativeButton(R.string.applist_settings_close, null)
                .create();

        methodGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean activity = checkedId == R.id.rb_method_activity;
            Prefs.setActivityWakeMode(packageName, activity);
            // 换路就是换目标，缓存的探测结论必须清掉，否则列表行里还是旧结论
            ComponentInventory.invalidate(packageName);
            refreshActivitySection(activityState, behindSwitch, packageName);
            loadApps(false);
            // 刚切成「拉起 Activity」、又还没挑过组件时，直接把候选列表摆出来 ——
            // 这就是这一步要用户做的事（挑完按「用这个」，或按取消让它自动挑）
            if (activity && Prefs.getAppActivity(packageName) == null) {
                dialog.dismiss();
                showActivityPicker(packageName);
            }
        });
        // 列表按钮一直可用：先去挑、看看再改方式也行（放在这种方式里才生效这件事写在状态行里）
        pick.setOnClickListener(v -> {
            dialog.dismiss();
            showActivityPicker(packageName);
        });
        dialog.show();
    }

    /**
     * 设置卡里那行结论：这个应用现在走哪条路、会用什么组件。
     *
     * <p>「唤醒后台」按组件探测结果给红 / 绿结论；「拉起 Activity」固定展示实际会打开的那一个
     * 完整组件名，只要选出了 Activity 就按可预热给绿底。两种方式都不再用「唤醒方式：…」这句
     * 前缀 —— 上面的单选项已经说清了方式，这里只留结论。
     *
     * <p>同时按当前方式启用/禁用「尝试后台启动activity」开关：唤醒后台时置灰，但保留已保存的值，
     * 切回「拉起 Activity」后原值还在。
     */
    private void refreshActivitySection(TextView state, MaterialSwitch behindSwitch,
                                        String packageName) {
        boolean activity = Prefs.isActivityWakeMode(packageName);
        behindSwitch.setEnabled(activity);
        boolean ok;
        String text;
        if (activity) {
            ActivityInventory.Result result =
                    ActivityInventory.resolve(requireContext(), packageName);
            ActivityInventory.Candidate chosen = result.chosen();
            if (chosen != null) {
                text = getString(R.string.applist_state_now_activity,
                        getString(result.chosenByUser()
                                ? R.string.applist_activity_chosen_by_user
                                : R.string.applist_activity_chosen_auto),
                        chosen.component.flattenToString());
                ok = true;
            } else if (!result.candidates.isEmpty()) {
                // 有导出组件、只是自动推断没挑中：这条路本身可用，让用户从下方挑一个。
                text = getString(R.string.applist_state_now_activity_pick);
                ok = true;
            } else {
                // 一个导出 Activity 都没有，这条路真的走不通，不能硬写「可预热」。
                text = getString(R.string.applist_state_now_unavailable,
                        ActivityInventory.stateText(requireContext(), packageName));
                ok = false;
            }
        } else {
            WarmTarget target = ComponentInventory.resolve(requireContext(), packageName);
            ok = target.isAvailable();
            text = ok
                    ? getString(R.string.applist_state_now_available, target.describe())
                    : getString(R.string.applist_state_now_unavailable, target.reason);
        }
        state.setText(text);
        state.setBackgroundTintList(ColorStateList.valueOf(requireContext().getColor(
                ok ? R.color.chip_ok_bg : R.color.chip_bad_bg)));
        state.setTextColor(requireContext().getColor(
                ok ? R.color.chip_ok_text : R.color.chip_bad_text));
    }

    /**
     * 唤醒 Activity 列表：列出这个应用<b>所有已导出</b>的 Activity，标出推断结果与风险。
     *
     * <p>推断（不建窗口 / 透明 / 会显示）来自对方包里的主题资源，只能算推断 —— 所以每一行都有
     * 「正常打开」和「尝试后台打开」两个按钮：前者普通启动，后者强制 noFront，但和正式触发共用同一套 launch-behind / 回退决策。
     * 界面有没有出来自己看，这是用户挑组件时唯一的判据。
     */
    private void showActivityPicker(final String packageName) {
        final ActivityInventory.Result result =
                ActivityInventory.resolve(requireContext(), packageName);
        // 完整候选（含证据）写进诊断日志：对话框一行放不下，日志里随时能核对。
        // 一个应用可能有一两百个导出 Activity，所以只写前 40 行，末尾补一句总数。
        logCandidateLines(packageName, result);

        if (result.candidates.isEmpty()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.applist_activity_empty_title)
                    .setMessage(R.string.applist_activity_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        final View body = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_activity_pick, null, false);
        TextView hint = body.findViewById(R.id.tv_pick_hint);
        // 这一句里有 <b>：TextView 不解析 HTML，之前 <b> 是照字面显示出来的，这里补上解析
        hint.setText(Markup.of(requireContext(), R.string.applist_pick_hint, result.candidates.size()));

        RecyclerView list = body.findViewById(R.id.rv_candidates);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        // 高度按条数算，最多半屏：AlertDialog 的自定义 view 外面套着一层 ScrollView，
        // 让它自己估高会把上百个候选全部量一遍。
        // 先按 112dp 一行估（名字 + 证据 + 按钮行），等第一帧量出真实行高再修正一次 ——
        // 0.7.9-M6 / 30 起长类名会折行，0.7.59-M6 / 80 起按钮另起一行，按旧高度会把最后一行切掉。
        final int rows = Math.max(1, Math.min(result.candidates.size(), 6));
        final int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
        float density = getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams params = list.getLayoutParams();
        params.height = Math.min((int) (rows * 112 * density), max);
        list.setLayoutParams(params);

        final ActivityPickAdapter adapter = new ActivityPickAdapter(null);
        adapter.setListener(new ActivityPickAdapter.Listener() {
            @Override
            public void onSelect(int position) {
                adapter.setSelected(position);
            }

            @Override
            public void onOpen(int position) {
                // 点测试按钮的人对这一个有兴趣，顺手也选中它（最终还是要按「用这个」才存下）
                adapter.setSelected(position);
                openOneActivity(packageName, adapter, position, false);
            }

            @Override
            public void onOpenBehind(int position) {
                adapter.setSelected(position);
                openOneActivity(packageName, adapter, position, true);
            }

            @Override
            public void onCopyComponent(int position) {
                copyComponent(adapter.item(position));
            }
        });
        adapter.submit(result.candidates, indexOfCurrent(result));
        list.setAdapter(adapter);
        // 量到真实行高之后把高度修正到位：取当前已布局那几行里最高的一行 × 条数（封顶半屏）。
        // 太矮会切掉最后一行、太高会在列表下面留一块空白，都只差几 dp 到几十 dp。
        list.post(() -> {
            int tallest = 0;
            for (int i = 0; i < list.getChildCount(); i++) {
                tallest = Math.max(tallest, list.getChildAt(i).getHeight());
            }
            if (tallest <= 0) {
                return;
            }
            int fixed = Math.min(rows * tallest, max);
            ViewGroup.LayoutParams p = list.getLayoutParams();
            if (p.height != fixed) {
                p.height = fixed;
                list.setLayoutParams(p);
            }
        });

        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.applist_activity_title, labelOf(packageName)))
                .setView(body)
                .setPositiveButton(R.string.applist_activity_use,
                        (d, w) -> applyActivityPick(packageName, result.candidates,
                                adapter.selected()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 当前指定的那个在候选列表里的下标；没指定就用自动挑的那个，都没有 = -1。 */
    private static int indexOfCurrent(ActivityInventory.Result result) {
        ActivityInventory.Candidate current = result.chosenByUser() ? result.picked : result.auto;
        return current == null ? -1 : result.candidates.indexOf(current);
    }

    /**
     * 长按一行：把完整组件名复制走 —— 行里显示的是去掉包名的短写（{@code .…}），
     * 而 {@code am start -n} 要的是 {@code 包名/类名}，复制这一份最省事。
     *
     * <p>Android 13 起系统自己也会弹「已复制」确认，我们这句只是把复制到的内容说清楚。
     */
    private void copyComponent(ActivityInventory.Candidate candidate) {
        if (candidate == null || candidate.component == null) {
            return;
        }
        String full = candidate.component.flattenToShortString();
        ClipboardManager clipboard =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.applist_activity_copy_label), full));
        }
        Toast.makeText(requireContext(),
                getString(R.string.applist_activity_copied, full), Toast.LENGTH_SHORT).show();
    }

    private void logCandidateLines(String packageName, ActivityInventory.Result result) {
        StringBuilder detail = new StringBuilder();
        List<String> lines = ActivityInventory.detailLines(result);
        int limit = Math.min(lines.size(), 40);
        for (int i = 0; i < limit; i++) {
            detail.append(detail.length() == 0 ? "" : "\n").append(lines.get(i));
        }
        if (lines.size() > limit) {
            detail.append("\n…还有 ").append(lines.size() - limit).append(" 行没写进日志");
        }
        EventLog.get().log(EventLog.CAT_DIAG, "Activity 候选 " + packageName, detail.toString());
    }

    /**
     * 「正常打开」/「尝试后台打开」：就试这一行。
     *
     * <p>与列表页那个「试一次」的区别是它<b>不</b>经过「这个应用该走哪条路」的判定 ——
     * 用户在挑组件，试的必须是手指点的那一个。{@code behind} = 强制 noFront；
     * 这条测试路不读该应用的「尝试后台启动activity」开关，但启动决策与正式触发相同（含回退）。
     */
    private void openOneActivity(String packageName, final ActivityPickAdapter adapter,
                                 final int position, boolean behind) {
        WarmEngine engine = WarmEngine.get();
        if (engine == null) {
            Toast.makeText(requireContext(), R.string.applist_engine_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        ActivityInventory.Candidate candidate = adapter.item(position);
        if (candidate == null) {
            return;
        }
        adapter.setTesting(position, behind);
        WarmEngine.Callback callback = result -> {
            adapter.setTesting(-1, false);
            if (isAdded()) {
                int title = behind
                        ? R.string.applist_open_behind_result_title
                        : R.string.applist_open_result_title;
                showWarmResult(result, getString(title, "." + candidate.shortName()));
            }
        };
        if (behind) {
            engine.warmActivityBehindNowAsync(packageName, candidate.component, callback);
        } else {
            engine.warmActivityNowAsync(packageName, candidate.component, callback);
        }
    }

    /** 存下唤醒方式：把选中的组件记为这个应用的唤醒 Activity，并确保方式就是「拉起 Activity」。 */
    private void applyActivityPick(String packageName,
                                   List<ActivityInventory.Candidate> candidates, int selection) {
        String component = null;
        if (selection >= 0 && selection < candidates.size()) {
            component = ActivityInventory.flatten(candidates.get(selection).component);
        }
        Prefs.setAppActivity(packageName, component);
        if (component != null) {
            // 「用这个」= 我要用「拉起 Activity」：不顺手把方式切过去的话，挑完看起来像没生效
            Prefs.setActivityWakeMode(packageName, true);
        }
        // 结论缓存在 ComponentInventory 里，指定变了必须清掉，否则行里还是旧结论
        ComponentInventory.clear();
        loadApps(false);
    }

    @Override
    public void onTestWarm(String packageName, MaterialButton button) {
        testWarm(packageName, button);
    }

    /** 「试一次」的实际流程；{@code button} 可以为 null（唤醒方式对话框里的测试没有按钮）。 */
    private void testWarm(String packageName, MaterialButton button) {
        WarmEngine engine = WarmEngine.get();
        if (engine == null) {
            Toast.makeText(requireContext(), R.string.applist_engine_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        if (button != null) {
            button.setEnabled(false);
            button.setText(R.string.applist_testing);
        }
        engine.warmNowAsync(packageName, result -> {
            if (button != null) {
                button.setEnabled(true);
                button.setText(R.string.applist_test);
            }
            if (isAdded()) {
                showWarmResult(result);
            }
        });
    }

    /**
     * 「试一次」的结果。
     *
     * <p>有探活证据就用对话框而不是 Toast：证据是这次实验的重点，
     * 「这次真的把进程拉起来了」和「进程本来就在」必须一眼分得清。走 Activity 路时
     * 还会多一段原始读数（本次创建窗口数量 / 顶层 / 有被拦时才出现的后台启动限制日志）。
     */
    private void showWarmResult(WarmResult result) {
        showWarmResult(result, null);
    }

    /** {@code intro} 非 null 时放在第一行（Activity 列表里测试的那一个是谁）。 */
    private void showWarmResult(WarmResult result, String intro) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (intro != null) {
            text.append(intro).append('\n');
        }
        text.append(firstParagraphForUi(result)).append('\n');
        if (result.launchMode != null) {
            text.append(getString(R.string.applist_open_mode, result.launchMode)).append('\n');
        }
        WarmEvidence evidence = result.evidence;
        if (evidence != null) {
            text.append('\n').append(evidence.describe()).append('\n');
        }
        if (result.activityReadout != null && !result.activityReadout.isEmpty()) {
            text.append('\n').append(result.activityReadout).append('\n');
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.applist_test_result_title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * 结果弹窗的第一段。日志仍用 {@link WarmResult#describe()} 里的原始诊断，这里只把
     * 两条太长的系统状态说明换成人话，并去掉 Activity 路只该进日志的尾句。
     */
    private String firstParagraphForUi(WarmResult result) {
        return result.describe()
                .replace(ActivityWarmStrategy.LOG_DETAIL_DELIVERED_SUFFIX, "")
                .replace(ComponentInventory.LOG_DETAIL_STOPPED,
                        getString(R.string.applist_warm_ui_check_association))
                .replace(ComponentInventory.LOG_DETAIL_STOPPED_AND_DISABLED,
                        getString(R.string.applist_warm_ui_system_disabled_recover));
    }

    /** 列表里找应用名，找不到就退回包名（对话框标题用）。 */
    private String labelOf(String packageName) {
        for (AppEntry entry : mAll) {
            if (entry.packageName.equals(packageName)) {
                return entry.label;
            }
        }
        return packageName;
    }
}

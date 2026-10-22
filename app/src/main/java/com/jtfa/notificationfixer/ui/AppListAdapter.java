package com.jtfa.notificationfixer.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.jtfa.notificationfixer.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 名单页的应用列表。
 *
 * <p>勾选状态与模式相关：黑名单模式下勾选表示排除，白名单模式下勾选表示预热，
 * 文案由宿主负责，所以这里只管「选中集合」。
 *
 * <p>顺序也由宿主排好（勾过的在前），这里不重排。
 *
 * <p>0.7.11-M6 / 32 起一行是「上块 + 下块」：上块是勾选框 / 图标 / 应用名 + 包名 / 设置 + 试一次，
 * 下块是三枚圆角标签（权重、唤醒方式、结论）。权重与唤醒方式是两档设置，摆在行里不用进设置卡
 * 就能看见；结论那枚按 {@link #bindVerdict} 上绿 / 红底。两枚按钮与分隔线见 0.7.12-M6 / 33：
 * 按钮改成同一行、行末加 1dp 分隔线（最后一行收起）。
 */
public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.AppHolder> {

    public interface Listener {

        /** 勾选状态变化。 */
        void onSelectedChanged(String packageName, boolean selected);

        /** 点了「试一次」，按钮由宿主在结果返回后恢复。 */
        void onTestWarm(String packageName, MaterialButton button);

        /** 点了「设置」（长按一行等价）：宿主弹这个应用的设置卡。 */
        void onSettingsRequested(String packageName);
    }

    private final List<AppEntry> mItems = new ArrayList<>();
    private final Listener mListener;
    private final Set<String> mSelected = new HashSet<>();
    private final Map<String, Integer> mWeights = new HashMap<>();
    private final Set<String> mActivityApps = new HashSet<>();

    public AppListAdapter(Listener listener) {
        mListener = listener;
    }

    /**
     * 一次提交全部输入：列表、选中集合、权重、选了「拉起 Activity」的应用。
     *
     * <p>合成一次是为了只重画一遍 —— 分别 submit 会让整表绑定好几遍。
     */
    public void submitAll(List<AppEntry> entries, Set<String> selected,
                          Map<String, Integer> weights, Set<String> activityApps) {
        mItems.clear();
        if (entries != null) {
            mItems.addAll(entries);
        }
        mSelected.clear();
        if (selected != null) {
            mSelected.addAll(selected);
        }
        mWeights.clear();
        if (weights != null) {
            mWeights.putAll(weights);
        }
        mActivityApps.clear();
        if (activityApps != null) {
            mActivityApps.addAll(activityApps);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AppHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new AppHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppHolder holder, int position) {
        AppEntry entry = mItems.get(position);
        Context context = holder.itemView.getContext();
        boolean selected = mSelected.contains(entry.packageName);

        holder.label.setText(entry.label);
        holder.icon.setImageDrawable(entry.icon);
        holder.pkg.setText(entry.packageName);

        // 两枚灰色标签：这个应用当前的两档设置，不进设置卡就能看见
        Integer weight = mWeights.get(entry.packageName);
        holder.weight.setText(context.getString(R.string.applist_row_weight,
                weightName(context, weight == null ? 0 : weight)));
        holder.method.setText(context.getString(R.string.applist_row_method,
                context.getString(mActivityApps.contains(entry.packageName)
                        ? R.string.applist_wake_activity
                        : R.string.applist_wake_routes)));

        bindVerdict(holder, context, entry);

        // 勾过的行：整行加一层底色（它们同时已经被排到列表最前面，见宿主）。底色与波纹分开：
        // 波纹在 foreground（XML 里），这里只动 background，否则点下去就没有按压反馈了。
        holder.itemView.setBackgroundColor(selected
                ? context.getColor(R.color.row_selected_bg) : Color.TRANSPARENT);

        // 分隔线只在两行之间：最后一行收起（否则列表末尾会多一条悬空的线）
        holder.divider.setVisibility(position == getItemCount() - 1 ? View.GONE : View.VISIBLE);

        // 绑定期间不要触发监听，否则会把载入状态当成用户操作
        holder.checkBox.setOnCheckedChangeListener(null);
        holder.checkBox.setChecked(selected);
        holder.checkBox.setTag(entry.packageName);
        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Object tag = buttonView.getTag();
            if (!(tag instanceof String)) {
                return;
            }
            String packageName = (String) tag;
            if (isChecked) {
                mSelected.add(packageName);
            } else {
                mSelected.remove(packageName);
            }
            mListener.onSelectedChanged(packageName, isChecked);
        });

        // 只有勾选框改勾选；整行不再接收点击，避免误触。长按一行与「设置」按钮都打开设置卡。
        holder.itemView.setOnLongClickListener(v -> {
            mListener.onSettingsRequested(entry.packageName);
            return true;
        });

        holder.settings.setTag(entry.packageName);
        holder.settings.setOnClickListener(v -> {
            Object tag = v.getTag();
            if (tag instanceof String) {
                mListener.onSettingsRequested((String) tag);
            }
        });

        holder.test.setTag(entry.packageName);
        holder.test.setOnClickListener(v -> {
            Object tag = v.getTag();
            if (tag instanceof String) {
                mListener.onTestWarm((String) tag, holder.test);
            }
        });
    }

    /**
     * 结论那枚标签：绿色 = 这条路能拉起它，红色 = 不能（后半句写的就是原因）。
     *
     * <p>颜色只是第二眼的信息，文字里的「可预热 / 不可预热」一个字不省 —— 色觉障碍与
     * 深浅色反转都会吃掉颜色。判据就是结论本身（{@link com.jtfa.notificationfixer.probe.WarmTarget#isAvailable()}），
     * 与勾没勾选无关：勾选是勾选框、置顶与行底色那三件事。
     */
    private static void bindVerdict(AppHolder holder, Context context, AppEntry entry) {
        boolean ok = entry.target.isAvailable();
        holder.state.setText(entry.target.describeShort());
        holder.state.setBackgroundTintList(ColorStateList.valueOf(context.getColor(
                ok ? R.color.chip_ok_bg : R.color.chip_bad_bg)));
        holder.state.setTextColor(context.getColor(
                ok ? R.color.chip_ok_text : R.color.chip_bad_text));
    }

    /** 权重档位在界面上的名字（普通 / 较高 / 最高）。 */
    static String weightName(Context context, int weight) {
        String[] names = context.getResources().getStringArray(R.array.applist_weight_names);
        if (names.length == 0) {
            return String.valueOf(weight);
        }
        return names[Math.max(0, Math.min(weight, names.length - 1))];
    }


    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class AppHolder extends RecyclerView.ViewHolder {

        final CheckBox checkBox;
        final ImageView icon;
        final TextView label;
        final TextView pkg;
        final TextView weight;
        final TextView method;
        final TextView state;
        final MaterialButton settings;
        final MaterialButton test;
        final View divider;

        AppHolder(@NonNull View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.app_selected);
            icon = itemView.findViewById(R.id.app_icon);
            label = itemView.findViewById(R.id.app_label);
            pkg = itemView.findViewById(R.id.app_package);
            weight = itemView.findViewById(R.id.app_weight);
            method = itemView.findViewById(R.id.app_method);
            state = itemView.findViewById(R.id.app_state);
            settings = itemView.findViewById(R.id.app_settings);
            test = itemView.findViewById(R.id.app_test);
            divider = itemView.findViewById(R.id.app_divider);
        }
    }
}

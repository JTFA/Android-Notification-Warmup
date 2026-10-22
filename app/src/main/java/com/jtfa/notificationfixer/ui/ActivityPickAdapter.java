package com.jtfa.notificationfixer.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.jtfa.notificationfixer.R;
import com.jtfa.notificationfixer.probe.ActivityInventory;

import java.util.ArrayList;
import java.util.List;

/**
 * 「唤醒 Activity」候选列表。
 *
 * <p>两个动作分得很清：
 *
 * <ul>
 *   <li><b>整行可点</b> = 选中它（对话框的「用这个」会存的就是它）；</li>
 *   <li><b>「正常打开」</b> = 用普通方式启动这一个，让用户自己看有没有界面；</li>
 *   <li><b>「尝试后台打开」</b> = 用 launch-behind 启动这一个，尝试不把页面带到前台；
 *       测试期间对应按钮变成「正常打开中…」/「尝试后台打开中…」；</li>
 *   <li><b>长按一行</b> = 复制它的完整组件名（含包名），直接拿去 {@code am start -n}。</li>
 * </ul>
 */
public class ActivityPickAdapter extends RecyclerView.Adapter<ActivityPickAdapter.Holder> {

    public interface Listener {

        /** 点了一行：选中它。 */
        void onSelect(int position);

        /** 点了「正常打开」：用普通方式启动这一个。 */
        void onOpen(int position);

        /** 点了「尝试后台打开」：用 launch-behind 启动这一个。 */
        void onOpenBehind(int position);

        /** 长按了一行：复制它的完整组件名（含包名）。 */
        void onCopyComponent(int position);
    }

    private final List<ActivityInventory.Candidate> mItems = new ArrayList<>();
    private Listener mListener;

    private int mSelected = -1;
    private int mTesting = -1;
    private boolean mTestingBehind;

    /** {@code listener} 可以为 null，之后用 {@link #setListener} 补上（自引用时要用）。 */
    public ActivityPickAdapter(Listener listener) {
        mListener = listener;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public void submit(List<ActivityInventory.Candidate> items, int selected) {
        int old = mItems.size();
        mItems.clear();
        if (items != null) {
            mItems.addAll(items);
        }
        mSelected = selected;
        mTesting = -1;
        mTestingBehind = false;
        // 不用 notifyDataSetChanged（lint 会警告整表重绑）：按区间换掉整份数据即可
        if (old > 0) {
            notifyItemRangeRemoved(0, old);
        }
        if (!mItems.isEmpty()) {
            notifyItemRangeInserted(0, mItems.size());
        }
    }

    /** 当前选中的下标；-1 = 没选。 */
    public int selected() {
        return mSelected;
    }

    public void setSelected(int position) {
        if (mSelected != position) {
            int previous = mSelected;
            mSelected = position;
            if (previous >= 0) {
                notifyItemChanged(previous);
            }
            if (position >= 0) {
                notifyItemChanged(position);
            }
        }
    }

    public ActivityInventory.Candidate item(int position) {
        return position >= 0 && position < mItems.size() ? mItems.get(position) : null;
    }

    /** 正在测试的那一行（-1 = 没有）；{@code behind} = 正在测「尝试后台打开」。 */
    public void setTesting(int position, boolean behind) {
        boolean nextBehind = position >= 0 && behind;
        if (mTesting != position || mTestingBehind != nextBehind) {
            int previous = mTesting;
            mTesting = position;
            mTestingBehind = nextBehind;
            if (previous >= 0 && previous != mTesting) {
                notifyItemChanged(previous);
            }
            if (mTesting >= 0) {
                notifyItemChanged(mTesting);
            }
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_activity_candidate, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ActivityInventory.Candidate candidate = mItems.get(position);
        holder.name.setText(holder.name.getContext()
                .getString(R.string.applist_activity_name, candidate.shortName()));

        // 档位 + 风险。推断成「不显示 / 透明」且真能用的，用主色点出来 —— 那才是想找的那种
        String warning = candidate.warning();
        String verdict = ActivityInventory.visibilityLabel(candidate.visibility)
                + (warning == null ? "" : "（" + warning + "）");
        holder.verdict.setText(verdict);
        boolean good = candidate.usable()
                && (candidate.visibility == ActivityInventory.Visibility.NO_DISPLAY
                || candidate.visibility == ActivityInventory.Visibility.TRANSLUCENT);
        holder.verdict.setTextColor(good
                ? MaterialColors.getColor(holder.verdict,
                        com.google.android.material.R.attr.colorPrimary)
                : MaterialColors.getColor(holder.verdict,
                        com.google.android.material.R.attr.colorOnSurfaceVariant));
        holder.evidence.setText(candidate.evidence);

        holder.radio.setChecked(position == mSelected);
        holder.itemView.setOnClickListener(v -> mListener.onSelect(position));
        // 长按 = 复制完整组件名：行里显示的是去掉包名的短写，复制要的是能直接用的全名
        holder.itemView.setOnLongClickListener(v -> {
            mListener.onCopyComponent(position);
            return true;
        });

        boolean testing = position == mTesting;
        boolean testingBehind = testing && mTestingBehind;
        holder.open.setEnabled(!testing);
        holder.open.setText(testing && !testingBehind
                ? R.string.applist_activity_opening
                : R.string.applist_activity_open);
        holder.open.setOnClickListener(v -> mListener.onOpen(position));

        holder.openBehind.setEnabled(!testing);
        holder.openBehind.setText(testingBehind
                ? R.string.applist_activity_opening_behind
                : R.string.applist_activity_open_behind);
        holder.openBehind.setOnClickListener(v -> mListener.onOpenBehind(position));

        // 分割线只画在两项之间，最后一项不画。
        holder.divider.setVisibility(position == mItems.size() - 1 ? View.GONE : View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    static class Holder extends RecyclerView.ViewHolder {

        final RadioButton radio;
        final TextView name;
        final TextView verdict;
        final TextView evidence;
        final MaterialButton open;
        final MaterialButton openBehind;
        final View divider;

        Holder(@NonNull View itemView) {
            super(itemView);
            radio = itemView.findViewById(R.id.rb_candidate);
            name = itemView.findViewById(R.id.tv_candidate_name);
            verdict = itemView.findViewById(R.id.tv_candidate_verdict);
            evidence = itemView.findViewById(R.id.tv_candidate_evidence);
            open = itemView.findViewById(R.id.btn_candidate_open);
            openBehind = itemView.findViewById(R.id.btn_candidate_open_behind);
            divider = itemView.findViewById(R.id.divider_candidate);
        }
    }
}

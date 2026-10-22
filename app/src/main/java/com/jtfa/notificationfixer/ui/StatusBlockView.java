package com.jtfa.notificationfixer.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.jtfa.notificationfixer.R;

/**
 * 状态页里的一格（0.7.29-M6 / 50）。
 *
 * <p>四角圆、有底色的方块，嵌在 MaterialCardView 里 —— 一张卡里摆几格，
 * 比原来「一行占满一屏、每行还带一枚按钮」的排法密得多。
 *
 * <p>整格可点：权限那几格点了就去对应的系统设置页，所以「去授权」只是一行提示文字
 * （{@link #setAction}），不再是按钮。值那一行的颜色按 {@link StatusLevel} 取。
 */
public class StatusBlockView extends LinearLayout {

    private final TextView mTitle;
    private final TextView mCaption;
    private final TextView mValue;
    private final TextView mDetail;
    private final TextView mNote;
    private final TextView mAction;

    public StatusBlockView(Context context) {
        this(context, null);
    }

    public StatusBlockView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        int padding = Math.round(getResources().getDisplayMetrics().density * 10f);
        setPadding(padding, padding, padding, padding);
        setBackgroundResource(R.drawable.bg_block);
        setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.block_bg)));

        LayoutInflater.from(context).inflate(R.layout.view_status_block, this, true);
        mTitle = findViewById(R.id.block_title);
        mCaption = findViewById(R.id.block_caption);
        mValue = findViewById(R.id.block_value);
        mDetail = findViewById(R.id.block_detail);
        mNote = findViewById(R.id.block_note);
        mAction = findViewById(R.id.block_action);
        mAction.setVisibility(GONE);
    }

    /** 主语式标题（权限那几格）：14sp、正色。 */
    public void setTitle(CharSequence title) {
        mTitle.setText(title);
        mTitle.setVisibility(VISIBLE);
        mCaption.setVisibility(GONE);
    }

    /** 弱化标题（运行状态的指标格）：11sp、次级色，把主角让给下面的值。 */
    public void setCaption(CharSequence caption) {
        mCaption.setText(caption);
        mCaption.setVisibility(VISIBLE);
        mTitle.setVisibility(GONE);
    }

    /** 值：加粗、按状态取色。传 null 就把这一行收起来。 */
    public void setValue(CharSequence value, int level) {
        if (value == null) {
            mValue.setVisibility(GONE);
            return;
        }
        mValue.setText(value);
        mValue.setTextColor(ContextCompat.getColor(getContext(), StatusLevel.color(level)));
        mValue.setVisibility(VISIBLE);
    }

    /** 补充说明：小字、次级色。传 null 收起。 */
    public void setDetail(CharSequence detail) {
        setOptionalText(mDetail, detail);
    }

    /** 第二段补充（目前只有电池那一格用：白名单之外还要在系统里做什么）。传 null 收起。 */
    public void setNote(CharSequence note) {
        setOptionalText(mNote, note);
    }

    /** 操作提示（整格可点，这行只是把它说出来）：主色小字。传 null 收起。 */
    public void setAction(CharSequence action) {
        setOptionalText(mAction, action);
    }

    public void setActionVisible(boolean visible) {
        if (!visible) {
            mAction.setVisibility(GONE);
        } else if (mAction.getText().length() > 0) {
            mAction.setVisibility(VISIBLE);
        }
    }

    private void setOptionalText(TextView view, CharSequence text) {
        if (text == null || text.length() == 0) {
            view.setVisibility(GONE);
            return;
        }
        view.setText(text);
        view.setVisibility(VISIBLE);
    }
}

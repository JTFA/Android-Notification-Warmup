package com.jtfa.notificationfixer.core;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

/**
 * 字符串里的 {@code <b>…</b>} 加粗标记。
 *
 * <p>TextView 与 AlertDialog 都<b>不解析 HTML</b>：{@code getString} 拿到什么就画什么，
 * 所以带标记的说明如果不经过这里，界面上显示出来的就是字面的 {@code <b>排除</b>}。
 *
 * <p>不用 {@code Html.fromHtml}：它把换行当空白处理，多个 {@code <br>} 会被并成一个，
 * 这些说明里大段落的空行会因此挤成一坨。这里只认 {@code <b>} / {@code </b>} 两个标记，
 * 其余字符（含换行）原样保留 —— 显示结果与字符串里写的一模一样，只有加粗不同。
 */
public final class Markup {

    private static final String OPEN = "<b>";
    private static final String CLOSE = "</b>";

    private Markup() {
    }

    /** 取字符串资源（可以带占位参数）并处理其中的加粗标记。 */
    public static CharSequence of(Context context, int resId, Object... args) {
        return of(context.getString(resId, args));
    }

    public static CharSequence of(CharSequence raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.toString();
        if (text.indexOf('<') < 0) {
            // 绝大多数字符串没有标记：原样返回，不额外做一次扫描复制
            return text;
        }
        SpannableStringBuilder out = new SpannableStringBuilder();
        int index = 0;
        while (true) {
            int open = text.indexOf(OPEN, index);
            if (open < 0) {
                out.append(text, index, text.length());
                break;
            }
            int close = text.indexOf(CLOSE, open + OPEN.length());
            if (close < 0) {
                // 只有半个标记：剩下的原样显示，总比把整句丢掉好
                out.append(text, index, text.length());
                break;
            }
            out.append(text, index, open);
            int start = out.length();
            out.append(text, open + OPEN.length(), close);
            out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            index = close + CLOSE.length();
        }
        return out;
    }
}

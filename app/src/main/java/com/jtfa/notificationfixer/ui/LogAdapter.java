package com.jtfa.notificationfixer.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.jtfa.notificationfixer.R;
import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.LogEntry;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 事件日志列表。 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.EntryHolder> {

    private final List<LogEntry> mItems = new ArrayList<>();
    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public void submit(List<LogEntry> entries) {
        mItems.clear();
        if (entries != null) {
            mItems.addAll(entries);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public EntryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new EntryHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EntryHolder holder, int position) {
        LogEntry entry = mItems.get(position);
        holder.time.setText(mTimeFormat.format(new Date(entry.timeMs)));
        holder.category.setText(entry.category);
        holder.category.setTextColor(ContextCompat.getColor(
                holder.itemView.getContext(), colorForCategory(entry.category)));
        holder.text.setText(entry.data == null ? entry.text : entry.text + "\n" + entry.data);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @ColorRes
    private static int colorForCategory(String category) {
        if (EventLog.CAT_TRIGGER.equals(category)) {
            return R.color.cat_trigger;
        }
        if (EventLog.CAT_WARM.equals(category)) {
            return R.color.cat_warm;
        }
        if (EventLog.CAT_NOTIF.equals(category)) {
            return R.color.cat_notification;
        }
        if (EventLog.CAT_DIAG.equals(category)) {
            return R.color.cat_diag;
        }
        if (EventLog.CAT_SERVICE.equals(category)) {
            return R.color.cat_service;
        }
        if (EventLog.CAT_PERM.equals(category)) {
            return R.color.cat_permission;
        }
        return R.color.state_neutral;
    }

    static class EntryHolder extends RecyclerView.ViewHolder {

        final TextView time;
        final TextView category;
        final TextView text;

        EntryHolder(@NonNull View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.log_time);
            category = itemView.findViewById(R.id.log_category);
            text = itemView.findViewById(R.id.log_text);
        }
    }
}

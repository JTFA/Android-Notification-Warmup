package com.jtfa.notificationfixer.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.jtfa.notificationfixer.R;
import com.jtfa.notificationfixer.core.EventLog;
import com.jtfa.notificationfixer.core.LogEntry;
import com.jtfa.notificationfixer.core.Prefs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 事件日志：筛选、清空、导出。 */
public class LogFragment extends Fragment {

    private LogAdapter mAdapter;
    private TextView mEmptyView;
    private Spinner mFilter;
    /** 标题栏那一行的两枚开关（0.7.41-M6 / 62）：记录诊断日志、记录通知移除事件。 */
    private View mSwitchBar;
    private MaterialSwitch mSwitchDiag;
    private MaterialSwitch mSwitchLogRemoved;
    private CompoundButton.OnCheckedChangeListener mSwitchListener;

    private final List<LogEntry> mAllEntries = new ArrayList<>();
    private String mCategoryFilter;

    private final EventLog.Listener mLogListener = snapshot -> {
        mAllEntries.clear();
        mAllEntries.addAll(snapshot);
        applyFilter();
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mEmptyView = view.findViewById(R.id.txt_empty);

        // 两枚开关住在标题栏那一行（activity_main.xml 的 log_switches），不在本页布局里。
        // 只有日志页显示它们：进入时露出，离开 / 销毁时收起。
        mSwitchBar = requireActivity().findViewById(R.id.log_switches);
        if (mSwitchBar != null) {
            mSwitchDiag = mSwitchBar.findViewById(R.id.sw_diag);
            mSwitchLogRemoved = mSwitchBar.findViewById(R.id.sw_log_removed);
            // 两枚开关只认 Prefs：setSaveEnabled(false) 挡住开关自己保存，
            // setSaveFromParentEnabled(false) 再让父容器在保存 / 恢复时跳过它们（65）。
            mSwitchDiag.setSaveEnabled(false);
            mSwitchDiag.setSaveFromParentEnabled(false);
            mSwitchLogRemoved.setSaveEnabled(false);
            mSwitchLogRemoved.setSaveFromParentEnabled(false);
            mSwitchBar.setVisibility(View.VISIBLE);
        }
        mSwitchListener = (buttonView, isChecked) -> {
            if (buttonView.getId() == R.id.sw_diag) {
                Prefs.setDiagLog(isChecked);
            } else if (buttonView.getId() == R.id.sw_log_removed) {
                Prefs.setLogRemoved(isChecked);
            }
        };
        bindSwitchState();

        RecyclerView list = view.findViewById(R.id.recycle_log);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new LogAdapter();
        list.setAdapter(mAdapter);

        mFilter = view.findViewById(R.id.spinner_filter);
        List<String> options = new ArrayList<>();
        options.add(getString(R.string.log_filter_all));
        for (String category : EventLog.CATEGORIES) {
            options.add(category);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mFilter.setAdapter(adapter);
        mFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
                mCategoryFilter = position == 0 ? null : EventLog.CATEGORIES[position - 1];
                applyFilter();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不需要处理
            }
        });

        view.findViewById(R.id.btn_clear).setOnClickListener(v -> confirmClear());
        view.findViewById(R.id.btn_export).setOnClickListener(v -> exportLog());

        mAllEntries.clear();
        mAllEntries.addAll(EventLog.get().snapshot());
        applyFilter();
    }

    @Override
    public void onResume() {
        super.onResume();
        bindSwitchState();
        // 放在 onResume：onViewStateRestored 已经结束，恢复 View 状态时的 setChecked()
        // 不会回调写库（0.7.43-M6 / 64）。
        attachSwitchListener();
        if (mSwitchBar != null) {
            mSwitchBar.setVisibility(View.VISIBLE);
        }
        EventLog.get().addListener(mLogListener);
        mAllEntries.clear();
        mAllEntries.addAll(EventLog.get().snapshot());
        applyFilter();
    }

    /**
     * 两枚开关的现状（0.7.40-M6 / 61）：设置页那边的入口撤掉后，这里是唯一能改它们的地方，
     * 所以在 onViewCreated 与 onResume 各同步一次（「恢复默认设置」会把它们改回去）。
     * 同步时先摘掉监听，免得 setChecked 又写一遍库；监听由 {@link #attachSwitchListener()}
     * 在状态恢复之后单独挂上。
     */
    private void bindSwitchState() {
        if (mSwitchDiag == null || mSwitchLogRemoved == null) {
            return;
        }
        mSwitchDiag.setOnCheckedChangeListener(null);
        mSwitchLogRemoved.setOnCheckedChangeListener(null);
        mSwitchDiag.setChecked(Prefs.isDiagLog());
        mSwitchLogRemoved.setChecked(Prefs.isLogRemoved());
        // 从别的页面回来时也从 XML 默认值拨到库里的值，别让滑块滑一下。
        mSwitchDiag.jumpDrawablesToCurrentState();
        mSwitchLogRemoved.jumpDrawablesToCurrentState();
    }

    private void attachSwitchListener() {
        if (mSwitchDiag == null || mSwitchLogRemoved == null) {
            return;
        }
        mSwitchDiag.setOnCheckedChangeListener(mSwitchListener);
        mSwitchLogRemoved.setOnCheckedChangeListener(mSwitchListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        EventLog.get().removeListener(mLogListener);
        // 标题栏那一行是 Activity 的，离开这一页就收起，别让设置页 / 名单页也看到日志开关
        if (mSwitchBar != null) {
            mSwitchBar.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        // 开关的 listener 由本 fragment 持有：视图先于 Activity 销毁，必须在这里摘掉
        if (mSwitchDiag != null) {
            mSwitchDiag.setOnCheckedChangeListener(null);
        }
        if (mSwitchLogRemoved != null) {
            mSwitchLogRemoved.setOnCheckedChangeListener(null);
        }
        if (mSwitchBar != null) {
            mSwitchBar.setVisibility(View.GONE);
        }
        mSwitchDiag = null;
        mSwitchLogRemoved = null;
        mSwitchBar = null;
        super.onDestroyView();
    }

    private void applyFilter() {
        if (mAdapter == null) {
            return;
        }
        List<LogEntry> filtered;
        if (mCategoryFilter == null) {
            filtered = new ArrayList<>(mAllEntries);
        } else {
            filtered = new ArrayList<>();
            for (LogEntry entry : mAllEntries) {
                if (mCategoryFilter.equals(entry.category)) {
                    filtered.add(entry);
                }
            }
        }
        mAdapter.submit(filtered);
        if (mEmptyView != null) {
            mEmptyView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.log_clear_title)
                .setMessage(R.string.log_clear_message)
                .setPositiveButton(R.string.dialog_ok, (dialog, which) -> {
                    EventLog.get().clear();
                    Toast.makeText(requireContext(), R.string.toast_log_cleared, Toast.LENGTH_SHORT)
                            .show();
                })
                .setNegativeButton(R.string.dialog_cancel, (DialogInterface dialog, int which) ->
                        dialog.dismiss())
                .show();
    }

    private void exportLog() {
        File file = EventLog.get().exportFile();
        if (file == null || !file.exists() || file.length() == 0L) {
            Toast.makeText(requireContext(), R.string.toast_export_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    requireContext(), requireContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.export_chooser_title)));
        } catch (Exception e) {
            EventLog.get().log(EventLog.CAT_SERVICE, "导出日志失败：" + e);
            Toast.makeText(requireContext(), R.string.toast_export_failed, Toast.LENGTH_SHORT).show();
        }
    }
}

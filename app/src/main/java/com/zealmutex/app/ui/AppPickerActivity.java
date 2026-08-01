package com.zealmutex.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.zealmutex.app.data.DataStore;
import com.zealmutex.app.data.Rule;
import com.zealmutex.app.engine.Safety;
import com.zealmutex.app.engine.TrustedTime;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Searchable list of third-party launchable apps, including visible clones. */
public final class AppPickerActivity extends Activity {
    public static final String EXTRA_MULTI_SELECT = "multiSelect";
    public static final String EXTRA_EDITING_RULE_KEY = "editingRuleKey";
    public static final String EXTRA_SELECTED_PACKAGES = "selectedPackages";
    public static final String EXTRA_SELECTED_LABELS = "selectedLabels";

    private final List<AppItem> allApps = new ArrayList<>();
    private final List<AppItem> shownApps = new ArrayList<>();
    private final Map<String, String> selected = new LinkedHashMap<>();
    private AppAdapter adapter;
    private ProgressBar progress;
    private boolean multiSelect;
    private String editingRuleKey = "";
    private Button confirmButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyBeforeCreate(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applySystemBars(this);
        multiSelect = getIntent().getBooleanExtra(EXTRA_MULTI_SELECT, false);
        editingRuleKey = getIntent().getStringExtra(EXTRA_EDITING_RULE_KEY);
        if (editingRuleKey == null) {
            editingRuleKey = "";
        }
        ArrayList<String> selectedPackages = getIntent().getStringArrayListExtra(
                EXTRA_SELECTED_PACKAGES);
        ArrayList<String> selectedLabels = getIntent().getStringArrayListExtra(
                EXTRA_SELECTED_LABELS);
        if (selectedPackages != null) {
            for (int i = 0; i < selectedPackages.size(); i++) {
                String label = selectedLabels != null && i < selectedLabels.size()
                        ? selectedLabels.get(i) : selectedPackages.get(i);
                selected.put(selectedPackages.get(i), label);
            }
        }
        LinearLayout root = Ui.column(this, 20);
        root.setBackgroundColor(Ui.background(this));
        root.addView(Ui.title(this, multiSelect ? "选择组内应用" : "选择应用", 28f));
        root.addView(Ui.text(this, multiSelect
                        ? "按选择顺序排列；已归属应用会在明天迁移"
                        : "系统关键应用与已设置应用不会重复添加",
                14f, Ui.MUTED),
                Ui.matchWrap(this, 6));

        EditText search = new EditText(this);
        search.setHint("搜索应用名称或包名");
        search.setHintTextColor(Ui.mutedText(this));
        search.setTextColor(Ui.primaryText(this));
        search.setSingleLine(true);
        search.setBackground(Ui.rounded(this, Ui.SURFACE, 12, 1, Ui.SURFACE_HIGH));
        search.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        root.addView(search, Ui.matchWrap(this, 18));

        progress = new ProgressBar(this);
        root.addView(progress, Ui.matchWrap(this, 30));
        ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setCacheColorHint(Ui.background(this));
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (multiSelect) {
            confirmButton = Ui.primaryButton(this, "完成选择（" + selected.size() + "）");
            confirmButton.setOnClickListener(view -> finishMultiSelection());
            root.addView(confirmButton, Ui.matchWrap(this, 12));
        }
        Ui.applyStatusBarInset(root);
        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        loadApps();
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager packageManager = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launchers;
            if (Build.VERSION.SDK_INT >= 33) {
                launchers = packageManager.queryIntentActivities(launcher,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL));
            } else {
                launchers = packageManager.queryIntentActivities(launcher,
                        PackageManager.MATCH_ALL);
            }
            Map<String, AppItem> unique = new LinkedHashMap<>();
            for (ResolveInfo resolved : launchers) {
                if (resolved.activityInfo == null
                        || resolved.activityInfo.applicationInfo == null) {
                    continue;
                }
                ApplicationInfo info = resolved.activityInfo.applicationInfo;
                if (Safety.isAlwaysAllowed(this, info.packageName)) {
                    continue;
                }
                CharSequence resolvedLabel = resolved.loadLabel(packageManager);
                String label = resolvedLabel == null || resolvedLabel.length() == 0
                        ? packageManager.getApplicationLabel(info).toString()
                        : resolvedLabel.toString();
                unique.putIfAbsent(info.packageName,
                        new AppItem(info.packageName, label, info));
            }
            List<AppItem> loaded = new ArrayList<>(unique.values());
            Collator collator = Collator.getInstance(Locale.CHINA);
            Collections.sort(loaded, (left, right) -> collator.compare(left.label, right.label));
            runOnUiThread(() -> {
                allApps.clear();
                allApps.addAll(loaded);
                filter("");
                progress.setVisibility(View.GONE);
            });
        }, "app-picker-loader").start();
    }

    private void filter(String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        shownApps.clear();
        for (AppItem app : allApps) {
            if (normalized.isEmpty()
                    || app.label.toLowerCase(Locale.ROOT).contains(normalized)
                    || app.packageName.toLowerCase(Locale.ROOT).contains(normalized)) {
                shownApps.add(app);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shownApps.size(); }
        @Override public AppItem getItem(int position) { return shownApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppItem item = getItem(position);
            LinearLayout row = new LinearLayout(AppPickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(AppPickerActivity.this, 10),
                    Ui.dp(AppPickerActivity.this, 10),
                    Ui.dp(AppPickerActivity.this, 10),
                    Ui.dp(AppPickerActivity.this, 10));

            ImageView icon = new ImageView(AppPickerActivity.this);
            icon.setImageDrawable(item.info.loadIcon(getPackageManager()));
            row.addView(icon, new LinearLayout.LayoutParams(
                    Ui.dp(AppPickerActivity.this, 48), Ui.dp(AppPickerActivity.this, 48)));

            LinearLayout labels = Ui.column(AppPickerActivity.this, 0);
            labels.setPadding(Ui.dp(AppPickerActivity.this, 14), 0, 0, 0);
            labels.addView(Ui.title(AppPickerActivity.this, item.label, 16f));
            labels.addView(Ui.text(AppPickerActivity.this, item.packageName, 11f, Ui.MUTED),
                    Ui.matchWrap(AppPickerActivity.this, 3));
            Rule owner = DataStore.get(AppPickerActivity.this).getEditableRuleForPackage(
                    item.packageName, TrustedTime.now(AppPickerActivity.this));
            if (owner != null && !owner.packageName.equals(editingRuleKey)) {
                labels.addView(Ui.text(AppPickerActivity.this,
                        "当前属于：" + owner.appLabel, 12f, Ui.LOCKED_TEXT),
                        Ui.matchWrap(AppPickerActivity.this, 3));
            }
            row.addView(labels, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (multiSelect) {
                CheckBox check = new CheckBox(AppPickerActivity.this);
                check.setChecked(selected.containsKey(item.packageName));
                check.setClickable(false);
                row.addView(check);
                row.setOnClickListener(view -> selectForGroup(item, owner));
            } else {
                row.setAlpha(owner == null ? 1f : 0.55f);
                row.setOnClickListener(view -> {
                    if (owner != null) {
                        Toast.makeText(AppPickerActivity.this,
                                "该应用已属于“" + owner.appLabel + "”",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    startActivity(new Intent(AppPickerActivity.this, RuleEditorActivity.class)
                            .putExtra("package", item.packageName)
                            .putExtra("label", item.label));
                    finish();
                });
            }
            return row;
        }
    }

    private void selectForGroup(AppItem item, Rule owner) {
        if (selected.containsKey(item.packageName)) {
            selected.remove(item.packageName);
            selectionChanged();
            return;
        }
        if (owner == null || owner.packageName.equals(editingRuleKey)) {
            addSelection(item);
            return;
        }
        String message = "“" + item.label + "”当前属于“" + owner.appLabel
                + "”。保存应用组后，它将在明天 00:00 迁移到当前组。";
        if (owner.group && owner.members.size() == 2) {
            Rule.AppMember remaining = owner.members.get(0).packageName.equals(item.packageName)
                    ? owner.members.get(1) : owner.members.get(0);
            message += "\n\n如果最终只从原组迁移这一个应用，原组将只剩“"
                    + remaining.label + "”，届时会自动转为该应用的单独规则。";
        }
        new AlertDialog.Builder(this)
                .setTitle("迁移应用？")
                .setMessage(message)
                .setPositiveButton("继续选择", (dialog, which) -> addSelection(item))
                .setNegativeButton("取消", null)
                .show();
    }

    private void addSelection(AppItem item) {
        selected.put(item.packageName, item.label);
        selectionChanged();
    }

    private void selectionChanged() {
        if (confirmButton != null) {
            confirmButton.setText(String.format(
                    Locale.CHINA, "完成选择（%d）", selected.size()));
        }
        adapter.notifyDataSetChanged();
    }

    private void finishMultiSelection() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "请至少选择一个应用", Toast.LENGTH_LONG).show();
            return;
        }
        Intent result = new Intent();
        result.putStringArrayListExtra(EXTRA_SELECTED_PACKAGES,
                new ArrayList<>(selected.keySet()));
        result.putStringArrayListExtra(EXTRA_SELECTED_LABELS,
                new ArrayList<>(selected.values()));
        setResult(RESULT_OK, result);
        finish();
    }

    private static final class AppItem {
        final String packageName;
        final String label;
        final ApplicationInfo info;

        AppItem(String packageName, String label, ApplicationInfo info) {
            this.packageName = packageName;
            this.label = label;
            this.info = info;
        }
    }
}

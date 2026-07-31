package com.zealmutex.app.ui;

import android.app.Activity;
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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.zealmutex.app.engine.Safety;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Searchable list of third-party launchable apps, including visible clones. */
public final class AppPickerActivity extends Activity {
    private final List<AppItem> allApps = new ArrayList<>();
    private final List<AppItem> shownApps = new ArrayList<>();
    private AppAdapter adapter;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this, 20);
        root.setBackgroundColor(Ui.BLACK);
        root.addView(Ui.title(this, "选择应用", 28f));
        root.addView(Ui.text(this, "系统关键应用已自动排除", 14f, Ui.MUTED),
                Ui.matchWrap(this, 6));

        EditText search = new EditText(this);
        search.setHint("搜索应用名称或包名");
        search.setHintTextColor(Ui.MUTED);
        search.setTextColor(Ui.WHITE);
        search.setSingleLine(true);
        search.setBackground(Ui.rounded(this, Ui.SURFACE, 12, 1, Ui.SURFACE_HIGH));
        search.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        root.addView(search, Ui.matchWrap(this, 18));

        progress = new ProgressBar(this);
        root.addView(progress, Ui.matchWrap(this, 30));
        ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setCacheColorHint(Ui.BLACK);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
            row.addView(labels, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(view -> startActivity(new Intent(
                    AppPickerActivity.this, RuleEditorActivity.class)
                    .putExtra("package", item.packageName)
                    .putExtra("label", item.label)));
            return row;
        }
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

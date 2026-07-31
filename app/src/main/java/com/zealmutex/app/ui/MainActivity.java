package com.zealmutex.app.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zealmutex.app.data.DataStore;
import com.zealmutex.app.data.Rule;
import com.zealmutex.app.engine.BackgroundSettings;
import com.zealmutex.app.engine.RuleEngine;
import com.zealmutex.app.engine.TrustedTime;
import com.zealmutex.app.engine.UsageTracker;
import com.zealmutex.app.service.MonitorService;

import java.util.List;

/** Dashboard, permission onboarding and active-rule list. */
public final class MainActivity extends Activity {
    private LinearLayout content;

    @Override
    protected void onResume() {
        super.onResume();
        buildDashboard();
    }

    private void buildDashboard() {
        MonitorService.start(this);
        TrustedTime.syncIfStale(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = Ui.column(this, 20);
        content.setBackgroundColor(Ui.BLACK);
        scroll.addView(content);
        setContentView(scroll);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView eyebrow = Ui.text(this, "ZEALMUTEX", 12f, Ui.MUTED);
        eyebrow.setLetterSpacing(0.18f);
        topBar.addView(eyebrow, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button about = Ui.secondaryButton(this, "⚙");
        about.setTextSize(20f);
        about.setContentDescription("关于与更新");
        about.setOnClickListener(view ->
                startActivity(new Intent(this, AboutActivity.class)));
        topBar.addView(about, new LinearLayout.LayoutParams(
                Ui.dp(this, 52), Ui.dp(this, 42)));
        content.addView(topBar);
        content.addView(Ui.title(this, "保持专注", 32f), Ui.matchWrap(this, 8));
        content.addView(Ui.text(this, "规则和使用记录仅保存在这台手机上", 14f, Ui.MUTED),
                Ui.matchWrap(this, 6));

        addPermissionCard();

        Button add = Ui.primaryButton(this, "选择要限制的应用");
        add.setOnClickListener(view -> startActivity(new Intent(this, AppPickerActivity.class)));
        content.addView(add, Ui.matchWrap(this, 8));

        Button reports = Ui.secondaryButton(this, "使用统计与周报");
        reports.setOnClickListener(view -> startActivity(new Intent(this, ReportsActivity.class)));
        content.addView(reports, Ui.matchWrap(this, 12));

        TextView section = Ui.title(this, "已设置的应用", 22f);
        content.addView(section, Ui.matchWrap(this, 30));
        addRuleCards();
    }

    private void addPermissionCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams params = Ui.matchWrap(this, 28);
        card.setLayoutParams(params);
        card.addView(Ui.title(this, "首次设置", 20f));
        card.addView(Ui.text(this,
                "ZealMutex 只读取当前可见应用的包名和系统使用时长，用于本机计时、提醒和锁定；不会读取输入内容，也不会上传记录。",
                13f, Ui.MUTED), Ui.matchWrap(this, 8));

        addPermissionRow(card, "使用情况访问", UsageTracker.hasUsageAccess(this), view ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        addPermissionRow(card, "无障碍限制服务",
                MonitorService.isAccessibilityEnabled(this), view ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addPermissionRow(card, "通知权限", hasNotificationPermission(), view -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
            } else {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }
        });
        addPermissionRow(card, "忽略电池优化", ignoresBatteryOptimizations(), view -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException unavailable) {
                openAppDetails();
            }
        });
        addBackgroundManagementRow(card);
        content.addView(card);
    }

    private void addPermissionRow(LinearLayout card, String name, boolean granted,
                                  View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView label = Ui.text(this, (granted ? "✓  " : "○  ") + name,
                15f, granted ? Ui.WHITE : Ui.MUTED);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button action = Ui.secondaryButton(this, granted ? "已开启" : "去设置");
        action.setEnabled(!granted);
        action.setOnClickListener(listener);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 92), Ui.dp(this, 44));
        row.addView(action, actionParams);
        card.addView(row, Ui.matchWrap(this, 12));
    }

    private void addBackgroundManagementRow(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout labels = Ui.column(this, 0);
        String vendor = BackgroundSettings.vendorName();
        labels.addView(Ui.text(this, "→  后台管理（" + vendor + "）", 15f, Ui.WHITE));
        labels.addView(Ui.text(this, "已自动识别设备 · 厂商开关需运行验证", 12f, Ui.MUTED),
                Ui.matchWrap(this, 3));
        row.addView(labels, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button action = Ui.secondaryButton(this, "去设置");
        action.setOnClickListener(view -> {
            if (!BackgroundSettings.open(this)) {
                Toast.makeText(this, "无法打开后台管理，请从系统设置手动进入",
                        Toast.LENGTH_LONG).show();
            }
        });
        row.addView(action, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 44)));

        card.addView(row, Ui.matchWrap(this, 12));
    }

    private void addRuleCards() {
        long now = TrustedTime.now(this);
        DataStore store = DataStore.get(this);
        List<Rule> rules = store.getActiveRules(now);
        if (rules.isEmpty()) {
            content.addView(Ui.text(this, "还没有限制规则。选择一个应用开始。",
                    15f, Ui.MUTED), Ui.matchWrap(this, 12));
            return;
        }
        for (Rule rule : rules) {
            LinearLayout card = Ui.card(this);
            card.addView(Ui.title(this, rule.appLabel, 19f));
            String mode = rule.mode == Rule.MODE_DAILY_LIMIT
                    ? "每日最多 " + rule.dailyLimitMinutes + " 分钟"
                    : "按允许时段使用 · " + rule.windows.size() + " 个时段";
            card.addView(Ui.text(this, mode, 14f, Ui.MUTED), Ui.matchWrap(this, 5));
            long used = store.getTodayUsageMs(rule.packageName, now);
            card.addView(Ui.text(this, "今日已使用 " + RuleEngine.formatDuration(used),
                    14f, Ui.WHITE), Ui.matchWrap(this, 10));
            String pending = store.pendingDescription(rule.packageName, now);
            if (!pending.isEmpty()) {
                card.addView(Ui.text(this, pending, 13f, Ui.MUTED), Ui.matchWrap(this, 6));
            }
            boolean scheduledDelete = store.hasScheduledDelete(rule.packageName, now);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button edit = Ui.secondaryButton(this, "查看或修改");
            edit.setOnClickListener(view -> startActivity(new Intent(this, RuleEditorActivity.class)
                    .putExtra("package", rule.packageName)
                    .putExtra("label", rule.appLabel)));
            LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                    0, Ui.dp(this, 48), 1f);
            editParams.setMarginEnd(Ui.dp(this, 8));
            actions.addView(edit, editParams);
            Button delete = Ui.secondaryButton(this,
                    scheduledDelete ? "撤销删除" : "删除");
            delete.setTextColor(Ui.DANGER);
            delete.setOnClickListener(view -> {
                if (scheduledDelete) {
                    store.cancelScheduledDelete(rule.packageName, TrustedTime.now(this));
                    Toast.makeText(this, "已撤销删除，规则将继续执行",
                            Toast.LENGTH_LONG).show();
                    buildDashboard();
                } else {
                    confirmDelete(rule);
                }
            });
            actions.addView(delete, new LinearLayout.LayoutParams(
                    0, Ui.dp(this, 48), 1f));
            card.addView(actions, Ui.matchWrap(this, 14));
            content.addView(card);
        }
    }

    private void confirmDelete(Rule rule) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("明天删除 " + rule.appLabel + " 的规则？")
                .setMessage("今天仍会继续执行当前限制，明天 00:00 后停止限制。")
                .setPositiveButton("确认删除", (dialog, which) -> {
                    DataStore.get(this).scheduleDelete(
                            rule.packageName, TrustedTime.now(this));
                    Toast.makeText(this, "已安排明天删除，可在按钮处撤销",
                            Toast.LENGTH_LONG).show();
                    buildDashboard();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            return manager == null || manager.areNotificationsEnabled();
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ignoresBatteryOptimizations() {
        PowerManager power = getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openAppDetails() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }
}

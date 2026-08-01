package com.zealmutex.app.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
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
import com.zealmutex.app.update.UpdateManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Two-tab home and settings surface for local restriction status. */
public final class MainActivity extends Activity {
    public static final String EXTRA_TAB = "tab";
    public static final int TAB_HOME = 0;
    public static final int TAB_SETTINGS = 1;

    private static final int REQUEST_NOTIFICATIONS = 10;
    private static final String ONBOARDING_PREFS = "onboarding";
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteRefresh = new Runnable() {
        @Override
        public void run() {
            if (selectedTab == TAB_HOME) {
                render();
            }
            handler.postDelayed(this, 60_000L);
        }
    };

    private LinearLayout body;
    private LinearLayout bottomNavigation;
    private int selectedTab = TAB_HOME;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selectedTab = getIntent().getIntExtra(EXTRA_TAB, TAB_HOME);
        buildShell();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        selectedTab = intent.getIntExtra(EXTRA_TAB, selectedTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        MonitorService.start(this);
        TrustedTime.syncIfStale(this);
        UpdateManager.checkIfDue(this, () -> {
            if (resumed) {
                render();
            }
        });
        render();
        handler.removeCallbacks(minuteRefresh);
        handler.postDelayed(minuteRefresh, 60_000L);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(minuteRefresh);
        super.onPause();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BLACK);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomNavigation = new LinearLayout(this);
        bottomNavigation.setOrientation(LinearLayout.HORIZONTAL);
        bottomNavigation.setPadding(Ui.dp(this, 12), Ui.dp(this, 8),
                Ui.dp(this, 12), Ui.dp(this, 10));
        bottomNavigation.setBackgroundColor(Ui.SURFACE);
        root.addView(bottomNavigation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.applyStatusBarInset(root);
        setContentView(root);
    }

    private void render() {
        if (body == null) {
            return;
        }
        body.removeAllViews();
        body.addView(selectedTab == TAB_HOME ? buildHome() : buildSettings(),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        renderBottomNavigation();
    }

    private View buildHome() {
        long now = TrustedTime.now(this);
        List<Rule> rules = DataStore.get(this).getRulesForDisplay(now);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = Ui.column(this, 20);
        content.setBackgroundColor(Ui.BLACK);
        scroll.addView(content);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView eyebrow = Ui.text(this, "ZEALMUTEX", 12f, Ui.MUTED);
        eyebrow.setLetterSpacing(0.18f);
        topBar.addView(eyebrow, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!rules.isEmpty()) {
            Button add = Ui.secondaryButton(this, "+");
            add.setTextSize(23f);
            add.setContentDescription("添加应用");
            add.setOnClickListener(view -> beginAddApplication());
            topBar.addView(add, new LinearLayout.LayoutParams(
                    Ui.dp(this, 52), Ui.dp(this, 42)));
        }
        content.addView(topBar);
        content.addView(Ui.title(this, "主页", 32f), Ui.matchWrap(this, 8));
        content.addView(Ui.text(this, "今天的限制状态与剩余额度", 14f, Ui.MUTED),
                Ui.matchWrap(this, 6));

        if (!hasCorePermissions()) {
            LinearLayout warning = Ui.card(this);
            warning.setBackground(Ui.rounded(this, Ui.LOCKED_SURFACE, 14, 0, 0));
            warning.addView(Ui.title(this, "计时可能不准确", 18f));
            warning.addView(Ui.text(this,
                    "使用情况访问或无障碍服务尚未开启，请先到设置完成授权。",
                    13f, Ui.LOCKED_TEXT), Ui.matchWrap(this, 6));
            Button settingsButton = Ui.secondaryButton(this, "前往设置");
            settingsButton.setOnClickListener(view -> selectTab(TAB_SETTINGS));
            warning.addView(settingsButton, Ui.matchWrap(this, 12));
            content.addView(warning, Ui.matchWrap(this, 22));
        }

        if (rules.isEmpty()) {
            LinearLayout empty = Ui.card(this);
            empty.addView(Ui.title(this, "还没有添加应用", 21f));
            empty.addView(Ui.text(this,
                    "添加一个应用并设置每日额度或允许使用时段。",
                    14f, Ui.MUTED), Ui.matchWrap(this, 8));
            Button add = Ui.primaryButton(this, "添加应用");
            add.setOnClickListener(view -> beginAddApplication());
            empty.addView(add, Ui.matchWrap(this, 18));
            content.addView(empty, Ui.matchWrap(this, 28));
            return scroll;
        }

        content.addView(Ui.title(this, "已设置的应用", 22f), Ui.matchWrap(this, 28));
        for (Rule rule : rules) {
            content.addView(buildRuleCard(rule, now));
        }
        return scroll;
    }

    private View buildRuleCard(Rule rule, long now) {
        DataStore store = DataStore.get(this);
        boolean pendingActivation = store.getActiveRule(rule.packageName, now) == null;
        boolean restrictionActive = RuleEngine.isRestrictionActive(rule, now);
        RuleEngine.Decision decision = RuleEngine.evaluate(this, rule, now);
        LinearLayout card = Ui.card(this);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> openRule(rule));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(buildRuleIcons(rule));
        LinearLayout labels = Ui.column(this, 0);
        labels.setPadding(Ui.dp(this, 12), 0, 0, 0);
        labels.addView(Ui.title(this, rule.appLabel, 19f));
        labels.addView(Ui.text(this,
                (rule.group ? "应用组 · " + rule.members.size() + " 个应用 · " : "")
                        + (rule.mode == Rule.MODE_DAILY_LIMIT
                        ? "每日使用时长" : "允许使用时段"),
                12f, Ui.MUTED), Ui.matchWrap(this, 3));
        labels.addView(Ui.text(this, activeWeekdays(rule), 12f, Ui.MUTED),
                Ui.matchWrap(this, 2));
        header.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button pin = Ui.secondaryButton(this, rule.pinned ? "取消置顶" : "置顶");
        pin.setTextSize(12f);
        pin.setContentDescription(rule.pinned ? "取消置顶" : "置顶规则");
        pin.setOnClickListener(view -> {
            store.setPinned(rule.packageName, !rule.pinned, TrustedTime.now(this));
            render();
        });
        header.addView(pin, new LinearLayout.LayoutParams(
                Ui.dp(this, 78), Ui.dp(this, 40)));
        card.addView(header);

        card.addView(buildStatus(decision, pendingActivation, restrictionActive),
                Ui.matchWrap(this, 14));
        if (!pendingActivation && restrictionActive && rule.mode == Rule.MODE_DAILY_LIMIT) {
            addDailyProgress(card, rule, decision.usedMs);
        } else if (!pendingActivation && !restrictionActive) {
            card.addView(Ui.text(this,
                    "今日已使用 " + RuleEngine.formatDuration(decision.usedMs),
                    13f, Ui.MUTED), Ui.matchWrap(this, 10));
        }

        if (!pendingActivation && restrictionActive) {
            int remainingUnlocks = Math.max(0,
                    rule.temporaryUnlocksPerDay - decision.usedTemporaryUnlocks);
            card.addView(Ui.text(this,
                    "今日还可临时解锁 " + remainingUnlocks + " 次",
                    13f, Ui.MUTED), Ui.matchWrap(this, 12));
        }

        String pending = store.pendingDescription(rule.packageName, now);
        if (!pending.isEmpty()) {
            String value = store.hasScheduledDelete(rule.packageName, now)
                    ? "明日删除" : pending;
            TextView pendingLabel = Ui.text(this, value, 12f, Ui.WHITE);
            pendingLabel.setPadding(Ui.dp(this, 10), Ui.dp(this, 7),
                    Ui.dp(this, 10), Ui.dp(this, 7));
            pendingLabel.setBackground(Ui.rounded(
                    this, Ui.SURFACE_HIGH, 9, 0, 0));
            card.addView(pendingLabel, Ui.matchWrap(this, 10));
        }
        return card;
    }

    private View buildStatus(RuleEngine.Decision decision, boolean pendingActivation,
                             boolean restrictionActive) {
        String value;
        int fill;
        int color;
        if (pendingActivation) {
            value = "规则尚未生效";
            fill = Ui.SURFACE_HIGH;
            color = Ui.MUTED;
        } else if (!restrictionActive) {
            value = "今日不限制";
            fill = Ui.SURFACE_HIGH;
            color = Ui.BLUE;
        } else if (decision.temporaryUnlockRemainingMs > 0L) {
            value = "临时解锁中 · 剩余 "
                    + RuleEngine.formatRemainingDuration(decision.temporaryUnlockRemainingMs);
            fill = Ui.BLUE_SURFACE;
            color = Ui.BLUE;
        } else if (decision.blocked) {
            value = decision.rule.mode == Rule.MODE_TIME_WINDOWS
                    ? "🔒 当前不在允许时段" : "🔒 已锁定";
            fill = Ui.LOCKED_SURFACE;
            color = Ui.LOCKED_TEXT;
        } else {
            value = decision.rule.mode == Rule.MODE_TIME_WINDOWS
                    ? "当前处于允许时段" : "今日可继续使用";
            fill = Ui.SURFACE_HIGH;
            color = decision.rule.mode == Rule.MODE_TIME_WINDOWS ? Ui.BLUE : Ui.WHITE;
        }
        TextView status = Ui.text(this, value, 14f, color);
        status.setPadding(Ui.dp(this, 11), Ui.dp(this, 9),
                Ui.dp(this, 11), Ui.dp(this, 9));
        status.setBackground(Ui.rounded(this, fill, 10, 0, 0));
        return status;
    }

    private static String activeWeekdays(Rule rule) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        StringBuilder value = new StringBuilder("生效：");
        for (int day = 1; day <= 7; day++) {
            if (!rule.isActiveOnDay(day)) {
                continue;
            }
            if (value.length() > 3) {
                value.append('、');
            }
            value.append(names[day - 1]);
        }
        return value.toString();
    }

    private void addDailyProgress(LinearLayout card, Rule rule, long usedMs) {
        long limitMs = rule.dailyLimitMinutes * 60_000L;
        long remainingMs = Math.max(0L, limitMs - usedMs);
        int remainingPercent = limitMs == 0L ? 0
                : (int) Math.ceil(remainingMs * 100.0d / limitMs);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.addView(Ui.text(this,
                RuleEngine.formatDuration(usedMs) + " / " + RuleEngine.formatDuration(limitMs),
                15f, Ui.WHITE), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView percent = Ui.title(this, "剩余 " + remainingPercent + "%", 16f);
        percent.setTextColor(Ui.BLUE);
        row.addView(percent);
        card.addView(row, Ui.matchWrap(this, 16));

        LinearLayout progress = new LinearLayout(this);
        progress.setOrientation(LinearLayout.HORIZONTAL);
        progress.setBackground(Ui.rounded(this, Ui.SURFACE_HIGH, 6, 0, 0));
        double usedRatio = Math.min(1.0d, usedMs / (double) limitMs);
        if (usedRatio > 0.0d) {
            View used = new View(this);
            used.setBackground(Ui.rounded(this, Ui.MUTED, 6, 0, 0));
            progress.addView(used, new LinearLayout.LayoutParams(
                    0, Ui.dp(this, 10), (float) usedRatio));
        }
        if (usedRatio < 1.0d) {
            View remaining = new View(this);
            remaining.setBackground(Ui.rounded(this, Ui.BLUE, 6, 0, 0));
            progress.addView(remaining, new LinearLayout.LayoutParams(
                    0, Ui.dp(this, 10), (float) (1.0d - usedRatio)));
        }
        card.addView(progress, Ui.matchWrap(this, 9));

        if (usedMs > limitMs) {
            TextView exceeded = Ui.text(this,
                    "已超出 " + RuleEngine.formatDuration(usedMs - limitMs),
                    13f, Ui.LOCKED_TEXT);
            card.addView(exceeded, Ui.matchWrap(this, 8));
        }
    }

    private View buildSettings() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = Ui.column(this, 20);
        content.setBackgroundColor(Ui.BLACK);
        scroll.addView(content);

        content.addView(Ui.text(this, "ZEALMUTEX", 12f, Ui.MUTED));
        content.addView(Ui.title(this, "设置", 32f), Ui.matchWrap(this, 8));
        content.addView(Ui.text(this, "权限、后台运行、报告和应用更新", 14f, Ui.MUTED),
                Ui.matchWrap(this, 6));

        addPermissionCard(content);
        addTimeStatusCard(content);
        content.addView(buildNavigationCard(
                "我的报告", "查看本周明细和历史周报", false,
                view -> startActivity(new Intent(this, ReportsActivity.class))),
                Ui.matchWrap(this, 16));
        content.addView(buildNavigationCard(
                "关于与更新", "当前版本、更新日志和 GitHub 更新", UpdateManager.hasUpdate(this),
                view -> startActivity(new Intent(this, AboutActivity.class))),
                Ui.matchWrap(this, 4));
        return scroll;
    }

    private void addPermissionCard(LinearLayout content) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "权限与后台", 20f));
        card.addView(Ui.text(this,
                "前两项是限制功能必需权限，其余项目用于提高提醒和后台可靠性。",
                13f, Ui.MUTED), Ui.matchWrap(this, 7));

        addPermissionRow(card, "使用情况访问 · 必需",
                UsageTracker.hasUsageAccess(this), view ->
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        addPermissionRow(card, "无障碍限制服务 · 必需",
                MonitorService.isAccessibilityEnabled(this), view ->
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addPermissionRow(card, "通知权限 · 建议", hasNotificationPermission(), view -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS);
            } else {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }
        });
        addPermissionRow(card, "忽略电池优化 · 建议",
                ignoresBatteryOptimizations(), view -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException unavailable) {
                        openAppDetails();
                    }
                });
        addBackgroundManagement(card);
        content.addView(card, Ui.matchWrap(this, 26));
    }

    private void addPermissionRow(LinearLayout card, String name, boolean granted,
                                  View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Ui.text(this, (granted ? "✓  " : "○  ") + name,
                14f, granted ? Ui.WHITE : Ui.MUTED);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button action = Ui.secondaryButton(this, granted ? "已开启" : "去设置");
        action.setEnabled(!granted);
        action.setOnClickListener(listener);
        row.addView(action, new LinearLayout.LayoutParams(
                Ui.dp(this, 92), Ui.dp(this, 42)));
        card.addView(row, Ui.matchWrap(this, 12));
    }

    private void addBackgroundManagement(LinearLayout card) {
        SharedPreferences preferences = getSharedPreferences(
                ONBOARDING_PREFS, MODE_PRIVATE);
        boolean confirmed = preferences.getBoolean("backgroundConfirmed", false);

        LinearLayout section = Ui.column(this, 0);
        section.addView(Ui.text(this,
                (confirmed ? "✓  " : "○  ") + "后台管理（"
                        + BackgroundSettings.vendorName() + "） · 建议",
                14f, confirmed ? Ui.WHITE : Ui.MUTED));
        section.addView(Ui.text(this,
                confirmed ? "已手动确认完成，仍可再次进入检查"
                        : "厂商开关无法自动读取，请设置后手动确认",
                12f, Ui.MUTED), Ui.matchWrap(this, 4));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button open = Ui.secondaryButton(this, confirmed ? "再次检查" : "去设置");
        open.setOnClickListener(view -> {
            if (!BackgroundSettings.open(this)) {
                Toast.makeText(this, "无法打开后台管理，请从系统设置手动进入",
                        Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 42), 1f);
        openParams.setMarginEnd(Ui.dp(this, 8));
        actions.addView(open, openParams);
        Button complete = Ui.secondaryButton(this,
                confirmed ? "已完成" : "我已完成设置");
        complete.setEnabled(!confirmed);
        complete.setOnClickListener(view -> {
            preferences.edit().putBoolean("backgroundConfirmed", true).apply();
            render();
        });
        actions.addView(complete, new LinearLayout.LayoutParams(
                0, Ui.dp(this, 42), 1f));
        section.addView(actions, Ui.matchWrap(this, 10));
        card.addView(section, Ui.matchWrap(this, 14));
    }

    private void addTimeStatusCard(LinearLayout content) {
        TrustedTime.Status status = TrustedTime.status(this);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "时间校准", 20f));
        String headline = status.networkCalibrated
                ? "✓ 网络时间已校准" : "○ 当前使用手机本地时间";
        card.addView(Ui.text(this, headline, 15f,
                status.networkCalibrated ? Ui.BLUE : Ui.MUTED), Ui.matchWrap(this, 9));
        if (status.lastSyncedEpochMillis > 0L) {
            String time = DATE_TIME.format(Instant.ofEpochMilli(
                    status.lastSyncedEpochMillis).atZone(ZoneId.systemDefault()));
            String source = status.source.isEmpty() ? "" : " · " + status.source;
            card.addView(Ui.text(this,
                    "最近校准 " + time + source, 12f, Ui.MUTED),
                    Ui.matchWrap(this, 5));
        } else {
            card.addView(Ui.text(this,
                    "联网后会自动校准；网络不可用时继续使用本地时间。",
                    12f, Ui.MUTED), Ui.matchWrap(this, 5));
        }
        content.addView(card, Ui.matchWrap(this, 4));
    }

    private View buildNavigationCard(String title, String subtitle, boolean showDot,
                                     View.OnClickListener listener) {
        LinearLayout card = Ui.card(this);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(Ui.title(this, title, 19f), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (showDot) {
            TextView dot = Ui.text(this, "●", 18f, Ui.DANGER);
            dot.setContentDescription("有新版本");
            titleRow.addView(dot);
        }
        card.addView(titleRow);
        card.addView(Ui.text(this, subtitle + "  ›", 13f, Ui.MUTED),
                Ui.matchWrap(this, 6));
        return card;
    }

    private void renderBottomNavigation() {
        bottomNavigation.removeAllViews();
        boolean update = UpdateManager.hasUpdate(this);
        Button home = navigationButton("主页", selectedTab == TAB_HOME);
        home.setOnClickListener(view -> selectTab(TAB_HOME));
        bottomNavigation.addView(home, navigationParams());

        Button settings = navigationButton(update ? "设置  ●" : "设置",
                selectedTab == TAB_SETTINGS);
        if (update) {
            settings.setTextColor(Ui.DANGER);
        }
        settings.setOnClickListener(view -> selectTab(TAB_SETTINGS));
        bottomNavigation.addView(settings, navigationParams());
    }

    private Button navigationButton(String text, boolean selected) {
        Button button = selected ? Ui.primaryButton(this, text)
                : Ui.secondaryButton(this, text);
        button.setMinHeight(Ui.dp(this, 46));
        return button;
    }

    private LinearLayout.LayoutParams navigationParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 48), 1f);
        params.setMarginStart(Ui.dp(this, 4));
        params.setMarginEnd(Ui.dp(this, 4));
        return params;
    }

    private void selectTab(int tab) {
        selectedTab = tab;
        render();
    }

    private boolean hasCorePermissions() {
        return UsageTracker.hasUsageAccess(this)
                && MonitorService.isAccessibilityEnabled(this);
    }

    private void beginAddApplication() {
        if (!hasCorePermissions()) {
            selectedTab = TAB_SETTINGS;
            render();
            Toast.makeText(this, "请先开启使用情况访问和无障碍服务",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("添加限制")
                .setItems(new String[]{"单个应用", "应用组"}, (dialog, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(this, AppPickerActivity.class));
                    } else {
                        startActivity(new Intent(this, RuleEditorActivity.class)
                                .putExtra(RuleEditorActivity.EXTRA_CREATE_GROUP, true));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private View buildRuleIcons(Rule rule) {
        LinearLayout icons = new LinearLayout(this);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        int shown = rule.group ? Math.min(2, rule.members.size()) : 1;
        for (int i = 0; i < shown; i++) {
            String packageName = rule.group
                    ? rule.members.get(i).packageName : rule.packageName;
            try {
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(getPackageManager().getApplicationIcon(packageName));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        Ui.dp(this, 38), Ui.dp(this, 38));
                if (i > 0) {
                    params.setMarginStart(Ui.dp(this, 4));
                }
                icons.addView(icon, params);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (rule.group && rule.members.size() > 2) {
            TextView more = Ui.text(this, "+" + (rule.members.size() - 2), 12f, Ui.MUTED);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER_VERTICAL;
            params.setMarginStart(Ui.dp(this, 5));
            icons.addView(more, params);
        }
        return icons;
    }

    private void openRule(Rule rule) {
        startActivity(new Intent(this, RuleEditorActivity.class)
                .putExtra("package", rule.packageName)
                .putExtra("label", rule.appLabel));
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

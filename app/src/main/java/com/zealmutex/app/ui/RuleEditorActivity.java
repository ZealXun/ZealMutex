package com.zealmutex.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zealmutex.app.data.DataStore;
import com.zealmutex.app.data.Rule;
import com.zealmutex.app.engine.TrustedTime;
import com.zealmutex.app.service.MonitorService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Full editor for one package-owned rule. */
public final class RuleEditorActivity extends Activity {
    private static final int REQUEST_REMINDER_IMAGE = 40;
    private static final String[] WEEKDAYS = {
            "周一", "周二", "周三", "周四", "周五", "周六", "周日"
    };

    private DataStore store;
    private Rule rule;
    private boolean existingRule;
    private RadioGroup modeGroup;
    private int dailyRadioId;
    private LinearLayout dailySection;
    private LinearLayout windowSection;
    private LinearLayout windowList;
    private final CheckBox[] weekdayChecks = new CheckBox[7];
    private CheckBox remindOnInactiveDays;
    private EditText dailyMinutes;
    private EditText extraMessage;
    private EditText reminderInterval;
    private EditText reminderMessage;
    private RadioGroup reminderDisplayGroup;
    private int fullPageRadioId;
    private Button chooseImageButton;
    private String reminderImageUri = "";
    private NumberPicker unlockCount;
    private NumberPicker unlockMinutes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String packageName = getIntent().getStringExtra("package");
        String label = getIntent().getStringExtra("label");
        if (packageName == null || packageName.isEmpty()) {
            finish();
            return;
        }
        long now = TrustedTime.now(this);
        store = DataStore.get(this);
        existingRule = store.getActiveRule(packageName, now) != null;
        rule = store.getEditableRule(packageName, now);
        if (rule == null) {
            rule = new Rule();
            rule.packageName = packageName;
            rule.appLabel = label == null || label.isEmpty() ? packageName : label;
        }
        buildEditor();
    }

    private void buildEditor() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this, 20);
        root.setBackgroundColor(Ui.BLACK);
        scroll.addView(root);
        Ui.applyStatusBarInset(scroll);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        try {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(getPackageManager().getApplicationIcon(rule.packageName));
            header.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        } catch (Exception ignored) {
        }
        LinearLayout labels = Ui.column(this, 0);
        labels.setPadding(Ui.dp(this, 14), 0, 0, 0);
        labels.addView(Ui.title(this, rule.appLabel, 25f));
        labels.addView(Ui.text(this, rule.packageName, 11f, Ui.MUTED), Ui.matchWrap(this, 4));
        header.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (existingRule) {
            boolean scheduledDelete = store.hasScheduledDelete(
                    rule.packageName, TrustedTime.now(this));
            ImageButton deleteAction = new ImageButton(this);
            deleteAction.setImageResource(scheduledDelete
                    ? com.zealmutex.app.R.drawable.ic_undo
                    : com.zealmutex.app.R.drawable.ic_delete);
            deleteAction.setColorFilter(scheduledDelete ? Ui.BLUE : Ui.DANGER);
            deleteAction.setContentDescription(scheduledDelete
                    ? "撤销删除" : "删除规则");
            deleteAction.setBackground(Ui.rounded(
                    this, Ui.SURFACE_HIGH, 12, 1, Ui.SURFACE_HIGH));
            deleteAction.setPadding(Ui.dp(this, 12), Ui.dp(this, 12),
                    Ui.dp(this, 12), Ui.dp(this, 12));
            deleteAction.setOnClickListener(view -> {
                if (scheduledDelete) {
                    store.cancelScheduledDelete(
                            rule.packageName, TrustedTime.now(this));
                    Toast.makeText(this, "已撤销删除，规则将继续执行",
                            Toast.LENGTH_LONG).show();
                    buildEditor();
                } else {
                    confirmDelete();
                }
            });
            header.addView(deleteAction, new LinearLayout.LayoutParams(
                    Ui.dp(this, 48), Ui.dp(this, 48)));
        }
        root.addView(header);

        String pending = store.pendingDescription(rule.packageName, TrustedTime.now(this));
        if (!pending.isEmpty()) {
            String detail = existingRule ? "；今天仍执行当前限制。" : "；生效前不会限制。";
            TextView notice = Ui.text(this, pending + detail, 13f, Ui.BLACK);
            notice.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                    Ui.dp(this, 12), Ui.dp(this, 10));
            notice.setBackground(Ui.rounded(this, Ui.WHITE, 10, 0, 0));
            root.addView(notice, Ui.matchWrap(this, 18));
        }

        root.addView(Ui.title(this, "生效星期", 20f), Ui.matchWrap(this, 28));
        LinearLayout weekdayCard = Ui.card(this);
        weekdayCard.addView(Ui.text(this,
                "选中的日期执行限制；未选日期仍统计使用时长。",
                13f, Ui.MUTED));
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int start = rowIndex == 0 ? 0 : 4;
            int end = rowIndex == 0 ? 4 : 7;
            for (int day = start; day < end; day++) {
                CheckBox check = new CheckBox(this);
                check.setText(WEEKDAYS[day]);
                check.setTextColor(Ui.WHITE);
                check.setTextSize(14f);
                check.setChecked(rule.isActiveOnDay(day + 1));
                weekdayChecks[day] = check;
                row.addView(check, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            weekdayCard.addView(row, Ui.matchWrap(this, rowIndex == 0 ? 10 : 2));
        }
        root.addView(weekdayCard);

        root.addView(Ui.title(this, "限制模式", 20f), Ui.matchWrap(this, 28));
        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton daily = radio("每日使用时长");
        RadioButton windows = radio("允许使用时段");
        dailyRadioId = View.generateViewId();
        daily.setId(dailyRadioId);
        windows.setId(View.generateViewId());
        modeGroup.addView(daily);
        modeGroup.addView(windows);
        modeGroup.check(rule.mode == Rule.MODE_DAILY_LIMIT ? daily.getId() : windows.getId());
        root.addView(modeGroup, Ui.matchWrap(this, 8));

        dailySection = Ui.card(this);
        dailySection.addView(Ui.text(this, "每天最多使用（分钟）", 14f, Ui.MUTED));
        dailyMinutes = input(String.valueOf(rule.dailyLimitMinutes), "1～1440");
        dailyMinutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        dailySection.addView(dailyMinutes, Ui.matchWrap(this, 8));
        root.addView(dailySection);

        windowSection = Ui.card(this);
        windowSection.addView(Ui.text(this,
                "可添加多个共用时段；所有选中的星期使用相同时段。",
                13f, Ui.MUTED));
        windowList = Ui.column(this, 0);
        windowSection.addView(windowList, Ui.matchWrap(this, 10));
        Button addWindow = Ui.secondaryButton(this, "添加允许时段");
        addWindow.setOnClickListener(view -> beginAddWindow());
        windowSection.addView(addWindow, Ui.matchWrap(this, 10));
        root.addView(windowSection);

        Rule.Reminder currentReminder = rule.reminders.isEmpty()
                ? null : rule.reminders.get(0);
        root.addView(Ui.title(this, "循环提醒", 20f), Ui.matchWrap(this, 22));
        LinearLayout reminderCard = Ui.card(this);
        reminderCard.addView(Ui.text(this,
                "按当天累计使用时间循环提醒；留空表示关闭提醒。",
                13f, Ui.MUTED));
        remindOnInactiveDays = new CheckBox(this);
        remindOnInactiveDays.setText("非限制日仍继续提醒");
        remindOnInactiveDays.setTextColor(Ui.WHITE);
        remindOnInactiveDays.setTextSize(14f);
        remindOnInactiveDays.setChecked(rule.remindOnInactiveDays);
        reminderCard.addView(remindOnInactiveDays, Ui.matchWrap(this, 8));
        reminderCard.addView(Ui.text(this, "每使用多少分钟提醒一次（1～1440）",
                14f, Ui.MUTED), Ui.matchWrap(this, 12));
        reminderInterval = input(currentReminder == null ? ""
                : String.valueOf(currentReminder.thresholdMinutes), "例如 20");
        reminderInterval.setInputType(InputType.TYPE_CLASS_NUMBER);
        reminderCard.addView(reminderInterval, Ui.matchWrap(this, 6));
        reminderCard.addView(Ui.text(this, "自定义提醒词", 14f, Ui.MUTED),
                Ui.matchWrap(this, 12));
        reminderMessage = input(currentReminder == null ? "" : currentReminder.customText,
                "留空使用：请注意使用时间");
        reminderCard.addView(reminderMessage, Ui.matchWrap(this, 6));

        reminderDisplayGroup = new RadioGroup(this);
        reminderDisplayGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton popup = radio("弹窗模式 · 显示 5 秒，可滑动关闭");
        RadioButton fullPage = radio("整页模式 · 点击按钮关闭");
        popup.setId(View.generateViewId());
        fullPageRadioId = View.generateViewId();
        fullPage.setId(fullPageRadioId);
        reminderDisplayGroup.addView(popup);
        reminderDisplayGroup.addView(fullPage);
        boolean fullPageSelected = currentReminder != null
                && currentReminder.displayMode == Rule.Reminder.DISPLAY_FULL_PAGE;
        reminderDisplayGroup.check(fullPageSelected ? fullPage.getId() : popup.getId());
        reminderCard.addView(reminderDisplayGroup, Ui.matchWrap(this, 10));

        reminderImageUri = currentReminder == null ? "" : currentReminder.imageUri;
        chooseImageButton = Ui.secondaryButton(this,
                reminderImageUri.isEmpty() ? "选择整页图片" : "重新选择整页图片");
        chooseImageButton.setOnClickListener(view -> chooseReminderImage());
        reminderCard.addView(chooseImageButton, Ui.matchWrap(this, 8));
        reminderCard.addView(Ui.text(this,
                "未选择图片时使用 ZealMutex 默认黑白图标。",
                12f, Ui.MUTED), Ui.matchWrap(this, 5));
        root.addView(reminderCard);
        reminderDisplayGroup.setOnCheckedChangeListener((group, checkedId) ->
                updateReminderImageVisibility());
        updateReminderImageVisibility();

        root.addView(Ui.title(this, "临时解锁", 20f), Ui.matchWrap(this, 28));
        LinearLayout unlockCard = Ui.card(this);
        unlockCard.addView(Ui.text(this, "每天允许次数（0～5）", 14f, Ui.MUTED));
        unlockCount = numberPicker(0, 5, rule.temporaryUnlocksPerDay);
        unlockCard.addView(unlockCount, Ui.matchWrap(this, 4));
        unlockCard.addView(Ui.text(this, "每次固定时长（1～10 分钟）", 14f, Ui.MUTED),
                Ui.matchWrap(this, 12));
        unlockMinutes = numberPicker(1, 10, rule.temporaryUnlockMinutes);
        unlockCard.addView(unlockMinutes, Ui.matchWrap(this, 4));
        root.addView(unlockCard);

        root.addView(Ui.title(this, "锁定页附加文字", 20f), Ui.matchWrap(this, 18));
        extraMessage = input(rule.extraLockMessage, "可选，不会替代必要的时间信息");
        extraMessage.setSingleLine(false);
        extraMessage.setMinLines(2);
        root.addView(extraMessage, Ui.matchWrap(this, 10));

        Button save = Ui.primaryButton(this, existingRule ? "保存设置" : "保存，明天生效");
        save.setOnClickListener(view -> save());
        root.addView(save, Ui.matchWrap(this, 28));
        root.addView(Ui.text(this,
                existingRule ? "提醒、非限制日提醒开关、图片和锁定页文字立即生效；星期、使用限制、时段、临时解锁和删除将在明天 00:00 生效。"
                        : "首次创建的规则和全部设置将在明天 00:00 生效。",
                12f, Ui.MUTED), Ui.matchWrap(this, 14));

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> updateModeVisibility());
        renderWindows();
        updateModeVisibility();
    }

    private RadioButton radio(String text) {
        RadioButton button = new RadioButton(this);
        button.setText(text);
        button.setTextColor(Ui.WHITE);
        button.setTextSize(16f);
        button.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        return button;
    }

    private EditText input(String value, String hint) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setHint(hint);
        edit.setTextColor(Ui.WHITE);
        edit.setHintTextColor(Ui.MUTED);
        edit.setBackground(Ui.rounded(this, Ui.SURFACE, 10, 1, Ui.SURFACE_HIGH));
        edit.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        return edit;
    }

    private NumberPicker numberPicker(int min, int max, int value) {
        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(Math.max(min, Math.min(max, value)));
        picker.setWrapSelectorWheel(false);
        return picker;
    }

    private void updateModeVisibility() {
        boolean daily = modeGroup.getCheckedRadioButtonId() == dailyRadioId;
        dailySection.setVisibility(daily ? View.VISIBLE : View.GONE);
        windowSection.setVisibility(daily ? View.GONE : View.VISIBLE);
    }

    private void updateReminderImageVisibility() {
        if (chooseImageButton != null) {
            chooseImageButton.setVisibility(
                    reminderDisplayGroup.getCheckedRadioButtonId() == fullPageRadioId
                            ? View.VISIBLE : View.GONE);
        }
    }

    private void chooseReminderImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_REMINDER_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_REMINDER_IMAGE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(data.getData(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // Some system pickers grant access without supporting persistence.
        }
        reminderImageUri = data.getData().toString();
        chooseImageButton.setText("重新选择整页图片");
    }

    private void beginAddWindow() {
        chooseStartTime();
    }

    private void chooseStartTime() {
        new TimePickerDialog(this, (picker, hour, minute) -> {
            int start = hour * 60 + minute;
            new TimePickerDialog(this, (endPicker, endHour, endMinute) -> {
                int end = endHour * 60 + endMinute;
                if (end <= start) {
                    Toast.makeText(this, "结束时间必须晚于开始时间，且不能跨午夜",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                rule.windows.add(new Rule.TimeWindow(start, end));
                renderWindows();
            }, Math.min(23, hour + 1), minute, true).show();
        }, 18, 0, true).show();
    }

    private void renderWindows() {
        windowList.removeAllViews();
        Collections.sort(rule.windows,
                (left, right) -> Integer.compare(left.startMinute, right.startMinute));
        if (rule.windows.isEmpty()) {
            windowList.addView(Ui.text(this, "尚未添加时段", 14f, Ui.MUTED));
            return;
        }
        List<Rule.TimeWindow> snapshot = new ArrayList<>(rule.windows);
        for (Rule.TimeWindow window : snapshot) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            String value = clock(window.startMinute) + "–" + clock(window.endMinute);
            row.addView(Ui.text(this, value, 15f, Ui.WHITE),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button remove = Ui.secondaryButton(this, "删除");
            remove.setOnClickListener(view -> {
                rule.windows.remove(window);
                renderWindows();
            });
            row.addView(remove, new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 42)));
            windowList.addView(row, Ui.matchWrap(this, 6));
        }
    }

    private void save() {
        rule.activeWeekdaysMask = 0;
        for (int day = 0; day < weekdayChecks.length; day++) {
            if (weekdayChecks[day].isChecked()) {
                rule.setActiveOnDay(day + 1, true);
            }
        }
        if (rule.activeWeekdaysMask == 0) {
            Toast.makeText(this, "至少选择一个生效星期", Toast.LENGTH_LONG).show();
            return;
        }
        rule.mode = modeGroup.getCheckedRadioButtonId() == dailyRadioId
                ? Rule.MODE_DAILY_LIMIT : Rule.MODE_TIME_WINDOWS;
        rule.dailyLimitMinutes = Math.max(1, Math.min(1440,
                parseInt(dailyMinutes.getText().toString(), 60)));
        if (rule.mode == Rule.MODE_TIME_WINDOWS && rule.windows.isEmpty()) {
            Toast.makeText(this, "时段模式至少需要一个允许时段", Toast.LENGTH_LONG).show();
            return;
        }
        rule.temporaryUnlocksPerDay = unlockCount.getValue();
        rule.temporaryUnlockMinutes = unlockMinutes.getValue();
        rule.remindOnInactiveDays = remindOnInactiveDays.isChecked();
        rule.extraLockMessage = extraMessage.getText().toString().trim();
        String intervalValue = reminderInterval.getText().toString().trim();
        rule.reminders.clear();
        if (!intervalValue.isEmpty()) {
            int interval = parseInt(intervalValue, -1);
            if (interval < 1 || interval > 1440) {
                Toast.makeText(this, "循环提醒间隔必须是 1～1440 分钟",
                        Toast.LENGTH_LONG).show();
                return;
            }
            int displayMode = reminderDisplayGroup.getCheckedRadioButtonId()
                    == fullPageRadioId ? Rule.Reminder.DISPLAY_FULL_PAGE
                    : Rule.Reminder.DISPLAY_POPUP;
            rule.reminders.add(new Rule.Reminder(Rule.Reminder.USED_MINUTES, interval,
                    reminderMessage.getText().toString().trim(), displayMode,
                    reminderImageUri));
        }

        long now = TrustedTime.now(this);
        boolean firstRule = store.saveRule(rule, now);
        MonitorService.start(this);
        String result;
        if (firstRule) {
            result = "规则将在明天 00:00 生效";
        } else if (store.hasScheduledDelete(rule.packageName, now)) {
            result = "提醒和文字已立即生效；删除仍将在明天生效";
        } else if (!store.pendingDescription(rule.packageName, now).isEmpty()) {
            result = "提醒和文字已立即生效；限制修改将在明天生效";
        } else {
            result = "提醒和文字已立即生效";
        }
        Toast.makeText(this, result, Toast.LENGTH_LONG).show();
        returnToDashboard();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("明天删除规则？")
                .setMessage("今天仍会继续执行当前限制，明天 00:00 后停止限制。")
                .setPositiveButton("确认", (dialog, which) -> {
                    store.scheduleDelete(rule.packageName, TrustedTime.now(this));
                    Toast.makeText(this, "已安排明天删除", Toast.LENGTH_LONG).show();
                    buildEditor();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void returnToDashboard() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static String clock(int minute) {
        return String.format(Locale.CHINA, "%02d:%02d", minute / 60, minute % 60);
    }
}

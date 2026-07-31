package com.zealmutex.app.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete user-owned configuration for one installed application.
 *
 * <p>Rules are keyed by package name so uninstalling and reinstalling the
 * restricted app does not reset its limits. The class deliberately uses only
 * platform JSON classes to keep the APK small.</p>
 */
public final class Rule {
    public static final int MODE_DAILY_LIMIT = 0;
    public static final int MODE_TIME_WINDOWS = 1;

    public String packageName = "";
    public String appLabel = "";
    public int mode = MODE_DAILY_LIMIT;
    public int dailyLimitMinutes = 60;
    public int temporaryUnlocksPerDay = 1;
    public int temporaryUnlockMinutes = 5;
    public String extraLockMessage = "";
    public final List<TimeWindow> windows = new ArrayList<>();
    public final List<Reminder> reminders = new ArrayList<>();

    public Rule copy() {
        try {
            return fromJson(toJson());
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to copy rule", impossible);
        }
    }

    /** Copies settings that are safe to apply without weakening today's limits. */
    public void applyImmediateSettingsFrom(Rule source) {
        extraLockMessage = source.extraLockMessage;
        reminders.clear();
        for (Reminder reminder : source.reminders) {
            reminders.add(new Reminder(reminder.type, reminder.thresholdMinutes,
                    reminder.customText, reminder.displayMode, reminder.imageUri));
        }
    }

    /** Compares settings whose changes must wait until tomorrow. */
    public boolean hasSameDelayedSettings(Rule other) {
        if (other == null
                || mode != other.mode
                || dailyLimitMinutes != other.dailyLimitMinutes
                || temporaryUnlocksPerDay != other.temporaryUnlocksPerDay
                || temporaryUnlockMinutes != other.temporaryUnlockMinutes
                || windows.size() != other.windows.size()) {
            return false;
        }
        for (int i = 0; i < windows.size(); i++) {
            TimeWindow left = windows.get(i);
            TimeWindow right = other.windows.get(i);
            if (left.dayOfWeek != right.dayOfWeek
                    || left.startMinute != right.startMinute
                    || left.endMinute != right.endMinute) {
                return false;
            }
        }
        return true;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("package", packageName);
        object.put("label", appLabel);
        object.put("mode", mode);
        object.put("dailyLimitMinutes", dailyLimitMinutes);
        object.put("temporaryUnlocksPerDay", temporaryUnlocksPerDay);
        object.put("temporaryUnlockMinutes", temporaryUnlockMinutes);
        object.put("extraLockMessage", extraLockMessage);

        JSONArray windowArray = new JSONArray();
        for (TimeWindow window : windows) {
            windowArray.put(window.toJson());
        }
        object.put("windows", windowArray);

        JSONArray reminderArray = new JSONArray();
        for (Reminder reminder : reminders) {
            reminderArray.put(reminder.toJson());
        }
        object.put("reminders", reminderArray);
        return object;
    }

    public static Rule fromJson(JSONObject object) throws JSONException {
        Rule rule = new Rule();
        rule.packageName = object.getString("package");
        rule.appLabel = object.optString("label", rule.packageName);
        rule.mode = object.optInt("mode", MODE_DAILY_LIMIT);
        rule.dailyLimitMinutes = clamp(object.optInt("dailyLimitMinutes", 60), 1, 1440);
        rule.temporaryUnlocksPerDay = clamp(object.optInt("temporaryUnlocksPerDay", 1), 0, 5);
        rule.temporaryUnlockMinutes = clamp(object.optInt("temporaryUnlockMinutes", 5), 1, 10);
        rule.extraLockMessage = object.optString("extraLockMessage", "");

        JSONArray windowArray = object.optJSONArray("windows");
        if (windowArray != null) {
            for (int i = 0; i < windowArray.length(); i++) {
                TimeWindow window = TimeWindow.fromJson(windowArray.getJSONObject(i));
                if (window.startMinute < window.endMinute) {
                    rule.windows.add(window);
                }
            }
        }

        JSONArray reminderArray = object.optJSONArray("reminders");
        if (reminderArray != null && reminderArray.length() > 0) {
            rule.reminders.add(Reminder.fromJson(reminderArray.getJSONObject(0)));
        }
        return rule;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** A non-cross-midnight interval on one ISO weekday (Monday=1). */
    public static final class TimeWindow {
        public int dayOfWeek = 1;
        public int startMinute;
        public int endMinute;

        public TimeWindow() {
        }

        public TimeWindow(int dayOfWeek, int startMinute, int endMinute) {
            this.dayOfWeek = dayOfWeek;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("day", dayOfWeek)
                    .put("start", startMinute)
                    .put("end", endMinute);
        }

        static TimeWindow fromJson(JSONObject object) {
            return new TimeWindow(
                    clamp(object.optInt("day", 1), 1, 7),
                    clamp(object.optInt("start", 0), 0, 1439),
                    clamp(object.optInt("end", 1), 1, 1440));
        }
    }

    /** A recurring reminder based on today's cumulative usage. */
    public static final class Reminder {
        public static final int USED_MINUTES = 0;
        // Kept for reading rules created by version 1.0.1.
        public static final int REMAINING_MINUTES = 1;
        public static final int BEFORE_WINDOW_END = 2;
        public static final int DISPLAY_POPUP = 0;
        public static final int DISPLAY_FULL_PAGE = 1;

        public int type;
        public int thresholdMinutes = 5;
        public String customText = "";
        public int displayMode = DISPLAY_POPUP;
        public String imageUri = "";

        public Reminder() {
        }

        public Reminder(int type, int thresholdMinutes, String customText) {
            this(type, thresholdMinutes, customText, DISPLAY_POPUP, "");
        }

        public Reminder(int type, int thresholdMinutes, String customText,
                        int displayMode, String imageUri) {
            this.type = type;
            this.thresholdMinutes = Math.max(1, thresholdMinutes);
            this.customText = customText == null ? "" : customText;
            this.displayMode = clamp(displayMode, DISPLAY_POPUP, DISPLAY_FULL_PAGE);
            this.imageUri = imageUri == null ? "" : imageUri;
        }

        public String stableId() {
            return USED_MINUTES + ":" + thresholdMinutes + ":" + customText.hashCode()
                    + ":" + displayMode + ":" + imageUri.hashCode();
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("type", type)
                    .put("thresholdMinutes", thresholdMinutes)
                    .put("customText", customText)
                    .put("displayMode", displayMode)
                    .put("imageUri", imageUri);
        }

        static Reminder fromJson(JSONObject object) {
            return new Reminder(
                    USED_MINUTES,
                    clamp(object.optInt("thresholdMinutes", 5), 1, 1440),
                    object.optString("customText", ""),
                    clamp(object.optInt("displayMode", DISPLAY_POPUP),
                            DISPLAY_POPUP, DISPLAY_FULL_PAGE),
                    object.optString("imageUri", ""));
        }
    }
}

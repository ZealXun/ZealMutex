package com.zealmutex.app.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete user-owned configuration for one standalone app or application group.
 *
 * <p>Standalone rules use their package name as the stable key; group rules
 * use a generated {@code group:UUID} key and keep members in selection order.
 * The class deliberately uses only platform JSON classes to keep the APK small.</p>
 */
public final class Rule {
    public static final int MODE_DAILY_LIMIT = 0;
    public static final int MODE_TIME_WINDOWS = 1;
    public static final int ALL_WEEKDAYS_MASK = (1 << 7) - 1;

    public String packageName = "";
    public String appLabel = "";
    public boolean group;
    public boolean pinned;
    public int mode = MODE_DAILY_LIMIT;
    public int dailyLimitMinutes = 60;
    public int temporaryUnlocksPerDay = 1;
    public int temporaryUnlockMinutes = 5;
    public int activeWeekdaysMask = ALL_WEEKDAYS_MASK;
    public boolean remindOnInactiveDays = true;
    public String extraLockMessage = "";
    public final List<AppMember> members = new ArrayList<>();
    public final List<TimeWindow> windows = new ArrayList<>();
    public final List<Reminder> reminders = new ArrayList<>();

    public Rule copy() {
        Rule copy = new Rule();
        copy.packageName = packageName;
        copy.appLabel = appLabel;
        copy.group = group;
        copy.pinned = pinned;
        copy.mode = mode;
        copy.dailyLimitMinutes = dailyLimitMinutes;
        copy.temporaryUnlocksPerDay = temporaryUnlocksPerDay;
        copy.temporaryUnlockMinutes = temporaryUnlockMinutes;
        copy.activeWeekdaysMask = activeWeekdaysMask;
        copy.remindOnInactiveDays = remindOnInactiveDays;
        copy.extraLockMessage = extraLockMessage;
        for (AppMember member : members) {
            copy.members.add(new AppMember(member.packageName, member.label));
        }
        for (TimeWindow window : windows) {
            copy.windows.add(new TimeWindow(window.startMinute, window.endMinute));
        }
        for (Reminder reminder : reminders) {
            copy.reminders.add(new Reminder(reminder.type, reminder.thresholdMinutes,
                    reminder.customText, reminder.displayMode, reminder.imageUri));
        }
        return copy;
    }

    /** Copies settings that are safe to apply without weakening today's limits. */
    public void applyImmediateSettingsFrom(Rule source) {
        appLabel = source.appLabel;
        pinned = source.pinned;
        extraLockMessage = source.extraLockMessage;
        remindOnInactiveDays = source.remindOnInactiveDays;
        reminders.clear();
        for (Reminder reminder : source.reminders) {
            reminders.add(new Reminder(reminder.type, reminder.thresholdMinutes,
                    reminder.customText, reminder.displayMode, reminder.imageUri));
        }
    }

    /** Compares settings whose changes must wait until tomorrow. */
    public boolean hasSameDelayedSettings(Rule other) {
        if (other == null
                || group != other.group
                || mode != other.mode
                || dailyLimitMinutes != other.dailyLimitMinutes
                || temporaryUnlocksPerDay != other.temporaryUnlocksPerDay
                || temporaryUnlockMinutes != other.temporaryUnlockMinutes
                || activeWeekdaysMask != other.activeWeekdaysMask
                || !hasSameMembers(other)
                || windows.size() != other.windows.size()) {
            return false;
        }
        for (int i = 0; i < windows.size(); i++) {
            TimeWindow left = windows.get(i);
            TimeWindow right = other.windows.get(i);
            if (left.startMinute != right.startMinute
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
        object.put("group", group);
        object.put("pinned", pinned);
        object.put("mode", mode);
        object.put("dailyLimitMinutes", dailyLimitMinutes);
        object.put("temporaryUnlocksPerDay", temporaryUnlocksPerDay);
        object.put("temporaryUnlockMinutes", temporaryUnlockMinutes);
        object.put("activeWeekdaysMask", activeWeekdaysMask);
        object.put("remindOnInactiveDays", remindOnInactiveDays);
        object.put("extraLockMessage", extraLockMessage);

        JSONArray memberArray = new JSONArray();
        for (AppMember member : members) {
            memberArray.put(member.toJson());
        }
        object.put("members", memberArray);

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
        rule.group = object.optBoolean("group", false);
        rule.pinned = object.optBoolean("pinned", false);
        rule.mode = object.optInt("mode", MODE_DAILY_LIMIT);
        rule.dailyLimitMinutes = clamp(object.optInt("dailyLimitMinutes", 60), 1, 1440);
        rule.temporaryUnlocksPerDay = clamp(object.optInt("temporaryUnlocksPerDay", 1), 0, 5);
        rule.temporaryUnlockMinutes = clamp(object.optInt("temporaryUnlockMinutes", 5), 1, 10);
        int weekdays = object.optInt("activeWeekdaysMask", ALL_WEEKDAYS_MASK)
                & ALL_WEEKDAYS_MASK;
        rule.activeWeekdaysMask = weekdays == 0 ? ALL_WEEKDAYS_MASK : weekdays;
        rule.remindOnInactiveDays = object.optBoolean("remindOnInactiveDays", true);
        rule.extraLockMessage = object.optString("extraLockMessage", "");

        JSONArray memberArray = object.optJSONArray("members");
        if (rule.group && memberArray != null) {
            for (int i = 0; i < memberArray.length(); i++) {
                AppMember member = AppMember.fromJson(memberArray.getJSONObject(i));
                if (!member.packageName.isEmpty() && !rule.containsPackage(member.packageName)) {
                    rule.members.add(member);
                }
            }
        }

        JSONArray windowArray = object.optJSONArray("windows");
        if (windowArray != null) {
            for (int i = 0; i < windowArray.length(); i++) {
                TimeWindow window = TimeWindow.fromJson(windowArray.getJSONObject(i));
                if (window.startMinute < window.endMinute
                        && !containsWindow(rule.windows, window)) {
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

    public boolean containsPackage(String installedPackage) {
        if (!group) {
            return packageName.equals(installedPackage);
        }
        for (AppMember member : members) {
            if (member.packageName.equals(installedPackage)) {
                return true;
            }
        }
        return false;
    }

    public String memberLabel(String installedPackage) {
        if (!group) {
            return appLabel;
        }
        for (AppMember member : members) {
            if (member.packageName.equals(installedPackage)) {
                return member.label;
            }
        }
        return installedPackage;
    }

    public Rule copyForSingleMember(AppMember member) {
        Rule single = copy();
        single.group = false;
        single.packageName = member.packageName;
        single.appLabel = member.label;
        single.members.clear();
        return single;
    }

    public boolean isActiveOnDay(int isoDayOfWeek) {
        return isoDayOfWeek >= 1 && isoDayOfWeek <= 7
                && (activeWeekdaysMask & (1 << (isoDayOfWeek - 1))) != 0;
    }

    public void setActiveOnDay(int isoDayOfWeek, boolean active) {
        if (isoDayOfWeek < 1 || isoDayOfWeek > 7) {
            return;
        }
        int flag = 1 << (isoDayOfWeek - 1);
        activeWeekdaysMask = active ? activeWeekdaysMask | flag : activeWeekdaysMask & ~flag;
    }

    public boolean shouldRemindOnDay(int isoDayOfWeek) {
        return isActiveOnDay(isoDayOfWeek) || remindOnInactiveDays;
    }

    private boolean hasSameMembers(Rule other) {
        if (members.size() != other.members.size()) {
            return false;
        }
        for (int i = 0; i < members.size(); i++) {
            AppMember left = members.get(i);
            AppMember right = other.members.get(i);
            if (!left.packageName.equals(right.packageName)
                    || !left.label.equals(right.label)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsWindow(List<TimeWindow> windows, TimeWindow candidate) {
        for (TimeWindow existing : windows) {
            if (existing.startMinute == candidate.startMinute
                    && existing.endMinute == candidate.endMinute) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** One launchable application owned by a group, kept in user-selected order. */
    public static final class AppMember {
        public String packageName = "";
        public String label = "";

        public AppMember() {
        }

        public AppMember(String packageName, String label) {
            this.packageName = packageName == null ? "" : packageName;
            this.label = label == null || label.isEmpty() ? this.packageName : label;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("package", packageName)
                    .put("label", label);
        }

        static AppMember fromJson(JSONObject object) {
            String packageName = object.optString("package", "");
            return new AppMember(packageName, object.optString("label", packageName));
        }
    }

    /** A non-cross-midnight interval shared by every active weekday. */
    public static final class TimeWindow {
        public int startMinute;
        public int endMinute;

        public TimeWindow() {
        }

        public TimeWindow(int startMinute, int endMinute) {
            this.startMinute = startMinute;
            this.endMinute = endMinute;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("start", startMinute)
                    .put("end", endMinute);
        }

        static TimeWindow fromJson(JSONObject object) {
            return new TimeWindow(
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

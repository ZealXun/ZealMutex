package com.zealmutex.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Single-process repository for rules, daily counters and weekly reports.
 *
 * <p>The in-memory JSON tree is flushed at most once every five seconds while
 * counting usage, which avoids writing flash storage every second. Explicit
 * user changes are committed immediately.</p>
 */
public final class DataStore {
    private static final String PREFS = "zealmutex_state";
    private static final String STATE = "state";
    private static volatile DataStore instance;

    private final SharedPreferences preferences;
    private JSONObject root;
    private long lastLazyPersistElapsed;

    private DataStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            root = new JSONObject(preferences.getString(STATE, "{}"));
        } catch (JSONException invalidState) {
            root = new JSONObject();
        }
        ensureShape();
    }

    public static DataStore get(Context context) {
        DataStore local = instance;
        if (local == null) {
            synchronized (DataStore.class) {
                local = instance;
                if (local == null) {
                    local = new DataStore(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Returns active rules after applying any next-day changes. */
    public synchronized List<Rule> getActiveRules(long nowMillis) {
        rollover(nowMillis);
        List<Rule> result = new ArrayList<>();
        JSONObject active = object("activeRules");
        Iterator<String> keys = active.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                result.add(Rule.fromJson(active.getJSONObject(key)));
            } catch (JSONException ignored) {
                // A single corrupt rule must not disable every other rule.
            }
        }
        Collections.sort(result, (left, right) ->
                left.appLabel.compareToIgnoreCase(right.appLabel));
        return result;
    }

    public synchronized Rule getActiveRule(String packageName, long nowMillis) {
        rollover(nowMillis);
        return parseRule(object("activeRules").optJSONObject(packageName));
    }

    /** Returns tomorrow's pending version when one exists, otherwise active. */
    public synchronized Rule getEditableRule(String packageName, long nowMillis) {
        rollover(nowMillis);
        JSONObject pending = object("pending").optJSONObject(packageName);
        if (pending != null && !pending.optBoolean("delete", false)) {
            Rule rule = parseRule(pending.optJSONObject("rule"));
            if (rule != null) {
                return rule;
            }
        }
        return parseRule(object("activeRules").optJSONObject(packageName));
    }

    /** Saves immediate fields now and queues only limit-related changes for tomorrow. */
    public synchronized boolean saveRule(Rule rule, long nowMillis) {
        rollover(nowMillis);
        try {
            JSONObject active = object("activeRules");
            if (!active.has(rule.packageName)) {
                active.put(rule.packageName, rule.toJson());
                persistNow();
                return true;
            }

            Rule activeRule = parseRule(active.optJSONObject(rule.packageName));
            if (activeRule == null) {
                active.put(rule.packageName, rule.toJson());
                persistNow();
                return true;
            }
            activeRule.applyImmediateSettingsFrom(rule);
            active.put(rule.packageName, activeRule.toJson());

            JSONObject pending = object("pending");
            JSONObject existingPending = pending.optJSONObject(rule.packageName);
            boolean deleteScheduled = existingPending != null
                    && existingPending.optBoolean("delete", false);
            if (!deleteScheduled) {
                if (activeRule.hasSameDelayedSettings(rule)) {
                    pending.remove(rule.packageName);
                } else {
                    pending.put(rule.packageName, new JSONObject()
                            .put("effectiveDate", date(nowMillis).plusDays(1).toString())
                            .put("delete", false)
                            .put("rule", rule.toJson()));
                }
            }
            persistNow();
        } catch (JSONException ignored) {
            // All values originate from local primitives, so this is defensive.
        }
        return false;
    }

    public synchronized void scheduleDelete(String packageName, long nowMillis) {
        rollover(nowMillis);
        if (!object("activeRules").has(packageName)) {
            return;
        }
        try {
            object("pending").put(packageName, new JSONObject()
                    .put("effectiveDate", date(nowMillis).plusDays(1).toString())
                    .put("delete", true));
            persistNow();
        } catch (JSONException ignored) {
        }
    }

    public synchronized boolean hasScheduledDelete(String packageName, long nowMillis) {
        rollover(nowMillis);
        JSONObject pending = object("pending").optJSONObject(packageName);
        return pending != null && pending.optBoolean("delete", false);
    }

    public synchronized void cancelScheduledDelete(String packageName, long nowMillis) {
        rollover(nowMillis);
        JSONObject pending = object("pending").optJSONObject(packageName);
        if (pending != null && pending.optBoolean("delete", false)) {
            object("pending").remove(packageName);
            persistNow();
        }
    }

    public synchronized String pendingDescription(String packageName, long nowMillis) {
        rollover(nowMillis);
        JSONObject pending = object("pending").optJSONObject(packageName);
        if (pending == null) {
            return "";
        }
        String action = pending.optBoolean("delete", false) ? "删除" : "限制修改";
        return action + "将在 " + pending.optString("effectiveDate", "明天") + " 生效";
    }

    public synchronized long getTodayUsageMs(String packageName, long nowMillis) {
        rollover(nowMillis);
        return todayApp(packageName, "", null, nowMillis).optLong("usedMs", 0L);
    }

    /** Never lowers the counter, so package reinstall and service restarts are safe. */
    public synchronized void seedTodayUsage(Rule rule, long measuredMs, long nowMillis) {
        rollover(nowMillis);
        JSONObject stats = todayApp(rule.packageName, rule.appLabel, rule, nowMillis);
        if (measuredMs > stats.optLong("usedMs", 0L)) {
            try {
                stats.put("usedMs", measuredMs);
                persistLazily();
            } catch (JSONException ignored) {
            }
        }
    }

    public synchronized void addUsage(Rule rule, long deltaMs, long nowMillis) {
        if (deltaMs <= 0L || deltaMs > 10_000L) {
            return;
        }
        rollover(nowMillis);
        JSONObject stats = todayApp(rule.packageName, rule.appLabel, rule, nowMillis);
        try {
            stats.put("usedMs", stats.optLong("usedMs", 0L) + deltaMs);
            persistLazily();
        } catch (JSONException ignored) {
        }
    }

    public synchronized int getTodayTemporaryUnlockCount(String packageName, long nowMillis) {
        rollover(nowMillis);
        return todayApp(packageName, "", null, nowMillis)
                .optInt("temporaryUnlockCount", 0);
    }

    public synchronized int incrementTemporaryUnlockCount(Rule rule, long nowMillis) {
        rollover(nowMillis);
        JSONObject stats = todayApp(rule.packageName, rule.appLabel, rule, nowMillis);
        int next = stats.optInt("temporaryUnlockCount", 0) + 1;
        try {
            stats.put("temporaryUnlockCount", next);
            persistNow();
        } catch (JSONException ignored) {
        }
        return next;
    }

    public synchronized boolean hasReminderFired(
            String packageName, String reminderId, long nowMillis) {
        rollover(nowMillis);
        JSONArray fired = todayApp(packageName, "", null, nowMillis)
                .optJSONArray("firedReminders");
        if (fired == null) {
            return false;
        }
        for (int i = 0; i < fired.length(); i++) {
            if (reminderId.equals(fired.optString(i))) {
                return true;
            }
        }
        return false;
    }

    public synchronized void markReminderFired(
            String packageName, String reminderId, long nowMillis) {
        rollover(nowMillis);
        JSONObject stats = todayApp(packageName, "", null, nowMillis);
        JSONArray fired = stats.optJSONArray("firedReminders");
        if (fired == null) {
            fired = new JSONArray();
            try {
                stats.put("firedReminders", fired);
            } catch (JSONException ignored) {
            }
        }
        fired.put(reminderId);
        persistNow();
    }

    public synchronized JSONArray getCurrentWeekDays(long nowMillis) {
        rollover(nowMillis);
        LocalDate monday = date(nowMillis).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        JSONArray copy = new JSONArray();
        JSONArray raw = array("rawDays");
        for (int i = 0; i < raw.length(); i++) {
            JSONObject day = raw.optJSONObject(i);
            if (day == null) {
                continue;
            }
            LocalDate dayDate = parseDate(day.optString("date"));
            if (dayDate != null && !dayDate.isBefore(monday)) {
                copy.put(copyObject(day));
            }
        }
        return copy;
    }

    public synchronized JSONArray getReports(long nowMillis) {
        rollover(nowMillis);
        return copyArray(array("reports"));
    }

    public synchronized void deleteReport(String reportId) {
        JSONArray current = array("reports");
        JSONArray kept = new JSONArray();
        for (int i = 0; i < current.length(); i++) {
            JSONObject report = current.optJSONObject(i);
            if (report != null && !reportId.equals(report.optString("id"))) {
                kept.put(report);
            }
        }
        put("reports", kept);
        persistNow();
    }

    public synchronized void forcePersist() {
        persistNow();
    }

    /** Applies pending rules, creates closed-week reports and ensures today's record. */
    public synchronized void rollover(long nowMillis) {
        LocalDate today = date(nowMillis);
        boolean changed = applyPending(today);
        changed |= generateClosedWeekReports(today, nowMillis);
        if (findDay(today.toString()) == null) {
            try {
                array("rawDays").put(new JSONObject()
                        .put("date", today.toString())
                        .put("apps", new JSONObject()));
                changed = true;
            } catch (JSONException ignored) {
            }
        }
        if (changed) {
            persistNow();
        }
    }

    private boolean applyPending(LocalDate today) {
        JSONObject pending = object("pending");
        JSONObject active = object("activeRules");
        List<String> applied = new ArrayList<>();
        Iterator<String> keys = pending.keys();
        while (keys.hasNext()) {
            String packageName = keys.next();
            JSONObject change = pending.optJSONObject(packageName);
            if (change == null) {
                applied.add(packageName);
                continue;
            }
            LocalDate effective = parseDate(change.optString("effectiveDate"));
            if (effective == null || effective.isAfter(today)) {
                continue;
            }
            if (change.optBoolean("delete", false)) {
                active.remove(packageName);
            } else {
                JSONObject rule = change.optJSONObject("rule");
                if (rule != null) {
                    try {
                        active.put(packageName, rule);
                    } catch (JSONException ignored) {
                    }
                }
            }
            applied.add(packageName);
        }
        for (String packageName : applied) {
            pending.remove(packageName);
        }
        return !applied.isEmpty();
    }

    private boolean generateClosedWeekReports(LocalDate today, long nowMillis) {
        LocalDate currentMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        JSONArray raw = array("rawDays");
        List<LocalDate> closedWeeks = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject day = raw.optJSONObject(i);
            LocalDate dayDate = day == null ? null : parseDate(day.optString("date"));
            if (dayDate == null) {
                continue;
            }
            LocalDate week = dayDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (week.isBefore(currentMonday) && !closedWeeks.contains(week)) {
                closedWeeks.add(week);
            }
        }
        Collections.sort(closedWeeks);
        boolean changed = false;
        for (LocalDate week : closedWeeks) {
            if (!hasReport(week.toString())) {
                createReport(week, nowMillis);
            }
            removeRawWeek(week);
            changed = true;
        }
        return changed;
    }

    private void createReport(LocalDate monday, long nowMillis) {
        JSONObject aggregate = new JSONObject();
        JSONArray raw = array("rawDays");
        for (int i = 0; i < raw.length(); i++) {
            JSONObject day = raw.optJSONObject(i);
            LocalDate dayDate = day == null ? null : parseDate(day.optString("date"));
            if (dayDate == null || !dayDate.with(
                    TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).equals(monday)) {
                continue;
            }
            JSONObject apps = day.optJSONObject("apps");
            if (apps == null) {
                continue;
            }
            Iterator<String> keys = apps.keys();
            while (keys.hasNext()) {
                String packageName = keys.next();
                JSONObject source = apps.optJSONObject(packageName);
                if (source == null) {
                    continue;
                }
                JSONObject target = aggregate.optJSONObject(packageName);
                if (target == null) {
                    target = new JSONObject();
                    try {
                        target.put("label", source.optString("label", packageName));
                        target.put("usedMs", 0L);
                        target.put("overMs", 0L);
                        target.put("temporaryUnlockCount", 0);
                        target.put("days", 0);
                        aggregate.put(packageName, target);
                    } catch (JSONException ignored) {
                    }
                }
                long used = source.optLong("usedMs", 0L);
                long over = 0L;
                if (source.optInt("mode", Rule.MODE_DAILY_LIMIT) == Rule.MODE_DAILY_LIMIT) {
                    over = Math.max(0L,
                            used - source.optLong("limitMinutes", 0L) * 60_000L);
                }
                try {
                    target.put("usedMs", target.optLong("usedMs", 0L) + used);
                    target.put("overMs", target.optLong("overMs", 0L) + over);
                    target.put("temporaryUnlockCount",
                            target.optInt("temporaryUnlockCount", 0)
                                    + source.optInt("temporaryUnlockCount", 0));
                    target.put("days", target.optInt("days", 0) + 1);
                } catch (JSONException ignored) {
                }
            }
        }

        if (aggregate.length() == 0) {
            return;
        }
        try {
            JSONObject report = new JSONObject()
                    .put("id", monday.toString())
                    .put("start", monday.toString())
                    .put("end", monday.plusDays(6).toString())
                    .put("createdAt", nowMillis)
                    .put("apps", aggregate);
            array("reports").put(report);
        } catch (JSONException ignored) {
        }
    }

    private void removeRawWeek(LocalDate monday) {
        JSONArray raw = array("rawDays");
        JSONArray kept = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject day = raw.optJSONObject(i);
            LocalDate dayDate = day == null ? null : parseDate(day.optString("date"));
            if (dayDate == null || !dayDate.with(
                    TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).equals(monday)) {
                if (day != null) {
                    kept.put(day);
                }
            }
        }
        put("rawDays", kept);
    }

    private boolean hasReport(String reportId) {
        JSONArray reports = array("reports");
        for (int i = 0; i < reports.length(); i++) {
            JSONObject report = reports.optJSONObject(i);
            if (report != null && reportId.equals(report.optString("id"))) {
                return true;
            }
        }
        return false;
    }

    private JSONObject todayApp(String packageName, String label, Rule rule, long nowMillis) {
        LocalDate today = date(nowMillis);
        JSONObject day = findDay(today.toString());
        if (day == null) {
            rollover(nowMillis);
            day = findDay(today.toString());
        }
        JSONObject apps = day == null ? new JSONObject() : day.optJSONObject("apps");
        if (apps == null) {
            apps = new JSONObject();
            try {
                if (day != null) {
                    day.put("apps", apps);
                }
            } catch (JSONException ignored) {
            }
        }
        JSONObject stats = apps.optJSONObject(packageName);
        if (stats == null) {
            stats = new JSONObject();
            try {
                stats.put("label", label.isEmpty() ? packageName : label);
                stats.put("usedMs", 0L);
                stats.put("temporaryUnlockCount", 0);
                stats.put("firedReminders", new JSONArray());
                stats.put("mode", rule == null ? Rule.MODE_DAILY_LIMIT : rule.mode);
                stats.put("limitMinutes", rule == null ? 0 : rule.dailyLimitMinutes);
                apps.put(packageName, stats);
            } catch (JSONException ignored) {
            }
        } else if (rule != null) {
            try {
                stats.put("label", rule.appLabel);
                stats.put("mode", rule.mode);
                stats.put("limitMinutes", rule.dailyLimitMinutes);
            } catch (JSONException ignored) {
            }
        }
        return stats;
    }

    private JSONObject findDay(String date) {
        JSONArray raw = array("rawDays");
        for (int i = 0; i < raw.length(); i++) {
            JSONObject day = raw.optJSONObject(i);
            if (day != null && date.equals(day.optString("date"))) {
                return day;
            }
        }
        return null;
    }

    private static LocalDate date(long nowMillis) {
        return Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private Rule parseRule(JSONObject object) {
        if (object == null) {
            return null;
        }
        try {
            return Rule.fromJson(object);
        } catch (JSONException ignored) {
            return null;
        }
    }

    private void ensureShape() {
        if (root.optJSONObject("activeRules") == null) {
            put("activeRules", new JSONObject());
        }
        if (root.optJSONObject("pending") == null) {
            put("pending", new JSONObject());
        }
        if (root.optJSONArray("rawDays") == null) {
            put("rawDays", new JSONArray());
        }
        if (root.optJSONArray("reports") == null) {
            put("reports", new JSONArray());
        }
    }

    private JSONObject object(String key) {
        JSONObject value = root.optJSONObject(key);
        if (value == null) {
            value = new JSONObject();
            put(key, value);
        }
        return value;
    }

    private JSONArray array(String key) {
        JSONArray value = root.optJSONArray(key);
        if (value == null) {
            value = new JSONArray();
            put(key, value);
        }
        return value;
    }

    private void put(String key, Object value) {
        try {
            root.put(key, value);
        } catch (JSONException ignored) {
        }
    }

    private void persistLazily() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLazyPersistElapsed >= 5_000L) {
            lastLazyPersistElapsed = now;
            preferences.edit().putString(STATE, root.toString()).apply();
        }
    }

    private void persistNow() {
        lastLazyPersistElapsed = SystemClock.elapsedRealtime();
        preferences.edit().putString(STATE, root.toString()).commit();
    }

    private static JSONObject copyObject(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (JSONException impossible) {
            return new JSONObject();
        }
    }

    private static JSONArray copyArray(JSONArray source) {
        try {
            return new JSONArray(source.toString());
        } catch (JSONException impossible) {
            return new JSONArray();
        }
    }
}

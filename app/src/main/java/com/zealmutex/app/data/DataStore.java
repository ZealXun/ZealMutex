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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        JSONArray order = array("ruleOrder");
        Set<String> added = new HashSet<>();
        for (int i = 0; i < order.length(); i++) {
            String packageName = order.optString(i);
            Rule rule = parseRule(active.optJSONObject(packageName));
            if (rule != null) {
                result.add(rule);
                added.add(packageName);
            }
        }
        boolean repairedOrder = false;
        Iterator<String> keys = active.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (added.contains(key)) {
                continue;
            }
            Rule rule = parseRule(active.optJSONObject(key));
            if (rule != null) {
                result.add(rule);
                order.put(key);
                repairedOrder = true;
            }
        }
        if (repairedOrder) {
            persistNow();
        }
        return result;
    }

    /** Returns active rules plus newly created rules waiting for tomorrow. */
    public synchronized List<Rule> getRulesForDisplay(long nowMillis) {
        rollover(nowMillis);
        List<Rule> result = new ArrayList<>();
        JSONObject active = object("activeRules");
        JSONObject pending = object("pending");
        JSONArray order = array("ruleOrder");
        for (int i = 0; i < order.length(); i++) {
            String packageName = order.optString(i);
            Rule rule = parseRule(active.optJSONObject(packageName));
            if (rule == null) {
                JSONObject change = pending.optJSONObject(packageName);
                if (change != null && !change.optBoolean("delete", false)
                        && change.optBoolean("visible", true)) {
                    rule = parseRule(change.optJSONObject("rule"));
                }
            }
            if (rule != null) {
                result.add(rule);
            }
        }
        List<Rule> ordered = new ArrayList<>();
        for (Rule rule : result) {
            if (rule.pinned) {
                ordered.add(rule);
            }
        }
        for (Rule rule : result) {
            if (!rule.pinned) {
                ordered.add(rule);
            }
        }
        return ordered;
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

    public synchronized Rule getActiveRuleForPackage(String installedPackage, long nowMillis) {
        for (Rule rule : getActiveRules(nowMillis)) {
            if (rule.containsPackage(installedPackage)) {
                return rule;
            }
        }
        return null;
    }

    public synchronized Rule getEditableRuleForPackage(String installedPackage, long nowMillis) {
        for (Rule rule : tomorrowRules(nowMillis)) {
            if (rule.containsPackage(installedPackage)) {
                return rule;
            }
        }
        return null;
    }

    public synchronized boolean isGroupNameAvailable(
            String name, String excludedRuleKey, long nowMillis) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        for (Rule rule : tomorrowRules(nowMillis)) {
            if (rule.group && !rule.packageName.equals(excludedRuleKey)
                    && normalized.equalsIgnoreCase(rule.appLabel.trim())) {
                return false;
            }
        }
        return true;
    }

    /** Saves a single-app rule now when new, or queues limit-related edits for tomorrow. */
    public synchronized boolean saveRule(Rule rule, long nowMillis) {
        rollover(nowMillis);
        try {
            JSONObject active = object("activeRules");
            if (!active.has(rule.packageName)) {
                active.put(rule.packageName, rule.toJson());
                object("pending").remove(rule.packageName);
                appendRuleOrder(rule.packageName);
                persistNow();
                return true;
            }

            Rule activeRule = parseRule(active.optJSONObject(rule.packageName));
            if (activeRule == null) {
                active.put(rule.packageName, rule.toJson());
                appendRuleOrder(rule.packageName);
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

    public synchronized GroupSaveResult saveGroupRule(Rule desired, long nowMillis) {
        rollover(nowMillis);
        if (!desired.group || desired.members.isEmpty()) {
            return GroupSaveResult.INVALID;
        }
        clearGroupTransaction(desired.packageName);
        clearConflictingGroupTransactions(desired, nowMillis);
        JSONObject scheduledChange = object("pending").optJSONObject(desired.packageName);
        boolean deleteScheduled = isExplicitDelete(scheduledChange);
        Rule activeRule = parseRule(object("activeRules").optJSONObject(desired.packageName));
        boolean ownedToday = false;
        for (Rule.AppMember member : desired.members) {
            Rule owner = getActiveRuleForPackage(member.packageName, nowMillis);
            if (owner != null && !owner.packageName.equals(desired.packageName)) {
                ownedToday = true;
                break;
            }
        }

        RuleAssignmentPlanner.Plan plan = RuleAssignmentPlanner.planGroupSave(
                tomorrowRules(nowMillis), desired);
        if (activeRule == null && !ownedToday && !plan.migrated
                && desired.members.size() >= 2) {
            try {
                object("activeRules").put(desired.packageName, desired.toJson());
                object("pending").remove(desired.packageName);
                appendRuleOrder(desired.packageName);
                persistNow();
                return GroupSaveResult.CREATED_IMMEDIATELY;
            } catch (JSONException ignored) {
                return GroupSaveResult.INVALID;
            }
        }

        if (activeRule != null) {
            try {
                activeRule.applyImmediateSettingsFrom(desired);
                object("activeRules").put(activeRule.packageName, activeRule.toJson());
            } catch (JSONException ignored) {
                return GroupSaveResult.INVALID;
            }
        }

        if (deleteScheduled) {
            persistNow();
            return GroupSaveResult.UPDATED_IMMEDIATELY;
        }

        boolean affectsOtherRules = plan.migrated || plan.deletions.size() > 1
                || plan.upserts.size() > 1;
        if (activeRule != null && activeRule.hasSameDelayedSettings(desired)
                && !affectsOtherRules) {
            object("pending").remove(desired.packageName);
            persistNow();
            return GroupSaveResult.UPDATED_IMMEDIATELY;
        }

        LocalDate effectiveDate = date(nowMillis).plusDays(1);
        for (String key : plan.deletions) {
            boolean conversion = plan.orderReplacements.containsKey(key);
            queueDelete(key, effectiveDate, desired.packageName, conversion);
        }
        for (Map.Entry<String, Rule> entry : plan.upserts.entrySet()) {
            String replacementSource = null;
            for (Map.Entry<String, String> replacement : plan.orderReplacements.entrySet()) {
                if (replacement.getValue().equals(entry.getKey())) {
                    replacementSource = replacement.getKey();
                    break;
                }
            }
            boolean visible = entry.getKey().equals(desired.packageName);
            queueRule(entry.getValue(), effectiveDate, visible, replacementSource,
                    desired.packageName);
        }
        if (plan.upserts.containsKey(desired.packageName)) {
            appendRuleOrder(desired.packageName);
        }
        persistNow();
        return GroupSaveResult.SCHEDULED;
    }

    public enum GroupSaveResult {
        CREATED_IMMEDIATELY,
        UPDATED_IMMEDIATELY,
        SCHEDULED,
        INVALID
    }

    public synchronized void setPinned(String ruleKey, boolean pinned, long nowMillis) {
        rollover(nowMillis);
        JSONObject active = object("activeRules");
        Rule activeRule = parseRule(active.optJSONObject(ruleKey));
        try {
            if (activeRule != null) {
                activeRule.pinned = pinned;
                active.put(ruleKey, activeRule.toJson());
            }
            JSONObject change = object("pending").optJSONObject(ruleKey);
            if (change != null && !change.optBoolean("delete", false)) {
                Rule pendingRule = parseRule(change.optJSONObject("rule"));
                if (pendingRule != null) {
                    pendingRule.pinned = pinned;
                    change.put("rule", pendingRule.toJson());
                }
            }
            persistNow();
        } catch (JSONException ignored) {
        }
    }

    public synchronized void scheduleDelete(String packageName, long nowMillis) {
        rollover(nowMillis);
        if (!object("activeRules").has(packageName)) {
            return;
        }
        clearTransactionAffectingRule(packageName);
        clearGroupTransaction(packageName);
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
        return isExplicitDelete(pending);
    }

    public synchronized void cancelScheduledDelete(String packageName, long nowMillis) {
        rollover(nowMillis);
        JSONObject pending = object("pending").optJSONObject(packageName);
        if (isExplicitDelete(pending)) {
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
        String action;
        String changeType = pending.optString("changeType", "");
        if ("conversion".equals(changeType)) {
            action = "应用组转为单独规则";
        } else if ("migration".equals(changeType)) {
            action = "成员迁移";
        } else if (pending.optBoolean("delete", false)) {
            action = "删除";
        } else if (object("activeRules").has(packageName)) {
            action = "限制修改";
        } else {
            action = "新规则";
        }
        return action + "将在 " + pending.optString("effectiveDate", "明天") + " 生效";
    }

    public synchronized long getTodayUsageMs(String packageName, long nowMillis) {
        rollover(nowMillis);
        return todayApp(packageName, "", null, nowMillis).optLong("usedMs", 0L);
    }

    public synchronized long getTodayUsageMs(Rule rule, long nowMillis) {
        rollover(nowMillis);
        return todayApp(rule.packageName, rule.appLabel, rule, nowMillis)
                .optLong("usedMs", 0L);
    }

    /** Never lowers the counter, so package reinstall and service restarts are safe. */
    public synchronized void seedTodayUsage(Rule rule, long measuredMs, long nowMillis) {
        if (rule.group) {
            return;
        }
        seedTodayUsage(rule, rule.packageName, rule.appLabel, measuredMs, nowMillis);
    }

    /** Seeds one installed member while preserving the group's aggregate counter. */
    public synchronized void seedTodayUsage(
            Rule rule, String installedPackage, String installedLabel,
            long measuredMs, long nowMillis) {
        rollover(nowMillis);
        JSONObject stats = todayApp(rule.packageName, rule.appLabel, rule, nowMillis);
        try {
            if (rule.group) {
                JSONObject member = todayMember(stats, installedPackage, installedLabel);
                long previous = member.optLong("usedMs", 0L);
                if (measuredMs > previous) {
                    member.put("usedMs", measuredMs);
                    stats.put("usedMs", stats.optLong("usedMs", 0L) + measuredMs - previous);
                    persistLazily();
                }
            } else if (measuredMs > stats.optLong("usedMs", 0L)) {
                stats.put("usedMs", measuredMs);
                persistLazily();
            }
        } catch (JSONException ignored) {
        }
    }

    public synchronized void addUsageInterval(
            Rule rule, long startMillis, long endMillis) {
        addUsageInterval(rule, rule.packageName, rule.appLabel, startMillis, endMillis);
    }

    /** Adds one foreground interval to both the owner rule and its member breakdown. */
    public synchronized void addUsageInterval(
            Rule rule, String installedPackage, String installedLabel,
            long startMillis, long endMillis) {
        long totalMs = endMillis - startMillis;
        if (totalMs <= 0L || totalMs > 60_000L) {
            return;
        }
        long cursor = startMillis;
        while (cursor < endMillis) {
            ZoneId zone = ZoneId.systemDefault();
            long nextMidnight = Instant.ofEpochMilli(cursor)
                    .atZone(zone)
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli();
            long partEnd = Math.min(endMillis, nextMidnight);
            rollover(cursor);
            JSONObject stats = todayApp(rule.packageName, rule.appLabel, rule, cursor);
            try {
                long delta = partEnd - cursor;
                stats.put("usedMs", stats.optLong("usedMs", 0L) + delta);
                if (rule.group) {
                    JSONObject member = todayMember(stats, installedPackage, installedLabel);
                    member.put("usedMs", member.optLong("usedMs", 0L) + delta);
                }
            } catch (JSONException ignored) {
            }
            cursor = partEnd;
        }
        persistLazily();
    }

    public synchronized int getTodayTemporaryUnlockCount(String packageName, long nowMillis) {
        rollover(nowMillis);
        return todayApp(packageName, "", null, nowMillis)
                .optInt("temporaryUnlockCount", 0);
    }

    public synchronized boolean startTemporaryUnlock(Rule rule, long nowMillis) {
        rollover(nowMillis);
        JSONObject stats = todayApp(rule.packageName, rule.appLabel, rule, nowMillis);
        int used = stats.optInt("temporaryUnlockCount", 0);
        if (rule.temporaryUnlocksPerDay == 0 || used >= rule.temporaryUnlocksPerDay) {
            return false;
        }
        try {
            stats.put("temporaryUnlockCount", used + 1);
            stats.put("temporaryUnlockUntil",
                    nowMillis + rule.temporaryUnlockMinutes * 60_000L);
            persistNow();
            return true;
        } catch (JSONException ignored) {
            return false;
        }
    }

    public synchronized long getTemporaryUnlockRemainingMs(
            String packageName, long nowMillis) {
        rollover(nowMillis);
        long until = todayApp(packageName, "", null, nowMillis)
                .optLong("temporaryUnlockUntil", 0L);
        return Math.max(0L, until - nowMillis);
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
            String replacement = change.optString("replaceOrderKey", "");
            if (!replacement.isEmpty()) {
                replaceRuleOrder(replacement, packageName);
            }
        }

        keys = pending.keys();
        while (keys.hasNext()) {
            String packageName = keys.next();
            JSONObject change = pending.optJSONObject(packageName);
            if (change == null) {
                if (!applied.contains(packageName)) {
                    applied.add(packageName);
                }
                continue;
            }
            LocalDate effective = parseDate(change.optString("effectiveDate"));
            if (effective == null || effective.isAfter(today)) {
                continue;
            }
            if (change.optBoolean("delete", false)) {
                active.remove(packageName);
                removeRuleOrder(packageName);
            } else {
                JSONObject rule = change.optJSONObject("rule");
                if (rule != null) {
                    try {
                        active.put(packageName, rule);
                        appendRuleOrder(packageName);
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

    private List<Rule> tomorrowRules(long nowMillis) {
        rollover(nowMillis);
        Map<String, Rule> byKey = new LinkedHashMap<>();
        for (Rule rule : getActiveRules(nowMillis)) {
            byKey.put(rule.packageName, rule);
        }
        JSONObject pending = object("pending");
        Iterator<String> keys = pending.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject change = pending.optJSONObject(key);
            if (change == null) {
                continue;
            }
            if (change.optBoolean("delete", false)) {
                byKey.remove(key);
            } else {
                Rule rule = parseRule(change.optJSONObject("rule"));
                if (rule != null) {
                    byKey.put(key, rule);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private void clearGroupTransaction(String owner) {
        JSONObject pending = object("pending");
        List<String> removed = new ArrayList<>();
        Iterator<String> keys = pending.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject change = pending.optJSONObject(key);
            if (change != null && owner.equals(change.optString("transactionOwner"))) {
                removed.add(key);
            }
        }
        for (String key : removed) {
            JSONObject change = pending.optJSONObject(key);
            if (change != null && change.optBoolean("visible", false)
                    && !object("activeRules").has(key)) {
                removeRuleOrder(key);
            }
            pending.remove(key);
        }
    }

    private void clearConflictingGroupTransactions(Rule desired, long nowMillis) {
        boolean cleared;
        do {
            cleared = false;
            List<Rule> future = tomorrowRules(nowMillis);
            for (Rule.AppMember member : desired.members) {
                Rule owner = null;
                for (Rule candidate : future) {
                    if (candidate.containsPackage(member.packageName)) {
                        owner = candidate;
                        break;
                    }
                }
                if (owner == null || owner.packageName.equals(desired.packageName)) {
                    continue;
                }
                JSONObject change = object("pending").optJSONObject(owner.packageName);
                String transactionOwner = change == null
                        ? "" : change.optString("transactionOwner", "");
                if (!transactionOwner.isEmpty()) {
                    clearGroupTransaction(transactionOwner);
                    cleared = true;
                    break;
                }
            }
        } while (cleared);
    }

    private void clearTransactionAffectingRule(String ruleKey) {
        JSONObject change = object("pending").optJSONObject(ruleKey);
        String transactionOwner = change == null
                ? "" : change.optString("transactionOwner", "");
        if (!transactionOwner.isEmpty()) {
            clearGroupTransaction(transactionOwner);
        }
    }

    private void queueRule(Rule rule, LocalDate effectiveDate, boolean visible,
                           String replaceOrderKey, String transactionOwner) {
        try {
            JSONObject change = new JSONObject()
                    .put("effectiveDate", effectiveDate.toString())
                    .put("delete", false)
                    .put("visible", visible)
                    .put("transactionOwner", transactionOwner)
                    .put("rule", rule.toJson());
            if (replaceOrderKey != null && !replaceOrderKey.isEmpty()) {
                change.put("replaceOrderKey", replaceOrderKey);
            }
            object("pending").put(rule.packageName, change);
        } catch (JSONException ignored) {
        }
    }

    private void queueDelete(String ruleKey, LocalDate effectiveDate,
                             String transactionOwner, boolean conversion) {
        try {
            object("pending").put(ruleKey, new JSONObject()
                    .put("effectiveDate", effectiveDate.toString())
                    .put("delete", true)
                    .put("changeType", conversion ? "conversion" : "migration")
                    .put("transactionOwner", transactionOwner));
        } catch (JSONException ignored) {
        }
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
                        target.put("group", source.optBoolean("group", false));
                        JSONArray memberOrder = source.optJSONArray("memberOrder");
                        target.put("memberOrder", copyArray(memberOrder == null
                                ? new JSONArray() : memberOrder));
                        target.put("members", new JSONObject());
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
                    mergeMemberUsage(target, source);
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
                stats.put("temporaryUnlockUntil", 0L);
                stats.put("firedReminders", new JSONArray());
                stats.put("mode", rule == null ? Rule.MODE_DAILY_LIMIT : rule.mode);
                stats.put("limitMinutes", rule == null ? 0 : rule.dailyLimitMinutes);
                stats.put("group", rule != null && rule.group);
                stats.put("memberOrder", new JSONArray());
                stats.put("members", new JSONObject());
                if (rule != null && rule.group) {
                    JSONArray order = stats.getJSONArray("memberOrder");
                    for (Rule.AppMember member : rule.members) {
                        order.put(member.packageName);
                        todayMember(stats, member.packageName, member.label);
                    }
                }
                apps.put(packageName, stats);
            } catch (JSONException ignored) {
            }
        } else if (rule != null) {
            try {
                stats.put("label", rule.appLabel);
                stats.put("mode", rule.mode);
                stats.put("limitMinutes", rule.dailyLimitMinutes);
                stats.put("group", rule.group);
                if (rule.group) {
                    JSONArray order = stats.optJSONArray("memberOrder");
                    if (order == null || order.length() == 0) {
                        order = new JSONArray();
                        for (Rule.AppMember member : rule.members) {
                            order.put(member.packageName);
                            todayMember(stats, member.packageName, member.label);
                        }
                        stats.put("memberOrder", order);
                    }
                }
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
        if (root.optJSONArray("ruleOrder") == null) {
            JSONArray order = new JSONArray();
            Iterator<String> keys = object("activeRules").keys();
            while (keys.hasNext()) {
                order.put(keys.next());
            }
            put("ruleOrder", order);
        }
    }

    private void appendRuleOrder(String packageName) {
        JSONArray order = array("ruleOrder");
        for (int i = 0; i < order.length(); i++) {
            if (packageName.equals(order.optString(i))) {
                return;
            }
        }
        order.put(packageName);
    }

    private void removeRuleOrder(String packageName) {
        JSONArray order = array("ruleOrder");
        JSONArray kept = new JSONArray();
        for (int i = 0; i < order.length(); i++) {
            String current = order.optString(i);
            if (!packageName.equals(current)) {
                kept.put(current);
            }
        }
        put("ruleOrder", kept);
    }

    private void replaceRuleOrder(String sourceKey, String targetKey) {
        JSONArray order = array("ruleOrder");
        JSONArray replaced = new JSONArray();
        Set<String> added = new HashSet<>();
        boolean foundSource = false;
        for (int i = 0; i < order.length(); i++) {
            String current = order.optString(i);
            if (sourceKey.equals(current)) {
                current = targetKey;
                foundSource = true;
            }
            if (!current.isEmpty() && added.add(current)) {
                replaced.put(current);
            }
        }
        if (!foundSource && added.add(targetKey)) {
            replaced.put(targetKey);
        }
        put("ruleOrder", replaced);
    }

    private static boolean isExplicitDelete(JSONObject change) {
        return change != null
                && change.optBoolean("delete", false)
                && change.optString("transactionOwner", "").isEmpty();
    }

    private JSONObject todayMember(JSONObject stats, String packageName, String label)
            throws JSONException {
        JSONObject members = stats.optJSONObject("members");
        if (members == null) {
            members = new JSONObject();
            stats.put("members", members);
        }
        JSONObject member = members.optJSONObject(packageName);
        if (member == null) {
            member = new JSONObject()
                    .put("label", label == null || label.isEmpty() ? packageName : label)
                    .put("usedMs", 0L);
            members.put(packageName, member);
        }
        return member;
    }

    private static void mergeMemberUsage(JSONObject target, JSONObject source)
            throws JSONException {
        JSONObject sourceMembers = source.optJSONObject("members");
        JSONObject targetMembers = target.optJSONObject("members");
        if (sourceMembers == null || targetMembers == null) {
            return;
        }
        JSONArray targetOrder = target.optJSONArray("memberOrder");
        JSONArray sourceOrder = source.optJSONArray("memberOrder");
        if (targetOrder != null && sourceOrder != null) {
            Set<String> ordered = new HashSet<>();
            for (int i = 0; i < targetOrder.length(); i++) {
                ordered.add(targetOrder.optString(i));
            }
            for (int i = 0; i < sourceOrder.length(); i++) {
                String packageName = sourceOrder.optString(i);
                if (!packageName.isEmpty() && ordered.add(packageName)) {
                    targetOrder.put(packageName);
                }
            }
        }
        Iterator<String> keys = sourceMembers.keys();
        while (keys.hasNext()) {
            String packageName = keys.next();
            JSONObject sourceMember = sourceMembers.optJSONObject(packageName);
            if (sourceMember == null) {
                continue;
            }
            JSONObject targetMember = targetMembers.optJSONObject(packageName);
            if (targetMember == null) {
                targetMember = new JSONObject()
                        .put("label", sourceMember.optString("label", packageName))
                        .put("usedMs", 0L);
                targetMembers.put(packageName, targetMember);
            }
            targetMember.put("usedMs", targetMember.optLong("usedMs", 0L)
                    + sourceMember.optLong("usedMs", 0L));
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

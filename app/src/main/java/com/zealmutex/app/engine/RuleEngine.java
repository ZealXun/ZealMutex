package com.zealmutex.app.engine;

import android.content.Context;
import android.os.SystemClock;

import com.zealmutex.app.data.DataStore;
import com.zealmutex.app.data.Rule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Pure decision layer shared by the monitoring service and lock overlay. */
public final class RuleEngine {
    private static final Map<String, Long> TEMPORARY_UNLOCK_UNTIL =
            new ConcurrentHashMap<>();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);

    private RuleEngine() {
    }

    public static Decision evaluate(Context context, Rule rule, long nowMillis) {
        DataStore store = DataStore.get(context);
        long usedMs = store.getTodayUsageMs(rule.packageName, nowMillis);
        int usedUnlocks = store.getTodayTemporaryUnlockCount(rule.packageName, nowMillis);

        Long unlockUntil = TEMPORARY_UNLOCK_UNTIL.get(rule.packageName);
        if (unlockUntil != null && unlockUntil > SystemClock.elapsedRealtime()) {
            return Decision.allowed(rule, usedMs, usedUnlocks);
        }
        TEMPORARY_UNLOCK_UNTIL.remove(rule.packageName);

        if (rule.mode == Rule.MODE_DAILY_LIMIT) {
            long limitMs = rule.dailyLimitMinutes * 60_000L;
            if (usedMs < limitMs) {
                return Decision.allowed(rule, usedMs, usedUnlocks);
            }
            return Decision.blocked(rule, usedMs, usedUnlocks,
                    "今日使用额度已用完", "明天 00:00");
        }

        LocalDateTime now = Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
        int minute = now.getHour() * 60 + now.getMinute();
        int day = now.getDayOfWeek().getValue();
        for (Rule.TimeWindow window : rule.windows) {
            if (window.dayOfWeek == day
                    && minute >= window.startMinute
                    && minute < window.endMinute) {
                return Decision.allowed(rule, usedMs, usedUnlocks);
            }
        }
        return Decision.blocked(rule, usedMs, usedUnlocks,
                "当前不在允许使用时段", nextAllowed(rule, now));
    }

    public static boolean startTemporaryUnlock(Context context, Rule rule, long nowMillis) {
        DataStore store = DataStore.get(context);
        int used = store.getTodayTemporaryUnlockCount(rule.packageName, nowMillis);
        if (used >= rule.temporaryUnlocksPerDay || rule.temporaryUnlocksPerDay == 0) {
            return false;
        }
        store.incrementTemporaryUnlockCount(rule, nowMillis);
        TEMPORARY_UNLOCK_UNTIL.put(rule.packageName,
                SystemClock.elapsedRealtime() + rule.temporaryUnlockMinutes * 60_000L);
        return true;
    }

    /** Returns newly due cumulative-usage reminders, once for each interval today. */
    public static List<ReminderAlert> collectDueReminders(
            Context context, Rule rule, long nowMillis) {
        DataStore store = DataStore.get(context);
        long usedMs = store.getTodayUsageMs(rule.packageName, nowMillis);
        long limitMs = rule.dailyLimitMinutes * 60_000L;
        List<ReminderAlert> result = new ArrayList<>();
        for (Rule.Reminder reminder : rule.reminders) {
            long intervalMs = reminder.thresholdMinutes * 60_000L;
            long intervalNumber = usedMs / intervalMs;
            if (intervalNumber <= 0L) {
                continue;
            }
            if (rule.mode == Rule.MODE_DAILY_LIMIT && usedMs >= limitMs) {
                continue;
            }
            String id = reminder.stableId() + ":" + intervalNumber;
            if (store.hasReminderFired(rule.packageName, id, nowMillis)) {
                continue;
            }
            long remainingMs;
            if (rule.mode == Rule.MODE_DAILY_LIMIT) {
                remainingMs = Math.max(0L, limitMs - usedMs);
            } else {
                remainingMs = currentWindowRemainingMs(rule, nowMillis);
                if (remainingMs <= 0L) {
                    continue;
                }
            }
            String message = reminder.customText.trim().isEmpty()
                    ? "请注意使用时间" : reminder.customText.trim();
            String detail = "已使用 " + formatDuration(usedMs)
                    + " · 剩余 " + formatDuration(remainingMs);
            store.markReminderFired(rule.packageName, id, nowMillis);
            result.add(new ReminderAlert(rule.packageName, rule.appLabel, message, detail,
                    reminder.displayMode, reminder.imageUri));
        }
        return result;
    }

    public static long currentWindowRemainingMs(Rule rule, long nowMillis) {
        LocalDateTime now = Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
        int minute = now.getHour() * 60 + now.getMinute();
        for (Rule.TimeWindow window : rule.windows) {
            if (window.dayOfWeek == now.getDayOfWeek().getValue()
                    && minute >= window.startMinute
                    && minute < window.endMinute) {
                long endOfMinuteAdjustment = 60_000L - now.getSecond() * 1_000L
                        - now.getNano() / 1_000_000L;
                return (window.endMinute - minute - 1L) * 60_000L
                        + endOfMinuteAdjustment;
            }
        }
        return -1L;
    }

    public static String formatDuration(long millis) {
        long totalMinutes = Math.max(0L, millis / 60_000L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0L) {
            return minutes == 0L ? hours + "小时"
                    : hours + "小时" + minutes + "分钟";
        }
        return minutes + "分钟";
    }

    private static String nextAllowed(Rule rule, LocalDateTime now) {
        for (int dayOffset = 0; dayOffset <= 7; dayOffset++) {
            LocalDateTime day = now.plusDays(dayOffset).withHour(0).withMinute(0)
                    .withSecond(0).withNano(0);
            int weekday = day.getDayOfWeek().getValue();
            int nowMinute = dayOffset == 0 ? now.getHour() * 60 + now.getMinute() : -1;
            Rule.TimeWindow best = null;
            for (Rule.TimeWindow window : rule.windows) {
                if (window.dayOfWeek == weekday && window.startMinute > nowMinute
                        && (best == null || window.startMinute < best.startMinute)) {
                    best = window;
                }
            }
            if (best != null) {
                LocalDateTime start = day.plusMinutes(best.startMinute);
                if (dayOffset == 0) {
                    return "今天 " + CLOCK.format(start);
                }
                if (dayOffset == 1) {
                    return "明天 " + CLOCK.format(start);
                }
                return weekdayName(start.getDayOfWeek()) + " " + CLOCK.format(start);
            }
        }
        return "尚未设置可用时段";
    }

    private static String weekdayName(DayOfWeek day) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[day.getValue() - 1];
    }

    public static final class Decision {
        public final boolean blocked;
        public final Rule rule;
        public final long usedMs;
        public final int usedTemporaryUnlocks;
        public final String reason;
        public final String nextAllowed;

        private Decision(boolean blocked, Rule rule, long usedMs, int usedTemporaryUnlocks,
                         String reason, String nextAllowed) {
            this.blocked = blocked;
            this.rule = rule;
            this.usedMs = usedMs;
            this.usedTemporaryUnlocks = usedTemporaryUnlocks;
            this.reason = reason;
            this.nextAllowed = nextAllowed;
        }

        static Decision allowed(Rule rule, long usedMs, int usedTemporaryUnlocks) {
            return new Decision(false, rule, usedMs, usedTemporaryUnlocks, "", "");
        }

        static Decision blocked(Rule rule, long usedMs, int usedTemporaryUnlocks,
                                String reason, String nextAllowed) {
            return new Decision(true, rule, usedMs, usedTemporaryUnlocks, reason, nextAllowed);
        }
    }

    public static final class ReminderAlert {
        public final String packageName;
        public final String title;
        public final String message;
        public final String detail;
        public final int displayMode;
        public final String imageUri;

        ReminderAlert(String packageName, String title, String message, String detail,
                      int displayMode, String imageUri) {
            this.packageName = packageName;
            this.title = title;
            this.message = message;
            this.detail = detail;
            this.displayMode = displayMode;
            this.imageUri = imageUri;
        }
    }
}

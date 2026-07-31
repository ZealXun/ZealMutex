package com.zealmutex.app.engine;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.time.Instant;
import java.time.ZoneId;

/** Reads system usage events to seed and repair ZealMutex's local counters. */
public final class UsageTracker {
    private UsageTracker() {
    }

    public static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        if (appOps == null) {
            return false;
        }
        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkCallingOrSelfPermission(
                    "android.permission.PACKAGE_USAGE_STATS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Calculates foreground time since local midnight. A one-day lookback lets
     * an app already open across midnight be clamped correctly to today's start.
     */
    public static long measureTodayForegroundMs(
            Context context, String packageName, long nowMillis) {
        if (!hasUsageAccess(context)) {
            return 0L;
        }
        UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
        if (manager == null) {
            return 0L;
        }
        long startOfDay = Instant.ofEpochMilli(nowMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        UsageEvents events = manager.queryEvents(startOfDay - 86_400_000L, nowMillis);
        if (events == null) {
            return 0L;
        }

        long total = 0L;
        long countableSince = -1L;
        int resumedDepth = 0;
        boolean screenInteractive = true;
        boolean keyguardShown = false;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            boolean packageEvent = packageName.equals(event.getPackageName());
            boolean stateChange = packageEvent && (type == UsageEvents.Event.ACTIVITY_RESUMED
                    || type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || type == UsageEvents.Event.ACTIVITY_PAUSED
                    || type == UsageEvents.Event.ACTIVITY_STOPPED
                    || type == UsageEvents.Event.MOVE_TO_BACKGROUND);
            stateChange |= type == UsageEvents.Event.SCREEN_INTERACTIVE
                    || type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                    || type == UsageEvents.Event.KEYGUARD_SHOWN
                    || type == UsageEvents.Event.KEYGUARD_HIDDEN;
            if (!stateChange) {
                continue;
            }

            long eventTime = Math.max(startOfDay,
                    Math.min(nowMillis, event.getTimeStamp()));
            boolean wasCountable = resumedDepth > 0 && screenInteractive && !keyguardShown;
            if (wasCountable && countableSince < 0L
                    && event.getTimeStamp() >= startOfDay) {
                countableSince = startOfDay;
            }
            if (wasCountable && countableSince >= 0L && event.getTimeStamp() >= startOfDay) {
                total += Math.max(0L, eventTime - countableSince);
                countableSince = -1L;
            }

            if (packageEvent && (type == UsageEvents.Event.ACTIVITY_RESUMED
                    || type == UsageEvents.Event.MOVE_TO_FOREGROUND)) {
                if (resumedDepth == 0) {
                    resumedDepth = 1;
                } else {
                    resumedDepth++;
                }
            } else if (packageEvent && (type == UsageEvents.Event.ACTIVITY_PAUSED
                    || type == UsageEvents.Event.ACTIVITY_STOPPED
                    || type == UsageEvents.Event.MOVE_TO_BACKGROUND)) {
                if (resumedDepth > 0) {
                    resumedDepth--;
                }
            } else if (type == UsageEvents.Event.SCREEN_INTERACTIVE) {
                screenInteractive = true;
            } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                screenInteractive = false;
            } else if (type == UsageEvents.Event.KEYGUARD_SHOWN) {
                keyguardShown = true;
            } else if (type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                keyguardShown = false;
            }

            boolean isCountable = resumedDepth > 0 && screenInteractive && !keyguardShown;
            if (isCountable && event.getTimeStamp() >= startOfDay) {
                countableSince = eventTime;
            }
        }
        if (resumedDepth > 0 && screenInteractive && !keyguardShown) {
            if (countableSince < 0L) {
                countableSince = startOfDay;
            }
            total += Math.max(0L, nowMillis - countableSince);
        }
        return Math.min(nowMillis - startOfDay, Math.max(0L, total));
    }

    public static boolean isThirdPartyLaunchable(Context context, ApplicationInfo info) {
        boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        return !system
                && !context.getPackageName().equals(info.packageName)
                && context.getPackageManager().getLaunchIntentForPackage(info.packageName) != null;
    }
}

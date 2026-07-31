package com.zealmutex.app.service;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.accessibility.AccessibilityManager;

import com.zealmutex.app.data.DataStore;
import com.zealmutex.app.data.Rule;
import com.zealmutex.app.engine.RuleEngine;
import com.zealmutex.app.engine.TrustedTime;
import com.zealmutex.app.engine.UsageTracker;
import com.zealmutex.app.update.UpdateManager;

import java.util.List;

/**
 * Low-frequency reliability service. Immediate enforcement is handled by the
 * accessibility service; this service repairs counters and survives UI closure.
 */
public final class MonitorService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lastRuleCount = -1;
    private boolean lastAccessibilityState;
    private boolean lastUsageAccessState;
    private boolean permissionStateInitialized;

    private final Runnable maintenance = new Runnable() {
        @Override
        public void run() {
            runMaintenance();
            handler.postDelayed(this, 15_000L);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, MonitorService.class);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException ignored) {
            // The next foreground app launch or boot event retries safely.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createChannels(this);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NotificationHelper.ONGOING_ID,
                    NotificationHelper.ongoing(this, "正在初始化限制规则"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NotificationHelper.ONGOING_ID,
                    NotificationHelper.ongoing(this, "正在初始化限制规则"));
        }
        handler.post(maintenance);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        DataStore.get(this).forcePersist();
        super.onDestroy();
    }

    private void runMaintenance() {
        TrustedTime.syncIfStale(this);
        long now = TrustedTime.now(this);
        DataStore store = DataStore.get(this);
        List<Rule> rules = store.getActiveRules(now);
        boolean accessibilityEnabled = isAccessibilityEnabled(this);
        boolean usageAccess = UsageTracker.hasUsageAccess(this);

        if (usageAccess && !accessibilityEnabled) {
            for (Rule rule : rules) {
                long systemUsage = UsageTracker.measureTodayForegroundMs(
                        this, rule.packageName, now);
                store.seedTodayUsage(rule, systemUsage, now);
                for (RuleEngine.ReminderAlert alert
                        : RuleEngine.collectDueReminders(this, rule, now)) {
                    NotificationHelper.showReminder(this, alert);
                }
            }
        }

        if (!permissionStateInitialized
                || rules.size() != lastRuleCount
                || accessibilityEnabled != lastAccessibilityState
                || usageAccess != lastUsageAccessState) {
            lastRuleCount = rules.size();
            lastAccessibilityState = accessibilityEnabled;
            lastUsageAccessState = usageAccess;
            boolean ready = accessibilityEnabled && usageAccess;
            String status = ready
                    ? "已启用 " + rules.size() + " 条应用限制"
                    : "核心权限未完成，限制功能已暂停";
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NotificationHelper.ONGOING_ID,
                        NotificationHelper.ongoing(this, status));
            }
            if (!ready && permissionStateInitialized) {
                String missing = !usageAccess && !accessibilityEnabled
                        ? "使用情况访问和无障碍服务已关闭"
                        : !usageAccess ? "使用情况访问已关闭" : "无障碍服务已关闭";
                NotificationHelper.showPermissionPaused(this, missing);
            } else if (ready) {
                NotificationHelper.clearPermissionPaused(this);
            }
            permissionStateInitialized = true;
        }
        UpdateManager.checkIfDue(this, null);
    }

    public static boolean isAccessibilityEnabled(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null) {
            return false;
        }
        ComponentName expected = new ComponentName(context, MonitorAccessibilityService.class);
        List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            ComponentName actual = new ComponentName(
                    info.getResolveInfo().serviceInfo.packageName,
                    info.getResolveInfo().serviceInfo.name);
            if (expected.equals(actual)) {
                return true;
            }
        }
        return false;
    }
}

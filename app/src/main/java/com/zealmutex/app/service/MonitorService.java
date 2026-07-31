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

import java.util.List;

/**
 * Low-frequency reliability service. Immediate enforcement is handled by the
 * accessibility service; this service repairs counters and survives UI closure.
 */
public final class MonitorService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lastRuleCount = -1;
    private boolean lastAccessibilityState;

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
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

        if (UsageTracker.hasUsageAccess(this) && !accessibilityEnabled) {
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

        if (rules.size() != lastRuleCount || accessibilityEnabled != lastAccessibilityState) {
            lastRuleCount = rules.size();
            lastAccessibilityState = accessibilityEnabled;
            String status = accessibilityEnabled
                    ? "已启用 " + rules.size() + " 条应用限制"
                    : "无障碍服务未开启，当前只能统计不能立即锁定";
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NotificationHelper.ONGOING_ID,
                        NotificationHelper.ongoing(this, status));
            }
        }
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

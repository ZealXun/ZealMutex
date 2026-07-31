package com.zealmutex.app.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.zealmutex.app.R;
import com.zealmutex.app.engine.RuleEngine;
import com.zealmutex.app.ui.MainActivity;

/** Owns the persistent service notification and user-configured reminders. */
public final class NotificationHelper {
    public static final int ONGOING_ID = 1001;
    private static final String SERVICE_CHANNEL = "zealmutex_service";
    private static final String REMINDER_CHANNEL = "zealmutex_reminders";

    private NotificationHelper() {
    }

    public static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel service = new NotificationChannel(
                SERVICE_CHANNEL, "限制服务", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("保持应用限制与每日重置可靠运行");
        service.setShowBadge(false);
        manager.createNotificationChannel(service);

        NotificationChannel reminders = new NotificationChannel(
                REMINDER_CHANNEL, "使用提醒", NotificationManager.IMPORTANCE_HIGH);
        reminders.setDescription("按累计使用时间循环显示的自定义提醒");
        manager.createNotificationChannel(reminders);
    }

    public static Notification ongoing(Context context, String status) {
        createChannels(context);
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, SERVICE_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("ZealMutex 正在运行")
                .setContentText(status)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    public static void showReminder(Context context, RuleEngine.ReminderAlert alert) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        createChannels(context);
        Notification notification = new Notification.Builder(context, REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(alert.title)
                .setContentText(alert.message)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(alert.message + "\n" + alert.detail))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify((alert.title + alert.message + alert.detail).hashCode(), notification);
        }
    }
}

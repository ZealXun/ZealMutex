package com.zealmutex.app.engine;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supplies network-calibrated wall time while the current boot remains active.
 * If calibration is unavailable (especially after reboot), local device time is
 * returned as requested by the product rules.
 */
public final class TrustedTime {
    private static final String PREFS = "trusted_time";
    private static final long MAX_SYNC_AGE_MS = 6L * 60L * 60L * 1000L;
    private static final String[] SOURCES = {
            "https://www.baidu.com/",
            "https://www.qq.com/",
            "https://www.cloudflare.com/"
    };

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean SYNCING = new AtomicBoolean(false);
    private static volatile boolean initialized;
    private static volatile long baseEpochMillis;
    private static volatile long baseElapsedMillis;
    private static volatile int syncedBootCount = -1;
    private static volatile long lastSyncedEpochMillis;
    private static volatile String lastSource = "";

    private TrustedTime() {
    }

    public static long now(Context context) {
        initialize(context);
        int bootCount = bootCount(context);
        long elapsed = SystemClock.elapsedRealtime();
        if (baseEpochMillis > 0L
                && syncedBootCount == bootCount
                && elapsed >= baseElapsedMillis) {
            return baseEpochMillis + elapsed - baseElapsedMillis;
        }
        return System.currentTimeMillis();
    }

    public static void syncIfStale(Context context) {
        initialize(context);
        long elapsed = SystemClock.elapsedRealtime();
        boolean validThisBoot = baseEpochMillis > 0L
                && syncedBootCount == bootCount(context)
                && elapsed >= baseElapsedMillis;
        if (validThisBoot && elapsed - baseElapsedMillis < MAX_SYNC_AGE_MS) {
            return;
        }
        if (!SYNCING.compareAndSet(false, true)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                for (String source : SOURCES) {
                    if (syncFrom(appContext, source)) {
                        return;
                    }
                }
            } finally {
                SYNCING.set(false);
            }
        });
    }

    public static Status status(Context context) {
        initialize(context);
        boolean calibrated = baseEpochMillis > 0L
                && syncedBootCount == bootCount(context)
                && SystemClock.elapsedRealtime() >= baseElapsedMillis;
        return new Status(calibrated, lastSyncedEpochMillis, lastSource);
    }

    private static boolean syncFrom(Context context, String source) {
        HttpURLConnection connection = null;
        try {
            long before = SystemClock.elapsedRealtime();
            connection = (HttpURLConnection) new URL(source).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(3_000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "ZealMutex/1.0 time-sync");
            connection.connect();
            long serverEpoch = connection.getDate();
            long after = SystemClock.elapsedRealtime();
            if (serverEpoch <= 0L) {
                return false;
            }
            long midpointElapsed = before + (after - before) / 2L;
            baseEpochMillis = serverEpoch;
            baseElapsedMillis = midpointElapsed;
            syncedBootCount = bootCount(context);
            lastSyncedEpochMillis = serverEpoch;
            lastSource = new URL(source).getHost();
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("baseEpoch", baseEpochMillis)
                    .putLong("baseElapsed", baseElapsedMillis)
                    .putInt("bootCount", syncedBootCount)
                    .putLong("lastSyncedEpoch", lastSyncedEpochMillis)
                    .putString("lastSource", lastSource)
                    .apply();
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static synchronized void initialize(Context context) {
        if (initialized) {
            return;
        }
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        baseEpochMillis = preferences.getLong("baseEpoch", 0L);
        baseElapsedMillis = preferences.getLong("baseElapsed", 0L);
        syncedBootCount = preferences.getInt("bootCount", -1);
        lastSyncedEpochMillis = preferences.getLong("lastSyncedEpoch", 0L);
        lastSource = preferences.getString("lastSource", "");
        initialized = true;
    }

    private static int bootCount(Context context) {
        return Settings.Global.getInt(
                context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    public static final class Status {
        public final boolean networkCalibrated;
        public final long lastSyncedEpochMillis;
        public final String source;

        private Status(boolean networkCalibrated, long lastSyncedEpochMillis, String source) {
            this.networkCalibrated = networkCalibrated;
            this.lastSyncedEpochMillis = lastSyncedEpochMillis;
            this.source = source;
        }
    }
}

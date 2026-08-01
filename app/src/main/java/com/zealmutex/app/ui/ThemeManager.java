package com.zealmutex.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.WindowInsetsController;

import com.zealmutex.app.R;

/** Stores the user-selected appearance and applies it only to regular activities. */
public final class ThemeManager {
    public static final int SYSTEM = 0;
    public static final int DARK = 1;
    public static final int LIGHT = 2;
    public static final int FOCUS_BLUE = 3;
    public static final int WARM = 4;

    private static final String PREFS = "appearance";
    private static final String KEY_THEME = "theme";
    private static final String[] LABELS = {
            "跟随系统", "深色", "浅色", "深蓝专注", "暖米护眼"
    };

    private ThemeManager() {
    }

    public static int selected(Context context) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_THEME, SYSTEM);
        return value >= SYSTEM && value <= WARM ? value : SYSTEM;
    }

    public static void select(Context context, int theme) {
        int safeTheme = theme >= SYSTEM && theme <= WARM ? theme : SYSTEM;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_THEME, safeTheme).apply();
    }

    public static int resolved(Context context) {
        int selected = selected(context);
        if (selected != SYSTEM) {
            return selected;
        }
        int nightMode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES ? DARK : LIGHT;
    }

    public static boolean isLight(Context context) {
        int theme = resolved(context);
        return theme == LIGHT || theme == WARM;
    }

    public static String selectedLabel(Context context) {
        return LABELS[selected(context)];
    }

    public static String[] labels() {
        return LABELS.clone();
    }

    /** Must be called before Activity.onCreate so dialogs and controls use the right base. */
    public static void applyBeforeCreate(Activity activity) {
        activity.setTheme(isLight(activity)
                ? R.style.Theme_ZealMutex_Light : R.style.Theme_ZealMutex);
    }

    /** Keeps status/navigation bars readable for every palette. */
    public static void applySystemBars(Activity activity) {
        activity.getWindow().setStatusBarColor(Ui.background(activity));
        activity.getWindow().setNavigationBarColor(Ui.surface(activity));
        boolean light = isLight(activity);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else {
            int flags = light
                    ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
            activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }
}

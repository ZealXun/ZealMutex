package com.zealmutex.app.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Opens the vendor-specific background or auto-start screen when available. */
public final class BackgroundSettings {
    private BackgroundSettings() {
    }

    public static String vendorName() {
        String brand = (Build.MANUFACTURER + " " + Build.BRAND)
                .toLowerCase(Locale.ROOT);
        if (brand.contains("xiaomi") || brand.contains("redmi")) return "小米/Redmi";
        if (brand.contains("huawei")) return "华为";
        if (brand.contains("honor")) return "荣耀";
        if (brand.contains("oppo")) return "OPPO";
        if (brand.contains("realme")) return "realme";
        if (brand.contains("oneplus")) return "一加";
        if (brand.contains("vivo")) return "vivo";
        if (brand.contains("iqoo")) return "iQOO";
        if (brand.contains("samsung")) return "三星";
        return Build.MANUFACTURER == null || Build.MANUFACTURER.trim().isEmpty()
                ? "当前设备" : Build.MANUFACTURER;
    }

    public static boolean open(Context context) {
        String brand = (Build.MANUFACTURER + " " + Build.BRAND)
                .toLowerCase(Locale.ROOT);
        List<Intent> candidates = new ArrayList<>();
        if (brand.contains("xiaomi") || brand.contains("redmi")) {
            candidates.add(component("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            candidates.add(new Intent("miui.intent.action.OP_AUTO_START"));
        } else if (brand.contains("huawei") || brand.contains("honor")) {
            candidates.add(component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
        } else if (brand.contains("oppo") || brand.contains("realme")
                || brand.contains("oneplus")) {
            candidates.add(component("com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"));
            candidates.add(component("com.oplus.safecenter",
                    "com.oplus.safecenter.startupapp.StartupAppListActivity"));
        } else if (brand.contains("vivo") || brand.contains("iqoo")) {
            candidates.add(component("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
        }
        for (Intent intent : candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(context.getPackageManager()) != null) {
                try {
                    context.startActivity(intent);
                    return true;
                } catch (RuntimeException ignored) {
                    // Try the next known entry point.
                }
            }
        }
        try {
            context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName())));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static Intent component(String packageName, String className) {
        return new Intent().setComponent(new ComponentName(packageName, className));
    }
}

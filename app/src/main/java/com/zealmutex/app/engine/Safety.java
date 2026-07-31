package com.zealmutex.app.engine;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.provider.Telephony;
import android.telecom.TelecomManager;

/** Central safety allowlist that prevents accidental device lockout. */
public final class Safety {
    private Safety() {
    }

    public static boolean isAlwaysAllowed(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return true;
        }
        if (packageName.equals(context.getPackageName())
                || packageName.equals("android")
                || packageName.equals("com.android.systemui")
                || packageName.equals("com.android.settings")
                || packageName.contains("permissioncontroller")
                || packageName.contains("packageinstaller")
                || packageName.equals("com.miui.securitycenter")
                || packageName.equals("com.huawei.systemmanager")
                || packageName.equals("com.vivo.permissionmanager")
                || packageName.contains("safecenter")) {
            return true;
        }

        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo home = context.getPackageManager().resolveActivity(homeIntent, 0);
        if (home != null && home.activityInfo != null
                && packageName.equals(home.activityInfo.packageName)) {
            return true;
        }
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        if (telecom != null && packageName.equals(telecom.getDefaultDialerPackage())) {
            return true;
        }
        return packageName.equals(Telephony.Sms.getDefaultSmsPackage(context));
    }
}

package com.zealmutex.app.ui;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Local version information, release notes and behavior summary. */
public final class AboutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this, 20);
        root.setBackgroundColor(Ui.BLACK);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Ui.text(this, "ZEALMUTEX", 12f, Ui.MUTED));
        root.addView(Ui.title(this, "关于与更新", 30f), Ui.matchWrap(this, 8));

        addCard(root, "当前版本", installedVersion()
                + "\n支持 Android 10（API 29）及以上系统");
        addCard(root, "更新日志",
                "1.0.3 · 2026-07-31\n"
                        + "• 提醒、图片和锁定页文字改为立即生效\n"
                        + "• 使用限制、时段和临时解锁仍在第二天生效\n"
                        + "• 新增关于与更新页面，移除启动身份验证\n\n"
                        + "1.0.2 · 2026-07-31\n"
                        + "• 兼容小米预装普通应用列表\n"
                        + "• 新增循环提醒、整页图片模式、删除与撤销删除\n"
                        + "• 自动识别手机厂商后台管理入口\n\n"
                        + "1.0.1 · 2026-07-30\n"
                        + "• 将厂商后台管理从应用权限中独立出来\n\n"
                        + "1.0.0 · 2026-07-30\n"
                        + "• 首个可安装版本");
        addCard(root, "设置生效时间",
                "立即生效：循环提醒间隔、提醒文案、弹窗或整页模式、"
                        + "自定义图片、锁定页附加文字。\n\n"
                        + "第二天 00:00 生效：每日使用额度、允许使用时段、"
                        + "临时解锁次数、每次解锁时长、删除规则。\n\n"
                        + "第一次创建规则时，所有设置当天立即生效。");
        addCard(root, "隐私与兼容",
                "规则、统计和周报只保存在本机。联网仅用于校准时间，"
                        + "不会上传使用记录。自定义图片由系统文件选择器授权，"
                        + "ZealMutex 只读取用户选中的图片。\n\n"
                        + "厂商后台管理没有统一的状态查询接口。ZealMutex 会识别品牌并尝试打开设置入口，"
                        + "是否稳定后台运行仍需在实际手机上验证。");
    }

    private void addCard(LinearLayout root, String title, String body) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, title, 19f));
        card.addView(Ui.text(this, body, 14f, Ui.MUTED), Ui.matchWrap(this, 8));
        root.addView(card, Ui.matchWrap(this, 16));
    }

    private String installedVersion() {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.PackageInfoFlags.of(0));
            } else {
                info = getPackageManager().getPackageInfo(getPackageName(), 0);
            }
            return "ZealMutex " + info.versionName + " · 构建 " + info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException impossible) {
            return "ZealMutex";
        }
    }
}

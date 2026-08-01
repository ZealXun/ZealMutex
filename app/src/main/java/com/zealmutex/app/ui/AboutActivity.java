package com.zealmutex.app.ui;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zealmutex.app.update.UpdateManager;

import java.io.File;

/** Local version information, release notes and behavior summary. */
public final class AboutActivity extends Activity {
    private LinearLayout root;
    private TextView downloadStatus;
    private Button downloadButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyBeforeCreate(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applySystemBars(this);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!UpdateManager.isDownloading()) {
            build();
        }
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        root = Ui.column(this, 20);
        root.setBackgroundColor(Ui.background(this));
        scroll.addView(root);
        Ui.applyStatusBarInset(scroll);
        setContentView(scroll);

        root.addView(Ui.text(this, "ZEALMUTEX", 12f, Ui.MUTED));
        root.addView(Ui.title(this, "关于与更新", 30f), Ui.matchWrap(this, 8));

        addUpdateCard();
        addCard("当前版本", installedVersion()
                + "\n支持 Android 10（API 29）及以上系统");
        addCard("更新日志",
                "1.2.1 · 2026-08-01\n"
                        + "• 修复部分 Android 16 设备启动闪退\n"
                        + "• 延迟系统栏外观设置，等待页面准备完成\n\n"
                        + "1.2.0 · 2026-08-01\n"
                        + "• 每条规则可选择生效星期\n"
                        + "• 新增多个应用共用限制的应用组\n"
                        + "• 新增五种主题和一体式图标导航\n\n"
                        + "1.1.0 · 2026-07-31\n"
                        + "• 重构主页与设置双标签，新增剩余额度进度\n"
                        + "• 优化锁屏、息屏、跨进程和跨午夜计时\n"
                        + "• 新增 GitHub 自动检查、校验下载和系统安装\n\n"
                        + "1.0.3 · 2026-07-31\n"
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
        addCard("设置生效时间",
                "立即生效：循环提醒间隔、提醒文案、弹窗或整页模式、"
                        + "自定义图片、锁定页附加文字。\n\n"
                        + "第二天 00:00 生效：每日使用额度、允许使用时段、"
                        + "临时解锁次数、每次解锁时长、删除规则。\n\n"
                        + "第一次创建规则时，所有设置当天立即生效。");
        addCard("隐私与兼容",
                "规则、统计和周报只保存在本机。联网仅用于校准时间，"
                        + "不会上传使用记录。自定义图片由系统文件选择器授权，"
                        + "ZealMutex 只读取用户选中的图片。\n\n"
                        + "厂商后台管理没有统一的状态查询接口。ZealMutex 会识别品牌并尝试打开设置入口，"
                        + "是否稳定后台运行仍需在实际手机上验证。");
    }

    private void addUpdateCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.title(this, "GitHub 更新", 19f));
        UpdateManager.UpdateInfo info = UpdateManager.availableUpdate(this);
        boolean ignored = info != null && !UpdateManager.hasUpdate(this);
        if (info == null) {
            card.addView(Ui.text(this, UpdateManager.isChecking()
                    ? "正在检查更新…" : "当前没有可用的新版本",
                    14f, Ui.MUTED), Ui.matchWrap(this, 8));
        } else {
            String state = ignored ? "已忽略 " + info.tag : "发现新版本 " + info.tag;
            card.addView(Ui.text(this, state, 15f,
                    ignored ? Ui.MUTED : Ui.BLUE), Ui.matchWrap(this, 8));
            if (!info.body.trim().isEmpty()) {
                card.addView(Ui.text(this, info.body.trim(), 13f, Ui.MUTED),
                        Ui.matchWrap(this, 8));
            }
            if (!ignored) {
                downloadStatus = Ui.text(this,
                        UpdateManager.hasDownloaded(this, info)
                                ? "APK 已校验，可以安装" : "下载后将校验 SHA-256",
                        13f, Ui.MUTED);
                card.addView(downloadStatus, Ui.matchWrap(this, 10));
                downloadButton = Ui.primaryButton(this,
                        UpdateManager.hasDownloaded(this, info)
                                ? "打开系统安装界面" : "下载并安装");
                downloadButton.setEnabled(!UpdateManager.isDownloading());
                downloadButton.setOnClickListener(view -> {
                    if (UpdateManager.hasDownloaded(this, info)) {
                        UpdateManager.install(this, info);
                    } else {
                        beginDownload(info);
                    }
                });
                card.addView(downloadButton, Ui.matchWrap(this, 10));

                Button ignore = Ui.secondaryButton(this, "忽略此版本");
                ignore.setOnClickListener(view -> {
                    UpdateManager.ignoreAvailableVersion(this);
                    build();
                });
                card.addView(ignore, Ui.matchWrap(this, 8));
            }
        }
        Button check = Ui.secondaryButton(this, "立即检查更新");
        check.setEnabled(!UpdateManager.isChecking());
        check.setOnClickListener(view -> {
            check.setEnabled(false);
            check.setText("正在检查…");
            UpdateManager.checkNow(this, (success, message) -> {
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    build();
                }
            });
        });
        card.addView(check, Ui.matchWrap(this, 10));
        root.addView(card, Ui.matchWrap(this, 20));
    }

    private void beginDownload(UpdateManager.UpdateInfo info) {
        downloadButton.setEnabled(false);
        downloadButton.setText("正在下载…");
        UpdateManager.download(this, info, new UpdateManager.DownloadCallback() {
            @Override
            public void onProgress(int percent, long downloadedBytes, long totalBytes) {
                if (downloadStatus == null) {
                    return;
                }
                if (percent >= 0) {
                    downloadStatus.setText("正在下载 " + percent + "%");
                } else {
                    downloadStatus.setText("正在下载 "
                            + downloadedBytes / 1024L + " KB");
                }
            }

            @Override
            public void onComplete(File apk) {
                if (!isFinishing() && !isDestroyed()) {
                    downloadStatus.setText("SHA-256 校验通过");
                    downloadButton.setText("打开系统安装界面");
                    downloadButton.setEnabled(true);
                    downloadButton.setOnClickListener(view ->
                            UpdateManager.install(AboutActivity.this, info));
                    UpdateManager.install(AboutActivity.this, info);
                }
            }

            @Override
            public void onError(String message) {
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(AboutActivity.this, message,
                            Toast.LENGTH_LONG).show();
                    build();
                }
            }
        });
    }

    private void addCard(String title, String body) {
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

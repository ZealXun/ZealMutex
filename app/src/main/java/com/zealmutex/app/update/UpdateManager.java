package com.zealmutex.app.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub Releases update discovery, verified download and installer handoff. */
public final class UpdateManager {
    private static final String PREFS = "github_updates";
    private static final String RELEASES_API =
            "https://api.github.com/repos/ZealXun/ZealMutex/releases?per_page=20";
    private static final String API_VERSION = "2026-03-10";
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("(?i)\\b[0-9a-f]{64}\\b");

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);

    private UpdateManager() {
    }

    public static void checkIfDue(Context context, Runnable callback) {
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = preferences(appContext);
        long now = System.currentTimeMillis();
        if (now - preferences.getLong("lastAttempt", 0L) < CHECK_INTERVAL_MS) {
            post(callback);
            return;
        }
        check(appContext, false, (success, message) -> post(callback));
    }

    public static void checkNow(Context context, CheckCallback callback) {
        check(context.getApplicationContext(), true, callback);
    }

    public static boolean hasUpdate(Context context) {
        UpdateInfo info = availableUpdate(context);
        return info != null && !info.tag.equals(
                preferences(context).getString("ignoredTag", ""));
    }

    public static UpdateInfo availableUpdate(Context context) {
        SharedPreferences preferences = preferences(context);
        String tag = preferences.getString("tag", "");
        String apkUrl = preferences.getString("apkUrl", "");
        String checksumUrl = preferences.getString("checksumUrl", "");
        if (tag.isEmpty() || apkUrl.isEmpty() || checksumUrl.isEmpty()
                || VersionComparator.compare(tag, installedVersion(context)) <= 0) {
            return null;
        }
        return new UpdateInfo(
                tag,
                preferences.getString("title", tag),
                preferences.getString("body", ""),
                preferences.getString("htmlUrl", ""),
                apkUrl,
                checksumUrl,
                preferences.getString("apkName", "ZealMutex-update.apk"));
    }

    public static void ignoreAvailableVersion(Context context) {
        UpdateInfo info = availableUpdate(context);
        if (info != null) {
            preferences(context).edit().putString("ignoredTag", info.tag).apply();
        }
    }

    public static boolean isChecking() {
        return CHECKING.get();
    }

    public static boolean isDownloading() {
        return DOWNLOADING.get();
    }

    public static boolean hasDownloaded(Context context, UpdateInfo info) {
        return info != null
                && info.tag.equals(preferences(context).getString("downloadedTag", ""))
                && updateApk(context).isFile();
    }

    public static void download(Context context, UpdateInfo info, DownloadCallback callback) {
        if (info == null) {
            post(() -> callback.onError("没有可下载的新版本"));
            return;
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            post(() -> callback.onError("更新正在下载"));
            return;
        }
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            File partial = partialApk(appContext);
            try {
                File directory = partial.getParentFile();
                if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
                    throw new IllegalStateException("无法创建更新目录");
                }
                String checksumText = readText(info.checksumUrl, MAX_TEXT_BYTES);
                Matcher matcher = SHA256.matcher(checksumText);
                if (!matcher.find()) {
                    throw new IllegalStateException("Release 缺少有效 SHA-256");
                }
                String expected = matcher.group().toLowerCase(Locale.ROOT);
                String actual = downloadApk(info.apkUrl, partial, callback);
                if (!expected.equals(actual)) {
                    Files.deleteIfExists(partial.toPath());
                    throw new IllegalStateException("APK 校验失败，请重新下载");
                }
                validatePackage(appContext, partial);
                File target = updateApk(appContext);
                Files.move(partial.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                preferences(appContext).edit()
                        .putString("downloadedTag", info.tag)
                        .apply();
                post(() -> callback.onComplete(target));
            } catch (Exception failure) {
                try {
                    Files.deleteIfExists(partial.toPath());
                } catch (Exception ignored) {
                }
                String message = failure.getMessage();
                post(() -> callback.onError(message == null
                        ? "更新下载失败" : message));
            } finally {
                DOWNLOADING.set(false);
            }
        });
    }

    public static void install(Activity activity, UpdateInfo info) {
        if (!hasDownloaded(activity, info)) {
            return;
        }
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(activity.getPackageName() + ".updates")
                .path("update.apk")
                .build();
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, false);
        activity.startActivity(install);
    }

    private static void check(Context context, boolean forced, CheckCallback callback) {
        if (!CHECKING.compareAndSet(false, true)) {
            notifyCheck(callback, false, "正在检查更新");
            return;
        }
        preferences(context).edit()
                .putLong("lastAttempt", System.currentTimeMillis())
                .apply();
        EXECUTOR.execute(() -> {
            try {
                ReleaseCandidate candidate = findCandidate(
                        context, readText(RELEASES_API, MAX_TEXT_BYTES));
                if (candidate == null
                        || VersionComparator.compare(
                        candidate.tag, installedVersion(context)) <= 0) {
                    clearAvailable(context);
                    notifyCheck(callback, true, "当前已是最新版本");
                } else {
                    saveCandidate(context, candidate);
                    notifyCheck(callback, true, "发现新版本 " + candidate.tag);
                }
            } catch (Exception failure) {
                String message = forced && failure.getMessage() != null
                        ? failure.getMessage() : "暂时无法检查更新";
                notifyCheck(callback, false, message);
            } finally {
                CHECKING.set(false);
            }
        });
    }

    private static ReleaseCandidate findCandidate(Context context, String json)
            throws Exception {
        JSONArray releases = new JSONArray(json);
        boolean development = isDevelopment(context);
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft", false)
                    || release.optBoolean("prerelease", false) != development) {
                continue;
            }
            String tag = release.optString("tag_name", "");
            if (tag.isEmpty()) {
                continue;
            }
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) {
                continue;
            }
            String apkName = "";
            String apkUrl = "";
            String checksumUrl = "";
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset == null) {
                    continue;
                }
                String name = asset.optString("name", "");
                String lower = name.toLowerCase(Locale.ROOT);
                boolean developmentAsset = lower.contains("dev");
                if (lower.endsWith(".apk") && developmentAsset == development) {
                    apkName = name;
                    apkUrl = asset.optString("browser_download_url", "");
                }
            }
            if (apkName.isEmpty() || apkUrl.isEmpty()) {
                continue;
            }
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                String name = asset == null ? "" : asset.optString("name", "");
                if (name.equals(apkName + ".sha256")
                        || (name.endsWith(".sha256") && name.contains(apkName))) {
                    checksumUrl = asset.optString("browser_download_url", "");
                    break;
                }
            }
            if (checksumUrl.isEmpty()) {
                continue;
            }
            return new ReleaseCandidate(
                    tag,
                    release.optString("name", tag),
                    release.optString("body", ""),
                    release.optString("html_url", ""),
                    apkName,
                    apkUrl,
                    checksumUrl);
        }
        return null;
    }

    private static String downloadApk(
            String source, File target, DownloadCallback callback) throws Exception {
        HttpURLConnection connection = open(source);
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                throw new IllegalStateException("APK 下载失败（" + response + "）");
            }
            long total = connection.getContentLengthLong();
            long downloaded = 0L;
            int lastPercent = -1;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(target, false)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    downloaded += count;
                    int percent = total > 0L
                            ? (int) Math.min(100L, downloaded * 100L / total) : -1;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        int progress = percent;
                        long bytes = downloaded;
                        post(() -> callback.onProgress(progress, bytes, total));
                    }
                }
                output.getFD().sync();
            }
            return hex(digest.digest());
        } finally {
            connection.disconnect();
        }
    }

    private static void validatePackage(Context context, File apk) throws Exception {
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= 33) {
            info = context.getPackageManager().getPackageArchiveInfo(
                    apk.getAbsolutePath(),
                    PackageManager.PackageInfoFlags.of(
                            PackageManager.GET_SIGNING_CERTIFICATES));
        } else {
            info = context.getPackageManager().getPackageArchiveInfo(
                    apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        }
        if (info == null || !context.getPackageName().equals(info.packageName)) {
            throw new IllegalStateException("APK 包名与当前版本不一致");
        }
    }

    private static String readText(String source, int maxBytes) throws Exception {
        HttpURLConnection connection = open(source);
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                throw new IllegalStateException("网络请求失败（" + response + "）");
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8 * 1024];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) {
                        throw new IllegalStateException("服务器响应过大");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String source) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION);
        connection.setRequestProperty("User-Agent", "ZealMutex Android updater");
        return connection;
    }

    private static String installedVersion(Context context) {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                info = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), 0);
            }
            return info.versionName == null ? "0" : info.versionName;
        } catch (PackageManager.NameNotFoundException impossible) {
            return "0";
        }
    }

    private static void saveCandidate(Context context, ReleaseCandidate candidate) {
        preferences(context).edit()
                .putString("tag", candidate.tag)
                .putString("title", candidate.title)
                .putString("body", candidate.body)
                .putString("htmlUrl", candidate.htmlUrl)
                .putString("apkName", candidate.apkName)
                .putString("apkUrl", candidate.apkUrl)
                .putString("checksumUrl", candidate.checksumUrl)
                .apply();
    }

    private static void clearAvailable(Context context) {
        preferences(context).edit()
                .remove("tag")
                .remove("title")
                .remove("body")
                .remove("htmlUrl")
                .remove("apkName")
                .remove("apkUrl")
                .remove("checksumUrl")
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean isDevelopment(Context context) {
        return context.getPackageName().endsWith(".dev");
    }

    private static File partialApk(Context context) {
        return new File(new File(context.getFilesDir(), "updates"), "update.part");
    }

    static File updateApk(Context context) {
        return new File(new File(context.getFilesDir(), "updates"), "update.apk");
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void post(Runnable runnable) {
        if (runnable != null) {
            MAIN.post(runnable);
        }
    }

    private static void notifyCheck(
            CheckCallback callback, boolean success, String message) {
        if (callback != null) {
            post(() -> callback.onComplete(success, message));
        }
    }

    public interface CheckCallback {
        void onComplete(boolean success, String message);
    }

    public interface DownloadCallback {
        void onProgress(int percent, long downloadedBytes, long totalBytes);

        void onComplete(File apk);

        void onError(String message);
    }

    public static final class UpdateInfo {
        public final String tag;
        public final String title;
        public final String body;
        public final String htmlUrl;
        public final String apkUrl;
        public final String checksumUrl;
        public final String apkName;

        private UpdateInfo(String tag, String title, String body, String htmlUrl,
                           String apkUrl, String checksumUrl, String apkName) {
            this.tag = tag;
            this.title = title;
            this.body = body;
            this.htmlUrl = htmlUrl;
            this.apkUrl = apkUrl;
            this.checksumUrl = checksumUrl;
            this.apkName = apkName;
        }
    }

    private static final class ReleaseCandidate {
        final String tag;
        final String title;
        final String body;
        final String htmlUrl;
        final String apkName;
        final String apkUrl;
        final String checksumUrl;

        ReleaseCandidate(String tag, String title, String body, String htmlUrl,
                         String apkName, String apkUrl, String checksumUrl) {
            this.tag = tag;
            this.title = title;
            this.body = body;
            this.htmlUrl = htmlUrl;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
            this.checksumUrl = checksumUrl;
        }
    }

}

package com.zealmutex.app.service;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zealmutex.app.data.DataStore;
import com.zealmutex.app.data.Rule;
import com.zealmutex.app.engine.RuleEngine;
import com.zealmutex.app.engine.Safety;
import com.zealmutex.app.engine.TrustedTime;
import com.zealmutex.app.engine.UsageTracker;
import com.zealmutex.app.ui.Ui;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * High-frequency enforcement loop. It counts all visible restricted windows,
 * including split-screen and picture-in-picture windows exposed by Android.
 */
public final class MonitorAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> previouslyVisible = new HashSet<>();
    private WindowManager windowManager;
    private View lockView;
    private View bannerView;
    private View fullPageView;
    private TextView lockTitle;
    private TextView lockReason;
    private TextView lockStats;
    private TextView lockExtra;
    private Button temporaryButton;
    private RuleEngine.Decision displayedDecision;
    private long lastTickElapsed;
    private boolean previousIntervalCountable = true;
    private String lastEventPackage = "";
    private long lastEventElapsed;
    private Runnable bannerRemoval;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = getSystemService(WindowManager.class);
        MonitorService.start(this);
        long now = TrustedTime.now(this);
        for (Rule rule : DataStore.get(this).getActiveRules(now)) {
            DataStore.get(this).seedTodayUsage(rule,
                    UsageTracker.measureTodayForegroundMs(this, rule.packageName, now), now);
        }
        lastTickElapsed = SystemClock.elapsedRealtime();
        handler.post(ticker);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            lastEventPackage = event.getPackageName().toString();
            lastEventElapsed = SystemClock.elapsedRealtime();
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    public void onInterrupt() {
        // No spoken or haptic feedback to interrupt.
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        removeLockView();
        removeBanner();
        removeFullPage();
        DataStore.get(this).forcePersist();
        super.onDestroy();
    }

    private void tick() {
        long elapsed = SystemClock.elapsedRealtime();
        long delta = Math.min(2_500L, Math.max(0L, elapsed - lastTickElapsed));
        lastTickElapsed = elapsed;
        long now = TrustedTime.now(this);

        List<Rule> rules = DataStore.get(this).getActiveRules(now);
        Map<String, Rule> byPackage = new HashMap<>();
        for (Rule rule : rules) {
            byPackage.put(rule.packageName, rule);
        }

        if (previousIntervalCountable) {
            for (String packageName : previouslyVisible) {
                Rule rule = byPackage.get(packageName);
                if (rule != null && !Safety.isAlwaysAllowed(this, packageName)) {
                    DataStore.get(this).addUsage(rule, delta, now);
                }
            }
        }

        Set<String> visible = visiblePackages();
        previouslyVisible.clear();
        for (String packageName : visible) {
            if (byPackage.containsKey(packageName)
                    && !Safety.isAlwaysAllowed(this, packageName)) {
                previouslyVisible.add(packageName);
            }
        }

        if (fullPageView != null) {
            previousIntervalCountable = false;
            removeLockView();
            return;
        }

        RuleEngine.Decision blocked = null;
        if (previouslyVisible.contains(lastEventPackage)) {
            Rule current = byPackage.get(lastEventPackage);
            blocked = current == null ? null : RuleEngine.evaluate(this, current, now);
            if (blocked != null && !blocked.blocked) {
                blocked = null;
            }
        }

        for (String packageName : previouslyVisible) {
            Rule rule = byPackage.get(packageName);
            for (RuleEngine.ReminderAlert alert
                    : RuleEngine.collectDueReminders(this, rule, now)) {
                if (alert.displayMode == Rule.Reminder.DISPLAY_FULL_PAGE) {
                    showFullPage(alert);
                } else {
                    showBanner(alert);
                }
            }
            if (fullPageView != null) {
                previousIntervalCountable = false;
                removeLockView();
                return;
            }
            if (blocked == null) {
                RuleEngine.Decision decision = RuleEngine.evaluate(this, rule, now);
                if (decision.blocked) {
                    blocked = decision;
                }
            }
        }

        if (blocked == null) {
            previousIntervalCountable = true;
            removeLockView();
        } else {
            previousIntervalCountable = false;
            showOrUpdateLock(blocked);
        }
    }

    private Set<String> visiblePackages() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (RuntimeException unavailable) {
            windows = Collections.emptyList();
        }
        Set<String> packages = new HashSet<>();
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) {
                continue;
            }
            CharSequence packageName = root.getPackageName();
            if (packageName != null) {
                packages.add(packageName.toString());
            }
            root.recycle();
        }
        if (!lastEventPackage.isEmpty()
                && SystemClock.elapsedRealtime() - lastEventElapsed < 1_500L) {
            packages.add(lastEventPackage);
        }
        packages.remove(getPackageName());
        return packages;
    }

    private void showOrUpdateLock(RuleEngine.Decision decision) {
        displayedDecision = decision;
        if (lockView == null) {
            lockView = buildLockView();
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            try {
                windowManager.addView(lockView, params);
            } catch (RuntimeException failed) {
                lockView = null;
                return;
            }
        }
        lockTitle.setText(decision.rule.appLabel + " 已锁定");
        lockReason.setText(decision.reason);
        long limitMs = decision.rule.dailyLimitMinutes * 60_000L;
        String usage = "今日已使用 " + RuleEngine.formatDuration(decision.usedMs);
        if (decision.rule.mode == Rule.MODE_DAILY_LIMIT) {
            usage += " / " + RuleEngine.formatDuration(limitMs);
        }
        int left = Math.max(0, decision.rule.temporaryUnlocksPerDay
                - decision.usedTemporaryUnlocks);
        lockStats.setText(usage + "\n下次可用：" + decision.nextAllowed
                + "\n今日剩余临时解锁：" + left + " 次");
        lockExtra.setText(decision.rule.extraLockMessage);
        lockExtra.setVisibility(decision.rule.extraLockMessage.trim().isEmpty()
                ? View.GONE : View.VISIBLE);
        temporaryButton.setVisibility(left > 0 ? View.VISIBLE : View.GONE);
        temporaryButton.setText("临时使用 " + decision.rule.temporaryUnlockMinutes + " 分钟");
    }

    private View buildLockView() {
        LinearLayout root = Ui.column(this, 28);
        root.setBackgroundColor(Ui.BLACK);
        root.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = Ui.text(this, "ZEALMUTEX", 12f, Ui.MUTED);
        brand.setLetterSpacing(0.18f);
        root.addView(brand);

        lockTitle = Ui.title(this, "应用已锁定", 34f);
        root.addView(lockTitle, Ui.matchWrap(this, 18));
        lockReason = Ui.text(this, "", 18f, Ui.WHITE);
        root.addView(lockReason, Ui.matchWrap(this, 8));
        lockStats = Ui.text(this, "", 15f, Ui.MUTED);
        root.addView(lockStats, Ui.matchWrap(this, 20));
        lockExtra = Ui.text(this, "", 15f, Ui.WHITE);
        lockExtra.setPadding(Ui.dp(this, 14), Ui.dp(this, 14),
                Ui.dp(this, 14), Ui.dp(this, 14));
        lockExtra.setBackground(Ui.rounded(this, Ui.SURFACE, 12, 0, 0));
        root.addView(lockExtra, Ui.matchWrap(this, 18));

        temporaryButton = Ui.primaryButton(this, "临时使用");
        temporaryButton.setOnClickListener(view -> {
            RuleEngine.Decision current = displayedDecision;
            if (current != null && RuleEngine.startTemporaryUnlock(
                    this, current.rule, TrustedTime.now(this))) {
                removeLockView();
            } else {
                Toast.makeText(this, "今日临时解锁次数已用完", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(temporaryButton, Ui.matchWrap(this, 24));

        Button home = Ui.secondaryButton(this, "返回桌面");
        home.setOnClickListener(view -> {
            performGlobalAction(GLOBAL_ACTION_HOME);
            removeLockView();
        });
        root.addView(home, Ui.matchWrap(this, 12));
        return root;
    }

    private void removeLockView() {
        if (lockView != null && windowManager != null) {
            try {
                windowManager.removeView(lockView);
            } catch (RuntimeException ignored) {
            }
        }
        lockView = null;
        displayedDecision = null;
    }

    private void showBanner(RuleEngine.ReminderAlert alert) {
        removeBanner();
        LinearLayout banner = Ui.column(this, 14);
        banner.setBackground(Ui.rounded(this, Ui.WHITE, 12, 0, 0));
        banner.addView(Ui.title(this, alert.title, 16f));
        TextView body = Ui.text(this, alert.message, 15f, Ui.BLACK);
        banner.addView(body, Ui.matchWrap(this, 4));
        banner.addView(Ui.text(this, alert.detail, 14f, Ui.MUTED),
                Ui.matchWrap(this, 4));
        // Title is white by default; use black for the white reminder card.
        ((TextView) banner.getChildAt(0)).setTextColor(Ui.BLACK);
        final float[] startX = new float[1];
        banner.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                startX[0] = event.getRawX();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (Math.abs(event.getRawX() - startX[0]) >= Ui.dp(this, 72)) {
                    removeBanner();
                }
                return true;
            }
            return true;
        });
        bannerView = banner;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP;
        params.x = Ui.dp(this, 12);
        params.y = Ui.dp(this, 18);
        try {
            windowManager.addView(bannerView, params);
            bannerRemoval = this::removeBanner;
            handler.postDelayed(bannerRemoval, 5_000L);
        } catch (RuntimeException failed) {
            bannerView = null;
            NotificationHelper.showReminder(this, alert);
        }
    }

    private void showFullPage(RuleEngine.ReminderAlert alert) {
        if (fullPageView != null) {
            return;
        }
        removeBanner();
        LinearLayout root = Ui.column(this, 28);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Ui.BLACK);
        root.addView(Ui.title(this, alert.title, 22f), Ui.matchWrap(this, 10));

        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(Ui.rounded(this, Ui.SURFACE, 16, 1, Ui.SURFACE_HIGH));
        if (!alert.imageUri.isEmpty()) {
            try {
                image.setImageURI(Uri.parse(alert.imageUri));
            } catch (RuntimeException ignored) {
                // The selected document may have moved or lost its grant.
            }
        }
        if (image.getDrawable() == null) {
            image.setImageResource(com.zealmutex.app.R.mipmap.ic_launcher);
            image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            image.setPadding(Ui.dp(this, 52), Ui.dp(this, 52),
                    Ui.dp(this, 52), Ui.dp(this, 52));
        }
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 250));
        imageParams.topMargin = Ui.dp(this, 28);
        root.addView(image, imageParams);

        TextView message = Ui.title(this, alert.message, 25f);
        message.setGravity(Gravity.CENTER);
        root.addView(message, Ui.matchWrap(this, 28));
        TextView detail = Ui.text(this, alert.detail, 16f, Ui.MUTED);
        detail.setGravity(Gravity.CENTER);
        root.addView(detail, Ui.matchWrap(this, 10));

        Button close = Ui.primaryButton(this, "关闭");
        close.setOnClickListener(view -> removeFullPage());
        root.addView(close, Ui.matchWrap(this, 34));
        fullPageView = root;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(fullPageView, params);
        } catch (RuntimeException failed) {
            fullPageView = null;
            NotificationHelper.showReminder(this, alert);
        }
    }

    private void removeBanner() {
        if (bannerRemoval != null) {
            handler.removeCallbacks(bannerRemoval);
            bannerRemoval = null;
        }
        if (bannerView != null && windowManager != null) {
            try {
                windowManager.removeView(bannerView);
            } catch (RuntimeException ignored) {
            }
        }
        bannerView = null;
    }

    private void removeFullPage() {
        if (fullPageView != null && windowManager != null) {
            try {
                windowManager.removeView(fullPageView);
            } catch (RuntimeException ignored) {
            }
        }
        fullPageView = null;
    }
}

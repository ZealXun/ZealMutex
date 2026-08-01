package com.zealmutex.app.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native-view factory that replaces a large UI dependency. */
public final class Ui {
    public static final int BLACK = Color.rgb(0, 0, 0);
    public static final int SURFACE = Color.rgb(21, 21, 21);
    public static final int SURFACE_HIGH = Color.rgb(34, 34, 34);
    public static final int WHITE = Color.WHITE;
    public static final int MUTED = Color.rgb(168, 168, 168);
    public static final int DANGER = Color.rgb(255, 107, 107);
    public static final int BLUE = Color.rgb(74, 144, 255);
    public static final int BLUE_SURFACE = Color.rgb(22, 48, 82);
    public static final int LOCKED_SURFACE = Color.rgb(78, 37, 40);
    public static final int LOCKED_TEXT = Color.rgb(255, 177, 181);

    private static final Palette DARK_PALETTE = new Palette(
            BLACK, SURFACE, SURFACE_HIGH, WHITE, MUTED, BLUE, BLUE_SURFACE,
            LOCKED_SURFACE, LOCKED_TEXT, Color.rgb(70, 70, 70), WHITE, BLACK);
    private static final Palette LIGHT_PALETTE = new Palette(
            WHITE, Color.rgb(244, 244, 244), Color.rgb(228, 228, 228),
            Color.rgb(18, 18, 18), Color.rgb(100, 100, 100), BLUE,
            Color.rgb(224, 236, 255), Color.rgb(252, 229, 230),
            Color.rgb(150, 43, 50), Color.rgb(200, 200, 200),
            Color.rgb(18, 18, 18), WHITE);
    private static final Palette FOCUS_PALETTE = new Palette(
            Color.rgb(9, 18, 32), Color.rgb(18, 34, 54), Color.rgb(29, 50, 76),
            Color.rgb(239, 245, 252), Color.rgb(151, 170, 193),
            Color.rgb(105, 166, 255), Color.rgb(25, 52, 84),
            LOCKED_SURFACE, LOCKED_TEXT, Color.rgb(54, 78, 106),
            Color.rgb(105, 166, 255), Color.rgb(9, 18, 32));
    private static final Palette WARM_PALETTE = new Palette(
            Color.rgb(248, 244, 236), Color.rgb(237, 230, 217),
            Color.rgb(222, 212, 195), Color.rgb(48, 43, 36),
            Color.rgb(112, 101, 87), Color.rgb(75, 111, 154),
            Color.rgb(218, 228, 238), Color.rgb(252, 229, 230),
            Color.rgb(150, 43, 50), Color.rgb(199, 188, 170),
            Color.rgb(48, 43, 36), WHITE);

    private Ui() {
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static int background(Context context) {
        return palette(context).background;
    }

    public static int surface(Context context) {
        return palette(context).surface;
    }

    public static int primaryText(Context context) {
        return palette(context).primaryText;
    }

    public static int mutedText(Context context) {
        return palette(context).mutedText;
    }

    public static int accent(Context context) {
        return palette(context).accent;
    }

    public static int color(Context context, int legacyColor) {
        Palette palette = palette(context);
        if (legacyColor == WHITE) {
            return palette.primaryText;
        }
        if (legacyColor == BLACK) {
            return palette.onPrimary;
        }
        if (legacyColor == MUTED) {
            return palette.mutedText;
        }
        if (legacyColor == SURFACE) {
            return palette.surface;
        }
        if (legacyColor == SURFACE_HIGH) {
            return palette.surfaceHigh;
        }
        if (legacyColor == BLUE) {
            return palette.accent;
        }
        if (legacyColor == BLUE_SURFACE) {
            return palette.accentSurface;
        }
        if (legacyColor == LOCKED_SURFACE) {
            return palette.lockedSurface;
        }
        if (legacyColor == LOCKED_TEXT) {
            return palette.lockedText;
        }
        return legacyColor;
    }

    public static void applyStatusBarInset(View view) {
        if (Build.VERSION.SDK_INT < 35) {
            return;
        }
        int initialTop = view.getPaddingTop();
        view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
            target.setPadding(target.getPaddingLeft(), initialTop + insets.top,
                    target.getPaddingRight(), target.getPaddingBottom());
            return windowInsets;
        });
        view.requestApplyInsets();
    }

    public static void applyNavigationBarInset(View view) {
        if (Build.VERSION.SDK_INT < 35) {
            return;
        }
        int initialBottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsets.Type.navigationBars());
            target.setPadding(target.getPaddingLeft(), target.getPaddingTop(),
                    target.getPaddingRight(), initialBottom + insets.bottom);
            return windowInsets;
        });
        view.requestApplyInsets();
    }

    public static LinearLayout column(Context context, int paddingDp) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, paddingDp), dp(context, paddingDp),
                dp(context, paddingDp), dp(context, paddingDp));
        return layout;
    }

    public static LinearLayout card(Context context) {
        LinearLayout card = column(context, 16);
        card.setBackground(rounded(context, SURFACE, 16, 0, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(context, 12));
        card.setLayoutParams(params);
        return card;
    }

    public static TextView text(Context context, String value, float sizeSp, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color(context, color));
        text.setLineSpacing(0f, 1.12f);
        return text;
    }

    public static TextView title(Context context, String value, float sizeSp) {
        TextView text = text(context, value, sizeSp, WHITE);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    public static Button primaryButton(Context context, String value) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextColor(onPrimary(context));
        button.setTextSize(15f);
        button.setAllCaps(false);
        button.setBackground(rounded(context, primaryFill(context), 12, 0, 0));
        button.setMinHeight(dp(context, 48));
        return button;
    }

    public static Button secondaryButton(Context context, String value) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextColor(primaryText(context));
        button.setTextSize(15f);
        button.setAllCaps(false);
        button.setBackground(rounded(context, surfaceHigh(context), 12, 1, border(context)));
        button.setMinHeight(dp(context, 48));
        return button;
    }

    public static GradientDrawable rounded(
            Context context, int fill, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(context, fill));
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(context, strokeDp), color(context, strokeColor));
        }
        return drawable;
    }

    public static LinearLayout.LayoutParams matchWrap(Context context, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, topMarginDp);
        return params;
    }

    private static int surfaceHigh(Context context) {
        return palette(context).surfaceHigh;
    }

    private static int primaryFill(Context context) {
        return palette(context).primaryFill;
    }

    private static int onPrimary(Context context) {
        return palette(context).onPrimary;
    }

    private static int border(Context context) {
        return palette(context).border;
    }

    private static Palette palette(Context context) {
        if (!(context instanceof Activity)) {
            return DARK_PALETTE;
        }
        switch (ThemeManager.resolved(context)) {
            case ThemeManager.LIGHT:
                return LIGHT_PALETTE;
            case ThemeManager.FOCUS_BLUE:
                return FOCUS_PALETTE;
            case ThemeManager.WARM:
                return WARM_PALETTE;
            default:
                return DARK_PALETTE;
        }
    }

    private static final class Palette {
        final int background;
        final int surface;
        final int surfaceHigh;
        final int primaryText;
        final int mutedText;
        final int accent;
        final int accentSurface;
        final int lockedSurface;
        final int lockedText;
        final int border;
        final int primaryFill;
        final int onPrimary;

        Palette(int background, int surface, int surfaceHigh, int primaryText,
                int mutedText, int accent, int accentSurface, int lockedSurface,
                int lockedText, int border, int primaryFill, int onPrimary) {
            this.background = background;
            this.surface = surface;
            this.surfaceHigh = surfaceHigh;
            this.primaryText = primaryText;
            this.mutedText = mutedText;
            this.accent = accent;
            this.accentSurface = accentSurface;
            this.lockedSurface = lockedSurface;
            this.lockedText = lockedText;
            this.border = border;
            this.primaryFill = primaryFill;
            this.onPrimary = onPrimary;
        }
    }
}

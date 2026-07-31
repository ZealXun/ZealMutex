package com.zealmutex.app.ui;

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

    private Ui() {
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
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
        text.setTextColor(color);
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
        button.setTextColor(BLACK);
        button.setTextSize(15f);
        button.setAllCaps(false);
        button.setBackground(rounded(context, WHITE, 12, 0, 0));
        button.setMinHeight(dp(context, 48));
        return button;
    }

    public static Button secondaryButton(Context context, String value) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextColor(WHITE);
        button.setTextSize(15f);
        button.setAllCaps(false);
        button.setBackground(rounded(context, SURFACE_HIGH, 12, 1, Color.rgb(70, 70, 70)));
        button.setMinHeight(dp(context, 48));
        return button;
    }

    public static GradientDrawable rounded(
            Context context, int fill, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(context, strokeDp), strokeColor);
        }
        return drawable;
    }

    public static LinearLayout.LayoutParams matchWrap(Context context, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, topMarginDp);
        return params;
    }
}

package com.mokuku.debug;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    private final Context context;
    Ui(Context context) { this.context = context; }
    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    int color(int resource) { return context.getColor(resource); }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    GradientDrawable background(int fill, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(fill));
        drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(dp(1), color(border));
        return drawable;
    }

    TextView text(String value, int size, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color(color));
        text.setLineSpacing(dp(3), 1);
        return text;
    }

    TextView muted(String value) { return text(value, 14, R.color.text_secondary); }

    LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(18), dp(16), dp(18));
        card.setBackground(background(R.color.surface, R.color.border, 20));
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(-1, -2);
        parameters.bottomMargin = dp(14);
        parent.addView(card, parameters);
        return card;
    }

    void gap(LinearLayout parent, int size) { parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(size))); }

    Button button(String label, boolean primary, boolean danger) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setTextColor(color(danger ? R.color.danger : primary ? R.color.accent_ink : R.color.text_primary));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x259ad8c2),
                background(primary ? R.color.accent : R.color.raised, primary ? 0 : R.color.border, 14), null));
        button.setStateListAnimator(null);
        return button;
    }

    void fullButton(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(-1, -2);
        parameters.topMargin = dp(10);
        parent.addView(button, parameters);
    }

    ImageView icon(int drawable, int tint) {
        ImageView image = new ImageView(context);
        image.setImageResource(drawable);
        image.setImageTintList(ColorStateList.valueOf(color(tint)));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    View eyes(boolean sleeping) {
        return new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                paint.setColor(color(R.color.accent));
                float eyeWidth = dp(24);
                float eyeHeight = dp(sleeping ? 10 : 42);
                float left = getWidth() / 2f - eyeWidth - dp(7);
                float top = (getHeight() - eyeHeight) / 2;
                canvas.drawRoundRect(left, top, left + eyeWidth, top + eyeHeight, dp(14), dp(14), paint);
                left = getWidth() / 2f + dp(7);
                canvas.drawRoundRect(left, top, left + eyeWidth, top + eyeHeight, dp(14), dp(14), paint);
            }
        };
    }
}

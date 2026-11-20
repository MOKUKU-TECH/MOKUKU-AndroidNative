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
import java.util.Locale;

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

    /** A rounded background with the mint press ripple. */
    RippleDrawable pressable(int fill, int border, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x259ad8c2), background(fill, border, radius), null);
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

    static String downloadProgressText(long received, long total, long elapsedMilliseconds) {
        if (total <= 0) return "Waiting for device reply…";
        String bytes = received + " / " + total + " bytes";
        if (received >= total) return bytes + "\nFinishing download…";
        if (received <= 0 || elapsedMilliseconds < 1000) return bytes + "\nEstimating speed and time remaining…";
        double bytesPerSecond = received * 1000.0 / elapsedMilliseconds;
        String speed = bytesPerSecond >= 1024 * 1024
                ? String.format(Locale.ROOT, "%.1f MiB/s", bytesPerSecond / (1024 * 1024))
                : bytesPerSecond >= 1024
                ? String.format(Locale.ROOT, "%.1f KiB/s", bytesPerSecond / 1024)
                : String.format(Locale.ROOT, "%.1f B/s", bytesPerSecond);
        long seconds = (long) Math.ceil((total - received) / bytesPerSecond);
        String remaining = seconds >= 3600 ? seconds / 3600 + "h " + seconds % 3600 / 60 + "m " + seconds % 60 + "s"
                : seconds >= 60 ? seconds / 60 + "m " + seconds % 60 + "s" : seconds + "s";
        return bytes + "\nAverage speed: " + speed + "\nAbout " + remaining + " left";
    }

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
        button.setBackground(pressable(primary ? R.color.accent : R.color.raised, primary ? 0 : R.color.border, 14));
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

    /** The trip's route, north up and fitted to the view, with a start ring and an end dot. */
    View route(TripLog log, int background) {
        View view = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path path = new android.graphics.Path();
            private final float[] start = new float[2], end = new float[2];

            @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
                path.reset();
                int count = log.pointCount();
                if (count == 0) return;
                double west = Double.MAX_VALUE, east = -Double.MAX_VALUE, south = Double.MAX_VALUE, north = -Double.MAX_VALUE;
                for (int i = 0; i < count; i++) {
                    west = Math.min(west, log.eastMeters[i]); east = Math.max(east, log.eastMeters[i]);
                    south = Math.min(south, log.northMeters[i]); north = Math.max(north, log.northMeters[i]);
                }
                float margin = dp(9);
                double scale = Math.min(width - 2 * margin, height - 2 * margin) / Math.max(1, Math.max(east - west, north - south));
                float left = (float) (width - (east - west) * scale) / 2, bottom = (float) (height + (north - south) * scale) / 2;
                for (int i = 0; i < count; i++) {
                    end[0] = left + (float) ((log.eastMeters[i] - west) * scale);
                    end[1] = bottom - (float) ((log.northMeters[i] - south) * scale);
                    if (i == 0) { start[0] = end[0]; start[1] = end[1]; path.moveTo(end[0], end[1]); } else path.lineTo(end[0], end[1]);
                }
            }

            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (log.pointCount() == 0) return;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(dp(2));
                paint.setColor(color(R.color.accent));
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color(R.color.warm));
                canvas.drawCircle(end[0], end[1], dp(3.5f), paint);
                paint.setColor(color(background));
                canvas.drawCircle(start[0], start[1], dp(3.5f), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(color(R.color.accent));
                canvas.drawCircle(start[0], start[1], dp(3.5f), paint);
            }
        };
        view.setBackground(background(background, 0, 13));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
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

package com.mokuku.debug;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w390dp-h844dp-mdpi")
public class AccessPreviewTest {
    @Test public void renderWide() throws Exception { render(390, ""); }
    @Test @Config(qualifiers = "w320dp-h844dp-mdpi")
    public void renderNarrow() throws Exception { render(320, "-small"); }

    private void render(int width, String suffix) throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity activity = controller.get();
            Object session = ReflectionHelpers.getField(activity, "session");
            MokukuConnection connection = ReflectionHelpers.getField(session, "connection");
            shadowOf(Looper.getMainLooper()).idle();
            ReflectionHelpers.setField(session, "route", "records");
            ReflectionHelpers.callInstanceMethod(activity, "render");
            save(activity.getWindow().getDecorView(), width, 844, "native-records" + suffix);
            ReflectionHelpers.callInstanceMethod(activity, "enterRecordingAccessCode");
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            save(dialog.getWindow().getDecorView(), width - 48, 0, "native-access-code" + suffix);
            dialog.dismiss();
            ReflectionHelpers.setField(connection, "ready", true);
            ReflectionHelpers.setField(connection, "operation", "Downloading mobili_128.record");
            ReflectionHelpers.setField(connection, "operationCancellable", true);
            ReflectionHelpers.setField(connection, "received", 262144L);
            ReflectionHelpers.setField(connection, "total", 1048576L);
            ReflectionHelpers.setField(connection, "downloadStartedAt", android.os.SystemClock.elapsedRealtime() - 10000L);
            ReflectionHelpers.setField(connection, "recordingAccessCode", "preview-only");
            ReflectionHelpers.setField(connection, "message", "Sample download preview.");
            ReflectionHelpers.setField(activity, "snapshot", new MokukuConnection.Snapshot(connection));
            ReflectionHelpers.callInstanceMethod(activity, "render");
            save(activity.getWindow().getDecorView(), width, 844, "native-download" + suffix);
        }
    }

    private void save(View view, int width, int height, String name) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height == 0 ? 844 : height,
                        height == 0 ? View.MeasureSpec.AT_MOST : View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        if (name.startsWith("native-download")) {
            ((android.widget.ScrollView) view.findViewById(R.id.content_scroll)).scrollTo(0, 120);
        }
        Bitmap bitmap = Bitmap.createBitmap(view.getMeasuredWidth(), view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        try (FileOutputStream stream = new FileOutputStream("../design/previews/" + name + ".png")) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }
}

package com.mokuku.debug;

import android.view.View;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.shadows.ShadowAlertDialog;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35})
public class MainActivityTest {
    @Test public void disconnectedNavigationUsesFourTabsAndCorrectParents() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ViewGroup navigation = activity.findViewById(R.id.navigation);
            assertEquals(4, navigation.getChildCount());
            for (int i = 0; i < navigation.getChildCount(); i++) assertTrue(navigation.getChildAt(i).isFocusable());
            navigation.getChildAt(3).performClick();
            click(find(activity.findViewById(R.id.content), "Updates"));
            assertEquals(View.GONE, navigation.getVisibility());
            assertFalse(((Button) find(activity.findViewById(R.id.content), "Save Wi-Fi")).isEnabled());
            assertFalse(((Button) find(activity.findViewById(R.id.content), "Save remote URL")).isEnabled());
            assertNull(find(activity.findViewById(R.id.content), "2.0.0"));
            activity.onBackPressed();
            assertNotNull(find(activity.findViewById(R.id.content), "Your device, your way."));
            navigation.getChildAt(2).performClick();
            assertNotNull(find(activity.findViewById(R.id.content), "Trips"));
            assertEquals(4, navigation.getChildCount());
        }
    }

    @Test public void lateVersionRepliesUpdateLabelsWithoutReplacingDrafts() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ((ViewGroup) activity.findViewById(R.id.navigation)).getChildAt(3).performClick();
            click(find(activity.findViewById(R.id.content), "Updates"));
            EditText password = input(activity.findViewById(R.id.content), "Wi-Fi password");
            password.setText("test-value");
            Object session = ReflectionHelpers.getField(activity, "session");
            MokukuConnection connection = ReflectionHelpers.getField(session, "connection");
            Map<Integer, String> information = ReflectionHelpers.getField(connection, "information");
            information.put(3, "test-left/test-right");
            information.put(5, "test-memes");
            ReflectionHelpers.callInstanceMethod(activity, "changed", ReflectionHelpers.ClassParameter.from(
                    MokukuConnection.Snapshot.class, new MokukuConnection.Snapshot(connection)));
            assertNotNull(find(activity.findViewById(R.id.content), "Last received firmware: test-left/test-right"));
            assertNotNull(find(activity.findViewById(R.id.content), "Last received meme versions: test-memes"));
            assertSame(password, input(activity.findViewById(R.id.content), "Wi-Fi password"));
            assertEquals("test-value", password.getText().toString());
        }
    }

    @Test public void rotationKeepsRouteAndWifiDraft() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ((ViewGroup) activity.findViewById(R.id.navigation)).getChildAt(3).performClick();
            click(find(activity.findViewById(R.id.content), "Updates"));
            EditText password = input(activity.findViewById(R.id.content), "Wi-Fi password");
            password.setText("test-value");
            controller.recreate();
            activity = controller.get();
            assertNotNull(find(activity.findViewById(R.id.content), "Remote URL"));
            assertEquals("test-value", input(activity.findViewById(R.id.content), "Wi-Fi password").getText().toString());
            assertNotNull(find(activity.findViewById(R.id.content), "Save Wi-Fi"));
        }
    }

    @Test public void launchRefillsLastSavedUpdateSources() {
        RuntimeEnvironment.getApplication().getSharedPreferences("update_sources", Context.MODE_PRIVATE).edit()
                .putString("wifi_name", "test-network").putString("wifi_password", "test-value")
                .putString("remote_url", "https://updates.example.com/mokuku").apply();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ((ViewGroup) activity.findViewById(R.id.navigation)).getChildAt(3).performClick();
            click(find(activity.findViewById(R.id.content), "Updates"));
            View content = activity.findViewById(R.id.content);
            assertEquals("test-network", input(content, "Wi-Fi name").getText().toString());
            EditText password = input(content, "Wi-Fi password");
            assertEquals("test-value", password.getText().toString());
            assertNotNull(password.getTransformationMethod());
            assertEquals("https://updates.example.com/mokuku", input(content, "Remote base URL").getText().toString());
        }
    }

    @Test public void recordingAccessDialogMasksValidatesAndClearsCanceledInput() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            Object session = ReflectionHelpers.getField(activity, "session");
            MokukuConnection connection = ReflectionHelpers.getField(session, "connection");
            CountDownLatch initialized = new CountDownLatch(1);
            ReflectionHelpers.<Handler>getField(connection, "worker").post(initialized::countDown);
            assertTrue(initialized.await(5, TimeUnit.SECONDS));
            shadowOf(Looper.getMainLooper()).idle();
            ReflectionHelpers.setField(connection, "ready", true);
            ReflectionHelpers.callInstanceMethod(activity, "changed", ReflectionHelpers.ClassParameter.from(
                    MokukuConnection.Snapshot.class, new MokukuConnection.Snapshot(connection)));
            ((ViewGroup) activity.findViewById(R.id.navigation)).getChildAt(1).performClick();
            click(find(activity.findViewById(R.id.content), "Enter access code"));
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            EditText code = input(dialog.getWindow().getDecorView(), "Recording access code");
            assertNotNull(code.getTransformationMethod());
            assertFalse(code.isSaveEnabled());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertNotNull(code.getError());
            code.setText("sample-code");
            find(dialog.getWindow().getDecorView(), "Show access code").performClick();
            assertNull(code.getTransformationMethod());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
            assertEquals("", code.getText().toString());
            assertEquals("", ReflectionHelpers.getField(connection, "recordingAccessCode"));
            click(find(activity.findViewById(R.id.content), "Enter access code"));
            dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("", input(dialog.getWindow().getDecorView(), "Recording access code").getText().toString());
            controller.recreate();
            assertFalse(dialog.isShowing());
        }
    }

    @Test public void savedAccessCodeCanBeForgottenWhileDisconnected() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("device", Context.MODE_PRIVATE).edit()
                .putString("address", "AA:BB:CC:DD:EE:FF").apply();
        context.getSharedPreferences("recording_access", Context.MODE_PRIVATE).edit()
                .putString("AA:BB:CC:DD:EE:FF", "sample-code").apply();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            Object session = ReflectionHelpers.getField(activity, "session");
            MokukuConnection connection = ReflectionHelpers.getField(session, "connection");
            Handler worker = ReflectionHelpers.getField(connection, "worker");
            CountDownLatch initialized = new CountDownLatch(1);
            worker.post(initialized::countDown);
            assertTrue(initialized.await(5, TimeUnit.SECONDS));
            shadowOf(Looper.getMainLooper()).idle();
            ((ViewGroup) activity.findViewById(R.id.navigation)).getChildAt(1).performClick();
            assertFalse(((Button) find(activity.findViewById(R.id.content), "Change access code")).isEnabled());
            Button forget = (Button) find(activity.findViewById(R.id.content), "Forget access code");
            assertTrue(forget.isEnabled());
            assertNull(find(activity.findViewById(R.id.content), "sample-code"));
            click(forget);
            CountDownLatch forgotten = new CountDownLatch(1);
            worker.post(forgotten::countDown);
            assertTrue(forgotten.await(5, TimeUnit.SECONDS));
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(find(activity.findViewById(R.id.content), "Forget access code"));
            assertNotNull(find(activity.findViewById(R.id.content), "Enter access code"));
            assertTrue(context.getSharedPreferences("recording_access", Context.MODE_PRIVATE).getAll().isEmpty());
        }
    }

    private static View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void click(View view) {
        assertNotNull(view);
        while (!view.isClickable() && view.getParent() instanceof View) view = (View) view.getParent();
        assertTrue(view.performClick());
    }

    private static EditText input(View root, String label) {
        if (root instanceof EditText && label.contentEquals(root.getContentDescription())) return (EditText) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = input(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}

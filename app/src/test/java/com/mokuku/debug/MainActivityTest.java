package com.mokuku.debug;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.Map;
import static org.junit.Assert.*;

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

    @Test public void rotationKeepsRouteButDoesNotPersistPassword() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ((ViewGroup) activity.findViewById(R.id.navigation)).getChildAt(3).performClick();
            click(find(activity.findViewById(R.id.content), "Updates"));
            EditText password = input(activity.findViewById(R.id.content), "Wi-Fi password");
            password.setText("test-value");
            controller.recreate();
            activity = controller.get();
            assertNotNull(find(activity.findViewById(R.id.content), "Remote URL"));
            assertEquals("", input(activity.findViewById(R.id.content), "Wi-Fi password").getText().toString());
            assertNotNull(find(activity.findViewById(R.id.content), "Save Wi-Fi"));
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

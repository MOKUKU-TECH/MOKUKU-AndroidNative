package com.mokuku.debug;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w390dp-h844dp-mdpi")
public class TripsPageTest {
    private static final String CONNECTED = "A4:CF:12:6B:73:A1";
    private static final String OTHER = "3C:84:27:C1:5C:22";

    @Test public void listsTheConnectedDeviceFirstAndOpensATripMap() throws Exception {
        TripStore store = new TripStore(RuntimeEnvironment.getApplication());
        TripLogTest.savePhoneTrip(store, CONNECTED, "mokuku_73A1", 39, TripLogTest.sampleTrip(1794816300000L, 1, 1260));
        TripLogTest.savePhoneTrip(store, CONNECTED, "mokuku_73A1", 40, TripLogTest.sampleTrip(1794855600000L, 2, 1860));
        TripLogTest.savePhoneTrip(store, CONNECTED, "mokuku_73A1", 41, TripLogTest.sampleTrip(1794903120000L, 3, 1380));
        // The other device's trip is newer, so it leads only while nothing is connected.
        TripLogTest.savePhoneTrip(store, OTHER, "mokukuii_5C20", 6, TripLogTest.sampleTrip(1794990120000L, 4, 2760));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity activity = controller.get();
            Object session = ReflectionHelpers.getField(activity, "session");
            MokukuConnection connection = ReflectionHelpers.getField(session, "connection");
            shadowOf(Looper.getMainLooper()).idle();
            ((ViewGroup) activity.findViewById(R.id.navigation)).getChildAt(2).performClick();
            waitForTrips(session);
            ViewGroup content = activity.findViewById(R.id.content);
            assertTrue(position(content, "mokukuii_5C20 (1)") < position(content, "mokuku_73A1 (3)"));
            assertNull(find(content, "● Connected"));
            assertFalse(find(content, "Sync trips").isEnabled());

            ReflectionHelpers.setField(connection, "ready", true);
            ReflectionHelpers.setField(connection, "address", CONNECTED);
            ReflectionHelpers.setField(connection, "name", "mokuku_73A1");
            ReflectionHelpers.setField(connection, "message", "Sample trips preview.");
            ReflectionHelpers.callInstanceMethod(activity, "changed", ReflectionHelpers.ClassParameter.from(
                    MokukuConnection.Snapshot.class, new MokukuConnection.Snapshot(connection)));
            content = activity.findViewById(R.id.content);
            assertTrue(position(content, "mokuku_73A1 (3)") < position(content, "mokukuii_5C20 (1)"));
            assertNotNull(find(content, "● Connected"));
            assertTrue(find(content, "Sync trips").isEnabled());
            assertTrue(find(content, "Delete older trips on MOKUKU").isEnabled());
            save(activity.getWindow().getDecorView(), 0, "native-trips");

            ReflectionHelpers.setField(connection, "operation", MokukuConnection.TRIP_SYNC);
            ReflectionHelpers.setField(connection, "operationCancellable", true);
            ReflectionHelpers.setField(connection, "tripStatus", "Trip 1 of 2 · trip_42.bin");
            ReflectionHelpers.setField(connection, "received", 12400L);
            ReflectionHelpers.setField(connection, "total", 31200L);
            ReflectionHelpers.setField(connection, "downloadStartedAt", android.os.SystemClock.elapsedRealtime() - 2000L);
            ReflectionHelpers.callInstanceMethod(activity, "changed", ReflectionHelpers.ClassParameter.from(
                    MokukuConnection.Snapshot.class, new MokukuConnection.Snapshot(connection)));
            assertNotNull(find(activity.findViewById(R.id.content), "Cancel sync"));
            save(activity.getWindow().getDecorView(), 0, "native-trips-sync");

            ReflectionHelpers.setField(connection, "operation", null);
            ReflectionHelpers.callInstanceMethod(activity, "changed", ReflectionHelpers.ClassParameter.from(
                    MokukuConnection.Snapshot.class, new MokukuConnection.Snapshot(connection)));
            View newest = findDescription(activity.findViewById(R.id.content), "Trip on ");
            newest.performClick();
            content = activity.findViewById(R.id.content);
            assertNotNull(find(content, "Top GPS speed"));
            assertNotNull(find(content, "Driving style"));
            assertNotNull(find(content, "Eco"));
            assertNotNull(findDescription(content, "Map of the trip route"));
            assertEquals(View.GONE, activity.findViewById(R.id.navigation).getVisibility());
            save(activity.getWindow().getDecorView(), 0, "native-trip");
            save(activity.getWindow().getDecorView(), 640, "native-trip-driving");
            activity.onBackPressed();
            assertNotNull(find(activity.findViewById(R.id.content), "Sync trips"));
        }
    }

    @Test public void deletesPhoneTripsByTripAndByDevice() throws Exception {
        TripStore store = new TripStore(RuntimeEnvironment.getApplication());
        TripLogTest.savePhoneTrip(store, CONNECTED, "mokuku_73A1", 1, TripLogTest.sampleTrip(1794816300000L, 1, 60));
        TripLogTest.savePhoneTrip(store, CONNECTED, "mokuku_73A1", 2, TripLogTest.sampleTrip(1794855600000L, 2, 60));
        TripLogTest.savePhoneTrip(store, OTHER, "mokukuii_5C20", 5, TripLogTest.sampleTrip(1794903120000L, 3, 60));
        TripLogTest.savePhoneTrip(store, OTHER, "mokukuii_5C20", 6, TripLogTest.sampleTrip(1794990120000L, 4, 60));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            MainActivity activity = controller.get();
            Object session = ReflectionHelpers.getField(activity, "session");
            shadowOf(Looper.getMainLooper()).idle();
            ((ViewGroup) activity.findViewById(R.id.navigation)).getChildAt(2).performClick();
            waitForTrips(session);

            // The newest trip is the other device's trip_6.bin.
            findDescription(activity.findViewById(R.id.content), "Trip on ").performClick();
            find(activity.findViewById(R.id.content), "Delete from phone").performClick();
            confirmDialog();
            waitFor(activity, "mokukuii_5C20 (1)", true);
            assertEquals(java.util.Arrays.asList(5), store.numbers(OTHER));

            find(activity.findViewById(R.id.content), "Delete 2 trips from phone").performClick();
            confirmDialog();
            waitFor(activity, "mokuku_73A1 (2)", false);
            assertNotNull(find(activity.findViewById(R.id.content), "mokukuii_5C20 (1)"));
            assertTrue(store.numbers(CONNECTED).isEmpty());
            assertEquals(1, store.load().size());
        }
    }

    private static void confirmDialog() {
        android.app.AlertDialog dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void waitFor(MainActivity activity, String text, boolean shown) throws Exception {
        for (int i = 0; i < 100 && (find(activity.findViewById(R.id.content), text) != null) != shown; i++) {
            Thread.sleep(20);
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertEquals(text, shown, find(activity.findViewById(R.id.content), text) != null);
    }

    private static void waitForTrips(Object session) throws Exception {
        for (int i = 0; i < 100 && ReflectionHelpers.getField(session, "trips") == null; i++) {
            Thread.sleep(20);
            shadowOf(Looper.getMainLooper()).idle();
        }
        List<?> trips = ReflectionHelpers.getField(session, "trips");
        assertNotNull("Trips did not load", trips);
    }

    private static int position(View root, String text) {
        int[] index = {0};
        return search(root, text, index);
    }

    private static int search(View view, String text, int[] index) {
        index[0]++;
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return index[0];
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                int found = search(((ViewGroup) view).getChildAt(i), text, index);
                if (found > 0) return found;
            }
        }
        return -1;
    }

    private static View find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = find(((ViewGroup) view).getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findDescription(View view, String prefix) {
        if (view.getContentDescription() != null && view.getContentDescription().toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = findDescription(((ViewGroup) view).getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void save(View view, int scrollY, String name) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        ((android.widget.ScrollView) view.findViewById(R.id.content_scroll)).scrollTo(0, scrollY);
        Bitmap bitmap = Bitmap.createBitmap(view.getMeasuredWidth(), view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        try (FileOutputStream stream = new FileOutputStream("../design/previews/" + name + ".png")) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }
}

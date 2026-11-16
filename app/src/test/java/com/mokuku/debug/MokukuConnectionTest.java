package com.mokuku.debug;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.Handler;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBluetoothDevice;
import org.robolectric.shadows.ShadowBluetoothGatt;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MokukuConnectionTest {
    @Test public void enteredCodeIsSentBeforeListingWithoutBuildConfiguration() throws Exception {
        MokukuConnection connection = connectedSession();
        try {
            List<byte[]> writes = new ArrayList<>();
            CountDownLatch listed = new CountDownLatch(1);
            BluetoothGatt gatt = ReflectionHelpers.getField(connection, "gatt");
            BluetoothGattCallback callback = ReflectionHelpers.getField(connection, "gattCallback");
            shadowOf(gatt).setGattCallback(new BluetoothGattCallback() {
                @Override public void onCharacteristicWrite(BluetoothGatt value, BluetoothGattCharacteristic characteristic, int status) {
                    byte[] packet = characteristic.getValue().clone();
                    writes.add(packet);
                    callback.onCharacteristicWrite(value, characteristic, status);
                    if (packet[0] == 60) listed.countDown();
                }
            });
            connection.setRecordingAccessCode("sample-code");
            assertTrue("Directory request was not sent", listed.await(5, TimeUnit.SECONDS));
            assertArrayEquals(MokukuProtocol.stringMessage(44, "sample-code", 255), writes.get(0));
            assertArrayEquals(MokukuProtocol.stringMessage(60, "/sd/record", 255), writes.get(1));
            assertTrue(connection.snapshot().hasRecordingAccessCode);
            assertFalse(connection.snapshot().message.contains("sample-code"));
        } finally { connection.close(); }
    }

    @Test public void disconnectClearsDeviceValuesAndPendingQueryErrorsButKeepsDownloads() throws Exception {
        MokukuConnection connection = connectedSession();
        File saved = new File(RuntimeEnvironment.getApplication().getFilesDir(), "mobili_1.record");
        assertTrue(saved.createNewFile() || saved.isFile());
        try {
            onWorker(connection, () -> {
                Map<Integer, String> information = ReflectionHelpers.getField(connection, "information");
                information.put(3, "sample-firmware");
                information.put(4, "sample-mac");
                information.put(61, "1/2");
                Map<Integer, String> errors = ReflectionHelpers.getField(connection, "errors");
                errors.put(57, "sample-error");
                List<String> recordings = ReflectionHelpers.getField(connection, "recordings");
                recordings.add(saved.getName());
                Map<String, File> downloads = ReflectionHelpers.getField(connection, "downloads");
                downloads.put(saved.getName(), saved);
                ReflectionHelpers.setField(connection, "recordingAccessCode", "sample-code");
                ReflectionHelpers.callInstanceMethod(connection, "refreshInformationOnWorker");
            });
            connection.disconnect();
            onWorker(connection, () -> {});
            MokukuConnection.Snapshot snapshot = connection.snapshot();
            assertFalse(snapshot.connected);
            assertTrue(snapshot.information.isEmpty());
            assertTrue(snapshot.errors.isEmpty());
            assertTrue(snapshot.recordings.isEmpty());
            assertFalse(snapshot.hasRecordingAccessCode);
            assertEquals("", ReflectionHelpers.getField(connection, "recordingAccessCode"));
            assertEquals(saved, snapshot.downloads.get(saved.getName()));
            assertTrue(saved.isFile());
        } finally { connection.close(); saved.delete(); }
    }

    private MokukuConnection connectedSession() throws Exception {
        MokukuConnection connection = new MokukuConnection(RuntimeEnvironment.getApplication());
        onWorker(connection, () -> {
            BluetoothGatt gatt = ShadowBluetoothGatt.newInstance(ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:FF"));
            BluetoothGattService service = new BluetoothGattService(MokukuProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            BluetoothGattCharacteristic messages = new BluetoothGattCharacteristic(MokukuProtocol.MESSAGE,
                    BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
            service.addCharacteristic(messages);
            BluetoothGattCallback callback = ReflectionHelpers.getField(connection, "gattCallback");
            shadowOf(gatt).setGattCallback(callback);
            ReflectionHelpers.setField(connection, "gatt", gatt);
            ReflectionHelpers.setField(connection, "messages", messages);
            ReflectionHelpers.setField(connection, "ready", true);
        });
        return connection;
    }

    private void onWorker(MokukuConnection connection, Runnable action) throws Exception {
        Handler worker = ReflectionHelpers.getField(connection, "worker");
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        worker.post(() -> {
            try { action.run(); }
            catch (Throwable error) { failure.set(error); }
            finally { complete.countDown(); }
        });
        assertTrue("Bluetooth worker did not finish", complete.await(5, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}

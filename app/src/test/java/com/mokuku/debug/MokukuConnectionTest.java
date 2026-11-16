package com.mokuku.debug;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.Handler;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBluetoothDevice;
import org.robolectric.shadows.ShadowBluetoothGatt;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = MokukuConnectionTest.TransferGatt.class)
public class MokukuConnectionTest {
    @Implements(BluetoothGatt.class)
    public static class TransferGatt extends ShadowBluetoothGatt {
        int writeType;

        @Implementation(minSdk = 23)
        protected boolean requestConnectionPriority(int priority) {
            return super.requestConnectionPriority(priority);
        }

        @Implementation(minSdk = 23, maxSdk = 32)
        protected boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
            writeType = characteristic.getWriteType();
            return writeIncomingCharacteristic(characteristic);
        }

        @Implementation(minSdk = 33)
        protected int writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value, int type) {
            writeType = type;
            return super.writeCharacteristic(characteristic, value, type);
        }
    }

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

    @Test @Config(sdk = {23, 35})
    public void downloadUsesFastWritesAndWaitsForBothCallbacksBeforeNextChunk() throws Exception {
        MokukuConnection connection = connectedSession();
        File saved = null;
        try {
            DownloadPeer peer = new DownloadPeer(connection, true, 517, 258);
            peer.holdReadCompletion = true;
            connection.download("mobili_9001.record");
            byte[] first = peer.nextRead();
            onWorker(connection, () -> {});
            assertEquals(BluetoothGatt.CONNECTION_PRIORITY_HIGH, shadowOf(peer.gatt).getConnectionPriority());
            assertEquals(255, MokukuProtocol.unsigned(first[1]));
            assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, (int) peer.writeTypes.get(2));
            assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, (int) peer.writeTypes.get(0));
            assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, (int) peer.writeTypes.get(1));

            byte[] expected = new byte[258];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte) i;
            peer.notifyChunk(0, java.util.Arrays.copyOf(expected, 255));
            onWorker(connection, () -> {});
            assertTrue("Next write started before the previous local write completed", peer.reads.isEmpty());
            assertEquals(0L, (long) ReflectionHelpers.getField(connection, "received"));

            peer.holdReadCompletion = false;
            peer.completeReadWrite();
            byte[] second = peer.nextRead();
            assertEquals(255, ByteBuffer.wrap(second).order(ByteOrder.LITTLE_ENDIAN).getInt(6));
            peer.notifyChunk(255, java.util.Arrays.copyOfRange(expected, 255, 258));
            onWorker(connection, () -> {});
            MokukuConnection.Snapshot snapshot = connection.snapshot();
            assertFalse(snapshot.busy);
            saved = snapshot.downloads.get("mobili_9001.record");
            assertNotNull(saved);
            assertArrayEquals(expected, Files.readAllBytes(saved.toPath()));
            assertEquals(BluetoothGatt.CONNECTION_PRIORITY_BALANCED, shadowOf(peer.gatt).getConnectionPriority());

            connection.command(43, "Left click");
            assertTrue(peer.commandSent.await(5, TimeUnit.SECONDS));
            assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, (int) peer.writeTypes.get(4));
        } finally { connection.close(); if (saved != null) saved.delete(); }
    }

    @Test @Config(sdk = {23, 35})
    public void smallMtuAndAcknowledgedOnlyCharacteristicRemainUsableAndCancelRestoresPriority() throws Exception {
        MokukuConnection connection = connectedSession();
        try {
            DownloadPeer peer = new DownloadPeer(connection, false, 64, 100);
            connection.download("mobili_9002.record");
            assertEquals(55, MokukuProtocol.unsigned(peer.nextRead()[1]));
            onWorker(connection, () -> {});
            assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, (int) peer.writeTypes.get(2));
            File partial = ReflectionHelpers.getField(connection, "partial");
            connection.cancelOperation();
            onWorker(connection, () -> {});
            assertFalse(connection.snapshot().busy);
            assertFalse(partial.exists());
            assertEquals(BluetoothGatt.CONNECTION_PRIORITY_BALANCED, shadowOf(peer.gatt).getConnectionPriority());
            peer.notifyChunk(0, new byte[55]);
            onWorker(connection, () -> {});
            assertTrue(peer.reads.isEmpty());
            assertTrue(connection.snapshot().downloads.isEmpty());
        } finally { connection.close(); }
    }

    @Test @Config(sdk = {23, 35})
    public void invalidChunkStopsFastDownloadAndRestoresPriority() throws Exception {
        MokukuConnection connection = connectedSession();
        try {
            DownloadPeer peer = new DownloadPeer(connection, true, 517, 100);
            connection.download("mobili_9003.record");
            peer.nextRead();
            onWorker(connection, () -> {});
            File partial = ReflectionHelpers.getField(connection, "partial");
            peer.notifyChunk(1, new byte[99]);
            onWorker(connection, () -> {});
            assertFalse(connection.snapshot().busy);
            assertTrue(connection.snapshot().message.contains("out of order"));
            assertTrue(connection.snapshot().downloads.isEmpty());
            assertFalse(partial.exists());
            assertEquals(BluetoothGatt.CONNECTION_PRIORITY_BALANCED, shadowOf(peer.gatt).getConnectionPriority());
            assertTrue(peer.reads.isEmpty());
        } finally { connection.close(); }
    }

    @Test public void disconnectDuringFastDownloadDiscardsPartialFileAndClearsPriorityState() throws Exception {
        MokukuConnection connection = connectedSession();
        try {
            DownloadPeer peer = new DownloadPeer(connection, true, 517, 300);
            connection.download("mobili_9004.record");
            peer.nextRead();
            onWorker(connection, () -> {});
            File partial = ReflectionHelpers.getField(connection, "partial");
            peer.notifyChunk(0, new byte[255]);
            peer.nextRead();
            connection.disconnect();
            onWorker(connection, () -> {});
            assertFalse(connection.snapshot().connected);
            assertFalse(connection.snapshot().busy);
            assertTrue(connection.snapshot().downloads.isEmpty());
            assertFalse(partial.exists());
            assertFalse((boolean) ReflectionHelpers.getField(connection, "downloadPriorityRequested"));
            assertTrue(shadowOf(peer.gatt).isClosed());
        } finally { connection.close(); }
    }

    private final class DownloadPeer {
        final BluetoothGatt gatt;
        final BluetoothGattCallback callback;
        final BluetoothGattCharacteristic messages;
        final BluetoothGattCharacteristic data = new BluetoothGattCharacteristic(MokukuProtocol.DATA,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
        final LinkedBlockingQueue<byte[]> reads = new LinkedBlockingQueue<>();
        final List<Integer> writeTypes = new ArrayList<>();
        final CountDownLatch commandSent = new CountDownLatch(1);
        volatile boolean holdReadCompletion;

        DownloadPeer(MokukuConnection connection, boolean fastWrites, int mtu, int fileSize) throws Exception {
            gatt = ReflectionHelpers.getField(connection, "gatt");
            callback = ReflectionHelpers.getField(connection, "gattCallback");
            BluetoothGattService service = new BluetoothGattService(MokukuProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            messages = new BluetoothGattCharacteristic(MokukuProtocol.MESSAGE,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | (fastWrites ? BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE : 0),
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            service.addCharacteristic(messages);
            service.addCharacteristic(data);
            onWorker(connection, () -> {
                ReflectionHelpers.setField(connection, "messages", messages);
                ReflectionHelpers.setField(connection, "mtu", mtu);
                ReflectionHelpers.setField(connection, "recordingAccessCode", "sample-code");
                shadowOf(gatt).setGattCallback(new BluetoothGattCallback() {
                    @Override public void onCharacteristicWrite(BluetoothGatt value, BluetoothGattCharacteristic characteristic, int status) {
                        byte[] packet = characteristic.getValue().clone();
                        writeTypes.add(((TransferGatt) Shadow.extract(value)).writeType);
                        if (packet[0] != 66 || !holdReadCompletion) callback.onCharacteristicWrite(value, characteristic, status);
                        if (packet[0] == 65) {
                            byte[] opened = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                                    .put((byte) 65).put((byte) 1).putInt(123).putInt(fileSize).array();
                            notifyPacket(opened);
                        } else if (packet[0] == 66) reads.add(packet);
                        else if (packet[0] == 1) {
                            notifyPacket(new byte[] {1, 1});
                            commandSent.countDown();
                        }
                    }
                });
            });
        }

        byte[] nextRead() throws Exception {
            byte[] packet = reads.poll(5, TimeUnit.SECONDS);
            assertNotNull("File read was not sent", packet);
            return packet;
        }

        void completeReadWrite() { callback.onCharacteristicWrite(gatt, messages, BluetoothGatt.GATT_SUCCESS); }

        void notifyChunk(int offset, byte[] bytes) {
            byte[] packet = ByteBuffer.allocate(6 + bytes.length).order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) 66).put((byte) bytes.length).putInt(offset).put(bytes).array();
            notifyPacket(packet);
        }

        @SuppressWarnings("deprecation")
        private void notifyPacket(byte[] packet) {
            data.setValue(packet);
            callback.onCharacteristicChanged(gatt, data);
        }
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

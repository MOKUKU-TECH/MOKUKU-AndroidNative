package com.mokuku.debug;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.Handler;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
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
        try {
            onWorker(connection, () -> {
                Map<Integer, String> information = ReflectionHelpers.getField(connection, "information");
                information.put(3, "sample-firmware");
                information.put(4, "sample-mac");
                information.put(61, "1/2");
                Map<Integer, String> errors = ReflectionHelpers.getField(connection, "errors");
                errors.put(57, "sample-error");
                List<String> recordings = ReflectionHelpers.getField(connection, "recordings");
                recordings.add("20261116_143512.rec");
                Map<String, String> saved = ReflectionHelpers.getField(connection, "saved");
                saved.put("20261116_143512.rec", "Download/MOKUKU/20261116_143512.rec");
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
            assertEquals("Download/MOKUKU/20261116_143512.rec", snapshot.saved.get("20261116_143512.rec"));
        } finally { connection.close(); }
    }

    @Test @Config(sdk = {23, 35})
    public void savedCodeSurvivesDisconnectAndNewSessionAndIsReusedForListing() throws Exception {
        MokukuConnection first = connectedSession();
        try {
            first.setRecordingAccessCode("sample-saved-code");
            onWorker(first, () -> {});
            first.disconnect();
            onWorker(first, () -> {});
            assertFalse(first.snapshot().connected);
            assertTrue(first.snapshot().hasRecordingAccessCode);
        } finally { first.close(); }

        MokukuConnection reopened = connectedSession();
        try {
            assertTrue(reopened.snapshot().hasRecordingAccessCode);
            List<byte[]> writes = new ArrayList<>();
            CountDownLatch listed = new CountDownLatch(1);
            BluetoothGatt gatt = ReflectionHelpers.getField(reopened, "gatt");
            BluetoothGattCallback callback = ReflectionHelpers.getField(reopened, "gattCallback");
            shadowOf(gatt).setGattCallback(new BluetoothGattCallback() {
                @Override public void onCharacteristicWrite(BluetoothGatt value, BluetoothGattCharacteristic characteristic, int status) {
                    byte[] packet = characteristic.getValue().clone();
                    writes.add(packet);
                    callback.onCharacteristicWrite(value, characteristic, status);
                    if (packet[0] == 60) listed.countDown();
                }
            });
            reopened.refreshRecordings();
            assertTrue(listed.await(5, TimeUnit.SECONDS));
            assertArrayEquals(MokukuProtocol.stringMessage(44, "sample-saved-code", 255), writes.get(0));
            assertEquals(60, writes.get(1)[0]);
            assertFalse(reopened.snapshot().message.contains("sample-saved-code"));
        } finally { reopened.close(); }
    }

    @Test @Config(sdk = {23, 35})
    public void replacingAndForgettingSavedCodeAffectsOnlySelectedDevice() throws Exception {
        SharedPreferences codes = RuntimeEnvironment.getApplication().getSharedPreferences("recording_access", Context.MODE_PRIVATE);
        codes.edit().putString("AA:BB:CC:DD:EE:FF", "sample-first")
                .putString("11:22:33:44:55:66", "sample-second").apply();
        MokukuConnection first = connectedSession();
        try {
            first.setRecordingAccessCode("");
            onWorker(first, () -> {});
            assertEquals("sample-first", codes.getString("AA:BB:CC:DD:EE:FF", ""));
            first.setRecordingAccessCode("sample-replacement");
            onWorker(first, () -> {});
            first.forgetRecordingAccessCode();
            onWorker(first, () -> {});
            assertEquals("sample-replacement", codes.getString("AA:BB:CC:DD:EE:FF", ""));
            first.disconnect();
            onWorker(first, () -> {});
            first.forgetRecordingAccessCode();
            onWorker(first, () -> {});
            assertFalse(first.snapshot().hasRecordingAccessCode);
            assertFalse(codes.contains("AA:BB:CC:DD:EE:FF"));
            assertEquals("sample-second", codes.getString("11:22:33:44:55:66", ""));
        } finally { first.close(); }

        MokukuConnection reopened = connectedSession();
        try { assertFalse(reopened.snapshot().hasRecordingAccessCode); }
        finally { reopened.close(); }

        MokukuConnection other = connectedSession("11:22:33:44:55:66");
        try { assertEquals("sample-second", ReflectionHelpers.getField(other, "recordingAccessCode")); }
        finally { other.close(); }
    }

    @Test @Config(sdk = {23, 35})
    public void downloadReceivesCrcCheckedSectorsAndSavesThemToThePhone() throws Exception {
        MokukuConnection connection = connectedSession();
        try {
            byte[] expected = pattern(250);
            SectorPeer peer = new SectorPeer(connection, expected);
            connection.download("20261116_143512.rec");
            assertTrue("Download did not finish: " + connection.snapshot().message, peer.finished.await(5, TimeUnit.SECONDS));
            onWorker(connection, () -> {});
            MokukuConnection.Snapshot snapshot = connection.snapshot();
            assertFalse(snapshot.busy);
            assertArrayEquals(expected, peer.phone.get("20261116_143512.rec"));
            assertEquals("Download/MOKUKU/20261116_143512.rec", snapshot.saved.get("20261116_143512.rec"));
            assertTrue(snapshot.message.contains("Download/MOKUKU/20261116_143512.rec"));
            assertEquals(java.util.Arrays.asList(1, 2, 3), peer.nextSectors);
            assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, (int) peer.startWriteType);
            assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, (int) peer.acknowledgementWriteType);
            assertEquals(BluetoothGatt.CONNECTION_PRIORITY_BALANCED, shadowOf(peer.gatt).getConnectionPriority());
            File[] left = new File(RuntimeEnvironment.getApplication().getCacheDir(), "recordings").listFiles();
            assertTrue("Partial file left behind", left == null || left.length == 0);
        } finally { connection.close(); }
    }

    @Test public void failedPhoneSaveReportsTheErrorAndKeepsNothing() throws Exception {
        MokukuConnection connection = connectedSession();
        try {
            SectorPeer peer = new SectorPeer(connection, pattern(250));
            onWorker(connection, () -> ReflectionHelpers.setField(connection, "saver",
                    (PhoneDownloads.Saver) (source, name) -> { throw new java.io.IOException("Downloads is full"); }));
            connection.download("20261116_143512.rec");
            assertTrue(peer.finished.await(5, TimeUnit.SECONDS));
            onWorker(connection, () -> {});
            assertFalse(connection.snapshot().busy);
            assertTrue(connection.snapshot().message.contains("Downloads is full"));
            assertTrue(connection.snapshot().saved.isEmpty());
        } finally { connection.close(); }
    }

    @Test public void corruptSectorAndRefusedStartAreRetried() throws Exception {
        MokukuConnection connection = connectedSession();
        try {
            byte[] expected = pattern(250);
            SectorPeer peer = new SectorPeer(connection, expected);
            peer.refusals = 1;
            peer.corruptSector = 1;
            connection.download("mobili_9005.record");
            assertTrue(peer.refused.await(5, TimeUnit.SECONDS));
            onWorker(connection, () -> {});
            // The paused test clock never fires the delayed retry.
            onWorker(connection, ReflectionHelpers.getField(connection, "startDownloadAgain"));
            assertTrue("Download did not finish: " + connection.snapshot().message, peer.finished.await(5, TimeUnit.SECONDS));
            onWorker(connection, () -> {});
            assertArrayEquals(expected, peer.phone.get("mobili_9005.record"));
            assertEquals(2, peer.starts.get());
            assertEquals(java.util.Arrays.asList(1, 1, 2, 3), peer.nextSectors);
        } finally { connection.close(); }
    }

    @Test public void repeatedStallsStopTheDownloadAndDiscardThePartialFile() throws Exception {
        MokukuConnection connection = connectedSession();
        try {
            SectorPeer peer = new SectorPeer(connection, pattern(250));
            peer.silent = true;
            connection.download("mobili_9006.record");
            assertTrue(peer.started.await(5, TimeUnit.SECONDS));
            onWorker(connection, () -> {});
            File partial = ReflectionHelpers.getField(connection, "partial");
            Runnable stall = ReflectionHelpers.getField(connection, "sectorTimeout");
            for (int i = 0; i < 4; i++) onWorker(connection, stall);
            onWorker(connection, () -> {});
            assertFalse(connection.snapshot().busy);
            assertTrue(connection.snapshot().message.contains("stopped sending"));
            assertFalse(partial.exists());
            assertTrue("Stop was not sent", peer.stops.get() > 0);
        } finally { connection.close(); }
    }

    @Test public void cancelStopsTheTransferAndRestoresPriority() throws Exception {
        MokukuConnection connection = connectedSession();
        try {
            SectorPeer peer = new SectorPeer(connection, pattern(250));
            peer.silent = true;
            connection.download("mobili_9002.record");
            assertTrue(peer.started.await(5, TimeUnit.SECONDS));
            onWorker(connection, () -> {});
            assertEquals(BluetoothGatt.CONNECTION_PRIORITY_HIGH, shadowOf(peer.gatt).getConnectionPriority());
            File partial = ReflectionHelpers.getField(connection, "partial");
            connection.cancelOperation();
            onWorker(connection, () -> {});
            onWorker(connection, () -> {});
            assertFalse(connection.snapshot().busy);
            assertFalse(partial.exists());
            assertEquals(1, peer.stops.get());
            assertTrue(connection.snapshot().saved.isEmpty());
            assertEquals(BluetoothGatt.CONNECTION_PRIORITY_BALANCED, shadowOf(peer.gatt).getConnectionPriority());
        } finally { connection.close(); }
    }

    @Test public void disconnectDuringDownloadDiscardsPartialFileAndClearsPriorityState() throws Exception {
        MokukuConnection connection = connectedSession();
        try {
            SectorPeer peer = new SectorPeer(connection, pattern(250));
            peer.silent = true;
            connection.download("mobili_9004.record");
            assertTrue(peer.started.await(5, TimeUnit.SECONDS));
            onWorker(connection, () -> {});
            File partial = ReflectionHelpers.getField(connection, "partial");
            connection.disconnect();
            onWorker(connection, () -> {});
            assertFalse(connection.snapshot().connected);
            assertFalse(connection.snapshot().busy);
            assertTrue(connection.snapshot().saved.isEmpty());
            assertFalse(partial.exists());
            assertFalse((boolean) ReflectionHelpers.getField(connection, "downloadPriorityRequested"));
            assertTrue(shadowOf(peer.gatt).isClosed());
        } finally { connection.close(); }
    }

    @Test public void firmwareWithoutSectorTransferAsksForAnUpdate() throws Exception {
        MokukuConnection connection = connectedSession();
        try {
            connection.download("mobili_9003.record");
            onWorker(connection, () -> {});
            assertFalse(connection.snapshot().busy);
            assertTrue(connection.snapshot().message.contains("Update MOKUKU's firmware"));
        } finally { connection.close(); }
    }

    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 7);
        return bytes;
    }

    /** MOKUKU's side of the sector transfer: 100-byte sectors in 40-byte packets. */
    private final class SectorPeer {
        static final int SECTOR_SIZE = 100;
        static final int CHUNK = 40;
        final BluetoothGatt gatt;
        final BluetoothGattCallback callback;
        final byte[] file;
        final BluetoothGattCharacteristic command = new BluetoothGattCharacteristic(MokukuProtocol.FILE_COMMAND,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
        final BluetoothGattCharacteristic data = new BluetoothGattCharacteristic(MokukuProtocol.FILE_DATA,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE | BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
        final List<Integer> nextSectors = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger stops = new java.util.concurrent.atomic.AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch refused = new CountDownLatch(1);
        // The phone's Download folder.
        final Map<String, byte[]> phone = new java.util.HashMap<>();
        final CountDownLatch finished = new CountDownLatch(1);
        Integer startWriteType;
        Integer acknowledgementWriteType;
        int refusals;
        int corruptSector = -1;
        boolean silent;

        SectorPeer(MokukuConnection connection, byte[] file) throws Exception {
            this.file = file;
            gatt = ReflectionHelpers.getField(connection, "gatt");
            callback = ReflectionHelpers.getField(connection, "gattCallback");
            BluetoothGattService service = new BluetoothGattService(MokukuProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            service.addCharacteristic(command);
            service.addCharacteristic(data);
            onWorker(connection, () -> {
                ReflectionHelpers.setField(connection, "fileCommand", command);
                ReflectionHelpers.setField(connection, "fileData", data);
                ReflectionHelpers.setField(connection, "saver", (PhoneDownloads.Saver) (source, name) -> {
                    phone.put(name, Files.readAllBytes(source.toPath()));
                    return "Download/MOKUKU/" + name;
                });
                ReflectionHelpers.setField(connection, "mtu", 517);
                ReflectionHelpers.setField(connection, "recordingAccessCode", "sample-code");
                shadowOf(gatt).setGattCallback(new BluetoothGattCallback() {
                    @Override public void onCharacteristicWrite(BluetoothGatt value, BluetoothGattCharacteristic characteristic, int status) {
                        byte[] packet = characteristic.getValue().clone();
                        int writeType = ((TransferGatt) Shadow.extract(value)).writeType;
                        callback.onCharacteristicWrite(value, characteristic, status);
                        if (characteristic.getUuid().equals(MokukuProtocol.FILE_COMMAND)) command(packet, writeType);
                        else if (characteristic.getUuid().equals(MokukuProtocol.FILE_DATA)) acknowledgement(packet, writeType);
                    }
                });
            });
        }

        private void command(byte[] frame, int writeType) {
            ByteBuffer body = ByteBuffer.wrap(MokukuProtocol.withoutCrc(frame, frame.length)).order(ByteOrder.LITTLE_ENDIAN);
            int command = body.getShort(0);
            if (command == MokukuProtocol.COMMAND_STOP) { stops.incrementAndGet(); return; }
            assertEquals(MokukuProtocol.COMMAND_START_DOWNLOAD, command);
            starts.incrementAndGet();
            startWriteType = writeType;
            if (refusals-- > 0) { notify(this.command, reply(MokukuProtocol.COMMAND_INVALID)); refused.countDown(); return; }
            notify(this.command, reply(MokukuProtocol.COMMAND_ACCEPTED));
            started.countDown();
            if (!silent) sendSector(0);
        }

        private byte[] reply(int status) {
            ByteBuffer body = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) MokukuProtocol.COMMAND_REPLY).putShort((short) MokukuProtocol.COMMAND_START_DOWNLOAD)
                    .putShort((short) status).putInt(file.length).putInt(SECTOR_SIZE).putShort((short) 0);
            return withCrc(body.array(), 16);
        }

        private void acknowledgement(byte[] frame, int writeType) {
            acknowledgementWriteType = writeType;
            ByteBuffer body = ByteBuffer.wrap(MokukuProtocol.withoutCrc(frame, frame.length)).order(ByteOrder.LITTLE_ENDIAN);
            int next = body.getShort(4);
            nextSectors.add(next);
            if (silent) return;
            if (next * SECTOR_SIZE >= file.length) finished.countDown();
            else sendSector(next);
        }

        private void sendSector(int index) {
            int start = index * SECTOR_SIZE;
            byte[] body = java.util.Arrays.copyOfRange(file, start, Math.min(file.length, start + SECTOR_SIZE));
            byte[] sector = withCrc(body, body.length);
            if (index == corruptSector) { sector[0] ^= 1; corruptSector = -1; }
            for (int offset = 0, sequence = 0; offset < sector.length; offset += CHUNK, sequence++) {
                int length = Math.min(CHUNK, sector.length - offset);
                boolean last = offset + length == sector.length;
                ByteBuffer packet = ByteBuffer.allocate(3 + length).order(ByteOrder.LITTLE_ENDIAN)
                        .putShort((short) index).put((byte) (last ? MokukuProtocol.LAST_PACKET : sequence));
                packet.put(sector, offset, length);
                notify(data, packet.array());
            }
        }

        /** Writes the CRC of bytes[0, length) into bytes[length, length + 2). */
        private byte[] withCrc(byte[] bytes, int length) {
            byte[] frame = java.util.Arrays.copyOf(bytes, length + 2);
            int crc = MokukuProtocol.crc16(frame, 0, length, 0);
            frame[length] = (byte) crc;
            frame[length + 1] = (byte) (crc >> 8);
            return frame;
        }

        @SuppressWarnings("deprecation")
        private void notify(BluetoothGattCharacteristic characteristic, byte[] packet) {
            characteristic.setValue(packet);
            callback.onCharacteristicChanged(gatt, characteristic);
        }
    }

    private MokukuConnection connectedSession() throws Exception {
        return connectedSession("AA:BB:CC:DD:EE:FF");
    }

    private MokukuConnection connectedSession(String address) throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("device", Context.MODE_PRIVATE)
                .edit().putString("address", address).apply();
        MokukuConnection connection = new MokukuConnection(RuntimeEnvironment.getApplication());
        onWorker(connection, () -> {
            BluetoothGatt gatt = ShadowBluetoothGatt.newInstance(ShadowBluetoothDevice.newInstance(address));
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

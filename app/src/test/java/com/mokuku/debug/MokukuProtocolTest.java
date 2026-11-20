package com.mokuku.debug;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MokukuProtocolTest {
    @Test public void tellsDeviceGenerationFromItsName() {
        assertTrue(MokukuProtocol.isOriginalDevice("mokuku_73A1"));
        assertFalse(MokukuProtocol.isOriginalDevice("mokukuii73A1"));
        assertTrue(MokukuProtocol.isMokukuII("mokukuii9EAB"));
        assertFalse(MokukuProtocol.isMokukuII("mokuku_73A1"));
        assertFalse(MokukuProtocol.isMokukuII(null));
        assertFalse(MokukuProtocol.isOriginalDevice(null));
        assertFalse(MokukuProtocol.isOriginalDevice("Other device"));
    }

    @Test public void countsUtf8BytesAndRejectsInvalidSettings() {
        byte[] packet = MokukuProtocol.stringMessage(7, "网络", 32);
        assertEquals(6, packet[1]);
        assertEquals(8, packet.length);
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.stringMessage(7, "网网网网网网网网网网网", 32));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.stringMessage(8, "", 64));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.stringMessage(9, "a\0b", 255));
        assertArrayEquals(new byte[] {1, 67}, MokukuProtocol.command(67));
    }

    @Test public void decodesFreeStorageBeforeTotal() {
        assertArrayEquals(new long[] {120, 512}, MokukuProtocol.storage(reply(61, "120/512")));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.storage(reply(61, "512/120")));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.storage(reply(61, "0/0")));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.voiceModel(new byte[] {57, 1, 3}));
        assertTrue(MokukuProtocol.voiceModel(new byte[] {57, 1, 2}).contains("Mandarin"));
    }

    @Test public void listsOnlySafeRecordingNamesNewestFirst() {
        assertEquals(Arrays.asList("mobili_128.record", "mobili_12.record", "mobili_2.record"),
                MokukuProtocol.recordings(Arrays.asList("mobili_2.record,mobili_12.record,../secret,mobili_128.record,mobili_2.record".split(","))));
        assertEquals(Arrays.asList("20261116_143512_1.rec", "20261116_143512.rec", "20251231_235959.rec",
                        "recording_10.rec", "recording_9.rec", "mobili_3.record"),
                MokukuProtocol.recordings(Arrays.asList(("recording_9.rec,mobili_3.record,20251231_235959.rec,"
                        + "20261116_143512.rec,recording_10.rec,20261116_143512_1.rec,2026_1.rec,20261116_143512.record").split(","))));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.recordPath("../mobili_1.record", false));
        assertEquals("/sdcard/record/mobili_1.record", MokukuProtocol.recordPath("mobili_1.record", true));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.textReply(new byte[] {60, 0}, 60));
    }

    @Test public void framesSectorTransferCommandsWithCrc() {
        assertEquals(0x31c3, MokukuProtocol.crc16("123456789".getBytes(StandardCharsets.US_ASCII), 0, 9, 0));
        byte[] start = MokukuProtocol.startDownload("/sd/record/mobili_1.record", 0x01020304);
        byte[] body = MokukuProtocol.withoutCrc(start, start.length);
        assertNotNull(body);
        assertEquals(MokukuProtocol.COMMAND_START_DOWNLOAD, body[0]);
        assertEquals(0x01020304, ByteBuffer.wrap(body, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
        assertEquals("/sd/record/mobili_1.record", new String(body, 6, body.length - 6, StandardCharsets.UTF_8));
        start[3] ^= 1;
        assertNull(MokukuProtocol.withoutCrc(start, start.length));
        assertArrayEquals(new byte[] {2, 0, 1, 0, 3, 0}, MokukuProtocol.withoutCrc(MokukuProtocol.acknowledgement(2, 1, 3), 8));
    }

    @Test public void encodesTripDeletionRanges() {
        assertArrayEquals(new byte[] {58, 1, 0, 0, 0, 0x29, 1, 0, 0}, MokukuProtocol.deleteTrips(false, 1, 297));
        assertArrayEquals(new byte[] {2, 17, 1, 0, 0, 0, 0x29, 1, 0, 0}, MokukuProtocol.deleteTrips(true, 1, 297));
    }

    @Test public void decodesDownloadStartRepliesAndRefusals() throws Exception {
        assertArrayEquals(new long[] {1000, 4096, 0xbeef}, MokukuProtocol.downloadStarted(startReply(0, 1000, 4096)));
        assertThrows(IllegalStateException.class, () -> MokukuProtocol.downloadStarted(startReply(1, 0, 0)));
        assertThrows(java.io.FileNotFoundException.class, () -> MokukuProtocol.downloadStarted(startReply(2, 0, 0)));
        byte[] corrupt = startReply(0, 1000, 4096);
        corrupt[7] ^= 1;
        assertNull(MokukuProtocol.downloadStarted(corrupt));
    }

    private static byte[] startReply(int status, int size, int sectorSize) {
        byte[] frame = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 3).putShort((short) 2)
                .putShort((short) status).putInt(size).putInt(sectorSize).putShort((short) 0xbeef).array();
        int crc = MokukuProtocol.crc16(frame, 0, 16, 0);
        frame[16] = (byte) crc;
        frame[17] = (byte) (crc >> 8);
        return frame;
    }

    @Test public void framesMokukuIIPropertiesAndDecodesReplies() {
        byte[] list = MokukuProtocol.listDirectory(258, "/sdcard/record");
        assertArrayEquals(new byte[] {3, 10, 2, 1}, Arrays.copyOf(list, 4));
        assertEquals("/sdcard/record", new String(list, 4, list.length - 4, StandardCharsets.UTF_8));
        assertEquals(3 << 8 | 10, MokukuProtocol.replyKey(list, true));
        assertEquals(3, MokukuProtocol.replyKey(new byte[] {3}, false));
        assertArrayEquals(new byte[] {'2', '.', '0'}, MokukuProtocol.propertyValue(new byte[] {3, 1, 0, '2', '.', '0'}));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.propertyValue(new byte[] {2, 13, 4}));
        assertEquals("DF:01:00:00:4A:01", MokukuProtocol.describeProperty(2, new byte[] {(byte) 0xdf, 1, 0, 0, 0x4a, 1}));
        assertEquals("330/479", MokukuProtocol.describeProperty(7, new byte[] {(byte) 0xdf, 1, 0, 0, 0x4a, 1, 0, 0}));
        assertEquals("On", MokukuProtocol.describeProperty(6, new byte[] {1}));
        assertEquals("English", MokukuProtocol.describeProperty(9, "en".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Arrays.asList("mobili_1.record", "mobili_2.record"),
                MokukuProtocol.directoryNames("mobili_1.record,mobili_2.record".getBytes(StandardCharsets.UTF_8)));
        assertTrue(MokukuProtocol.directoryNames(new byte[0]).isEmpty());
    }

    @Test public void splitsFirmwareSectorsIntoCrcCheckedPackets() {
        byte[] image = new byte[25];
        for (int i = 0; i < image.length; i++) image[i] = (byte) i;
        java.util.List<byte[]> packets = MokukuProtocol.sectorPackets(1, image, 10, 15, 13);
        assertEquals(2, packets.size());
        assertArrayEquals(new byte[] {1, 0, 0}, Arrays.copyOf(packets.get(0), 3));
        assertEquals(MokukuProtocol.LAST_PACKET, MokukuProtocol.unsigned(packets.get(1)[2]));
        byte[] sector = new byte[17];
        System.arraycopy(packets.get(0), 3, sector, 0, 10);
        System.arraycopy(packets.get(1), 3, sector, 10, 7);
        assertArrayEquals(Arrays.copyOfRange(image, 10, 25), MokukuProtocol.withoutCrc(sector, sector.length));
        assertArrayEquals(new int[] {2, 1, 3}, MokukuProtocol.sectorAcknowledgement(MokukuProtocol.acknowledgement(2, 1, 3)));
        byte[] start = MokukuProtocol.withoutCrc(MokukuProtocol.startFirmwareUpdate(1000), 8);
        assertArrayEquals(new byte[] {(byte) 200, 0, (byte) 0xe8, 3, 0, 0}, start);
    }

    @Test public void encodesVehicleDataWithRpmTimesFourBigEndian() {
        assertArrayEquals(new byte[] {1, 120, 0x2e, (byte) 0xe0, 75}, MokukuProtocol.vehicleData(120, 3000, 75));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.vehicleData(256, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.vehicleData(0, 16384, 0));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.vehicleData(0, 0, 101));
    }

    @Test public void encodesSelectedWallClockWithoutVehicleData() {
        byte[] packet = MokukuProtocol.dateTime(2026, 10, 13, 9, 41);
        assertEquals(11, packet.length);
        assertEquals(255, MokukuProtocol.unsigned(packet[1]));
        assertEquals(0, packet[4]);
        long seconds = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(5) & 0xffffffffL;
        assertEquals(9 * 3600 + 41 * 60, seconds % 86400);
        assertEquals("2026-11-13T09:41:00Z", java.time.Instant.ofEpochSecond(seconds).toString());
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.dateTime(2107, 0, 1, 0, 0));
    }

    private static byte[] reply(int id, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(text.length + 2).put((byte) id).put((byte) 1).put(text).array();
    }
}

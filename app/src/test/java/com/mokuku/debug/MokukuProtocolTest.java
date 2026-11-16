package com.mokuku.debug;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MokukuProtocolTest {
    @Test public void selectsOriginalMokukuOnly() {
        assertTrue(MokukuProtocol.isOriginalDevice("mokuku_73A1"));
        assertFalse(MokukuProtocol.isOriginalDevice("mokukuii73A1"));
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

    @Test public void listsOnlySafeRecordingNamesInNumericOrder() {
        assertEquals(Arrays.asList("mobili_128.record", "mobili_12.record", "mobili_2.record"),
                MokukuProtocol.recordings(reply(60, "mobili_2.record,mobili_12.record,../secret,mobili_128.record,mobili_2.record")));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.recordPath("../mobili_1.record"));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.textReply(new byte[] {60, 0}, 60));
    }

    @Test public void framesSectorTransferCommandsWithCrc() {
        assertEquals(0x31c3, MokukuProtocol.crc16("123456789".getBytes(StandardCharsets.US_ASCII), 0, 9, 0));
        byte[] start = MokukuProtocol.startDownload("/sd/record/mobili_1.record");
        byte[] body = MokukuProtocol.withoutCrc(start, start.length);
        assertNotNull(body);
        assertEquals(MokukuProtocol.COMMAND_START_DOWNLOAD, body[0]);
        assertEquals("/sd/record/mobili_1.record", new String(body, 6, body.length - 6, StandardCharsets.UTF_8));
        start[3] ^= 1;
        assertNull(MokukuProtocol.withoutCrc(start, start.length));
        assertArrayEquals(new byte[] {2, 0, 1, 0, 3, 0}, MokukuProtocol.withoutCrc(MokukuProtocol.acknowledgement(2, 1, 3), 8));
    }

    @Test public void decodesDownloadStartRepliesAndRefusals() {
        assertArrayEquals(new long[] {1000, 4096}, MokukuProtocol.downloadStarted(startReply(0, 1000, 4096)));
        assertThrows(IllegalStateException.class, () -> MokukuProtocol.downloadStarted(startReply(1, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.downloadStarted(startReply(2, 0, 0)));
        byte[] corrupt = startReply(0, 1000, 4096);
        corrupt[7] ^= 1;
        assertNull(MokukuProtocol.downloadStarted(corrupt));
    }

    private static byte[] startReply(int status, int size, int sectorSize) {
        byte[] frame = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 3).putShort((short) 2)
                .putShort((short) status).putInt(size).putInt(sectorSize).putShort((short) 0).array();
        int crc = MokukuProtocol.crc16(frame, 0, 16, 0);
        frame[16] = (byte) crc;
        frame[17] = (byte) (crc >> 8);
        return frame;
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

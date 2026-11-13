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

    @Test public void validatesFileSizeOffsetAndPacketLength() {
        byte[] opened = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).put((byte) 65).put((byte) 1).putInt(0xf0000001).putInt(3).array();
        assertArrayEquals(new long[] {0xf0000001L, 3}, MokukuProtocol.openFile(opened));
        byte[] packet = new byte[] {66, 3, 0, 0, 0, 0, 10, 20, 30};
        assertArrayEquals(new byte[] {10, 20, 30}, MokukuProtocol.fileChunk(packet, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.fileChunk(packet, 1, 4));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.fileChunk(packet, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.fileChunk(new byte[] {66, 0, 0, 0, 0, 0}, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> MokukuProtocol.fileChunk(Arrays.copyOf(packet, 8), 0, 3));
        assertEquals(14, MokukuProtocol.unsigned(MokukuProtocol.readFile(1, 0, 23)[1]));
        assertEquals(255, MokukuProtocol.unsigned(MokukuProtocol.readFile(1, 0, 517)[1]));
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

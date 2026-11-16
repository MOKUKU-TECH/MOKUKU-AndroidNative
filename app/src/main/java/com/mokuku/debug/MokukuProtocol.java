package com.mokuku.debug;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;

final class MokukuProtocol {
    static final UUID SERVICE = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    static final UUID DATA = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");
    static final UUID MESSAGE = UUID.fromString("d222e154-1a80-4e71-9a63-2aa2c0ce0a8c");
    static final UUID FILE_COMMAND = UUID.fromString("18bf25fe-c5c8-40fe-ab13-9aa144d65b69");
    static final UUID FILE_DATA = UUID.fromString("737cbd9b-848b-4fad-87b8-5c3b9f75768d");
    static final UUID NOTIFICATIONS = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final String RECORD_DIRECTORY = "/sd/record";
    private static final Pattern RECORDING_NAME = Pattern.compile("mobili_[0-9]+\\.record");

    private MokukuProtocol() {}

    static boolean isOriginalDevice(String name) {
        return name != null && name.startsWith("mokuku_");
    }

    static byte[] stringMessage(int id, String value, int maximumBytes) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        if (text.length == 0 || text.length > maximumBytes || text.length > 255 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Enter between 1 and " + maximumBytes + " UTF-8 bytes.");
        }
        return ByteBuffer.allocate(text.length + 2).put((byte) id).put((byte) text.length).put(text).array();
    }

    static byte[] command(int command) {
        return new byte[] {1, (byte) command};
    }

    static String textReply(byte[] packet, int expectedId) {
        if (packet.length < 2 || unsigned(packet[0]) != expectedId || packet[1] != 1) {
            throw new IllegalArgumentException("MOKUKU could not complete the request.");
        }
        return new String(packet, 2, packet.length - 2, StandardCharsets.UTF_8);
    }

    static String voiceModel(byte[] packet) {
        textReply(packet, 57);
        if (packet.length != 3) throw new IllegalArgumentException("Invalid voice model response.");
        switch (packet[2]) {
            case 0: return "Other or unavailable model";
            case 1: return "wn10_himokuku · English";
            case 2: return "wn10_himokuku · Mandarin";
            default: throw new IllegalArgumentException("Unknown voice model response.");
        }
    }

    static long[] storage(byte[] packet) {
        String[] values = textReply(packet, 61).split("/", -1);
        try {
            if (values.length != 2) throw new NumberFormatException();
            long free = Long.parseLong(values[0]);
            long total = Long.parseLong(values[1]);
            if (free < 0 || total <= 0 || free > total) throw new NumberFormatException();
            return new long[] {free, total};
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("SD card information is unavailable.");
        }
    }

    static String recordPath(String name) {
        if (!isRecordingName(name)) {
            throw new IllegalArgumentException("Invalid recording filename.");
        }
        return RECORD_DIRECTORY + "/" + name;
    }

    static boolean isRecordingName(String name) { return RECORDING_NAME.matcher(name).matches(); }

    static List<String> recordings(byte[] packet) {
        String text = textReply(packet, 60);
        List<String> names = new ArrayList<>();
        for (String name : text.split(",")) {
            if (isRecordingName(name) && !names.contains(name)) names.add(name);
        }
        Collections.sort(names, (left, right) -> {
            String a = left.substring(7, left.length() - 7);
            String b = right.substring(7, right.length() - 7);
            int length = Integer.compare(b.length(), a.length());
            return length == 0 ? b.compareTo(a) : length;
        });
        return names;
    }

    static final int COMMAND_START_DOWNLOAD = 2;
    static final int COMMAND_REPLY = 3;
    static final int COMMAND_STOP = 4;
    static final int COMMAND_ACCEPTED = 0;
    static final int COMMAND_INVALID = 1;
    static final int SECTOR_OK = 0;
    static final int SECTOR_CRC_ERROR = 1;
    // Sequence number of a sector's last packet, which ends with the sector's CRC.
    static final int LAST_PACKET = 0xff;
    static final int PACKET_HEADER_SIZE = 3;

    // CRC-16/XMODEM, as the firmware's esp_rom_crc16_be.
    static int crc16(byte[] bytes, int offset, int length, int crc) {
        for (int i = offset; i < offset + length; i++) {
            crc ^= unsigned(bytes[i]) << 8;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
        }
        return crc & 0xffff;
    }

    private static byte[] withCrc(ByteBuffer body) {
        int length = body.position();
        int crc = crc16(body.array(), 0, length, 0);
        return Arrays.copyOf(body.putShort((short) crc).array(), length + 2);
    }

    /** The frame body without its trailing CRC, or null when the CRC does not match. */
    static byte[] withoutCrc(byte[] frame, int length) {
        if (length < 2) return null;
        int crc = unsigned(frame[length - 2]) | unsigned(frame[length - 1]) << 8;
        return crc == crc16(frame, 0, length - 2, 0) ? Arrays.copyOf(frame, length - 2) : null;
    }

    static byte[] startDownload(String path) {
        byte[] text = path.getBytes(StandardCharsets.UTF_8);
        return withCrc(ByteBuffer.allocate(text.length + 8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) COMMAND_START_DOWNLOAD).putInt(0).put(text));
    }

    static byte[] stopTransfer() {
        return withCrc(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) COMMAND_STOP));
    }

    static byte[] acknowledgement(int sector, int status, int nextSector) {
        return withCrc(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) sector).putShort((short) status).putShort((short) nextSector));
    }

    /**
     * File size and sector size from an accepted start-download reply; null for other frames.
     * Throws with the refusal when the device refused.
     */
    static long[] downloadStarted(byte[] frame) {
        byte[] body = withoutCrc(frame, frame.length);
        if (body == null || body.length < 6) return null;
        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        if ((buffer.getShort(0) & 0xffff) != COMMAND_REPLY || (buffer.getShort(2) & 0xffff) != COMMAND_START_DOWNLOAD) return null;
        int status = buffer.getShort(4) & 0xffff;
        if (status == COMMAND_INVALID) throw new IllegalStateException("MOKUKU refused the recording. Check the access code.");
        if (status != COMMAND_ACCEPTED || body.length < 16) throw new IllegalArgumentException("The recording is missing or unreadable.");
        long size = buffer.getInt(6) & 0xffffffffL;
        long sectorSize = buffer.getInt(10) & 0xffffffffL;
        if (size > Integer.MAX_VALUE || sectorSize == 0) throw new IllegalArgumentException("The recording is too large.");
        return new long[] {size, sectorSize};
    }

    static byte[] dateTime(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.setLenient(false);
        calendar.set(year, month, day, hour, minute, 0);
        long seconds = calendar.getTimeInMillis() / 1000;
        if (seconds <= 0 || seconds > 0xffffffffL) throw new IllegalArgumentException("Choose a date between 1970 and 2106.");
        // Firmware uses the same value for calendar fields and seconds modulo a day.
        return ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 1).put((byte) 255).putShort((short) 0).put((byte) 0)
                .putInt((int) seconds).put((byte) 0).put((byte) 0).array();
    }

    static int unsigned(byte value) { return value & 255; }
}

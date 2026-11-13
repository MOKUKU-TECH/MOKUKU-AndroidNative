package com.mokuku.debug;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    static long[] openFile(byte[] packet) {
        if (packet.length != 10 || packet[0] != 65 || packet[1] != 1) {
            throw new IllegalArgumentException("Invalid file response.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        long key = buffer.getInt(2) & 0xffffffffL;
        long size = buffer.getInt(6) & 0xffffffffL;
        if (key == 0 || size == 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The recording is missing, empty, or too large.");
        }
        return new long[] {key, size};
    }

    static byte[] readFile(long key, long offset, int mtu) {
        int length = Math.min(255, mtu - 9);
        if (length <= 0) throw new IllegalArgumentException("The Bluetooth packet size is too small.");
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 66).put((byte) length).putInt((int) key).putInt((int) offset).array();
    }

    static byte[] fileChunk(byte[] packet, long offset, long total) {
        if (packet.length < 6 || packet[0] != 66) throw new IllegalArgumentException("Invalid recording packet.");
        int length = unsigned(packet[1]);
        long actualOffset = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(2) & 0xffffffffL;
        if (packet.length != length + 6 || actualOffset != offset || length == 0 || offset + length > total) {
            throw new IllegalArgumentException("Recording data was incomplete or out of order. Download again.");
        }
        byte[] bytes = new byte[length];
        System.arraycopy(packet, 6, bytes, 0, length);
        return bytes;
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

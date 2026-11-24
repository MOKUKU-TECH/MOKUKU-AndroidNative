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
    // Start time YYYYMMDD_HHMMSS, recording_N before the time is known, and older mobili_N.record.
    private static final Pattern RECORDING_NAME = Pattern.compile(
            "[0-9]{8}_[0-9]{6}(_[0-9]{1,9})?\\.rec|recording_[0-9]{1,9}\\.rec|mobili_[0-9]{1,9}\\.record");

    private MokukuProtocol() {}

    static boolean isOriginalDevice(String name) {
        return name != null && name.startsWith("mokuku_");
    }

    // The connected left eye relays these to the right eye over UART and keeps Bluetooth up.
    static boolean isRightEyeUpdate(int command) {
        return command == 67 || command == 69 || command == 71;
    }

    static boolean isMokukuII(String name) {
        return name != null && name.startsWith("mokukuii");
    }

    static String recordDirectory(boolean mokukuII) {
        return mokukuII ? "/sdcard/record" : "/sd/record";
    }

    static String tripDirectory(boolean mokukuII) {
        return mokukuII ? "/sdcard/trip" : "/sd/trip";
    }

    static byte[] utf8(String value, int maximumBytes) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        if (text.length == 0 || text.length > maximumBytes || text.length > 255 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Enter between 1 and " + maximumBytes + " UTF-8 bytes.");
        }
        return text;
    }

    static byte[] stringMessage(int id, String value, int maximumBytes) {
        byte[] text = utf8(value, maximumBytes);
        return ByteBuffer.allocate(text.length + 2).put((byte) id).put((byte) text.length).put(text).array();
    }

    /** Matches a request to its reply: the message ID, or MOKUKU II's message type and property. */
    static int replyKey(byte[] packet, boolean mokukuII) {
        if (!mokukuII) return unsigned(packet[0]);
        return packet.length < 2 ? -2 : unsigned(packet[0]) << 8 | unsigned(packet[1]);
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

    static String recordPath(String name, boolean mokukuII) {
        if (!isRecordingName(name)) {
            throw new IllegalArgumentException("Invalid recording filename.");
        }
        return recordDirectory(mokukuII) + "/" + name;
    }

    static boolean isRecordingName(String name) { return RECORDING_NAME.matcher(name).matches(); }

    /** Valid recording names from a directory listing, newest first. */
    static List<String> recordings(List<String> listed) {
        List<String> names = new ArrayList<>();
        for (String name : listed) {
            if (isRecordingName(name) && !names.contains(name)) names.add(name);
        }
        Collections.sort(names, (left, right) -> {
            int group = Integer.compare(nameGroup(left), nameGroup(right));
            if (group != 0) return group;
            if (nameGroup(left) == 0) return right.compareTo(left);
            return Long.compare(nameNumber(right), nameNumber(left));
        });
        return names;
    }

    // Newest first: timed names, then recording_N, then older mobili_N.
    private static int nameGroup(String name) {
        if (Character.isDigit(name.charAt(0))) return 0;
        return name.startsWith("recording_") ? 1 : 2;
    }

    private static long nameNumber(String name) {
        return Long.parseLong(name.substring(name.indexOf('_') + 1, name.indexOf('.')));
    }

    // MOKUKU II message types and properties.
    static final int TYPE_SET = 2;
    static final int TYPE_GET = 3;
    static final int PROPERTY_VERSION = 1;
    static final int PROPERTY_MAC_ADDRESS = 2;
    static final int PROPERTY_WIFI_NAME = 3;
    static final int PROPERTY_WIFI_PASSWORD = 4;
    static final int PROPERTY_REMOTE_ADDRESS = 5;
    static final int PROPERTY_OBD_ENABLED = 6;
    static final int PROPERTY_SD_CARD = 7;
    static final int PROPERTY_WAKE_WORD_MODEL = 8;
    static final int PROPERTY_LANGUAGE = 9;
    static final int PROPERTY_DIRECTORY = 10;
    static final int PROPERTY_DELETE_RECORDINGS = 13;
    static final int PROPERTY_PANEL_ACTION = 15;
    static final int PROPERTY_FIRMWARE_SLOTS = 16;
    static final int PROPERTY_DELETE_TRIPS = 17;
    static final int PROPERTY_TRIP_RECORDING = 18;
    // Message 59; also the information key for MOKUKU II property 18.
    static final int TRIP_RECORDING = 59;
    static final int PROPERTY_ROLLBACK = 200;
    static final int MOKUKU_II_STRING_MAXIMUM_BYTES = 64;

    static byte[] property(int type, int property, byte[] value) {
        return ByteBuffer.allocate(value.length + 2).put((byte) type).put((byte) property).put(value).array();
    }

    static byte[] listDirectory(int first, String path) {
        byte[] text = path.getBytes(StandardCharsets.UTF_8);
        return property(TYPE_GET, PROPERTY_DIRECTORY, ByteBuffer.allocate(text.length + 2)
                .order(ByteOrder.LITTLE_ENDIAN).putShort((short) first).put(text).array());
    }

    /** Deletes MOKUKU's trips first to last (inclusive); MOKUKU keeps the trip it is recording. */
    static byte[] deleteTrips(boolean mokukuII, int first, int last) {
        ByteBuffer range = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(first).putInt(last);
        if (mokukuII) return property(TYPE_SET, PROPERTY_DELETE_TRIPS, range.array());
        return ByteBuffer.allocate(9).put((byte) 58).put(range.array()).array();
    }

    static byte[] setTripRecording(boolean mokukuII, boolean enabled) {
        byte value = (byte) (enabled ? 1 : 0);
        if (mokukuII) return property(TYPE_SET, PROPERTY_TRIP_RECORDING, new byte[] {value});
        return new byte[] {TRIP_RECORDING, value};
    }

    /** The original MOKUKU's `59, 1, enabled` reply as display text. */
    static String tripRecording(byte[] packet) {
        textReply(packet, TRIP_RECORDING);
        if (packet.length != 3) throw new IllegalArgumentException("Invalid trip recording state.");
        return onOff(packet[2]);
    }

    private static String onOff(byte value) {
        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid on/off state.");
        return value == 0 ? "Off" : "On";
    }

    /** The value of a MOKUKU II set/get reply; throws with the reason for a result other than OK. */
    static byte[] propertyValue(byte[] reply) {
        if (reply.length < 3) throw new IllegalArgumentException("Invalid MOKUKU II reply.");
        switch (reply[2]) {
            case 0: return Arrays.copyOfRange(reply, 3, reply.length);
            case 1: throw new IllegalArgumentException("MOKUKU II's firmware does not support this request.");
            case 3: throw new IllegalArgumentException("MOKUKU II rejected the value.");
            case 4: throw new IllegalArgumentException("MOKUKU II can't do this now: no SD card, a recording in progress, or the action is unavailable.");
            default: throw new IllegalArgumentException("MOKUKU II could not complete the request (" + reply[2] + ").");
        }
    }

    /** A MOKUKU II get value as display text; SD card space is free/total MB, as the original reply. */
    static String describeProperty(int property, byte[] value) {
        switch (property) {
            case PROPERTY_MAC_ADDRESS: {
                if (value.length != 6) throw new IllegalArgumentException("Invalid MAC address.");
                StringBuilder text = new StringBuilder();
                for (byte part : value) text.append(text.length() == 0 ? "" : ":").append(String.format("%02X", unsigned(part)));
                return text.toString();
            }
            case PROPERTY_SD_CARD: {
                if (value.length != 8) throw new IllegalArgumentException("SD card information is unavailable.");
                ByteBuffer sizes = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
                long total = sizes.getInt(0) & 0xffffffffL;
                long free = sizes.getInt(4) & 0xffffffffL;
                if (total == 0 || free > total) throw new IllegalArgumentException("SD card information is unavailable.");
                return free + "/" + total;
            }
            case PROPERTY_OBD_ENABLED:
            case PROPERTY_TRIP_RECORDING:
                if (value.length != 1) throw new IllegalArgumentException("Invalid on/off state.");
                return onOff(value[0]);
            case PROPERTY_LANGUAGE: {
                String language = new String(value, StandardCharsets.UTF_8);
                return "en".equals(language) ? "English" : "cn".equals(language) ? "Mandarin" : language;
            }
            default:
                return new String(value, StandardCharsets.UTF_8).trim();
        }
    }

    static List<String> directoryNames(byte[] value) {
        if (value.length == 0) return Collections.emptyList();
        return Arrays.asList(new String(value, StandardCharsets.UTF_8).split(","));
    }

    static final int COMMAND_START_DOWNLOAD = 2;
    static final int COMMAND_REPLY = 3;
    static final int COMMAND_STOP = 4;
    static final int COMMAND_START_FIRMWARE_UPDATE = 200;
    static final int COMMAND_ACCEPTED = 0;
    static final int COMMAND_INVALID = 1;
    static final int COMMAND_UNAVAILABLE = 2;
    static final int SECTOR_OK = 0;
    static final int SECTOR_CRC_ERROR = 1;
    static final int SECTOR_WRITE_ERROR = 3;
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

    static byte[] startFirmwareUpdate(int size) {
        return withCrc(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) COMMAND_START_FIRMWARE_UPDATE).putInt(size));
    }

    /** Starts a download at offset, the number of bytes the phone already has. */
    static byte[] startDownload(String path, long offset) {
        byte[] text = path.getBytes(StandardCharsets.UTF_8);
        return withCrc(ByteBuffer.allocate(text.length + 8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) COMMAND_START_DOWNLOAD).putInt((int) offset).put(text));
    }

    static byte[] stopTransfer() {
        return withCrc(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) COMMAND_STOP));
    }

    static byte[] acknowledgement(int sector, int status, int nextSector) {
        return withCrc(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) sector).putShort((short) status).putShort((short) nextSector));
    }

    /** The body of a reply to this command, or null for other frames. */
    private static ByteBuffer commandReply(byte[] frame, int command) {
        byte[] body = withoutCrc(frame, frame.length);
        if (body == null || body.length < 6) return null;
        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        if ((buffer.getShort(0) & 0xffff) != COMMAND_REPLY || (buffer.getShort(2) & 0xffff) != command) return null;
        return buffer;
    }

    /**
     * File size, sector size, and the CRC of the bytes before the offset from an accepted
     * start-download reply; null for other frames. Throws with the refusal when the device refused:
     * FileNotFoundException when the file cannot be opened.
     */
    static long[] downloadStarted(byte[] frame) throws java.io.FileNotFoundException {
        ByteBuffer buffer = commandReply(frame, COMMAND_START_DOWNLOAD);
        if (buffer == null) return null;
        int status = buffer.getShort(4) & 0xffff;
        if (status == COMMAND_INVALID) throw new IllegalStateException("MOKUKU refused the file. Check the access code.");
        if (status == COMMAND_UNAVAILABLE) throw new java.io.FileNotFoundException("The file is missing or unreadable.");
        if (status != COMMAND_ACCEPTED || buffer.limit() < 16) throw new IllegalArgumentException("Invalid download reply.");
        long size = buffer.getInt(6) & 0xffffffffL;
        long sectorSize = buffer.getInt(10) & 0xffffffffL;
        if (size > Integer.MAX_VALUE || sectorSize == 0) throw new IllegalArgumentException("The file is too large.");
        return new long[] {size, sectorSize, buffer.getShort(14) & 0xffff};
    }

    /** Sector size and largest packet from an accepted firmware-update reply; null for other frames. */
    static int[] firmwareUpdateStarted(byte[] frame) {
        ByteBuffer buffer = commandReply(frame, COMMAND_START_FIRMWARE_UPDATE);
        if (buffer == null) return null;
        if ((buffer.getShort(4) & 0xffff) != COMMAND_ACCEPTED || buffer.limit() < 12) {
            throw new IllegalArgumentException("MOKUKU II refused the firmware image. Check that it is a MOKUKU II app image.");
        }
        int sectorSize = buffer.getInt(6);
        int packetSize = buffer.getShort(10) & 0xffff;
        if (sectorSize <= 0 || packetSize <= PACKET_HEADER_SIZE) throw new IllegalArgumentException("Invalid firmware update reply.");
        return new int[] {sectorSize, packetSize};
    }

    /** Sector, status, and next sector of an acknowledgement; null when its CRC fails. */
    static int[] sectorAcknowledgement(byte[] frame) {
        byte[] body = withoutCrc(frame, frame.length);
        if (body == null || body.length != 6) return null;
        ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        return new int[] {buffer.getShort(0) & 0xffff, buffer.getShort(2) & 0xffff, buffer.getShort(4) & 0xffff};
    }

    /** A sector's packets of at most packetSize bytes; the last one ends with the sector's CRC. */
    static List<byte[]> sectorPackets(int sector, byte[] data, int offset, int length, int packetSize) {
        byte[] blob = withCrc(ByteBuffer.allocate(length + 2).order(ByteOrder.LITTLE_ENDIAN).put(data, offset, length));
        int chunk = packetSize - PACKET_HEADER_SIZE;
        List<byte[]> packets = new ArrayList<>();
        for (int start = 0; start < blob.length; start += chunk) {
            int end = Math.min(blob.length, start + chunk);
            packets.add(ByteBuffer.allocate(PACKET_HEADER_SIZE + end - start).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) sector).put((byte) (end == blob.length ? LAST_PACKET : packets.size()))
                    .put(blob, start, end - start).array());
        }
        return packets;
    }

    // The data packet's first five bytes; the original firmware would also set its clock from a longer one.
    static byte[] vehicleData(int speed, int rpm, int fuel) {
        if (speed < 0 || speed > 255) throw new IllegalArgumentException("Enter a speed from 0 to 255 km/h.");
        if (rpm < 0 || rpm > 16383) throw new IllegalArgumentException("Enter an RPM from 0 to 16383.");
        if (fuel < 0 || fuel > 100) throw new IllegalArgumentException("Enter fuel from 0 to 100%.");
        return ByteBuffer.allocate(5).put((byte) 1).put((byte) speed).putShort((short) (rpm * 4)).put((byte) fuel).array();
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

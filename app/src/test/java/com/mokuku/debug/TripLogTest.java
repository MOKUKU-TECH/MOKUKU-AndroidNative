package com.mokuku.debug;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class TripLogTest {
    private static final long START = 1794817815000L;

    // Encoded as IDF_SHARED/backend/trip_log.c does.
    private static byte[] withCrc(ByteBuffer record) {
        byte[] bytes = record.array();
        int crc = MokukuProtocol.crc16(bytes, 0, bytes.length - 2, 0xffff);
        bytes[bytes.length - 2] = (byte) crc;
        bytes[bytes.length - 1] = (byte) (crc >> 8);
        return bytes;
    }

    private static byte[] header() { return header(START, 31.23, 121.47); }

    private static byte[] header(long start, double latitude, double longitude) {
        return withCrc(ByteBuffer.allocate(TripLog.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .put("MOKUTRP\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put((byte) 1)
                .putLong(start).putDouble(latitude).putDouble(longitude).putDouble(10));
    }

    private static byte[] point(int timeMilliseconds, int eastDecimeters, int northDecimeters, int speedCentimetersPerSecond) {
        return point(timeMilliseconds, eastDecimeters, northDecimeters, speedCentimetersPerSecond, 255);
    }

    private static byte[] point(int timeMilliseconds, int eastDecimeters, int northDecimeters, int speedCentimetersPerSecond, int obdKmh) {
        return withCrc(ByteBuffer.allocate(TripLog.POINT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0).putInt(timeMilliseconds).putInt(eastDecimeters).putInt(northDecimeters).putInt(0)
                .putShort((short) speedCentimetersPerSecond).put((byte) obdKmh).putShort((short) -32768).put((byte) 9).put((byte) 12));
    }

    @Test public void routeSpeedPrefersObdOverGps() {
        TripLog log = TripLog.parse(trip(header(), point(0, 0, 0, 1000, 50), point(1000, 100, 0, 1000)));
        assertEquals(50, log.speedKmh[0], 1e-9);
        assertEquals(36, log.speedKmh[1], 1e-9);
    }

    private static byte[] trip(byte[]... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) bytes.write(part, 0, part.length);
        return bytes.toByteArray();
    }

    /** A sample trip: a winding drive of count points a second apart near Shanghai. */
    static byte[] sampleTrip(long start, int seed, int count) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(header(start, 31.2 + seed * 0.01, 121.45 + seed * 0.01), 0, TripLog.HEADER_SIZE);
        double east = 0, north = 0, heading = seed;
        for (int i = 0; i < count; i++) {
            if (i % 30 == 0) heading += 0.25 * Math.sin(seed * 3 + i / 400.0);
            double speed = 12 + 6 * Math.sin(i / 40.0 + seed);
            byte[] point = point(i * 1000, (int) Math.round(east * 10), (int) Math.round(north * 10), (int) Math.round(speed * 100));
            bytes.write(point, 0, point.length);
            east += Math.cos(heading) * speed;
            north += Math.sin(heading) * speed;
        }
        return bytes.toByteArray();
    }

    /** Saves bytes as the device's trip, as a sync does. */
    static void savePhoneTrip(TripStore store, String address, String name, int number, byte[] bytes) throws Exception {
        java.io.File download = java.io.File.createTempFile("trip", ".bin", org.robolectric.RuntimeEnvironment.getApplication().getCacheDir());
        java.nio.file.Files.write(download.toPath(), bytes);
        store.save(address, name, number, download);
    }

    @Test public void parsesPointsIntoLatitudeLongitudeAndSummary() {
        // 0.001° of longitude is about 95.3 m east here, 0.001° of latitude about 110.9 m north.
        TripLog log = TripLog.parse(trip(header(), point(0, 0, 0, 0), point(60000, 953, 0, 1000), point(120000, 953, 1109, 2000)));
        assertEquals(START, log.startUnixMilliseconds);
        assertEquals(3, log.pointCount());
        double[][] points = log.latitudeLongitudes();
        assertEquals(31.23, points[0][0], 1e-9);
        assertEquals(121.47, points[0][1], 1e-9);
        assertEquals(121.471, points[1][1], 1e-5);
        assertEquals(31.23, points[1][0], 1e-5);
        assertEquals(31.231, points[2][0], 1e-5);
        assertEquals(206.2, log.distanceMeters, 0.01);
        assertEquals(120000, log.durationMilliseconds);
        assertEquals(72, log.maximumGpsSpeedKmh, 1e-9);
        assertEquals(206.2 / 120 * 3.6, log.averageSpeedKmh(), 1e-9);
    }

    @Test public void stopsAtADamagedOrPartialRecord() {
        byte[] damaged = point(2000, 30, 0, 0);
        damaged[5] ^= 1;
        assertEquals(2, TripLog.parse(trip(header(), point(0, 0, 0, 0), point(1000, 10, 0, 0), damaged, point(3000, 40, 0, 0))).pointCount());
        byte[] partial = java.util.Arrays.copyOf(point(1000, 10, 0, 0), 20);
        assertEquals(1, TripLog.parse(trip(header(), point(0, 0, 0, 0), partial)).pointCount());
        assertEquals(0, TripLog.parse(header()).durationMilliseconds);
    }

    @Test public void rejectsAnInvalidHeader() {
        byte[] header = header();
        header[20] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> TripLog.parse(header));
        assertThrows(IllegalArgumentException.class, () -> TripLog.parse(new byte[10]));
        byte[] version = header();
        version[8] = 2;
        assertThrows(IllegalArgumentException.class, () -> TripLog.parse(version));
    }
}

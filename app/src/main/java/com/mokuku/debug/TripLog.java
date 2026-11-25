package com.mokuku.debug;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A MOKUKU trip file (MobiliMOKUKU doc/TRIP_RECORDING.md): header, then points until the first bad record. */
final class TripLog {
    static final int HEADER_SIZE = 43;
    static final int POINT_SIZE = 26;
    private static final byte[] MAGIC = {'M', 'O', 'K', 'U', 'T', 'R', 'P', 0};
    private static final int FORMAT_VERSION = 1;
    private static final double SEMI_MAJOR_AXIS_METERS = 6378137.0;
    private static final double FLATTENING = 1 / 298.257223563;
    private static final double ECCENTRICITY_SQUARED = FLATTENING * (2 - FLATTENING);

    final long startUnixMilliseconds;
    final double originLatitude;
    final double originLongitude;
    final double originAltitude;
    // One entry per point, in meters from the origin.
    final double[] eastMeters;
    final double[] northMeters;
    final double[] upMeters;
    final long durationMilliseconds;
    final double distanceMeters;
    final double maximumGpsSpeedKmh;
    final TripAnalysis analysis;

    private TripLog(ByteBuffer header, ByteBuffer points, int count) {
        startUnixMilliseconds = header.getLong(9);
        originLatitude = header.getDouble(17);
        originLongitude = header.getDouble(25);
        originAltitude = header.getDouble(33);
        eastMeters = new double[count];
        northMeters = new double[count];
        upMeters = new double[count];
        double[] timeSeconds = new double[count], gpsSpeed = new double[count], yawRate = new double[count];
        double distance = 0, maximumSpeed = 0;
        for (int i = 0; i < count; i++) {
            int record = i * POINT_SIZE;
            timeSeconds[i] = (points.getInt(record + 1) & 0xffffffffL) / 1000.0;
            eastMeters[i] = points.getInt(record + 5) / 10.0;
            northMeters[i] = points.getInt(record + 9) / 10.0;
            upMeters[i] = points.getInt(record + 13) / 10.0;
            gpsSpeed[i] = (points.getShort(record + 17) & 0xffff) / 100.0;
            maximumSpeed = Math.max(maximumSpeed, gpsSpeed[i] * 3.6);
            short yaw = points.getShort(record + 20);
            yawRate[i] = yaw == Short.MIN_VALUE ? Double.NaN : yaw / 1000.0;
            if (i > 0) distance += Math.hypot(eastMeters[i] - eastMeters[i - 1], northMeters[i] - northMeters[i - 1]);
        }
        durationMilliseconds = count == 0 ? 0 : points.getInt((count - 1) * POINT_SIZE + 1) & 0xffffffffL;
        distanceMeters = distance;
        maximumGpsSpeedKmh = maximumSpeed;
        analysis = new TripAnalysis(timeSeconds, gpsSpeed, yawRate, distance, maximumSpeed);
    }

    /** Latitude and longitude in degrees of each point, for the map. */
    double[][] latitudeLongitudes() {
        double[] origin = earthCentered(originLatitude, originLongitude, originAltitude);
        double sinLatitude = Math.sin(Math.toRadians(originLatitude)), cosLatitude = Math.cos(Math.toRadians(originLatitude));
        double sinLongitude = Math.sin(Math.toRadians(originLongitude)), cosLongitude = Math.cos(Math.toRadians(originLongitude));
        double[][] points = new double[pointCount()][];
        for (int i = 0; i < points.length; i++) {
            // ENU back to Earth-centered, then to latitude and longitude.
            double east = eastMeters[i], north = northMeters[i], up = upMeters[i];
            double towardEquator = -sinLatitude * north + cosLatitude * up;
            points[i] = geodetic(origin[0] - sinLongitude * east + cosLongitude * towardEquator,
                    origin[1] + cosLongitude * east + sinLongitude * towardEquator,
                    origin[2] + cosLatitude * north + sinLatitude * up);
        }
        return points;
    }

    /** Parses a trip file; throws when its header is not a valid trip header. */
    static TripLog parse(byte[] bytes) {
        if (bytes.length < HEADER_SIZE) throw new IllegalArgumentException("The trip file is too short.");
        ByteBuffer header = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) throw new IllegalArgumentException("Not a MOKUKU trip file.");
        }
        if (bytes[8] != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported trip format version " + bytes[8] + ".");
        if (!crcMatches(bytes, 0, HEADER_SIZE)) throw new IllegalArgumentException("The trip header is damaged.");
        int count = 0;
        // A power cut can leave a partial or damaged last record.
        while (HEADER_SIZE + (count + 1) * POINT_SIZE <= bytes.length && crcMatches(bytes, HEADER_SIZE + count * POINT_SIZE, POINT_SIZE)) count++;
        ByteBuffer points = ByteBuffer.wrap(bytes, HEADER_SIZE, bytes.length - HEADER_SIZE).slice().order(ByteOrder.LITTLE_ENDIAN);
        return new TripLog(header, points, count);
    }

    int pointCount() { return eastMeters.length; }

    double averageSpeedKmh() {
        return durationMilliseconds == 0 ? 0 : distanceMeters / durationMilliseconds * 3600;
    }

    // CRC-16/CCITT-FALSE of the bytes before the record's last two, stored little-endian after them.
    private static boolean crcMatches(byte[] bytes, int offset, int length) {
        int stored = (bytes[offset + length - 2] & 255) | (bytes[offset + length - 1] & 255) << 8;
        return MokukuProtocol.crc16(bytes, offset, length - 2, 0xffff) == stored;
    }

    private static double[] earthCentered(double latitudeDegrees, double longitudeDegrees, double altitude) {
        double latitude = Math.toRadians(latitudeDegrees), longitude = Math.toRadians(longitudeDegrees);
        double sinLatitude = Math.sin(latitude);
        double primeVerticalRadius = SEMI_MAJOR_AXIS_METERS / Math.sqrt(1 - ECCENTRICITY_SQUARED * sinLatitude * sinLatitude);
        double horizontal = (primeVerticalRadius + altitude) * Math.cos(latitude);
        return new double[] {horizontal * Math.cos(longitude), horizontal * Math.sin(longitude),
                (primeVerticalRadius * (1 - ECCENTRICITY_SQUARED) + altitude) * sinLatitude};
    }

    // Latitude and longitude in degrees, iterating the latitude to well below a millimeter.
    private static double[] geodetic(double x, double y, double z) {
        double horizontal = Math.hypot(x, y);
        double latitude = Math.atan2(z, horizontal * (1 - ECCENTRICITY_SQUARED));
        for (int i = 0; i < 5; i++) {
            double sinLatitude = Math.sin(latitude);
            double primeVerticalRadius = SEMI_MAJOR_AXIS_METERS / Math.sqrt(1 - ECCENTRICITY_SQUARED * sinLatitude * sinLatitude);
            double altitude = horizontal / Math.cos(latitude) - primeVerticalRadius;
            latitude = Math.atan2(z, horizontal * (1 - ECCENTRICITY_SQUARED * primeVerticalRadius / (primeVerticalRadius + altitude)));
        }
        return new double[] {Math.toDegrees(latitude), Math.toDegrees(Math.atan2(y, x))};
    }
}

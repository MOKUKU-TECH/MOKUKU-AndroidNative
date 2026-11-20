package com.mokuku.debug;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Trips synced from MOKUKU, kept in app-private storage under one folder per BLE address. */
final class TripStore {
    private static final Pattern TRIP_NAME = Pattern.compile("trip_([1-9][0-9]{0,8})\\.bin");

    static final class Trip {
        final String deviceName;
        final File file;
        final TripLog log;
        Trip(String deviceName, File file, TripLog log) {
            this.deviceName = deviceName;
            this.file = file;
            this.log = log;
        }
    }

    static final class Device {
        final String address;
        final String name;
        // Newest first.
        final List<Trip> trips = new ArrayList<>();
        Device(String address, String name) {
            this.address = address;
            this.name = name;
        }
    }

    private final File root;
    private final SharedPreferences names;

    TripStore(Context context) {
        root = new File(context.getFilesDir(), "trips");
        names = context.getSharedPreferences("trip_devices", Context.MODE_PRIVATE);
    }

    /** The number in trip_<number>.bin, or 0 for another name. */
    static int number(String name) {
        Matcher matcher = TRIP_NAME.matcher(name);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    static String name(int number) { return "trip_" + number + ".bin"; }

    private File directory(String address) { return new File(root, address.replace(":", "")); }

    File file(String address, int number) { return new File(directory(address), name(number)); }

    /** Trip numbers saved for the device, ascending. */
    List<Integer> numbers(String address) {
        List<Integer> numbers = new ArrayList<>();
        String[] files = directory(address).list();
        if (files != null) for (String file : files) {
            int number = number(file);
            if (number > 0) numbers.add(number);
        }
        Collections.sort(numbers);
        return numbers;
    }

    /** Moves a complete download into place as the device's trip. */
    void save(String address, String deviceName, int number, File download) throws IOException {
        File directory = directory(address);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create the phone trip folder.");
        names.edit().putString(address, deviceName).apply();
        File destination = file(address, number);
        if (!download.renameTo(destination)) {
            copy(download, destination);
            download.delete();
        }
    }

    static byte[] read(File file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            for (int count; (count = input.read(buffer)) != -1; ) bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    static void copy(File source, File destination) throws IOException {
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32768];
            for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
        }
    }

    /** Every saved trip that parses, grouped by device; call off the main thread. */
    List<Device> load() {
        List<Device> devices = new ArrayList<>();
        for (Map.Entry<String, ?> entry : names.getAll().entrySet()) {
            Device device = new Device(entry.getKey(), String.valueOf(entry.getValue()));
            List<Integer> numbers = numbers(device.address);
            for (int i = numbers.size() - 1; i >= 0; i--) {
                File file = file(device.address, numbers.get(i));
                try {
                    device.trips.add(new Trip(device.name, file, TripLog.parse(read(file))));
                } catch (IOException | IllegalArgumentException exception) {
                    android.util.Log.w("MokukuTrips", "Skipping " + file + ": " + exception.getMessage());
                }
            }
            if (!device.trips.isEmpty()) devices.add(device);
        }
        return devices;
    }
}

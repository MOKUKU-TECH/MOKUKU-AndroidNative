package com.mokuku.debug;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Saves downloaded recordings to the phone's public Download/MOKUKU folder. */
final class PhoneDownloads {
    static final String FOLDER = "MOKUKU";
    static final String LOCATION = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER;

    interface Saver {
        /** Copies source into the folder as name; returns the saved file's location. */
        String save(File source, String name) throws IOException;
    }

    /** Android 9 and older write the shared folder directly and need this permission. */
    static boolean needsStoragePermission() { return Build.VERSION.SDK_INT < 29; }

    static Saver forContext(Context context) {
        Context application = context.getApplicationContext();
        return Build.VERSION.SDK_INT >= 29 ? (source, name) -> saveToMediaStore(application.getContentResolver(), source, name)
                : (source, name) -> saveToFolder(application, source, name);
    }

    @android.annotation.TargetApi(29)
    private static String saveToMediaStore(ContentResolver resolver, File source, String name) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, LOCATION);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create the file in Downloads.");
        try (OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) throw new IOException("Could not open the file in Downloads.");
            copy(source, output);
        } catch (IOException | RuntimeException exception) {
            resolver.delete(uri, null, null);
            throw exception instanceof IOException ? (IOException) exception : new IOException(exception.getMessage());
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        // MediaStore renames a file whose name is taken.
        try (android.database.Cursor cursor = resolver.query(uri, new String[] {MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return LOCATION + "/" + cursor.getString(0);
        }
        return LOCATION + "/" + name;
    }

    @SuppressWarnings("deprecation")
    private static String saveToFolder(Context context, File source, String name) throws IOException {
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Could not create Download/" + FOLDER + ".");
        File destination = new File(folder, name);
        int dot = name.lastIndexOf('.');
        for (int copy = 1; destination.exists(); copy++) {
            destination = new File(folder, name.substring(0, dot) + " (" + copy + ")" + name.substring(dot));
        }
        try (OutputStream output = new FileOutputStream(destination)) {
            copy(source, output);
        } catch (IOException exception) {
            destination.delete();
            throw exception;
        }
        MediaScannerConnection.scanFile(context, new String[] {destination.getAbsolutePath()}, null, null);
        return LOCATION + "/" + destination.getName();
    }

    private static void copy(File source, OutputStream output) throws IOException {
        try (InputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private PhoneDownloads() {}
}

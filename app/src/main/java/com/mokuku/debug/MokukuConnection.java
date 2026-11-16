package com.mokuku.debug;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuppressLint("MissingPermission")
final class MokukuConnection {
    private static final String TAG = "MokukuBluetooth";
    // Consecutive failed sectors, or refused starts while MOKUKU applies the access code.
    private static final int DOWNLOAD_RETRY_LIMIT = 3;
    private static final long SECTOR_TIMEOUT_MILLISECONDS = 3000;
    private static final long START_RETRY_MILLISECONDS = 300;
    interface Listener { void changed(Snapshot snapshot); }
    private interface Reply { void complete(byte[] value, String error); }

    static final class Device {
        final String name;
        final String address;
        final int signal;
        Device(String name, String address, int signal) {
            this.name = name;
            this.address = address;
            this.signal = signal;
        }
    }

    static final class Snapshot {
        final boolean connected;
        final boolean scanning;
        final boolean connecting;
        final boolean busy;
        final boolean cancellable;
        final boolean hasRecordingAccessCode;
        final String name;
        final String address;
        final String message;
        final String operation;
        final long received;
        final long total;
        final long downloadElapsedMilliseconds;
        final List<Device> devices;
        final Map<Integer, String> information;
        final Map<Integer, String> errors;
        final List<String> recordings;
        final Map<String, File> downloads;

        Snapshot(MokukuConnection connection) {
            connected = connection.ready;
            scanning = connection.scanner != null;
            connecting = connection.gatt != null && !connected;
            busy = connection.operation != null;
            cancellable = connection.operationCancellable;
            hasRecordingAccessCode = !connection.recordingAccessCode.isEmpty();
            name = connection.name;
            address = connection.address;
            message = connection.message;
            operation = connection.operation;
            received = connection.received;
            total = connection.total;
            downloadElapsedMilliseconds = total > 0
                    ? Math.max(0, android.os.SystemClock.elapsedRealtime() - connection.downloadStartedAt) : 0;
            devices = Collections.unmodifiableList(new ArrayList<>(connection.devices.values()));
            information = Collections.unmodifiableMap(new HashMap<>(connection.information));
            errors = Collections.unmodifiableMap(new HashMap<>(connection.errors));
            recordings = Collections.unmodifiableList(new ArrayList<>(connection.recordings));
            downloads = Collections.unmodifiableMap(new HashMap<>(connection.downloads));
        }
    }

    private static final class Request {
        final UUID characteristic;
        final byte[] bytes;
        final int replyId;
        final boolean withoutResponse;
        final Reply callback;
        boolean written;
        byte[] reply;
        Request(UUID characteristic, byte[] bytes, int replyId, boolean withoutResponse, Reply callback) {
            this.characteristic = characteristic;
            this.bytes = bytes;
            this.replyId = replyId;
            this.withoutResponse = withoutResponse;
            this.callback = callback;
        }
    }

    private final Context context;
    private final SharedPreferences recordingAccessPreferences;
    private final BluetoothAdapter adapter;
    private final HandlerThread thread = new HandlerThread("MokukuBluetooth");
    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<Integer, String> information = new HashMap<>();
    private final Map<Integer, String> errors = new HashMap<>();
    private final Map<String, File> downloads = new HashMap<>();
    private final ArrayDeque<Request> requests = new ArrayDeque<>();
    private List<String> recordings = new ArrayList<>();
    private volatile Listener listener;
    private volatile Snapshot snapshot;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic data;
    private BluetoothGattCharacteristic messages;
    private BluetoothGattCharacteristic fileCommand;
    private BluetoothGattCharacteristic fileData;
    private final ArrayDeque<BluetoothGattCharacteristic> subscriptions = new ArrayDeque<>();
    private boolean ready;
    private int mtu = 23;
    private Request active;
    private String name = "MOKUKU";
    private String address = "";
    private String message = "Find your MOKUKU to get started.";
    private String recordingAccessCode = "";
    private String operation;
    private boolean operationCancellable;
    private boolean refreshingInformation;
    private boolean downloadPriorityRequested;
    private int operationId;
    private File partial;
    private BufferedOutputStream output;
    private long received;
    private long total;
    private long sectorSize;
    private int sector;
    private int nextSequence;
    private boolean sectorIntact;
    private final ByteArrayOutputStream sectorBytes = new ByteArrayOutputStream();
    private int downloadRetries;
    private String downloadName;
    private long downloadStartedAt;
    private final Runnable scanTimeout = this::stopScanOnWorker;
    private final Runnable sectorTimeout = this::retrySector;
    private final Runnable startDownloadAgain = () -> startDownload(operationId);
    private final Runnable connectionTimeout = () -> disconnectOnWorker("Connection timed out. Try again.");
    private final Runnable requestTimeout = () -> {
        if (active != null) Log.w(TAG, "Request timed out: replyId=" + active.replyId
                + ", writeComplete=" + active.written + ", replyReceived=" + (active.reply != null)
                + ", offset=" + received + ", mtu=" + mtu);
        disconnectOnWorker("MOKUKU did not reply. Reconnect and try again; recording queries may exceed the firmware's Bluetooth response limit.");
    };

    MokukuConnection(Context context) {
        this.context = context.getApplicationContext();
        recordingAccessPreferences = this.context.getSharedPreferences("recording_access", Context.MODE_PRIVATE);
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        thread.start();
        worker = new Handler(thread.getLooper());
        snapshot = new Snapshot(this);
        worker.post(() -> {
            android.content.SharedPreferences preferences = this.context.getSharedPreferences("device", Context.MODE_PRIVATE);
            address = preferences.getString("address", "");
            name = preferences.getString("name", "MOKUKU");
            recordingAccessCode = recordingAccessPreferences.getString(address, "");
            updateDownloads();
            publish();
        });
    }

    Snapshot snapshot() { return snapshot; }
    void setListener(Listener listener) { this.listener = listener; }

    private void publish() {
        Snapshot update = new Snapshot(this);
        snapshot = update;
        main.post(() -> {
            Listener current = listener;
            if (current != null) current.changed(update);
        });
    }

    private void report(String message) { this.message = message; publish(); }

    void scan() {
        worker.post(() -> {
            if (gatt != null) { report("Disconnect before looking for another MOKUKU."); return; }
            stopScanOnWorker();
            devices.clear();
            try {
                if (adapter == null || !adapter.isEnabled()) { report("Enable Bluetooth to find MOKUKU."); return; }
                scanner = adapter.getBluetoothLeScanner();
                if (scanner == null) { report("Bluetooth scanning is unavailable on this device."); return; }
                scanner.startScan(Collections.singletonList(new ScanFilter.Builder()
                                .setServiceUuid(new ParcelUuid(MokukuProtocol.SERVICE)).build()),
                        new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
                worker.postDelayed(scanTimeout, 10000);
                report("Looking for nearby MOKUKU devices…");
            } catch (SecurityException | IllegalStateException exception) {
                scanner = null;
                report("Bluetooth access is unavailable. Check app permissions and try again.");
            }
        });
    }

    void stopScan() { worker.post(this::stopScanOnWorker); }

    private void stopScanOnWorker() {
        worker.removeCallbacks(scanTimeout);
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException | IllegalStateException ignored) {}
            scanner = null;
            report(devices.isEmpty() ? "No MOKUKU found. Check its power and try again." : "Choose your MOKUKU to connect.");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            worker.post(() -> {
                if (scanner == null || result.getScanRecord() == null) return;
                String deviceName = result.getScanRecord().getDeviceName();
                if (!MokukuProtocol.isOriginalDevice(deviceName)) return;
                String deviceAddress;
                try { deviceAddress = result.getDevice().getAddress(); }
                catch (SecurityException exception) { stopScanOnWorker(); report("Bluetooth permission was revoked. Check app permissions and search again."); return; }
                if (devices.size() >= 64 && !devices.containsKey(deviceAddress)) return;
                devices.put(deviceAddress, new Device(deviceName, deviceAddress, result.getRssi()));
                publish();
            });
        }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) onScanResult(0, result);
        }
        @Override public void onScanFailed(int errorCode) {
            worker.post(() -> { stopScanOnWorker(); report("Bluetooth scan failed (" + errorCode + "). Wait a moment and try again."); });
        }
    };

    void connect(String deviceAddress) {
        worker.post(() -> {
            Device device = devices.get(deviceAddress);
            if (device == null || adapter == null) { report("Search for MOKUKU again before connecting."); return; }
            stopScanOnWorker();
            disconnectOnWorker("Connecting…");
            if (!address.equals(deviceAddress)) {
                information.clear(); errors.clear(); recordings.clear(); downloads.clear();
            }
            address = device.address;
            name = device.name;
            recordingAccessCode = recordingAccessPreferences.getString(address, "");
            updateDownloads();
            context.getSharedPreferences("device", Context.MODE_PRIVATE).edit().putString("address", address).putString("name", name).apply();
            mtu = 23;
            try {
                gatt = adapter.getRemoteDevice(deviceAddress).connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) { report("Could not start the Bluetooth connection."); return; }
                worker.postDelayed(connectionTimeout, 18000);
                report("Connecting to " + name + "…");
            } catch (SecurityException | IllegalStateException exception) {
                disconnectOnWorker("Bluetooth connection failed. Check permissions and try again.");
            }
        });
    }

    void disconnect() { worker.post(() -> disconnectOnWorker("Disconnected. You can reconnect whenever you’re ready.")); }

    private void disconnectOnWorker(String reason) {
        Log.i(TAG, "Disconnect: " + reason);
        ready = false;
        refreshingInformation = false;
        worker.removeCallbacks(connectionTimeout);
        worker.removeCallbacks(requestTimeout);
        BluetoothGatt previous = gatt;
        gatt = null;
        data = null;
        messages = null;
        fileCommand = null;
        fileData = null;
        subscriptions.clear();
        if (previous != null) {
            try { previous.disconnect(); } catch (SecurityException ignored) {}
            try { previous.close(); } catch (SecurityException ignored) {}
        }
        List<Request> failed = new ArrayList<>(requests);
        requests.clear();
        if (active != null) failed.add(0, active);
        active = null;
        information.clear();
        errors.clear();
        recordings.clear();
        recordingAccessCode = recordingAccessPreferences.getString(address, "");
        endOperation(reason);
        for (Request request : failed) request.callback.complete(null, reason);
        report(reason);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
            worker.post(() -> {
                if (connection != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnectOnWorker("MOKUKU disconnected" + (status == 0 ? "." : " (Bluetooth " + status + ").") + " Reconnect to refresh its information.");
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        if (!connection.discoverServices()) disconnectOnWorker("Could not discover MOKUKU's Bluetooth services.");
                    } catch (SecurityException exception) { disconnectOnWorker("Bluetooth permission was revoked. Check app permissions and reconnect."); }
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt connection, int status) {
            worker.post(() -> {
                if (connection != gatt) return;
                BluetoothGattService service = connection.getService(MokukuProtocol.SERVICE);
                data = service == null ? null : service.getCharacteristic(MokukuProtocol.DATA);
                messages = service == null ? null : service.getCharacteristic(MokukuProtocol.MESSAGE);
                fileCommand = service == null ? null : service.getCharacteristic(MokukuProtocol.FILE_COMMAND);
                fileData = service == null ? null : service.getCharacteristic(MokukuProtocol.FILE_DATA);
                if (status != BluetoothGatt.GATT_SUCCESS || data == null || messages == null) {
                    disconnectOnWorker("This device does not provide the original MOKUKU protocol."); return;
                }
                try {
                    if (!connection.requestMtu(517)) subscribe();
                } catch (SecurityException exception) { disconnectOnWorker("Bluetooth permission was revoked. Check app permissions and reconnect."); }
            });
        }
        @Override public void onMtuChanged(BluetoothGatt connection, int value, int status) {
            worker.post(() -> {
                if (connection != gatt || ready) return;
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = Math.max(23, value);
                Log.i(TAG, "Negotiated MTU: " + mtu + " (status " + status + ")");
                subscribe();
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
            worker.post(() -> {
                if (connection != gatt || !descriptor.getUuid().equals(MokukuProtocol.NOTIFICATIONS)) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { disconnectOnWorker("Could not subscribe to MOKUKU replies."); return; }
                if (!subscriptions.isEmpty()) { subscribeNext(); return; }
                worker.removeCallbacks(connectionTimeout);
                ready = true;
                report("Connected. Refreshing device information…");
                refreshInformationOnWorker();
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            worker.post(() -> {
                if (connection != gatt || active == null || !active.characteristic.equals(characteristic.getUuid())) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { disconnectOnWorker("Bluetooth write failed (" + status + "). Reconnect and try again."); return; }
                active.written = true;
                completeRequest();
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
            acceptNotification(connection, characteristic, value.clone());
        }
        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) acceptNotification(connection, characteristic, value.clone());
        }
    };

    private void subscribe() {
        subscriptions.clear();
        subscriptions.add(data);
        if (fileCommand != null && fileData != null) { subscriptions.add(fileCommand); subscriptions.add(fileData); }
        subscribeNext();
    }

    // Descriptor writes run one at a time; onDescriptorWrite starts the next.
    @SuppressWarnings("deprecation")
    private void subscribeNext() {
        BluetoothGattCharacteristic characteristic = subscriptions.removeFirst();
        try {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(MokukuProtocol.NOTIFICATIONS);
            if (descriptor == null || !gatt.setCharacteristicNotification(characteristic, true)) {
                disconnectOnWorker("MOKUKU notifications are unavailable."); return;
            }
            boolean started;
            if (Build.VERSION.SDK_INT >= 33) {
                started = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
            } else {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                started = gatt.writeDescriptor(descriptor);
            }
            if (!started) disconnectOnWorker("Could not enable MOKUKU replies.");
        } catch (SecurityException exception) { disconnectOnWorker("Bluetooth permission was revoked. Check app permissions and reconnect."); }
    }

    private void acceptNotification(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
        UUID uuid = characteristic.getUuid();
        worker.post(() -> {
            if (connection != gatt || value.length == 0) return;
            if (uuid.equals(MokukuProtocol.FILE_COMMAND)) { if (output != null) acceptDownloadStart(value); return; }
            if (uuid.equals(MokukuProtocol.FILE_DATA)) { if (output != null && total > 0) acceptSectorPacket(value); return; }
            if (!uuid.equals(MokukuProtocol.DATA)) return;
            if (active == null || MokukuProtocol.unsigned(value[0]) != active.replyId) return;
            active.reply = value;
            completeRequest();
        });
    }

    private void request(UUID characteristic, byte[] bytes, int replyId, Reply callback) {
        request(characteristic, bytes, replyId, false, callback);
    }

    private void request(UUID characteristic, byte[] bytes, int replyId, boolean withoutResponse, Reply callback) {
        if (!ready) { callback.complete(null, "Connect to MOKUKU first."); return; }
        if (bytes.length > mtu - 3) { callback.complete(null, "Bluetooth packet size is too small for this setting. Reconnect and try again."); return; }
        if (requests.size() >= 32) { callback.complete(null, "Wait for the current requests to finish."); return; }
        requests.add(new Request(characteristic, bytes, replyId, withoutResponse, callback));
        pump();
    }

    @SuppressWarnings("deprecation")
    private void pump() {
        if (active != null || !ready || requests.isEmpty()) return;
        active = requests.removeFirst();
        BluetoothGattCharacteristic characteristic = characteristic(active.characteristic);
        int writeType = active.withoutResponse
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try {
            boolean started;
            if (Build.VERSION.SDK_INT >= 33) {
                started = gatt.writeCharacteristic(characteristic, active.bytes, writeType) == BluetoothStatusCodes.SUCCESS;
            } else {
                characteristic.setWriteType(writeType);
                characteristic.setValue(active.bytes);
                started = gatt.writeCharacteristic(characteristic);
            }
            if (!started) { disconnectOnWorker("Bluetooth is busy or unavailable. Reconnect and try again."); return; }
            worker.postDelayed(requestTimeout, 8000);
        } catch (SecurityException | IllegalStateException exception) {
            disconnectOnWorker("Bluetooth access was lost. Check permissions and reconnect.");
        }
    }

    private BluetoothGattCharacteristic characteristic(UUID uuid) {
        if (uuid.equals(MokukuProtocol.DATA)) return data;
        if (uuid.equals(MokukuProtocol.FILE_COMMAND)) return fileCommand;
        if (uuid.equals(MokukuProtocol.FILE_DATA)) return fileData;
        return messages;
    }

    private void completeRequest() {
        if (active == null || !active.written || (active.replyId >= 0 && active.reply == null)) return;
        worker.removeCallbacks(requestTimeout);
        Request completed = active;
        active = null;
        completed.callback.complete(completed.reply, null);
        pump();
    }

    void refreshInformation() { worker.post(() -> { if (canStart()) refreshInformationOnWorker(); }); }

    private void refreshInformationOnWorker() {
        if (refreshingInformation) return;
        refreshingInformation = true;
        for (int id : new int[] {3, 4, 5, 57, 61}) {
            request(MokukuProtocol.MESSAGE, new byte[] {(byte) id}, id, (reply, error) -> {
                if (!ready) return;
                if (error == null) {
                    try {
                        String value = id == 57 ? MokukuProtocol.voiceModel(reply) : MokukuProtocol.textReply(reply, id);
                        if (id == 61) MokukuProtocol.storage(reply);
                        if (value.isEmpty()) throw new IllegalArgumentException("No information returned.");
                        information.put(id, value);
                        errors.remove(id);
                    } catch (IllegalArgumentException exception) { error = exception.getMessage(); }
                }
                if (error != null) errors.put(id, error);
                if (id == 61) {
                    refreshingInformation = false;
                    if (ready) message = errors.isEmpty() ? "Device information refreshed." : "Device information refreshed; some queries are unavailable.";
                }
                publish();
            });
        }
    }

    private boolean canStart() {
        if (!ready) { report("Connect to MOKUKU first."); return false; }
        if (operation != null) { report("Finish or cancel the current operation first."); return false; }
        return true;
    }

    void command(int command, String label) {
        worker.post(() -> {
            if (!canStart()) return;
            boolean restarting = command >= 66 && command <= 73;
            if (restarting) { operation = label; operationCancellable = false; publish(); }
            request(MokukuProtocol.MESSAGE, MokukuProtocol.command(command), 1, (reply, error) -> {
                if (error == null) {
                    try { MokukuProtocol.textReply(reply, 1); }
                    catch (IllegalArgumentException exception) { error = exception.getMessage(); }
                }
                if (restarting && error == null) {
                    disconnectOnWorker(label + " requested. Reconnect after MOKUKU finishes to check versions.");
                } else if (restarting) endOperation(error);
                else report(error == null ? label + " requested. Check MOKUKU for the result." : error);
            });
        });
    }

    void saveWifi(String network, String password) {
        worker.post(() -> {
            if (!canStart()) return;
            try {
                byte[] networkPacket = MokukuProtocol.stringMessage(7, network, 32);
                byte[] passwordPacket = MokukuProtocol.stringMessage(8, password, 64);
                operation = "Saving Wi-Fi";
                operationCancellable = false;
                publish();
                setting(networkPacket, 7, (reply, error) -> {
                    if (error != null) { endOperation("Could not confirm the Wi-Fi name: " + error); return; }
                    setting(passwordPacket, 8, (value, failure) -> endOperation(failure == null
                            ? "Wi-Fi name and password saved to MOKUKU."
                            : "Wi-Fi name saved, but password could not be confirmed: " + failure));
                });
            } catch (IllegalArgumentException exception) { report(exception.getMessage()); }
        });
    }

    void saveUrl(String url) {
        worker.post(() -> {
            if (!canStart()) return;
            try {
                java.net.URI uri = java.net.URI.create(url);
                if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    throw new IllegalArgumentException("Enter an HTTP or HTTPS base URL without credentials, query, or fragment.");
                }
                String normalized = url.replaceAll("/+$", "");
                setting(MokukuProtocol.stringMessage(9, normalized, 255), 9,
                        (reply, error) -> report(error == null ? "Remote URL saved to MOKUKU." : error));
            } catch (IllegalArgumentException exception) { report(exception.getMessage()); }
        });
    }

    private void setting(byte[] packet, int id, Reply callback) {
        request(MokukuProtocol.MESSAGE, packet, id, (reply, error) -> {
            if (error == null) {
                try { MokukuProtocol.textReply(reply, id); }
                catch (IllegalArgumentException exception) { error = exception.getMessage(); }
            }
            callback.complete(reply, error);
        });
    }

    void setDateTime(int year, int month, int day, int hour, int minute) {
        worker.post(() -> {
            if (!canStart()) return;
            try {
                request(MokukuProtocol.DATA, MokukuProtocol.dateTime(year, month, day, hour, minute), -1,
                        (reply, error) -> report(error == null ? "Date and time sent. GPS can override the debug date." : error));
            } catch (IllegalArgumentException exception) { report(exception.getMessage()); }
        });
    }

    void setRecordingAccessCode(String code) {
        worker.post(() -> {
            if (!canStart()) return;
            try {
                MokukuProtocol.stringMessage(44, code, 255);
                recordingAccessPreferences.edit().putString(address, code).apply();
                recordingAccessCode = code;
                refreshRecordings();
            } catch (IllegalArgumentException exception) { report(exception.getMessage()); }
        });
    }

    void forgetRecordingAccessCode() {
        worker.post(() -> {
            if (operation != null) { report("Finish or cancel the current operation first."); return; }
            recordingAccessPreferences.edit().remove(address).apply();
            recordingAccessCode = "";
            recordings.clear();
            report("Saved access code forgotten. Downloaded phone copies are kept.");
        });
    }

    private void authorize(Reply callback) {
        if (recordingAccessCode.isEmpty()) {
            callback.complete(null, "Enter an access code in Data Collection to access recordings."); return;
        }
        try {
            request(MokukuProtocol.MESSAGE, MokukuProtocol.stringMessage(44, recordingAccessCode, 255), -1, callback);
        } catch (IllegalArgumentException exception) { callback.complete(null, "Enter a valid recording access code."); }
    }

    private void list(Reply callback) {
        request(MokukuProtocol.MESSAGE, MokukuProtocol.stringMessage(60, MokukuProtocol.RECORD_DIRECTORY, 255), 60, callback);
    }

    void refreshRecordings() {
        worker.post(() -> {
            if (!canStart()) return;
            operation = "Loading recordings";
            operationCancellable = true;
            int id = ++operationId;
            publish();
            authorize((reply, error) -> {
                if (id != operationId) return;
                if (error != null) { endOperation(error); return; }
                list((value, failure) -> {
                    if (id != operationId) return;
                    if (failure == null) {
                        try { recordings = MokukuProtocol.recordings(value); updateDownloads(); }
                        catch (IllegalArgumentException exception) { failure = exception.getMessage(); }
                    }
                    endOperation(failure == null ? "Recordings refreshed. The firmware may return a partial list." : failure);
                });
            });
        });
    }

    private File recordingDirectory() {
        return new File(context.getFilesDir(), "recordings/" + address.replace(":", ""));
    }

    private void updateDownloads() {
        downloads.clear();
        if (address.isEmpty()) return;
        File[] localFiles = recordingDirectory().listFiles();
        if (localFiles == null) return;
        for (File local : localFiles) {
            if (local.isFile() && MokukuProtocol.isRecordingName(local.getName())) downloads.put(local.getName(), local);
            else if (local.isFile() && local.getName().startsWith("download-") && local.getName().endsWith(".partial")) local.delete();
        }
    }

    void deleteRecording(String name, boolean all) {
        worker.post(() -> {
            if (!canStart()) return;
            operation = all ? "Clearing device recordings" : "Deleting " + name;
            operationCancellable = true;
            int id = ++operationId;
            publish();
            authorize((reply, error) -> {
                if (id != operationId) return;
                if (error != null) { endOperation(error); return; }
                // Stop recording before touching files; command acceptance is not state readback.
                request(MokukuProtocol.MESSAGE, MokukuProtocol.command(2), 1, (value, failure) -> {
                    if (id != operationId) return;
                    if (failure == null) {
                        try { MokukuProtocol.textReply(value, 1); }
                        catch (IllegalArgumentException exception) { failure = exception.getMessage(); }
                    }
                    if (failure != null) endOperation(failure);
                    else if (all) nextDelete(id, null);
                    else deleteThenList(id, name, false);
                });
            });
        });
    }

    private void nextDelete(int id, String previousName) {
        list((reply, error) -> {
            if (id != operationId) return;
            if (error != null) { endOperation("Clearing stopped: " + error); return; }
            try { recordings = MokukuProtocol.recordings(reply); }
            catch (IllegalArgumentException exception) { endOperation(exception.getMessage()); return; }
            if (previousName != null && recordings.contains(previousName)) {
                endOperation("Clearing stopped: " + previousName + " is still returned. Refresh and retry."); return;
            }
            if (recordings.isEmpty()) { endOperation("No recordings returned after clearing. Phone copies are kept."); return; }
            publish();
            deleteThenList(id, recordings.get(0), true);
        });
    }

    private void deleteThenList(int id, String name, boolean all) {
        try {
            request(MokukuProtocol.MESSAGE, MokukuProtocol.stringMessage(62, MokukuProtocol.recordPath(name), 255), -1, (reply, error) -> {
                if (id != operationId) return;
                if (error != null) { endOperation(error); return; }
                if (all) { nextDelete(id, name); return; }
                list((value, failure) -> {
                    if (id != operationId) return;
                    if (failure == null) {
                        try {
                            recordings = MokukuProtocol.recordings(value);
                            if (recordings.contains(name)) failure = "The recording is still returned. It may not have been deleted.";
                        } catch (IllegalArgumentException exception) { failure = exception.getMessage(); }
                    }
                    endOperation(failure == null ? "Recording no longer returned. Phone copies are kept." : failure);
                });
            });
        } catch (IllegalArgumentException exception) { endOperation(exception.getMessage()); }
    }

    void download(String name) {
        worker.post(() -> {
            if (!canStart()) return;
            if (fileData == null) { report("Update MOKUKU's firmware to download recordings."); return; }
            operation = "Downloading " + name;
            operationCancellable = true;
            received = 0;
            total = 0;
            int id = ++operationId;
            setDownloadPriority(true);
            publish();
            downloadName = name;
            authorize((reply, error) -> {
                if (id != operationId) return;
                if (error != null) { endOperation(error); return; }
                try {
                    File directory = recordingDirectory();
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create the phone recording folder.");
                    partial = File.createTempFile("download-", ".partial", directory);
                    output = new BufferedOutputStream(new FileOutputStream(partial));
                } catch (IOException exception) { endOperation(exception.getMessage()); return; }
                startDownload(id);
            });
        });
    }

    private void startDownload(int id) {
        if (id != operationId) return;
        try {
            request(MokukuProtocol.FILE_COMMAND, MokukuProtocol.startDownload(MokukuProtocol.recordPath(downloadName)), -1, (reply, error) -> {
                if (id == operationId && error != null) endOperation(error);
            });
            worker.removeCallbacks(sectorTimeout);
            worker.postDelayed(sectorTimeout, SECTOR_TIMEOUT_MILLISECONDS);
        } catch (IllegalArgumentException exception) { endOperation(exception.getMessage()); }
    }

    private void acceptDownloadStart(byte[] frame) {
        if (total > 0) return;
        try {
            long[] started = MokukuProtocol.downloadStarted(frame);
            if (started == null) return;
            if (started[0] == 0) throw new IOException("The recording is empty.");
            if (started[0] > recordingDirectory().getUsableSpace()) throw new IOException("Not enough free phone storage.");
            total = started[0];
            sectorSize = started[1];
            sector = 0;
            downloadRetries = 0;
            resetSector();
            downloadStartedAt = android.os.SystemClock.elapsedRealtime();
            Log.i(TAG, "Download transport: MTU=" + mtu + ", sector=" + sectorSize);
            publish();
            worker.removeCallbacks(sectorTimeout);
            worker.postDelayed(sectorTimeout, SECTOR_TIMEOUT_MILLISECONDS);
        } catch (IllegalStateException refused) {
            // MOKUKU applies the access code on another task, so the first start can race it.
            worker.removeCallbacks(sectorTimeout);
            if (++downloadRetries > DOWNLOAD_RETRY_LIMIT) endOperation(refused.getMessage());
            else worker.postDelayed(startDownloadAgain, START_RETRY_MILLISECONDS);
        } catch (IOException | IllegalArgumentException exception) { endOperation(exception.getMessage()); }
    }

    private void resetSector() {
        sectorBytes.reset();
        nextSequence = 0;
        sectorIntact = true;
    }

    private void acceptSectorPacket(byte[] packet) {
        if (packet.length < MokukuProtocol.PACKET_HEADER_SIZE) return;
        int index = MokukuProtocol.unsigned(packet[0]) | MokukuProtocol.unsigned(packet[1]) << 8;
        int sequence = MokukuProtocol.unsigned(packet[2]);
        if (index != sector) return;  // left over from a resent sector
        if (sequence == 0) resetSector();
        if (sequence != MokukuProtocol.LAST_PACKET) {
            sectorIntact &= sequence == nextSequence;
            nextSequence = sequence + 1;
        }
        sectorBytes.write(packet, MokukuProtocol.PACKET_HEADER_SIZE, packet.length - MokukuProtocol.PACKET_HEADER_SIZE);
        worker.removeCallbacks(sectorTimeout);
        worker.postDelayed(sectorTimeout, SECTOR_TIMEOUT_MILLISECONDS);
        if (sequence != MokukuProtocol.LAST_PACKET) return;
        byte[] body = MokukuProtocol.withoutCrc(sectorBytes.toByteArray(), sectorBytes.size());
        if (!sectorIntact || body == null || body.length != Math.min(sectorSize, total - received)) { retrySector(); return; }
        resetSector();
        try {
            output.write(body);
            received += body.length;
            downloadRetries = 0;
            sector++;
            acknowledge(sector - 1, MokukuProtocol.SECTOR_OK);
            if (received < total) { publish(); return; }
            output.close(); output = null;
            File destination = new File(recordingDirectory(), downloadName);
            if (!partial.renameTo(destination)) throw new IOException("Could not finish saving the recording.");
            partial = null;
            downloads.put(downloadName, destination);
            endOperation("Downloaded " + downloadName + " to the app. Use Save to phone to choose a location.");
        } catch (IOException exception) { endOperation(exception.getMessage()); }
    }

    // Asks MOKUKU to resend the current sector after a CRC failure, a gap or a stall.
    private void retrySector() {
        worker.removeCallbacks(sectorTimeout);
        if (output == null) return;
        if (total == 0) { endOperation("MOKUKU did not start the download. Reconnect and try again."); return; }
        if (++downloadRetries > DOWNLOAD_RETRY_LIMIT) { endOperation("MOKUKU stopped sending recording data. Download again."); return; }
        Log.w(TAG, "Resending sector " + sector + " at offset " + received + " (attempt " + downloadRetries + ")");
        resetSector();
        acknowledge(sector, MokukuProtocol.SECTOR_CRC_ERROR);
        worker.postDelayed(sectorTimeout, SECTOR_TIMEOUT_MILLISECONDS);
    }

    // The acknowledgement names the sector MOKUKU sends next.
    private void acknowledge(int acknowledged, int status) {
        int id = operationId;
        boolean withoutResponse = (fileData.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        request(MokukuProtocol.FILE_DATA, MokukuProtocol.acknowledgement(acknowledged, status, sector), -1, withoutResponse,
                (reply, error) -> { if (id == operationId && error != null) endOperation(error); });
    }

    void cancelOperation() {
        worker.post(() -> {
            if (operationCancellable) endOperation("Operation canceled. Completed phone copies are kept.");
        });
    }

    private void setDownloadPriority(boolean high) {
        if (downloadPriorityRequested == high) return;
        downloadPriorityRequested = high;
        if (gatt == null) return;
        try {
            boolean accepted = gatt.requestConnectionPriority(high
                    ? BluetoothGatt.CONNECTION_PRIORITY_HIGH : BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
            Log.i(TAG, "Connection priority request: " + (high ? "high" : "balanced") + ", accepted=" + accepted);
        } catch (SecurityException exception) {
            Log.w(TAG, "Bluetooth permission unavailable for connection priority request.");
        }
    }

    private void endOperation(String result) {
        ++operationId;
        if (downloadStartedAt > 0) Log.i(TAG, "Download ended at " + received + "/" + total + " after "
                + (android.os.SystemClock.elapsedRealtime() - downloadStartedAt) + " ms: " + result);
        worker.removeCallbacks(sectorTimeout);
        worker.removeCallbacks(startDownloadAgain);
        downloadRetries = 0;
        setDownloadPriority(false);
        if (output != null) {
            if (ready) request(MokukuProtocol.FILE_COMMAND, MokukuProtocol.stopTransfer(), -1, (reply, error) -> {});
            try { output.close(); } catch (IOException ignored) {}
            output = null;
        }
        if (partial != null) { partial.delete(); partial = null; }
        operation = null;
        operationCancellable = false;
        received = 0;
        total = 0;
        downloadStartedAt = 0;
        report(result == null ? "Operation could not be completed." : result);
    }

    void close() {
        listener = null;
        worker.post(() -> {
            stopScanOnWorker();
            disconnectOnWorker("Disconnected.");
            worker.removeCallbacksAndMessages(null);
            thread.quitSafely();
        });
    }
}

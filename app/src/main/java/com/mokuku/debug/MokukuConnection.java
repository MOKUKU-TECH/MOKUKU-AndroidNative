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
import java.util.Arrays;
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
    // MOKUKU II drops a host that writes nothing for 30 s.
    private static final long KEEP_ALIVE_MILLISECONDS = 10000;
    // A firmware sector is up to 256 packets.
    private static final int REQUEST_QUEUE_LIMIT = 300;
    private static final int FIRMWARE_IMAGE_MAXIMUM_BYTES = 16 << 20;
    static final String FIRMWARE_UPDATE = "Updating firmware";
    // The left eye restarts into its update with Bluetooth off; retry until it comes back.
    private static final long RECONNECT_WINDOW_MILLISECONDS = 5 * 60_000;
    private static final long RECONNECT_DELAY_MILLISECONDS = 3000;
    private static final long LEFT_EYE_UPDATE_DELAY_MILLISECONDS = 500;
    interface Listener { void changed(Snapshot snapshot); }
    private interface Reply { void complete(byte[] value, String error); }
    private interface Names { void complete(List<String> names, String error); }

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
        final boolean mokukuII;
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
        // Recording name to where this session saved it on the phone.
        final Map<String, String> saved;

        Snapshot(MokukuConnection connection) {
            connected = connection.ready;
            scanning = connection.scanner != null;
            connecting = connection.gatt != null && !connected;
            busy = connection.operation != null;
            cancellable = connection.operationCancellable;
            hasRecordingAccessCode = !connection.recordingAccessCode.isEmpty();
            mokukuII = connection.mokukuII;
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
            saved = Collections.unmodifiableMap(new HashMap<>(connection.saved));
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
    private final Map<String, String> saved = new HashMap<>();
    private PhoneDownloads.Saver saver;
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
    // Chosen from the advertised name: mokukuii… speaks the set/get protocol.
    private boolean mokukuII;
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
    private byte[] firmwareImage;
    private int packetSize;
    private long downloadStartedAt;
    private final Runnable scanTimeout = this::stopScanOnWorker;
    private final Runnable sectorTimeout = this::retrySector;
    private final Runnable startDownloadAgain = () -> startDownload(operationId);
    private long lastWriteAt;
    // Re-arms itself for the time left since the last write, so writes need not touch the handler.
    private final Runnable keepAlive = new Runnable() {
        @Override public void run() {
            if (!ready || !mokukuII) return;
            long idle = android.os.SystemClock.elapsedRealtime() - lastWriteAt;
            if (idle < KEEP_ALIVE_MILLISECONDS) { worker.postDelayed(this, KEEP_ALIVE_MILLISECONDS - idle); return; }
            if (active == null && requests.isEmpty()) send(get(MokukuProtocol.PROPERTY_VERSION), (reply, error) -> {});
            worker.postDelayed(this, KEEP_ALIVE_MILLISECONDS);
        }
    };
    private long reconnectUntil;
    private final Runnable reconnect = () -> { if (gatt == null) openGatt(); };
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
        saver = PhoneDownloads.forContext(context);
        snapshot = new Snapshot(this);
        worker.post(() -> {
            android.content.SharedPreferences preferences = this.context.getSharedPreferences("device", Context.MODE_PRIVATE);
            address = preferences.getString("address", "");
            name = preferences.getString("name", "MOKUKU");
            mokukuII = MokukuProtocol.isMokukuII(name);
            recordingAccessCode = recordingAccessPreferences.getString(address, "");
            deletePartialDownloads();
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
            stopReconnecting();
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
                if (!MokukuProtocol.isOriginalDevice(deviceName) && !MokukuProtocol.isMokukuII(deviceName)) return;
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
            stopReconnecting();
            disconnectOnWorker("Connecting…");
            if (!address.equals(deviceAddress)) {
                information.clear(); errors.clear(); recordings.clear(); saved.clear();
            }
            address = device.address;
            name = device.name;
            mokukuII = MokukuProtocol.isMokukuII(name);
            recordingAccessCode = recordingAccessPreferences.getString(address, "");
            context.getSharedPreferences("device", Context.MODE_PRIVATE).edit().putString("address", address).putString("name", name).apply();
            openGatt();
        });
    }

    private void openGatt() {
        mtu = 23;
        try {
            gatt = adapter.getRemoteDevice(address).connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { disconnectOnWorker("Could not start the Bluetooth connection."); return; }
            worker.postDelayed(connectionTimeout, 18000);
            report(reconnectUntil != 0 ? "MOKUKU is updating. Reconnecting to " + name + "…" : "Connecting to " + name + "…");
        } catch (SecurityException | IllegalStateException exception) {
            disconnectOnWorker("Bluetooth connection failed. Check permissions and try again.");
        }
    }

    private void stopReconnecting() {
        reconnectUntil = 0;
        worker.removeCallbacks(reconnect);
    }

    void disconnect() {
        worker.post(() -> {
            stopReconnecting();
            disconnectOnWorker("Disconnected. You can reconnect whenever you’re ready.");
        });
    }

    private void disconnectOnWorker(String reason) {
        Log.i(TAG, "Disconnect: " + reason);
        ready = false;
        refreshingInformation = false;
        worker.removeCallbacks(connectionTimeout);
        worker.removeCallbacks(requestTimeout);
        worker.removeCallbacks(keepAlive);
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
        if (reconnectUntil != 0 && android.os.SystemClock.elapsedRealtime() >= reconnectUntil) {
            reconnectUntil = 0;
            reason = "MOKUKU did not come back after the update. Reconnect to check versions.";
        }
        endOperation(reason);
        for (Request request : failed) request.callback.complete(null, reason);
        if (reconnectUntil != 0) {
            worker.postDelayed(reconnect, RECONNECT_DELAY_MILLISECONDS);
            reason = "MOKUKU is updating. Reconnecting…";
        }
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
                boolean complete = mokukuII ? messages != null && fileCommand != null && fileData != null : data != null && messages != null;
                if (status != BluetoothGatt.GATT_SUCCESS || !complete) {
                    disconnectOnWorker("This device does not provide the " + (mokukuII ? "MOKUKU II" : "original MOKUKU") + " protocol."); return;
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
                reconnectUntil = 0;
                ready = true;
                if (mokukuII) worker.postDelayed(keepAlive, KEEP_ALIVE_MILLISECONDS);
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
        subscriptions.add(mokukuII ? messages : data);
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
            if (uuid.equals(MokukuProtocol.FILE_COMMAND)) {
                if (output != null) acceptDownloadStart(value);
                else if (firmwareImage != null) acceptFirmwareUpdateStart(value);
                return;
            }
            if (uuid.equals(MokukuProtocol.FILE_DATA)) {
                if (output != null && total > 0) acceptSectorPacket(value);
                else if (firmwareImage != null && total > 0) acceptFirmwareAcknowledgement(value);
                return;
            }
            if (!uuid.equals(mokukuII ? MokukuProtocol.MESSAGE : MokukuProtocol.DATA)) return;
            if (active == null || MokukuProtocol.replyKey(value, mokukuII) != active.replyId) return;
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
        if (requests.size() >= REQUEST_QUEUE_LIMIT) { callback.complete(null, "Wait for the current requests to finish."); return; }
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
            lastWriteAt = android.os.SystemClock.elapsedRealtime();
        } catch (SecurityException | IllegalStateException exception) {
            disconnectOnWorker("Bluetooth access was lost. Check permissions and reconnect.");
        }
    }

    /** Sends a message whose reply reports success: `ID, 1` or MOKUKU II's result 0. */
    private void send(byte[] packet, Reply callback) {
        request(MokukuProtocol.MESSAGE, packet, MokukuProtocol.replyKey(packet, mokukuII), (reply, error) -> {
            if (error == null) {
                try {
                    if (mokukuII) MokukuProtocol.propertyValue(reply);
                    else MokukuProtocol.textReply(reply, MokukuProtocol.unsigned(reply[0]));
                } catch (IllegalArgumentException exception) { error = exception.getMessage(); }
            }
            callback.complete(reply, error);
        });
    }

    private static byte[] set(int property, byte[] value) {
        return MokukuProtocol.property(MokukuProtocol.TYPE_SET, property, value);
    }

    private static byte[] get(int property) {
        return MokukuProtocol.property(MokukuProtocol.TYPE_GET, property, new byte[0]);
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

    // Information is keyed by the original message IDs; MOKUKU II properties map onto them, and its
    // own properties (OBD 6, language 9, firmware slots 16) use their numbers.
    private static final int[][] MOKUKU_II_INFORMATION = {
            {MokukuProtocol.PROPERTY_VERSION, 3}, {MokukuProtocol.PROPERTY_MAC_ADDRESS, 4},
            {MokukuProtocol.PROPERTY_WAKE_WORD_MODEL, 57}, {MokukuProtocol.PROPERTY_LANGUAGE, MokukuProtocol.PROPERTY_LANGUAGE},
            {MokukuProtocol.PROPERTY_OBD_ENABLED, MokukuProtocol.PROPERTY_OBD_ENABLED},
            {MokukuProtocol.PROPERTY_FIRMWARE_SLOTS, MokukuProtocol.PROPERTY_FIRMWARE_SLOTS}, {MokukuProtocol.PROPERTY_SD_CARD, 61}};

    private void refreshInformationOnWorker() {
        if (refreshingInformation) return;
        refreshingInformation = true;
        if (mokukuII) {
            for (int[] query : MOKUKU_II_INFORMATION) queryInformation(query[0], query[1], query == MOKUKU_II_INFORMATION[MOKUKU_II_INFORMATION.length - 1]);
        } else {
            int[] keys = {3, 4, 5, 57, 61};
            for (int key : keys) queryInformation(key, key, key == keys[keys.length - 1]);
        }
    }

    private void queryInformation(int property, int key, boolean last) {
        byte[] query = mokukuII ? get(property) : new byte[] {(byte) key};
        request(MokukuProtocol.MESSAGE, query, MokukuProtocol.replyKey(query, mokukuII), (reply, error) -> {
            if (!ready) return;
            if (error == null) {
                try {
                    String value;
                    if (mokukuII) value = MokukuProtocol.describeProperty(property, MokukuProtocol.propertyValue(reply));
                    else {
                        value = key == 57 ? MokukuProtocol.voiceModel(reply) : MokukuProtocol.textReply(reply, key);
                        if (key == 61) MokukuProtocol.storage(reply);
                    }
                    if (value.isEmpty()) throw new IllegalArgumentException("No information returned.");
                    information.put(key, value);
                    errors.remove(key);
                } catch (IllegalArgumentException exception) { error = exception.getMessage(); }
            }
            if (error != null) errors.put(key, error);
            if (last) {
                refreshingInformation = false;
                if (ready) message = errors.isEmpty() ? "Device information refreshed." : "Device information refreshed; some queries are unavailable.";
            }
            publish();
        });
    }

    private boolean canStart() {
        if (!ready) { report("Connect to MOKUKU first."); return false; }
        if (operation != null) { report("Finish or cancel the current operation first."); return false; }
        return true;
    }

    // Requests the right eye, then the left eye, which restarts; the app reconnects once it is back.
    void updateBothEyes() {
        worker.post(() -> {
            if (!canStart()) return;
            operation = "Updating both eyes";
            operationCancellable = false;
            publish();
            BluetoothGatt connection = gatt;
            send(MokukuProtocol.command(67), (reply, error) -> {
                if (error != null) endOperation(error);
                else worker.postDelayed(() -> requestLeftEyeUpdate(connection), LEFT_EYE_UPDATE_DELAY_MILLISECONDS);
            });
        });
    }

    private void requestLeftEyeUpdate(BluetoothGatt connection) {
        if (gatt != connection || !ready) return;
        send(MokukuProtocol.command(66), (reply, error) -> {
            if (error != null) { endOperation(error); return; }
            reconnectUntil = android.os.SystemClock.elapsedRealtime() + RECONNECT_WINDOW_MILLISECONDS;
            disconnectOnWorker("Both eye updates requested.");
        });
    }

    void command(int command, String label) {
        perform(MokukuProtocol.command(command), label, command >= 66 && command <= 73 && !MokukuProtocol.isRightEyeUpdate(command), null);
    }

    /** Sets a MOKUKU II property; restarting when acceptance reboots the device. */
    void setProperty(int property, byte[] value, String label, boolean restarting) {
        // Read OBD back so Debug shows the state MOKUKU II reports.
        Runnable accepted = property == MokukuProtocol.PROPERTY_OBD_ENABLED
                ? () -> queryInformation(property, property, false) : null;
        perform(set(property, value), label, restarting, accepted);
    }

    private void perform(byte[] packet, String label, boolean restarting, Runnable accepted) {
        worker.post(() -> {
            if (!canStart()) return;
            if (restarting) { operation = label; operationCancellable = false; publish(); }
            send(packet, (reply, error) -> {
                if (error == null && accepted != null) accepted.run();
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
                byte[] networkPacket = stringSetting(7, MokukuProtocol.PROPERTY_WIFI_NAME, network, 32);
                byte[] passwordPacket = stringSetting(8, MokukuProtocol.PROPERTY_WIFI_PASSWORD, password, 64);
                operation = "Saving Wi-Fi";
                operationCancellable = false;
                publish();
                send(networkPacket, (reply, error) -> {
                    if (error != null) { endOperation("Could not confirm the Wi-Fi name: " + error); return; }
                    send(passwordPacket, (value, failure) -> endOperation(failure == null
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
                send(stringSetting(9, MokukuProtocol.PROPERTY_REMOTE_ADDRESS, normalized, 255),
                        (reply, error) -> report(error == null ? "Remote URL saved to MOKUKU." : error));
            } catch (IllegalArgumentException exception) { report(exception.getMessage()); }
        });
    }

    private byte[] stringSetting(int id, int property, String value, int maximumBytes) {
        return mokukuII ? set(property, MokukuProtocol.utf8(value, MokukuProtocol.MOKUKU_II_STRING_MAXIMUM_BYTES))
                : MokukuProtocol.stringMessage(id, value, maximumBytes);
    }

    void sendVehicleData(int speed, int rpm, int fuel) {
        worker.post(() -> {
            if (!canStart()) return;
            try {
                request(mokukuII ? MokukuProtocol.MESSAGE : MokukuProtocol.DATA, MokukuProtocol.vehicleData(speed, rpm, fuel), -1,
                        (reply, error) -> report(error == null ? "Vehicle data sent: " + speed + " km/h, " + rpm + " RPM, " + fuel + "% fuel." : error));
            } catch (IllegalArgumentException exception) { report(exception.getMessage()); }
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

    // MOKUKU II needs no recording access code.
    private void authorize(Reply callback) {
        if (mokukuII) { callback.complete(null, null); return; }
        if (recordingAccessCode.isEmpty()) {
            callback.complete(null, "Enter an access code in Data Collection to access recordings."); return;
        }
        try {
            request(MokukuProtocol.MESSAGE, MokukuProtocol.stringMessage(44, recordingAccessCode, 255), -1, callback);
        } catch (IllegalArgumentException exception) { callback.complete(null, "Enter a valid recording access code."); }
    }

    private void list(Names callback) {
        String directory = MokukuProtocol.recordDirectory(mokukuII);
        if (mokukuII) { listFrom(new ArrayList<>(), directory, callback); return; }
        send(MokukuProtocol.stringMessage(60, directory, 255), (reply, error) -> {
            if (error != null) callback.complete(null, error);
            else callback.complete(MokukuProtocol.recordings(Arrays.asList(MokukuProtocol.textReply(reply, 60).split(","))), null);
        });
    }

    // MOKUKU II returns one notification of names per request; an empty page ends the list.
    private void listFrom(List<String> names, String directory, Names callback) {
        send(MokukuProtocol.listDirectory(names.size(), directory), (reply, error) -> {
            if (error != null) { callback.complete(null, error); return; }
            List<String> page = MokukuProtocol.directoryNames(MokukuProtocol.propertyValue(reply));
            if (page.isEmpty()) { callback.complete(MokukuProtocol.recordings(names), null); return; }
            names.addAll(page);
            listFrom(names, directory, callback);
        });
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
                list((names, failure) -> {
                    if (id != operationId) return;
                    if (failure == null) recordings = names;
                    endOperation(failure == null ? "Recordings refreshed." + (mokukuII ? "" : " The firmware may return a partial list.") : failure);
                });
            });
        });
    }

    // Downloads arrive here before they are saved to the phone's Download folder.
    private File recordingDirectory() {
        return new File(context.getCacheDir(), "recordings");
    }

    private void deletePartialDownloads() {
        File[] localFiles = recordingDirectory().listFiles();
        if (localFiles == null) return;
        for (File local : localFiles) if (local.isFile() && local.getName().endsWith(".partial")) local.delete();
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
                if (mokukuII) { clearMokukuII(id); return; }
                // Stop recording before touching files; command acceptance is not state readback.
                send(MokukuProtocol.command(2), (value, failure) -> {
                    if (id != operationId) return;
                    if (failure != null) endOperation(failure);
                    else if (all) nextDelete(id, null);
                    else deleteThenList(id, name, false);
                });
            });
        });
    }

    // MOKUKU II deletes every recording in one request and refuses while recording.
    private void clearMokukuII(int id) {
        send(set(MokukuProtocol.PROPERTY_DELETE_RECORDINGS, new byte[0]), (reply, error) -> {
            if (id != operationId) return;
            if (error != null) { endOperation("Clearing failed. Stop any recording and try again. " + error); return; }
            list((names, failure) -> {
                if (id != operationId) return;
                if (failure == null) recordings = names;
                endOperation(failure != null ? failure : names.isEmpty()
                        ? "Device recordings cleared. Phone copies are kept." : "Some recordings are still returned. Refresh and retry.");
            });
        });
    }

    private void nextDelete(int id, String previousName) {
        list((names, error) -> {
            if (id != operationId) return;
            if (error != null) { endOperation("Clearing stopped: " + error); return; }
            recordings = names;
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
            request(MokukuProtocol.MESSAGE, MokukuProtocol.stringMessage(62, MokukuProtocol.recordPath(name, false), 255), -1, (reply, error) -> {
                if (id != operationId) return;
                if (error != null) { endOperation(error); return; }
                if (all) { nextDelete(id, name); return; }
                list((names, failure) -> {
                    if (id != operationId) return;
                    if (failure == null) {
                        recordings = names;
                        if (recordings.contains(name)) failure = "The recording is still returned. It may not have been deleted.";
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
            request(MokukuProtocol.FILE_COMMAND, MokukuProtocol.startDownload(MokukuProtocol.recordPath(downloadName, mokukuII)), -1, (reply, error) -> {
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
            if (mokukuII) endOperation("MOKUKU II refused the recording. Refresh recordings and try again.");
            else if (++downloadRetries > DOWNLOAD_RETRY_LIMIT) endOperation(refused.getMessage());
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
            String location = saver.save(partial, downloadName);
            saved.put(downloadName, location);
            endOperation("Saved " + downloadName + " to " + location + ".");
        } catch (IOException exception) {
            endOperation("Could not save the recording: " + exception.getMessage());
        } catch (SecurityException exception) {
            endOperation("Allow storage access to save recordings to Downloads.");
        }
    }

    // Asks MOKUKU to resend the current sector after a CRC failure, a gap or a stall.
    private void retrySector() {
        worker.removeCallbacks(sectorTimeout);
        if (firmwareImage != null) { resendFirmwareSector(); return; }
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
        writeFileData(MokukuProtocol.acknowledgement(acknowledged, status, sector),
                (reply, error) -> { if (id == operationId && error != null) endOperation(error); });
    }

    private void writeFileData(byte[] packet, Reply callback) {
        boolean withoutResponse = (fileData.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        request(MokukuProtocol.FILE_DATA, packet, -1, withoutResponse, callback);
    }

    void updateFirmware(android.net.Uri image) {
        worker.post(() -> {
            if (!canStart()) return;
            if (!mokukuII) { report("Firmware images are sent only to MOKUKU II."); return; }
            byte[] bytes;
            try (java.io.InputStream input = context.getContentResolver().openInputStream(image)) {
                if (input == null) throw new IOException("The firmware image could not be opened.");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[65536];
                for (int length; (length = input.read(chunk)) > 0; ) {
                    buffer.write(chunk, 0, length);
                    if (buffer.size() > FIRMWARE_IMAGE_MAXIMUM_BYTES) throw new IOException("The file is too large for a firmware image.");
                }
                bytes = buffer.toByteArray();
            } catch (IOException | SecurityException exception) { report("Could not read the firmware image: " + exception.getMessage()); return; }
            if (bytes.length == 0) { report("The firmware image is empty."); return; }
            operation = FIRMWARE_UPDATE;
            operationCancellable = true;
            received = 0;
            total = 0;
            firmwareImage = bytes;
            int id = ++operationId;
            setDownloadPriority(true);
            publish();
            request(MokukuProtocol.FILE_COMMAND, MokukuProtocol.startFirmwareUpdate(bytes.length), -1, (reply, error) -> {
                if (id == operationId && error != null) endOperation(error);
            });
            worker.postDelayed(sectorTimeout, SECTOR_TIMEOUT_MILLISECONDS);
        });
    }

    private void acceptFirmwareUpdateStart(byte[] frame) {
        if (total > 0) return;
        try {
            int[] started = MokukuProtocol.firmwareUpdateStarted(frame);
            if (started == null) return;
            sectorSize = started[0];
            packetSize = Math.min(started[1], mtu - 3);
            total = firmwareImage.length;
            sector = 0;
            downloadRetries = 0;
            downloadStartedAt = android.os.SystemClock.elapsedRealtime();
            Log.i(TAG, "Firmware update transport: MTU=" + mtu + ", sector=" + sectorSize + ", packet=" + packetSize);
            publish();
            sendFirmwareSector();
        } catch (IllegalArgumentException exception) { endOperation(exception.getMessage()); }
    }

    private void sendFirmwareSector() {
        worker.removeCallbacks(sectorTimeout);
        int id = operationId;
        int offset = (int) Math.min((long) sector * sectorSize, total);
        int length = (int) Math.min(sectorSize, total - offset);
        List<byte[]> packets = MokukuProtocol.sectorPackets(sector, firmwareImage, offset, length, packetSize);
        for (int i = 0; i < packets.size(); i++) {
            boolean last = i == packets.size() - 1;
            writeFileData(packets.get(i), (reply, error) -> {
                if (id != operationId) return;
                if (error != null) endOperation(error);
                // Wait for the acknowledgement only once the whole sector is written.
                else if (last) worker.postDelayed(sectorTimeout, SECTOR_TIMEOUT_MILLISECONDS);
            });
        }
    }

    private void acceptFirmwareAcknowledgement(byte[] frame) {
        int[] acknowledgement = MokukuProtocol.sectorAcknowledgement(frame);
        if (acknowledgement == null || acknowledgement[0] != sector) return;
        worker.removeCallbacks(sectorTimeout);
        int status = acknowledgement[1];
        if (status == MokukuProtocol.SECTOR_WRITE_ERROR) {
            endOperation("MOKUKU II could not write the firmware image. It restarts into its current firmware."); return;
        }
        if (status == MokukuProtocol.SECTOR_OK) {
            downloadRetries = 0;
            received = Math.min(total, (long) (sector + 1) * sectorSize);
            if (received >= total) {
                // MOKUKU II checks the image, then restarts into it.
                firmwareImage = null;
                disconnectOnWorker("Firmware sent. MOKUKU II restarts into it; reconnect to check the version.");
                return;
            }
            publish();
        } else if (++downloadRetries > DOWNLOAD_RETRY_LIMIT) {
            endOperation("MOKUKU II did not accept the firmware image. Try again."); return;
        }
        sector = acknowledgement[2];
        sendFirmwareSector();
    }

    private void resendFirmwareSector() {
        if (total == 0) { endOperation("MOKUKU II did not start the firmware update. Reconnect and try again."); return; }
        if (++downloadRetries > DOWNLOAD_RETRY_LIMIT) { endOperation("MOKUKU II stopped acknowledging the firmware image. Try again."); return; }
        Log.w(TAG, "Resending firmware sector " + sector + " (attempt " + downloadRetries + ")");
        sendFirmwareSector();
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
        if ((output != null || firmwareImage != null) && ready) {
            request(MokukuProtocol.FILE_COMMAND, MokukuProtocol.stopTransfer(), -1, (reply, error) -> {});
        }
        firmwareImage = null;
        if (output != null) {
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

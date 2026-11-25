package com.mokuku.debug;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BLUETOOTH_PERMISSION = 10;
    private static final int ENABLE_BLUETOOTH = 11;
    private static final int STORAGE_PERMISSION = 12;
    private static final int FIRMWARE_IMAGE = 13;

    private static final class Session {
        MokukuConnection connection;
        String route = "home";
        String wifiName;
        String wifiPassword;
        String remoteUrl;
        String speed = "";
        String rpm = "";
        String fuel = "";
        // Recording to download once Android 9 or older grants storage access.
        String pendingDownload;
        final Map<String, Integer> scroll = new HashMap<>();
        // Trips saved on the phone, parsed off the main thread; null until loaded.
        List<TripStore.Device> trips;
        // The snapshot's tripsVersion that the last load started from.
        int tripsRequestedVersion = -1;
        final ExecutorService tripLoader = Executors.newSingleThreadExecutor();
        TripStore.Trip openTrip;
        MainActivity activity;
    }

    private Session session;
    private MokukuConnection.Snapshot snapshot;
    private Ui ui;
    private LinearLayout content;
    private LinearLayout header;
    private LinearLayout navigation;
    private ScrollView scroll;
    private TextView feedback;
    private TextView connectionLabel;
    private TextView firmwareVersion;
    private TextView memeVersion;
    private final List<View> deviceControls = new ArrayList<>();
    private EditText wifiName;
    private EditText wifiPassword;
    private EditText remoteUrl;
    private EditText speed;
    private EditText rpm;
    private EditText fuel;
    private TextView progressText;
    private ProgressBar progress;
    // MOKUKU II's firmware upload progress on the Updates page, shown while it runs.
    private View firmwareProgress;
    private AlertDialog discovery;
    private AlertDialog recordingAccess;
    private LinearLayout discoveryResults;
    private TextView discoveryStatus;
    private final Map<String, Button> discoveryDevices = new HashMap<>();
    private OnBackInvokedCallback backCallback;
    private org.osmdroid.views.MapView map;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);
        session = (Session) getLastNonConfigurationInstance();
        if (session == null) {
            session = new Session();
            session.connection = new MokukuConnection(this);
            SharedPreferences sources = updateSourcePreferences();
            session.wifiName = sources.getString("wifi_name", "");
            session.wifiPassword = sources.getString("wifi_password", "");
            session.remoteUrl = sources.getString("remote_url", "");
            if (savedInstanceState != null) session.route = savedInstanceState.getString("route", "home");
        }
        session.activity = this;
        snapshot = session.connection.snapshot();
        setContentView(R.layout.activity_main);
        content = findViewById(R.id.content);
        header = findViewById(R.id.header);
        navigation = findViewById(R.id.navigation);
        feedback = findViewById(R.id.feedback);
        scroll = findViewById(R.id.content_scroll);
        session.connection.setListener(this::changed);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = () -> { if (!goBack()) finish(); };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        render();
        if (snapshot.scanning) showDiscovery();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        captureDrafts();
        session.scroll.put(session.route, scroll.getScrollY());
        return session;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("route", session.route);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
    }

    @Override protected void onPause() {
        if (map != null) map.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (session.activity == this) session.activity = null;
        releaseMap();
        session.connection.setListener(null);
        if (recordingAccess != null) recordingAccess.dismiss();
        if (discovery != null) {
            discovery.setOnDismissListener(null);
            discovery.dismiss();
        }
        if (!isChangingConfigurations()) {
            session.connection.close();
            session.tripLoader.shutdown();
        }
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        super.onDestroy();
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (!goBack()) super.onBackPressed();
    }

    private boolean goBack() {
        if ("home".equals(session.route)) return false;
        String parent = parentRoute(session.route);
        navigate(parent == null ? "home" : parent);
        return true;
    }

    private void captureDrafts() {
        if (wifiName != null) {
            session.wifiName = wifiName.getText().toString();
            session.wifiPassword = wifiPassword.getText().toString();
        }
        if (remoteUrl != null) session.remoteUrl = remoteUrl.getText().toString();
        if (speed != null) {
            session.speed = speed.getText().toString();
            session.rpm = rpm.getText().toString();
            session.fuel = fuel.getText().toString();
        }
    }

    private void navigate(String route) {
        captureDrafts();
        session.scroll.put(session.route, scroll.getScrollY());
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(content.getWindowToken(), 0);
        session.route = route;
        render();
        if ("records".equals(route) && snapshot.connected && canListRecordings() && !snapshot.busy) session.connection.refreshRecordings();
    }

    private boolean canListRecordings() { return snapshot.mokukuII || snapshot.hasRecordingAccessCode; }

    private void changed(MokukuConnection.Snapshot update) {
        MokukuConnection.Snapshot previous = snapshot;
        snapshot = update;
        if (!update.connected && recordingAccess != null) recordingAccess.dismiss();
        feedback.setText(update.message);
        if (discovery != null && discovery.isShowing()) {
            if (update.connecting || update.connected) discovery.dismiss();
            else { renderDiscovery(); return; }
        }
        if (previous.connected != update.connected || previous.connecting != update.connecting) {
            captureDrafts();
            session.scroll.put(session.route, scroll.getScrollY());
            render();
        } else if (("home".equals(session.route) || "tools".equals(session.route) || "debug".equals(session.route))
                && (!previous.information.equals(update.information) || !previous.errors.equals(update.errors)
                || previous.busy != update.busy || !previous.name.equals(update.name))) {
            session.scroll.put(session.route, scroll.getScrollY());
            render();
        } else if ("trips".equals(session.route)) {
            if (previous.busy != update.busy || previous.tripsVersion != update.tripsVersion || !java.util.Objects.equals(previous.tripStatus, update.tripStatus)
                    || !java.util.Objects.equals(previous.information.get(MokukuProtocol.TRIP_RECORDING), update.information.get(MokukuProtocol.TRIP_RECORDING))
                    || previous.errors.containsKey(MokukuProtocol.TRIP_RECORDING) != update.errors.containsKey(MokukuProtocol.TRIP_RECORDING)) {
                session.scroll.put(session.route, scroll.getScrollY());
                render();
            } else updateProgress();
        } else if ("records".equals(session.route)) {
            boolean sameTransfer = previous.operation != null && previous.operation.equals(update.operation)
                    && update.operation.startsWith("Downloading ") && progress != null;
            if (sameTransfer) updateProgress();
            else { session.scroll.put(session.route, scroll.getScrollY()); render(); }
        } else updateProgress();
        updateVersions();
        updateControls();
    }

    private void render() {
        releaseMap();
        content.removeAllViews(); header.removeAllViews(); navigation.removeAllViews();
        deviceControls.clear();
        wifiName = null; wifiPassword = null; remoteUrl = null;
        speed = null; rpm = null; fuel = null;
        firmwareVersion = null; memeVersion = null;
        progress = null; progressText = null; firmwareProgress = null;
        String parent = parentRoute(session.route);
        boolean subpage = parent != null;
        if (subpage) {
            Button back = ui.button("‹", false, false);
            back.setContentDescription("Back to " + Character.toUpperCase(parent.charAt(0)) + parent.substring(1));
            back.setOnClickListener(view -> goBack());
            header.addView(back, new LinearLayout.LayoutParams(ui.dp(48), -2));
        }
        TextView title = ui.text(subpage ? pageName() : "MOKUKU", subpage ? 20 : 19, R.color.text_primary);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        connectionLabel = ui.text(snapshot.connected ? "● Connected" : snapshot.connecting ? "Connecting…" : "Disconnected", 12,
                snapshot.connected ? R.color.accent : R.color.text_secondary);
        header.addView(connectionLabel);
        feedback.setText(snapshot.message);
        navigation.setVisibility(subpage ? View.GONE : View.VISIBLE);
        if (!subpage) {
            addTab("home", "Home", R.drawable.ic_home);
            addTab("records", "Data Collection", R.drawable.ic_records);
            addTab("trips", "Trips", R.drawable.ic_trips);
            addTab("tools", "Tools", R.drawable.ic_tools);
        }
        switch (session.route) {
            case "updates": updates(); break;
            case "debug": debug(); break;
            case "simulation": simulation(); break;
            case "records": records(); break;
            case "trips": trips(); break;
            case "trip": trip(); break;
            case "tools": tools(); break;
            default: session.route = "home"; home(); break;
        }
        updateControls();
        int position = session.scroll.containsKey(session.route) ? session.scroll.get(session.route) : 0;
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    // The tab a subpage's Back returns to; null for the tabs themselves.
    private static String parentRoute(String route) {
        switch (route) {
            case "updates": case "debug": return "tools";
            case "simulation": return "home";
            case "trip": return "trips";
            default: return null;
        }
    }

    private String pageName() {
        switch (session.route) {
            case "updates": return "Updates";
            case "debug": return "Debug";
            case "trip": return "Trip";
            default: return "Simulation";
        }
    }

    private void addTab(String route, String label, int drawable) {
        LinearLayout tab = ui.column();
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(10));
        tab.setMinimumHeight(ui.dp(64));
        boolean selected = route.equals(session.route);
        if (selected) tab.setBackground(ui.background(R.color.raised, 0, 14));
        int tint = selected ? R.color.accent : R.color.text_secondary;
        tab.addView(ui.icon(drawable, tint), new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        TextView text = ui.text(label, 12, tint);
        text.setGravity(Gravity.CENTER);
        tab.addView(text);
        tab.setContentDescription(label + (selected ? ", selected" : ""));
        tab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        tab.setFocusable(true);
        tab.setOnClickListener(view -> navigate(route));
        navigation.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void intro(String title, String description) {
        content.addView(ui.text(title, 29, R.color.text_primary));
        ui.gap(content, 8);
        content.addView(ui.muted(description));
        ui.gap(content, 24);
    }

    private void section(String title) {
        ui.gap(content, 12);
        content.addView(ui.text(title, 18, R.color.text_primary));
        ui.gap(content, 12);
    }

    private Button deviceButton(LinearLayout parent, String label, boolean primary, boolean danger, Runnable action) {
        Button button = ui.button(label, primary, danger);
        button.setOnClickListener(view -> action.run());
        deviceControls.add(button);
        ui.fullButton(parent, button);
        return button;
    }

    private void updateControls() {
        for (View control : deviceControls) {
            control.setEnabled(snapshot.connected && !snapshot.busy);
            control.setAlpha(control.isEnabled() ? 1 : .45f);
        }
    }

    private void shortcut(String title, String description, int drawable, String route) {
        LinearLayout card = ui.card(content);
        LinearLayout row = ui.row();
        row.addView(ui.icon(drawable, R.color.accent), new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        LinearLayout labels = ui.column();
        labels.setPadding(ui.dp(16), 0, 0, 0);
        labels.addView(ui.text(title, 17, R.color.text_primary));
        ui.gap(labels, 4);
        labels.addView(ui.muted(description));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
        card.setMinimumHeight(ui.dp(80));
        card.setContentDescription(title + ". " + description);
        card.setFocusable(true);
        card.setOnClickListener(view -> navigate(route));
    }

    private void home() {
        intro("Hello, MOKUKU.", "A little closer to your companion.");
        LinearLayout device = ui.card(content);
        LinearLayout row = ui.row();
        LinearLayout labels = ui.column();
        labels.addView(ui.text(snapshot.connected ? snapshot.name : snapshot.connecting ? "Connecting…" : "Let’s find your companion.", 21, R.color.text_primary));
        labels.addView(ui.muted(snapshot.connected ? "Two eyes. One little companion." : "Power on your MOKUKU."));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(ui.eyes(!snapshot.connected), new LinearLayout.LayoutParams(ui.dp(82), ui.dp(80)));
        device.addView(row);
        Button connect = ui.button(snapshot.connected ? "Disconnect" : snapshot.connecting ? "Cancel connection" : "Find MOKUKU", !snapshot.connected, false);
        connect.setOnClickListener(view -> {
            if (snapshot.connected) confirm("Disconnect MOKUKU?", "Reconnect whenever you’re ready.", "Disconnect", session.connection::disconnect);
            else if (snapshot.connecting) session.connection.disconnect();
            else findDevice();
        });
        ui.fullButton(device, connect);
        section("Device overview");
        LinearLayout firstRow = overviewRow();
        // MOKUKU II reports one version; the original reports left/right.
        String[] versions = information(3).split("/", -1);
        overview(overviewColumn(firstRow, true), "Software", versions.length == 2 ? "Left · INS\n" + versions[0] + "\nRight · AI\n" + versions[1] : information(3), 3);
        overview(overviewColumn(firstRow, false), "SD card", storageText(), 61);
        LinearLayout secondRow = overviewRow();
        String language = snapshot.information.get(MokukuProtocol.PROPERTY_LANGUAGE);
        overview(overviewColumn(secondRow, true), "Voice model", information(57) + (language == null ? "" : " · " + language), 57);
        LinearLayout mac = overview(overviewColumn(secondRow, false), "MAC address", information(4), 4);
        if (snapshot.information.containsKey(4)) {
            Button copy = ui.button("Copy address", false, false);
            copy.setOnClickListener(view -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("MOKUKU MAC address", snapshot.information.get(4)));
                feedback.setText("MAC address copied.");
            });
            ui.fullButton(mac, copy);
        }
        deviceButton(content, "Refresh device information", false, false, session.connection::refreshInformation);
        section("A little interaction");
        shortcut("Simulation", snapshot.mokukuII ? "Next panels and meme/data mode" : "Left/right clicks and eye mode", R.drawable.ic_eyes, "simulation");
    }

    private String information(int id) {
        String value = snapshot.information.get(id);
        return value == null ? snapshot.errors.containsKey(id) ? "Unavailable" : "—" : value;
    }

    private String storageText() {
        String value = snapshot.information.get(61);
        if (value == null) return information(61);
        String[] sizes = value.split("/");
        return sizes.length == 2 ? sizes[0] + " MB free / " + sizes[1] + " MB total" : "Unavailable";
    }

    private LinearLayout overviewRow() {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.TOP);
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private LinearLayout overviewColumn(LinearLayout row, boolean first) {
        LinearLayout column = ui.column();
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(0, -2, 1);
        if (first) parameters.rightMargin = ui.dp(12);
        row.addView(column, parameters);
        return column;
    }

    private LinearLayout overview(LinearLayout parent, String title, String value, int id) {
        LinearLayout card = ui.card(parent);
        card.setMinimumHeight(ui.dp(130));
        card.addView(ui.muted(title));
        ui.gap(card, 10);
        TextView information = ui.text(value, 16, R.color.text_primary);
        if (id == 4) { information.setTextSize(12); information.setTypeface(Typeface.MONOSPACE); }
        information.setTextIsSelectable(true);
        card.addView(information);
        if (snapshot.errors.containsKey(id)) {
            ui.gap(card, 8);
            card.addView(ui.muted("Last query: " + snapshot.errors.get(id)));
        }
        return card;
    }

    private void tools() {
        intro("Your device, your way.", "A few useful things, whenever you need them.");
        shortcut("Updates", snapshot.mokukuII ? "Wi-Fi, remote URL, firmware & voice" : "Wi-Fi, remote URL, firmware, voice & memes", R.drawable.ic_update, "updates");
        shortcut("Debug", snapshot.mokukuII ? "OBD & recordings" : "OBD, logs, date & recording", R.drawable.ic_debug, "debug");
    }

    private EditText field(LinearLayout parent, String label, String hint, int type) {
        ui.gap(parent, 16);
        TextView caption = ui.muted(label);
        parent.addView(caption);
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
        field.setContentDescription(label);
        field.setInputType(type);
        field.setTextSize(16);
        field.setTextColor(ui.color(R.color.text_primary));
        field.setHintTextColor(ui.color(R.color.text_secondary));
        field.setHint(hint);
        field.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        field.setMinHeight(ui.dp(48));
        field.setBackground(ui.background(R.color.background, R.color.border, 14));
        field.setSaveEnabled(false);
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(-1, -2);
        parameters.topMargin = ui.dp(8);
        parent.addView(field, parameters);
        deviceControls.add(field);
        return field;
    }

    private void updates() {
        intro("Keep the good going.", "A fresh update for your little companion.");
        // MOKUKU II updates over BLE, so its Wi-Fi and remote URL settings go last.
        if (snapshot.mokukuII) { mokukuIIUpdates(); updateSources(); return; }
        updateSources();
        LinearLayout firmware = ui.card(content);
        firmware.addView(ui.text("Firmware OTA", 18, R.color.text_primary));
        firmwareVersion = ui.muted("");
        firmware.addView(firmwareVersion);
        firmware.addView(ui.muted("Right eye first, then left eye."));
        updateButton(firmware, "Update right eye", 67, "right-eye firmware update");
        updateButton(firmware, "Update left eye", 66, "left-eye firmware update");
        deviceButton(firmware, "Update both eyes", false, false, () -> confirm("Update both eyes?",
                "MOKUKU downloads both firmware updates using its saved Wi-Fi and remote URL. The app requests the right eye, then the left eye 500 ms later. The left eye restarts, so the app disconnects and keeps reconnecting for up to 5 minutes.",
                "Update", session.connection::updateBothEyes));
        LinearLayout voice = ui.card(content);
        voice.addView(ui.text("Voice model", 18, R.color.text_primary));
        voice.addView(ui.muted("Updates the right-eye model and both eyes’ wake-word assets."));
        updateButton(voice, "English", 72, "English voice update for both eyes");
        updateButton(voice, "Mandarin", 73, "Mandarin voice update for both eyes");
        LinearLayout memes = ui.card(content);
        memes.addView(ui.text("Meme assets", 18, R.color.text_primary));
        memeVersion = ui.muted("");
        memes.addView(memeVersion);
        memes.addView(ui.muted("Update the right eye before the left eye."));
        updateButton(memes, "Update right eye", 69, "right-eye meme update");
        updateButton(memes, "Update left eye", 68, "left-eye meme update");
        LinearLayout rollback = ui.card(content);
        rollback.addView(ui.text("Rollback", 18, R.color.text_primary));
        updateButton(rollback, "Rollback right eye", 71, "right-eye rollback");
        updateButton(rollback, "Rollback left eye", 70, "left-eye rollback");
        updateVersions();
    }

    // The last Wi-Fi and remote URL saved to a device, restored on the next launch.
    private SharedPreferences updateSourcePreferences() {
        return getSharedPreferences("update_sources", MODE_PRIVATE);
    }

    private void updateSources() {
        LinearLayout wifi = ui.card(content);
        wifi.addView(ui.text("Wi-Fi", 18, R.color.text_primary));
        wifi.addView(ui.muted("MOKUKU downloads updates over Wi-Fi."));
        wifiName = field(wifi, "Wi-Fi name", "Enter network name", InputType.TYPE_CLASS_TEXT);
        wifiName.setText(session.wifiName);
        wifiPassword = passwordField(wifi, "Wi-Fi password", "Enter network password");
        wifiPassword.setText(session.wifiPassword);
        Button reveal = ui.button("Show password", false, false);
        reveal.setOnClickListener(view -> {
            boolean hidden = wifiPassword.getTransformationMethod() != null;
            wifiPassword.setTransformationMethod(hidden ? null : android.text.method.PasswordTransformationMethod.getInstance());
            reveal.setText(hidden ? "Hide password" : "Show password");
            wifiPassword.setSelection(wifiPassword.length());
        });
        deviceControls.add(reveal);
        ui.fullButton(wifi, reveal);
        deviceButton(wifi, "Save Wi-Fi", true, false, () -> {
            String network = wifiName.getText().toString();
            String password = wifiPassword.getText().toString();
            updateSourcePreferences().edit().putString("wifi_name", network).putString("wifi_password", password).apply();
            session.connection.saveWifi(network, password);
        });
        LinearLayout remote = ui.card(content);
        remote.addView(ui.text("Remote URL", 18, R.color.text_primary));
        remote.addView(ui.muted("Choose where updates come from."));
        remoteUrl = field(remote, "Remote base URL", "https://updates.example.com/mokuku/", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        remoteUrl.setText(session.remoteUrl);
        deviceButton(remote, "Save remote URL", true, false, () -> {
            String url = remoteUrl.getText().toString().trim();
            updateSourcePreferences().edit().putString("remote_url", url).apply();
            session.connection.saveUrl(url);
        });
    }

    private void mokukuIIUpdates() {
        LinearLayout firmware = ui.card(content);
        firmware.addView(ui.text("Firmware", 18, R.color.text_primary));
        firmwareVersion = ui.muted("");
        firmware.addView(firmwareVersion);
        firmware.addView(ui.muted("Send a MOKUKU II app image from this phone. MOKUKU II restarts into it."));
        deviceButton(firmware, "Choose firmware image", true, false, () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(pick, FIRMWARE_IMAGE);
        });
        LinearLayout transfer = ui.column();
        firmware.addView(transfer);
        progressCard(transfer, MokukuConnection.FIRMWARE_UPDATE, "Cancel update");
        firmwareProgress = transfer;
        LinearLayout voice = ui.card(content);
        voice.addView(ui.text("Voice commands", 18, R.color.text_primary));
        voice.addView(ui.muted("Changing the language restarts MOKUKU II."));
        languageButton(voice, "English", "en");
        languageButton(voice, "Mandarin", "cn");
        LinearLayout rollback = ui.card(content);
        rollback.addView(ui.text("Rollback", 18, R.color.text_primary));
        rollback.addView(ui.muted("Restarts into the other slot's firmware when it is older."));
        deviceButton(rollback, "Roll back firmware", false, true, () -> confirm("Roll back MOKUKU II's firmware?",
                "MOKUKU II restarts into its previous firmware and disconnects.", "Roll back",
                () -> session.connection.setProperty(MokukuProtocol.PROPERTY_ROLLBACK, new byte[0], "Firmware rollback", true)));
        updateVersions();
        updateProgress();
    }

    private void languageButton(LinearLayout parent, String label, String language) {
        deviceButton(parent, label, false, false, () -> confirm("Use " + label + " voice commands?",
                "MOKUKU II restarts if the language changes.", "Use " + label,
                () -> session.connection.setProperty(MokukuProtocol.PROPERTY_LANGUAGE,
                        language.getBytes(java.nio.charset.StandardCharsets.US_ASCII), label + " voice commands", false)));
    }

    private void updateVersions() {
        if (firmwareVersion != null && snapshot.mokukuII) {
            firmwareVersion.setText("Running " + information(3) + "\n" + information(MokukuProtocol.PROPERTY_FIRMWARE_SLOTS));
            return;
        }
        if (firmwareVersion != null) firmwareVersion.setText("Last received firmware: " + information(3));
        if (memeVersion != null) memeVersion.setText("Last received meme versions: " + information(5));
    }

    private void updateButton(LinearLayout parent, String label, int command, String description) {
        boolean rollback = command == 70 || command == 71;
        deviceButton(parent, label, false, rollback, () -> {
            boolean rightEye = MokukuProtocol.isRightEyeUpdate(command);
            String message = (rollback ? "Request the previous firmware for this eye."
                    : "MOKUKU downloads updates using its saved Wi-Fi and remote URL.")
                    + (rightEye ? " The right eye restarts; the app stays connected through the left eye."
                    : " The app will disconnect after the request; reconnect after the update finishes to check versions.");
            if (command == 66 || command == 68) {
                android.widget.CheckBox checked = new android.widget.CheckBox(this);
                checked.setText("The right-eye update is complete.");
                checked.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12));
                AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Request " + description + "?")
                        .setMessage(message).setView(checked).setNegativeButton("Cancel", null)
                        .setPositiveButton("Request", (value, which) -> session.connection.command(command, description)).create();
                dialog.setOnShowListener(value -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    checked.setOnCheckedChangeListener((button, selected) -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(selected));
                });
                dialog.show();
            } else confirm("Request " + description + "?", message, "Request", () -> session.connection.command(command, description));
        });
    }

    private void simulation() {
        intro("A little eye contact.", "Try a tap. See how MOKUKU responds.");
        LinearLayout card = ui.card(content);
        card.addView(ui.eyes(false), new LinearLayout.LayoutParams(-1, ui.dp(112)));
        if (snapshot.mokukuII) {
            propertyButton(card, "Next left panel", MokukuProtocol.PROPERTY_PANEL_ACTION, 1);
            propertyButton(card, "Next right panel", MokukuProtocol.PROPERTY_PANEL_ACTION, 2);
            propertyButton(card, "Switch meme/data mode", MokukuProtocol.PROPERTY_PANEL_ACTION, 3);
            card.addView(ui.muted("From meme mode, a next-panel tap returns both eyes to data panels."));
        } else {
            commandButton(card, "Left click", 43);
            commandButton(card, "Right click", 53);
            commandButton(card, "Change eye mode", 10);
            card.addView(ui.muted("Check MOKUKU’s display for the current mode."));
        }
        vehicleData();
    }

    private void vehicleData() {
        LinearLayout card = ui.card(content);
        card.addView(ui.text("Vehicle data", 18, R.color.text_primary));
        card.addView(ui.muted("Shown on the speed, RPM, and fuel panels. OBD or GPS data can replace it."));
        speed = field(card, "Speed (km/h, 0–255)", "0", InputType.TYPE_CLASS_NUMBER);
        speed.setText(session.speed);
        rpm = field(card, "RPM (0–16383)", "0", InputType.TYPE_CLASS_NUMBER);
        rpm.setText(session.rpm);
        fuel = field(card, "Fuel (%, 0–100)", "0", InputType.TYPE_CLASS_NUMBER);
        fuel.setText(session.fuel);
        deviceButton(card, "Send vehicle data", true, false, () -> {
            try {
                session.connection.sendVehicleData(number(speed), number(rpm), number(fuel));
            } catch (NumberFormatException exception) { feedback.setText("Enter whole numbers for speed, RPM, and fuel."); }
        });
    }

    private static int number(EditText field) {
        String text = field.getText().toString().trim();
        return text.isEmpty() ? 0 : Integer.parseInt(text);
    }

    private void commandButton(LinearLayout parent, String label, int command) {
        deviceButton(parent, label, false, false, () -> session.connection.command(command, label));
    }

    private void propertyButton(LinearLayout parent, String label, int property, int value) {
        deviceButton(parent, label, false, false, () -> session.connection.setProperty(property, new byte[] {(byte) value}, label, false));
    }

    private void debug() {
        intro("A closer look.", "A few useful tools, all in one place.");
        if (snapshot.mokukuII) { mokukuIIDebug(); return; }
        LinearLayout obd = ui.card(content);
        obd.addView(ui.text("OBD connection", 18, R.color.text_primary));
        commandButton(obd, "Enable OBD", 34);
        commandButton(obd, "Disable OBD", 35);
        obd.addView(ui.muted("Actions send requests; current OBD state is not reported."));
        LinearLayout logs = ui.card(content);
        logs.addView(ui.text("Debug log", 18, R.color.text_primary));
        commandButton(logs, "Enable debug log", 4);
        logs.addView(ui.muted("Logging stays enabled for this device session."));
        LinearLayout date = ui.card(content);
        date.addView(ui.text("Date & time", 18, R.color.text_primary));
        deviceButton(date, "Set date & time", false, false, this::chooseDate);
        deviceButton(date, "Use phone time", false, false, () -> confirmDate(Calendar.getInstance()));
        date.addView(ui.muted("GPS can override the debug date."));
        LinearLayout recording = ui.card(content);
        recording.addView(ui.text("Recording", 18, R.color.text_primary));
        commandButton(recording, "Start recording", 1);
        commandButton(recording, "Stop recording", 2);
        Button open = ui.button("Open Data Collection", false, false);
        open.setOnClickListener(view -> navigate("records"));
        ui.fullButton(recording, open);
        recording.addView(ui.muted("Refresh recordings after stopping to find the device file."));
    }

    private void mokukuIIDebug() {
        LinearLayout obd = ui.card(content);
        obd.addView(ui.text("OBD connection", 18, R.color.text_primary));
        obd.addView(ui.muted("Current: " + information(MokukuProtocol.PROPERTY_OBD_ENABLED)));
        propertyButton(obd, "Enable OBD", MokukuProtocol.PROPERTY_OBD_ENABLED, 1);
        propertyButton(obd, "Disable OBD", MokukuProtocol.PROPERTY_OBD_ENABLED, 0);
        LinearLayout recording = ui.card(content);
        recording.addView(ui.text("Recording", 18, R.color.text_primary));
        recording.addView(ui.muted("Start and stop recordings by voice in developer mode."));
        Button open = ui.button("Open Data Collection", false, false);
        open.setOnClickListener(view -> navigate("records"));
        ui.fullButton(recording, open);
    }

    private void chooseDate() {
        Calendar value = Calendar.getInstance();
        new DatePickerDialog(this, (picker, year, month, day) -> {
            value.set(year, month, day);
            new TimePickerDialog(this, (clock, hour, minute) -> {
                value.set(Calendar.HOUR_OF_DAY, hour);
                value.set(Calendar.MINUTE, minute);
                confirmDate(value);
            }, value.get(Calendar.HOUR_OF_DAY), value.get(Calendar.MINUTE), true).show();
        }, value.get(Calendar.YEAR), value.get(Calendar.MONTH), value.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void confirmDate(Calendar value) {
        confirm("Set device date & time?", DateFormat.getDateTimeInstance().format(value.getTime()) + "\nUses these wall-clock fields on MOKUKU. GPS can override the debug date.",
                "Set date & time", () -> session.connection.setDateTime(value.get(Calendar.YEAR), value.get(Calendar.MONTH), value.get(Calendar.DAY_OF_MONTH), value.get(Calendar.HOUR_OF_DAY), value.get(Calendar.MINUTE)));
    }

    private void records() {
        intro("Data Collection", "Little moments. Useful details.");
        if (!snapshot.connected) {
            content.addView(ui.muted("Connect to MOKUKU to list its recordings."));
            Button find = ui.button("Find MOKUKU", true, false);
            find.setOnClickListener(view -> findDevice());
            ui.fullButton(content, find);
            ui.gap(content, 14);
        }
        overview(content, "MOKUKU storage", storageText(), 61);
        if (!snapshot.mokukuII) recordingAccess();
        deviceButton(content, "Refresh recordings", false, false, () -> {
            if (canListRecordings()) session.connection.refreshRecordings();
            else enterRecordingAccessCode();
        });
        recordingsList();
    }

    private void recordingAccess() {
        LinearLayout access = ui.card(content);
        access.addView(ui.text("Recording access", 18, R.color.text_primary));
        access.addView(ui.muted(snapshot.hasRecordingAccessCode
                ? "Access code saved for this MOKUKU. Reused when you reconnect."
                : "Enter your MOKUKU access code to load and manage recordings."));
        deviceButton(access, snapshot.hasRecordingAccessCode ? "Change access code" : "Enter access code", false, false, this::enterRecordingAccessCode);
        if (snapshot.hasRecordingAccessCode) {
            Button forget = ui.button("Forget access code", false, true);
            forget.setEnabled(!snapshot.busy);
            forget.setOnClickListener(view -> session.connection.forgetRecordingAccessCode());
            ui.fullButton(access, forget);
        }
    }

    private void recordingsList() {
        section("Returned recordings (" + snapshot.recordings.size() + ")");
        if (snapshot.busy) {
            progressCard(ui.card(content), snapshot.operation, snapshot.cancellable ? "Cancel operation" : null);
            updateProgress();
        }
        if (snapshot.recordings.isEmpty()) content.addView(ui.muted(snapshot.busy ? "Waiting for MOKUKU…" : "No recordings returned. Refresh after stopping a recording."));
        for (String name : snapshot.recordings) {
            LinearLayout card = ui.card(content);
            TextView filename = ui.text(name, 15, R.color.text_primary);
            filename.setTypeface(Typeface.MONOSPACE);
            card.addView(filename);
            String location = snapshot.saved.get(name);
            card.addView(ui.muted(location == null ? "On MOKUKU · device recording" : "Saved to " + location));
            deviceButton(card, location == null ? "Download to phone" : "Download again", false, false, () -> downloadRecording(name));
            if (!snapshot.mokukuII) deviceButton(card, "Delete from MOKUKU", false, true, () -> confirm("Delete device recording?", name + "\nRequests recording stop before deleting. Phone copies are kept.", "Delete", () -> session.connection.deleteRecording(name, false)));
        }
        content.addView(ui.muted(snapshot.mokukuII ? "The firmware returns filenames only. Device deletion keeps phone copies."
                : "The firmware returns filenames only and may return a partial list. Device deletion keeps phone copies."));
        if (!snapshot.recordings.isEmpty()) deviceButton(content, "Clear all recordings", false, true, () -> confirm("Clear all recordings?",
                (snapshot.mokukuII ? "Removes every device recording, including ones you haven’t downloaded. MOKUKU II refuses while recording."
                        : "Requests recording stop, then removes device recordings, including ones you haven’t downloaded.") + " Phone copies are kept.", "Clear all recordings", () -> session.connection.deleteRecording(null, true)));
    }

    private void progressCard(LinearLayout parent, String title, String cancelLabel) {
        parent.addView(ui.text(title, 16, R.color.accent));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.color(R.color.accent)));
        parent.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(12)));
        progressText = ui.muted("");
        parent.addView(progressText);
        if (cancelLabel == null) return;
        Button cancel = ui.button(cancelLabel, false, false);
        cancel.setOnClickListener(view -> session.connection.cancelOperation());
        ui.fullButton(parent, cancel);
    }

    private EditText passwordField(LinearLayout parent, String label, String hint) {
        EditText input = field(parent, label, hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return input;
    }

    private void enterRecordingAccessCode() {
        if (recordingAccess != null) return;
        LinearLayout form = ui.column();
        form.setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(16));
        EditText input = passwordField(form, "Recording access code", "Enter access code");
        input.setSingleLine(true);
        android.widget.CheckBox reveal = new android.widget.CheckBox(this);
        reveal.setText("Show access code");
        reveal.setMinHeight(ui.dp(48));
        reveal.setOnCheckedChangeListener((button, checked) -> {
            input.setTransformationMethod(checked ? null : android.text.method.PasswordTransformationMethod.getInstance());
            input.setSelection(input.length());
        });
        form.addView(reveal);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Recording access")
                .setMessage("Saved on this phone for this MOKUKU and reused when you reconnect.")
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Use code", null).create();
        recordingAccess = dialog;
        dialog.setOnShowListener(value -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String code = input.getText().toString();
            try {
                MokukuProtocol.stringMessage(44, code, 255);
                session.connection.setRecordingAccessCode(code, "trips".equals(session.route));
                dialog.dismiss();
            } catch (IllegalArgumentException exception) { input.setError(exception.getMessage()); }
        }));
        dialog.setOnDismissListener(value -> {
            input.setText("");
            deviceControls.remove(input);
            recordingAccess = null;
        });
        dialog.show();
    }

    private void updateProgress() {
        if (progress == null) return;
        if (firmwareProgress != null) {
            boolean updating = MokukuConnection.FIRMWARE_UPDATE.equals(snapshot.operation);
            firmwareProgress.setVisibility(updating ? View.VISIBLE : View.GONE);
            if (!updating) return;
        }
        progress.setIndeterminate(snapshot.total == 0);
        if (snapshot.total > 0) progress.setProgress((int) (100 * snapshot.received / snapshot.total));
        progressText.setText(Ui.downloadProgressText(snapshot.received, snapshot.total, snapshot.downloadElapsedMilliseconds));
    }

    private void downloadRecording(String name) {
        if (PhoneDownloads.needsStoragePermission()
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            session.pendingDownload = name;
            requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION);
            return;
        }
        session.connection.download(name);
    }

    private void trips() {
        intro("Trips", "Every drive with MOKUKU, kept on this phone.");
        syncCard();
        if (snapshot.connected) tripRecordingCard();
        loadTrips();
        if (session.trips == null) { content.addView(ui.muted("Loading trips…")); return; }
        if (session.trips.isEmpty()) {
            ui.gap(content, 40);
            content.addView(ui.eyes(true), new LinearLayout.LayoutParams(-1, ui.dp(100)));
            TextView title = ui.text("No trips on this phone yet.", 25, R.color.text_primary);
            title.setGravity(Gravity.CENTER);
            content.addView(title);
            ui.gap(content, 16);
            TextView message = ui.muted("Connect to MOKUKU and sync. Trips are saved here, grouped by device.");
            message.setGravity(Gravity.CENTER);
            content.addView(message);
            return;
        }
        // The connected MOKUKU first, then the device with the newest trip.
        String connected = snapshot.connected ? snapshot.address : null;
        List<TripStore.Device> devices = new ArrayList<>(session.trips);
        java.util.Collections.sort(devices, (left, right) -> {
            int current = Boolean.compare(right.address.equals(connected), left.address.equals(connected));
            return current != 0 ? current : Long.compare(right.trips.get(0).log.startUnixMilliseconds, left.trips.get(0).log.startUnixMilliseconds);
        });
        for (TripStore.Device device : devices) tripGroup(device, device.address.equals(connected));
    }

    private void tripRecordingCard() {
        LinearLayout card = ui.card(content);
        card.addView(ui.text("Trip recording", 18, R.color.text_primary));
        String state = information(MokukuProtocol.TRIP_RECORDING);
        card.addView(ui.muted("Current: " + state + ". MOKUKU records trips only while this is on; it starts off."));
        boolean on = "On".equals(state);
        deviceButton(card, on ? "Turn off trip recording" : "Turn on trip recording", false, false,
                () -> session.connection.setTripRecording(!on));
    }

    private void syncCard() {
        LinearLayout card = ui.card(content);
        if (MokukuConnection.TRIP_SYNC.equals(snapshot.operation)) {
            card.addView(ui.text("Syncing trips", 18, R.color.text_primary));
            ui.gap(card, 8);
            progressCard(card, snapshot.tripStatus == null ? "Starting…" : snapshot.tripStatus, "Cancel sync");
            updateProgress();
            return;
        }
        card.addView(ui.text(snapshot.connected ? snapshot.name : "Sync with MOKUKU", 18, R.color.text_primary));
        card.addView(ui.muted(snapshot.connected ? "Downloads the trips this phone doesn’t have yet."
                : "Connect to download new trips. Saved trips stay on this phone."));
        deviceButton(card, "Sync trips", true, false, () -> syncTrips(false));
        deviceButton(card, "Delete older trips on MOKUKU", false, true, () -> confirm("Delete older trips on MOKUKU?",
                "MOKUKU syncs first, then deletes every trip on its SD card except the latest. Phone copies are kept. "
                        + "If any trip fails to sync, nothing is deleted.", "Sync and delete", () -> syncTrips(true)));
    }

    private void syncTrips(boolean deleteOlder) {
        if (snapshot.mokukuII || snapshot.hasRecordingAccessCode) session.connection.syncTrips(deleteOlder);
        else enterRecordingAccessCode();
    }

    private void loadTrips() {
        if (session.tripsRequestedVersion == snapshot.tripsVersion) return;
        session.tripsRequestedVersion = snapshot.tripsVersion;
        Session current = session;
        TripStore store = new TripStore(this);
        Handler main = new Handler(Looper.getMainLooper());
        current.tripLoader.execute(() -> {
            List<TripStore.Device> devices = store.load();
            main.post(() -> {
                current.trips = devices;
                MainActivity activity = current.activity;
                if (activity != null && ("trips".equals(current.route) || "trip".equals(current.route))) {
                    current.scroll.put(current.route, activity.scroll.getScrollY());
                    activity.render();
                }
            });
        });
    }

    private void tripGroup(TripStore.Device device, boolean connected) {
        LinearLayout group = ui.column();
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(-1, -2);
        parameters.topMargin = ui.dp(connected ? 14 : 22);
        if (connected) {
            group.setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(2));
            group.setBackground(ui.background(R.color.connected_group, R.color.connected_border, 22));
        }
        content.addView(group, parameters);
        LinearLayout heading = ui.row();
        heading.addView(ui.text(device.name + " (" + device.trips.size() + ")", 17, connected ? R.color.accent : R.color.text_primary),
                new LinearLayout.LayoutParams(0, -2, 1));
        if (connected) heading.addView(ui.text("● Connected", 12, R.color.accent));
        group.addView(heading);
        TextView address = ui.text(device.address, 12, R.color.text_secondary);
        address.setTypeface(Typeface.MONOSPACE);
        group.addView(address);
        ui.gap(group, 10);
        for (TripStore.Trip trip : device.trips) tripRow(group, trip, connected);
        String count = device.trips.size() == 1 ? "1 trip" : device.trips.size() + " trips";
        phoneDeleteButton(group, "Delete " + count + " from phone", () -> confirm("Delete " + device.name + "’s trips from this phone?",
                count + " from " + device.address + ". Trips still on MOKUKU download again on the next sync.", "Delete",
                () -> deletePhoneTrips(store -> store.deleteDevice(device.address), null)));
        ui.gap(group, 10);
    }

    private interface TripDeletion { void delete(TripStore store) throws IOException; }

    // Phone trips can be deleted while disconnected, but not while a sync is saving trips.
    private void phoneDeleteButton(LinearLayout parent, String label, Runnable action) {
        Button button = ui.button(label, false, true);
        button.setOnClickListener(view -> action.run());
        boolean syncing = MokukuConnection.TRIP_SYNC.equals(snapshot.operation);
        button.setEnabled(!syncing);
        button.setAlpha(syncing ? .45f : 1);
        ui.fullButton(parent, button);
    }

    // On the trip loader's thread, so the reload that follows sees the deletion.
    private void deletePhoneTrips(TripDeletion deletion, String then) {
        Session current = session;
        TripStore store = new TripStore(this);
        Handler main = new Handler(Looper.getMainLooper());
        current.tripLoader.execute(() -> {
            try {
                deletion.delete(store);
            } catch (IOException exception) {
                main.post(() -> { if (current.activity != null) Toast.makeText(current.activity, exception.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
        current.tripsRequestedVersion = -1;
        if (then != null) navigate(then);
        else loadTrips();
    }

    private static String tripStart(TripLog log, String skeleton) {
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return new java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(new java.util.Date(log.startUnixMilliseconds));
    }

    private static String tripDuration(TripLog log) {
        long minutes = Math.round(log.durationMilliseconds / 60000.0);
        return minutes >= 60 ? minutes / 60 + " h " + minutes % 60 + " min" : minutes + " min";
    }

    private static String tripDistance(TripLog log) {
        return String.format(Locale.getDefault(), "%.1f km", log.distanceMeters / 1000);
    }

    private void tripRow(LinearLayout parent, TripStore.Trip trip, boolean connected) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(10), ui.dp(10), ui.dp(14), ui.dp(10));
        row.setMinimumHeight(ui.dp(84));
        row.setBackground(ui.pressable(connected ? R.color.connected_surface : R.color.surface, connected ? R.color.connected_border : R.color.border, 17));
        row.addView(ui.route(trip.log, connected ? R.color.connected_raised : R.color.raised), new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        LinearLayout labels = ui.column();
        labels.setPadding(ui.dp(14), 0, ui.dp(8), 0);
        String start = tripStart(trip.log, "EEEdMMMjmm");
        String summary = tripDistance(trip.log) + " · " + tripDuration(trip.log);
        if (trip.log.analysis.style != TripAnalysis.Style.TOO_SHORT) summary += " · " + trip.log.analysis.style.label;
        labels.addView(ui.text(start, 16, R.color.text_primary));
        labels.addView(ui.muted(summary));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(ui.text("›", 22, R.color.text_secondary));
        row.setContentDescription("Trip on " + start + ", " + summary);
        row.setFocusable(true);
        row.setOnClickListener(view -> {
            session.openTrip = trip;
            session.scroll.put("trip", 0);
            navigate("trip");
        });
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(-1, -2);
        parameters.bottomMargin = ui.dp(10);
        parent.addView(row, parameters);
    }

    private void trip() {
        TripStore.Trip trip = session.openTrip;
        if (trip == null) { session.route = "trips"; trips(); return; }
        TripLog log = trip.log;
        intro(tripStart(log, "EEEdMMMyyyy"), tripStart(log, "jmm") + " · " + trip.deviceName);
        tripMap(log);
        LinearLayout legend = ui.row();
        legend.setPadding(ui.dp(4), ui.dp(12), 0, ui.dp(12));
        legend.addView(ui.text("○ Start", 13, R.color.accent));
        TextView end = ui.text("● End", 13, R.color.warm);
        end.setPadding(ui.dp(18), 0, 0, 0);
        legend.addView(end);
        content.addView(legend);
        speedLegend();
        TextView source = ui.muted("Route color: OBD speed, or GPS speed where OBD is unavailable.");
        source.setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(16));
        content.addView(source);
        LinearLayout first = overviewRow();
        statistic(overviewColumn(first, true), "Distance", tripDistance(log));
        statistic(overviewColumn(first, false), "Duration", tripDuration(log));
        LinearLayout second = overviewRow();
        statistic(overviewColumn(second, true), "Average speed", Math.round(log.averageSpeedKmh()) + " km/h");
        statistic(overviewColumn(second, false), "Top GPS speed", Math.round(log.maximumGpsSpeedKmh) + " km/h");
        tripDriving(log.analysis);
        content.addView(ui.muted("From " + trip.file.getName() + " · " + log.pointCount() + " GPS points recorded by MOKUKU."));
        phoneDeleteButton(content, "Delete from phone", () -> confirm("Delete this trip from the phone?",
                trip.file.getName() + " from " + trip.deviceName + ". If it is still on MOKUKU, the next sync downloads it again.", "Delete",
                () -> deletePhoneTrips(store -> store.delete(trip.file), "trips")));
    }

    private void tripDriving(TripAnalysis analysis) {
        TextView heading = ui.text("Driving", 17, R.color.text_primary);
        heading.setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(12));
        content.addView(heading);
        LinearLayout style = ui.card(content);
        style.addView(ui.muted("Driving style"));
        ui.gap(style, 8);
        style.addView(ui.text(analysis.style.label, 18, R.color.text_primary));
        ui.gap(style, 6);
        style.addView(ui.muted(analysis.style.description));
        if (analysis.style != TripAnalysis.Style.TOO_SHORT) {
            ui.gap(style, 10);
            String cornering = analysis.yawRateKnown
                    ? String.format(Locale.getDefault(), ", cornering %.1f m/s²", analysis.typicalLateralAcceleration) : "";
            style.addView(ui.muted(String.format(Locale.getDefault(),
                    "Typical acceleration %.1f m/s², braking %.1f m/s²%s. Stopped %d%% of the time.",
                    analysis.typicalAcceleration, -analysis.typicalBraking, cornering, Math.round(analysis.idleShare * 100))));
        }
        LinearLayout events = ui.card(content);
        events.addView(ui.muted("Harsh events"));
        ui.gap(events, 10);
        LinearLayout counts = ui.row();
        harshCount(counts, analysis.harshAccelerations, "Acceleration");
        harshCount(counts, analysis.harshBrakings, "Braking");
        harshCount(counts, analysis.harshTurns, "Turns");
        events.addView(counts);
        ui.gap(events, 10);
        events.addView(ui.muted(String.format(Locale.getDefault(),
                "Counted from %.1f m/s² speeding up, %.1f m/s² braking, and %.1f m/s² sideways, using GPS speed and the IMU yaw rate.",
                TripAnalysis.HARSH_ACCELERATION, -TripAnalysis.HARSH_BRAKING, TripAnalysis.HARSH_TURN)));
    }

    private void harshCount(LinearLayout row, int count, String title) {
        LinearLayout column = ui.column();
        column.addView(ui.text(String.valueOf(count), 18, R.color.text_primary));
        column.addView(ui.text(title, 13, R.color.text_secondary));
        column.setContentDescription(count + " harsh " + title.toLowerCase(Locale.ROOT));
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
    }

    // Upper limits of the route's speed bands in km/h; the last band has none.
    private static final int[] SPEED_BAND_LIMITS = {20, 40, 60, 90};
    private static final int[] SPEED_COLORS = {R.color.speed_1, R.color.speed_2, R.color.speed_3, R.color.speed_4, R.color.speed_5};

    private static int speedBand(double kmh) {
        int band = 0;
        while (band < SPEED_BAND_LIMITS.length && kmh >= SPEED_BAND_LIMITS[band]) band++;
        return band;
    }

    private void speedLegend() {
        LinearLayout legend = ui.row();
        legend.setContentDescription("Route color shows speed, from blue under 20 km/h to red over 90 km/h");
        for (int band = 0; band < SPEED_COLORS.length; band++) {
            LinearLayout column = ui.column();
            View swatch = new View(this);
            swatch.setBackground(ui.background(SPEED_COLORS[band], SPEED_COLORS[band], 3));
            column.addView(swatch, new LinearLayout.LayoutParams(-1, ui.dp(6)));
            String label = band == 0 ? "<" + SPEED_BAND_LIMITS[0] : band == SPEED_BAND_LIMITS.length
                    ? SPEED_BAND_LIMITS[band - 1] + "+" : SPEED_BAND_LIMITS[band - 1] + "–" + SPEED_BAND_LIMITS[band];
            TextView text = ui.text(label, 12, R.color.text_secondary);
            text.setPadding(0, ui.dp(4), 0, 0);
            column.addView(text);
            LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(0, -2, 1);
            if (band > 0) parameters.leftMargin = ui.dp(2);
            legend.addView(column, parameters);
        }
        TextView unit = ui.text("km/h", 12, R.color.text_secondary);
        unit.setPadding(ui.dp(8), ui.dp(8), 0, 0);
        legend.addView(unit);
        content.addView(legend);
    }

    private void statistic(LinearLayout parent, String title, String value) {
        LinearLayout card = ui.card(parent);
        card.addView(ui.muted(title));
        ui.gap(card, 8);
        card.addView(ui.text(value, 18, R.color.text_primary));
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void tripMap(TripLog log) {
        org.osmdroid.config.IConfigurationProvider configuration = org.osmdroid.config.Configuration.getInstance();
        configuration.setUserAgentValue(getPackageName());
        File cache = new File(getCacheDir(), "osmdroid");
        configuration.setOsmdroidBasePath(cache);
        configuration.setOsmdroidTileCache(new File(cache, "tiles"));
        map = new org.osmdroid.views.MapView(this);
        map.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        // Inverted, dimmed tiles match the dark app.
        android.graphics.ColorMatrix tiles = new android.graphics.ColorMatrix(new float[] {
                -1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0});
        android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix();
        dim.setScale(.72f, .8f, .86f, 1);
        tiles.postConcat(dim);
        map.getOverlayManager().getTilesOverlay().setColorFilter(new android.graphics.ColorMatrixColorFilter(tiles));
        List<org.osmdroid.util.GeoPoint> points = new ArrayList<>();
        for (double[] point : log.latitudeLongitudes()) points.add(new org.osmdroid.util.GeoPoint(point[0], point[1]));
        if (points.isEmpty()) points.add(new org.osmdroid.util.GeoPoint(log.originLatitude, log.originLongitude));
        // One polyline per run of points in the same speed band, colored by the speed at each segment's end.
        for (int first = 0; first < points.size() - 1; ) {
            int band = speedBand(log.speedKmh[first + 1]), last = first + 1;
            while (last + 1 < points.size() && speedBand(log.speedKmh[last + 1]) == band) last++;
            org.osmdroid.views.overlay.Polyline route = new org.osmdroid.views.overlay.Polyline(map);
            route.setPoints(points.subList(first, last + 1));
            route.getOutlinePaint().setColor(ui.color(SPEED_COLORS[band]));
            route.getOutlinePaint().setStrokeWidth(ui.dp(4));
            route.getOutlinePaint().setStrokeCap(android.graphics.Paint.Cap.ROUND);
            route.getOutlinePaint().setStrokeJoin(android.graphics.Paint.Join.ROUND);
            route.setInfoWindow(null);
            map.getOverlays().add(route);
            first = last;
        }
        map.getOverlays().add(endpoint(points.get(0), true));
        map.getOverlays().add(endpoint(points.get(points.size() - 1), false));
        map.getOverlays().add(new org.osmdroid.views.overlay.CopyrightOverlay(this));
        org.osmdroid.util.BoundingBox box = org.osmdroid.util.BoundingBox.fromGeoPointsSafe(points);
        map.getController().setZoom(16.0);
        map.getController().setCenter(box.getCenterWithDateLine());
        if (points.size() > 1) map.addOnFirstLayoutListener((view, left, top, right, bottom) -> map.zoomToBoundingBox(box, false, ui.dp(36)));
        map.setBackground(ui.background(R.color.raised, R.color.border, 20));
        map.setClipToOutline(true);
        map.setContentDescription("Map of the trip route");
        // Let the map pan inside the scrolling page.
        map.setOnTouchListener((view, event) -> {
            view.getParent().requestDisallowInterceptTouchEvent(event.getActionMasked() != android.view.MotionEvent.ACTION_UP);
            return false;
        });
        content.addView(map, new LinearLayout.LayoutParams(-1, ui.dp(360)));
        map.onResume();
    }

    private org.osmdroid.views.overlay.Marker endpoint(org.osmdroid.util.GeoPoint position, boolean start) {
        org.osmdroid.views.overlay.Marker marker = new org.osmdroid.views.overlay.Marker(map);
        marker.setPosition(position);
        marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER);
        android.graphics.drawable.GradientDrawable icon = new android.graphics.drawable.GradientDrawable();
        icon.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        icon.setSize(ui.dp(14), ui.dp(14));
        icon.setColor(ui.color(start ? R.color.background : R.color.warm));
        icon.setStroke(ui.dp(start ? 3 : 2), ui.color(start ? R.color.accent : R.color.background));
        marker.setIcon(icon);
        marker.setTitle(start ? "Start" : "End");
        marker.setInfoWindow(null);
        marker.setOnMarkerClickListener((clicked, view) -> true);
        return marker;
    }

    private void releaseMap() {
        if (map == null) return;
        map.onPause();
        map.onDetach();
        map = null;
    }

    private void confirm(String title, String message, String action, Runnable confirmed) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("Cancel", null).setPositiveButton(action, (dialog, which) -> confirmed.run()).show();
    }

    private void findDevice() {
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) { feedback.setText("This phone has no Bluetooth adapter."); return; }
        String[] required = Build.VERSION.SDK_INT >= 31
                ? new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
        for (String permission : required) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(required, BLUETOOTH_PERMISSION); return;
            }
        }
        try {
            if (!adapter.isEnabled()) { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH); return; }
            if (Build.VERSION.SDK_INT < 31) {
                LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
                if (location != null && !location.isProviderEnabled(LocationManager.GPS_PROVIDER) && !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    new AlertDialog.Builder(this).setTitle("Enable location for Bluetooth discovery")
                            .setMessage("Android 6–11 requires location services for nearby Bluetooth scans.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Open settings", (dialog, which) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).show();
                    return;
                }
            }
            showDiscovery();
            session.connection.scan();
        } catch (SecurityException exception) { feedback.setText("Allow " + bluetoothPermissionName() + " access in app settings."); }
    }

    private String bluetoothPermissionName() { return Build.VERSION.SDK_INT >= 31 ? "Nearby devices" : "Location"; }

    private void showDiscovery() {
        discoveryResults = ui.column();
        discoveryDevices.clear();
        discoveryStatus = ui.muted("");
        discoveryStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        discoveryResults.addView(discoveryStatus);
        discoveryResults.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8));
        ScrollView container = new ScrollView(this);
        container.addView(discoveryResults);
        discovery = new AlertDialog.Builder(this).setTitle("Find your MOKUKU").setView(container)
                .setNegativeButton("Close", null).setNeutralButton("Search again", null).create();
        discovery.setOnDismissListener(dialog -> { session.connection.stopScan(); discovery = null; });
        discovery.setOnShowListener(dialog -> discovery.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> session.connection.scan()));
        discovery.show();
        renderDiscovery();
    }

    private void renderDiscovery() {
        if (discoveryResults == null) return;
        String status = snapshot.scanning ? "Looking for nearby companions…" : snapshot.message;
        if (!status.contentEquals(discoveryStatus.getText())) discoveryStatus.setText(status);
        if (snapshot.devices.isEmpty() && !discoveryDevices.isEmpty()) {
            while (discoveryResults.getChildCount() > 1) discoveryResults.removeViewAt(1);
            discoveryDevices.clear();
        }
        for (MokukuConnection.Device device : snapshot.devices) {
            String label = device.name + "\n" + device.address + " · " + device.signal + " dBm";
            Button button = discoveryDevices.get(device.address);
            if (button == null) {
                button = ui.button(label, false, false);
                button.setOnClickListener(view -> session.connection.connect(device.address));
                discoveryDevices.put(device.address, button);
                ui.fullButton(discoveryResults, button);
            } else if (!label.contentEquals(button.getText())) button.setText(label);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == STORAGE_PERMISSION) {
            String name = session.pendingDownload;
            session.pendingDownload = null;
            if (name == null) return;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) session.connection.download(name);
            else feedback.setText("Allow storage access to save recordings to Downloads.");
            return;
        }
        if (requestCode != BLUETOOTH_PERMISSION) return;
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this).setTitle("Bluetooth access is needed")
                        .setMessage("Allow " + bluetoothPermissionName() + " access to find and connect to MOKUKU.")
                        .setNegativeButton("Cancel", null).setPositiveButton("App settings", (dialog, which) ->
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))).show();
                return;
            }
        }
        if (results.length > 0) findDevice();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENABLE_BLUETOOTH && resultCode == RESULT_OK) findDevice();
        if (requestCode == FIRMWARE_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri image = data.getData();
            confirm("Send this firmware image?", "MOKUKU II writes it to its other slot and restarts into it. Canceling restarts MOKUKU II into its current firmware.",
                    "Send firmware", () -> session.connection.updateFirmware(image));
        }
    }
}

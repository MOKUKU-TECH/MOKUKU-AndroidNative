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
    private interface FileFeedback { void completed(String message); }
    private static final int BLUETOOTH_PERMISSION = 10;
    private static final int ENABLE_BLUETOOTH = 11;
    private static final int SAVE_RECORDING = 12;

    private static final class Session {
        MokukuConnection connection;
        String route = "home";
        String wifiName = "";
        String remoteUrl = "";
        File pendingSave;
        boolean saving;
        FileFeedback fileFeedback;
        final Map<String, Integer> scroll = new HashMap<>();
        final ExecutorService files = Executors.newSingleThreadExecutor();
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
    private TextView progressText;
    private ProgressBar progress;
    private AlertDialog discovery;
    private LinearLayout discoveryResults;
    private TextView discoveryStatus;
    private final Map<String, Button> discoveryDevices = new HashMap<>();
    private OnBackInvokedCallback backCallback;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);
        session = (Session) getLastNonConfigurationInstance();
        if (session == null) {
            session = new Session();
            session.connection = new MokukuConnection(this);
            if (savedInstanceState != null) {
                session.route = savedInstanceState.getString("route", "home");
                String pending = savedInstanceState.getString("pending_save");
                if (pending != null) {
                    try {
                        File file = new File(pending);
                        if (file.isFile() && file.getCanonicalPath().startsWith(getFilesDir().getCanonicalPath() + File.separator)) session.pendingSave = file;
                    } catch (IOException ignored) {}
                }
            }
        }
        snapshot = session.connection.snapshot();
        setContentView(R.layout.activity_main);
        content = findViewById(R.id.content);
        header = findViewById(R.id.header);
        navigation = findViewById(R.id.navigation);
        feedback = findViewById(R.id.feedback);
        scroll = findViewById(R.id.content_scroll);
        session.connection.setListener(this::changed);
        session.fileFeedback = message -> { if ("records".equals(session.route)) render(); feedback.setText(message); };
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
        if (session.pendingSave != null) state.putString("pending_save", session.pendingSave.getAbsolutePath());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        session.connection.setListener(null);
        if (discovery != null) {
            discovery.setOnDismissListener(null);
            discovery.dismiss();
        }
        session.fileFeedback = null;
        if (!isChangingConfigurations()) {
            session.connection.close();
            session.files.shutdown();
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
        navigate("updates".equals(session.route) || "debug".equals(session.route) ? "tools" : "home");
        return true;
    }

    private void captureDrafts() {
        if (wifiName != null) session.wifiName = wifiName.getText().toString();
        if (remoteUrl != null) session.remoteUrl = remoteUrl.getText().toString();
    }

    private void navigate(String route) {
        captureDrafts();
        session.scroll.put(session.route, scroll.getScrollY());
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(content.getWindowToken(), 0);
        session.route = route;
        render();
        if ("records".equals(route) && snapshot.connected && !snapshot.busy) session.connection.refreshRecordings();
    }

    private void changed(MokukuConnection.Snapshot update) {
        MokukuConnection.Snapshot previous = snapshot;
        snapshot = update;
        feedback.setText(update.message);
        if (discovery != null && discovery.isShowing()) {
            if (update.connecting || update.connected) discovery.dismiss();
            else { renderDiscovery(); return; }
        }
        if (previous.connected != update.connected || previous.connecting != update.connecting) {
            captureDrafts();
            session.scroll.put(session.route, scroll.getScrollY());
            render();
        } else if (("home".equals(session.route) || "tools".equals(session.route))
                && (!previous.information.equals(update.information) || !previous.errors.equals(update.errors)
                || previous.busy != update.busy || !previous.name.equals(update.name))) {
            session.scroll.put(session.route, scroll.getScrollY());
            render();
        } else if ("records".equals(session.route)) {
            boolean sameTransfer = previous.operation != null && previous.operation.equals(update.operation)
                    && update.operation.startsWith("Downloading ") && progress != null;
            if (sameTransfer) updateProgress();
            else { session.scroll.put(session.route, scroll.getScrollY()); render(); }
        }
        updateVersions();
        updateControls();
        if (!previous.connected && update.connected && "records".equals(session.route)) session.connection.refreshRecordings();
    }

    private void render() {
        content.removeAllViews(); header.removeAllViews(); navigation.removeAllViews();
        deviceControls.clear();
        wifiName = null; wifiPassword = null; remoteUrl = null;
        firmwareVersion = null; memeVersion = null;
        progress = null; progressText = null;
        boolean subpage = "updates".equals(session.route) || "debug".equals(session.route) || "simulation".equals(session.route);
        if (subpage) {
            Button back = ui.button("‹", false, false);
            back.setContentDescription("Back to " + ("simulation".equals(session.route) ? "Home" : "Tools"));
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
            case "tools": tools(); break;
            default: session.route = "home"; home(); break;
        }
        updateControls();
        int position = session.scroll.containsKey(session.route) ? session.scroll.get(session.route) : 0;
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    private String pageName() {
        switch (session.route) {
            case "updates": return "Updates";
            case "debug": return "Debug";
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
        if (!snapshot.connected && !snapshot.information.isEmpty()) content.addView(ui.muted("Last received · reconnect to refresh."));
        String[] versions = information(3).split("/", -1);
        LinearLayout firstRow = overviewRow();
        overview(overviewColumn(firstRow, true), "Software", versions.length == 2 ? "Left · INS\n" + versions[0] + "\nRight · AI\n" + versions[1] : information(3), 3);
        overview(overviewColumn(firstRow, false), "SD card", storageText(), 61);
        LinearLayout secondRow = overviewRow();
        overview(overviewColumn(secondRow, true), "Voice model", information(57), 57);
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
        shortcut("Simulation", "Left/right clicks and eye mode", R.drawable.ic_eyes, "simulation");
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
        shortcut("Updates", "Wi-Fi, remote URL, firmware, voice & memes", R.drawable.ic_update, "updates");
        shortcut("Debug", "OBD, logs, date & recording", R.drawable.ic_debug, "debug");
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
        LinearLayout wifi = ui.card(content);
        wifi.addView(ui.text("Wi-Fi", 18, R.color.text_primary));
        wifi.addView(ui.muted("MOKUKU downloads updates over Wi-Fi."));
        wifiName = field(wifi, "Wi-Fi name", "Enter network name", InputType.TYPE_CLASS_TEXT);
        wifiName.setText(session.wifiName);
        wifiPassword = field(wifi, "Wi-Fi password", "Enter network password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (Build.VERSION.SDK_INT >= 26) wifiPassword.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
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
            session.connection.saveWifi(wifiName.getText().toString(), wifiPassword.getText().toString());
            wifiPassword.setText("");
        });
        LinearLayout remote = ui.card(content);
        remote.addView(ui.text("Remote URL", 18, R.color.text_primary));
        remote.addView(ui.muted("Choose where updates come from."));
        remoteUrl = field(remote, "Remote base URL", "https://updates.example.com/mokuku/", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        remoteUrl.setText(session.remoteUrl);
        deviceButton(remote, "Save remote URL", true, false, () -> session.connection.saveUrl(remoteUrl.getText().toString().trim()));
        LinearLayout firmware = ui.card(content);
        firmware.addView(ui.text("Firmware OTA", 18, R.color.text_primary));
        firmwareVersion = ui.muted("");
        firmware.addView(firmwareVersion);
        firmware.addView(ui.muted("Right eye first → reconnect → left eye."));
        updateButton(firmware, "Update right eye", 67, "right-eye firmware update");
        updateButton(firmware, "Update left eye", 66, "left-eye firmware update");
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

    private void updateVersions() {
        if (firmwareVersion != null) firmwareVersion.setText("Last received firmware: " + information(3));
        if (memeVersion != null) memeVersion.setText("Last received meme versions: " + information(5));
    }

    private void updateButton(LinearLayout parent, String label, int command, String description) {
        boolean rollback = command == 70 || command == 71;
        deviceButton(parent, label, false, rollback, () -> {
            String message = rollback ? "Request the previous firmware for this eye. MOKUKU may restart and disconnect."
                    : "MOKUKU downloads updates using its saved Wi-Fi and remote URL. The app will disconnect after the request; reconnect after the update finishes to check versions.";
            if (command == 66 || command == 68) {
                android.widget.CheckBox checked = new android.widget.CheckBox(this);
                checked.setText("The right-eye update is complete and I have reconnected.");
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
        commandButton(card, "Left click", 43);
        commandButton(card, "Right click", 53);
        commandButton(card, "Change eye mode", 10);
        card.addView(ui.muted("Check MOKUKU’s display for the current mode."));
    }

    private void commandButton(LinearLayout parent, String label, int command) {
        deviceButton(parent, label, false, false, () -> session.connection.command(command, label));
    }

    private void debug() {
        intro("A closer look.", "A few useful tools, all in one place.");
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
        }
        overview(content, "MOKUKU storage", storageText(), 61);
        deviceButton(content, "Refresh recordings", false, false, session.connection::refreshRecordings);
        section("Returned recordings (" + snapshot.recordings.size() + ")");
        if (snapshot.busy) {
            LinearLayout active = ui.card(content);
            active.addView(ui.text(snapshot.operation, 16, R.color.accent));
            progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.color(R.color.accent)));
            active.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(12)));
            progressText = ui.muted("");
            active.addView(progressText);
            if (snapshot.cancellable) {
                Button cancel = ui.button("Cancel operation", false, false);
                cancel.setOnClickListener(view -> session.connection.cancelOperation());
                ui.fullButton(active, cancel);
            }
            updateProgress();
        }
        if (snapshot.recordings.isEmpty()) content.addView(ui.muted(snapshot.busy ? "Waiting for MOKUKU…" : "No recordings returned. Refresh after stopping a recording."));
        for (String name : snapshot.recordings) {
            LinearLayout card = ui.card(content);
            TextView filename = ui.text(name, 15, R.color.text_primary);
            filename.setTypeface(Typeface.MONOSPACE);
            card.addView(filename);
            card.addView(ui.muted(snapshot.connected ? "On MOKUKU · device recording" : "Last returned · reconnect to refresh"));
            deviceButton(card, "Download", false, false, () -> session.connection.download(name));
            deviceButton(card, "Delete from MOKUKU", false, true, () -> confirm("Delete device recording?", name + "\nRequests recording stop before deleting. Phone copies are kept.", "Delete", () -> session.connection.deleteRecording(name, false)));
        }
        content.addView(ui.muted("The firmware returns filenames only and may return a partial list. Device deletion keeps phone copies."));
        if (!snapshot.recordings.isEmpty()) deviceButton(content, "Clear all recordings", false, true, () -> confirm("Clear all recordings?",
                "Requests recording stop, then removes device recordings, including ones you haven’t downloaded. Phone copies are kept.", "Clear all recordings", () -> session.connection.deleteRecording(null, true)));
        if (!snapshot.downloads.isEmpty()) {
            section("Downloaded to the app");
            content.addView(ui.muted("These complete copies are kept when device recordings are deleted."));
            for (Map.Entry<String, File> entry : snapshot.downloads.entrySet()) {
                LinearLayout card = ui.card(content);
                card.addView(ui.text(entry.getKey(), 15, R.color.text_primary));
                Button save = ui.button(session.saving ? "Saving…" : "Save to phone", false, false);
                save.setEnabled(!session.saving);
                save.setOnClickListener(view -> saveFile(entry.getValue(), entry.getKey()));
                ui.fullButton(card, save);
            }
        }
    }

    private void updateProgress() {
        if (progress == null) return;
        progress.setIndeterminate(snapshot.total == 0);
        if (snapshot.total > 0) progress.setProgress((int) (100 * snapshot.received / snapshot.total));
        progressText.setText(snapshot.total == 0 ? "Waiting for device reply…" : snapshot.received + " / " + snapshot.total + " bytes");
    }

    private void saveFile(File source, String name) {
        session.pendingSave = source;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, SAVE_RECORDING);
    }

    private void trips() {
        intro("Trips", "There’s more down the road.");
        ui.gap(content, 70);
        content.addView(ui.eyes(true), new LinearLayout.LayoutParams(-1, ui.dp(100)));
        TextView title = ui.text("Your journeys, all together.", 25, R.color.text_primary);
        title.setGravity(Gravity.CENTER);
        content.addView(title);
        ui.gap(content, 16);
        TextView message = ui.muted("A place for the trips you make with MOKUKU. Coming later.");
        message.setGravity(Gravity.CENTER);
        content.addView(message);
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
        if (requestCode == ENABLE_BLUETOOTH) { if (resultCode == RESULT_OK) findDevice(); return; }
        if (requestCode != SAVE_RECORDING || resultCode != RESULT_OK || data == null || data.getData() == null || session.pendingSave == null) return;
        File source = session.pendingSave;
        Uri destination = data.getData();
        Session owner = session;
        ContentResolver resolver = getApplicationContext().getContentResolver();
        owner.saving = true;
        if ("records".equals(owner.route)) render();
        owner.files.execute(() -> {
            String result;
            try (FileInputStream input = new FileInputStream(source); OutputStream output = resolver.openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("Could not open the selected destination.");
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                result = "Recording saved to your selected phone location.";
            } catch (IOException | SecurityException exception) {
                try { android.provider.DocumentsContract.deleteDocument(resolver, destination); } catch (IOException | RuntimeException ignored) {}
                result = "Could not save recording: " + exception.getMessage() + ". The app copy is kept; try Save to phone again.";
            }
            String message = result;
            new Handler(Looper.getMainLooper()).post(() -> {
                owner.saving = false;
                if (owner.fileFeedback != null) owner.fileFeedback.completed(message);
            });
        });
    }
}

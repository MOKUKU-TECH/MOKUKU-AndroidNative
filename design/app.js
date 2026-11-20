"use strict";

const paths = {
  home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',
  records: '<rect x="5" y="3" width="14" height="18" rx="3"/><path d="M9 8h6M9 12h6M9 16h4"/>',
  trips: '<path d="M5 19c0-5 14-2 14-8S5 12 5 5"/><circle cx="5" cy="4" r="2"/><circle cx="19" cy="19" r="2"/>',
  arrow: '<path d="m9 5 7 7-7 7"/>',
  back: '<path d="m14 5-7 7 7 7M7 12h14"/>',
  refresh: '<path d="M20 7v5h-5M4 17v-5h5M5 8a8 8 0 0 1 13-2l2 3M4 15l2 3a8 8 0 0 0 13-2"/>',
  bluetooth: '<path d="m7 6 10 12-5 4V2l5 4L7 18"/>',
  software: '<rect x="4" y="4" width="16" height="16" rx="4"/><path d="m10 8-4 4 4 4m4-8 4 4-4 4"/>',
  storage: '<path d="M7 3h9l4 5v13H4V6zM8 3v6h8V3M8 17h8"/>',
  voice: '<rect x="9" y="2" width="6" height="13" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8"/>',
  chip: '<rect x="6" y="6" width="12" height="12" rx="3"/><path d="M9 2v4m6-4v4M9 18v4m6-4v4M2 9h4m-4 6h4m12-6h4m-4 6h4"/>',
  update: '<path d="M12 16V3m-5 5 5-5 5 5M4 15v5h16v-5"/>',
  eyes: '<rect x="3" y="6" width="6" height="12" rx="3"/><rect x="15" y="6" width="6" height="12" rx="3"/>',
  debug: '<path d="m4 6 5 6-5 6m9 0h7"/>',
  tools: '<path d="M4 6h6m4 0h6M4 12h10m4 0h2M4 18h2m4 0h10"/><circle cx="12" cy="6" r="2"/><circle cx="16" cy="12" r="2"/><circle cx="8" cy="18" r="2"/>',
  download: '<path d="M12 3v13m-5-5 5 5 5-5M4 17v4h16v-4"/>',
  more: '<circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>',
  wifi: '<path d="M3 8a14 14 0 0 1 18 0M6 12a9 9 0 0 1 12 0m-9 4a4 4 0 0 1 6 0"/><circle cx="12" cy="20" r="1"/>',
  link: '<path d="m10 13 4-4M8 16l-1 1a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0m0 2 1-1a4 4 0 0 1 6 6l-5 5a4 4 0 0 1-6 0"/>',
  undo: '<path d="M4 10h10a6 6 0 0 1 0 12M4 10l6-6m-6 6 6 6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7"/><circle cx="12" cy="12" r="3"/>',
};

const state = {
  route: "home",
  connected: false,
  hasConnected: false,
  records: ["mobili_128.record", "mobili_127.record", "mobili_126.record"],
  devices: [{ name: "mokuku_73A1", address: "A4:CF:12:6B:73:A1" }, { name: "mokukuii_5C20", address: "3C:84:27:C1:5C:22" }],
  trips: [
    sampleTrip("A4:CF:12:6B:73:A1", 41, "2026-11-18T08:12", 23, 12.4, 72, 7),
    sampleTrip("A4:CF:12:6B:73:A1", 40, "2026-11-17T18:40", 31, 18.9, 96, 3),
    sampleTrip("A4:CF:12:6B:73:A1", 39, "2026-11-17T08:05", 21, 11.8, 68, 11),
    sampleTrip("3C:84:27:C1:5C:22", 6, "2026-11-19T14:22", 46, 27.3, 104, 5),
  ],
  // Sample trips the connected MOKUKU holds that the phone has not synced yet.
  deviceOnlyTrips: [sampleTrip("A4:CF:12:6B:73:A1", 42, "2026-11-19T17:55", 34, 20.6, 88, 13), sampleTrip("A4:CF:12:6B:73:A1", 43, "2026-11-20T08:09", 25, 13.1, 75, 2)],
  sync: null,
  trip: null,
  downloaded: new Set(),
  transfer: null,
  scroll: {},
};
// A deterministic sample route: east/north meters from the trip's first point.
function sampleRoute(seed, kilometers) {
  let random = seed * 9301 + 49297;
  const next = () => (random = (random * 9301 + 49297) % 233280) / 233280;
  const points = [[0, 0]];
  let heading = next() * Math.PI * 2;
  const steps = 60;
  for (let step = 0; step < steps; step++) {
    if (step % 6 === 0) heading += (next() - .5) * 1.6;
    const [east, north] = points[points.length - 1];
    const length = kilometers * 1000 / steps;
    points.push([east + Math.cos(heading) * length, north + Math.sin(heading) * length]);
  }
  return points;
}

function sampleTrip(address, number, start, minutes, kilometers, maximumKmh, seed) {
  return { address, name: `trip_${number}.bin`, start: new Date(start), minutes, kilometers, maximumKmh, points: sampleRoute(seed, kilometers) };
}

const screen = document.getElementById("screen");
const sheet = document.getElementById("sheet");
let toastTimer;
let transferTimer;
let scanTimer;
let queryTimer;

function icon(name) {
  return `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${paths[name]}</svg>`;
}

function eyes(extraClass = "") {
  return `<div class="eyes ${extraClass}" aria-hidden="true"><span class="eye"></span><span class="eye"></span></div>`;
}

function status() {
  return `<span class="connection ${state.connected ? "online" : ""}"><span></span>${state.connected ? "Connected" : "Disconnected"}</span>`;
}

function button(label, action, style = "secondary", extra = "") {
  return `<button class="button ${style}" data-action="${action}" ${extra}>${label}</button>`;
}

function deviceButton(label, action, style = "secondary", extra = "") {
  return button(label, action, style, `${state.connected ? "" : "disabled"} ${extra}`);
}

function shortcut(title, description, symbol, route, tone = "") {
  return `<button class="shortcut" data-route="${route}"><span class="icon-tile ${tone}">${icon(symbol)}</span><span><strong>${title}</strong><p>${description}</p></span>${icon("arrow")}</button>`;
}

function subpageIntro(title, description) {
  return `<h1 class="greeting">${title}</h1><p class="description">${description}</p>${state.connected ? "" : '<div class="notice">Connect to MOKUKU to use device actions.</div>'}`;
}

function home() {
  const hasInformation = state.connected || state.hasConnected;
  const deviceCard = state.connected
    ? `<div class="device-top"><div><div class="device-kicker">YOUR DASHBOARD COMPANION</div><div class="device-name">mokuku_73A1</div><div class="device-caption">Two eyes. One little companion.</div></div>${eyes()}</div><div class="device-divider"></div><div class="device-footer"><span>${icon("bluetooth")} Connected over Bluetooth</span><button class="text-button" data-action="disconnect">Disconnect</button></div>`
    : `${eyes("hero-eyes sleepy")}<div class="centered"><div class="device-name">Let’s find your companion.</div><p class="description">Power on MOKUKU.<br>We’ll take it from here.</p></div>${button(`${icon("bluetooth")} Find MOKUKU`, "scan", "primary full")}`;
  const firmware = hasInformation
    ? '<div class="firmware-lines"><div><span>Left · INS</span><strong class="mono">2.0.0</strong></div><div><span>Right · AI</span><strong class="mono">2.0.0</strong></div></div>'
    : '<div class="value">—</div><div class="detail">Left & right eye</div>';
  return `<h1 class="greeting">Hello, MOKUKU.</h1><p class="description">A little closer to your companion.</p><section class="card device-card" aria-label="Device connection">${deviceCard}</section>
    <div class="section-heading"><h3>Device overview</h3><button class="text-button" data-action="refresh-info" ${state.connected ? "" : "disabled"}>${icon("refresh")} Refresh</button></div>
    ${!state.connected && state.hasConnected ? '<div class="notice">Last received · reconnect to refresh device information.</div>' : ""}
    <div class="info-grid">
      <section class="card"><div class="label">${icon("software")} Software</div>${firmware}</section>
      <section class="card"><div class="label">${icon("storage")} SD card</div><div class="value">${hasInformation ? '19.6 <span class="detail">GB free</span>' : "—"}</div><div class="detail">${hasInformation ? "of 31.2 GB total" : "Free / total space"}</div>${hasInformation ? '<div class="storage-track" aria-hidden="true"><span></span></div>' : ""}</section>
      <section class="card"><div class="label">${icon("voice")} Voice model</div><div class="value" style="font-size:14px">${hasInformation ? "Hi MOKUKU" : "—"}</div><div class="detail">${hasInformation ? 'English · <span class="mono">wn10_himokuku</span>' : "Model & language"}</div></section>
      <section class="card"><div class="label">${icon("chip")} MAC address</div><div class="value mono mac-value">${hasInformation ? "A4:CF:12:6B:73:A1" : "—"}</div>${hasInformation ? '<button class="text-button" data-action="copy-mac" aria-label="Copy MAC address">Copy address</button>' : '<div class="detail">Device identity</div>'}</section>
    </div>
    <div class="section-heading"><h3>A little interaction</h3></div>
    ${shortcut("Simulation", "A little interaction for each eye", "eyes", "simulation")}`;
}

function tools() {
  return `<h1 class="greeting">Your device, your way.</h1><p class="description">A few useful things, whenever you need them.</p>
    <section class="card device-card"><div class="device-top"><div><div class="device-kicker">${state.connected ? "YOUR CONNECTED COMPANION" : "YOUR LITTLE COMPANION"}</div><div class="device-name">${state.connected ? "mokuku_73A1" : "MOKUKU"}</div><div class="device-caption">${state.connected ? "Ready when you are." : "Connect from Home to use device actions."}</div></div>${eyes(state.connected ? "" : "sleepy")}</div></section>
    <div class="section-heading"><h3>Device tools</h3></div>
    ${shortcut("Updates", "Wi-Fi, remote URL, firmware, voice & memes", "update", "updates", "warm")}
    ${shortcut("Debug", "OBD, logs, date & recording", "debug", "debug", "slate")}
    <p class="detail" style="margin:24px 4px">Your everyday connection stays on Home. Find maintenance and diagnostic actions here.</p>`;
}

function updates() {
  return `${subpageIntro("Keep the good going.", "A fresh update for your little companion.")}
    <form id="wifi-settings" class="card">
      <div class="card-heading"><span class="icon-tile">${icon("wifi")}</span><div><h3>Wi-Fi</h3><p>MOKUKU downloads updates over Wi-Fi.</p></div></div>
      <label class="field">Wi-Fi name<input name="wifi-name" type="text" placeholder="Enter network name" autocomplete="off" required ${state.connected ? "" : "disabled"}></label>
      <label class="field">Wi-Fi password<span class="password-field"><input id="wifi-password" name="wifi-password" type="password" placeholder="Enter network password" autocomplete="off" required ${state.connected ? "" : "disabled"}><button class="icon-button" type="button" data-action="reveal-password" aria-label="Show password" aria-pressed="false">${icon("eye")}</button></span></label>
      <p class="detail">Enter the network details to send to MOKUKU.</p>
      <button class="button primary full" type="submit" ${state.connected ? "" : "disabled"}>Save Wi-Fi</button>
      <div id="wifi-feedback" class="inline-status" role="status"></div>
    </form>
    <form id="remote-url-settings" class="card">
      <div class="card-heading"><span class="icon-tile">${icon("link")}</span><div><h3>Remote URL</h3><p>Choose where updates come from.</p></div></div>
      <label class="field">Remote base URL<input name="remote-url" type="url" placeholder="https://updates.example.com/mokuku/" autocomplete="off" required ${state.connected ? "" : "disabled"}></label>
      <p class="detail">Existing settings cannot be read from MOKUKU.</p>
      <button class="button primary full" type="submit" ${state.connected ? "" : "disabled"}>Save remote URL</button>
      <div id="remote-url-feedback" class="inline-status" role="status"></div>
    </form>
    <section class="card"><div class="card-heading"><span class="icon-tile warm">${icon("software")}</span><div><h3>Firmware OTA</h3><p>${state.connected || state.hasConnected ? `${state.connected ? "" : "Last received · "}Left 2.0.0 · Right 2.0.0` : "Connect to query eye versions."}</p></div></div><p class="step-note">01 &nbsp; Right eye first &nbsp; → &nbsp; 02 &nbsp; Reconnect &nbsp; → &nbsp; 03 &nbsp; Left eye</p>
      <div class="button-row">${deviceButton("Update right eye", "confirm-update", "secondary", 'data-kind="Firmware OTA" data-eye="right"')}${deviceButton("Update left eye", "confirm-update", "secondary", 'data-kind="Firmware OTA" data-eye="left"')}</div></section>
    <section class="card"><div class="card-heading"><span class="icon-tile">${icon("voice")}</span><div><h3>Voice model</h3><p>Right-eye speech & both eyes' wake-word assets</p></div></div><div class="button-row">${deviceButton("English", "confirm-update", "secondary", 'data-kind="English voice update" data-eye="both"')}${deviceButton("Mandarin", "confirm-update", "secondary", 'data-kind="Mandarin voice update" data-eye="both"')}</div></section>
    <section class="card"><div class="card-heading"><span class="icon-tile">${icon("eyes")}</span><div><h3>Meme assets</h3><p>Refresh MOKUKU’s expressions.</p></div></div><p class="step-note">Update the right eye before the left eye.</p><div class="button-row">${deviceButton("Update right eye", "confirm-update", "secondary", 'data-kind="Meme update" data-eye="right"')}${deviceButton("Update left eye", "confirm-update", "secondary", 'data-kind="Meme update" data-eye="left"')}</div></section>
    <section class="card"><div class="card-heading"><span class="icon-tile warm">${icon("undo")}</span><div><h3>Rollback</h3><p>Request the previous firmware for an eye.</p></div></div><div class="button-row">${deviceButton("Right eye", "confirm-update", "danger", 'data-kind="Firmware rollback" data-eye="right"')}${deviceButton("Left eye", "confirm-update", "danger", 'data-kind="Firmware rollback" data-eye="left"')}</div></section>`;
}

function simulation() {
  return `${subpageIntro("A little eye contact.", "Try a tap. See how MOKUKU responds.")}
    <div class="eye-controls"><button class="eye-control" data-action="command" data-command="Left click" ${state.connected ? "" : "disabled"}><span class="eye" aria-hidden="true"></span>Left click</button><button class="eye-control" data-action="command" data-command="Right click" ${state.connected ? "" : "disabled"}><span class="eye" aria-hidden="true"></span>Right click</button></div>
    <section class="card"><div class="card-heading"><span class="icon-tile">${icon("eyes")}</span><div><h3>Change eye mode</h3><p>Send MOKUKU’s eye-mode toggle.</p></div></div>${deviceButton("Change eye mode", "command", "secondary full", 'data-command="Change eye mode"')}<p class="detail">Check MOKUKU’s display for the current mode.</p></section>
    <p class="description centered" style="margin-top:28px">Small gestures.<br>A little more personality.</p>`;
}

function debug() {
  return `${subpageIntro("A closer look.", "A few useful tools, all in one place.")}
    <section class="card"><div class="card-heading"><span class="icon-tile slate">${icon("chip")}</span><div><h3>OBD connection</h3><p>Choose OBD scanning or GPS mode.</p></div></div><div class="button-row">${deviceButton("Enable OBD", "command", "secondary", 'data-command="Enable OBD"')}${deviceButton("Disable OBD", "command", "secondary", 'data-command="Disable OBD"')}</div><p class="detail">Actions send a request; current OBD state is not reported.</p></section>
    <section class="card"><div class="card-heading"><span class="icon-tile slate">${icon("debug")}</span><div><h3>Debug log</h3><p>Enable device diagnostic logging.</p></div></div>${deviceButton("Enable debug log", "command", "secondary full", 'data-command="Enable debug log"')}<p class="detail">Logging stays enabled for this device session.</p></section>
    <section class="card"><div class="card-heading"><span class="icon-tile warm">${icon("trips")}</span><div><h3>Date & time</h3><p>Set a date for device testing.</p></div></div>${deviceButton("Set date & time", "set-date", "secondary full")}<p class="detail">A GPS fix can override the debug date.</p></section>
    <section class="card"><div class="card-heading"><span class="icon-tile">${icon("records")}</span><div><h3>Recording</h3><p>Capture device data for a closer look.</p></div></div><div class="button-row">${deviceButton("Start recording", "command", "secondary", 'data-command="Start recording"')}${deviceButton("Stop recording", "command", "secondary", 'data-command="Stop recording"')}</div><button class="text-button" data-route="records">Open Data Collection ${icon("arrow")}</button><p class="detail">Refresh recordings after stopping to find the device file.</p></section>`;
}

function recordCard(name) {
  const saved = state.downloaded.has(name);
  const downloading = state.transfer?.name === name;
  return `<article class="card record-card"><div class="record-top"><span class="icon-tile slate">${icon("records")}</span><div><div class="record-name">${name}</div><div class="record-meta">${saved ? "Preview download complete" : "On MOKUKU · device recording"}</div></div><button class="icon-button" data-action="record-menu" data-file="${name}" aria-label="Actions for ${name}" ${state.connected && !downloading ? "" : "disabled"}>${icon("more")}</button></div>
    ${downloading ? `<div class="download-progress"><progress id="download-progress" value="${state.transfer.progress}" max="100" aria-label="Sample download progress"></progress><div class="download-status"><span id="download-status">${state.transfer.progress}% · sample transfer</span><button class="text-button" data-action="cancel-download">Cancel</button></div></div>` : `<div class="record-actions"><span class="detail">${saved ? "Sample transfer only" : ".record"}</span>${deviceButton(`${icon(saved ? "check" : "download")} ${saved ? "Download again" : "Download"}`, "download", "secondary small", `data-file="${name}" ${state.transfer ? "disabled" : ""}`)}</div>`}</article>`;
}

function records() {
  if (!state.connected) {
    return `<h1 class="greeting">Data Collection</h1><p class="description">Bring your recordings a little closer.</p><div class="empty-state">${eyes("sleepy")}<h2>Connect to your<br>companion first.</h2><p>Your MOKUKU recordings will appear here once you’re connected.</p>${button("Find MOKUKU", "scan", "primary")}</div>`;
  }
  return `<h1 class="greeting">Data Collection</h1><p class="description">Little moments. Useful details.</p>
    <section class="card"><div class="storage-summary"><div><div class="eyebrow">MOKUKU STORAGE</div><strong>19.6 GB free</strong><div class="detail">31.2 GB total · SD card</div></div><span class="icon-tile">${icon("storage")}</span></div><div class="storage-track" aria-hidden="true"><span></span></div></section>
    <div class="section-heading"><h3>Returned recordings <span class="detail">(${state.records.length})</span></h3><button class="text-button" data-action="refresh-records">${icon("refresh")} Refresh</button></div>
    <div class="record-list">${state.records.length ? state.records.map(recordCard).join("") : '<section class="card centered"><h3>No recordings returned.</h3><p class="description">Start a recording from Debug, then stop it and refresh here.</p><button class="text-button" data-route="debug">Open Debug</button></section>'}</div>
    <p class="detail" style="margin:18px 4px">Files are listed by recording number. Download a file to keep a phone copy; deleting removes the device copy.</p>
    ${deviceButton(`${icon("trash")} Clear all recordings`, "confirm-clear-records", "danger full", state.records.length && !state.transfer ? "" : "disabled")}
    <p class="detail centered">Removes device recordings. Phone copies are kept.</p>`;
}

function tripDate(trip, withTime = true) {
  return trip.start.toLocaleString("en-GB", { weekday: "short", day: "numeric", month: "short", ...(withTime ? { hour: "2-digit", minute: "2-digit" } : {}) });
}

function duration(minutes) {
  return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
}

// The route scaled into a width × height box, north up, as SVG points.
function routePoints(points, width, height, margin) {
  const easts = points.map(([east]) => east), norths = points.map(([, north]) => north);
  const left = Math.min(...easts), bottom = Math.min(...norths);
  const span = Math.max(Math.max(...easts) - left, Math.max(...norths) - bottom, 1);
  const scale = Math.min(width - 2 * margin, height - 2 * margin) / span;
  const offsetX = (width - (Math.max(...easts) - left) * scale) / 2, offsetY = (height - (Math.max(...norths) - bottom) * scale) / 2;
  return points.map(([east, north]) => [offsetX + (east - left) * scale, height - offsetY - (north - bottom) * scale]);
}

function routeSvg(trip, width, height, margin, className) {
  const points = routePoints(trip.points, width, height, margin);
  const [start, end] = [points[0], points[points.length - 1]];
  return `<svg class="${className}" viewBox="0 0 ${width} ${height}" aria-hidden="true"><polyline points="${points.map((point) => point.map((value) => value.toFixed(1)).join(",")).join(" ")}"/><circle class="route-start" cx="${start[0]}" cy="${start[1]}" r="${className === "route-map" ? 7 : 3}"/><circle class="route-end" cx="${end[0]}" cy="${end[1]}" r="${className === "route-map" ? 7 : 3}"/></svg>`;
}

function tripCard(trip) {
  const index = state.trips.indexOf(trip);
  return `<button class="trip-card" data-action="open-trip" data-trip="${index}" aria-label="Trip on ${tripDate(trip)}, ${trip.kilometers} kilometers, ${duration(trip.minutes)}">${routeSvg(trip, 64, 64, 8, "route-thumbnail")}<span class="trip-summary"><strong>${tripDate(trip)}</strong><span class="detail">${trip.kilometers.toFixed(1)} km · ${duration(trip.minutes)}</span></span>${icon("arrow")}</button>`;
}

function syncCard() {
  const device = state.devices[0];
  if (state.sync) {
    return `<section class="card"><div class="card-heading"><span class="icon-tile">${icon("refresh")}</span><div><h3>Syncing trips</h3><p>Trip ${state.sync.index + 1} of ${state.sync.total} · ${state.deviceOnlyTrips[state.sync.index].name}</p></div></div><div class="download-progress"><progress id="sync-progress" value="${state.sync.progress}" max="100" aria-label="Sample sync progress"></progress><div class="download-status"><span id="sync-status">${state.sync.progress}% · sample transfer</span><button class="text-button" data-action="cancel-sync">Cancel</button></div></div></section>`;
  }
  return `<section class="card"><div class="card-heading"><span class="icon-tile">${icon("download")}</span><div><h3>${state.connected ? device.name : "Sync with MOKUKU"}</h3><p>${state.connected ? "Downloads the trips this phone doesn’t have yet." : "Connect to download new trips. Saved trips stay on this phone."}</p></div></div>${deviceButton(`${icon("refresh")} Sync trips`, "sync-trips", "primary full")}${deviceButton(`${icon("trash")} Delete older trips on MOKUKU`, "confirm-delete-trips", "danger full", 'style="margin-top:10px"')}</section>`;
}

function tripCount(count) {
  return count === 1 ? "1 trip" : `${count} trips`;
}

function trips() {
  // The connected MOKUKU first, then the device with the newest trip.
  const connected = state.connected ? state.devices[0].address : null;
  const groups = state.devices.map((device) => ({ device, trips: state.trips.filter((trip) => trip.address === device.address).sort((left, right) => right.start - left.start) }))
    .filter((group) => group.trips.length)
    .sort((left, right) => (right.device.address === connected) - (left.device.address === connected) || right.trips[0].start - left.trips[0].start);
  const list = groups.length
    ? groups.map(({ device, trips }) => {
      const current = device.address === connected;
      return `<section class="trip-group ${current ? "connected" : ""}"><div class="section-heading"><h3>${device.name} <span class="detail">(${trips.length})</span></h3>${current ? '<span class="connection online"><span></span>Connected</span>' : ""}</div><div class="detail mono trip-address">${device.address}</div><div class="trip-list">${trips.map(tripCard).join("")}</div>${button(`${icon("trash")} Delete ${tripCount(trips.length)} from phone`, "confirm-delete-device-trips", "danger full", `data-address="${device.address}" style="margin-top:10px"`)}</section>`;
    }).join("")
    : `<section class="empty-state"><div class="trip-illustration" aria-hidden="true"><span class="orbit"></span><span class="orbit inner"></span>${icon("trips")}<span class="tiny-dot"></span></div><h2>No trips on<br>this phone yet.</h2><p>Connect to MOKUKU and sync. Trips are saved here, grouped by device.</p></section>`;
  return `<h1 class="greeting">Trips</h1><p class="description">Every drive with MOKUKU, kept on this phone.</p>${syncCard()}${list}`;
}

function trip() {
  const current = state.trip;
  if (!current) return trips();
  const device = state.devices.find((item) => item.address === current.address);
  const average = current.kilometers / (current.minutes / 60);
  return `<h1 class="greeting">${tripDate(current, false)}</h1><p class="description">${current.start.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })} · ${device.name}</p>
    <section class="map-card" aria-label="Trip route map">${routeSvg(current, 360, 300, 36, "route-map")}<span class="map-note">Preview · route only. The app draws it on OpenStreetMap.</span><span class="map-attribution">© OpenStreetMap contributors</span></section>
    <div class="map-legend"><span><i class="route-start"></i>Start</span><span><i class="route-end"></i>End</span></div>
    <div class="info-grid">
      <section class="card"><div class="label">${icon("trips")} Distance</div><div class="value">${current.kilometers.toFixed(1)} <span class="detail">km</span></div></section>
      <section class="card"><div class="label">${icon("refresh")} Duration</div><div class="value">${duration(current.minutes)}</div></section>
      <section class="card"><div class="label">${icon("software")} Average speed</div><div class="value">${Math.round(average)} <span class="detail">km/h</span></div></section>
      <section class="card"><div class="label">${icon("update")} Top speed</div><div class="value">${current.maximumKmh} <span class="detail">km/h</span></div><div class="detail">GPS speed</div></section>
    </div>
    <p class="detail" style="margin:18px 4px">From <span class="mono">${current.name}</span> · recorded by MOKUKU from GPS fixes.</p>
    ${button(`${icon("trash")} Delete from phone`, "confirm-delete-phone-trip", "danger full")}`;
}

const pages = { home, updates, simulation, debug, records, trips, trip, tools };
const subpageNames = { updates: "Updates", simulation: "Simulation", debug: "Debug", trip: "Trip" };
const parentPages = { simulation: "home", trip: "trips" };
const pageLabels = { home: "Home", trips: "Trips", tools: "Tools" };

function render() {
  const subpage = Object.hasOwn(subpageNames, state.route);
  const parent = parentPages[state.route] || "tools";
  document.getElementById("app-heading").innerHTML = `<header class="app-header">${subpage ? `<div class="subpage-heading"><button class="icon-button" data-route="${parent}" aria-label="Back to ${pageLabels[parent]}">${icon("back")}</button><h2>${subpageNames[state.route]}</h2></div>` : '<div><div class="app-wordmark">MOKUKU</div><div class="app-subtitle">YOUR LITTLE COMPANION</div></div>'}${status()}</header>`;
  screen.innerHTML = pages[state.route]();
  const navigation = document.getElementById("bottom-navigation");
  navigation.hidden = subpage;
  navigation.style.display = subpage ? "none" : "flex";
  navigation.innerHTML = [["home", "Home"], ["records", "Data Collection"], ["trips", "Trips"], ["tools", "Tools"]].map(([route, label]) => `<button class="tab ${state.route === route ? "active" : ""}" data-route="${route}" ${state.route === route ? 'aria-current="page"' : ""}>${icon(route)}<span>${label}</span></button>`).join("");
  screen.scrollTop = state.scroll[state.route] || 0;
  const previewName = state.route === "home" && !state.connected ? "disconnected" : state.route;
  document.querySelectorAll("[data-preview]").forEach((item) => item.classList.toggle("active", item.dataset.preview === previewName));
}

function navigate(route) {
  if (!Object.hasOwn(pages, route)) return;
  state.scroll[state.route] = screen.scrollTop;
  clearTimeout(queryTimer);
  state.route = route;
  render();
  screen.focus({ preventScroll: true });
}

function toast(message) {
  const element = document.getElementById("toast");
  clearTimeout(toastTimer);
  element.textContent = message;
  element.classList.add("visible");
  toastTimer = setTimeout(() => element.classList.remove("visible"), 3800);
}

function openSheet(title, content) {
  sheet.innerHTML = `<div class="sheet-handle" aria-hidden="true"></div><h2 id="sheet-title" tabindex="-1">${title}</h2>${content}`;
  if (!sheet.open) sheet.showModal();
  positionSheet();
  document.getElementById("sheet-title").focus({ preventScroll: true });
}

function positionSheet() {
  if (!sheet.open) return;
  const bounds = document.querySelector(".phone").getBoundingClientRect();
  sheet.style.width = `${bounds.width - 16}px`;
  sheet.style.left = `${bounds.left + 8}px`;
  sheet.style.bottom = `${Math.max(8, window.innerHeight - bounds.bottom + 19)}px`;
  sheet.style.maxHeight = `${Math.min(bounds.height * .82, window.innerHeight - 32)}px`;
}

function closeSheet() {
  clearTimeout(scanTimer);
  sheet.close();
}

function scan() {
  openSheet("Find your MOKUKU", '<p id="scan-status" role="status">Looking for nearby companions…</p><p class="detail">Preview scan · no Bluetooth access is requested.</p>' + button("Cancel", "close-sheet", "secondary full"));
  scanTimer = setTimeout(() => {
    if (!sheet.open) return;
    openSheet("A companion, found.", `<p>Choose your MOKUKU to get connected.</p><button class="scan-result" data-action="connect">${eyes()}<span><strong>mokuku_73A1</strong><span class="detail mono">A4:CF:12:6B:73:A1</span><br><span class="detail">Nearby · −54 dBm · sample device</span></span>${icon("arrow")}</button>${button("Search again", "scan", "secondary full")}${button("Cancel", "close-sheet", "secondary full", 'style="margin-top:10px"')}`);
  }, 650);
}

function cancelDownload(notify = true) {
  clearInterval(transferTimer);
  state.transfer = null;
  if (state.route === "records") {
    state.scroll.records = screen.scrollTop;
    render();
  }
  if (notify) toast("Sample download canceled. The device file is unchanged.");
}

function download(name) {
  if (state.transfer || !state.records.includes(name)) return;
  state.transfer = { name, progress: 0 };
  state.scroll.records = screen.scrollTop;
  render();
  toast("Preview: demonstrating a download with sample data.");
  transferTimer = setInterval(() => {
    state.transfer.progress = Math.min(state.transfer.progress + 10, 100);
    const progress = document.getElementById("download-progress");
    if (progress) {
      progress.value = state.transfer.progress;
      document.getElementById("download-status").textContent = `${state.transfer.progress}% · sample transfer`;
    }
    if (state.transfer.progress === 100) {
      state.downloaded.add(name);
      cancelDownload(false);
      toast("Sample download complete. No file was saved to your phone.");
    }
  }, 250);
}

function cancelSync(notify = true) {
  clearInterval(transferTimer);
  state.sync = null;
  if (state.route === "trips") {
    state.scroll.trips = screen.scrollTop;
    render();
  }
  if (notify) toast("Sample sync canceled. Trips already saved stay on this phone.");
}

function syncTrips() {
  if (state.sync || state.transfer) return;
  if (!state.deviceOnlyTrips.length) {
    toast(`Preview: this phone already has every trip from ${state.devices[0].name}.`);
    return;
  }
  state.sync = { index: 0, total: state.deviceOnlyTrips.length, progress: 0 };
  render();
  toast("Preview: demonstrating a trip sync with sample data.");
  transferTimer = setInterval(() => {
    state.sync.progress = Math.min(state.sync.progress + 20, 100);
    if (state.sync.progress === 100) {
      state.sync.index++;
      state.sync.progress = 0;
      if (state.sync.index === state.sync.total) {
        const count = state.sync.total;
        state.trips.push(...state.deviceOnlyTrips);
        state.deviceOnlyTrips = [];
        cancelSync(false);
        toast(`Sample sync complete: ${count} new trips. No files were saved to your phone.`);
        return;
      }
    }
    if (state.route === "trips") {
      state.scroll.trips = screen.scrollTop;
      render();
    }
  }, 250);
}

function confirmUpdate(kind, eye) {
  const rollback = kind === "Firmware rollback";
  const target = eye === "both" ? "both eyes" : `the ${eye} eye`;
  openSheet(`${rollback ? "Request rollback?" : "Request update?"}`, `<p><strong>${kind}</strong> for ${target}.</p><p>${rollback ? "MOKUKU will try to boot the previous firmware for this eye." : "MOKUKU will download the update using its saved Wi-Fi and remote URL."} The device may restart and disconnect.</p>${eye === "left" && !rollback ? '<div class="notice warm">Update the right eye first, then reconnect before updating the left eye.</div>' : ""}<div class="button-row">${button("Cancel", "close-sheet")}${button(rollback ? "Request rollback" : "Request update", "request-update", rollback ? "danger" : "primary")}</div>`);
}

const actions = {
  scan,
  "close-sheet": closeSheet,
  connect() {
    state.connected = true;
    state.hasConnected = true;
    closeSheet();
    navigate("home");
    toast("Preview: sample MOKUKU connected. Showing sample information.");
  },
  disconnect() {
    openSheet("Disconnect MOKUKU?", '<p>You can reconnect whenever you’re ready.</p>' + `<div class="button-row">${button("Stay connected", "close-sheet")}${button("Disconnect", "confirm-disconnect", "primary")}</div>`);
  },
  "confirm-disconnect"() {
    state.connected = false;
    cancelDownload(false);
    cancelSync(false);
    closeSheet();
    render();
    toast("Sample device disconnected.");
  },
  "refresh-info"() {
    toast("Refreshing sample device information…");
    queryTimer = setTimeout(() => toast("Sample information refreshed."), 700);
  },
  "refresh-records"() { toast("Sample recording list refreshed."); },
  async "copy-mac"() {
    try {
      await navigator.clipboard.writeText("A4:CF:12:6B:73:A1");
      toast("Sample MAC address copied.");
    } catch {
      openSheet("Sample MAC address", '<div class="filename-confirm">A4:CF:12:6B:73:A1</div><p>Select this sample address to copy it.</p>' + button("Close", "close-sheet", "secondary full"));
    }
  },
  "reveal-password"(element) {
    const field = document.getElementById("wifi-password");
    const show = field.type === "password";
    field.type = show ? "text" : "password";
    element.setAttribute("aria-label", show ? "Hide password" : "Show password");
    element.setAttribute("aria-pressed", String(show));
  },
  "confirm-update"(element) { confirmUpdate(element.dataset.kind, element.dataset.eye); },
  "request-update"() {
    closeSheet();
    toast("Preview: update request demonstrated. No command was sent.");
  },
  command(element) { toast(`Preview: ${element.dataset.command} request. No device command was sent.`); },
  "set-date"() {
    openSheet("Set date & time", `<p>Choose a test date or use your phone’s clock.</p><label class="field">Date & time<input id="test-date" type="datetime-local" value="2026-11-13T09:41"></label>${button("Use phone time", "phone-time", "secondary full")}<p class="detail">A GPS fix can override MOKUKU’s debug date.</p><div class="button-row">${button("Cancel", "close-sheet")}${button("Set date & time", "confirm-date", "primary")}</div>`);
  },
  "phone-time"() {
    const now = new Date();
    const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
    document.getElementById("test-date").value = local.toISOString().slice(0, 16);
  },
  "confirm-date"() {
    const field = document.getElementById("test-date");
    if (!field.value || !field.reportValidity()) return;
    closeSheet();
    toast("Preview: date & time request. The device clock was not changed.");
  },
  download(element) { download(element.dataset.file); },
  "sync-trips": syncTrips,
  "cancel-sync"() { cancelSync(); },
  "confirm-delete-trips"() {
    openSheet("Delete older trips on MOKUKU?", `<p>MOKUKU syncs first, then deletes every trip on its SD card except the latest. Phone copies are kept. If any trip fails to sync, nothing is deleted.</p><p class="detail">Design preview: no trips are deleted.</p><div class="button-row">${button("Keep trips", "close-sheet")}${button("Sync and delete", "delete-trips", "danger")}</div>`);
  },
  "delete-trips"() {
    closeSheet();
    toast("Preview: sync and delete demonstrated. No device trips were deleted.");
  },
  "confirm-delete-device-trips"(element) {
    const device = state.devices.find((item) => item.address === element.dataset.address);
    const count = state.trips.filter((trip) => trip.address === device.address).length;
    state.deleteAddress = device.address;
    openSheet(`Delete ${device.name}’s trips from this phone?`, `<p>${tripCount(count)} from <span class="mono">${device.address}</span>. Trips still on MOKUKU download again on the next sync.</p><div class="button-row">${button("Keep trips", "close-sheet")}${button("Delete", "delete-device-trips", "danger")}</div>`);
  },
  "delete-device-trips"() {
    state.trips = state.trips.filter((trip) => trip.address !== state.deleteAddress);
    closeSheet();
    render();
  },
  "confirm-delete-phone-trip"() {
    const device = state.devices.find((item) => item.address === state.trip.address);
    openSheet("Delete this trip from the phone?", `<p><span class="mono">${state.trip.name}</span> from ${device.name}. If it is still on MOKUKU, the next sync downloads it again.</p><div class="button-row">${button("Keep trip", "close-sheet")}${button("Delete", "delete-phone-trip", "danger")}</div>`);
  },
  "delete-phone-trip"() {
    state.trips = state.trips.filter((trip) => trip !== state.trip);
    state.trip = null;
    closeSheet();
    navigate("trips");
  },
  "open-trip"(element) {
    state.trip = state.trips[Number(element.dataset.trip)];
    state.scroll.trip = 0;
    navigate("trip");
  },
  "cancel-download"() { cancelDownload(); },
  "record-menu"(element) {
    const name = element.dataset.file;
    openSheet("Recording actions", `<div class="filename-confirm">${name}</div>${button("Delete from MOKUKU", "confirm-delete", "danger full", `data-file="${name}"`)}${button("Cancel", "close-sheet", "secondary full", 'style="margin-top:12px"')}`);
  },
  "confirm-delete"(element) {
    const name = element.dataset.file;
    openSheet("Delete device recording?", `<div class="filename-confirm">${name}</div><p>This removes the recording from MOKUKU. Any copy already saved on your phone is kept.</p><p class="detail">Design preview: only the sample row will be removed.</p><div class="button-row">${button("Keep recording", "close-sheet")}${button("Delete", "delete", "danger", `data-file="${name}"`)}</div>`);
  },
  "confirm-clear-records"() {
    openSheet("Clear all recordings?", `<p>This removes every recording from MOKUKU, including files you haven’t downloaded. Copies already saved on your phone are kept.</p><div class="notice warm">Stop recording before clearing device files.</div><p class="detail">Design preview: only the ${state.records.length} sample recordings will be removed.</p><div class="button-row">${button("Keep recordings", "close-sheet")}${button("Clear all recordings", "clear-records", "danger")}</div>`);
  },
  "clear-records"() {
    state.records = [];
    closeSheet();
    state.scroll.records = 0;
    render();
    toast("Preview: all sample device recordings cleared. Phone copies are unchanged.");
  },
  delete(element) {
    state.records = state.records.filter((name) => name !== element.dataset.file);
    closeSheet();
    state.scroll.records = screen.scrollTop;
    render();
    toast("Preview: sample recording removed from the list.");
  },
};

document.addEventListener("click", (event) => {
  const element = event.target.closest("button");
  if (!element || element.disabled) return;
  if (element.dataset.preview) {
    closeSheet();
    const target = element.dataset.preview;
    state.connected = target !== "disconnected";
    state.hasConnected = state.connected;
    if (!state.connected) { cancelDownload(false); cancelSync(false); }
    if (target === "trip") state.trip = state.trip || state.trips[0];
    navigate(target === "disconnected" ? "home" : target);
    return;
  }
  if (element.dataset.route) {
    navigate(element.dataset.route);
    return;
  }
  if (Object.hasOwn(actions, element.dataset.action)) actions[element.dataset.action](element);
});

document.addEventListener("submit", (event) => {
  if (!event.target.matches("#wifi-settings, #remote-url-settings")) return;
  event.preventDefault();
  if (!state.connected) return;
  const setting = event.target.id === "wifi-settings" ? "Wi-Fi" : "Remote URL";
  event.target.querySelector('[role="status"]').textContent = `Preview: ${setting} entries accepted locally. No settings were sent or stored.`;
});

sheet.addEventListener("close", () => clearTimeout(scanTimer));
window.addEventListener("resize", positionSheet);
sheet.addEventListener("click", (event) => {
  if (event.target !== sheet) return;
  const bounds = sheet.getBoundingClientRect();
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) closeSheet();
});

render();

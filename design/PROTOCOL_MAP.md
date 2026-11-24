# Protocol map

## Original MOKUKU (`mokuku_*`)

Scope: two ESP32-S3 eyes. All control/file writes use Transfer Message
`d222e154-1a80-4e71-9a63-2aa2c0ce0a8c`. Subscribe to Transfer Data
`beb5483e-36e1-4688-b7f5-ea07361b26a8` before sending queries.
Service: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`.

Source of truth: parent repository `IDF_SHARED/backend/ble_messager.cc`,
`IDF_SHARED/sdcard/sd_card.c`, and `MOKUKU-BleControl/monitor_app/ble_client.py`.
The parent `doc/BLE_CONTROL.md` is useful context; SD free/total ordering below
follows the firmware. Setting a calendar date differs from the desktop client's
clock-only time sync, as detailed below.

| Screen / action | Request | Reply / UI constraint |
|---|---|---|
| Firmware versions | `03` | `03 01` + UTF-8 `left/right` |
| MAC address | `04` | `04 01` + formatted address |
| Meme versions | `05` | `05 01` + UTF-8 `left/right` |
| SD information | `3D` (61) | `3D 01` + text `free_MB/total_MB`, **free first** |
| Voice model | `39` (57) | `39 01 value`: 0 other/unavailable, 1 `wn10_himokuku` English, 2 Mandarin |
| Wi-Fi name / password / remote URL | ID 7 / 8 / 9, UTF-8 byte length, UTF-8 text | Individual two-byte ID/success replies; no readback API |
| Firmware OTA | `01 43` right (67), `01 42` left (66) | Command acceptance precedes update; right first, then left. Only left-eye requests drop BLE; the left eye has no BLE until its update ends. Both eyes: 67, then 66 after 500 ms, then reconnect |
| Rollback | `01 47` right (71), `01 46` left (70) | Acceptance is not proof of a successful rollback. Right-eye rollback keeps BLE |
| Voice OTA | `01 48` English (72), `01 49` Mandarin (73) | Both eyes' wake-word assets and right-eye model |
| Meme update | `01 45` right (69), `01 44` left (68) | Wi-Fi download on device; no BLE progress reply |
| Left / right click | `01 2B` (43) / `01 35` (53) | Direct-command acceptance reply |
| Vehicle data | Transfer Data `01 speed rpm×4_BE16 fuel` | No reply. The app sends 5 bytes: the 11-byte form also sets the clock and debug date |
| Eye mode change | `01 0A` (10) | Toggle; current mode is not queried |
| Enable / disable OBD | `01 22` (34) / `01 23` (35) | Commands only; no state readback |
| Enable debug log | `01 04` | Enable only; no corresponding disable command |
| Start / stop recording | `01 01` / `01 02` | Commands only; refresh files after stopping |
| List recordings | `3C len /sd/record` (60) | ID, success, comma-separated names; no metadata or pagination |
| Delete recording | `3E len /sd/record/name` (62) | No guaranteed operation-specific acknowledgement; refresh to verify |
| Clear all recordings | Repeated list (60) and per-file delete (62) | No bulk-delete command; repeat batches until no names return, detect no progress, preserve phone copies; empty results also occur on directory failure |
| Download recording | File Command start `02 00`, offset LE32 `0`, path, CRC16 | Reply: size LE32, sector size LE32, prefix CRC16; then File Data sectors |
| List trips | `3C len /sd/trip` (60) | Comma-separated `trip_<n>.bin` names. The reply is dropped, not truncated, once it exceeds MTU − 3 bytes (about 40 trips), so the app also probes `trip_<n+1>.bin` upward from the highest number it knows until a start is refused with status 2 |
| Download trip | File Command start, offset LE32, `/sd/trip/trip_<n>.bin` | Offset = bytes the phone has; the reply's prefix CRC16 must match them, else keep the phone copy and skip |
| Delete trips | `3A first_LE32 last_LE32` (58) | `3A 01` when done; deletes `trip_<n>.bin` from first to last except the trip recording. Needs the access code |
| Trip recording | `3B` to query, `3B 00`/`3B 01` to turn off/on (59) | `3B 01 enabled`; `3B 00` when it cannot be saved. Saved on MOKUKU, off by default |

Downloads use the sector file transfer shared with MOKUKU II (MobiliMOKUKU
`doc/MOKUKU_II_BLE_CONTROL.md`). Each File Data packet is sector LE16, sequence
number, bytes; sequence `FF` marks the last packet, which ends with the sector's
CRC-16/XMODEM. Android acknowledges every sector on File Data with sector LE16,
status LE16 (0 OK, 1 resend), and next sector LE16. MOKUKU refuses a path
(status 1) until message 44 carries the access code; the code is applied on another
task, so Android retries a refused start up to three times.

Hex byte values are shown without a prefix; parenthesized command numbers are
decimal. Length-prefixed paths and strings count UTF-8 bytes, not characters.
Firmware handlers use strings as C strings in several places; the implementation
must match the existing desktop client's framing and test against a physical device.

File list, download, and delete require the existing developer authorization
exchange on message ID 44. Collect the code in the native Data Collection dialog
and save it in app-private preferences keyed by BLE device address. Restore it
after reconnect or app restart; changing or forgetting affects only that device.
Exclude saved codes from backup and device transfer. ID 44 has no application reply;
GATT write completion does not prove authorization. A rejected code can leave file
requests unanswered. Do not invent a MOKUKU II set/get handshake or include codes in
logs, previews, source, or build configuration.

Set date/time by writing the existing 11-byte data packet on **Transfer Data**:
`01 speed rpm_high rpm_low fuel timestamp_LE32 backlight command`.
The native app encodes the selected local calendar fields as UTC-based seconds,
with speed 255, RPM/fuel 0, backlight 0, and command 0. This preserves the selected
wall-clock time without inventing vehicle data. Hardware verification is pending.
The firmware uses this value both modulo 86400 for its clock and through `localtime`
for its debug calendar date when GPS has no fix. The existing desktop time-sync
helper sends local seconds since midnight, which does not encode a selected calendar
date. Verify both clock and calendar behavior on hardware; the app does not reuse that helper
unchanged. Zero timestamp is ignored; zero backlight does not
change brightness. This packet also updates speed/RPM/fuel, so the date action must
define those values deliberately rather than sending an unrelated command packet.
GPS can override the debug calendar date.

Command success is `ID, 1`; failure is `ID, 0` where a handler sends it. Direct
commands are acknowledged before execution and do not prove the physical action
finished. Query errors and missing replies need timeouts; retry only operations
that remain valid after reconnecting.

The current directory handler fits names into a bounded response and skips names
that do not fit. Android must not claim “all recordings loaded” from that response.
The same handler returns an empty successful reply if opening the directory fails,
so **No recordings returned after clearing** is the strongest supported completion
message; an empty reply does not prove every device file was deleted.
The firmware sends nothing when the names exceed one notification (MTU − 3 bytes), or
without the access code; the app treats 8 s of silence as no list and stays connected.
Large directory/notification handling needs hardware verification before shipping.

## MOKUKU II (`mokukuii*`)

Source of truth: parent `doc/MOKUKU_II_BLE_CONTROL.md`,
`IDF_MOKUKU_II_prototype/main/bluetooth/host_server.c`, and
`MOKUKU-BleControl/monitor_app_II/`. Every message goes to Transfer Message, which
also notifies replies: set is `02 property value`, get `03 property request`; replies
are `type property result value`, result 0 OK. File Command and File Data carry the
same sector transfer as above.

| Screen / action | Request | Reply / UI constraint |
|---|---|---|
| Version / MAC / OBD / SD / wake word / language / firmware slots | Get 1 / 2 / 6 / 7 / 8 / 9 / 16 | SD is total MB, free MB (LE32 each); MAC 6 bytes; language `cn` or `en` |
| Wi-Fi name / password / remote address | Set 3 / 4 / 5, UTF-8 text | 1–64 bytes |
| Enable / disable OBD | Set 6 `01` / `00` | App reads property 6 back |
| Next left / right panel, meme/data mode | Set 15 `01` / `02` / `03` | Simulation |
| Vehicle data | Data `01 speed rpm×4_BE16 fuel` | No reply; same 5 bytes as the original |
| Voice command language | Set 9 `en` / `cn` | Restarts when the language changes |
| Rollback | Set 200, no value | Restarts; result 4 when the other slot is not older |
| List recordings | Get 10, first index LE16, `/sdcard/record` | One notification of names per request; repeat from the next index until empty |
| Clear all recordings | Set 13, no value | Result 4 while recording; no per-file delete |
| Download recording | File Command start `02 00`, `/sdcard/record/name` | No access code |
| List trips | Get 10, first index LE16, `/sdcard/trip` | Paged like recordings; result 4 when the directory is missing (no trips yet) or no SD card |
| Download trip | File Command start, offset LE32, `/sdcard/trip/trip_<n>.bin` | Same resume rule as the original |
| Delete trips | Set 17, first LE32, last LE32 | As the original's message 58; result 4 without a card or when a file cannot be deleted |
| Trip recording | Get/set 18, 1 byte | `0` off (the default), `1` on |
| Firmware update | File Command start `C8 00`, size LE32 | Reply: sector size LE32, packet size LE16; app sends each sector's packets on File Data and waits for its acknowledgement; MOKUKU II restarts after the last |

MOKUKU II has no debug-log, date/time, recording start/stop, or meme-update
messages. It disconnects a host that writes nothing for 30 s, so the app sends get 1
after 10 s of silence. A reply longer than MTU − 3 is not notified; the app relies on
MTU 517 rather than reading Transfer Message back.

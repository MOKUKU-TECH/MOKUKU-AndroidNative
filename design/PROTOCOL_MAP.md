# Original MOKUKU protocol map

Scope: two ESP32-S3 eyes. All control/file writes use Transfer Message
`d222e154-1a80-4e71-9a63-2aa2c0ce0a8c`. Subscribe to Transfer Data
`beb5483e-36e1-4688-b7f5-ea07361b26a8` before sending queries.
Service: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`.

Source of truth: parent repository `IDF_SHARED/backend/ble_messager.cc`,
`IDF_SHARED/sdcard/sd_card.c`, and `BleControl/monitor_app/ble_client.py`.
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
| Firmware OTA | `01 43` right (67), `01 42` left (66) | Command acceptance precedes update; right first, reconnect before left |
| Rollback | `01 47` right (71), `01 46` left (70) | Acceptance is not proof of a successful rollback |
| Voice OTA | `01 48` English (72), `01 49` Mandarin (73) | Both eyes' wake-word assets and right-eye model |
| Meme update | `01 45` right (69), `01 44` left (68) | Wi-Fi download on device; no BLE progress reply |
| Left / right click | `01 2B` (43) / `01 35` (53) | Direct-command acceptance reply |
| Eye mode change | `01 0A` (10) | Toggle; current mode is not queried |
| Enable / disable OBD | `01 22` (34) / `01 23` (35) | Commands only; no state readback |
| Enable debug log | `01 04` | Enable only; no corresponding disable command |
| Start / stop recording | `01 01` / `01 02` | Commands only; refresh files after stopping |
| List recordings | `3C len /sd/record` (60) | ID, success, comma-separated names; no metadata or pagination |
| Delete recording | `3E len /sd/record/name` (62) | No guaranteed operation-specific acknowledgement; refresh to verify |
| Clear all recordings | Repeated list (60) and per-file delete (62) | No bulk-delete command; repeat batches until no names return, detect no progress, preserve phone copies; empty results also occur on directory failure |
| Open download | `41 len /sd/record/name` (65) | 10 bytes: ID, success, file key LE32, size LE32 |
| Read download chunk | `42 requested_length key_LE32 offset_LE32` (66) | ID, length, offset LE32, bytes; validate offset and continue to advertised size |

Hex byte values are shown without a prefix; parenthesized command numbers are
decimal. Length-prefixed paths and strings count UTF-8 bytes, not characters.
Firmware handlers use strings as C strings in several places; the implementation
must match the existing desktop client's framing and test against a physical device.

File list, open/read, and delete require the existing developer authorization
exchange on message ID 44. Use the reference client's session setup; do not invent
a MOKUKU II set/get handshake or expose authorization internals as normal UI.
Use the deployment's existing authorization value without putting it in logs or
design screenshots.

Set date/time by writing the existing 11-byte data packet on **Transfer Data**:
`01 speed rpm_high rpm_low fuel timestamp_LE32 backlight command`.
The firmware uses this value both modulo 86400 for its clock and through `localtime`
for its debug calendar date when GPS has no fix. The existing desktop time-sync
helper sends local seconds since midnight, which does not encode a selected calendar
date. A date-setting implementation must define the epoch/timezone encoding and
verify both clock and calendar behavior on hardware; it cannot reuse that helper
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
Large directory/notification handling needs hardware verification before shipping.

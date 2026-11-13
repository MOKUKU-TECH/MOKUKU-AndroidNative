# Agent instructions

Direct user instructions take precedence; flag conflicts with these rules.

## Scope and references

This repository is the native Android companion for the original two-eye ESP32-S3
MOKUKU. Do not add MOKUKU II support or its set/get protocol.

Read [README.md](README.md) before working. For UI changes, read
[design/README.md](design/README.md); for BLE and device actions, read
[design/PROTOCOL_MAP.md](design/PROTOCOL_MAP.md).

The current Android app is a Java/XML starter. `design/` contains an offline
browser prototype with sample data. Keep prototype interactions clearly labeled;
do not present sample connections, settings, downloads, or commands as real device
operations. Implement native features only within the requested task's scope.

When checked out inside `MobiliMOKUKU`, verify protocol behavior against
`../IDF_SHARED/backend/ble_messager.cc`, `../IDF_SHARED/sdcard/sd_card.c`, and
`../BleControl/monitor_app/`. Follow the parent repository's applicable instructions.
For standalone checkouts, use the local protocol map and identify behavior that
still needs firmware or hardware verification rather than guessing.

## Repository layout

| Path | Purpose |
|---|---|
| `app/` | Native Android manifest, Java code, and XML resources. |
| `design/` | Style guide, screen flows, protocol map, and HTML/CSS/JavaScript prototype. |
| `design/previews/` | Screenshots rendered from the prototype. |
| `gradle/`, `gradlew`, `gradlew.bat` | Checked-in Gradle wrapper. |
| `scripts/build.sh` | Build, lint, export, and optional installation helper. |
| `.github/workflows/android.yml` | GitHub Actions APK build and artifact upload. |
| `artifacts/docker/dev.dockerfile` | Optional local Android build environment. |

## Product and design

- Keep four tabs: **Home**, **Data Collection**, **Trips**, and **Tools**.
  Simulation opens from Home; Updates and Debug open from Tools. Trips remains
  “Coming later” until its implementation is requested.
- Follow the charcoal/slate surfaces, muted mint accents, spacing, typography,
  and touch targets in the style guide. Preserve accessible labels, readable
  contrast, system font scaling, reduced motion, and Android Back behavior.
- Wi-Fi credentials and the remote URL have separate forms, save buttons,
  validation, and feedback. Changing one must not require completing the other.
- Show device information only after it is received. Distinguish cached values,
  unavailable information, command acceptance, and verified completion.
- Preserve right-eye-first update ordering. Require confirmation for rollback,
  per-file deletion, and clearing all recordings. Device deletion keeps phone copies.
- Do not invent recording dates, sizes, or complete-list guarantees from a
  filename-only directory reply. Follow the protocol map's clear-all limitations.
- Mask passwords, allow deliberate reveal, and keep credentials out of logs,
  screenshots, commits, and prototype storage.

## Build and verification

Use JDK 17 and Android SDK 35 with Build Tools 35.0.0. Use the checked-in wrapper;
do not depend on a globally installed Gradle. Set `ANDROID_HOME` or local SDK
configuration as described in the README.

```bash
./scripts/build.sh
```

This runs `:app:assembleDebug` and `:app:lintDebug`, then exports
`output/mokuku-debug.apk`. Install on a device only when requested, using
`./scripts/build.sh --install`. Docker is optional for Android builds; firmware
container instructions in the parent repository concern firmware changes.

For Android or build configuration changes, run the build helper. For prototype
JavaScript changes, run `node --check design/app.js` and exercise the affected
browser flows, including disconnected and canceled actions. Check narrow layouts
at 320 and 390 pixels when changing layout. Refresh affected screenshots from the
rendered prototype, excluding entered credentials. Documentation-only edits need
link and diff checks rather than an unrelated app rebuild.

GitHub Actions is the CI build path. Do not add `.pipeline/`. Automatic APK builds
watch app source and build inputs; documentation/design-only changes must not
trigger them. Preserve the shared push/PR path filter, manual builds, and APK
artifact upload. Inspect failed checks before reporting a PR ready. Do not add
placeholder tests or new test infrastructure for minor visual or documentation changes.

## Changes and pull requests

- Keep changes focused. Prefer the simplest concrete design, descriptive names,
  and existing helpers. Avoid speculative abstractions, compatibility layers,
  dependencies, and broad refactors.
- Keep prose and comments concise. Explain a non-obvious reason rather than
  narrating the code.
- Update the README for setup or build changes, the style guide and previews for
  UI changes, and the protocol map for verified protocol discoveries. Update this
  file when contributor workflows or repository layout change.
- Before a PR, review changed code separately for reuse, quality, and efficiency;
  use `$simplify` when available. Apply concrete findings and summarize validation
  and rejected findings in the PR description.
- Use a feature branch and target `main`. State whether the change is a design
  prototype or a working Android feature; do not claim hardware verification
  without a device check. Leave merging to the user unless requested.
- Preserve unrelated edits. Do not force-push, reset hard, delete branches, or
  bypass hooks without explicit user confirmation in the current session.
- Keep generated builds, local SDK paths, keystores, and credentials untracked.
  Do not modify the parent repository's submodule pointer unless requested.

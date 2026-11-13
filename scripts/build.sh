#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--install" ) ]]; then
    echo "Usage: $0 [--install]" >&2
    exit 2
fi

repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

./gradlew --no-daemon :app:assembleDebug :app:lintDebug
mkdir -p output
cp app/build/outputs/apk/debug/app-debug.apk output/mokuku-debug.apk
echo "APK: $repository_root/output/mokuku-debug.apk"

if [[ "${1:-}" == "--install" ]]; then
    ./gradlew --no-daemon :app:installDebug
fi

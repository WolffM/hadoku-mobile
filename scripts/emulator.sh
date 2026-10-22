#!/usr/bin/env bash
# Boots the headless test emulator unless a device is already on adb, and
# returns once Android has finished booting. Idempotent: run it before any
# device test. The AVD is `spike` on hokon (API 36, google_apis, x86_64);
# override with AVD=<name>.
set -euo pipefail

sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
export PATH="$sdk/platform-tools:$sdk/emulator:$PATH"
avd=${AVD:-spike}

if adb devices | grep -qE '\s(device)$'; then
  echo "device already attached: $(adb devices | awk '$2=="device"{print $1}' | paste -sd' ')"
else
  log=${TMPDIR:-/tmp}/hadoku-emulator.log
  echo "booting $avd (log: $log)"
  nohup emulator -avd "$avd" -no-window -no-audio -gpu swiftshader_indirect -memory 2048 \
    -no-snapshot-save > "$log" 2>&1 &
  adb wait-for-device
fi

for _ in $(seq 1 180); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { echo booted; exit 0; }
  sleep 1
done
echo "emulator did not finish booting in 180 s" >&2
exit 1

#!/usr/bin/env bash
# Boots the headless test emulator unless a device is already on adb, and
# returns once Android has finished booting. Idempotent: run it before any
# device test. The AVD is `spike` on hokon (API 36, google_apis, x86_64);
# override with AVD=<name>.
#
#   scripts/emulator.sh         boot (or reuse) and register the caller as an owner
#   scripts/emulator.sh stop    shut the emulator down now
#
# WHO OWNS IT. The emulator outlives the command that booted it, so that
# back-to-back test runs share one boot. It must not outlive the SESSION:
# on 2026-11-23 one booted by a session on 11-21 had run for 41 hours after
# that session ended, holding 2 GB and most of a core on hokon at a load of
# 150-220. Each call records its owner, and a watcher stops the emulator once
# no owner is alive. The owner is EMULATOR_OWNER_PID if set, else the agent
# session (CLAUDE_PID), else the caller's terminal session (its session
# leader). Never the command's own parent: pnpm exits after every test, and
# that would cold-boot the emulator each run.
set -euo pipefail

sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
export PATH="$sdk/platform-tools:$sdk/emulator:$PATH"
avd=${AVD:-spike}
state=${XDG_RUNTIME_DIR:-${TMPDIR:-/tmp}}/hadoku-emulator-$avd
owners=$state/owners
watch_sec=${EMULATOR_WATCH_SEC:-60}
mkdir -p "$state"

# Emulator serials only: a phone on adb is never ours to stop.
emulators() { adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}'; }

# A pid alone can be reused; pid + its start time (field 22 of /proc/<pid>/stat)
# names one process.
proc_id() {
  local start
  start=$(awk '{print $22}' "/proc/$1/stat" 2>/dev/null) || return 1
  echo "$1:$start"
}
alive() {
  local pid=${1%%:*}
  [ "$(proc_id "$pid" 2>/dev/null)" = "$1" ]
}

stop_emulators() {
  local s
  for s in $(emulators); do
    echo "stopping $s"
    adb -s "$s" emu kill > /dev/null
  done
  rm -f "$owners"
}

watch() {
  # One watcher per AVD: the lock is held for as long as this loop runs.
  exec 9> "$state/watch.lock"
  flock -n 9 || exit 0
  while sleep "$watch_sec"; do
    [ -n "$(emulators)" ] || { rm -f "$owners"; exit 0; }
    local o any=0
    while read -r o; do
      [ -n "$o" ] && alive "$o" && any=1
    done < "$owners" 2> /dev/null || true
    if [ "$any" = 0 ]; then
      echo "$(date -u +%FT%TZ) no owner of $avd is alive; stopping it"
      stop_emulators
      exit 0
    fi
  done
}

case "${1:-}" in
  stop)
    stop_emulators
    exit 0
    ;;
  --watch)
    watch
    exit 0
    ;;
  '') ;;
  *)
    echo "usage: $0 [stop]" >&2
    exit 2
    ;;
esac

owner_pid=${EMULATOR_OWNER_PID:-${CLAUDE_PID:-$(ps -o sid= -p $$ | tr -d ' ')}}
owner=$(proc_id "$owner_pid") || {
  echo "owner pid $owner_pid is not running" >&2
  exit 1
}

if adb devices | grep -qE '\s(device)$'; then
  echo "device already attached: $(adb devices | awk '$2=="device"{print $1}' | paste -sd' ')"
else
  log=${TMPDIR:-/tmp}/hadoku-emulator.log
  echo "booting $avd (log: $log)"
  nohup emulator -avd "$avd" -no-window -no-audio -gpu swiftshader_indirect -memory 2048 \
    -no-snapshot-save > "$log" 2>&1 &
  adb wait-for-device
fi

if [ -n "$(emulators)" ]; then
  grep -qxF "$owner" "$owners" 2> /dev/null || echo "$owner" >> "$owners"
  # Detached from this command and its terminal; a second one exits on the lock.
  setsid nohup "$0" --watch >> "$state/watch.log" 2>&1 < /dev/null &
fi

for _ in $(seq 1 180); do
  [ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = 1 ] && {
    echo booted
    exit 0
  }
  sleep 1
done
echo "emulator did not finish booting in 180 s" >&2
exit 1

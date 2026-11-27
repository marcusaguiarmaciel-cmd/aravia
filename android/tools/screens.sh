#!/bin/sh
# Installs a debug APK on a running emulator, opens each demo screen (app/aravia/Demo.kt) and
# saves a screenshot of it, then the log. Fails when the app crashed.
#
#   sh android/tools/screens.sh app-debug.apk out/screens
set -e
APK=$1
OUT=${2:-screens}
PKG=app.aravia.watch
ACT=$PKG/app.aravia.MainActivity
mkdir -p "$OUT"

# A CI emulator is slow: let System UI settle, and keep its "isn't responding" prompt off the pictures.
adb shell settings put global hide_error_dialogs 1 || true
sleep 45
adb install -r -g "$APK"
adb logcat -c

calm() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true; }

open_screen() {
  calm
  adb shell am start -S -W -n "$ACT" --es aravia.demo 1 --es aravia.screen "$1" >/dev/null
  sleep "${2:-8}"
}
shot() { calm; sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; echo "saved $1"; }

for s in today sleep trends train band monitoring diagnostics beta findphone overlay workout rest treadmill session; do
  open_screen "$s"
  shot "$s"
done

# The rest of the Treino tab, below the arara.
open_screen train
adb shell input swipe 540 1800 540 500 400
sleep 2
shot train-2

# The ongoing notification during a rest.
open_screen rest
adb shell cmd statusbar expand-notifications
sleep 3
adb exec-out screencap -p > "$OUT/notification.png"
adb shell cmd statusbar collapse

# The route map needs its tiles from the internet.
open_screen route 20
shot route

# The Story video screen renders a preview frame from map snapshots.
open_screen story 45
shot story

# The whole video, ending included: wait for the app to say how it went.
open_screen video 5
for i in $(seq 1 60); do
  adb logcat -d -s AraviaVideo:* | grep -qE "done|failed" && break
  sleep 5
done
adb logcat -d -s AraviaVideo:* | tee "$OUT/video.txt"
shot video
grep -q "done" "$OUT/video.txt" || { echo "The video was not made." >&2; adb logcat -d > "$OUT/logcat.txt"; exit 1; }

adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  grep -A40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80
  echo "The app crashed." >&2
  exit 1
fi
echo "No crash in the log."

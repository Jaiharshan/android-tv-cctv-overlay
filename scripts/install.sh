#!/usr/bin/env bash
# Build, install and start the floating window on an Android TV over adb.
#
#   TV=192.168.x.y:5555 scripts/install.sh
set -euo pipefail
: "${TV:?set TV=<tv-ip>:5555}"
cd "$(dirname "$0")/.."
./gradlew assembleDebug
adb connect "$TV" >/dev/null
adb -s "$TV" install -r app/build/outputs/apk/debug/app-debug.apk
# allow "display over other apps" (no settings screen needed)
adb -s "$TV" shell appops set com.camfloat.hdmi SYSTEM_ALERT_WINDOW allow
# start the (idle) service, and switch on the remote shortcut
adb -s "$TV" shell am start-foreground-service \
  -n com.camfloat.hdmi/.OverlayService -a com.camfloat.hdmi.IDLE
CUR=$(adb -s "$TV" shell settings get secure enabled_accessibility_services | tr -d '\r')
KEY=com.camfloat.hdmi/com.camfloat.hdmi.KeyService
case "$CUR" in
  null|"") NEW=$KEY ;;
  *"$KEY"*) NEW=$CUR ;;
  *) NEW="$CUR:$KEY" ;;
esac
adb -s "$TV" shell settings put secure enabled_accessibility_services "$NEW"
adb -s "$TV" shell settings put secure accessibility_enabled 1
echo "Installed. Add cameras with scripts/add-camera.sh, then press MENU twice on the remote."

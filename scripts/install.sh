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
adb -s "$TV" shell am start-foreground-service \
  -n com.camfloat.hdmi/.OverlayService -a com.camfloat.hdmi.SHOW
echo "Installed. Now point it at a stream with scripts/set-camera.sh"

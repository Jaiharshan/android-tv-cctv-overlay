#!/usr/bin/env bash
# Point the floating window at a camera / stream.
#
#   TV=192.168.x.y:5555 scripts/set-camera.sh "rtsp://USER:PASS@NVR_IP:554/PATH" "Label"
#
# Pass an empty URL ("") to go back to the HDMI-input mode.
# NOTE: the URL is stored on the TV (app-private storage). It is never written to this repo.
set -euo pipefail
: "${TV:?set TV=<tv-ip>:5555}"
URL="${1-}"
NAME="${2:-Camera}"
adb connect "$TV" >/dev/null
adb -s "$TV" shell am start-foreground-service \
  -n com.camfloat.hdmi/.OverlayService \
  -a com.camfloat.hdmi.SET_URL \
  --es url "$URL" --es name "$NAME" >/dev/null
echo "Window pointed at: $NAME"

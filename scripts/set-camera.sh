#!/usr/bin/env bash
# Replace the camera list with ONE camera (use add-camera.sh to build a list of several).
#
#   TV=192.168.x.y:5555 scripts/set-camera.sh "rtsp://USER:PASS@NVR_IP:554/PATH" "Label"
#
# Pass an empty URL ("") to clear the list; the window then shows the HDMI 1 input.
# NOTE: the URL is stored on the TV (app-private storage). It is never written to this repo.
set -euo pipefail
: "${TV:?set TV=<tv-ip>:5555}"
URL="${1-}"
NAME="${2:-Camera}"
adb connect "$TV" >/dev/null
adb -s "$TV" shell am broadcast \
  -n com.camfloat.hdmi/.ControlReceiver \
  -a com.camfloat.hdmi.SET_URL \
  --es url "$URL" --es name "$NAME" >/dev/null
echo "Window pointed at: $NAME"

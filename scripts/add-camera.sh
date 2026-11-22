#!/usr/bin/env bash
# Add (or replace, by name) one camera in the TV's list. The address stays on the TV only.
#
#   TV=192.168.x.y:5555 scripts/add-camera.sh "Front gate" "rtsp://USER:PASS@IP:554/PATH"
#   TV=192.168.x.y:5555 scripts/add-camera.sh "Console" "hdmi://1"
#   TV=192.168.x.y:5555 scripts/add-camera.sh --clear
set -euo pipefail
: "${TV:?set TV=<tv-ip>:5555}"
SVC="am start-foreground-service -n com.camfloat.hdmi/.OverlayService -a com.camfloat.hdmi"
if [ "${1:-}" = "--clear" ]; then
  adb -s "$TV" shell "$SVC.CLEAR_CAMERAS" >/dev/null
  echo "Camera list cleared."
  exit 0
fi
NAME="${1:?camera name}"
URL="${2:?camera url (rtsp://... or hdmi://N)}"
adb -s "$TV" shell "$SVC.ADD_CAMERA --es name '$NAME' --es url '$URL'" >/dev/null
echo "Added \"$NAME\"."

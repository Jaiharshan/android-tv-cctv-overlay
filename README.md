# TV Floating Camera

A small, rounded **picture-in-picture camera window for Android TV**. It floats above whatever is on screen (YouTube, a film, the home screen) so you can keep an eye on a CCTV camera, baby cam, doorbell or any other live video while you watch something else.

![Overview](docs/overview.png)

- **Opens only when you want it**: press MENU twice on the TV remote. Nothing runs or decodes while it is closed
- **Several cameras, four sizes, one remote**: switch camera, resize, or open a full-screen view of all cameras, all with the normal TV remote
- Always on top, always muted, never steals focus from the app you are using
- Ready again after the TV boots, and reconnects if the Wi-Fi or camera drops
- Plays any **RTSP** stream (CCTV cameras, NVRs, IP cameras), or an **HDMI input** of the TV
- Rounded corners with a slim glass frame; a calm, see-through "frosted glass" card when there is no picture
- No cloud, no account, no servers: the video goes straight from your camera or recorder to your TV

## What is it good for?

| Use | How |
| --- | --- |
| Watch the front gate while you watch a film | Point it at the gate camera's RTSP stream |
| Keep a baby or pet camera in the corner | Any camera that speaks RTSP |
| See a doorbell or driveway camera on the TV | Same |
| Show an HDMI source (console, capture box, a recorder's HDMI out) | HDMI input mode, see "Limitations" |

## How it works

```
 camera ──► recorder (NVR) ──► your home network ──► TV app ──► floating window
                                  (Wi-Fi / LAN)       (this repo)
```

1. The app asks for one video stream over **RTSP** (TCP) using [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer).
2. The picture is decoded **in software inside the app** and drawn on a `TextureView` clipped to a rounded outline.
3. That view lives in a `TYPE_APPLICATION_OVERLAY` window, a special layer Android draws above every app, so other apps never know it is there.
4. A custom `GlassView` paints the frame, and the frosted "No signal / Connecting / Camera offline" card.
5. A boot receiver starts a tiny idle service after a restart. It shows nothing until you press MENU twice.

Because the camera is decoded by the app (not by the TV's single hardware video plane), it can run **at the same time** as YouTube or a film.

## Requirements

- An Android TV / Google TV device (developed on Android 9). Minimum API 26.
- `adb` access to the TV (Developer options → network debugging)
- JDK 17 and the Android SDK (platform 34) to build
- A camera or recorder with an **H.264 RTSP** stream on your local network

## Quick start

```bash
git clone https://github.com/Jaiharshan/tv-floating-camera.git
cd tv-floating-camera

# 1. build, install, grant the overlay permission, start
TV=<tv-ip>:5555 scripts/install.sh

# 2. add your cameras (the URLs stay on the TV, never in this repo)
TV=<tv-ip>:5555 scripts/add-camera.sh "Front gate" "rtsp://USER:PASS@CAMERA_OR_NVR_IP:554/PATH"
TV=<tv-ip>:5555 scripts/add-camera.sh "Driveway"   "rtsp://USER:PASS@CAMERA_OR_NVR_IP:554/PATH2"

# 3. on the TV remote: press MENU twice quickly
```

`install.sh` also switches on the small accessibility service that listens for the double MENU press.
If you skip that, you can still open the window from the app icon ("CCTV Window") on the TV's home screen.

Tip: use a **separate view-only account** for the TV, not your admin login.

### Finding your RTSP address

It differs by brand. Check your device's manual. A few commonly documented forms (replace the placeholders):

| Device type | Typical address |
| --- | --- |
| TP-Link VIGI / Tapo camera | `rtsp://USER:PASS@CAM_IP:554/stream1` (main) or `/stream2` (sub) |
| TP-Link VIGI NVR | `rtsp://USER:PASS@NVR_IP:554/live/CHANNEL/STREAM/avm` (STREAM `1` main, `2` sub) |
| Many other IP cameras | `rtsp://USER:PASS@IP:554/` plus a brand-specific path |

Use the **sub-stream** (lower resolution) for a small window. It is lighter on Wi-Fi.

## Controls (TV remote)

Press **MENU twice quickly** to open. The window shows its key help while it listens to the remote.

| Where | Key | What it does |
| --- | --- | --- |
| Small window | Left / Right | previous / next camera |
| | Up / Down | bigger / smaller (4 sizes, remembered) |
| | OK | full-screen view of all cameras |
| | MENU (once) | move the window with the arrows, OK when placed |
| | MENU (twice) | close the window completely |
| | BACK | hand the remote back to the app below (window stays) |
| All cameras (grid) | arrows | choose a camera (amber outline) |
| | OK | open that camera full screen |
| | BACK | back to the small window |
| One camera, full screen | Left / Right | next camera |
| | OK or BACK | back to the grid |

The small window gives the remote back by itself after about 15 seconds without a key, so YouTube and everything else behave normally again. Press MENU twice again to take control.

## Controls (adb, for scripts and testing)

```bash
SVC="am start-foreground-service -n com.camfloat.hdmi/.OverlayService -a com.camfloat.hdmi"
adb -s $TV shell "$SVC.OPEN"            # open and take the remote
adb -s $TV shell "$SVC.SHOW"            # open, leave the remote alone
adb -s $TV shell "$SVC.HIDE"            # close
adb -s $TV shell "$SVC.ADD_CAMERA --es name 'Gate' --es url 'rtsp://...'"
adb -s $TV shell "$SVC.CLEAR_CAMERAS"
adb -s $TV shell "$SVC.MOVE_TO --ei x 1400 --ei y 40"
adb -s $TV shell "$SVC.KEY --ei key 22"  # press an Android KEYCODE inside the window (22 = right)
```

An `hdmi://1` address (or no camera at all) shows the TV's HDMI input instead of a network camera.

### If double MENU does nothing on your remote

Remotes differ in which button sends the Android MENU key. To see what your remote sends:

```bash
adb -s $TV shell "$SVC.DEBUG_KEYS --ez on true"
adb -s $TV logcat -s CamFloat      # press the button, read "key code N"
adb -s $TV shell "$SVC.DEBUG_KEYS --ez on false"
```

MENU is code `82`. If your button sends another code, change `KEYCODE_MENU` in `KeyService.java`.

## Limitations and known issues

- **H.264 only.** Media3 cannot start many H.265 (HEVC) RTSP streams that lack `sprop-vps` in the stream description. Set the camera's sub-stream to H.264.
- **Passwords containing `@`** break URL parsing in Media3's RTSP client. Use a password without `@`.
- **SRTP must be off** on the recorder, otherwise third-party players cannot read the stream.
- **HDMI input mode** uses the TV Input Framework. It was only tested with no signal connected. On TVs with one hardware video plane (e.g. Amlogic) it may clash with other video, which is why RTSP is the recommended mode.
- Debug-signed build. Sideload only.
- **Decoder budget.** Every camera in the grid needs its own video decoder. Five 640x480 H.264 sub-streams played together with YouTube on an Amlogic Android 9 TV, but the TV's CPU was busy while the grid was open. Use sub-streams, and expect fewer on weaker boxes.
- Adb `input keyevent` is not delivered to accessibility key filters. To test the double-MENU shortcut without a remote, send key events to the remote's own input device (`sendevent`).
- The double-MENU shortcut was not yet confirmed with the physical Mi remote's MENU button (see the debug steps above).

## Privacy and security

- The stream URL (with its login) is saved only in the app's private storage **on your TV**. It is never in this repository, never logged, and not sent anywhere except to your own camera or recorder.
- Prefer a dedicated **view-only** account for the TV.
- The app needs: draw over other apps, internet (local network), foreground service, boot-completed. The accessibility service is only for the remote shortcut: Android shows it every key press, it reacts to MENU (and to keys while the window is open), passes everything else through untouched, and logs nothing unless you switch on `DEBUG_KEYS`.
- Keep your recorder off the internet: UPnP off, no port forwarding, current firmware.

## Project layout

```
app/src/main/java/com/camfloat/hdmi/
  OverlayService.java   window modes (small / move / grid / full screen), camera list, remote-key logic
  Tile.java             one camera view: RTSP or HDMI video under a glass frame
  Cam.java              a camera (name + address) and how the list is stored
  GlassView.java        rounded glass frame, camera label, key-help pill, status card
  KeyService.java       accessibility service: double-MENU opens, keys go to the window while open
  BootReceiver.java     starts the idle service at boot (nothing is shown)
  MainActivity.java     launcher entry: opens the window
scripts/                install, add-camera, set-camera helpers
docs/overview.png       illustrated overview
```

## Contributing

Issues and pull requests are welcome. Ideas: a settings screen on the TV, paging for more than six cameras, H.265 support, a configurable shortcut key.

## License

[MIT](LICENSE)

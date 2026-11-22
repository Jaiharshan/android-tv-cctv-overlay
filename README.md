# TV Floating Camera

A small, rounded **picture-in-picture camera window for Android TV**. It floats above whatever is on screen (YouTube, a film, the home screen) so you can keep an eye on a CCTV camera, baby cam, doorbell or any other live video while you watch something else.

![Overview](docs/overview.png)

- Always on top, always muted, never steals the remote's focus
- Starts by itself when the TV boots, and reconnects if the Wi-Fi or camera drops
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
5. A boot receiver and a foreground service bring it back after a restart.

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

# 2. point it at your camera (the URL stays on the TV, never in this repo)
TV=<tv-ip>:5555 scripts/set-camera.sh "rtsp://USER:PASS@CAMERA_OR_NVR_IP:554/PATH" "Front gate"
```

Tip: use a **separate view-only account** for the TV, not your admin login.

### Finding your RTSP address

It differs by brand. Check your device's manual. A few commonly documented forms (replace the placeholders):

| Device type | Typical address |
| --- | --- |
| TP-Link VIGI / Tapo camera | `rtsp://USER:PASS@CAM_IP:554/stream1` (main) or `/stream2` (sub) |
| TP-Link VIGI NVR | `rtsp://USER:PASS@NVR_IP:554/live/CHANNEL/STREAM/avm` (STREAM `1` main, `2` sub) |
| Many other IP cameras | `rtsp://USER:PASS@IP:554/` plus a brand-specific path |

Use the **sub-stream** (lower resolution) for a small window. It is lighter on Wi-Fi.

## Controls

Everything can be driven with `adb` intents:

| Action | Command (after `adb -s $TV shell am start-foreground-service -n com.camfloat.hdmi/.OverlayService`) |
| --- | --- |
| Show | `-a com.camfloat.hdmi.SHOW` |
| Hide | `-a com.camfloat.hdmi.HIDE` |
| Toggle | `-a com.camfloat.hdmi.TOGGLE` |
| Set stream | `-a com.camfloat.hdmi.SET_URL --es url "rtsp://..." --es name "Label"` |
| Move | `-a com.camfloat.hdmi.MOVE_TO --ei x 1400 --ei y 40` |
| HDMI mode, port N | `-a com.camfloat.hdmi.SET_PORT --ei port N` (clear the URL first) |

The window is 360 × 240. Change `W`/`H`/`RADIUS`/`FRAME` in `OverlayService.java` for another size.

### Remote-control shortcut (experimental)

`KeyService` is an accessibility service: **double-press MENU** to enter move mode, arrows to move, OK to hide, BACK to finish. It is implemented but **not verified on a real TV yet**, and you need to enable the service in Accessibility settings.

## Limitations and known issues

- **H.264 only.** Media3 cannot start many H.265 (HEVC) RTSP streams that lack `sprop-vps` in the stream description. Set the camera's sub-stream to H.264.
- **Passwords containing `@`** break URL parsing in Media3's RTSP client. Use a password without `@`.
- **SRTP must be off** on the recorder, otherwise third-party players cannot read the stream.
- **HDMI input mode** uses the TV Input Framework. It was only tested with no signal connected. On TVs with one hardware video plane (e.g. Amlogic) it may clash with other video, which is why RTSP is the recommended mode.
- Debug-signed build. Sideload only.
- One window, one stream at a time.

## Privacy and security

- The stream URL (with its login) is saved only in the app's private storage **on your TV**. It is never in this repository, never logged, and not sent anywhere except to your own camera or recorder.
- Prefer a dedicated **view-only** account for the TV.
- The app needs: draw over other apps, internet (local network), foreground service, boot-completed. The optional accessibility service is only for the remote shortcut.
- Keep your recorder off the internet: UPnP off, no port forwarding, current firmware.

## Project layout

```
app/src/main/java/com/camfloat/hdmi/
  OverlayService.java   window, RTSP player, HDMI input, position, state
  GlassView.java        rounded glass frame + frosted status card
  KeyService.java       optional remote-control move shortcut
  BootReceiver.java     start at boot
  MainActivity.java     launcher entry (asks for overlay permission)
scripts/                install + set-camera helpers
docs/overview.png       illustrated overview
```

## Contributing

Issues and pull requests are welcome. Ideas: cycle between several cameras with the remote, a small settings screen, multi-camera grid, H.265 support.

## License

[MIT](LICENSE)

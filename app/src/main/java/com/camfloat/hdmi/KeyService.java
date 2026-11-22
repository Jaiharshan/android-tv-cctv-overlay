package com.camfloat.hdmi;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remote-control entry point for the CCTV window.
 *
 *  - Press MENU twice quickly while the window is closed (or not using the remote): it opens and
 *    takes the remote. All further keys are handled by OverlayService.onKey().
 *  - While the window owns the remote every key is swallowed; otherwise every key passes through
 *    to the foreground app untouched, except the second MENU press of the open combo.
 */
public class KeyService extends AccessibilityService {
    private static final long DOUBLE_MS = 450;

    private long lastMenuDown = 0;
    private boolean swallowMenuUp = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // not used
    }

    @Override
    protected void onServiceConnected() {
        Log.i("CamFloat", "key service connected");
    }

    @Override
    public void onInterrupt() {
        // nothing to clean up
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        OverlayService svc = OverlayService.instance;
        if (OverlayService.debugKeys && down) Log.i("CamFloat", "key code " + key);

        if (svc != null && svc.capturing()) {
            if (down && e.getRepeatCount() == 0) svc.onKey(key);
            return true;   // the window owns the remote: swallow down, up and repeats
        }

        if (key != KeyEvent.KEYCODE_MENU) return false;
        if (!down) {
            if (swallowMenuUp) {
                swallowMenuUp = false;
                return true;
            }
            return false;
        }
        if (e.getRepeatCount() != 0) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastMenuDown < DOUBLE_MS) {
            lastMenuDown = 0;
            swallowMenuUp = true;
            if (svc != null) {
                svc.open(true);
            } else {
                startForegroundService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_OPEN));
            }
            return true;
        }
        lastMenuDown = now;
        return false;   // the first press goes to the foreground app as normal
    }
}

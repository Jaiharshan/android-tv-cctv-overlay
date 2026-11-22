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
 *  - Press MENU (or the Mi remote's "mi" button) twice quickly while the window is closed (or not using the remote): it opens and
 *    takes the remote. All further keys are handled by OverlayService.onKey().
 *  - While the window owns the remote every key is swallowed; otherwise every key passes through
 *    to the foreground app untouched, except the second MENU press of the open combo.
 */
public class KeyService extends AccessibilityService {
    private static final int MI_BUTTON = 189;
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
        try {
            return handle(e);
        } catch (RuntimeException ex) {   // never let a bug here break the TV's remote
            Log.w("CamFloat", "key handling failed: " + ex);
            return false;
        }
    }

    private static boolean ours(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    private boolean handle(KeyEvent e) {
        int key = e.getKeyCode();
        if (key == MI_BUTTON) key = KeyEvent.KEYCODE_MENU;   // the "mi" button of the Mi remote sends code 189
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        OverlayService svc = OverlayService.instance;
        if (OverlayService.debugKeys && down) Log.i("CamFloat", "key code " + e.getKeyCode());

        if (svc != null && svc.capturing()) {
            if (key == KeyEvent.KEYCODE_HOME) {   // HOME, volume, power etc. always reach the TV
                if (down) svc.releaseToSmall();
                return false;
            }
            if (!ours(key)) return false;
            if (down && (e.getRepeatCount() == 0 || svc.allowsRepeat())) svc.onKey(key);
            return true;   // our keys are swallowed (down, up and repeats)
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

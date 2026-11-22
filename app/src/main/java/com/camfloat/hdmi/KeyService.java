package com.camfloat.hdmi;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remote-control handling for the floating window.
 *
 *  - Press MENU twice quickly: enter "move mode" (shows the window first if hidden).
 *  - In move mode: arrow keys move the window, OK hides it, BACK or MENU finishes.
 *  - Move mode ends by itself after 15 s without a key.
 * Outside move mode every key passes through untouched, except the second MENU press of the combo.
 */
public class KeyService extends AccessibilityService {
    private static final int STEP = 60;                 // pixels per arrow press
    private static final long DOUBLE_MS = 450;
    private static final long MOVE_TIMEOUT_MS = 15000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastMenuDown = 0;
    private boolean swallowMenuUp = false;
    private boolean move = false;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            endMove();
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // not used
    }

    @Override
    public void onInterrupt() {
        endMove();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        OverlayService svc = OverlayService.instance;

        if (!move) {
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
                beginMove();
                return true;
            }
            lastMenuDown = now;
            return false;   // first press goes to the foreground app as normal
        }

        // ---- move mode: we own the keys ----
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, MOVE_TIMEOUT_MS);
        if (svc == null) {
            endMove();
            return false;
        }
        if (down) {
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    svc.moveBy(-STEP, 0);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    svc.moveBy(STEP, 0);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    svc.moveBy(0, -STEP);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    svc.moveBy(0, STEP);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    endMove();
                    svc.hide();
                    break;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_MENU:
                    endMove();
                    break;
                default:
                    break;
            }
        }
        return true;   // swallow everything while moving (down and up)
    }

    private void beginMove() {
        OverlayService svc = OverlayService.instance;
        if (svc == null) {
            startForegroundService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_SHOW));
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    OverlayService s = OverlayService.instance;
                    if (s != null) {
                        s.setMoving(true);
                        move = true;
                    }
                }
            }, 600);
        } else {
            if (!svc.isShown()) svc.show();
            svc.setMoving(true);
            move = true;
        }
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, MOVE_TIMEOUT_MS);
    }

    private void endMove() {
        handler.removeCallbacks(timeout);
        move = false;
        OverlayService svc = OverlayService.instance;
        if (svc != null) svc.setMoving(false);
    }
}

package com.camfloat.hdmi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The CCTV window. It does nothing (no window, no video) until it is opened, then:
 *
 *   PIP    small rounded window, 4 sizes, cycles through the cameras
 *   MOVE   same window, arrow keys slide it around the screen
 *   GRID   full screen, every camera at once
 *   SINGLE full screen, one camera
 *
 * It is driven by the remote through KeyService, or by adb with intent actions (see ACTION_*).
 */
public class OverlayService extends Service {
    private static final String TAG = "CamFloat";
    static volatile OverlayService instance;
    /** When true KeyService logs the Android key code of every press (to find a remote's Menu button). Off by default. */
    static volatile boolean debugKeys;

    static final String ACTION_OPEN = "com.camfloat.hdmi.OPEN";             // show + take the remote
    static final String ACTION_IDLE = "com.camfloat.hdmi.IDLE";             // keep the service alive, show nothing
    static final String ACTION_SHOW = "com.camfloat.hdmi.SHOW";             // show, leave the remote alone
    static final String ACTION_HIDE = "com.camfloat.hdmi.HIDE";
    static final String ACTION_TOGGLE = "com.camfloat.hdmi.TOGGLE";
    static final String ACTION_SET_URL = "com.camfloat.hdmi.SET_URL";       // --es url ... [--es name ...]  (one camera)
    static final String ACTION_ADD_CAMERA = "com.camfloat.hdmi.ADD_CAMERA"; // --es name ... --es url ...
    static final String ACTION_CLEAR_CAMERAS = "com.camfloat.hdmi.CLEAR_CAMERAS";
    static final String ACTION_MOVE_TO = "com.camfloat.hdmi.MOVE_TO";       // --ei x N --ei y N
    static final String ACTION_DEBUG_KEYS = "com.camfloat.hdmi.DEBUG_KEYS"; // --ez on true|false
    static final String ACTION_KEY = "com.camfloat.hdmi.KEY";               // --ei key KEYCODE (testing)

    private static final String CHANNEL = "camfloat";
    private static final int[][] SIZES = {{360, 240}, {480, 320}, {640, 427}, {800, 533}};
    private static final int RADIUS = 22;
    private static final int FRAME = 6;
    private static final int STEP = 60;                    // pixels per arrow press while moving
    private static final long DOUBLE_MS = 400;              // double-MENU window
    private static final long CONTROL_TIMEOUT_MS = 15000;   // small window gives the remote back
    private static final long FULL_TIMEOUT_MS = 90000;      // full-screen views fall back to the small window

    static final int PIP = 0, MOVE = 1, GRID = 2, SINGLE = 3;

    private WindowManager wm;
    private SharedPreferences sp;
    private final Handler h = new Handler(Looper.getMainLooper());

    private FrameLayout box;
    private WindowManager.LayoutParams lp;
    private final List<Tile> tiles = new ArrayList<>();
    private List<Cam> cams = new ArrayList<>();
    private int mode = PIP;
    private boolean control;          // this service owns the remote keys
    private boolean menuPending;
    private int cur, sel, sizeIdx;
    private int px, py;               // small-window position
    private int screenW = 1920, screenH = 1080;

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            if (mode != PIP) {
                mode = PIP;
                build();
            }
            control = false;
            refreshHints();
        }
    };
    private final Runnable menuSingle = new Runnable() {
        @Override
        public void run() {
            menuPending = false;
            if (box != null && mode == PIP && control) {
                mode = MOVE;
                refreshHints();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "CCTV window", NotificationManager.IMPORTANCE_MIN));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, new Notification.Builder(this, CHANNEL)
                .setContentTitle("CCTV window ready")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build());
        String a = intent == null ? null : intent.getAction();
        // a null/unknown action only keeps the (idle) service alive: nothing is shown or played
        if (ACTION_OPEN.equals(a)) {
            open(true);
        } else if (ACTION_SHOW.equals(a)) {
            open(false);
        } else if (ACTION_HIDE.equals(a)) {
            hide();
        } else if (ACTION_TOGGLE.equals(a)) {
            if (box == null) open(true); else hide();
        } else if (ACTION_SET_URL.equals(a)) {
            List<Cam> one = new ArrayList<>();
            String u = intent.getStringExtra("url");
            if (u != null && !u.isEmpty()) one.add(new Cam(intent.getStringExtra("name"), u));
            saveCams(one);
            cur = 0;
            rebuildIfShown();
        } else if (ACTION_ADD_CAMERA.equals(a)) {
            String u = intent.getStringExtra("url");
            if (u != null && !u.isEmpty()) {
                List<Cam> l = loadCams();
                Cam c = new Cam(intent.getStringExtra("name"), u);
                for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).name.equals(c.name)) l.remove(i);
                l.add(c);
                saveCams(l);
                rebuildIfShown();
            }
        } else if (ACTION_CLEAR_CAMERAS.equals(a)) {
            saveCams(new ArrayList<Cam>());
            cur = 0;
            rebuildIfShown();
        } else if (ACTION_MOVE_TO.equals(a)) {
            if (box == null) open(false);
            if (box != null && mode != GRID && mode != SINGLE) {
                px = intent.getIntExtra("x", px);
                py = intent.getIntExtra("y", py);
                lp.x = px;
                lp.y = py;
                applyPosition();
            }
        } else if (ACTION_DEBUG_KEYS.equals(a)) {
            debugKeys = intent.getBooleanExtra("on", false);
        } else if (ACTION_KEY.equals(a)) {
            onKey(intent.getIntExtra("key", 0));
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        hide();
        instance = null;
        super.onDestroy();
    }

    // ---- cameras --------------------------------------------------------------------------------

    private List<Cam> loadCams() {
        List<Cam> l = Cam.parse(sp.getString("cams", null), "\n");
        if (l.isEmpty()) {   // settings from the single-camera version
            String u = sp.getString("url", null);
            if (u != null) l.add(new Cam(sp.getString("name", "Camera"), u));
        }
        return l;
    }

    private void saveCams(List<Cam> l) {
        sp.edit().putString("cams", Cam.serialize(l)).remove("url").remove("name").apply();
    }

    private void rebuildIfShown() {
        if (box != null) {
            cams = loadCams();
            if (cams.isEmpty()) cams.add(new Cam("HDMI 1", "hdmi://1"));
            cur = Math.min(cur, cams.size() - 1);
            build();
        }
    }

    // ---- window ---------------------------------------------------------------------------------

    boolean isShown() {
        return box != null;
    }

    /** True while the remote's keys belong to this window (KeyService then swallows them). */
    boolean capturing() {
        return box != null && (control || mode != PIP);
    }

    void open(boolean takeRemote) {
        if (box == null) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "CCTV window: allow \"display over other apps\"", Toast.LENGTH_LONG).show();
                Log.w(TAG, "overlay permission missing");
                return;
            }
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
            cams = loadCams();
            if (cams.isEmpty()) cams.add(new Cam("HDMI 1", "hdmi://1"));   // nothing set up: show the HDMI input
            cur = Math.max(0, Math.min(sp.getInt("cur", 0), cams.size() - 1));
            sizeIdx = Math.max(0, Math.min(sp.getInt("size", 0), SIZES.length - 1));
            px = sp.getInt("x", screenW - SIZES[sizeIdx][0] - 48);
            py = sp.getInt("y", 40);
            mode = PIP;
            box = new FrameLayout(this);
            box.setBackgroundColor(Color.TRANSPARENT);
            lp = new WindowManager.LayoutParams(
                    SIZES[sizeIdx][0], SIZES[sizeIdx][1],
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = px;
            lp.y = py;
            clampPip();
            wm.addView(box, lp);
            build();
        }
        control = takeRemote;
        if (takeRemote) arm(CONTROL_TIMEOUT_MS);
        refreshHints();
    }

    void hide() {
        h.removeCallbacks(timeout);
        h.removeCallbacks(menuSingle);
        menuPending = false;
        control = false;
        for (Tile t : tiles) t.stop();
        tiles.clear();
        if (box != null) {
            try {
                wm.removeView(box);
            } catch (Exception e) {
                Log.w(TAG, "hide: " + e);
            }
            box = null;
        }
    }

    /** (Re)creates the tiles for the current mode. */
    private void build() {
        if (box == null) return;
        for (Tile t : tiles) t.stop();
        tiles.clear();
        box.removeAllViews();

        boolean full = mode == GRID || mode == SINGLE;
        if (full) {
            lp.x = 0;
            lp.y = 0;
            lp.width = screenW;
            lp.height = screenH;
            box.setBackgroundColor(0xF7000000);
        } else {
            lp.width = SIZES[sizeIdx][0];
            lp.height = SIZES[sizeIdx][1];
            lp.x = px;
            lp.y = py;
            clampPip();
            box.setBackgroundColor(Color.TRANSPARENT);
        }
        wm.updateViewLayout(box, lp);

        if (mode == GRID) {
            int n = cams.size();
            int cols = gridCols(n);
            int rows = (n + cols - 1) / cols;
            int gap = 24;
            int cw = (screenW - gap * (cols + 1)) / cols;
            int ch = (screenH - gap * (rows + 1)) / rows;
            // keep a 4:3 shape and centre the whole grid
            if (cw * 3 > ch * 4) cw = ch * 4 / 3; else ch = cw * 3 / 4;
            int left = (screenW - (cw * cols + gap * (cols - 1))) / 2;
            int top = (screenH - (ch * rows + gap * (rows - 1))) / 2;
            for (int i = 0; i < n; i++) {
                Tile t = new Tile(this, cams.get(i), RADIUS, FRAME);
                FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(cw, ch);
                p.leftMargin = left + (i % cols) * (cw + gap);
                p.topMargin = top + (i / cols) * (ch + gap);
                box.addView(t, p);
                tiles.add(t);
            }
        } else {
            Tile t = new Tile(this, cams.get(cur), RADIUS, FRAME);
            box.addView(t, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            tiles.add(t);
        }
        for (Tile t : tiles) t.start();
        refreshHints();
    }

    private static int gridCols(int n) {
        if (n <= 1) return 1;
        if (n <= 4) return 2;
        if (n <= 6) return 3;
        return 3;
    }

    private void refreshHints() {
        for (int i = 0; i < tiles.size(); i++) {
            GlassView g = tiles.get(i).glass();
            String hint = null;
            boolean accent = false;
            if (mode == MOVE) {
                hint = "Arrows move  •  OK done";
                accent = true;
            } else if (mode == PIP && control) {
                hint = "Left/Right camera  •  Up/Down size  •  OK all  •  Back done";
            } else if (mode == GRID) {
                accent = i == sel;
                if (accent) hint = "Arrows choose  •  OK open  •  Back small window";
            } else if (mode == SINGLE) {
                hint = "Left/Right camera  •  OK or Back: all cameras";
            }
            g.setHint(hint);
            g.setAccent(accent);
        }
    }

    // ---- remote keys ----------------------------------------------------------------------------

    /** Called by KeyService for each key press while capturing(), and by ACTION_KEY. */
    void onKey(int key) {
        if (!capturing()) return;
        switch (mode) {
            case PIP:
                pipKey(key);
                break;
            case MOVE:
                moveKey(key);
                break;
            case GRID:
                gridKey(key);
                break;
            case SINGLE:
                singleKey(key);
                break;
            default:
                break;
        }
        // re-arm after the key was handled: the new mode decides how long the remote stays ours
        if (capturing()) arm(mode == PIP || mode == MOVE ? CONTROL_TIMEOUT_MS : FULL_TIMEOUT_MS);
    }

    private void arm(long ms) {
        h.removeCallbacks(timeout);
        h.postDelayed(timeout, ms);
    }

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
    }

    private void pipKey(int key) {
        int n = cams.size();
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                cur = (cur - 1 + n) % n;
                sp.edit().putInt("cur", cur).apply();
                build();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                cur = (cur + 1) % n;
                sp.edit().putInt("cur", cur).apply();
                build();
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                resize(Math.min(SIZES.length - 1, sizeIdx + 1));
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                resize(Math.max(0, sizeIdx - 1));
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                mode = GRID;
                sel = cur;
                build();
                break;
            case KeyEvent.KEYCODE_BACK:
                control = false;
                h.removeCallbacks(timeout);
                refreshHints();
                break;
            case KeyEvent.KEYCODE_MENU:
                // one press = move the window, two quick presses = close it
                if (menuPending) {
                    h.removeCallbacks(menuSingle);
                    menuPending = false;
                    hide();
                } else {
                    menuPending = true;
                    h.postDelayed(menuSingle, DOUBLE_MS);
                }
                break;
            default:
                break;
        }
    }

    private void moveKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveBy(-STEP, 0);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveBy(STEP, 0);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveBy(0, -STEP);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveBy(0, STEP);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                mode = PIP;
                sp.edit().putInt("x", px).putInt("y", py).apply();
                refreshHints();
                break;
            default:
                break;
        }
    }

    private void gridKey(int key) {
        int n = cams.size();
        int cols = gridCols(n);
        int old = sel;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (sel % cols > 0) sel--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (sel % cols < cols - 1 && sel + 1 < n) sel++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (sel - cols >= 0) sel -= cols;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (sel + cols < n) sel += cols;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                cur = sel;
                mode = SINGLE;
                build();
                return;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                cur = sel;
                sp.edit().putInt("cur", cur).apply();
                mode = PIP;
                build();
                return;
            default:
                break;
        }
        if (old != sel) refreshHints();
    }

    private void singleKey(int key) {
        int n = cams.size();
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                cur = (cur - 1 + n) % n;
                build();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                cur = (cur + 1) % n;
                build();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                sel = cur;
                mode = GRID;
                build();
                break;
            case KeyEvent.KEYCODE_MENU:
                sp.edit().putInt("cur", cur).apply();
                mode = PIP;
                build();
                break;
            default:
                break;
        }
    }

    // ---- size and position of the small window ---------------------------------------------------

    private void resize(int idx) {
        if (idx == sizeIdx || box == null) return;
        int oldW = lp.width;
        sizeIdx = idx;
        lp.width = SIZES[idx][0];
        lp.height = SIZES[idx][1];
        // grow/shrink around the screen edge the window is nearest to
        if (lp.x + oldW / 2 > screenW / 2) lp.x += oldW - lp.width;
        px = lp.x;
        py = lp.y;
        applyPosition();
        sp.edit().putInt("size", sizeIdx).apply();
    }

    private void moveBy(int dx, int dy) {
        if (box == null) return;
        lp.x += dx;
        lp.y += dy;
        applyPosition();
    }

    private void applyPosition() {
        clampPip();
        px = lp.x;
        py = lp.y;
        wm.updateViewLayout(box, lp);
        sp.edit().putInt("x", px).putInt("y", py).apply();
    }

    private void clampPip() {
        lp.x = Math.max(0, Math.min(screenW - lp.width, lp.x));
        lp.y = Math.max(0, Math.min(screenH - lp.height, lp.y));
        px = lp.x;
        py = lp.y;
    }
}

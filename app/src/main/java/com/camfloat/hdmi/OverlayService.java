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
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.IBinder;
import android.provider.Settings;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Always-on floating window (720x480) showing an HDMI input via the TV Input Framework.
 * Rounded glass look is drawn by GlassView above the (rectangular) video surface.
 * Controlled by the remote through KeyService, or by adb with intent actions (see ACTION_*).
 */
public class OverlayService extends Service {
    private static final String TAG = "CamFloat";
    static volatile OverlayService instance;

    static final String ACTION_SHOW = "com.camfloat.hdmi.SHOW";
    static final String ACTION_HIDE = "com.camfloat.hdmi.HIDE";
    static final String ACTION_TOGGLE = "com.camfloat.hdmi.TOGGLE";
    static final String ACTION_SET_PORT = "com.camfloat.hdmi.SET_PORT";   // --ei port 1..3
    static final String ACTION_SET_URL = "com.camfloat.hdmi.SET_URL";      // --es url rtsp://... [--es name Label]
    static final String ACTION_MOVE_TO = "com.camfloat.hdmi.MOVE_TO";      // --ei x N --ei y N

    private static final String CHANNEL = "camfloat";
    static final int W = 360, H = 240;
    private static final int RADIUS = 22;   // outer corner radius
    private static final int FRAME = 6;    // frame thickness around live video

    private WindowManager wm;
    private SharedPreferences sp;
    private FrameLayout box;
    private TvView tv;
    private GlassView glass;
    private WindowManager.LayoutParams lp;
    private int screenW = 1920, screenH = 1080;
    private boolean moving = false;
    private boolean live = false;
    private TextureView tex;
    private ExoPlayer player;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (player != null) player.prepare();
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
                .setContentTitle("CCTV window running")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build());
        String a = intent == null ? null : intent.getAction();
        if (a == null || ACTION_SHOW.equals(a)) {
            show();
        } else if (ACTION_HIDE.equals(a)) {
            hide();
        } else if (ACTION_TOGGLE.equals(a)) {
            if (box == null) show(); else hide();
        } else if (ACTION_SET_PORT.equals(a)) {
            sp.edit().putInt("port", intent.getIntExtra("port", 1)).apply();
            if (box == null) show(); else tune();
        } else if (ACTION_SET_URL.equals(a)) {
            SharedPreferences.Editor ed = sp.edit();
            String u = intent.getStringExtra("url");
            if (u == null || u.isEmpty()) ed.remove("url"); else ed.putString("url", u);
            if (intent.hasExtra("name")) ed.putString("name", intent.getStringExtra("name"));
            ed.apply();
            hide();
            show();
        } else if (ACTION_MOVE_TO.equals(a)) {
            if (box == null) show();
            if (lp != null) {
                lp.x = intent.getIntExtra("x", lp.x);
                lp.y = intent.getIntExtra("y", lp.y);
                applyPosition();
            }
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

    // ---- window ---------------------------------------------------------------------------

    boolean isShown() {
        return box != null;
    }

    void show() {
        if (box != null) return;
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "CCTV window: allow \"display over other apps\"", Toast.LENGTH_LONG).show();
            Log.w(TAG, "overlay permission missing");
            return;
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        live = false;
        box = new FrameLayout(this);
        box.setBackgroundColor(Color.TRANSPARENT);

        final String url = sp.getString("url", null);
        if (url != null) {
            tex = new TextureView(this);
            tex.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), RADIUS - FRAME);
                }
            });
            tex.setClipToOutline(true);
            tex.setAlpha(0f);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(W - 2 * FRAME, H - 2 * FRAME);
            tp.setMargins(FRAME, FRAME, FRAME, FRAME);
            box.addView(tex, tp);
        } else {
            tv = new TvView(this);
            tv.setZOrderMediaOverlay(true);   // video above the window, below the glass frame
            tv.setCallback(new TvView.TvInputCallback() {
                @Override
                public void onVideoAvailable(String inputId) {
                    Log.i(TAG, "video available " + inputId);
                    setLive(true);
                }

                @Override
                public void onVideoUnavailable(String inputId, int reason) {
                    Log.i(TAG, "video unavailable reason=" + reason);
                    setLive(false);
                    if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
                            || reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING) {
                        glass.setTexts("Connecting…", "HDMI " + port());
                    } else {
                        glass.setTexts("No signal", "HDMI " + port());
                    }
                }

                @Override
                public void onConnectionFailed(String inputId) {
                    Log.i(TAG, "connection failed");
                    setLive(false);
                    glass.setTexts("Can't open input", "HDMI " + port());
                }
            });
            // starts tiny so the translucent no-signal card shows; grows once video is live
            box.addView(tv, new FrameLayout.LayoutParams(1, 1));
        }

        glass = new GlassView(this, RADIUS, FRAME);
        glass.setTexts("Connecting…", url != null ? sp.getString("name", "Camera") : "HDMI " + port());
        box.addView(glass, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        lp = new WindowManager.LayoutParams(
                W, H,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = sp.getInt("x", screenW - lp.width - 48);   // right side
        lp.y = sp.getInt("y", (int) (screenH * 0.24));    // above the dock
        clamp();
        wm.addView(box, lp);
        if (url != null) startRtsp(url); else tune();
    }

    void hide() {
        moving = false;
        live = false;
        h.removeCallbacks(retry);
        if (player != null) {
            player.release();
            player = null;
        }
        tex = null;
        if (box != null) {
            try {
                if (tv != null) tv.reset();
                wm.removeView(box);
            } catch (Exception e) {
                Log.w(TAG, "hide: " + e);
            }
            box = null;
            tv = null;
            glass = null;
        }
    }

    /** Live: video fills the rounded hole of the frame. Not live: video shrunk away, glass card shows. */
    private void setLive(boolean on) {
        live = on;
        if (glass == null) return;
        if (tex != null) {
            tex.setAlpha(on ? 1f : 0f);
            glass.setMode(on ? GlassView.LIVE : GlassView.NO_SIGNAL);
            return;
        }
        if (tv == null) return;
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) tv.getLayoutParams();
        if (on) {
            p.width = W - 2 * FRAME;
            p.height = H - 2 * FRAME;
            p.setMargins(FRAME, FRAME, FRAME, FRAME);
        } else {
            p.width = 1;
            p.height = 1;
            p.setMargins(0, 0, 0, 0);
        }
        tv.setLayoutParams(p);
        glass.setMode(on ? GlassView.LIVE : GlassView.NO_SIGNAL);
    }

    // ---- RTSP -----------------------------------------------------------------------------

    private void startRtsp(String url) {
        final String label = sp.getString("name", "Camera");
        player = new ExoPlayer.Builder(this).build();
        player.setVolume(0f);   // muted: the window is for watching only
        player.setVideoTextureView(tex);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (glass == null) return;
                if (state == Player.STATE_READY) {
                    setLive(true);
                } else if (state == Player.STATE_BUFFERING || state == Player.STATE_IDLE) {
                    setLive(false);
                    glass.setTexts("Connecting…", label);
                }
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                Log.w(TAG, "rtsp error: " + e.getErrorCodeName());
                if (glass == null) return;
                setLive(false);
                glass.setTexts("Camera offline", label);
                h.removeCallbacks(retry);
                h.postDelayed(retry, 5000);
            }
        });
        player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(url)));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    // ---- HDMI -----------------------------------------------------------------------------

    private int port() {
        return sp.getInt("port", 1);
    }

    private void tune() {
        if (tv == null) return;
        String id = findInputId(port());
        if (id == null) {
            setLive(false);
            glass.setTexts("Input not found", "HDMI " + port());
            return;
        }
        Log.i(TAG, "tuning " + id);
        tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id));
        tv.setStreamVolume(0f);   // muted: the window is for watching only
    }

    private String findInputId(int port) {
        TvInputManager m = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> list = m.getTvInputList();
        Pattern p = Pattern.compile("Hdmi(\\d)InputService");
        String fallback = null;
        int n = 0;
        for (TvInputInfo i : list) {
            if (i.getType() != TvInputInfo.TYPE_HDMI) continue;
            n++;
            String id = i.getId();
            Matcher mt = p.matcher(id);
            if (mt.find() && Integer.parseInt(mt.group(1)) == port) return id;
            if (n == port) fallback = id;
        }
        return fallback;
    }

    // ---- moving ---------------------------------------------------------------------------

    boolean isMoving() {
        return moving;
    }

    void setMoving(boolean on) {
        moving = on;
        if (box == null) return;
        if (glass != null) glass.setMoving(on);
        if (!on) sp.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
    }

    void moveBy(int dx, int dy) {
        if (lp == null || box == null) return;
        lp.x += dx;
        lp.y += dy;
        applyPosition();
    }

    private void applyPosition() {
        clamp();
        if (box != null) wm.updateViewLayout(box, lp);
        sp.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
    }

    private void clamp() {
        lp.x = Math.max(0, Math.min(screenW - lp.width, lp.x));
        lp.y = Math.max(0, Math.min(screenH - lp.height, lp.y));
    }
}

package com.camfloat.hdmi;

import android.content.Context;
import android.graphics.Outline;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One rounded camera view: video (RTSP or HDMI input) under a GlassView frame. Owns its player. */
final class Tile extends FrameLayout {
    private static final String TAG = "CamFloat";

    private final Cam cam;
    private final int frame;
    private final int radius;
    private final GlassView glass;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextureView tex;
    private TvView tv;
    private ExoPlayer player;
    private boolean started;

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (player != null) player.prepare();
        }
    };
    /** A stream that neither plays nor fails (e.g. no free decoder) is restarted after 20 s. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.getPlaybackState() != Player.STATE_READY) {
                Log.w(TAG, "stuck, restarting: " + cam.name);
                player.stop();
                player.prepare();
                h.postDelayed(this, 20000);
            }
        }
    };

    Tile(Context c, Cam cam, int radius, int frame) {
        super(c);
        this.cam = cam;
        this.radius = radius;
        this.frame = frame;

        LayoutParams inner = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        inner.setMargins(frame, frame, frame, frame);
        if (cam.isHdmi()) {
            tv = new TvView(c);
            tv.setZOrderMediaOverlay(true);
            addView(tv, new LayoutParams(1, 1));   // tiny until video is live, so the glass card shows
        } else {
            tex = new TextureView(c);
            tex.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Tile.this.radius - Tile.this.frame);
                }
            });
            tex.setClipToOutline(true);
            tex.setAlpha(0f);
            addView(tex, inner);
        }
        glass = new GlassView(c, radius, frame);
        glass.setTexts("Connecting…", cam.name);
        glass.setLabel(cam.name);
        addView(glass, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    GlassView glass() {
        return glass;
    }

    void start() {
        if (started) return;
        started = true;
        if (cam.isHdmi()) tuneHdmi(); else startRtsp();
    }

    void stop() {
        started = false;
        h.removeCallbacks(retry);
        h.removeCallbacks(watchdog);
        if (player != null) {
            player.release();
            player = null;
        }
        if (tv != null) {
            try {
                tv.reset();
            } catch (Exception e) {
                Log.w(TAG, "tv reset: " + e);
            }
        }
    }

    private void setLive(boolean on) {
        glass.setMode(on ? GlassView.LIVE : GlassView.NO_SIGNAL);
        if (tex != null) {
            tex.setAlpha(on ? 1f : 0f);
        } else if (tv != null) {
            LayoutParams p = (LayoutParams) tv.getLayoutParams();
            if (on) {
                p.width = LayoutParams.MATCH_PARENT;
                p.height = LayoutParams.MATCH_PARENT;
                p.setMargins(frame, frame, frame, frame);
            } else {
                p.width = 1;
                p.height = 1;
                p.setMargins(0, 0, 0, 0);
            }
            tv.setLayoutParams(p);
        }
    }

    // ---- RTSP ---------------------------------------------------------------------------------

    private void startRtsp() {
        // decoder fallback lets a tile use a software decoder if the TV's hardware decoders are all busy
        player = new ExoPlayer.Builder(getContext(),
                new DefaultRenderersFactory(getContext()).setEnableDecoderFallback(true)).build();
        player.setVolume(0f);   // muted: the window is for watching only
        player.setVideoTextureView(tex);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    h.removeCallbacks(watchdog);
                    setLive(true);
                } else if (state == Player.STATE_BUFFERING || state == Player.STATE_IDLE) {
                    setLive(false);
                    glass.setTexts("Connecting…", cam.name);
                    h.removeCallbacks(watchdog);
                    h.postDelayed(watchdog, 20000);
                }
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                Log.w(TAG, "rtsp error (" + cam.name + "): " + e.getErrorCodeName());
                setLive(false);
                glass.setTexts("Camera offline", cam.name);
                h.removeCallbacks(watchdog);
                h.removeCallbacks(retry);
                h.postDelayed(retry, 5000);
            }
        });
        player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(cam.url)));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    // ---- HDMI ---------------------------------------------------------------------------------

    private void tuneHdmi() {
        final String sub = "HDMI " + cam.hdmiPort();
        tv.setCallback(new TvView.TvInputCallback() {
            @Override
            public void onVideoAvailable(String inputId) {
                setLive(true);
            }

            @Override
            public void onVideoUnavailable(String inputId, int reason) {
                setLive(false);
                boolean wait = reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
                        || reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING;
                glass.setTexts(wait ? "Connecting…" : "No signal", sub);
            }

            @Override
            public void onConnectionFailed(String inputId) {
                setLive(false);
                glass.setTexts("Can't open input", sub);
            }
        });
        String id = findInputId(cam.hdmiPort());
        if (id == null) {
            glass.setTexts("Input not found", sub);
            return;
        }
        tv.tune(id, TvContract.buildChannelUriForPassthroughInput(id));
        tv.setStreamVolume(0f);
    }

    private String findInputId(int port) {
        TvInputManager m = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
        Pattern p = Pattern.compile("Hdmi(\\d)InputService");
        String fallback = null;
        int n = 0;
        for (TvInputInfo i : m.getTvInputList()) {
            if (i.getType() != TvInputInfo.TYPE_HDMI) continue;
            n++;
            Matcher mt = p.matcher(i.getId());
            if (mt.find() && Integer.parseInt(mt.group(1)) == port) return i.getId();
            if (n == port) fallback = i.getId();
        }
        return fallback;
    }
}

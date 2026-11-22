package com.camfloat.hdmi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Draws the look of the floating window above the video surface:
 *  - LIVE: a slim glossy glass frame with rounded corners; the video shows through the rounded hole.
 *  - NO_SIGNAL: a soft, semi-transparent glossy glass card (camera icon + status text),
 *    so an idle window stays calm and see-through instead of a black box.
 */
public class GlassView extends View {
    static final int NO_SIGNAL = 0, LIVE = 1;

    private static final int AMBER = 0xFFE8A33D;

    private final float outerR, frameT;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path ring = new Path();
    private final Path tri = new Path();
    private final RectF outer = new RectF();
    private final RectF inner = new RectF();
    private final RectF tmp = new RectF();

    private int mode = NO_SIGNAL;
    private boolean moving = false;
    private String titleText = "No signal";
    private String subText = "HDMI 1";

    GlassView(Context c, float outerRadius, float frameThickness) {
        super(c);
        this.outerR = outerRadius;
        this.frameT = frameThickness;

        line.setStyle(Paint.Style.STROKE);
        icon.setStyle(Paint.Style.FILL);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        title.setTypeface(medium);
        title.setTextSize(32f);
        title.setTextAlign(Paint.Align.CENTER);
        title.setColor(0xD9FFFFFF);
        sub.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        sub.setTextSize(21f);
        sub.setTextAlign(Paint.Align.CENTER);
        sub.setColor(0x99FFFFFF);
        hint.setTypeface(medium);
        hint.setTextSize(15f);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setColor(0xFFFFFFFF);
    }

    void setMode(int m) {
        if (mode != m) {
            mode = m;
            invalidate();
        }
    }

    void setMoving(boolean on) {
        if (moving != on) {
            moving = on;
            invalidate();
        }
    }

    void setTexts(String t, String s) {
        titleText = t;
        subText = s;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        outer.set(0, 0, w, h);
        inner.set(frameT, frameT, w - frameT, h - frameT);
        ring.reset();
        ring.setFillType(Path.FillType.EVEN_ODD);
        ring.addRoundRect(outer, outerR, outerR, Path.Direction.CW);
        ring.addRoundRect(inner, outerR - frameT, outerR - frameT, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (mode == LIVE) {
            drawFrame(c, w, h);
        } else {
            drawCard(c, w, h);
        }
        if (moving) drawMoveHint(c, w, h);
    }

    // ---- live: slim frame -------------------------------------------------------------------

    private void drawFrame(Canvas c, float w, float h) {
        fill.setShader(new LinearGradient(0, 0, 0, h, 0xE6231812, 0xD9120C09, Shader.TileMode.CLAMP));
        c.drawPath(ring, fill);
        fill.setShader(null);

        gloss.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x4DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawPath(ring, gloss);

        line.setStrokeWidth(moving ? 4f : 1.5f);
        line.setColor(moving ? AMBER : 0x66FFE6C8);
        float half = line.getStrokeWidth() / 2f;
        tmp.set(half, half, w - half, h - half);
        c.drawRoundRect(tmp, outerR - half, outerR - half, line);

        line.setStrokeWidth(1f);
        line.setColor(0x38FFFFFF);
        c.drawRoundRect(inner, outerR - frameT, outerR - frameT, line);
    }

    // ---- no signal: translucent glossy card -------------------------------------------------

    private void drawCard(Canvas c, float w, float h) {
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x40FFF1DE, 0x1FFFB27A, Shader.TileMode.CLAMP));
        c.drawRoundRect(outer, outerR, outerR, fill);
        fill.setShader(null);

        // diagonal gloss streak across the upper part
        c.save();
        tmp.set(0, 0, w, h);
        Path clip = new Path();
        clip.addRoundRect(tmp, outerR, outerR, Path.Direction.CW);
        c.clipPath(clip);
        gloss.setShader(new LinearGradient(0, 0, w * 0.55f, h * 0.62f, 0x59FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, gloss);
        c.restore();

        line.setStrokeWidth(moving ? 4f : 2f);
        line.setColor(moving ? AMBER : 0x66FFF1DE);
        float half = line.getStrokeWidth() / 2f;
        tmp.set(half, half, w - half, h - half);
        c.drawRoundRect(tmp, outerR - half, outerR - half, line);

        // camera icon + text, scaled to the window size (designed at 540 wide)
        float k = w / 540f;
        c.save();
        c.scale(k, k, w / 2f, h / 2f);
        float cx = w / 2f, cy = h / 2f - 34f;
        icon.setColor(0xB3FFFFFF);
        tmp.set(cx - 46, cy - 27, cx + 20, cy + 27);
        c.drawRoundRect(tmp, 11, 11, icon);
        tri.reset();
        tri.moveTo(cx + 27, cy - 9);
        tri.lineTo(cx + 52, cy - 23);
        tri.lineTo(cx + 52, cy + 23);
        tri.lineTo(cx + 27, cy + 9);
        tri.close();
        c.drawPath(tri, icon);
        icon.setColor(0x66000000);
        c.drawCircle(cx - 13, cy, 10, icon);

        c.drawText(titleText, cx, cy + 82, title);
        c.drawText(subText, cx, cy + 116, sub);
        c.restore();
    }

    private void drawMoveHint(Canvas c, float w, float h) {
        String s = "Arrows move  •  OK hide  •  Back done";
        float tw = hint.measureText(s) + 44f;
        float th = 46f;
        tmp.set((w - tw) / 2f, h - th - 18f, (w + tw) / 2f, h - 18f);
        fill.setColor(0xCC000000);
        c.drawRoundRect(tmp, th / 2f, th / 2f, fill);
        c.drawText(s, w / 2f, tmp.bottom - 14f, hint);
    }
}

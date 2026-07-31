package com.jlr.jacobmaps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * Sapak ve dönüşlerde şeritleri görsel olarak çizer.
 *
 * OSRM her kavşak için şeritleri ve her şeridin rotamız için geçerli olup olmadığını
 * veriyor. Geçerli şeritler parlak, kullanılamayanlar sönük — "hangi şeride geçmeliyim"
 * sorusu tek bakışta cevaplanıyor.
 *
 * Oklar Canvas'a çiziliyor: şerit sayısı kavşağa göre değiştiği için hazır görsellerle
 * uğraşmak yerine hücre genişliğine göre ölçekleniyor.
 */
public class LaneView extends View {

    private static final float STROKE_DP = 3.2f;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cellBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private List<NavRoute.Lane> lanes;
    private int activeColor = 0xFF4DA3FF;
    private int idleColor = 0x55FFFFFF;
    private int cellColor = 0x14FFFFFF;
    private final float density;

    public LaneView(Context c) { this(c, null); }

    public LaneView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(STROKE_DP * density);
        fill.setStyle(Paint.Style.FILL);
        cellBg.setStyle(Paint.Style.FILL);
    }

    /** Geçerli şerit rengi manevraya yaklaştıkça değiştiği için dışarıdan veriliyor. */
    void setColors(int active, int idle, int cell) {
        activeColor = active;
        idleColor = idle;
        cellColor = cell;
        invalidate();
    }

    void setLanes(List<NavRoute.Lane> lanes) {
        this.lanes = lanes;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (lanes == null || lanes.isEmpty()) return;

        int n = lanes.size();
        float w = getWidth() / (float) n;
        float h = getHeight();
        float pad = 3f * density;

        for (int i = 0; i < n; i++) {
            NavRoute.Lane lane = lanes.get(i);
            float left = i * w;

            rect.set(left + pad, pad, left + w - pad, h - pad);
            cellBg.setColor(lane.valid ? cellColor : 0x0DFFFFFF);
            c.drawRoundRect(rect, 6 * density, 6 * density, cellBg);

            int color = lane.valid ? activeColor : idleColor;
            stroke.setColor(color);
            fill.setColor(color);

            // Bir şerit birden çok yöne izin verebilir (ör. sol + düz).
            String[] inds = lane.indications;
            if (inds == null || inds.length == 0) {
                drawArrow(c, rect, "none");
            } else {
                for (String ind : inds) drawArrow(c, rect, ind);
            }
        }
    }

    private void drawArrow(Canvas c, RectF r, String indication) {
        float cx = r.centerX();
        float bottom = r.bottom - 4 * density;
        float top = r.top + 7 * density;
        float midY = (top + bottom) / 2f;
        float reach = Math.min(r.width() * 0.34f, 13 * density);

        String ind = indication == null ? "" : indication;
        path.reset();

        if (ind.contains("uturn")) {
            path.moveTo(cx + reach * 0.6f, bottom);
            path.lineTo(cx + reach * 0.6f, midY);
            path.cubicTo(cx + reach * 0.6f, top, cx - reach * 0.6f, top,
                    cx - reach * 0.6f, midY);
            c.drawPath(path, stroke);
            head(c, cx - reach * 0.6f, midY + 4 * density, 180);
            return;
        }

        boolean left = ind.contains("left");
        boolean right = ind.contains("right");
        boolean slight = ind.contains("slight");
        boolean sharp = ind.contains("sharp");

        if (!left && !right) {                       // düz ya da işaretsiz şerit
            path.moveTo(cx, bottom);
            path.lineTo(cx, top + 5 * density);
            c.drawPath(path, stroke);
            head(c, cx, top, 0);
            return;
        }

        float dir = right ? 1f : -1f;

        if (slight) {
            path.moveTo(cx - dir * reach * 0.35f, bottom);
            path.lineTo(cx - dir * reach * 0.35f, midY);
            path.lineTo(cx + dir * reach * 0.5f, top + 7 * density);
            c.drawPath(path, stroke);
            head(c, cx + dir * reach * 0.68f, top + 3 * density, dir * 45);
            return;
        }

        float turnY = sharp ? midY + 2 * density : midY;
        path.moveTo(cx - dir * reach * 0.45f, bottom);
        path.lineTo(cx - dir * reach * 0.45f, turnY + 4 * density);
        path.quadTo(cx - dir * reach * 0.45f, turnY - 2 * density,
                cx + dir * reach * 0.15f, turnY - 2 * density);
        if (sharp) {
            path.lineTo(cx + dir * reach * 0.4f, turnY - 6 * density);
            c.drawPath(path, stroke);
            head(c, cx + dir * reach * 0.58f, turnY - 9 * density, dir * 55);
        } else {
            path.lineTo(cx + dir * reach * 0.45f, turnY - 2 * density);
            c.drawPath(path, stroke);
            head(c, cx + dir * reach * 0.72f, turnY - 2 * density, dir * 90);
        }
    }

    /** Ok başı: (x,y) ucunda, angleDeg yönüne bakan üçgen (0° = yukarı). */
    private void head(Canvas c, float x, float y, float angleDeg) {
        float s = 5.0f * density;
        double a = Math.toRadians(angleDeg);
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
        float px = -dy, py = dx;

        path.reset();
        path.moveTo(x, y);
        path.lineTo(x - dx * s * 1.6f + px * s, y - dy * s * 1.6f + py * s);
        path.lineTo(x - dx * s * 1.6f - px * s, y - dy * s * 1.6f - py * s);
        path.close();
        c.drawPath(path, fill);
    }
}

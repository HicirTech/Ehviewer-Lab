package com.hippo.ehviewer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A ring showing how far along a download is, in the colour of the device doing it (#77).
 *
 * <p>Small enough to sit on the corner of a cover, which is the whole constraint: there is no room
 * for a number or a device name, so the arc says how far and the colour says who. Two devices can
 * be handed the same colour — there are sixteen — and the download list is where the names are.
 *
 * <p>Drawn on an opaque disc rather than straight onto the artwork. A ring alone disappears against
 * a cover that happens to share its colour, and covers are not a background anyone controls.
 */
public class DownloadProgressBadge extends View {

    private static final float STROKE_DP = 2.5f;
    /** Twelve o'clock. Anything else reads as a ring that started in the wrong place. */
    private static final float START_ANGLE = -90f;
    /** The track is the same hue as the arc, faint enough that the filled part is unmistakable. */
    private static final int TRACK_ALPHA = 60;

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();

    private float progress;
    private int color = Color.GRAY;

    public DownloadProgressBadge(Context context) {
        this(context, null);
    }

    public DownloadProgressBadge(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DownloadProgressBadge(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float stroke = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, STROKE_DP,
                context.getResources().getDisplayMetrics());

        discPaint.setStyle(Paint.Style.FILL);
        discPaint.setColor(Color.WHITE);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(stroke);
        // Rounded, so that a download one page in is a visible mark rather than nothing at all.
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        applyColor();
    }

    /** The device's colour, from {@code SmbDeviceColor}. */
    public void setColor(int color) {
        if (this.color == color) {
            return;
        }
        this.color = color;
        applyColor();
        invalidate();
    }

    /**
     * How far along, 0 to 1. Out-of-range values are clamped rather than rejected: the count comes
     * off the share, where a stale total and a fresh finished can briefly disagree.
     */
    public void setProgress(float progress) {
        float clamped = progress < 0f ? 0f : (progress > 1f ? 1f : progress);
        if (Float.compare(this.progress, clamped) == 0) {
            return;
        }
        this.progress = clamped;
        invalidate();
    }

    private void applyColor() {
        arcPaint.setColor(color);
        trackPaint.setColor(color);
        trackPaint.setAlpha(TRACK_ALPHA);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = arcPaint.getStrokeWidth() / 2f;
        ring.set(inset, inset, w - inset, h - inset);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        canvas.drawCircle(cx, cy, Math.min(cx, cy), discPaint);
        canvas.drawOval(ring, trackPaint);
        if (progress > 0f) {
            canvas.drawArc(ring, START_ANGLE, progress * 360f, false, arcPaint);
        }
    }
}

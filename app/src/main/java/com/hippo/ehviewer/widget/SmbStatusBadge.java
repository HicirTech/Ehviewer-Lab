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

import com.hippo.android.resource.AttrResources;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.smb.SmbSavedGalleries;

/**
 * The one corner of a gallery card that speaks about the SMB share.
 *
 * <p>A card has room for exactly one small mark, and the two things worth saying are mutually
 * exclusive by nature — a gallery someone is still downloading is, by definition, not yet sitting
 * finished on the share. So they share a slot and a shape, and which one is showing is this view's
 * state rather than a question of which of two overlapping views someone remembered to hide.
 *
 * <table>
 *   <tr><td>{@link #setProgress}</td><td>an arc, in the colour of the device doing the work (#77)</td></tr>
 *   <tr><td>{@link #setSaved}</td><td>a closed ring, in one fixed colour — it is already here (#83)</td></tr>
 * </table>
 *
 * <p>Drawn on an opaque disc rather than straight onto the artwork. A ring alone disappears against
 * a cover that happens to share its colour, and covers are not a background anyone controls.
 */
public class SmbStatusBadge extends View {

    private static final float STROKE_DP = 2.5f;
    /** Twelve o'clock. Anything else reads as a ring that started in the wrong place. */
    private static final float START_ANGLE = -90f;
    /** The track is the same hue as the arc, faint enough that the filled part is unmistakable. */
    private static final int TRACK_ALPHA = 60;

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();

    /**
     * The colour of "this is on the share". Deliberately not one of the sixteen device colours:
     * nobody in particular saved it, it is simply here.
     */
    private final int savedColor;

    private float progress;
    private int color = Color.GRAY;

    public SmbStatusBadge(Context context) {
        this(context, null);
    }

    public SmbStatusBadge(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SmbStatusBadge(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
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

        savedColor = AttrResources.getAttrColor(context, R.attr.widgetColorThemePrimary);
        applyColor();
    }

    /**
     * Marks a card whose gallery is already finished on the share, and hides the badge otherwise
     * (#83).
     *
     * <p>Static and null-tolerant because three different lists bind this, each with its own
     * holder, and one of the card layouts has no badge at all — a rule this small should be stated
     * once rather than copied into every adapter with its own idea of what to check.
     *
     * <p>Answers from whatever {@link SmbSavedGalleries} already holds. It never goes to the share
     * here: binding a row cannot wait on a NAS, so a cold list simply shows no marks until the
     * background read lands and the list is told to redraw.
     */
    public static void bindSaved(@Nullable SmbStatusBadge badge, long gid) {
        if (badge == null) {
            return;
        }
        if (SmbSavedGalleries.getInstance().contains(gid)) {
            badge.setSaved();
            badge.setVisibility(VISIBLE);
        } else {
            badge.setVisibility(GONE);
        }
    }

    /**
     * Shows a download in flight: how far along, in the colour of the device doing it.
     *
     * @param deviceColor from {@code SmbDeviceColor}
     * @param fraction    0 to 1; out-of-range values are clamped rather than rejected, because the
     *                    count comes off the share where a stale total and a fresh finished can
     *                    briefly disagree
     */
    public void setProgress(int deviceColor, float fraction) {
        float clamped = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
        if (this.color == deviceColor && Float.compare(this.progress, clamped) == 0) {
            return;
        }
        this.color = deviceColor;
        this.progress = clamped;
        applyColor();
        invalidate();
    }

    /**
     * Shows that the gallery is finished and on the share: a closed ring.
     *
     * <p>The same shape a completed download ends on, which is the point — it is the state a
     * progress arc is heading towards.
     */
    public void setSaved() {
        if (this.color == savedColor && Float.compare(this.progress, 1f) == 0) {
            return;
        }
        this.color = savedColor;
        this.progress = 1f;
        applyColor();
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

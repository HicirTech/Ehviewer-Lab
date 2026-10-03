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

/** A card's one SMB mark, on an opaque disc so no cover colour can hide the ring. */
public class SmbStatusBadge extends View {

    private static final float STROKE_DP = 2.5f;
    /** Twelve o'clock. */
    private static final float START_ANGLE = -90f;
    private static final int TRACK_ALPHA = 60;

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();

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
        // Keeps a one-page arc visible.
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        savedColor = AttrResources.getAttrColor(context, R.attr.widgetColorThemePrimary);
        applyColor();
    }

    /** Cache only, never the share: a cold list shows no marks until the background read lands. */
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

    /** Clamps {@code fraction} to 0..1: a stale total and a fresh count can briefly disagree. */
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

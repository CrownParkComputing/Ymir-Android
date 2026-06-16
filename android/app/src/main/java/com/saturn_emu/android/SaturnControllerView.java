package com.saturn_emu.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Shows the Saturn control-pad photo ({@code R.drawable.saturn_pad}) and lays out
 * transparent {@link Hotspot}-tagged child views over each button so the caller
 * can attach tap handlers for external-controller binding. The D-pad is placed
 * separately via {@link #dpadRegion()} (a DpadView).
 */
final class SaturnControllerView extends FrameLayout {

    enum Shape { OVAL, PILL }

    /** Centre (cx,cy) fraction of width/height; size (w,h) fraction of width. */
    static final class Hotspot {
        final int buttonIndex;
        final float cx, cy, w, h;
        final Shape shape;
        Hotspot(int buttonIndex, float cx, float cy, float w, float h, Shape shape) {
            this.buttonIndex = buttonIndex;
            this.cx = cx; this.cy = cy; this.w = w; this.h = h; this.shape = shape;
        }
    }

    static Hotspot[] hotspots() {
        return new Hotspot[]{
                new Hotspot(SaturnPadMappingManager.BTN_L, 0.17f, 0.26f, 0.130f, 0.050f, Shape.PILL),
                new Hotspot(SaturnPadMappingManager.BTN_R, 0.83f, 0.26f, 0.130f, 0.050f, Shape.PILL),
                new Hotspot(SaturnPadMappingManager.BTN_START, 0.475f, 0.605f, 0.100f, 0.050f, Shape.PILL),
                new Hotspot(SaturnPadMappingManager.BTN_X, 0.665f, 0.470f, 0.085f, 0.085f, Shape.OVAL),
                new Hotspot(SaturnPadMappingManager.BTN_Y, 0.750f, 0.410f, 0.085f, 0.085f, Shape.OVAL),
                new Hotspot(SaturnPadMappingManager.BTN_Z, 0.835f, 0.370f, 0.085f, 0.085f, Shape.OVAL),
                new Hotspot(SaturnPadMappingManager.BTN_A, 0.695f, 0.585f, 0.085f, 0.085f, Shape.OVAL),
                new Hotspot(SaturnPadMappingManager.BTN_B, 0.780f, 0.525f, 0.085f, 0.085f, Shape.OVAL),
                new Hotspot(SaturnPadMappingManager.BTN_C, 0.865f, 0.485f, 0.085f, 0.085f, Shape.OVAL),
        };
    }

    /** Square region over the photo's D-pad for the editor DpadView. */
    static Hotspot dpadRegion() {
        return new Hotspot(-1, 0.205f, 0.520f, 0.180f, 0.180f, Shape.OVAL);
    }

    private static final float ASPECT = 683f / 1024f; // saturn_pad.png height / width
    private final Drawable pad;

    SaturnControllerView(Context context) {
        super(context);
        setWillNotDraw(false);
        pad = context.getResources().getDrawable(R.drawable.saturn_pad, context.getTheme());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int) (w * ASPECT);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);
        int hSize = MeasureSpec.getSize(heightMeasureSpec);
        if (hMode != MeasureSpec.UNSPECIFIED && hSize > 0 && h > hSize) {
            h = hSize;
            w = (int) (h / ASPECT);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof Hotspot)) continue;
            Hotspot hs = (Hotspot) tag;
            int pw = Math.round(hs.w * w);
            int ph = Math.round(hs.h * w);
            int cx = Math.round(hs.cx * w);
            int cy = Math.round(hs.cy * h);
            child.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY));
            int l = cx - pw / 2;
            int t = cy - ph / 2;
            child.layout(l, t, l + pw, t + ph);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (pad != null) {
            pad.setBounds(0, 0, getWidth(), getHeight());
            pad.draw(canvas);
        }
        super.dispatchDraw(canvas);
    }
}

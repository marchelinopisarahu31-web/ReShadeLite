package com.reshadelite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Full-screen, non-touchable view that draws the colour filter layers. */
public class FilterView extends View {
    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int brightness = 0;   // -50..50  (negative = darker, positive = washed/brighter)
    private int warmth = 0;       // -100..100 (negative = cool/blue, positive = warm/orange)
    private int tint = 0;         // 0..100 extra colour strength
    private int tintColor = Color.rgb(255, 80, 120);
    private int vignette = 0;     // 0..100

    public FilterView(Context c) {
        super(c);
    }

    public void apply(int brightness, int warmth, int tint, int tintColor, int vignette) {
        this.brightness = brightness;
        this.warmth = warmth;
        this.tint = tint;
        this.tintColor = tintColor;
        this.vignette = vignette;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 1) brightness
        if (brightness < 0) {
            canvas.drawColor(Color.argb(Math.min(200, -brightness * 4), 0, 0, 0));
        } else if (brightness > 0) {
            canvas.drawColor(Color.argb(Math.min(140, brightness * 3), 255, 255, 255));
        }

        // 2) warm / cool
        if (warmth > 0) {
            canvas.drawColor(Color.argb(warmth * 90 / 100, 255, 140, 20));
        } else if (warmth < 0) {
            canvas.drawColor(Color.argb(-warmth * 90 / 100, 30, 120, 255));
        }

        // 3) custom tint
        if (tint > 0) {
            canvas.drawColor(Color.argb(tint * 120 / 100, Color.red(tintColor),
                    Color.green(tintColor), Color.blue(tintColor)));
        }

        // 4) vignette
        if (vignette > 0) {
            float w = getWidth(), h = getHeight();
            float radius = (float) Math.hypot(w, h) / 2f;
            int edge = Color.argb(vignette * 230 / 100, 0, 0, 0);
            vignettePaint.setShader(new RadialGradient(w / 2f, h / 2f, radius,
                    new int[]{Color.TRANSPARENT, Color.TRANSPARENT, edge},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, vignettePaint);
        }
    }
      }

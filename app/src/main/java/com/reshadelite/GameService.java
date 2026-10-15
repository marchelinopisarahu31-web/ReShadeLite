package com.reshadelite;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Game pad: joystick (WASD), letter keys that tap a spot, touchpad + cursor. */
public class GameService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String HAND = "🖐";
    private static final String JOY = "🕹";

    private WindowManager wm;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout panel;
    private LinearLayout keysBox;
    private Button editBtn;
    private LinearLayout sizeRow;
    private final Map<String, WindowManager.LayoutParams> markerLp = new HashMap<>();
    private String selMarker = null;
    private WindowManager.LayoutParams panelLp;
    private View cursor;
    private WindowManager.LayoutParams cursorLp;
    private LinearLayout picker;
    private final Map<String, View> markers = new HashMap<>();
    private int cursorSize;
    private float cx, cy;
    private boolean editMode = false;

    // joystick state
    private boolean active = false;
    private float tx, ty, jLastX, jLastY;
    private GestureDescription.StrokeDescription joyStroke = null;

    // camera (look) state
    private boolean lookDown = false;
    private boolean lookWrap = false;
    private float ltx, lty, lLastX, lLastY;
    private GestureDescription.StrokeDescription lookStroke = null;
    private Button lookBtn, sensBtn;

    private boolean inFlight = false;
    private int gen = 0;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(this);
        prefs.edit().putBoolean("eedit", false).apply();
        cursorSize = dp(22);
        buildCursor();
        buildPanel();
        buildEBubble();
        applyVisibility();
        applyTapSound();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        ui.postDelayed(this::resetPositions, 400);
    }

    private void resetPositions() {
        if (wm == null || panel == null) return;
        int[] s = screen();
        panelLp.x = Math.max(0, Math.min(panelLp.x, s[0] - dp(120)));
        panelLp.y = Math.max(0, Math.min(panelLp.y, s[1] - dp(120)));
        if (panel.isAttachedToWindow()) wm.updateViewLayout(panel, panelLp);
        cx = s[0] / 2f;
        cy = s[1] / 2f;
        moveCursor(cx, cy);
        if (eEditOn) { hideMarkers(); addMarker("E", 0); addMarker("CROSS"); }
        else if (editMode) { hideMarkers(); showMarkers(); }
        else if (eBubble != null && eBubble.isAttachedToWindow()) {
            buildEBubble();
            wm.addView(eBubble, eLp);
            startPolling();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if ("tapsound".equals(key)) { applyTapSound(); return; }
        if ("snd_style".equals(key) || "tapvol".equals(key)) {
            if (touchWatch != null) {
                if ("snd_style".equals(key)) buildSounds();
                playClick(true);
            }
            return;
        }
        if ("pad".equals(key) || "eonly".equals(key)) {
            applyVisibility();
            ui.postDelayed(this::resetPositions, 200);
        } else if ("eedit".equals(key)) {
            if (prefs.getBoolean("eedit", false)) enterEEdit(); else exitEEdit();
        }
    }

    @Override
    public void onDestroy() {
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        hideMarkers();
        removeView(picker);
        removeView(panel);
        removeView(cursor);
        polling = false;
        removeView(touchWatch);
        for (AudioTrack t : clicks) { if (t != null) t.release(); }
        removeView(eBar);
        removeView(eBubble);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- windows

    private WindowManager.LayoutParams lp(boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams l = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags, PixelFormat.TRANSLUCENT);
        l.gravity = Gravity.TOP | Gravity.START;
        return l;
    }

    private void removeView(View v) {
        if (v == null || wm == null) return;
        try { if (v.isAttachedToWindow()) wm.removeView(v); } catch (Exception ignored) { }
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float ps() { return prefs.getInt("ps", 100) / 100f; }

    private int pdp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density * ps());
    }

    private int[] screen() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
    }

    // ---------------------------------------------------------------- E bubble (at the crosshair)

    private TextView eBubble;
    private WindowManager.LayoutParams eLp;

    private void buildEBubble() {
        removeView(eBubble);
        int sizeDp = prefs.getInt("sz_CROSS", 44);
        int sz = dp(sizeDp);
        int[] sc = screen();
        eBubble = new TextView(this);
        eBubble.setGravity(Gravity.CENTER);
        eBubble.setText("E");
        eBubble.setTextColor(Color.WHITE);
        eBubble.setTextSize(sizeDp * 0.4f);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.argb(70, 0, 0, 0));
        g.setStroke(dp(2), Color.argb(230, 255, 255, 255));
        eBubble.setBackground(g);
        eBubble.setOnClickListener(v -> tapE());
        eBubble.setVisibility(bubbleOn ? View.VISIBLE : View.INVISIBLE);
        eLp = lp(true);
        eLp.width = sz;
        eLp.height = sz;
        eLp.x = (int) (sc[0] * prefs.getInt("bx", 50) / 100f) - sz / 2;
        eLp.y = (int) (sc[1] * prefs.getInt("by", 50) / 100f) - sz / 2;
    }

    private void tapE() {
        if (!isSet("E")) {
            Toast.makeText(this, "Tombol E belum diatur. Tekan \u270E lalu geser \uD83D\uDD90E ke tombol tangan game",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int[] s = screen();
        tapAt(s[0] * prefs.getInt("kx_E", 0) / 1000f, s[1] * prefs.getInt("ky_E", 0) / 1000f);
    }

    // ---------------------------------------------------------------- auto-detect: item can be picked up

    private boolean bubbleOn = true;
    private int shotFails = 0;
    private boolean polling = false;
    private long lastShot = 0;
    private final java.util.concurrent.Executor bgExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void startPolling() {
        if (polling || Build.VERSION.SDK_INT < 30 || !prefs.getBoolean("auto", true)) return;
        polling = true;
        ui.postDelayed(pollRunnable, 300);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            boolean wanted = (prefs.getBoolean("pad", false) || prefs.getBoolean("eonly", false))
                    && !editMode && !eEditOn
                    && prefs.getBoolean("auto", true) && shotFails < 3;
            if (!wanted) {
                polling = false;
                if (shotFails >= 3) setBubbleShown(true);
                return;
            }
            lastShot = System.currentTimeMillis();
            takeShot();
        }
    };

    /** Next check as soon as the system allows (it needs ~0.35 s between screenshots). */
    private void scheduleNext() {
        if (!polling) return;
        long wait = Math.max(20, 360 - (System.currentTimeMillis() - lastShot));
        ui.postDelayed(pollRunnable, wait);
    }

    private void setBubbleShown(boolean on) {
        bubbleOn = on;
        if (eBubble != null) eBubble.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private void takeShot() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, bgExec, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    try {
                        HardwareBuffer hb = r.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        if (hw != null) {
                            Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                            if (soft != null) {
                                final boolean present = itemReachable(soft);
                                soft.recycle();
                                shotFails = 0;
                                ui.post(() -> setBubbleShown(present));
                            }
                        }
                        hb.close();
                    } catch (Throwable t) {
                        shotFails++;
                    }
                    ui.post(() -> scheduleNext());
                }
                @Override public void onFailure(int code) {
                    // 3 = interval too short: just try again
                    if (code != 3) shotFails++;
                    ui.post(() -> {
                        if (shotFails >= 3) {
                            setBubbleShown(true);
                            Toast.makeText(GameService.this,
                                    "Deteksi otomatis tidak tersedia, tombol E tampil terus",
                                    Toast.LENGTH_LONG).show();
                        }
                        scheduleNext();
                    });
                }
            });
        } catch (Throwable t) {
            shotFails++;
            ui.post(() -> scheduleNext());
        }
    }

    /**
     * True when the game shows its pickup (hand) button: it is only on screen while an item is
     * in reach. Falls back to the aim ring in the screen centre if the hand spot is not set yet.
     */
    private boolean itemReachable(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (isSet("E")) {
            float hx = w * prefs.getInt("kx_E", 0) / 1000f;
            float hy = h * prefs.getInt("ky_E", 0) / 1000f;
            float half = h * 0.05f;
            int n = 0, bright = 0;
            for (int iy = -4; iy <= 4; iy++) {
                for (int ix = -4; ix <= 4; ix++) {
                    int x = Math.max(0, Math.min(w - 1, Math.round(hx + ix * half / 4f)));
                    int y = Math.max(0, Math.min(h - 1, Math.round(hy + iy * half / 4f)));
                    int px = b.getPixel(x, y);
                    int lum = (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000;
                    if (lum > 215) bright++;
                    n++;
                }
            }
            return bright / (float) n >= 0.30f;
        }
        return ringRatio(b) >= 0.65f;
    }

    /** Fraction (0..1) of points on the game's aim ring (centre of screen) that are bright white. */
    private float ringRatio(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        float cx0 = w / 2f, cy0 = h / 2f;
        int tol = Math.max(2, Math.round(h * 0.004f));
        float best = 0f;
        for (float rf = 0.016f; rf <= 0.026f; rf += 0.005f) {
            float r = h * rf;
            int n = 24, hit = 0;
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * i / n;
                boolean bright = false;
                for (int d = -tol; d <= tol && !bright; d++) {
                    int x = (int) Math.round(cx0 + Math.cos(a) * (r + d));
                    int y = (int) Math.round(cy0 + Math.sin(a) * (r + d));
                    x = Math.max(0, Math.min(w - 1, x));
                    y = Math.max(0, Math.min(h - 1, y));
                    int px = b.getPixel(x, y);
                    int lum = (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000;
                    if (lum > 200) bright = true;
                }
                if (bright) hit++;
            }
            best = Math.max(best, hit / (float) n);
        }
        return best;
    }

    // ---------------------------------------------------------------- touch sound (keyboard click)

    private View touchWatch;
    private final AudioTrack[] clicks = new AudioTrack[4];
    private int clickIdx = 0;
    private long lastClick = 0;

    private short[] genClick(int style, int variant) {
        int sr = 44100;
        int n = (int) (sr * 0.09);
        short[] out = new short[n];
        double f = 1 + (variant - 1.5) * 0.06;
        java.util.Random rnd = new java.util.Random(7 + variant);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) sr;
            double noise = rnd.nextDouble() * 2 - 1;
            double v;
            if (style == 0) {            // thock: deep, soft
                lp += 0.25 * (noise - lp);
                v = 0.55 * lp * Math.exp(-t / 0.004)
                        + 0.75 * Math.sin(2 * Math.PI * 165 * f * t) * Math.exp(-t / 0.022)
                        + 0.25 * Math.sin(2 * Math.PI * 1450 * f * t) * Math.exp(-t / 0.006);
            } else if (style == 1) {     // click: crisp
                v = 0.5 * noise * Math.exp(-t / 0.0015)
                        + 0.6 * Math.sin(2 * Math.PI * 2600 * f * t) * Math.exp(-t / 0.004)
                        + 0.35 * Math.sin(2 * Math.PI * 420 * f * t) * Math.exp(-t / 0.010);
            } else {                     // soft pop
                v = 0.7 * Math.sin(2 * Math.PI * 520 * f * t) * Math.exp(-t / 0.016)
                        + 0.25 * Math.sin(2 * Math.PI * 1040 * f * t) * Math.exp(-t / 0.010);
            }
            v *= Math.min(1.0, t / 0.0007);
            v = Math.max(-1.0, Math.min(1.0, v * 0.9));
            out[i] = (short) (v * 32767);
        }
        return out;
    }

    private void buildSounds() {
        int style = prefs.getInt("snd_style", 0);
        for (int i = 0; i < clicks.length; i++) {
            try {
                if (clicks[i] != null) clicks[i].release();
                short[] data = genClick(style, i);
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(44100)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(data.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                t.write(data, 0, data.length);
                clicks[i] = t;
            } catch (Throwable ex) {
                clicks[i] = null;
            }
        }
    }

    private void playClick(boolean force) {
        if (!force && !prefs.getBoolean("tapsound", false)) return;
        long now = System.currentTimeMillis();
        if (now - lastClick < 45) return;
        lastClick = now;
        AudioTrack t = clicks[clickIdx++ % clicks.length];
        if (t == null) return;
        try {
            t.setVolume(Math.max(0f, Math.min(1f, prefs.getInt("tapvol", 60) / 100f)));
            t.stop();
            t.reloadStaticData();
            t.play();
        } catch (Throwable ignored) { }
    }

    /** A 1-pixel window that is told about every touch that lands anywhere else. */
    private void applyTapSound() {
        boolean on = prefs.getBoolean("tapsound", false);
        if (on && touchWatch == null) {
            buildSounds();
            touchWatch = new View(this) {
                @Override public boolean onTouchEvent(MotionEvent e) {
                    if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) playClick(false);
                    return false;
                }
            };
            WindowManager.LayoutParams l = new WindowManager.LayoutParams(1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            l.gravity = Gravity.TOP | Gravity.START;
            try { wm.addView(touchWatch, l); } catch (Throwable t) { touchWatch = null; }
            playClick(true);
        } else if (!on && touchWatch != null) {
            removeView(touchWatch);
            touchWatch = null;
            for (int i = 0; i < clicks.length; i++) {
                if (clicks[i] != null) { clicks[i].release(); clicks[i] = null; }
            }
        }
    }

    // ---------------------------------------------------------------- cursor

    private void buildCursor() {
        cursor = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.argb(190, 255, 0, 0));
        g.setStroke(dp(2), Color.WHITE);
        cursor.setBackground(g);
        cursorLp = lp(false);
        cursorLp.width = cursorSize;
        cursorLp.height = cursorSize;
        int[] s = screen();
        cx = s[0] / 2f;
        cy = s[1] / 2f;
        cursorLp.x = (int) cx - cursorSize / 2;
        cursorLp.y = (int) cy - cursorSize / 2;
    }

    private void moveCursor(float x, float y) {
        int[] s = screen();
        cx = Math.max(0, Math.min(s[0] - 1, x));
        cy = Math.max(0, Math.min(s[1] - 1, y));
        cursorLp.x = (int) cx - cursorSize / 2;
        cursorLp.y = (int) cy - cursorSize / 2;
        if (cursor.isAttachedToWindow()) wm.updateViewLayout(cursor, cursorLp);
    }

    // ---------------------------------------------------------------- joystick widget

    /** Round pad you drag with your thumb. Reports direction in -1..1. */
    private class JoyPad extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float tx0 = 0, ty0 = 0; // thumb offset, -1..1

        JoyPad() { super(GameService.this); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(120, 255, 255, 255));
            c.drawCircle(w / 2, h / 2, r - dp(2), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(Color.argb(200, 255, 255, 255));
            c.drawCircle(w / 2, h / 2, r - dp(2), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(230, 255, 120, 0));
            float thumbR = r * 0.38f;
            c.drawCircle(w / 2 + tx0 * (r - thumbR), h / 2 + ty0 * (r - thumbR), thumbR, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                tx0 = 0; ty0 = 0;
                invalidate();
                setJoy(0, 0);
                return true;
            }
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
                float dx = (e.getX() - w / 2f) / r;
                float dy = (e.getY() - h / 2f) / r;
                float len = (float) Math.hypot(dx, dy);
                if (len > 1f) { dx /= len; dy /= len; }
                tx0 = dx; ty0 = dy;
                invalidate();
                setJoy(dx, dy);
            }
            return true;
        }
    }

    private boolean kw, ka, ks, kd;

    private final boolean[] latched = new boolean[4];
    private final boolean[] ignoreUp = new boolean[4];
    private final long[] pressT = new long[4];

    private void setKey(String k, boolean down) {
        if (k.equals("W")) kw = down;
        else if (k.equals("A")) ka = down;
        else if (k.equals("S")) ks = down;
        else kd = down;
        float dx = (kd ? 1 : 0) - (ka ? 1 : 0);
        float dy = (ks ? 1 : 0) - (kw ? 1 : 0);
        float len = (float) Math.hypot(dx, dy);
        if (len > 0) { dx /= len; dy /= len; }
        setJoy(dx, dy);
    }

    /** Hold = walk while held. Quick tap = keeps walking until you tap it again. */
    private Button holdKey(final String k) {
        final Button b = headBtn(k);
        final int i = "WASD".indexOf(k);
        b.setTextSize(17);
        b.setBackground(rounded(Color.argb(220, 60, 60, 80), 10));
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                if (latched[i]) {
                    latched[i] = false;
                    ignoreUp[i] = true;
                    b.setBackground(rounded(Color.argb(220, 60, 60, 80), 10));
                    setKey(k, false);
                } else {
                    ignoreUp[i] = false;
                    pressT[i] = System.currentTimeMillis();
                    b.setBackground(rounded(Color.argb(240, 255, 120, 0), 10));
                    setKey(k, true);
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                if (ignoreUp[i]) return true;
                if (System.currentTimeMillis() - pressT[i] < 280) {
                    latched[i] = true; // quick tap: stay on
                } else {
                    b.setBackground(rounded(Color.argb(220, 60, 60, 80), 10));
                    setKey(k, false);
                }
            }
            return true;
        });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(pdp(50), pdp(46));
        b.setLayoutParams(l);
        return b;
    }

    private void setJoy(float x, float y) {
        float mag = (float) Math.hypot(x, y);
        if (mag < 0.2f) {
            active = false;
        } else {
            int[] s = screen();
            float r = Math.min(s[0], s[1]) * 0.10f;
            tx = joyX() + x * r;
            ty = joyY() + y * r;
            active = true;
        }
        pump();
    }

    private float joyX() { return screen()[0] * prefs.getInt("jx", 15) / 100f; }
    private float joyY() { return screen()[1] * prefs.getInt("jy", 70) / 100f; }

    // ---------------------------------------------------------------- gestures

    private GestureDescription.StrokeDescription seg(GestureDescription.StrokeDescription prev,
            float sx, float sy, float ex, float ey, boolean cont, int dur) {
        Path p = new Path();
        p.moveTo(sx, sy);
        if (ex != sx || ey != sy) p.lineTo(ex, ey);
        return prev == null
                ? new GestureDescription.StrokeDescription(p, 0, dur, cont)
                : prev.continueStroke(p, 0, dur, cont);
    }

    /** One gesture carries the joystick finger and the camera finger together. */
    private void pump() {
        if (inFlight) return;
        GestureDescription.Builder gb = new GestureDescription.Builder();
        boolean any = false;
        GestureDescription.StrokeDescription js = null, ls = null;
        boolean jc = false, lc = false;

        if (active) {
            float sx = joyStroke == null ? joyX() : jLastX;
            float sy = joyStroke == null ? joyY() : jLastY;
            boolean same = joyStroke != null && tx == jLastX && ty == jLastY;
            js = seg(joyStroke, sx, sy, tx, ty, true, same ? 250 : 100);
            jc = true;
            jLastX = tx; jLastY = ty;
            gb.addStroke(js);
            any = true;
        } else if (joyStroke != null) {
            js = seg(joyStroke, jLastX, jLastY, jLastX, jLastY, false, 50);
            gb.addStroke(js);
            any = true;
        }

        if (lookDown && !lookWrap) {
            float sx = lookStroke == null ? lookPX() : lLastX;
            float sy = lookStroke == null ? lookPY() : lLastY;
            boolean lsame = lookStroke != null && ltx == lLastX && lty == lLastY;
            ls = seg(lookStroke, sx, sy, ltx, lty, true, lsame ? 150 : 100);
            lc = true;
            lLastX = ltx; lLastY = lty;
            gb.addStroke(ls);
            any = true;
        } else if (lookStroke != null) {
            ls = seg(lookStroke, lLastX, lLastY, lLastX, lLastY, false, 50);
            gb.addStroke(ls);
            any = true;
            if (lookWrap) {
                ltx = lookPX(); lty = lookPY();
                lookWrap = false;
            }
        }
        final boolean hadTap = hasTap;
        final float htx = tapX, hty = tapY;
        if (hasTap) {
            Path tp = new Path();
            tp.moveTo(tapX, tapY);
            gb.addStroke(new GestureDescription.StrokeDescription(tp, 0, 100));
            hasTap = false;
            any = true;
        }
        if (!any) return;

        final int my = ++gen;
        final boolean fjc = jc, flc = lc;
        final GestureDescription.StrokeDescription fjs = js, fls = ls;
        inFlight = true;
        boolean ok = dispatchGesture(gb.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                if (my != gen) return;
                inFlight = false;
                if (fjs != null) joyStroke = fjc ? fjs : null;
                if (fls != null) lookStroke = flc ? fls : null;
                if (hadTap) Toast.makeText(GameService.this, "Tap terkirim ("
                        + Math.round(htx) + "," + Math.round(hty) + ")", Toast.LENGTH_SHORT).show();
                pump();
            }
            @Override public void onCancelled(GestureDescription g) {
                if (my != gen) return;
                inFlight = false;
                joyStroke = null;
                lookStroke = null;
                if (hadTap && tapRetries > 0) {
                    tapRetries--;
                    tapX = htx; tapY = hty;
                    hasTap = true;
                } else if (hadTap) {
                    Toast.makeText(GameService.this, "Tap dibatalkan sistem. Jangan sentuh layar lain saat menekan.",
                            Toast.LENGTH_SHORT).show();
                } else {
                    warn("Gerakan dibatalkan sistem");
                }
                ui.postDelayed(() -> pump(), 120);
            }
        }, ui);
        if (!ok) {
            inFlight = false;
            joyStroke = null;
            lookStroke = null;
            warn("Gagal kirim gerakan (cek Aksesibilitas)");
        } else {
            // safety: if the system never answers, unstick and retry
            ui.postDelayed(() -> {
                if (gen == my && inFlight) {
                    inFlight = false;
                    joyStroke = null;
                    lookStroke = null;
                    pump();
                }
            }, 700);
        }
    }

    private long lastWarn = 0;

    private void warn(String m) {
        long n = System.currentTimeMillis();
        if (n - lastWarn < 3000) return;
        lastWarn = n;
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private boolean hasTap = false;
    private int tapRetries = 0;
    private float tapX, tapY;

    /** A tap rides along in the same gesture as the held joystick, so walking is not cut. */
    private void tapAt(float x, float y) {
        tapX = x;
        tapY = y;
        hasTap = true;
        tapRetries = 3;
        pump();
    }

    // ---------------------------------------------------------------- camera

    private float lookPX() { return screen()[0] * prefs.getInt("lx", 70) / 100f; }
    private float lookPY() { return screen()[1] * prefs.getInt("ly", 50) / 100f; }
    private boolean lookMode() { return prefs.getBoolean("look", true); }
    private int sens() { return prefs.getInt("sens", 5); }

    private void lookStart() {
        lookDown = true;
        lookWrap = false;
        ltx = lookPX();
        lty = lookPY();
    }

    private void lookMove(float dx, float dy) {
        int[] s = screen();
        float gain = sens() * 0.12f;
        ltx += dx * gain;
        lty += dy * gain;
        float mx = s[0] * 0.03f, my = s[1] * 0.03f;
        if (ltx < mx || ltx > s[0] - mx || lty < my || lty > s[1] - my) {
            ltx = Math.max(mx, Math.min(s[0] - mx, ltx));
            lty = Math.max(my, Math.min(s[1] - my, lty));
            lookWrap = true;
        }
        pump();
    }

    private void lookEnd() {
        lookDown = false;
        lookWrap = false;
        pump();
    }

    // ---------------------------------------------------------------- panel

    private Button headBtn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13 * ps());
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setPadding(pdp(10), pdp(4), pdp(10), pdp(4));
        b.setBackground(rounded(Color.argb(200, 60, 60, 70), 8));
        return b;
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pdp(6), pdp(6), pdp(6), pdp(6));
        panel.setBackground(rounded(Color.argb(110, 0, 0, 0), 12));

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        final Button drag = headBtn("≡ geser");
        Button setBtn = headBtn("⚙");
        editBtn = headBtn("✎");
        head.addView(drag);
        head.addView(setBtn);
        head.addView(editBtn);
        lookBtn = headBtn(lookMode() ? "\uD83D\uDC41" : "\uD83D\uDDB1");
        sensBtn = headBtn("S" + sens());
        head.addView(lookBtn);
        head.addView(sensBtn);
        panel.addView(head);

        sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button tMin = headBtn("Tanda \u2212");
        Button tPlus = headBtn("Tanda +");
        Button pMin = headBtn("Panel \u2212");
        Button pPlus = headBtn("Panel +");
        sizeRow.addView(tMin);
        sizeRow.addView(tPlus);
        sizeRow.addView(pMin);
        sizeRow.addView(pPlus);
        sizeRow.setVisibility(editMode ? View.VISIBLE : View.GONE);
        panel.addView(sizeRow);
        tMin.setOnClickListener(v -> resizeMarker(-6));
        tPlus.setOnClickListener(v -> resizeMarker(6));
        pMin.setOnClickListener(v -> resizePanel(-10));
        pPlus.setOnClickListener(v -> resizePanel(10));
        editBtn.setText(editMode ? "\u2714" : "\u270E");
        lookBtn.setOnClickListener(v -> {
            boolean m = !lookMode();
            prefs.edit().putBoolean("look", m).apply();
            lookBtn.setText(m ? "\uD83D\uDC41" : "\uD83D\uDDB1");
            Toast.makeText(this, m ? "Mode kamera: geser touchpad = putar layar game"
                    : "Mode kursor: geser = gerak kursor, tap = klik", Toast.LENGTH_SHORT).show();
        });
        sensBtn.setOnClickListener(v -> {
            int n = sens() % 10 + 1;
            prefs.edit().putInt("sens", n).apply();
            sensBtn.setText("S" + n);
        });

        final float[] down = new float[4];
        drag.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                down[0] = e.getRawX(); down[1] = e.getRawY();
                down[2] = panelLp.x; down[3] = panelLp.y;
            } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                int[] s = screen();
                panelLp.x = (int) Math.max(0, Math.min(s[0] - pdp(60), down[2] + e.getRawX() - down[0]));
                panelLp.y = (int) Math.max(0, Math.min(s[1] - pdp(60), down[3] + e.getRawY() - down[1]));
                wm.updateViewLayout(panel, panelLp);
            }
            return true;
        });
        setBtn.setOnClickListener(v -> showPicker());
        editBtn.setOnClickListener(v -> toggleEdit());

        // body: joystick | keys | touchpad
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout wasd = new LinearLayout(this);
        wasd.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = new LinearLayout(this);
        LinearLayout r2 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        View sp = new View(this);
        r1.addView(sp, new LinearLayout.LayoutParams(pdp(50), pdp(46)));
        r1.addView(holdKey("W"));
        r2.addView(holdKey("A"));
        r2.addView(holdKey("S"));
        r2.addView(holdKey("D"));
        wasd.addView(r1);
        wasd.addView(r2);
        wasd.setPadding(0, pdp(6), 0, 0);
        body.addView(wasd);

        keysBox = new LinearLayout(this);
        keysBox.setOrientation(LinearLayout.VERTICAL);
        keysBox.setPadding(pdp(6), pdp(6), pdp(6), 0);
        body.addView(keysBox);
        rebuildKeys();

        View pad = new View(this);
        pad.setBackground(rounded(Color.argb(110, 255, 255, 255), 10));
        final float[] last = new float[2];
        final long[] downT = new long[1];
        final float[] moved = new float[1];
        pad.setOnTouchListener((v, e) -> {
            boolean look = lookMode();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    last[0] = e.getRawX(); last[1] = e.getRawY();
                    downT[0] = System.currentTimeMillis();
                    moved[0] = 0;
                    if (look) lookStart();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - last[0], dy = e.getRawY() - last[1];
                    moved[0] += Math.abs(dx) + Math.abs(dy);
                    last[0] = e.getRawX(); last[1] = e.getRawY();
                    if (look) lookMove(dx, dy);
                    else moveCursor(cx + dx * 2.2f, cy + dy * 2.2f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (look) {
                        lookEnd();
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                            && moved[0] < pdp(8) && System.currentTimeMillis() - downT[0] < 300) {
                        tapAt(cx, cy);
                    }
                    break;
                default:
            }
            return true;
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(pdp(120), pdp(96));
        pl.topMargin = pdp(6);
        body.addView(pad, pl);

        panel.addView(body);

        if (panelLp == null) {
            panelLp = lp(true);
            int[] s = screen();
            panelLp.x = dp(8);
            panelLp.y = Math.max(0, s[1] - dp(190));
        }
    }

    // ---------------------------------------------------------------- keys

    private List<String> selected() {
        List<String> out = new ArrayList<>();
        for (String k : prefs.getString("keys", "E").split(",")) {
            k = k.trim().toUpperCase();
            if (k.length() == 1 && Character.isLetter(k.charAt(0))
                    && !"WASD".contains(k) && !out.contains(k)) out.add(k);
        }
        return out;
    }

    private void saveSelected(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String k : l) { if (sb.length() > 0) sb.append(','); sb.append(k); }
        prefs.edit().putString("keys", sb.toString()).apply();
    }

    private boolean isSet(String k) { return prefs.contains("kx_" + k); }

    private void rebuildKeys() {
        keysBox.removeAllViews();
        LinearLayout row = null;
        int n = 0;
        for (final String k : selected()) {
            if (n % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                keysBox.addView(row);
            }
            Button b = headBtn(label(k));
            b.setTextSize(15 * ps());
            b.setBackground(rounded(isSet(k) ? Color.argb(220, 30, 120, 50)
                    : Color.argb(220, 160, 90, 0), 10));
            b.setOnClickListener(v -> {
                if (!isSet(k)) {
                    Toast.makeText(this, "Belum diatur. Tekan ✎ lalu geser tanda " + k
                            + " ke tombol di game", Toast.LENGTH_LONG).show();
                    return;
                }
                int[] s = screen();
                tapAt(s[0] * prefs.getInt("kx_" + k, 0) / 1000f,
                        s[1] * prefs.getInt("ky_" + k, 0) / 1000f);
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(pdp(54), pdp(46));
            l.setMargins(pdp(2), pdp(2), pdp(2), pdp(2));
            row.addView(b, l);
            n++;
        }
    }

    private String label(String k) {
        String base = "E".equals(k) ? HAND + "E" : k;
        return isSet(k) ? base : base + "?";
    }

    private void showPicker() {
        removeView(picker);
        picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.VERTICAL);
        picker.setPadding(dp(10), dp(10), dp(10), dp(10));
        picker.setBackground(rounded(Color.argb(235, 20, 20, 28), 14));
        TextView t = new TextView(this);
        t.setText("Pilih huruf tambahan");
        t.setTextColor(Color.WHITE);
        picker.addView(t);
        final List<String> sel = selected();
        String letters = "BCEFGHIJKLMNOPQRTUVXYZ";
        LinearLayout row = null;
        for (int i = 0; i < letters.length(); i++) {
            if (i % 8 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                picker.addView(row);
            }
            final String k = String.valueOf(letters.charAt(i));
            final Button b = headBtn(k);
            b.setBackground(rounded(sel.contains(k) ? Color.rgb(30, 120, 50)
                    : Color.rgb(70, 70, 80), 8));
            b.setOnClickListener(v -> {
                if (sel.contains(k)) sel.remove(k); else sel.add(k);
                b.setBackground(rounded(sel.contains(k) ? Color.rgb(30, 120, 50)
                        : Color.rgb(70, 70, 80), 8));
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(40), dp(40));
            l.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(b, l);
        }
        Button ok = headBtn("OK");
        ok.setOnClickListener(v -> {
            saveSelected(sel);
            removeView(picker);
            rebuildKeys();
            if (editMode) { hideMarkers(); showMarkers(); }
        });
        picker.addView(ok);
        WindowManager.LayoutParams l = lp(true);
        l.gravity = Gravity.CENTER;
        wm.addView(picker, l);
    }

    // ---------------------------------------------------------------- edit mode (markers)

    private void toggleEdit() {
        editMode = !editMode;
        editBtn.setText(editMode ? "✔" : "✎");
        sizeRow.setVisibility(editMode ? View.VISIBLE : View.GONE);
        selMarker = null;
        if (editMode) {
            removeView(eBubble);
            showMarkers();
            Toast.makeText(this, "Geser tanda ke tombol game. Ketuk tanda lalu Tanda -/+ untuk ukuran. Selesai: ✔",
                    Toast.LENGTH_LONG).show();
        } else {
            hideMarkers();
            rebuildKeys();
            buildEBubble();
            wm.addView(eBubble, eLp);
            startPolling();
        }
    }

    private void rebuildPanel() {
        boolean att = panel != null && panel.isAttachedToWindow();
        removeView(panel);
        buildPanel();
        if (att) wm.addView(panel, panelLp);
    }

    private void resizePanel(int delta) {
        int n = Math.max(60, Math.min(160, prefs.getInt("ps", 100) + delta));
        prefs.edit().putInt("ps", n).apply();
        rebuildPanel();
        Toast.makeText(this, "Ukuran panel " + n + "%", Toast.LENGTH_SHORT).show();
    }

    private void resizeMarker(int deltaDp) {
        View v = selMarker == null ? null : markers.get(selMarker);
        WindowManager.LayoutParams l = selMarker == null ? null : markerLp.get(selMarker);
        if (v == null || l == null) {
            Toast.makeText(this, "Ketuk dulu satu tanda di layar", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean j = "JOY".equals(selMarker) || "LOOK".equals(selMarker);
        int cur = prefs.getInt("sz_" + selMarker,
                "CROSS".equals(selMarker) ? 44 : j ? 64 : 52);
        int n = Math.max(28, Math.min(160, cur + deltaDp));
        float centerX = l.x + l.width / 2f, centerY = l.y + l.height / 2f;
        l.width = dp(n);
        l.height = dp(n);
        l.x = (int) (centerX - l.width / 2f);
        l.y = (int) (centerY - l.height / 2f);
        ((TextView) v).setTextSize(n * 0.3f);
        wm.updateViewLayout(v, l);
        prefs.edit().putInt("sz_" + selMarker, n).apply();
    }

    private void applyVisibility() {
        boolean pad = prefs.getBoolean("pad", false);
        boolean eo = prefs.getBoolean("eonly", false);
        if (pad) {
            if (!panel.isAttachedToWindow()) wm.addView(panel, panelLp);
            if (!cursor.isAttachedToWindow()) wm.addView(cursor, cursorLp);
        } else {
            if (!eEditOn) hideMarkers();
            editMode = false;
            if (editBtn != null) editBtn.setText("\u270E");
            removeView(picker);
            removeView(panel);
            removeView(cursor);
        }
        if ((pad || eo) && !editMode && !eEditOn) {
            if (!eBubble.isAttachedToWindow()) wm.addView(eBubble, eLp);
            startPolling();
        } else {
            removeView(eBubble);
        }
    }

    // ---------------------------------------------------------------- "E only" mode (no keyboard)

    private boolean eEditOn = false;
    private LinearLayout eBar;
    private WindowManager.LayoutParams eBarLp;

    private void enterEEdit() {
        if (eEditOn || wm == null) return;
        eEditOn = true;
        removeView(eBubble);
        hideMarkers();
        addMarker("E", 0);
        addMarker("CROSS");
        eBar = new LinearLayout(this);
        eBar.setOrientation(LinearLayout.HORIZONTAL);
        eBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        eBar.setBackground(rounded(Color.argb(200, 0, 0, 0), 12));
        Button m = headBtn("Tanda \u2212");
        Button pl = headBtn("Tanda +");
        Button ok = headBtn("\u2714 Selesai");
        m.setOnClickListener(v -> resizeMarker(-6));
        pl.setOnClickListener(v -> resizeMarker(6));
        ok.setOnClickListener(v -> prefs.edit().putBoolean("eedit", false).apply());
        eBar.addView(m);
        eBar.addView(pl);
        eBar.addView(ok);
        eBarLp = lp(true);
        eBarLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        eBarLp.y = dp(12);
        wm.addView(eBar, eBarLp);
        Toast.makeText(this, "Geser \uD83D\uDD90E ke tombol tangan game, dan CROSS ke lingkaran bidik. Lalu tekan Selesai",
                Toast.LENGTH_LONG).show();
    }

    private void exitEEdit() {
        if (!eEditOn) return;
        eEditOn = false;
        hideMarkers();
        removeView(eBar);
        buildEBubble();
        applyVisibility();
        rebuildKeys();
    }

    private void hideMarkers() {
        for (View v : markers.values()) removeView(v);
        markers.clear();
        markerLp.clear();
        selMarker = null;
    }

    private void showMarkers() {
        hideMarkers();
        addMarker("JOY");
        addMarker("LOOK");
        addMarker("CROSS");
        int i = 0;
        for (String k : selected()) addMarker(k, i++);
    }

    private void addMarker(String id) { addMarker(id, 0); }

    private boolean markerSet(String id) {
        if ("JOY".equals(id)) return prefs.contains("jx");
        if ("LOOK".equals(id)) return prefs.contains("lx");
        if ("CROSS".equals(id)) return prefs.contains("bx");
        return isSet(id);
    }

    private void selectMarker(String id) {
        selMarker = id;
        for (Map.Entry<String, View> en : markers.entrySet()) {
            markerBg((TextView) en.getValue(), en.getKey());
        }
    }

    private void addMarker(final String id, int order) {
        final boolean look = "LOOK".equals(id);
        final boolean cross = "CROSS".equals(id);
        final boolean joy = "JOY".equals(id) || look || cross;
        final int sizeDp = prefs.getInt("sz_" + id, cross ? 44 : joy ? 64 : 52);
        final int size = dp(sizeDp);
        final int[] s = screen();
        float px, py;
        if (cross) {
            px = s[0] * prefs.getInt("bx", 50) / 100f;
            py = s[1] * prefs.getInt("by", 50) / 100f;
        } else if (look) {
            px = lookPX(); py = lookPY();
        } else if (joy) {
            px = joyX(); py = joyY();
        } else if (isSet(id)) {
            px = s[0] * prefs.getInt("kx_" + id, 0) / 1000f;
            py = s[1] * prefs.getInt("ky_" + id, 0) / 1000f;
        } else {
            px = s[0] * 0.5f + order * dp(60);
            py = s[1] * 0.3f;
        }
        final TextView m = new TextView(this);
        m.setGravity(Gravity.CENTER);
        m.setTextColor(Color.WHITE);
        m.setTextSize(sizeDp * 0.3f);
        m.setText(cross ? "E" : look ? "\uD83D\uDC41" : joy ? JOY : ("E".equals(id) ? HAND + "E" : id));

        final WindowManager.LayoutParams l = lp(true);
        l.width = size;
        l.height = size;
        l.x = (int) px - size / 2;
        l.y = (int) py - size / 2;

        final float[] st = new float[5];
        m.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = e.getRawY();
                    st[2] = l.x; st[3] = l.y; st[4] = 0;
                    selectMarker(id);
                    break;
                case MotionEvent.ACTION_MOVE:
                    st[4] += Math.abs(e.getRawX() - st[0]) + Math.abs(e.getRawY() - st[1]) > 0 ? 1 : 0;
                    l.x = (int) (st[2] + e.getRawX() - st[0]);
                    l.y = (int) (st[3] + e.getRawY() - st[1]);
                    wm.updateViewLayout(m, l);
                    break;
                case MotionEvent.ACTION_UP:
                    float moved = Math.abs(l.x - st[2]) + Math.abs(l.y - st[3]);
                    if (moved > dp(6) || markerSet(id)) {
                        saveMarker(id, l.x + l.width / 2f, l.y + l.height / 2f);
                        markerBg(m, id);
                    }
                    break;
                default:
            }
            return true;
        });
        wm.addView(m, l);
        markers.put(id, m);
        markerLp.put(id, l);
        markerBg(m, id);
    }

    /** Green = placed, orange = not placed yet. Yellow frame = selected. */
    private void markerBg(TextView m, String id) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(markerSet(id) ? Color.argb(140, 30, 170, 70) : Color.argb(140, 240, 130, 0));
        boolean sel = id.equals(selMarker);
        g.setStroke(dp(sel ? 4 : 2), sel ? Color.YELLOW : Color.WHITE);
        m.setBackground(g);
    }

    private void saveMarker(String id, float x, float y) {
        int[] s = screen();
        if ("CROSS".equals(id)) {
            prefs.edit().putInt("bx", Math.round(x * 100f / s[0]))
                    .putInt("by", Math.round(y * 100f / s[1])).apply();
        } else if ("LOOK".equals(id)) {
            prefs.edit().putInt("lx", Math.round(x * 100f / s[0]))
                    .putInt("ly", Math.round(y * 100f / s[1])).apply();
        } else if ("JOY".equals(id)) {
            prefs.edit().putInt("jx", Math.round(x * 100f / s[0]))
                    .putInt("jy", Math.round(y * 100f / s[1])).apply();
        } else {
            prefs.edit().putInt("kx_" + id, Math.round(x * 1000f / s[0]))
                    .putInt("ky_" + id, Math.round(y * 1000f / s[1])).apply();
        }
    }
}

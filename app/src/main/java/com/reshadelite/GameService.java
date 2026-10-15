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
    private final AudioTrack[] clicks = new AudioTrack[8];
    private int clickIdx = 0;
    private final java.util.Random rnd = new java.util.Random();
    private long lastClick = 0;

    // Real click recordings (22.05 kHz, 16-bit mono) taken from the reference video.
    private static final String[] REAL_CLICKS = {
            "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"
    };

    private short[] realClick(int variant) {
        byte[] b = android.util.Base64.decode(REAL_CLICKS[variant % REAL_CLICKS.length],
                android.util.Base64.DEFAULT);
        short[] out = new short[b.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        }
        return out;
    }

    private short[] genClick(int style, int variant) {
        if (style == 3) return realClick(variant);
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
        int style = prefs.getInt("snd_style", 3);
        int rate = style == 3 ? 22050 : 44100;
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
                                .setSampleRate(rate)
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
        // random variant, never the same one twice in a row
        int pick = rnd.nextInt(clicks.length);
        if (pick == clickIdx) pick = (pick + 1 + rnd.nextInt(clicks.length - 1)) % clicks.length;
        clickIdx = pick;
        AudioTrack t = clicks[pick];
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

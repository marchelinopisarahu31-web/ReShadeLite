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
    private final AudioTrack[] clicks = new AudioTrack[10];
    private int clickIdx = 0;
    private final java.util.Random rnd = new java.util.Random();
    private long lastClick = 0;

    // Real click recordings (22.05 kHz, 16-bit mono) taken from the reference video.

    private short[] realClick(int variant) {
        byte[] b = android.util.Base64.decode(ClickData.REAL_CLICKS[variant % ClickData.REAL_CLICKS.length],
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
        int rate = 44100;
        for (int i = 0; i < clicks.length; i++) {
            try {
                if (clicks[i] != null) clicks[i].release();
                short[] data = genClick(style, i);
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
            t.setVolume(Math.max(0f, Math.min(1f, prefs.getInt("tapvol", 100) / 100f)));
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
package com.reshadelite;

final class ClickData {
    private ClickData() {}

    static final String[] REAL_CLICKS = {
            "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NABGAFgAZABsAHUAfACCAIUAhQCFAIkAjACMAIcAfABqAFYAQwAtABcABQDz/97/yf+2/6H/jv98/2z/Yv9f/13/W/9d/2H/Y/9j/2P/Yv9i/2L/Yv9i/2X/av9z/3v/g/+K/43/jv+N/4r/hv+D/4D/gP+D/4b/i/+U/6D/rv+8/87/4v/7/xgANQBRAGsAgQCTAKEArwC3AL4AxwDQANgA3wDjAOYA5gDgANQAxgC1AKEAiQBvAFQANQAcAAQA7//b/8//yf/H/8X/xf/F/8f/yP/J/8n/yP/H/8j/y//O/9D/0//W/9r/3v/k/+n/8P/3//z/AAAFAA0AFwAfACcAMQA/AE4AXQBrAH0AkAClALcAwwDNANIA1QDVANEAygDCALsAswCoAJgAiAB2AGMATgA4ACIADAD1/97/xf+u/5v/iv97/3D/aP9j/2P/aP9p/2n/av9t/23/bf9q/2b/Yv9f/13/WP9W/1T/U/9R/1D/UP9R/1P/Uf9T/1b/Xf9j/2r/dP9//47/of+5/9L/7P8GACMAPwBcAHYAjACeAKwAugDGANIA3QDnAO4A8gDzAPEA6ADfANQAwwCxAJ0AhQBsAFQAPAAmABQABgD7//P/7f/o/+L/3v/b/9f/0v/M/8f/xP/A/7z/uf+3/7n/uf+3/7X/sv+x/67/rv+r/6j/p/+r/7H/t/++/8X/0P/e//D/BAAYAC4ARwBfAHMAhACSAJgAngCjAKUApQCiAJ8AmgCSAIUAdgBlAFIAPgAnAA8A9//d/8T/rP+W/4P/c/9m/17/W/9b/17/X/9f/2H/Y/9l/2X/Yv9h/17/Xf9Y/1P/T/9N/0n/Rf9C/z3/N/8z/y//LP8s/y7/L/8y/zr/Qv9M/1v/bf+A/5j/sf/I/97/9P8IABgAJwA1AEAATQBYAGMAbAB1AHkAeQB2AHEAagBhAFQAQgAtABgAAgDv/9r/xf+z/6T/mf+P/4n/g/9//3n/df9x/23/af9m/2X/Zv9q/27/df9+/4f/j/+Y/6D/p/+u/7X/vP/C/8v/0//e/+n/8//+/wsAGgAsAD8AUgBlAHcAiACWAJ8ApgCpAKkAqACmAKMAnwCYAJAAhwB6AGsAWQBHADQAHgAJAPP/2v/C/6z/mf+H/3j/bv9o/2X/Zf9p/23/cf90/3f/ef98/4D/gv+C/4P/hv+J/4v/jv+R/5T/mP+c/6D/pv+q/6//uf/C/8z/1//k//P/BQAcADQATwBsAIsAqQDGAOAA9gAIARkBJQEwAToBQwFMAVMBVwFYAVcBUwFMAUIBNAEmARQBAwHuANkAxgC0AKIAkwCIAH4AdwBxAGsAZABcAFQASgBAADgALQAjABoAEQAJAAEA+//1//D/7P/o/+X/5P/i/+T/5f/p//D/+P8AAAsAGAAmADQARABZAHEAhQCYAKkAtwDAAMcAywDOAM4AygDHAMIAuwCxAKYAmgCJAHcAYwBLADIAGgABAOn/0v+8/6f/lv+K/4P/gP+D/4b/i/+R/5b/nP+h/6T/p/+r/67/r/+s/6v/q/+q/6j/p/+k/5//mP+S/43/h/+A/3z/ef94/3j/fP+D/4v/mP+q/8H/2v/0/w0AJwA+AFEAYwByAH0AhwCPAJYAnQCjAKgApgCiAJ0AlACIAHcAZQBSADwAJgAPAPj/4v/O/73/sv+r/6b/of+g/5//nf+c/5z/m/+b/5z/n/+g/6H/of+j/6b/qP+r/6//sf+y/7X/uf+9/8L/y//S/9v/6P/0////DAAcAC0AQABWAGwAgACSAKEArQC3ALwAwADCAL8AvAC4ALEAqACbAIwAegBkAE0ANAAYAP7/4f/C/6b/if9u/1f/RP81/yv/Jf8g/xz/Gf8V/xL/Ef8P/w7/Df8L/wv/C/8N/w//Ef8V/xz/IP8j/yf/Kv8s/y//Nf86/0H/Sf9U/2H/cP+A/5X/r//L/+n/BgAlAEAAWQBuAIEAkwCiALAAuwDHANQA3wDnAO0A7wDvAOoA4wDYAMkAtQCiAIwAdQBcAEQALgAcABAABgAAAPv/9P/t/+b/3//Z/9D/yP/A/7r/tf+u/6f/o/+f/5n/lv+V/5T/lP+V/5b/mf+d/6T/q/+z/73/xf/Q/93/7P/+/xEAJwA/AFIAZABzAIAAhwCMAI8AjACJAIQAfgB2AGwAYwBWAEcANwAlABMA///t/9r/xf+y/6H/lP+H/3//e/98/4D/h/+K/4//lf+Z/5//pP+m/6f/qv+r/6v/qv+r/6r/p/+m/6P/n/+Z/5T/jv+K/4f/hv+E/4b/iv+R/5v/qP+2/8n/4f/7/xMALABCAFUAZQB1AIAAiwCWAJ0AowCsALEAswC0ALAAqACbAI8AfgBqAFUAPwAnABAA+v/k/9D/wv+5/7P/r/+s/6z/q/+q/6j/p/+k/6D/nf+b/5j/lP+S/5L/kf+R/5L/kv+S/5T/lf+W/5n/nP+h/6j/r/+3/8H/zP/a/+r///8UAC4ASgBjAHoAjwCfAK0AuAC/AMIAxQDFAMIAvgC3AK0AogCUAIIAbwBaAEIAKQAQAPf/3v/F/67/mf+J/3//eP91/3X/df91/3X/d/94/3v/fv9+/3//gv+D/4T/hv+H/4f/h/+J/4f/hP+E/4T/hP+H/4v/kf+W/6H/rP+5/8n/3f/0/w8ALABJAGUAgACYALAAxQDWAOcA+AAFARMBIQErATMBOgE7ATgBMQEmARkBBwHzAN8AxwCwAJgAggBuAFwATQBAADgALgAlABoAEQAIAP7/9f/s/+L/2v/T/8z/x//E/8H/vv+9/7z/uv+5/7r/uv+9/8D/wv/H/8z/1P/d/+j/8/8BABQAKgBAAFgAbgCAAI0AmgClAKoArQCtAKwAqQClAKEAmACNAIQAdgBkAFQAQAAqABYAAgDs/9T/wf+u/53/kf+K/4P/g/+E/4T/g/+E/4T/hP+E/4P/gv9//37/fP97/3v/e/94/3f/eP93/3f/d/94/3n/fv+D/4r/kv+c/6r/t//H/9n/7f8CABsANABKAF8AbwB+AIkAlACdAKIAqACqAK0ArwCtAKkAogCXAIkAfABrAFkARgA0ACIADwD//+//3//W/9D/z//P/9D/0P/Q/8//z//P/87/yf/I/8j/xf/C/8L/wv/C/8L/wv/C/8L/wv/E/8X/yP/O/9T/2//k/+//+/8GABYAJwA8AFUAbgCFAJkAqQC4AMIAyQDLAMwAyQDEAL0AtwCsAJ0AjwB+AGgAUwA8ACIABwDu/9T/u/+i/4v/dv9m/1j/Uf9N/0v/S/9Q/1H/U/9W/1n/XP9e/2H/Yv9k/2f/av9s/2//cv90/3P/c/90/3H/cv91/3f/ff+D/4z/lf+j/7D/vv/T/+r/AgAcADcAUQBpAIEAlAClALYAwgDOANkA4wDqAO0A8QDvAOsA5QDdANEAwgCyAKEAkAB9AGsAWgBIADkALQAjABwAFwASAA0ACgAGAAIA/v/6//f/9P/x/+3/6//o/+T/4//h/93/2//a/9j/1f/U/9L/0f/S/9L/0//V/9n/3v/l/+3/9/8CABAAHQAqADUAPQBDAEoATQBQAFMA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

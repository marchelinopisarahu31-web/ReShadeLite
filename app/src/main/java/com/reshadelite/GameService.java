package com.reshadelite;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
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
        cursorSize = dp(22);
        buildCursor();
        buildPanel();
        applyVisibility();
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
        if (editMode) { hideMarkers(); showMarkers(); }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if ("pad".equals(key)) {
            applyVisibility();
            ui.postDelayed(this::resetPositions, 200);
        }
    }

    @Override
    public void onDestroy() {
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        hideMarkers();
        removeView(picker);
        removeView(panel);
        removeView(cursor);
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

    private int[] screen() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
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

    /** Letter button that stays pressed while the finger is on it. */
    private Button holdKey(final String k) {
        final Button b = headBtn(k);
        b.setTextSize(17);
        b.setBackground(rounded(Color.argb(220, 60, 60, 80), 10));
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            boolean down;
            if (a == MotionEvent.ACTION_DOWN) down = true;
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) down = false;
            else return true;
            b.setBackground(rounded(down ? Color.argb(240, 255, 120, 0)
                    : Color.argb(220, 60, 60, 80), 10));
            if (k.equals("W")) kw = down;
            else if (k.equals("A")) ka = down;
            else if (k.equals("S")) ks = down;
            else kd = down;
            float dx = (kd ? 1 : 0) - (ka ? 1 : 0);
            float dy = (ks ? 1 : 0) - (kw ? 1 : 0);
            float len = (float) Math.hypot(dx, dy);
            if (len > 0) { dx /= len; dy /= len; }
            setJoy(dx, dy);
            return true;
        });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(50), dp(46));
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
            js = seg(joyStroke, sx, sy, tx, ty, true, 110);
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
            ls = seg(lookStroke, sx, sy, ltx, lty, true, 110);
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
                pump();
            }
            @Override public void onCancelled(GestureDescription g) {
                if (my != gen) return;
                inFlight = false;
                joyStroke = null;
                lookStroke = null;
                pump();
            }
        }, ui);
        if (!ok) {
            inFlight = false;
            joyStroke = null;
            lookStroke = null;
        }
    }

    private void tapAt(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        final int my = ++gen;
        joyStroke = null;
        lookStroke = null;
        inFlight = true;
        boolean ok = dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50)).build(),
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) {
                        if (my != gen) return;
                        inFlight = false;
                        pump();
                    }
                    @Override public void onCancelled(GestureDescription g) {
                        if (my != gen) return;
                        inFlight = false;
                        pump();
                    }
                }, ui);
        if (!ok) inFlight = false;
    }

    // ---------------------------------------------------------------- camera

    private float lookPX() { return screen()[0] * prefs.getInt("lx", 70) / 100f; }
    private float lookPY() { return screen()[1] * prefs.getInt("ly", 50) / 100f; }
    private boolean lookMode() { return prefs.getBoolean("look", true); }
    private int sens() { return prefs.getInt("sens", 4); }

    private void lookStart() {
        lookDown = true;
        lookWrap = false;
        ltx = lookPX();
        lty = lookPY();
    }

    private void lookMove(float dx, float dy) {
        int[] s = screen();
        float gain = sens() * 0.6f;
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
        b.setTextSize(13);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setBackground(rounded(Color.argb(200, 60, 60, 70), 8));
        return b;
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));
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
                panelLp.x = (int) Math.max(0, Math.min(s[0] - dp(60), down[2] + e.getRawX() - down[0]));
                panelLp.y = (int) Math.max(0, Math.min(s[1] - dp(60), down[3] + e.getRawY() - down[1]));
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
        r1.addView(sp, new LinearLayout.LayoutParams(dp(50), dp(46)));
        r1.addView(holdKey("W"));
        r2.addView(holdKey("A"));
        r2.addView(holdKey("S"));
        r2.addView(holdKey("D"));
        wasd.addView(r1);
        wasd.addView(r2);
        wasd.setPadding(0, dp(6), 0, 0);
        body.addView(wasd);

        keysBox = new LinearLayout(this);
        keysBox.setOrientation(LinearLayout.VERTICAL);
        keysBox.setPadding(dp(6), dp(6), dp(6), 0);
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
                            && moved[0] < dp(8) && System.currentTimeMillis() - downT[0] < 300) {
                        tapAt(cx, cy);
                    }
                    break;
                default:
            }
            return true;
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(120), dp(96));
        pl.topMargin = dp(6);
        body.addView(pad, pl);

        panel.addView(body);

        panelLp = lp(true);
        int[] s = screen();
        panelLp.x = dp(8);
        panelLp.y = Math.max(0, s[1] - dp(190));
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
            b.setTextSize(15);
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
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(54), dp(46));
            l.setMargins(dp(2), dp(2), dp(2), dp(2));
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
        if (editMode) {
            showMarkers();
            Toast.makeText(this, "Geser tanda ke tombol di game, lalu tekan ✔",
                    Toast.LENGTH_LONG).show();
        } else {
            hideMarkers();
            rebuildKeys();
        }
    }

    private void applyVisibility() {
        boolean show = prefs.getBoolean("pad", false);
        if (show) {
            if (!panel.isAttachedToWindow()) wm.addView(panel, panelLp);
            if (!cursor.isAttachedToWindow()) wm.addView(cursor, cursorLp);
        } else {
            hideMarkers();
            editMode = false;
            if (editBtn != null) editBtn.setText("✎");
            removeView(picker);
            removeView(panel);
            removeView(cursor);
        }
    }

    private void hideMarkers() {
        for (View v : markers.values()) removeView(v);
        markers.clear();
    }

    private void showMarkers() {
        hideMarkers();
        addMarker("JOY");
        addMarker("LOOK");
        int i = 0;
        for (String k : selected()) addMarker(k, i++);
    }

    private void addMarker(String id) { addMarker(id, 0); }

    private void addMarker(final String id, int order) {
        final boolean look = "LOOK".equals(id);
        final boolean joy = "JOY".equals(id) || look;
        final int size = dp(joy ? 64 : 52);
        final int[] s = screen();
        float px, py;
        boolean set;
        if (look) {
            px = lookPX(); py = lookPY(); set = true;
        } else if (joy) {
            px = joyX(); py = joyY(); set = true;
        } else if (isSet(id)) {
            px = s[0] * prefs.getInt("kx_" + id, 0) / 1000f;
            py = s[1] * prefs.getInt("ky_" + id, 0) / 1000f;
            set = true;
        } else {
            px = s[0] * 0.5f + order * dp(60);
            py = s[1] * 0.3f;
            set = false;
        }
        final TextView m = new TextView(this);
        m.setGravity(Gravity.CENTER);
        m.setTextColor(Color.WHITE);
        m.setTextSize(joy ? 18 : 16);
        m.setText(look ? "\uD83D\uDC41" : joy ? JOY : ("E".equals(id) ? HAND + "E" : id));
        markerBg(m, joy || set, joy);

        final WindowManager.LayoutParams l = lp(true);
        l.width = size;
        l.height = size;
        l.x = (int) px - size / 2;
        l.y = (int) py - size / 2;

        final float[] st = new float[4];
        m.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = e.getRawY();
                    st[2] = l.x; st[3] = l.y;
                    break;
                case MotionEvent.ACTION_MOVE:
                    l.x = (int) (st[2] + e.getRawX() - st[0]);
                    l.y = (int) (st[3] + e.getRawY() - st[1]);
                    wm.updateViewLayout(m, l);
                    break;
                case MotionEvent.ACTION_UP:
                    saveMarker(id, l.x + size / 2f, l.y + size / 2f);
                    markerBg(m, true, joy);
                    break;
                default:
            }
            return true;
        });
        wm.addView(m, l);
        markers.put(id, m);
    }

    private void markerBg(TextView m, boolean set, boolean joy) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(joy ? Color.argb(190, 255, 120, 0)
                : set ? Color.argb(210, 30, 150, 60) : Color.argb(210, 230, 130, 0));
        g.setStroke(dp(2), Color.WHITE);
        m.setBackground(g);
    }

    private void saveMarker(String id, float x, float y) {
        int[] s = screen();
        if ("LOOK".equals(id)) {
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

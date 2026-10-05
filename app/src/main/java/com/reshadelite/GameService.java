package com.reshadelite;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashSet;
import java.util.Set;

public class GameService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String[] ROWS = {"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM", "1234567890"};
    private static final String ORDER = "QWERTYUIOPASDFGHJKLZXCVBNM1234567890";

    private WindowManager wm;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout panel;
    private LinearLayout keysBox;
    private TextView editBtn;
    private WindowManager.LayoutParams panelLp;
    private View cursor;
    private WindowManager.LayoutParams cursorLp;
    private View picker;
    private View capture;
    private int cursorSize;
    private float cx, cy;
    private boolean editMode;

    private boolean kw, ka, ks, kd;
    private boolean active;
    private float tx, ty;
    private GestureDescription.StrokeDescription stroke;
    private float lastX, lastY;
    private boolean inFlight;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        cursorSize = dp(28);
        int[] s = screen();
        cx = s[0] / 2f;
        cy = s[1] / 2f;
        buildCursor();
        buildPanel();
        prefs.registerOnSharedPreferenceChangeListener(this);
        applyVisibility();
    }

    // ---------- window helpers ----------

    private WindowManager.LayoutParams lp(boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }

    private WindowManager.LayoutParams lpFull() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }

    private void removeView(View v) {
        if (v == null) return;
        try { wm.removeView(v); } catch (Exception ignored) { }
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(color);
        return g;
    }

    private TextView headBtn(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        return t;
    }

    // ---------- cursor ----------

    private void buildCursor() {
        cursor = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(0x55FF0000);
        gd.setStroke(dp(2), Color.WHITE);
        cursor.setBackground(gd);
        cursorLp = lp(false);
        cursorLp.width = cursorSize;
        cursorLp.height = cursorSize;
        cursorLp.x = (int) (cx - cursorSize / 2f);
        cursorLp.y = (int) (cy - cursorSize / 2f);
        wm.addView(cursor, cursorLp);
    }

    private void moveCursor(float dx, float dy) {
        int[] s = screen();
        cx = Math.max(0, Math.min(s[0], cx + dx));
        cy = Math.max(0, Math.min(s[1], cy + dy));
        cursorLp.x = (int) (cx - cursorSize / 2f);
        cursorLp.y = (int) (cy - cursorSize / 2f);
        wm.updateViewLayout(cursor, cursorLp);
    }

    // ---------- panel ----------

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(4), dp(6), dp(6));
        panel.setBackground(rounded(0xAA000000));

        // baris atas: geser | pilih huruf | atur titik
        LinearLayout head = new LinearLayout(this);
        TextView handle = headBtn("\u2261 geser");
        handle.setTextSize(11);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            int sx, sy;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX();
                        dy = e.getRawY();
                        sx = panelLp.x;
                        sy = panelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = sx + (int) (e.getRawX() - dx);
                        panelLp.y = sy + (int) (e.getRawY() - dy);
                        wm.updateViewLayout(panel, panelLp);
                        return true;
                }
                return true;
            }
        });
        head.addView(handle, new LinearLayout.LayoutParams(0, dp(24), 1f));

        TextView pickBtn = headBtn("\u2699");
        pickBtn.setOnClickListener(v -> showPicker());
        head.addView(pickBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(24)));

        editBtn = headBtn("\u270E");
        editBtn.setOnClickListener(v -> toggleEdit());
        head.addView(editBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(24)));
        panel.addView(head);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        keysBox = new LinearLayout(this);
        keysBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(keysBox);

        TextView pad = new TextView(this);
        pad.setText("touchpad\n(ketuk = klik)");
        pad.setTextColor(0xCCFFFFFF);
        pad.setTextSize(11);
        pad.setGravity(Gravity.CENTER);
        pad.setBackground(rounded(0x55FFFFFF));
        pad.setOnTouchListener(new View.OnTouchListener() {
            float lx, ly, sx, sy;
            long t0;
            boolean moved;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lx = sx = e.getRawX();
                        ly = sy = e.getRawY();
                        t0 = e.getEventTime();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        moveCursor((e.getRawX() - lx) * 2.5f, (e.getRawY() - ly) * 2.5f);
                        lx = e.getRawX();
                        ly = e.getRawY();
                        if (Math.hypot(e.getRawX() - sx, e.getRawY() - sy) > dp(10)) moved = true;
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && e.getEventTime() - t0 < 250) tapAt(cx, cy);
                        return true;
                }
                return true;
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(110), dp(84));
        plp.setMargins(dp(8), dp(2), 0, 0);
        body.addView(pad, plp);
        panel.addView(body);

        panelLp = lp(true);
        int[] s = screen();
        panelLp.x = s[0] / 2 - dp(160);
        panelLp.y = s[1] - dp(200);
        wm.addView(panel, panelLp);

        rebuildKeys();
    }

    private void rebuildKeys() {
        keysBox.removeAllViews();
        Set<String> sel = selected();
        LinearLayout row = null;
        int n = 0;
        for (char c : ORDER.toCharArray()) {
            String k = String.valueOf(c);
            if (!sel.contains(k)) continue;
            if (n % 5 == 0) {
                row = new LinearLayout(this);
                keysBox.addView(row);
            }
            row.addView(key(k));
            n++;
        }
        if (n == 0) {
            TextView t = new TextView(this);
            t.setText("Tekan \u2699 untuk\npilih huruf");
            t.setTextColor(Color.WHITE);
            t.setTextSize(12);
            keysBox.addView(t);
        }
    }

    private TextView key(final String k) {
        final TextView t = new TextView(this);
        t.setText(k);
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(editMode ? 0xFF8D5A00 : 0xAA444444));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(40), dp(40));
        p.setMargins(dp(1), dp(1), dp(1), dp(1));
        t.setLayoutParams(p);
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (editMode) {
                        armCapture(k);
                        return true;
                    }
                    t.setAlpha(0.5f);
                    press(k, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    t.setAlpha(1f);
                    if (!editMode) press(k, false);
                    return true;
            }
            return true;
        });
        return t;
    }

    private void toggleEdit() {
        editMode = !editMode;
        editBtn.setTextColor(editMode ? 0xFFFFA000 : Color.WHITE);
        if (editMode) {
            Toast.makeText(this, "Mode atur titik: tekan huruf, lalu ketuk titiknya di layar game. "
                    + "Untuk joystick: tekan W.", Toast.LENGTH_LONG).show();
        }
        rebuildKeys();
    }

    private void applyVisibility() {
        int vis = prefs.getBoolean("pad", true) ? View.VISIBLE : View.GONE;
        if (panel != null) panel.setVisibility(vis);
        if (cursor != null) cursor.setVisibility(vis);
    }

    // ---------- pilih huruf ----------

    private Set<String> selected() {
        String s = prefs.getString("keys", "W,A,S,D,E");
        Set<String> out = new LinkedHashSet<>();
        for (String k : s.split(",")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    private void saveSelected(Set<String> set) {
        StringBuilder b = new StringBuilder();
        for (String k : set) {
            if (b.length() > 0) b.append(',');
            b.append(k);
        }
        prefs.edit().putString("keys", b.toString()).apply();
    }

    private void showPicker() {
        removeView(picker);
        final Set<String> sel = new LinkedHashSet<>(selected());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xEE000000);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView title = new TextView(this);
        title.setText("Pilih huruf yang mau dipakai (hijau = dipakai)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        for (String r : ROWS) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (char c : r.toCharArray()) {
                final String k = String.valueOf(c);
                final TextView b = new TextView(this);
                b.setText(k);
                b.setTextColor(Color.WHITE);
                b.setTextSize(18);
                b.setGravity(Gravity.CENTER);
                b.setBackground(rounded(sel.contains(k) ? 0xFF2E7D32 : 0xFF444444));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1f);
                p.setMargins(dp(2), dp(2), dp(2), dp(2));
                b.setOnClickListener(v -> {
                    if (sel.contains(k)) sel.remove(k); else sel.add(k);
                    b.setBackground(rounded(sel.contains(k) ? 0xFF2E7D32 : 0xFF444444));
                });
                row.addView(b, p);
            }
            root.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        TextView done = new TextView(this);
        done.setText("SELESAI");
        done.setTextColor(Color.WHITE);
        done.setTextSize(16);
        done.setGravity(Gravity.CENTER);
        done.setBackground(rounded(0xFF1565C0));
        done.setOnClickListener(v -> {
            saveSelected(sel);
            removeView(picker);
            picker = null;
            rebuildKeys();
        });
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        dp2.setMargins(dp(24), dp(16), dp(24), 0);
        root.addView(done, dp2);

        picker = root;
        wm.addView(root, lpFull());
    }

    // ---------- atur titik ----------

    private boolean isMove(String k) {
        return k.equals("W") || k.equals("A") || k.equals("S") || k.equals("D");
    }

    private void armCapture(final String k) {
        removeView(capture);
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(0x44000000);
        TextView t = new TextView(this);
        t.setText(isMove(k)
                ? "Ketuk titik TENGAH joystick di game\n(tap tulisan ini = batal)"
                : "Ketuk titik tombol '" + k + "' di layar game\n(tap tulisan ini = batal)");
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(0xCC000000);
        t.setPadding(dp(12), dp(40), dp(12), dp(12));
        t.setOnClickListener(v -> {
            removeView(capture);
            capture = null;
        });
        f.addView(t, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        f.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                saveCapture(k, e.getRawX(), e.getRawY());
                removeView(capture);
                capture = null;
            }
            return true;
        });
        capture = f;
        wm.addView(f, lpFull());
    }

    private void saveCapture(String k, float rx, float ry) {
        int[] s = screen();
        if (isMove(k)) {
            prefs.edit()
                    .putInt("jx", Math.round(rx * 100f / s[0]))
                    .putInt("jy", Math.round(ry * 100f / s[1]))
                    .apply();
            Toast.makeText(this, "Titik joystick tersimpan", Toast.LENGTH_SHORT).show();
        } else {
            prefs.edit()
                    .putInt("kx_" + k, Math.round(rx * 1000f / s[0]))
                    .putInt("ky_" + k, Math.round(ry * 1000f / s[1]))
                    .apply();
            Toast.makeText(this, "Titik " + k + " tersimpan", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- input ke game ----------

    private void press(String k, boolean down) {
        switch (k) {
            case "W": kw = down; break;
            case "A": ka = down; break;
            case "S": ks = down; break;
            case "D": kd = down; break;
            default:
                if (down) tapKey(k);
                return;
        }
        updateTarget();
        pump();
    }

    private void tapKey(String k) {
        int[] s = screen();
        if (prefs.contains("kx_" + k)) {
            tapAt(s[0] * prefs.getInt("kx_" + k, 0) / 1000f,
                    s[1] * prefs.getInt("ky_" + k, 0) / 1000f);
        } else if (k.equals("E")) {
            tapAt(s[0] * prefs.getInt("ex", 85) / 100f,
                    s[1] * prefs.getInt("ey", 60) / 100f);
        } else {
            Toast.makeText(this, "Atur titik " + k + " dulu (tombol \u270E)",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private float joyX() { return screen()[0] * prefs.getInt("jx", 15) / 100f; }
    private float joyY() { return screen()[1] * prefs.getInt("jy", 70) / 100f; }

    private void updateTarget() {
        int dx = (kd ? 1 : 0) - (ka ? 1 : 0);
        int dy = (ks ? 1 : 0) - (kw ? 1 : 0);
        if (dx == 0 && dy == 0) {
            active = false;
            return;
        }
        int[] s = screen();
        float r = Math.min(s[0], s[1]) * 0.10f;
        double len = Math.hypot(dx, dy);
        tx = (float) (joyX() + dx / len * r);
        ty = (float) (joyY() + dy / len * r);
        active = true;
    }

    private void pump() {
        if (inFlight) return;
        Path p = new Path();
        GestureDescription.StrokeDescription next;
        if (active) {
            if (stroke == null) {
                p.moveTo(joyX(), joyY());
                p.lineTo(tx, ty);
                next = new GestureDescription.StrokeDescription(p, 0, 80, true);
            } else {
                p.moveTo(lastX, lastY);
                p.lineTo(tx, ty);
                next = stroke.continueStroke(p, 0, 80, true);
            }
            lastX = tx;
            lastY = ty;
            stroke = next;
        } else if (stroke != null) {
            p.moveTo(lastX, lastY);
            next = stroke.continueStroke(p, 0, 20, false);
        } else {
            return;
        }
        final boolean end = !active;
        GestureDescription g = new GestureDescription.Builder().addStroke(next).build();
        inFlight = true;
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) {
                inFlight = false;
                if (end) stroke = null;
                pump();
            }
            @Override public void onCancelled(GestureDescription d) {
                inFlight = false;
                stroke = null;
                pump();
            }
        }, ui);
        if (!ok) {
            inFlight = false;
            stroke = null;
        }
    }

    private void tapAt(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50))
                .build(), null, null);
    }

    // ---------- util ----------

    private int[] screen() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if ("pad".equals(key)) applyVisibility();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        removeView(panel);
        removeView(cursor);
        removeView(picker);
        removeView(capture);
        super.onDestroy();
    }
                                           }

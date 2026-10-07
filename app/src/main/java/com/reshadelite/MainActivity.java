package com.reshadelite;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private final List<Runnable> syncers = new ArrayList<>();
    private Switch power;

    // name, brightness, warmth, tint, tintColor, vignette
    private static final Object[][] PRESETS = {
            {"Normal", 0, 0, 0, 0xFFFFFFFF, 0},
            {"Sinematik", -10, -25, 25, 0xFF1B6E8C, 55},
            {"Hangat", 0, 55, 0, 0xFFFFFFFF, 20},
            {"Malam", -30, 70, 0, 0xFFFFFFFF, 10},
            {"Horor", -25, -40, 30, 0xFF2E7D32, 80},
            {"Vivid Pink", 5, 10, 35, 0xFFFF4D8D, 25},
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);
        root.setBackgroundColor(Color.rgb(18, 18, 24));

        TextView title = text("ReShade Lite", 26, Color.WHITE);
        root.addView(title);
        root.addView(text("Filter warna layar untuk semua app & game", 13, Color.LTGRAY));

        power = new Switch(this);
        power.setText("Aktifkan filter");
        power.setTextColor(Color.WHITE);
        power.setTextSize(18);
        power.setPadding(0, pad, 0, pad);
        power.setChecked(prefs.getBoolean("on", false) && Settings.canDrawOverlays(this));
        power.setOnCheckedChangeListener((v, on) -> setEnabled(on));
        root.addView(power);

        root.addView(text("Preset", 16, Color.WHITE));
        LinearLayout row1 = new LinearLayout(this);
        LinearLayout row2 = new LinearLayout(this);
        for (int i = 0; i < PRESETS.length; i++) {
            final Object[] p = PRESETS[i];
            Button btn = new Button(this);
            btn.setText((String) p[0]);
            btn.setAllCaps(false);
            btn.setOnClickListener(v -> {
                prefs.edit()
                        .putInt("brightness", (int) p[1])
                        .putInt("warmth", (int) p[2])
                        .putInt("tint", (int) p[3])
                        .putInt("tintColor", (int) p[4])
                        .putInt("vignette", (int) p[5])
                        .apply();
                for (Runnable r : syncers) r.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            (i < 3 ? row1 : row2).addView(btn, lp);
        }
        root.addView(row1);
        root.addView(row2);

        root.addView(slider("Kecerahan", "brightness", -50, 50, 0));
        root.addView(slider("Hangat / Dingin", "warmth", -100, 100, 0));
        root.addView(slider("Kekuatan warna tint", "tint", 0, 100, 0));
        root.addView(slider("Vignette", "vignette", 0, 100, 0));

        // ---- Game pad (Accessibility) ----
        Button accBtn = new Button(this);
        accBtn.setText("1) Aktifkan layanan Game Pad (Aksesibilitas)");
        accBtn.setAllCaps(false);
        accBtn.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(20);
        root.addView(accBtn, alp);

        Switch padSw = new Switch(this);
        padSw.setText("2) Tampilkan game pad");
        padSw.setTextColor(Color.WHITE);
        padSw.setTextSize(16);
        padSw.setPadding(0, pad, 0, pad);
        padSw.setChecked(prefs.getBoolean("pad", false));
        padSw.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("pad", on).apply());
        root.addView(padSw);

        // ---- E button only (no keyboard) ----
        Switch eSw = new Switch(this);
        eSw.setText("3) Tombol E saja di lingkaran bidik (tanpa keyboard)");
        eSw.setTextColor(Color.WHITE);
        eSw.setTextSize(16);
        eSw.setPadding(0, pad, 0, pad);
        eSw.setChecked(prefs.getBoolean("eonly", false));
        eSw.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("eonly", on).apply());
        root.addView(eSw);

        Button eEdit = new Button(this);
        eEdit.setText("Atur posisi tombol E");
        eEdit.setAllCaps(false);
        eEdit.setOnClickListener(v -> {
            prefs.edit().putBoolean("eedit", true).apply();
            Toast.makeText(this, "Buka Granny. Geser tanda ke tombol tangan dan ke lingkaran bidik, lalu tekan Selesai",
                    Toast.LENGTH_LONG).show();
        });
        root.addView(eEdit);

        root.addView(slider("Joystick game - X (%)", "jx", 0, 100, 15));
        root.addView(slider("Joystick game - Y (%)", "jy", 0, 100, 70));

        root.addView(text("Warna tint", 16, Color.WHITE));
        LinearLayout colors = new LinearLayout(this);
        int[] swatches = {0xFFFF5078, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50,
                0xFF1B6E8C, 0xFF3F51B5, 0xFF9C27B0};
        for (int c : swatches) {
            View sw = new View(this);
            sw.setBackgroundColor(c);
            sw.setOnClickListener(v -> prefs.edit().putInt("tintColor", c).apply());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(3), dp(8), dp(3), dp(8));
            colors.addView(sw, lp);
        }
        root.addView(colors);

        TextView note = text("Catatan: ini overlay warna di atas layar (bukan shader asli seperti "
                + "ReShade di PC), jadi contrast/saturasi sungguhan tidak bisa diubah.", 12, Color.GRAY);
        note.setPadding(0, pad, 0, 0);
        root.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(18, 18, 24));
        sv.addView(root);
        setContentView(sv);
    }

    private void setEnabled(boolean on) {
        if (on && !Settings.canDrawOverlays(this)) {
            power.setChecked(false);
            Toast.makeText(this, "Izinkan 'Tampil di atas app lain' dulu", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        prefs.edit().putBoolean("on", on).apply();
        Intent svc = new Intent(this, OverlayService.class);
        if (on) startForegroundService(svc); else stopService(svc);
    }

    private View slider(String label, final String key, final int min, int max, int def) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, 0);

        final TextView tv = text("", 14, Color.WHITE);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);

        Runnable sync = () -> {
            int v = prefs.getInt(key, def);
            sb.setProgress(v - min);
            tv.setText(label + ": " + v);
        };
        syncers.add(sync);
        sync.run();

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                tv.setText(label + ": " + (p + min));
                if (user) prefs.edit().putInt(key, p + min).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        box.addView(tv);
        box.addView(sb);
        return box;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

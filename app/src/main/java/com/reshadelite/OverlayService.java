package com.reshadelite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;

public class OverlayService extends Service
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String PREFS = "filter";
    public static final String ACTION_TOGGLE = "com.reshadelite.TOGGLE";
    public static final String ACTION_STOP = "com.reshadelite.STOP";
    private static final String CHANNEL = "overlay";

    private WindowManager wm;
    private FilterView view;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        // setiap kali filter dinyalakan dari app, mulai dalam keadaan ON
        prefs.edit().putBoolean("visible", true).apply();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Filter aktif", NotificationManager.IMPORTANCE_LOW));

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }

        view = new FilterView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        wm.addView(view, lp);

        prefs.registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(action) && view != null) {
            boolean visible = !prefs.getBoolean("visible", true);
            prefs.edit().putBoolean("visible", visible).apply();
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
            getSystemService(NotificationManager.class).notify(1, buildNotification());
        } else if (ACTION_STOP.equals(action)) {
            prefs.edit().putBoolean("on", false).apply();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        boolean visible = prefs.getBoolean("visible", true);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        Icon icon = Icon.createWithResource(this, android.R.drawable.ic_menu_view);

        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(visible ? "Filter ON" : "Filter OFF")
                .setContentText("katz_oki reshade lite")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(icon,
                        visible ? "MATIKAN" : "NYALAKAN", toggle).build())
                .addAction(new Notification.Action.Builder(icon, "BUKA", open).build())
                .addAction(new Notification.Action.Builder(icon, "TUTUP", stop).build())
                .build();
    }

    private void refresh() {
        view.apply(
                prefs.getInt("brightness", 0),
                prefs.getInt("warmth", 0),
                prefs.getInt("tint", 0),
                prefs.getInt("tintColor", 0xFFFF5078),
                prefs.getInt("vignette", 0));
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (view != null) refresh();
    }

    @Override
    public void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (view != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
            view = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
                           }

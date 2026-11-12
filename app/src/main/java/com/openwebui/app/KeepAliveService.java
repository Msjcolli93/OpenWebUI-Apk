package com.openwebui.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** Keeps the app process alive while backgrounded (notifications, and the mic during call mode). */
public class KeepAliveService extends Service {
    public static volatile boolean mic = false;
    private static KeepAliveService inst;
    private View bubble;
    private WindowManager wm;

    /** Hide the floating bubble while the app itself is on screen. */
    public static void appVisible(boolean visible) {
        KeepAliveService s = inst;
        if (s != null && s.bubble != null) s.bubble.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    @Override public void onCreate() { super.onCreate(); inst = this; }

    @Override public void onDestroy() {
        removeBubble();
        if (inst == this) inst = null;
        super.onDestroy();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void removeBubble() {
        if (bubble != null && wm != null) { try { wm.removeView(bubble); } catch (Exception ignored) {} }
        bubble = null;
    }

    private void showBubble() {
        if (bubble != null) return;
        SharedPreferences p = getSharedPreferences("owui", MODE_PRIVATE);
        if (!p.getBoolean("bubble", false)) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            TextView t = new TextView(this);
            t.setText("\uD83D\uDCAC");
            t.setTextSize(24);
            t.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.parseColor("#E11D2E"));
            t.setBackground(g);
            t.setElevation(dp(6));
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(54), dp(54),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(8);
            lp.y = dp(220);
            final int[] st = new int[4];
            t.setOnTouchListener((v, e) -> {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        st[0] = (int) e.getRawX(); st[1] = (int) e.getRawY(); st[2] = lp.x; st[3] = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = st[2] + (int) e.getRawX() - st[0];
                        lp.y = st[3] + (int) e.getRawY() - st[1];
                        try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (Math.abs(e.getRawX() - st[0]) < dp(8) && Math.abs(e.getRawY() - st[1]) < dp(8)) {
                            startActivity(new Intent(this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                        }
                        return true;
                }
                return false;
            });
            bubble = t;
            wm.addView(bubble, lp);
        } catch (Exception e) {
            bubble = null;
        }
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("keepalive", "Background connection", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        boolean useMic = mic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        Notification n = new Notification.Builder(this, "keepalive")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Open WebUI")
            .setContentText(useMic ? "Call mode active (microphone on)" : "Watching for finished responses")
            .setContentIntent(pi).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (useMic) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(1, n, type);
        } else {
            startForeground(1, n);
        }
        showBubble();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }
}

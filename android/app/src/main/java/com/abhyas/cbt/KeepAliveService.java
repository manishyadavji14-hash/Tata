package com.abhyas.cbt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the app process (and its WebView) alive while background AI jobs run, so switching to another
 * app or turning the screen off does not stop them. Shows an ongoing notification with the progress.
 */
public class KeepAliveService extends Service {
    static final String CHANNEL = "abhyas_jobs";
    static final String CHANNEL_DONE = "abhyas_jobs_done";
    static final int NID = 4107;
    static volatile boolean running = false;
    private PowerManager.WakeLock wakeLock;

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "AI काम (चालू)", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "AI काम पूरे", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    static PendingIntent openJobs(Context c, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.putExtra("abhyas_open", "jobs");
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("deprecation")
    static Notification build(Context c, int count, String text) {
        channels(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(count + " AI काम चल रहे हैं — Abhyas CBT")
                .setContentText(text == null || text.isEmpty() ? "बैकग्राउंड में काम जारी है" : text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openJobs(c, 1))
                .build();
    }

    static void update(Context c, int count, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NID, build(c, count, text));
    }

    @SuppressWarnings("deprecation")
    static void notifyDone(Context c, String title, String text) {
        channels(c);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL_DONE) : new Notification.Builder(c);
        Notification n = b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("✅ पूरा: " + title)
                .setContentText(text + " — देखें और सेव करें")
                .setAutoCancel(true)
                .setContentIntent(openJobs(c, 2))
                .build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int) (System.currentTimeMillis() % 100000) + 5000, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int count = intent == null ? 1 : intent.getIntExtra("count", 1);
        String text = intent == null ? "" : intent.getStringExtra("text");
        Notification n = build(this, count, text);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NID, n);
        running = true;
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "abhyas:jobs");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(4 * 60 * 60 * 1000L);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

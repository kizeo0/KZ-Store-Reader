package com.kizeo.kzstorereader.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.kizeo.kzstorereader.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servicio en primer plano para descargas en segundo plano.
 * Corregido: la cancelación ahora detiene inmediatamente la descarga
 * y elimina el archivo parcial para evitar basura.
 */
public class DownloadService extends Service {

    private static final String TAG = "DownloadService";

    public static final String ACTION_START  = "com.kizeo.kzstorereader.DL_START";
    public static final String ACTION_PAUSE  = "com.kizeo.kzstorereader.DL_PAUSE";
    public static final String ACTION_RESUME = "com.kizeo.kzstorereader.DL_RESUME";
    public static final String ACTION_CANCEL = "com.kizeo.kzstorereader.DL_CANCEL";

    public static final String EXTRA_URL      = "url";
    public static final String EXTRA_FILENAME = "filename";
    public static final String EXTRA_TASK_ID  = "task_id";

    private static final String CHANNEL_ID = "kz_downloads";
    private static final int    NOTIF_IDLE = 1999;
    private static final int    NOTIF_BASE = 2000;

    public interface Callback {
        void onProgress(int taskId, int progress, String status, String speed);
        void onPaused(int taskId);
        void onResumed(int taskId);
        void onDone(int taskId, String filename);
        void onError(int taskId, String error);
    }

    private static final ConcurrentHashMap<Integer, Callback> CALLBACKS = new ConcurrentHashMap<>();

    public static void register(int taskId, Callback cb)   { CALLBACKS.put(taskId, cb); }
    public static void unregister(int taskId)               { CALLBACKS.remove(taskId); }

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler         uiHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<Integer, AtomicBoolean> cancelMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicBoolean> pauseMap  = new ConcurrentHashMap<>();
    private final AtomicInteger activeCount = new AtomicInteger(0);

    private NotificationManager notifMgr;
    private static final AtomicInteger ID_GEN = new AtomicInteger(0);
    public static int nextTaskId() { return ID_GEN.incrementAndGet(); }

    @Override
    public void onCreate() {
        super.onCreate();
        notifMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        startForeground(NOTIF_IDLE, buildIdleNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;

        int    taskId = intent.getIntExtra(EXTRA_TASK_ID, -1);
        String action = intent.getAction();

        switch (action) {
            case ACTION_START: {
                String url      = intent.getStringExtra(EXTRA_URL);
                String filename = intent.getStringExtra(EXTRA_FILENAME);
                if (url != null && filename != null && taskId >= 0)
                    enqueueTask(url, filename, taskId);
                break;
            }
            case ACTION_PAUSE:
                if (pauseMap.containsKey(taskId)) {
                    pauseMap.get(taskId).set(true);
                    notifyCallback(taskId, cb -> cb.onPaused(taskId));
                }
                break;
            case ACTION_RESUME:
                if (pauseMap.containsKey(taskId)) {
                    pauseMap.get(taskId).set(false);
                    notifyCallback(taskId, cb -> cb.onResumed(taskId));
                }
                break;
            case ACTION_CANCEL:
                if (cancelMap.containsKey(taskId))
                    cancelMap.get(taskId).set(true);
                break;
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void enqueueTask(String url, String filename, int taskId) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean paused    = new AtomicBoolean(false);
        cancelMap.put(taskId, cancelled);
        pauseMap.put(taskId, paused);
        activeCount.incrementAndGet();
        executor.execute(() -> runDownload(url, filename, taskId, cancelled, paused));
    }

    private void runDownload(String url, String filename, int taskId,
                             AtomicBoolean cancelled, AtomicBoolean paused) {
        int notifId = NOTIF_BASE + taskId;
        updateNotif(notifId, taskId, filename, 0, "Resolviendo enlace…", false, false);
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream fos = null;
        File dest = null;

        try {
            String resolved = UrlResolver.resolve(url);
            if (cancelled.get()) { cleanup(taskId, notifId); return; }

            updateNotif(notifId, taskId, filename, 0, "Conectando…", false, false);
            URL dlUrl = new URL(resolved);
            conn = openConn(dlUrl, 0);
            long totalBytes = conn.getContentLengthLong();

            File destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (destDir == null || !destDir.exists())
                destDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (destDir == null) destDir = getFilesDir();
            destDir.mkdirs();
            dest = new File(destDir, filename + ".pkg");

            long downloaded = 0;
            if (dest.exists() && totalBytes > 0) {
                downloaded = dest.length();
                if (downloaded >= totalBytes) {
                    onTaskDone(notifId, taskId, filename);
                    cleanup(taskId, notifId);
                    return;
                }
                conn.disconnect();
                conn = openConn(dlUrl, downloaded);
            }

            in = conn.getInputStream();
            fos = new FileOutputStream(dest, downloaded > 0);
            byte[] buf = new byte[131072];
            int n;
            long lastSpeedB = downloaded;
            long lastSpeedT = System.currentTimeMillis();
            long total = downloaded;

            while ((n = in.read(buf)) != -1) {
                // Cancelación inmediata
                if (cancelled.get()) {
                    break;  // salir del bucle
                }

                // Manejo de pausa: mientras esté pausado, esperar pero seguir comprobando cancelación
                while (paused.get() && !cancelled.get()) {
                    int prog = totalBytes > 0 ? (int)(total * 100 / totalBytes) : 0;
                    notifMgr.notify(notifId,
                            buildProgressNotif(taskId, filename, prog, "⏸ Pausado", true).build());
                    Thread.sleep(300);
                }
                if (cancelled.get()) break;

                fos.write(buf, 0, n);
                total += n;

                long now = System.currentTimeMillis();
                if (now - lastSpeedT >= 1000) {
                    long speedBps = (total - lastSpeedB) * 1000 / Math.max(1, now - lastSpeedT);
                    lastSpeedB = total;
                    lastSpeedT = now;

                    int prog = totalBytes > 0 ? (int)(total * 100 / totalBytes) : 0;
                    String status = totalBytes > 0
                            ? DownloadManager.formatSize(total) + " / " + DownloadManager.formatSize(totalBytes)
                            : DownloadManager.formatSize(total);
                    String speed = formatSpeed(speedBps);

                    notifMgr.notify(notifId,
                            buildProgressNotif(taskId, filename, prog, status + "  " + speed, false).build());
                    final int fp = prog; final String fs = status; final String fsp = speed;
                    notifyCallback(taskId, cb -> cb.onProgress(taskId, fp, fs, fsp));
                }
            }

            if (cancelled.get()) {
                // Cancelación solicitada: eliminar archivo parcial
                if (dest != null && dest.exists()) dest.delete();
                notifMgr.cancel(notifId);
                notifyCallback(taskId, cb -> cb.onError(taskId, "Cancelado por el usuario"));
            } else {
                onTaskDone(notifId, taskId, filename);
            }

        } catch (Exception e) {
            Log.e(TAG, "runDownload: " + e.getMessage(), e);
            if (!cancelled.get()) {
                String err = e.getMessage();
                notifMgr.cancel(notifId);
                notifyCallback(taskId, cb -> cb.onError(taskId, err));
            } else {
                // Si fue cancelado, ya limpiamos
                if (dest != null && dest.exists()) dest.delete();
            }
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
            cleanup(taskId, notifId);
        }
    }

    private void onTaskDone(int notifId, int taskId, String filename) {
        notifMgr.notify(notifId, buildDoneNotif(filename).build());
        notifyCallback(taskId, cb -> cb.onDone(taskId, filename));
    }

    private void cleanup(int taskId, int notifId) {
        cancelMap.remove(taskId);
        pauseMap.remove(taskId);
        CALLBACKS.remove(taskId);
        int remaining = activeCount.decrementAndGet();
        if (remaining <= 0) {
            uiHandler.postDelayed(() -> {
                if (activeCount.get() <= 0) stopSelf();
            }, 3000);
        }
    }

    private HttpURLConnection openConn(URL url, long rangeStart) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setInstanceFollowRedirects(true);
        if (rangeStart > 0)
            conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
        conn.connect();
        return conn;
    }

    @FunctionalInterface
    private interface CallbackAction { void run(Callback cb); }

    private void notifyCallback(int taskId, CallbackAction action) {
        Callback cb = CALLBACKS.get(taskId);
        if (cb != null) uiHandler.post(() -> action.run(cb));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Descargas KZ Store", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Progreso de descargas en segundo plano");
            ch.setSound(null, null);
            notifMgr.createNotificationChannel(ch);
        }
    }

    private Notification buildIdleNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("KZ Store Reader")
                .setContentText("Servicio de descargas listo")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .build();
    }

    private NotificationCompat.Builder buildProgressNotif(int taskId, String filename,
                                                          int progress, String status,
                                                          boolean paused) {
        PendingIntent pauseResumeIntent = makePendingIntent(
                paused ? ACTION_RESUME : ACTION_PAUSE, taskId, 0);
        PendingIntent cancelIntent = makePendingIntent(ACTION_CANCEL, taskId, 1);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(filename + ".pkg")
                .setContentText(status)
                .setProgress(100, Math.max(0, progress), progress <= 0)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause,
                        paused ? "▶ Reanudar" : "⏸ Pausar", pauseResumeIntent)
                .addAction(android.R.drawable.ic_delete, "✖ Cancelar", cancelIntent);
    }

    private NotificationCompat.Builder buildDoneNotif(String filename) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("✔ " + filename + ".pkg")
                .setContentText("Guardado en Descargas/")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
    }

    private void updateNotif(int notifId, int taskId, String filename,
                             int progress, String status, boolean paused, boolean done) {
        if (done) {
            notifMgr.notify(notifId, buildDoneNotif(filename).build());
        } else {
            notifMgr.notify(notifId,
                    buildProgressNotif(taskId, filename, progress, status, paused).build());
        }
    }

    private PendingIntent makePendingIntent(String action, int taskId, int reqCodeOffset) {
        Intent i = new Intent(this, DownloadService.class)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, reqCodeOffset * 10000 + taskId, i, flags);
    }

    static String formatSpeed(long bps) {
        if (bps >= 1_048_576) return String.format("%.1f MB/s", bps / 1_048_576.0);
        if (bps >= 1024)      return String.format("%.0f KB/s", bps / 1024.0);
        return bps + " B/s";
    }
}
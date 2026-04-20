package com.kizeo.kzstorereader.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.io.CopyStreamAdapter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class FtpService extends Service {

    private static final String TAG = "FtpService";

    public static final String ACTION_UPLOAD   = "com.kizeo.kzstorereader.FTP_UPLOAD";
    public static final String ACTION_DOWNLOAD = "com.kizeo.kzstorereader.FTP_DOWNLOAD";
    public static final String ACTION_CANCEL   = "com.kizeo.kzstorereader.FTP_CANCEL";

    public static final String EXTRA_HOST        = "ftp_host";
    public static final String EXTRA_PORT        = "ftp_port";
    public static final String EXTRA_USER        = "ftp_user";
    public static final String EXTRA_PASS        = "ftp_pass";
    public static final String EXTRA_LOCAL_PATH  = "local_path";
    public static final String EXTRA_REMOTE_PATH = "remote_path";
    public static final String EXTRA_FILENAME    = "filename";
    public static final String EXTRA_TASK_ID     = "task_id";

    private static final String CHANNEL_ID = "kz_ftp";
    private static final int    NOTIF_IDLE = 3999;
    private static final int    NOTIF_BASE = 4000;

    public interface Callback {
        void onProgress(int taskId, int progress, String status);
        void onDone(int taskId, boolean upload, String filename);
        void onError(int taskId, String error);
    }

    private static final ConcurrentHashMap<Integer, Callback> CALLBACKS = new ConcurrentHashMap<>();
    public static void register(int taskId, Callback cb)  { CALLBACKS.put(taskId, cb); }
    public static void unregister(int taskId)              { CALLBACKS.remove(taskId); }

    private final ExecutorService executor    = Executors.newCachedThreadPool();
    private final Handler         uiHandler   = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<Integer, AtomicBoolean> cancelMap = new ConcurrentHashMap<>();
    private final AtomicInteger   activeCount = new AtomicInteger(0);
    private NotificationManager   notifMgr;

    private static final AtomicInteger ID_GEN = new AtomicInteger(1000);
    public static int nextTaskId() { return ID_GEN.incrementAndGet(); }

    @Override
    public void onCreate() {
        super.onCreate();
        notifMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        startForeground(NOTIF_IDLE, buildIdleNotif());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;

        String action = intent.getAction();
        int    taskId = intent.getIntExtra(EXTRA_TASK_ID, -1);

        if (ACTION_CANCEL.equals(action)) {
            if (cancelMap.containsKey(taskId))
                cancelMap.get(taskId).set(true);
            return START_STICKY;
        }

        boolean upload = ACTION_UPLOAD.equals(action);
        String host       = intent.getStringExtra(EXTRA_HOST);
        int    port       = intent.getIntExtra(EXTRA_PORT, 21);
        String user       = intent.getStringExtra(EXTRA_USER);
        String pass       = intent.getStringExtra(EXTRA_PASS);
        String localPath  = intent.getStringExtra(EXTRA_LOCAL_PATH);
        String remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH);
        String filename   = intent.getStringExtra(EXTRA_FILENAME);

        if (host == null || localPath == null || filename == null || taskId < 0)
            return START_STICKY;

        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelMap.put(taskId, cancelled);
        activeCount.incrementAndGet();

        if (upload) {
            executor.execute(() -> runUpload(host, port, user, pass,
                    localPath, remotePath, filename, taskId, cancelled));
        } else {
            executor.execute(() -> runDownload(host, port, user, pass,
                    localPath, remotePath, filename, taskId, cancelled));
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── Subida FTP (sin cambios) ──────────────────────────────────────────────
    private void runUpload(String host, int port, String user, String pass,
                           String localPath, String remotePath, String filename,
                           int taskId, AtomicBoolean cancelled) {
        int notifId = NOTIF_BASE + taskId;
        File src = new File(localPath);
        long totalBytes = src.length();

        updateNotif(notifId, "↑ " + filename, 0, "Conectando a " + host + "…");

        FTPClient ftp = new FTPClient();
        try {
            ftp.setDefaultTimeout(20_000);
            ftp.setControlEncoding("ISO-8859-1");
            ftp.connect(host, port);
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode()))
                throw new Exception("Servidor FTP rechazó la conexión");
            ftp.login(user != null ? user : "anonymous", pass != null ? pass : "");
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTPClient.BINARY_FILE_TYPE);

            if (cancelled.get()) { ftp.disconnect(); cleanup(taskId, notifId); return; }

            final long[] transferred = {0};
            ftp.setCopyStreamListener(new CopyStreamAdapter() {
                @Override
                public void bytesTransferred(long total, int bytes, long streamSize) {
                    transferred[0] += bytes;
                    if (totalBytes > 0) {
                        int prog = (int)(transferred[0] * 100 / totalBytes);
                        String status = DownloadManager.formatSize(transferred[0])
                                + " / " + DownloadManager.formatSize(totalBytes);
                        updateNotif(notifId, "↑ " + filename, prog, status);
                        notifyCallback(taskId, cb -> cb.onProgress(taskId, prog, status));
                    }
                }
            });

            String dest = remotePath.endsWith("/")
                    ? remotePath + filename : remotePath + "/" + filename;

            updateNotif(notifId, "↑ " + filename, 0, "Enviando…");

            try (FileInputStream fis = new FileInputStream(src)) {
                boolean ok = ftp.storeFile(dest, fis);
                if (!ok) throw new Exception("storeFile devolvió false: " + ftp.getReplyString());
            }
            ftp.logout();
            ftp.disconnect();

            if (!cancelled.get()) {
                notifMgr.notify(notifId, buildDoneNotif("↑ " + filename, "Enviado a PS3").build());
                notifyCallback(taskId, cb -> cb.onDone(taskId, true, filename));
            }

        } catch (Exception e) {
            Log.e(TAG, "runUpload: " + e.getMessage(), e);
            try { ftp.disconnect(); } catch (Exception ignored) {}
            if (!cancelled.get()) {
                notifMgr.cancel(notifId);
                notifyCallback(taskId, cb -> cb.onError(taskId, e.getMessage()));
            }
        } finally {
            cleanup(taskId, notifId);
        }
    }

    // ── Bajada FTP (corregido: uso de listFiles en lugar de getSize) ──────────
    private void runDownload(String host, int port, String user, String pass,
                             String localPath, String remotePath, String filename,
                             int taskId, AtomicBoolean cancelled) {
        int notifId = NOTIF_BASE + taskId;
        updateNotif(notifId, "↓ " + filename, 0, "Conectando a " + host + "…");

        FTPClient ftp = new FTPClient();
        try {
            ftp.setDefaultTimeout(20_000);
            ftp.setControlEncoding("ISO-8859-1");
            ftp.connect(host, port);
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode()))
                throw new Exception("Servidor FTP rechazó la conexión");
            ftp.login(user != null ? user : "anonymous", pass != null ? pass : "");
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTPClient.BINARY_FILE_TYPE);

            if (cancelled.get()) { ftp.disconnect(); cleanup(taskId, notifId); return; }

            // Obtener tamaño del archivo remoto de forma compatible (evita el error de tipo)
            String src = remotePath.endsWith("/")
                    ? remotePath + filename : remotePath + "/" + filename;
            long totalBytes = getRemoteFileSize(ftp, src);  // ← método alternativo

            final long[] transferred = {0};
            ftp.setCopyStreamListener(new CopyStreamAdapter() {
                @Override
                public void bytesTransferred(long total, int bytes, long streamSize) {
                    transferred[0] += bytes;
                    int prog = totalBytes > 0 ? (int)(transferred[0] * 100 / totalBytes) : -1;
                    String status = totalBytes > 0
                            ? DownloadManager.formatSize(transferred[0]) + " / " + DownloadManager.formatSize(totalBytes)
                            : DownloadManager.formatSize(transferred[0]);
                    updateNotif(notifId, "↓ " + filename, Math.max(0, prog), status);
                    notifyCallback(taskId, cb -> cb.onProgress(taskId, prog, status));
                }
            });

            File dest = new File(localPath, filename);
            updateNotif(notifId, "↓ " + filename, 0, "Descargando…");

            try (FileOutputStream fos = new FileOutputStream(dest)) {
                boolean ok = ftp.retrieveFile(src, fos);
                if (!ok) throw new Exception("retrieveFile devolvió false: " + ftp.getReplyString());
            }
            ftp.logout();
            ftp.disconnect();

            if (!cancelled.get()) {
                notifMgr.notify(notifId, buildDoneNotif("↓ " + filename, "Guardado en " + localPath).build());
                notifyCallback(taskId, cb -> cb.onDone(taskId, false, filename));
            }

        } catch (Exception e) {
            Log.e(TAG, "runDownload: " + e.getMessage(), e);
            try { ftp.disconnect(); } catch (Exception ignored) {}
            if (!cancelled.get()) {
                notifMgr.cancel(notifId);
                notifyCallback(taskId, cb -> cb.onError(taskId, e.getMessage()));
            }
        } finally {
            cleanup(taskId, notifId);
        }
    }

    /**
     * Obtiene el tamaño de un archivo remoto de forma compatible.
     * Primero intenta con getSize() (si existe y devuelve long),
     * si falla o devuelve -1, usa listFiles().
     */
    private long getRemoteFileSize(FTPClient ftp, String remotePath) {
        try {
            // Intento 1: método estándar getSize (long)
            // Nota: algunas versiones devuelven long, otras String.
            // Por reflexión o try-catch manejamos ambas.
            java.lang.reflect.Method method = ftp.getClass().getMethod("getSize", String.class);
            Object result = method.invoke(ftp, remotePath);
            if (result instanceof Long) {
                long size = (Long) result;
                if (size >= 0) return size;
            } else if (result instanceof String) {
                // Caso extraño: devuelve String (algunas implementaciones antiguas)
                try {
                    long size = Long.parseLong((String) result);
                    if (size >= 0) return size;
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "getSize falló, usando listFiles: " + e.getMessage());
        }

        // Intento 2: listFiles
        try {
            FTPFile[] files = ftp.listFiles(remotePath);
            if (files != null && files.length == 1 && files[0].isFile()) {
                return files[0].getSize();
            }
            // Si el path es un directorio? No debería.
            // Intentar obtener el nombre base
            String parent = remotePath.substring(0, remotePath.lastIndexOf('/') + 1);
            String name = remotePath.substring(remotePath.lastIndexOf('/') + 1);
            FTPFile[] children = ftp.listFiles(parent);
            if (children != null) {
                for (FTPFile f : children) {
                    if (f.getName().equals(name) && f.isFile()) {
                        return f.getSize();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "listFiles también falló: " + e.getMessage());
        }
        return -1; // tamaño desconocido
    }

    // ── Limpieza y callbacks (sin cambios) ────────────────────────────────────
    private void cleanup(int taskId, int notifId) {
        cancelMap.remove(taskId);
        CALLBACKS.remove(taskId);
        int remaining = activeCount.decrementAndGet();
        if (remaining <= 0)
            uiHandler.postDelayed(() -> { if (activeCount.get() <= 0) stopSelf(); }, 3000);
    }

    @FunctionalInterface
    private interface CallbackAction { void run(Callback cb); }

    private void notifyCallback(int taskId, CallbackAction action) {
        Callback cb = CALLBACKS.get(taskId);
        if (cb != null) uiHandler.post(() -> action.run(cb));
    }

    // ── Notificaciones (sin cambios) ──────────────────────────────────────────
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "FTP KZ Store", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Transferencias FTP hacia PS3");
            ch.setSound(null, null);
            notifMgr.createNotificationChannel(ch);
        }
    }

    private Notification buildIdleNotif() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("KZ Store Reader — FTP")
                .setContentText("Servicio FTP listo")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .build();
    }

    private void updateNotif(int notifId, String title, int progress, String status) {
        PendingIntent cancelIntent = makePendingCancelIntent(notifId);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(status)
                .setProgress(100, Math.max(0, progress), progress <= 0)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "✖ Cancelar", cancelIntent)
                .build();
        notifMgr.notify(notifId, n);
    }

    private NotificationCompat.Builder buildDoneNotif(String title, String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("✔ " + title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
    }

    private PendingIntent makePendingCancelIntent(int taskId) {
        Intent i = new Intent(this, FtpService.class)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_TASK_ID, taskId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, taskId + 5000, i, flags);
    }
}
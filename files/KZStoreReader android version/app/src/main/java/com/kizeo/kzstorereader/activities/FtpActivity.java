package com.kizeo.kzstorereader.activities;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NotificationCompat;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kizeo.kzstorereader.R;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class FtpActivity extends AppCompatActivity {

    private static final String PREFS_NAME    = "KZStorePrefs";
    private static final String PREF_FTP_HOST = "ftp_host";
    private static final String PREF_FTP_PORT = "ftp_port";
    private static final String PREF_FTP_USER = "ftp_user";
    private static final String PREF_FTP_PASS = "ftp_pass";

    private static final String CHANNEL_ID = "ftp_transfers";
    private static final int    NOTIF_ID   = 3001;

    // ── UI ────────────────────────────────────────────────────────────────────
    private EditText  etHost, etPort;
    private Button    btnConnect;
    private View      ledStatus;
    private TextView  btnCredMenu, btnRefresh, btnTransferIcon;
    private TextView  tvLog, tvLocalPath, tvRemotePath;
    private RecyclerView rvLocal, rvRemote;

    // ── Estado FTP ────────────────────────────────────────────────────────────
    private SharedPreferences prefs;
    private boolean isConnected = false;
    private String  currentHost, currentUser, currentPass;
    private int     currentPort = 21;

    private FTPClient ftpClient;
    private String    currentRemotePath = "/";
    private File      currentLocalPath  = Environment.getExternalStorageDirectory();
    private final File externalStorageRoot = Environment.getExternalStorageDirectory();

    /** Referencia estática a la tarea activa — accesible desde CancelTransferReceiver */
    static volatile TransferTask activeTransfer = null;
    /** Referencia estática a la Activity activa — usada por TransferTask para callbacks */
    static volatile FtpActivity  currentActivityRef = null;

    private AlertDialog progressDialog;
    private NotificationManager notificationManager;

    /** Executor de UN solo hilo para todo lo que toca ftpClient */
    private final ExecutorService ftpExecutor   = Executors.newSingleThreadExecutor();
    /** Executor separado para operaciones de filesystem local */
    private final ExecutorService localExecutor = Executors.newSingleThreadExecutor();
    private final Handler         uiHandler     = new Handler(Looper.getMainLooper());

    private FileAdapter localAdapter, remoteAdapter;
    private List<Object> localItems  = new ArrayList<>();
    private List<Object> remoteItems = new ArrayList<>();

    // ── Modo selección ────────────────────────────────────────────────────────
    /**
     * true = modo selección activo.
     * Solo un panel puede tener selección a la vez: local O remoto.
     */
    private boolean selectionMode = false;
    private final Set<Integer> selectedLocalPositions  = new HashSet<>();
    private final Set<Integer> selectedRemotePositions = new HashSet<>();

    private boolean isEs = true;
    private String t(String es, String en) { return isEs ? es : en; }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_ftp);

        createNotificationChannel();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        isEs = !"en".equals(getIntent().getStringExtra("lang"));
        if (!getIntent().hasExtra("lang")) {
            SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            isEs = !"en".equals(p.getString("lang", "es"));
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("FTP Manager");
        }

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        ((TextView) findViewById(R.id.tvIpLabel)).setText("IP:");
        ((TextView) findViewById(R.id.tvPortLabel)).setText(t("Puerto:", "Port:"));
        etHost          = findViewById(R.id.etFtpHost);
        etPort          = findViewById(R.id.etFtpPort);
        btnConnect      = findViewById(R.id.btnFtpConnect);
        ledStatus       = findViewById(R.id.ledStatus);
        btnCredMenu     = findViewById(R.id.btnCredMenu);
        btnRefresh      = findViewById(R.id.btnRefresh);
        btnTransferIcon = findViewById(R.id.btnTransferIcon);
        tvLog           = findViewById(R.id.tvFtpLog);
        tvLocalPath     = findViewById(R.id.tvLocalPath);
        tvRemotePath    = findViewById(R.id.tvRemotePath);
        rvLocal         = findViewById(R.id.rvLocalFiles);
        rvRemote        = findViewById(R.id.rvRemoteFiles);
        ((TextView) findViewById(R.id.tvLocalLabel)).setText(t("\uD83D\uDCF1  Celular — Local", "\uD83D\uDCF1  Phone — Local"));
        ((TextView) findViewById(R.id.tvRemoteLabel)).setText(t("🎮  PS3 — Remoto", "🎮  PS3 — Remote"));

        Button btnUpload   = findViewById(R.id.btnUpload);
        Button btnDownload = findViewById(R.id.btnDownload);
        btnUpload.setText(t("↑ Enviar", "↑ Upload"));
        btnDownload.setText(t("↓ Recibir", "↓ Download"));

        localAdapter  = new FileAdapter(true);
        remoteAdapter = new FileAdapter(false);
        rvLocal.setLayoutManager(new LinearLayoutManager(this));
        rvLocal.setAdapter(localAdapter);
        rvRemote.setLayoutManager(new LinearLayoutManager(this));
        rvRemote.setAdapter(remoteAdapter);

        refreshLocalFiles();
        loadSavedCredentials();

        btnCredMenu.setOnClickListener(v -> showCredentialsDialog());
        btnRefresh.setOnClickListener(v -> { if (isConnected) refreshRemoteFiles(); else refreshLocalFiles(); });
        btnTransferIcon.setOnClickListener(v -> showProgressDialog());

        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                disconnectFtp();
            } else {
                String host = etHost.getText().toString().trim();
                int    port;
                try { port = Integer.parseInt(etPort.getText().toString().trim()); }
                catch (NumberFormatException e) { port = 21; }
                if (host.isEmpty()) {
                    Toast.makeText(this, t("Ingresa la IP de la PS3", "Enter PS3 IP"), Toast.LENGTH_SHORT).show();
                    return;
                }
                currentHost = host;
                currentPort = port;
                prefs.edit().putString(PREF_FTP_HOST, host).putInt(PREF_FTP_PORT, port).apply();
                connectFtp(host, port, currentUser, currentPass);
            }
        });

        btnUpload.setOnClickListener(v   -> startUpload());
        btnDownload.setOnClickListener(v -> startDownload());
    }

    @Override protected void onResume()  { super.onResume();  currentActivityRef = this; }
    @Override protected void onPause()   { super.onPause(); }
    @Override protected void onDestroy() {
        currentActivityRef = null;
        if (ftpClient != null && ftpClient.isConnected())
            try { ftpClient.disconnect(); } catch (IOException ignored) {}
        if (activeTransfer != null) activeTransfer.cancel();
        ftpExecutor.shutdownNow();
        localExecutor.shutdownNow();
        super.onDestroy();
    }

    private void loadSavedCredentials() {
        etHost.setText(prefs.getString(PREF_FTP_HOST, "192.168.1.100"));
        etPort.setText(String.valueOf(prefs.getInt(PREF_FTP_PORT, 21)));
        currentUser = prefs.getString(PREF_FTP_USER, "anonymous");
        currentPass = prefs.getString(PREF_FTP_PASS, "");
    }

    // ── Helper de rutas ───────────────────────────────────────────────────────

    private String joinRemote(String base, String name) {
        return base.endsWith("/") ? base + name : base + "/" + name;
    }

    // ── Modo selección ────────────────────────────────────────────────────────

    /**
     * Entra en modo selección para el panel indicado.
     * Limpia la selección del panel opuesto para no mezclar paneles.
     */
    private void enterSelectionMode(boolean isLocal) {
        selectionMode = true;
        if (isLocal) {
            selectedRemotePositions.clear();
            remoteAdapter.notifyDataSetChanged();
        } else {
            selectedLocalPositions.clear();
            localAdapter.notifyDataSetChanged();
        }
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedLocalPositions.clear();
        selectedRemotePositions.clear();
        localAdapter.notifyDataSetChanged();
        remoteAdapter.notifyDataSetChanged();
    }

    /**
     * Alterna la selección de un ítem.
     * Si tras el toggle no queda ningún ítem seleccionado → sale del modo selección.
     */
    private void toggleSelection(boolean isLocal, int position) {
        Set<Integer> set = isLocal ? selectedLocalPositions : selectedRemotePositions;
        if (set.contains(position)) set.remove(position);
        else                        set.add(position);

        if (isLocal) localAdapter.notifyItemChanged(position);
        else         remoteAdapter.notifyItemChanged(position);

        if (set.isEmpty()) exitSelectionMode();
    }

    private List<File> getSelectedLocalFiles() {
        List<File> sel = new ArrayList<>();
        for (int pos : selectedLocalPositions) {
            Object obj = localItems.get(pos);
            if (obj instanceof File) sel.add((File) obj);
        }
        return sel;
    }

    private List<FTPFile> getSelectedRemoteFiles() {
        List<FTPFile> sel = new ArrayList<>();
        for (int pos : selectedRemotePositions) {
            Object obj = remoteItems.get(pos);
            if (obj instanceof FTPFile) sel.add((FTPFile) obj);
        }
        return sel;
    }

    // ── Transferencias ────────────────────────────────────────────────────────

    private void startUpload() {
        if (!isConnected) {
            Toast.makeText(this, t("Conecta a la PS3 primero", "Connect to PS3 first"), Toast.LENGTH_SHORT).show();
            return;
        }
        List<File> selected = getSelectedLocalFiles();
        if (selected.isEmpty()) {
            Toast.makeText(this, t("Mantén presionado para seleccionar archivos", "Long-press to select files"), Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(t("Enviar a PS3", "Upload to PS3"))
                .setMessage(t("Enviar " + selected.size() + " elemento(s) a " + currentRemotePath + "?",
                        "Upload " + selected.size() + " item(s) to " + currentRemotePath + "?"))
                .setPositiveButton(t("Enviar", "Upload"), (d, w) -> { startTransfer(true, selected, null); exitSelectionMode(); })
                .setNegativeButton(t("Cancelar", "Cancel"), null)
                .show();
    }

    private void startDownload() {
        if (!isConnected) {
            Toast.makeText(this, t("Conecta a la PS3 primero", "Connect to PS3 first"), Toast.LENGTH_SHORT).show();
            return;
        }
        List<FTPFile> selected = getSelectedRemoteFiles();
        if (selected.isEmpty()) {
            Toast.makeText(this, t("Mantén presionado para seleccionar archivos", "Long-press to select files"), Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(t("Recibir de PS3", "Download from PS3"))
                .setMessage(t("Descargar " + selected.size() + " elemento(s) a " + currentLocalPath.getAbsolutePath() + "?",
                        "Download " + selected.size() + " item(s) to " + currentLocalPath.getAbsolutePath() + "?"))
                .setPositiveButton(t("Descargar", "Download"), (d, w) -> { startTransfer(false, null, selected); exitSelectionMode(); })
                .setNegativeButton(t("Cancelar", "Cancel"), null)
                .show();
    }

    private void startTransfer(boolean isUpload, List<File> localFilesList, List<FTPFile> remoteFilesList) {
        if (activeTransfer != null && !activeTransfer.isCancelled()) {
            Toast.makeText(this, t("Ya hay una transferencia en curso", "Transfer already in progress"), Toast.LENGTH_SHORT).show();
            return;
        }
        activeTransfer = new TransferTask(isUpload, localFilesList, remoteFilesList, currentRemotePath, currentLocalPath);
        btnTransferIcon.setVisibility(View.VISIBLE);
        showProgressDialog();
        ftpExecutor.execute(activeTransfer);
    }

    private void showProgressDialog() {
        if (progressDialog == null || !progressDialog.isShowing()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            View view = getLayoutInflater().inflate(R.layout.dialog_transfer_progress, null);
            builder.setView(view);
            builder.setNegativeButton(t("Cancelar transferencia", "Cancel transfer"),
                    (d, w) -> { if (activeTransfer != null) activeTransfer.cancel(); });
            builder.setNeutralButton(t("Ocultar", "Hide"), (d, w) -> progressDialog.dismiss());
            progressDialog = builder.create();
            progressDialog.setCancelable(false);
            progressDialog.show();
        }
        updateProgressDialog(0, 0, "", 0);
    }

    void updateProgressDialog(int currentFile, int totalFiles, String fileName, long percent) {
        if (progressDialog != null && progressDialog.isShowing()) {
            ProgressBar bar = progressDialog.findViewById(R.id.progressBarTransfer);
            TextView tv     = progressDialog.findViewById(R.id.tvTransferStatus);
            if (bar != null) { bar.setMax(100); bar.setProgress((int) percent); }
            if (tv  != null) tv.setText(String.format(
                    t("Archivo %d de %d: %s\nProgreso: %d%%", "File %d of %d: %s\nProgress: %d%%"),
                    currentFile, totalFiles, fileName, percent));
        }
    }

    void updateNotification(int currentFile, int totalFiles, String fileName, long percent) {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openPi   = PendingIntent.getActivity(this, 0,
                new Intent(this, FtpActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);
        PendingIntent cancelPi = PendingIntent.getBroadcast(this, 1,
                new Intent(this, CancelTransferReceiver.class), piFlags);
        notificationManager.notify(NOTIF_ID,
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_upload)
                        .setContentTitle(t("Transferencia FTP", "FTP Transfer"))
                        .setContentText(String.format(
                                t("Archivo %d/%d: %s - %d%%", "File %d/%d: %s - %d%%"),
                                currentFile, totalFiles, fileName, percent))
                        .setProgress(100, (int) percent, false)
                        .setOngoing(true)
                        .setContentIntent(openPi)
                        .addAction(android.R.drawable.ic_menu_close_clear_cancel, t("Cancelar", "Cancel"), cancelPi)
                        .build());
    }

    void finishTransfer(boolean success, String message, boolean wasUpload) {
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
        notificationManager.cancel(NOTIF_ID);
        btnTransferIcon.setVisibility(View.GONE);
        activeTransfer = null;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (success) { if (wasUpload) refreshRemoteFiles(); else refreshLocalFiles(); }
    }

    // ── TransferTask ──────────────────────────────────────────────────────────

    static class TransferTask implements Runnable {
        final boolean       isUpload;
        final List<File>    localFiles;
        final List<FTPFile> remoteFiles;
        final String        remoteBaseDir;
        final File          localBaseDir;
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        int    totalItems = 0, completedItems = 0;
        long   currentFileTotalBytes = 0, currentFileTransferred = 0;
        String currentFileName = "";
        private long lastUiMs = 0;

        TransferTask(boolean upload, List<File> loc, List<FTPFile> rem,
                     String remoteDir, File localDir) {
            isUpload      = upload;
            localFiles    = loc  != null ? loc  : new ArrayList<>();
            remoteFiles   = rem  != null ? rem  : new ArrayList<>();
            remoteBaseDir = remoteDir;
            localBaseDir  = localDir;
            totalItems    = upload ? localFiles.size() : remoteFiles.size();
        }

        void cancel()         { cancelled.set(true); }
        boolean isCancelled() { return cancelled.get(); }

        @Override
        public void run() {
            try {
                if (isUpload) {
                    for (int i = 0; i < localFiles.size() && !cancelled.get(); i++) {
                        File f = localFiles.get(i);
                        currentFileName = f.getName();
                        completedItems  = i;
                        if (f.isDirectory()) uploadDir(f, joinR(remoteBaseDir, f.getName()));
                        else                 uploadFile(f, joinR(remoteBaseDir, f.getName()));
                        completedItems++;
                    }
                } else {
                    for (int i = 0; i < remoteFiles.size() && !cancelled.get(); i++) {
                        FTPFile f = remoteFiles.get(i);
                        currentFileName = f.getName();
                        completedItems  = i;
                        if (f.isDirectory()) downloadDir(joinR(remoteBaseDir, f.getName()), new File(localBaseDir, f.getName()));
                        else                 downloadFile(joinR(remoteBaseDir, f.getName()), new File(localBaseDir, f.getName()), f.getSize());
                        completedItems++;
                    }
                }
                boolean wasCancelled = cancelled.get();
                String msg = wasCancelled
                        ? (isEs() ? "Transferencia cancelada" : "Transfer cancelled")
                        : (isEs() ? "Transferencia completada ✔" : "Transfer completed ✔");
                FtpActivity act = currentActivityRef;
                if (act != null) act.uiHandler.post(() -> act.finishTransfer(!wasCancelled, msg, isUpload));
            } catch (Exception e) {
                FtpActivity act = currentActivityRef;
                String err = e.getMessage();
                if (act != null) act.uiHandler.post(() ->
                        act.finishTransfer(false, "Error: " + err, isUpload));
            }
        }

        private void uploadDir(File dir, String remotePath) throws IOException {
            if (cancelled.get()) return;
            FTPClient ftp = ftpRef();
            if (ftp == null) return;
            ftp.makeDirectory(remotePath);
            File[] ch = dir.listFiles();
            if (ch != null)
                for (File c : ch) {
                    if (cancelled.get()) return;
                    if (c.isDirectory()) uploadDir(c, joinR(remotePath, c.getName()));
                    else                 uploadFile(c, joinR(remotePath, c.getName()));
                }
        }

        private void uploadFile(File local, String remotePath) throws IOException {
            if (cancelled.get()) return;
            FTPClient ftp = ftpRef();
            if (ftp == null) throw new IOException("FTP desconectado");
            currentFileTotalBytes  = local.length();
            currentFileTransferred = 0;
            byte[] buf = new byte[65536];
            try (InputStream is = new FileInputStream(local);
                 OutputStream os = ftp.storeFileStream(remotePath)) {
                if (os == null) throw new IOException("No se pudo abrir stream: " + remotePath);
                int len;
                while ((len = is.read(buf)) != -1 && !cancelled.get()) {
                    os.write(buf, 0, len);
                    currentFileTransferred += len;
                    throttleUi();
                }
                os.flush();
            } finally {
                if (ftp.isConnected()) ftp.completePendingCommand();
            }
            log("⬆️ " + local.getName());
        }

        private void downloadDir(String remotePath, File localDir) throws IOException {
            if (cancelled.get()) return;
            FTPClient ftp = ftpRef();
            if (ftp == null) return;
            if (!localDir.exists()) localDir.mkdirs();
            FTPFile[] files = ftp.listFiles(remotePath);
            if (files != null)
                for (FTPFile f : files) {
                    if (cancelled.get()) return;
                    if (f.getName().equals(".") || f.getName().equals("..")) continue;
                    if (f.isDirectory()) downloadDir(joinR(remotePath, f.getName()), new File(localDir, f.getName()));
                    else                 downloadFile(joinR(remotePath, f.getName()), new File(localDir, f.getName()), f.getSize());
                }
        }

        private void downloadFile(String remotePath, File local, long total) throws IOException {
            if (cancelled.get()) return;
            FTPClient ftp = ftpRef();
            if (ftp == null) throw new IOException("FTP desconectado");
            currentFileTotalBytes  = total;
            currentFileTransferred = 0;
            byte[] buf = new byte[65536];
            try (InputStream  is = ftp.retrieveFileStream(remotePath);
                 OutputStream os = new FileOutputStream(local)) {
                if (is == null) throw new IOException("No se pudo abrir stream: " + remotePath);
                int len;
                while ((len = is.read(buf)) != -1 && !cancelled.get()) {
                    os.write(buf, 0, len);
                    currentFileTransferred += len;
                    throttleUi();
                }
                os.flush();
            } finally {
                if (ftp.isConnected()) ftp.completePendingCommand();
            }
            log("⬇️ " + local.getName());
        }

        /** Actualiza UI máximo 1 vez cada 300ms para no saturar el hilo principal */
        private void throttleUi() {
            long now = System.currentTimeMillis();
            if (now - lastUiMs < 300) return;
            lastUiMs = now;
            int pct = currentFileTotalBytes > 0
                    ? (int)(currentFileTransferred * 100 / currentFileTotalBytes) : 0;
            final int fp = pct, fc = completedItems + 1, ft = totalItems;
            final String fn = currentFileName;
            FtpActivity act = currentActivityRef;
            if (act != null) act.uiHandler.post(() -> {
                act.updateProgressDialog(fc, ft, fn, fp);
                act.updateNotification(fc, ft, fn, fp);
            });
        }

        private void log(String msg) {
            FtpActivity act = currentActivityRef;
            if (act != null && !cancelled.get()) act.uiHandler.post(() -> act.addLog(msg));
        }

        private FTPClient ftpRef() {
            FtpActivity act = currentActivityRef;
            return act != null ? act.ftpClient : null;
        }

        private boolean isEs() {
            FtpActivity act = currentActivityRef;
            return act == null || act.isEs;
        }

        private String joinR(String base, String name) {
            return base.endsWith("/") ? base + name : base + "/" + name;
        }
    }

    // ── Conexión ──────────────────────────────────────────────────────────────

    private void connectFtp(String host, int port, String user, String pass) {
        btnConnect.setEnabled(false);
        btnConnect.setText(t("Conectando...", "Connecting..."));
        addLog(t("Conectando a ", "Connecting to ") + host + ":" + port + "...");
        ftpExecutor.execute(() -> {
            FTPClient ftp = new FTPClient();
            try {
                ftp.setDefaultTimeout(10_000);
                ftp.setConnectTimeout(10_000);
                ftp.connect(host, port);
                if (!FTPReply.isPositiveCompletion(ftp.getReplyCode()))
                    throw new IOException(t("Servidor rechazó conexión", "Server refused connection"));
                if (!ftp.login(user != null ? user : "anonymous", pass != null ? pass : ""))
                    throw new IOException(t("Usuario/contraseña incorrectos", "Wrong user/password"));
                ftp.enterLocalPassiveMode();
                ftp.setFileType(FTPClient.BINARY_FILE_TYPE);
                uiHandler.post(() -> {
                    isConnected = true;
                    ftpClient   = ftp;
                    ledStatus.setBackgroundResource(R.drawable.led_green);
                    btnConnect.setText(t("Desconectar", "Disconnect"));
                    addLog(t("✅ Conectado a ", "✅ Connected to ") + host);
                    Toast.makeText(this, t("Conexión exitosa", "Connection successful"), Toast.LENGTH_SHORT).show();
                    refreshRemoteFiles();
                });
            } catch (Exception e) {
                try { if (ftp.isConnected()) ftp.disconnect(); } catch (Exception ignored) {}
                final String err = e.getMessage();
                uiHandler.post(() -> {
                    ledStatus.setBackgroundResource(R.drawable.led_red);
                    btnConnect.setText(t("Conectar", "Connect"));
                    addLog(t("❌ Error: ", "❌ Error: ") + err);
                    Toast.makeText(this, t("Error de conexión", "Connection failed"), Toast.LENGTH_LONG).show();
                });
            } finally {
                uiHandler.post(() -> btnConnect.setEnabled(true));
            }
        });
    }

    private void disconnectFtp() {
        ftpExecutor.execute(() -> {
            try { if (ftpClient != null) { ftpClient.logout(); ftpClient.disconnect(); } }
            catch (IOException ignored) {}
            ftpClient   = null;
            isConnected = false;
            uiHandler.post(() -> {
                ledStatus.setBackgroundResource(R.drawable.led_red);
                btnConnect.setText(t("Conectar", "Connect"));
                addLog(t("🔌 Desconectado", "🔌 Disconnected"));
                remoteItems.clear();
                remoteAdapter.notifyDataSetChanged();
                tvRemotePath.setText("/");
                currentRemotePath = "/";
                exitSelectionMode();
                Toast.makeText(this, t("Desconectado", "Disconnected"), Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ── Listado ───────────────────────────────────────────────────────────────

    private void refreshLocalFiles() {
        localExecutor.execute(() -> {
            List<Object> items = new ArrayList<>();
            if (!currentLocalPath.equals(externalStorageRoot) && currentLocalPath.getParentFile() != null)
                items.add("..");
            File[] list = currentLocalPath.listFiles();
            if (list != null) {
                Arrays.sort(list, (a, b) -> {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (File f : list) items.add(f);
            }
            uiHandler.post(() -> {
                localItems = items;
                localAdapter.notifyDataSetChanged();
                tvLocalPath.setText(currentLocalPath.getAbsolutePath());
            });
        });
    }

    private void refreshRemoteFiles() {
        if (!isConnected || ftpClient == null) return;
        ftpExecutor.execute(() -> {
            List<Object> items = new ArrayList<>();
            if (!currentRemotePath.equals("/")) items.add("..");
            try {
                FTPFile[] files = ftpClient.listFiles(currentRemotePath);
                if (files != null) {
                    Arrays.sort(files, (a, b) -> {
                        if (a.isDirectory() && !b.isDirectory()) return -1;
                        if (!a.isDirectory() && b.isDirectory()) return 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    });
                    for (FTPFile f : files)
                        if (!f.getName().equals(".") && !f.getName().equals("..")) items.add(f);
                }
                uiHandler.post(() -> {
                    remoteItems = items;
                    remoteAdapter.notifyDataSetChanged();
                    tvRemotePath.setText(currentRemotePath);
                });
            } catch (IOException e) {
                uiHandler.post(() -> addLog(t("Error al listar: ", "List error: ") + e.getMessage()));
            }
        });
    }

    // ── Menú de acciones para selección masiva ────────────────────────────────

    /**
     * Aparece al hacer long-press sobre un ítem YA marcado dentro del modo selección.
     * Opciones: Borrar seleccionados | Renombrar (solo si 1 seleccionado) | Cancelar selección
     */
    private void showSelectionActionMenu(boolean isLocal) {
        int count = isLocal ? selectedLocalPositions.size() : selectedRemotePositions.size();
        List<String> options = new ArrayList<>();
        options.add(t("Borrar seleccionados (" + count + ")", "Delete selected (" + count + ")"));
        if (count == 1) options.add(t("Renombrar", "Rename"));
        options.add(t("Cancelar selección", "Cancel selection"));

        new AlertDialog.Builder(this)
                .setTitle(t(count + " elemento(s) seleccionado(s)", count + " item(s) selected"))
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String sel = options.get(which);
                    if      (sel.startsWith(t("Borrar", "Delete")))               { if (isLocal) deleteMultipleLocal(); else deleteMultipleRemote(); }
                    else if (sel.equals(t("Renombrar", "Rename")))                { if (isLocal) renameSingleLocal();   else renameSingleRemote(); }
                    else                                                            exitSelectionMode();
                })
                .show();
    }

    // ── Borrado masivo ────────────────────────────────────────────────────────

    private void deleteMultipleLocal() {
        List<File> toDelete = getSelectedLocalFiles();
        new AlertDialog.Builder(this)
                .setTitle(t("Borrar archivos", "Delete files"))
                .setMessage(t("¿Borrar " + toDelete.size() + " elemento(s) permanentemente?",
                        "Delete " + toDelete.size() + " item(s) permanently?"))
                .setPositiveButton(t("Borrar", "Delete"), (d, w) -> localExecutor.execute(() -> {
                    int del = 0;
                    for (File f : toDelete) if (deleteRecursive(f)) del++;
                    final int fd = del;
                    uiHandler.post(() -> { addLog("🗑 " + fd + t(" borrado(s)", " deleted")); refreshLocalFiles(); exitSelectionMode(); });
                }))
                .setNegativeButton(t("Cancelar", "Cancel"), null).show();
    }

    private void deleteMultipleRemote() {
        List<FTPFile> toDelete = getSelectedRemoteFiles();
        new AlertDialog.Builder(this)
                .setTitle(t("Borrar en PS3", "Delete on PS3"))
                .setMessage(t("¿Borrar " + toDelete.size() + " elemento(s) de la PS3?",
                        "Delete " + toDelete.size() + " item(s) from PS3?"))
                .setPositiveButton(t("Borrar", "Delete"), (d, w) -> ftpExecutor.execute(() -> {
                    int del = 0;
                    for (FTPFile f : toDelete) {
                        try {
                            boolean ok = f.isDirectory()
                                    ? deleteRemoteDir(ftpClient, joinRemote(currentRemotePath, f.getName()))
                                    : ftpClient.deleteFile(joinRemote(currentRemotePath, f.getName()));
                            if (ok) del++;
                        } catch (IOException e) { e.printStackTrace(); }
                    }
                    final int fd = del;
                    uiHandler.post(() -> { addLog("🗑 " + fd + t(" remoto(s) borrado(s)", " remote(s) deleted")); refreshRemoteFiles(); exitSelectionMode(); });
                }))
                .setNegativeButton(t("Cancelar", "Cancel"), null).show();
    }

    // ── Renombrar (un solo ítem seleccionado) ─────────────────────────────────

    private void renameSingleLocal() {
        List<File> sel = getSelectedLocalFiles();
        if (sel.isEmpty()) return;
        File file = sel.get(0);
        EditText input = new EditText(this);
        input.setText(file.getName());
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(t("Renombrar", "Rename")).setView(input)
                .setPositiveButton(t("Renombrar", "Rename"), (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (!n.isEmpty()) {
                        if (file.renameTo(new File(file.getParentFile(), n))) addLog("✏️ " + file.getName() + " → " + n);
                        else addLog(t("❌ Error al renombrar", "❌ Rename failed"));
                        refreshLocalFiles();
                    }
                    exitSelectionMode();
                })
                .setNegativeButton(t("Cancelar", "Cancel"), (d, w) -> exitSelectionMode()).show();
    }

    private void renameSingleRemote() {
        List<FTPFile> sel = getSelectedRemoteFiles();
        if (sel.isEmpty()) return;
        FTPFile file = sel.get(0);
        EditText input = new EditText(this);
        input.setText(file.getName());
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(t("Renombrar en PS3", "Rename on PS3")).setView(input)
                .setPositiveButton(t("Renombrar", "Rename"), (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (!n.isEmpty() && isConnected && ftpClient != null) {
                        String oldP = joinRemote(currentRemotePath, file.getName());
                        String newP = joinRemote(currentRemotePath, n);
                        ftpExecutor.execute(() -> {
                            try {
                                if (ftpClient.rename(oldP, newP)) uiHandler.post(() -> { addLog("✏️ " + file.getName() + " → " + n); refreshRemoteFiles(); });
                                else uiHandler.post(() -> addLog(t("❌ Error al renombrar remoto", "❌ Remote rename failed")));
                            } catch (IOException e) { uiHandler.post(() -> addLog("Error: " + e.getMessage())); }
                            finally { uiHandler.post(this::exitSelectionMode); }
                        });
                    } else exitSelectionMode();
                })
                .setNegativeButton(t("Cancelar", "Cancel"), (d, w) -> exitSelectionMode()).show();
    }

    // ── Operaciones individuales (long-press en modo NORMAL) ──────────────────

    /**
     * En modo NORMAL, long-press sobre un archivo → entra en selección y marca ese archivo.
     * Pero si es carpeta, el primer tap ya navega, así que long-press también puede mostrar
     * opciones de carpeta.  Para archivos regulares, long-press = entrar en modo selección.
     */
    private void handleLocalLongPress(File file, int position) {
        if (file.isDirectory()) {
            // Para carpetas: menú rápido con crear subcarpeta, renombrar, borrar
            String[] options = { t("Nueva carpeta aquí", "New folder here"), t("Renombrar", "Rename"), t("Borrar", "Delete") };
            new AlertDialog.Builder(this).setTitle(file.getName())
                    .setItems(options, (dialog, which) -> {
                        if      (which == 0) showCreateDirDialogLocal();
                        else if (which == 1) showRenameDialogLocal(file);
                        else                 deleteLocalFile(file);
                    }).show();
        } else {
            // Para archivos: entrar en modo selección y marcar este
            enterSelectionMode(true);
            toggleSelection(true, position);
        }
    }

    private void handleRemoteLongPress(FTPFile file, int position) {
        if (file.isDirectory()) {
            String[] options = { t("Nueva carpeta aquí", "New folder here"), t("Renombrar", "Rename"), t("Borrar", "Delete") };
            new AlertDialog.Builder(this).setTitle(file.getName())
                    .setItems(options, (dialog, which) -> {
                        if      (which == 0) showCreateDirDialogRemote();
                        else if (which == 1) showRenameDialogRemote(file);
                        else                 deleteRemoteFile(file);
                    }).show();
        } else {
            enterSelectionMode(false);
            toggleSelection(false, position);
        }
    }

    // ── Crear carpetas ────────────────────────────────────────────────────────

    private void showCreateDirDialogLocal() {
        EditText input = new EditText(this);
        input.setHint(t("nombre", "name"));
        new AlertDialog.Builder(this).setTitle(t("Nueva carpeta", "New folder")).setView(input)
                .setPositiveButton(t("Crear", "Create"), (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (!n.isEmpty()) {
                        if (new File(currentLocalPath, n).mkdir()) { refreshLocalFiles(); addLog("📁 " + n); }
                        else addLog(t("❌ Error al crear carpeta", "❌ Folder creation failed"));
                    }
                }).setNegativeButton(t("Cancelar", "Cancel"), null).show();
    }

    private void showCreateDirDialogRemote() {
        EditText input = new EditText(this);
        input.setHint(t("nombre", "name"));
        new AlertDialog.Builder(this).setTitle(t("Nueva carpeta", "New folder")).setView(input)
                .setPositiveButton(t("Crear", "Create"), (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (!n.isEmpty() && isConnected && ftpClient != null) {
                        ftpExecutor.execute(() -> {
                            try {
                                if (ftpClient.makeDirectory(joinRemote(currentRemotePath, n)))
                                    uiHandler.post(() -> { addLog("📁 " + n); refreshRemoteFiles(); });
                                else uiHandler.post(() -> addLog(t("❌ Error al crear carpeta remota", "❌ Failed to create remote folder")));
                            } catch (IOException e) { uiHandler.post(() -> addLog("Error: " + e.getMessage())); }
                        });
                    }
                }).setNegativeButton(t("Cancelar", "Cancel"), null).show();
    }

    // ── Renombrar individuales ────────────────────────────────────────────────

    private void showRenameDialogLocal(File file) {
        EditText input = new EditText(this);
        input.setText(file.getName()); input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(t("Renombrar", "Rename")).setView(input)
                .setPositiveButton(t("Renombrar", "Rename"), (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (!n.isEmpty()) {
                        if (file.renameTo(new File(file.getParentFile(), n))) { refreshLocalFiles(); addLog("✏️ " + file.getName() + " → " + n); }
                        else addLog(t("❌ Error al renombrar", "❌ Rename failed"));
                    }
                }).setNegativeButton(t("Cancelar", "Cancel"), null).show();
    }

    private void showRenameDialogRemote(FTPFile file) {
        EditText input = new EditText(this);
        input.setText(file.getName()); input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(t("Renombrar", "Rename")).setView(input)
                .setPositiveButton(t("Renombrar", "Rename"), (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (!n.isEmpty() && isConnected && ftpClient != null) {
                        String oldP = joinRemote(currentRemotePath, file.getName());
                        String newP = joinRemote(currentRemotePath, n);
                        ftpExecutor.execute(() -> {
                            try {
                                if (ftpClient.rename(oldP, newP)) uiHandler.post(() -> { addLog("✏️ " + file.getName() + " → " + n); refreshRemoteFiles(); });
                                else uiHandler.post(() -> addLog(t("❌ Error al renombrar remoto", "❌ Remote rename failed")));
                            } catch (IOException e) { uiHandler.post(() -> addLog("Error: " + e.getMessage())); }
                        });
                    }
                }).setNegativeButton(t("Cancelar", "Cancel"), null).show();
    }

    // ── Borrar individuales ───────────────────────────────────────────────────

    private void deleteLocalFile(File file) {
        new AlertDialog.Builder(this).setTitle(t("Borrar", "Delete"))
                .setMessage(t("¿Borrar ", "Delete ") + file.getName() + "?")
                .setPositiveButton(t("Borrar", "Delete"), (d, w) -> {
                    if (file.isDirectory() ? deleteRecursive(file) : file.delete()) { refreshLocalFiles(); addLog("🗑 " + file.getName()); }
                    else addLog(t("❌ Error al borrar", "❌ Delete failed"));
                }).setNegativeButton(t("Cancelar", "Cancel"), null).show();
    }

    private void deleteRemoteFile(FTPFile file) {
        new AlertDialog.Builder(this).setTitle(t("Borrar en PS3", "Delete on PS3"))
                .setMessage(t("¿Borrar ", "Delete ") + file.getName() + "?")
                .setPositiveButton(t("Borrar", "Delete"), (d, w) -> ftpExecutor.execute(() -> {
                    String path = joinRemote(currentRemotePath, file.getName());
                    try {
                        boolean ok = file.isDirectory() ? deleteRemoteDir(ftpClient, path) : ftpClient.deleteFile(path);
                        uiHandler.post(() -> { if (ok) { addLog("🗑 " + file.getName()); refreshRemoteFiles(); } else addLog(t("❌ Error al borrar remoto", "❌ Remote delete failed")); });
                    } catch (IOException e) { uiHandler.post(() -> addLog("Error: " + e.getMessage())); }
                })).setNegativeButton(t("Cancelar", "Cancel"), null).show();
    }

    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) deleteRecursive(c); }
        return f.delete();
    }

    private boolean deleteRemoteDir(FTPClient ftp, String path) throws IOException {
        FTPFile[] files = ftp.listFiles(path);
        if (files != null)
            for (FTPFile f : files) {
                String cp = joinRemote(path, f.getName());
                if (f.isDirectory()) deleteRemoteDir(ftp, cp);
                else ftp.deleteFile(cp);
            }
        return ftp.removeDirectory(path);
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
        private final boolean isLocal;
        FileAdapter(boolean local) { this.isLocal = local; }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(getLayoutInflater().inflate(R.layout.item_ftp_file, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
            if (isLocal) bindLocal(h, pos);
            else         bindRemote(h, pos);
        }

        // ── Panel local ───────────────────────────────────────────────────────

        private void bindLocal(ViewHolder h, int pos) {
            Object obj = localItems.get(pos);

            if (obj instanceof String) {          // entrada ".."
                h.tvName.setText("..");
                h.tvSize.setText("");
                h.chkSelect.setVisibility(View.GONE);
                h.itemView.setBackgroundColor(0xFF0F0F0F);
                h.itemView.setOnClickListener(v -> {
                    if (selectionMode) return;
                    File parent = currentLocalPath.getParentFile();
                    if (parent != null && (parent.exists() || parent.equals(externalStorageRoot))) {
                        currentLocalPath = parent;
                        refreshLocalFiles();
                    }
                });
                h.itemView.setOnLongClickListener(null);
                return;
            }

            File file = (File) obj;
            h.tvName.setText(file.getName());
            h.tvSize.setText(file.isDirectory() ? "<DIR>" : formatSize(file.length()));

            // Checkbox y resaltado de selección
            h.chkSelect.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            h.chkSelect.setChecked(selectedLocalPositions.contains(pos));
            h.itemView.setBackgroundColor(selectedLocalPositions.contains(pos) ? 0xFF1A3A5C : 0xFF0F0F0F);

            h.itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    // Modo selección: tap alterna la marca
                    toggleSelection(true, pos);
                } else {
                    // Modo normal: solo navegar si es directorio
                    if (file.isDirectory()) { currentLocalPath = file; refreshLocalFiles(); }
                }
            });

            h.itemView.setOnLongClickListener(v -> {
                if (!selectionMode) {
                    // ── Modo normal ──
                    // Archivo regular → entrar en selección y marcar
                    // Carpeta → menú de operaciones de carpeta
                    handleLocalLongPress(file, pos);
                } else {
                    // ── Ya en modo selección ──
                    if (selectedLocalPositions.contains(pos)) {
                        // Long-press sobre ítem YA marcado → menú de acciones masivas
                        showSelectionActionMenu(true);
                    } else {
                        // Long-press sobre ítem NO marcado → sumarlo a la selección
                        toggleSelection(true, pos);
                    }
                }
                return true;
            });
        }

        // ── Panel remoto ──────────────────────────────────────────────────────

        private void bindRemote(ViewHolder h, int pos) {
            Object obj = remoteItems.get(pos);

            if (obj instanceof String) {          // entrada ".."
                h.tvName.setText("..");
                h.tvSize.setText("");
                h.chkSelect.setVisibility(View.GONE);
                h.itemView.setBackgroundColor(0xFF0F0F0F);
                h.itemView.setOnClickListener(v -> {
                    if (selectionMode) return;
                    String cur = currentRemotePath.endsWith("/")
                            ? currentRemotePath.substring(0, currentRemotePath.length() - 1)
                            : currentRemotePath;
                    int idx = cur.lastIndexOf('/');
                    currentRemotePath = idx > 0 ? cur.substring(0, idx) : "/";
                    refreshRemoteFiles();
                });
                h.itemView.setOnLongClickListener(null);
                return;
            }

            FTPFile file = (FTPFile) obj;
            h.tvName.setText(file.getName());
            h.tvSize.setText(file.isDirectory() ? "<DIR>" : formatSize(file.getSize()));

            h.chkSelect.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            h.chkSelect.setChecked(selectedRemotePositions.contains(pos));
            h.itemView.setBackgroundColor(selectedRemotePositions.contains(pos) ? 0xFF1A5C2A : 0xFF0F0F0F);

            h.itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    toggleSelection(false, pos);
                } else {
                    if (file.isDirectory()) {
                        currentRemotePath = joinRemote(currentRemotePath, file.getName());
                        refreshRemoteFiles();
                    }
                }
            });

            h.itemView.setOnLongClickListener(v -> {
                if (!selectionMode) {
                    handleRemoteLongPress(file, pos);
                } else {
                    if (selectedRemotePositions.contains(pos)) {
                        showSelectionActionMenu(false);
                    } else {
                        toggleSelection(false, pos);
                    }
                }
                return true;
            });
        }

        @Override public int getItemCount() { return isLocal ? localItems.size() : remoteItems.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            CheckBox chkSelect;
            TextView tvName, tvSize;
            ViewHolder(View v) {
                super(v);
                chkSelect = v.findViewById(R.id.chkSelect);
                tvName    = v.findViewById(R.id.tvFileName);
                tvSize    = v.findViewById(R.id.tvFileSize);
            }
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    void addLog(String msg) {
        uiHandler.post(() -> {
            String cur = tvLog.getText().toString();
            String nl  = msg + "\n" + cur;
            if (nl.length() > 2000) nl = nl.substring(0, 1800) + "...\n";
            tvLog.setText(nl);
        });
    }

    private void showCredentialsDialog() {
        View view       = getLayoutInflater().inflate(R.layout.dialog_ftp_credentials, null);
        EditText etUser = view.findViewById(R.id.etFtpUserDialog);
        EditText etPass = view.findViewById(R.id.etFtpPassDialog);
        etUser.setText(currentUser);
        etPass.setText(currentPass);
        etUser.setHint(t("Usuario", "Username"));
        etPass.setHint(t("Contraseña", "Password"));
        new AlertDialog.Builder(this).setTitle(t("Credenciales FTP", "FTP Credentials")).setView(view)
                .setPositiveButton(t("Guardar", "Save"), (dialog, which) -> {
                    currentUser = etUser.getText().toString().trim();
                    currentPass = etPass.getText().toString().trim();
                    prefs.edit().putString(PREF_FTP_USER, currentUser).putString(PREF_FTP_PASS, currentPass).apply();
                    Toast.makeText(this, t("Credenciales guardadas", "Credentials saved"), Toast.LENGTH_SHORT).show();
                    if (isConnected) { disconnectFtp(); connectFtp(currentHost, currentPort, currentUser, currentPass); }
                })
                .setNegativeButton(t("Cancelar", "Cancel"), null).show();
    }

    private String formatSize(long bytes) {
        if (bytes >= 1_073_741_824) return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576)     return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1024)          return String.format("%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Transferencias FTP", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Progreso de transferencias FTP");
            notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(ch);
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
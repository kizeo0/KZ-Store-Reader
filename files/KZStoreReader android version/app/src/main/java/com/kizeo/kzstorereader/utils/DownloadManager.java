package com.kizeo.kzstorereader.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.kizeo.kzstorereader.R;

import java.util.concurrent.atomic.AtomicBoolean;

public class DownloadManager {

    private final Context      context;
    private final LinearLayout container;
    private final Handler      uiHandler;

    public DownloadManager(Context context, LinearLayout container, Handler uiHandler) {
        this.context   = context;
        this.container = container;
        this.uiHandler = uiHandler;
    }

    public void enqueue(String url, String suggestedTitle) {
        String rawDefault = suggestedTitle.replaceAll("[\\\\/*?:\"<>|]", "").trim();
        if (rawDefault.isEmpty()) rawDefault = "descarga";
        final String defaultName = rawDefault;

        uiHandler.post(() -> {
            EditText input = new EditText(context);
            input.setText(defaultName);
            input.setSelectAllOnFocus(true);
            input.setHint("nombre_del_archivo");

            new AlertDialog.Builder(context)
                    .setTitle("Nombre del archivo")
                    .setMessage("Se guardará en Descargas/ con extensión .pkg")
                    .setView(input)
                    .setPositiveButton("⬇ Descargar", (d, w) -> {
                        String chosen = input.getText().toString().trim();
                        if (chosen.isEmpty()) chosen = defaultName;
                        final String filename = chosen.replaceAll("[\\\\/*?:\"<>|]", "").trim();
                        startDownload(url, filename);
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
    }

    private void startDownload(String url, String filename) {
        int taskId = DownloadService.nextTaskId();

        View card = LayoutInflater.from(context)
                .inflate(R.layout.item_download_task, container, false);
        uiHandler.post(() -> container.addView(card));

        ProgressBar bar       = card.findViewById(R.id.progressDownload);
        TextView    tvTitle   = card.findViewById(R.id.tvDlTitle);
        TextView    tvStatus  = card.findViewById(R.id.tvDlStatus);
        TextView    tvSpeed   = card.findViewById(R.id.tvDlSpeed);
        View        btnPause  = card.findViewById(R.id.btnDlPause);
        View        btnCancel = card.findViewById(R.id.btnDlCancel);

        String shortName = filename.length() > 38
                ? filename.substring(0, 38) + "…" : filename;
        uiHandler.post(() -> tvTitle.setText(shortName + ".pkg"));

        AtomicBoolean uiPaused = new AtomicBoolean(false);

        DownloadService.register(taskId, new DownloadService.Callback() {
            @Override
            public void onProgress(int id, int progress, String status, String speed) {
                uiHandler.post(() -> {
                    bar.setProgress(Math.max(0, progress));
                    tvStatus.setText(status);
                    tvSpeed.setText(speed);
                });
            }
            @Override
            public void onPaused(int id) {
                uiHandler.post(() -> {
                    uiPaused.set(true);
                    ((TextView) btnPause).setText("▶");
                    tvStatus.setText("⏸ Pausado");
                });
            }
            @Override
            public void onResumed(int id) {
                uiHandler.post(() -> {
                    uiPaused.set(false);
                    ((TextView) btnPause).setText("⏸");
                    tvStatus.setText("Reanudando…");
                });
            }
            @Override
            public void onDone(int id, String fn) {
                uiHandler.post(() -> {
                    bar.setProgress(100);
                    tvStatus.setText("✔ Descargas/" + fn + ".pkg");
                    tvStatus.setTextColor(0xFF76FF03);
                    tvSpeed.setText("");
                    btnPause.setVisibility(View.GONE);
                    btnCancel.setVisibility(View.GONE);
                    Toast.makeText(context,
                            "✔ Guardado en Descargas/" + fn + ".pkg",
                            Toast.LENGTH_LONG).show();
                });
            }
            @Override
            public void onError(int id, String error) {
                uiHandler.post(() -> {
                    tvStatus.setText("❌ " + error);
                    tvStatus.setTextColor(0xFFFF5252);
                    tvSpeed.setText("");
                    // Eliminar la tarjeta tras error
                    container.removeView(card);
                });
            }
        });

        btnPause.setOnClickListener(v -> {
            String action = uiPaused.get()
                    ? DownloadService.ACTION_RESUME
                    : DownloadService.ACTION_PAUSE;
            sendServiceIntent(action, taskId);
        });

        btnCancel.setOnClickListener(v -> {
            sendServiceIntent(DownloadService.ACTION_CANCEL, taskId);
            DownloadService.unregister(taskId);
            uiHandler.post(() -> container.removeView(card));
        });

        Intent serviceIntent = new Intent(context, DownloadService.class)
                .setAction(DownloadService.ACTION_START)
                .putExtra(DownloadService.EXTRA_URL,      url)
                .putExtra(DownloadService.EXTRA_FILENAME, filename)
                .putExtra(DownloadService.EXTRA_TASK_ID,  taskId);
        ContextCompat.startForegroundService(context, serviceIntent);
    }

    private void sendServiceIntent(String action, int taskId) {
        context.startService(new Intent(context, DownloadService.class)
                .setAction(action)
                .putExtra(DownloadService.EXTRA_TASK_ID, taskId));
    }

    static String formatSize(long bytes) {
        if (bytes >= 1_073_741_824) return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576)     return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1024)          return String.format("%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
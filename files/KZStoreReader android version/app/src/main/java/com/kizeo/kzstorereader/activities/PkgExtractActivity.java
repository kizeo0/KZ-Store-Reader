package com.kizeo.kzstorereader.activities;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.kizeo.kzstorereader.R;
import com.kizeo.kzstorereader.utils.PkgExtractor;
import com.kizeo.kzstorereader.utils.StoreScanner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class PkgExtractActivity extends AppCompatActivity {

    public static final String EXTRA_PKG_URI = "pkg_uri";
    public static final String EXTRA_OUT_DIR = "out_dir";

    private TextView    tvStatus, tvLog, tvPercent;
    private ProgressBar progressBar;
    private Button      btnCancel, btnOpenStore;
    private ScrollView  logScroll;

    private volatile boolean cancelled  = false;
    private Thread           extractThread;
    private final Handler    uiHandler  = new Handler(Looper.getMainLooper());

    private boolean isEs = true; // idioma

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_pkg_extract);

        isEs = !"en".equals(getIntent().getStringExtra("lang"));

        tvStatus    = findViewById(R.id.tvExtractStatus);
        tvLog       = findViewById(R.id.tvExtractLog);
        tvPercent   = findViewById(R.id.tvExtractPercent);
        progressBar = findViewById(R.id.progressBarExtract);
        btnCancel   = findViewById(R.id.btnExtractCancel);
        btnOpenStore= findViewById(R.id.btnOpenStore);
        logScroll   = findViewById(R.id.logScroll);

        btnOpenStore.setVisibility(View.GONE);
        btnCancel.setText(isEs ? "Cancelar" : "Cancel");
        btnCancel.setOnClickListener(v -> {
            cancelled = true;
            tvStatus.setText(isEs ? "Cancelando…" : "Cancelling…");
            if (extractThread != null) extractThread.interrupt();
            finish();
        });

        String pkgUriStr = getIntent().getStringExtra(EXTRA_PKG_URI);
        String outDir    = getIntent().getStringExtra(EXTRA_OUT_DIR);

        if (pkgUriStr == null || outDir == null) {
            Toast.makeText(this, "Missing parameters", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        startExtraction(Uri.parse(pkgUriStr), outDir);
    }

    private void startExtraction(Uri pkgUri, String outDir) {
        tvStatus.setText(isEs ? "📦  Copiando PKG desde almacenamiento…"
                              : "📦  Copying PKG from storage…");

        extractThread = new Thread(() -> {
            File tempPkg = null;
            try {
                tempPkg = File.createTempFile("ps3pkg_", ".pkg", getCacheDir());
                appendLog(isEs ? "Copiando PKG al archivo temporal…"
                               : "Copying PKG to temp file…");

                ContentResolver cr = getContentResolver();
                try (InputStream in  = cr.openInputStream(pkgUri);
                     FileOutputStream out = new FileOutputStream(tempPkg)) {
                    if (in == null) throw new Exception("Cannot open URI stream");
                    byte[] buf = new byte[65536];
                    int n; long total = 0;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelled) throw new InterruptedException("Cancelled");
                        out.write(buf, 0, n);
                        total += n;
                    }
                    appendLog((isEs ? "Copiado " : "Copied ") + formatSize(total));
                }

                if (cancelled) return;
                uiHandler.post(() -> tvStatus.setText(isEs ? "📦  Extrayendo PKG…"
                                                           : "📦  Extracting PKG…"));

                PkgExtractor.ExtractResult result = PkgExtractor.extract(
                        tempPkg.getAbsolutePath(), outDir,
                        new PkgExtractor.ProgressCallback() {
                            @Override public void onProgress(long done, long total) {
                                int pct = total > 0 ? (int)(done * 100 / total) : 0;
                                uiHandler.post(() -> {
                                    progressBar.setProgress(pct);
                                    tvPercent.setText(pct + "%  "
                                            + formatSize(done) + " / " + formatSize(total));
                                });
                            }
                            @Override public void onLog(String message) { appendLog(message); }
                            @Override public boolean isCancelled()      { return cancelled; }
                        });

                uiHandler.post(() -> onExtractionDone(result, outDir));

            } catch (InterruptedException e) {
                appendLog(isEs ? "Cancelado." : "Cancelled.");
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                appendLog((isEs ? "Error: " : "Error: ") + msg);
                uiHandler.post(() -> {
                    tvStatus.setText("❌ " + msg);
                    tvStatus.setTextColor(0xFFFF5252);
                });
            } finally {
                if (tempPkg != null) tempPkg.delete();
            }
        });
        extractThread.start();
    }

    private void onExtractionDone(PkgExtractor.ExtractResult result, String outDir) {
        progressBar.setProgress(100);
        if (result.success) {
            tvStatus.setText(isEs ? "✔ ¡Extracción completa!" : "✔ Extraction complete!");
            tvStatus.setTextColor(0xFF76FF03);

            File extDir = new File(result.outDir);
            File xml    = StoreScanner.findMainXml(extDir);

            btnCancel.setText(isEs ? "Cerrar" : "Close");
            if (xml != null) {
                btnOpenStore.setVisibility(View.VISIBLE);
                btnOpenStore.setText(isEs ? "▶ Abrir tienda" : "▶ Open store");
                btnOpenStore.setOnClickListener(v -> {
                    Intent intent = new Intent(this, StoreViewerActivity.class);
                    intent.putExtra(StoreViewerActivity.EXTRA_XML_PATH, xml.getAbsolutePath());
                    intent.putExtra(StoreViewerActivity.EXTRA_STORE_ROOT, result.outDir);
                    intent.putExtra("lang", isEs ? "es" : "en");
                    startActivity(intent);
                    finish();
                });
            } else {
                appendLog(isEs ? "⚠ No se encontró XML principal. Abrí manualmente desde 'Mis Tiendas'."
                               : "⚠ No main XML found. Open manually from 'My Stores'.");
            }
        } else {
            tvStatus.setText("❌ " + result.error);
            tvStatus.setTextColor(0xFFFF5252);
            appendLog(isEs ? "Revisar el log para más detalles."
                           : "Check the log for details.");
        }
    }

    private void appendLog(String msg) {
        uiHandler.post(() -> {
            tvLog.append(msg + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1_073_741_824) return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576)     return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1024)          return String.format("%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
    }
}

package com.kizeo.kzstorereader.activities;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.kizeo.kzstorereader.R;
import com.kizeo.kzstorereader.utils.StoreScanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.widget.ImageView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "KZStorePrefs";
    public static final String PREF_LANG  = "lang";

    // URL de la API de GitHub Releases para detectar actualizaciones
    private static final String GITHUB_RELEASES_API =
            "https://api.github.com/repos/kizeo0/KZ-Store-Reader/releases/latest";
    private static final String GITHUB_RELEASES_PAGE =
            "https://github.com/kizeo0/KZ-Store-Reader/releases";

    // URL para la sección de créditos (canal de YouTube)
    private static final String CREDITS_URL =
            "https://www.youtube.com/channel/UC68BJa6n5j3BhxsdxFlGqFQ";

    // ── Sistema de strings bilingüe ───────────────────────────────────────────
    private static final String[][] S = {
            // [0] ES
            {
                    "By KiZeo — Emulador de Tienda PS3",
                    "Mis Tiendas",
                    "Sin tiendas aún\nExtraé un PKG primero",
                    "Extraer PKG",
                    "Desempaqueta un\narchivo .pkg",
                    "Abrir XML",
                    "Carga el XML\nde una tienda",
                    "FTP Manager",
                    "Conectar PS3 vía webMAN / MultiMAN",
                    "PKGs extraídos → Android/data",
                    "tienda", "tiendas disponibles",
                    "Idioma:", "Buscar actualización", "Créditos"
            },
            // [1] EN
            {
                    "By KiZeo — PS3 Store Emulator",
                    "My Stores",
                    "No stores yet\nExtract a PKG first",
                    "Extract PKG",
                    "Extract resources\nfrom a .pkg file",
                    "Open XML",
                    "Load the main XML\nof a store",
                    "FTP Manager",
                    "Connect PS3 via webMAN / MultiMAN",
                    "Extracted PKGs → Android/data",
                    "store", "stores available",
                    "Language:", "Check for update", "Credits"
            }
    };
    private static final int S_SUB=0, S_STORES=1, S_STORES_EMPTY=2,
            S_PKG=3, S_PKG_SUB=4, S_XML=5, S_XML_SUB=6,
            S_FTP=7, S_FTP_SUB=8, S_FOOTER=9,
            S_STORE_SING=10, S_STORE_PLUR=11,
            S_LANG=12, S_CHECK_UPDATE=13, S_CREDITS=14;

    private SharedPreferences prefs;
    private int langIdx = 0;
    private String t(int k) { return S[langIdx][k]; }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<String[]> permLauncher;
    private ActivityResultLauncher<Intent>   filePickLauncher;
    private ActivityResultLauncher<Intent>   pkgPickLauncher;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        View rootView = findViewById(R.id.rootScrollView);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        prefs   = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        langIdx = "en".equals(prefs.getString(PREF_LANG, "es")) ? 1 : 0;

        loadLogo();
        setupLaunchers();
        setupOverflowMenu();
        setupCards();
        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStoresCard();
    }

    // ── Menú ⋮ ────────────────────────────────────────────────────────────────

    private void setupOverflowMenu() {
        View btn = findViewById(R.id.btnOverflowMenu);
        btn.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, btn);
            MenuInflater inflater = popup.getMenuInflater();
            inflater.inflate(R.menu.main_menu, popup.getMenu());

            // Traducir los items del menú dinámicamente
            MenuItem updateItem = popup.getMenu().findItem(R.id.menu_check_update);
            if (updateItem != null) {
                updateItem.setTitle(t(S_CHECK_UPDATE));
            }
            MenuItem creditsItem = popup.getMenu().findItem(R.id.menu_credits);
            if (creditsItem != null) {
                creditsItem.setTitle(t(S_CREDITS));
            }

            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.menu_language) {
                    showLanguageDialog();
                    return true;
                } else if (id == R.id.menu_check_update) {
                    checkForUpdate();
                    return true;
                } else if (id == R.id.menu_credits) {
                    openCredits();
                    return true;
                }
                return false;
            });
            popup.show();
        });
        applyLanguage();
    }

    private void showLanguageDialog() {
        String[] options = {"Español", "English"};
        new AlertDialog.Builder(this)
                .setTitle(langIdx == 0 ? "Seleccionar idioma" : "Select language")
                .setSingleChoiceItems(options, langIdx, (dialog, which) -> {
                    langIdx = which;
                    prefs.edit().putString(PREF_LANG, which == 1 ? "en" : "es").apply();
                    applyLanguage();
                    dialog.dismiss();
                })
                .setNegativeButton(langIdx == 0 ? "Cancelar" : "Cancel", null)
                .show();
    }

    // ── Detección de actualización (sin BuildConfig) ─────────────────────────

    private String getCurrentVersion() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pInfo.versionName.trim().replace("v", "");
        } catch (PackageManager.NameNotFoundException e) {
            Log.e("MainActivity", "No se pudo obtener la versión", e);
            return "0.0";
        }
    }

    private void checkForUpdate() {
        Toast.makeText(this,
                langIdx == 0 ? "Buscando actualizaciones…" : "Checking for updates…",
                Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            try {
                URL url = new URL(GITHUB_RELEASES_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "KZStoreReader-Android");
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    showUpdateError();
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                conn.disconnect();

                JSONObject json      = new JSONObject(sb.toString());
                String     latestTag = json.getString("tag_name").trim().replace("v", "");
                String     currentVn = getCurrentVersion();

                boolean hasUpdate = compareVersions(latestTag, currentVn) > 0;

                uiHandler.post(() -> {
                    if (hasUpdate) {
                        showUpdateAvailableDialog(latestTag);
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle(langIdx == 0 ? "Sin actualizaciones" : "Up to date")
                                .setMessage(langIdx == 0
                                        ? "Ya tenés la última versión (" + currentVn + ")."
                                        : "You already have the latest version (" + currentVn + ").")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });

            } catch (Exception e) {
                Log.w("MainActivity", "checkForUpdate: " + e.getMessage());
                showUpdateError();
            }
        });
    }

    private void showUpdateAvailableDialog(String latestTag) {
        new AlertDialog.Builder(this)
                .setTitle(langIdx == 0 ? "🔄 Actualización disponible" : "🔄 Update available")
                .setMessage(langIdx == 0
                        ? "Versión " + latestTag + " disponible en GitHub.\n\n¿Querés descargarla?"
                        : "Version " + latestTag + " available on GitHub.\n\nDo you want to download it?")
                .setPositiveButton(langIdx == 0 ? "Ir a GitHub" : "Go to GitHub", (d, w) ->
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(GITHUB_RELEASES_PAGE))))
                .setNegativeButton(langIdx == 0 ? "Ahora no" : "Not now", null)
                .show();
    }

    private void showUpdateError() {
        uiHandler.post(() ->
                Toast.makeText(this,
                        langIdx == 0
                                ? "No se pudo verificar actualizaciones. Revisá tu conexión."
                                : "Could not check for updates. Check your connection.",
                        Toast.LENGTH_LONG).show());
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    // ── Créditos ─────────────────────────────────────────────────────────────

    private void openCredits() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(CREDITS_URL));
        startActivity(intent);
    }

    // ── Idioma ────────────────────────────────────────────────────────────────

    private void applyLanguage() {
        ((TextView) findViewById(R.id.tvSubtitle)).setText(t(S_SUB));
        ((TextView) findViewById(R.id.tvCardStoresTitle)).setText(t(S_STORES));
        ((TextView) findViewById(R.id.tvCardPkgTitle)).setText(t(S_PKG));
        ((TextView) findViewById(R.id.tvCardPkgSub)).setText(t(S_PKG_SUB));
        ((TextView) findViewById(R.id.tvCardXmlTitle)).setText(t(S_XML));
        ((TextView) findViewById(R.id.tvCardXmlSub)).setText(t(S_XML_SUB));
        ((TextView) findViewById(R.id.tvCardFtpTitle)).setText(t(S_FTP));
        ((TextView) findViewById(R.id.tvCardFtpSub)).setText(t(S_FTP_SUB));
        ((TextView) findViewById(R.id.tvFooter)).setText(t(S_FOOTER));
        refreshStoresCard();
    }

    // ── Logo ──────────────────────────────────────────────────────────────────

    private void loadLogo() {
        ImageView iv = findViewById(R.id.ivMainLogo);
        if (iv == null) return;
        try {
            InputStream is = getAssets().open("mainlogo.png");
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            iv.setImageBitmap(bmp);
        } catch (Exception e) {
            Log.w("MainActivity", "mainlogo.png not found in assets: " + e.getMessage());
            iv.setVisibility(View.GONE);
        }
    }

    // ── Cards ─────────────────────────────────────────────────────────────────

    private void setupCards() {
        findViewById(R.id.cardStores).setOnClickListener(v -> openMyStores());
        findViewById(R.id.cardPkg).setOnClickListener(v   -> pickPkgFile());
        findViewById(R.id.cardXml).setOnClickListener(v   -> pickXmlFile());
        findViewById(R.id.cardPsn).setOnClickListener(v   -> openPsnDatabase());
        findViewById(R.id.cardFtp).setOnClickListener(v   -> openFtp());
    }

    private void refreshStoresCard() {
        List<StoreScanner.StoreEntry> stores = StoreScanner.scanStores(getStoreDataDir());
        TextView tv = findViewById(R.id.tvStoreCount);
        if (stores.isEmpty()) {
            tv.setText(t(S_STORES_EMPTY));
            tv.setTextColor(0xFF555555);
        } else {
            String word = stores.size() == 1 ? t(S_STORE_SING) : t(S_STORE_PLUR);
            tv.setText(stores.size() + " " + word);
            tv.setTextColor(0xFF76FF03);
        }
    }

    // ── Navegación ────────────────────────────────────────────────────────────

    private void openMyStores() {
        List<StoreScanner.StoreEntry> stores = StoreScanner.scanStores(getStoreDataDir());
        if (stores.isEmpty()) {
            Toast.makeText(this,
                    langIdx == 0 ? "No hay tiendas. Extraé un PKG primero."
                            : "No stores. Extract a PKG first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, StoreListActivity.class);
        i.putExtra("data_dir", getStoreDataDir().getAbsolutePath());
        i.putExtra("lang", langIdx == 1 ? "en" : "es");
        startActivity(i);
    }

    private void pickPkgFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        pkgPickLauncher.launch(i);
    }

    private void pickXmlFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        filePickLauncher.launch(i);
    }

    private void openPsnDatabase() {
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://ps3-pro.github.io/PSN-Content/files/website/modern.html")));
    }

    private void openFtp() {
        startActivity(new Intent(this, FtpActivity.class));
    }

    public File getStoreDataDir() {
        File dir = new File(getExternalFilesDir(null), "data");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ── Launchers ─────────────────────────────────────────────────────────────

    private void setupLaunchers() {
        permLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), r -> {});

        filePickLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            Intent i = new Intent(this, StoreViewerActivity.class);
                            i.putExtra(StoreViewerActivity.EXTRA_XML_URI, uri.toString());
                            i.putExtra("lang", langIdx == 1 ? "en" : "es");
                            startActivity(i);
                        }
                    }
                });

        pkgPickLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            Intent i = new Intent(this, PkgExtractActivity.class);
                            i.putExtra(PkgExtractActivity.EXTRA_PKG_URI, uri.toString());
                            i.putExtra(PkgExtractActivity.EXTRA_OUT_DIR,
                                    getStoreDataDir().getAbsolutePath());
                            i.putExtra("lang", langIdx == 1 ? "en" : "es");
                            startActivity(i);
                        }
                    }
                });
    }

    // ── Permisos ──────────────────────────────────────────────────────────────

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(new String[]{ Manifest.permission.POST_NOTIFICATIONS });
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("Permiso de almacenamiento")
                        .setMessage("KZ Store Reader necesita acceso a todos los archivos para leer PKGs.")
                        .setPositiveButton("Conceder", (d, w) -> {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        })
                        .setNegativeButton("Saltar", null).show();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                });
            }
        }
    }
}
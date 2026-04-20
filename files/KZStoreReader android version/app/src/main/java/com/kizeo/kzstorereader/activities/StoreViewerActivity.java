package com.kizeo.kzstorereader.activities;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kizeo.kzstorereader.R;
import com.kizeo.kzstorereader.utils.DownloadManager;
import com.kizeo.kzstorereader.utils.StoreXmlParser;
import com.kizeo.kzstorereader.utils.UrlResolver;

import org.w3c.dom.Document;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.util.LruCache;

public class StoreViewerActivity extends AppCompatActivity {

    public static final String EXTRA_XML_URI    = "xml_uri";
    public static final String EXTRA_XML_PATH   = "xml_path";
    public static final String EXTRA_STORE_ROOT = "store_root";

    interface OnItemClick     { void onClick(StoreXmlParser.StoreItem item); }
    interface OnItemLongClick { boolean onLongClick(StoreXmlParser.StoreItem item); }

    private RecyclerView  rvItems;
    private LinearLayout  queueContainer;
    private TextView      tvStoreName, tvEmptyState;
    private ProgressBar   progressBar;

    private static class NavEntry {
        final String xmlPath, viewId, title;
        final List<StoreXmlParser.StoreItem> items;
        NavEntry(String xp, String vid, String t, List<StoreXmlParser.StoreItem> i) {
            xmlPath=xp; viewId=vid; title=t; items=i;
        }
    }
    private final Deque<NavEntry> history = new ArrayDeque<>();

    private String currentXmlPath = "";
    private String currentViewId  = "";
    private String usrdirPath = "";
    private String storeRoot  = "";
    private String dataDir    = "";

    private Document              currentDoc;
    private StoreXmlParser.XmlFormat currentFmt;
    private StoreItemAdapter      adapter;
    private DownloadManager       downloadManager;

    private final ExecutorService executor  = Executors.newCachedThreadPool();
    private final Handler         uiHandler = new Handler(Looper.getMainLooper());

    private LruCache<String, Bitmap> bitmapCache;

    private boolean isEs = true;
    private String t(String es, String en) { return isEs ? es : en; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_store_viewer);

        isEs = !"en".equals(getIntent().getStringExtra("lang"));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("KZ Store Reader");
        }

        View root = findViewById(R.id.storeViewerRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            toolbar.setPadding(sys.left, sys.top, sys.right, 0);
            toolbar.getLayoutParams().height =
                    getResources().getDimensionPixelSize(R.dimen.toolbar_height) + sys.top;
            return insets;
        });

        rvItems        = findViewById(R.id.recyclerItems);
        queueContainer = findViewById(R.id.downloadQueueContainer);
        tvStoreName    = findViewById(R.id.tvStoreName);
        tvEmptyState   = findViewById(R.id.tvEmptyState);
        progressBar    = findViewById(R.id.progressLoading);

        downloadManager = new DownloadManager(this, queueContainer, uiHandler);

        // Cache de bitmaps
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        bitmapCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        adapter = new StoreItemAdapter(new ArrayList<>(),
                this::onItemClick, this::onItemLongClick);
        rvItems.setLayoutManager(new LinearLayoutManager(this));
        rvItems.addItemDecoration(
                new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        rvItems.setAdapter(adapter);

        String xmlPath = getIntent().getStringExtra(EXTRA_XML_PATH);
        storeRoot = nvl(getIntent().getStringExtra(EXTRA_STORE_ROOT));
        dataDir   = new File(storeRoot).getParent() != null
                ? new File(storeRoot).getParent() : storeRoot;

        if (xmlPath != null) {
            usrdirPath = new File(xmlPath).getParent();
            if (usrdirPath != null && "USRDIR".equalsIgnoreCase(
                    new File(usrdirPath).getName())) {
                storeRoot = new File(usrdirPath).getParent();
            }
            loadXml(xmlPath, null);
        } else {
            String xmlUri = getIntent().getStringExtra(EXTRA_XML_URI);
            if (xmlUri != null) loadXmlFromUri(Uri.parse(xmlUri));
        }
    }

    private void loadXml(String xmlPath, String viewId) {
        showProgress(true);
        executor.execute(() -> {
            String content = readFile(xmlPath);
            if (content == null) {
                uiHandler.post(() -> showError(t("No se puede leer: ", "Cannot read: ") + xmlPath));
                return;
            }
            StoreXmlParser.ParseResult result = StoreXmlParser.parse(content);
            if (result.error != null) {
                uiHandler.post(() -> showError("Error XML:\n" + result.error));
                return;
            }
            currentDoc     = result.document;
            currentFmt     = result.format;
            currentXmlPath = xmlPath;
            currentViewId  = viewId != null ? viewId : "";
            String newUsrdir = new File(xmlPath).getParent();
            if (newUsrdir != null) usrdirPath = newUsrdir;
            List<StoreXmlParser.StoreItem> items;
            if (viewId != null && !viewId.isEmpty()) {
                items = StoreXmlParser.extractItemsForView(currentDoc, currentFmt, viewId);
            } else {
                items = StoreXmlParser.extractItems(currentDoc, currentFmt);
            }
            String title = new File(xmlPath).getName() +
                    (viewId != null && !viewId.isEmpty() ? " / " + viewId : "");
            uiHandler.post(() -> {
                showProgress(false);
                showItems(items, title);
            });
        });
    }

    private void loadXmlFromUri(Uri uri) {
        showProgress(true);
        executor.execute(() -> {
            String content = readUri(uri);
            if (content == null) {
                uiHandler.post(() -> showError(t("No se puede leer el XML.", "Cannot read XML.")));
                return;
            }
            StoreXmlParser.ParseResult result = StoreXmlParser.parse(content);
            if (result.error != null) {
                uiHandler.post(() -> showError("Error XML:\n" + result.error));
                return;
            }
            currentDoc     = result.document;
            currentFmt     = result.format;
            currentXmlPath = uri.toString();
            List<StoreXmlParser.StoreItem> items =
                    StoreXmlParser.extractItems(currentDoc, currentFmt);
            uiHandler.post(() -> { showProgress(false); showItems(items, "Store"); });
        });
    }

    private void showItems(List<StoreXmlParser.StoreItem> items, String title) {
        tvStoreName.setText(title);
        tvEmptyState.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (items.isEmpty())
            tvEmptyState.setText(t("Sin ítems en esta vista.", "No items in this view."));
        adapter.setItems(items);
        rvItems.scrollToPosition(0);
    }

    private void onItemClick(StoreXmlParser.StoreItem item) {
        String src = item.src;
        String dl  = item.dl;
        if (!src.isEmpty() && !"None".equals(src)) {
            history.push(new NavEntry(currentXmlPath, currentViewId,
                    tvStoreName.getText().toString(), adapter.getCurrentItems()));
            String viewId = null;
            String xmlRef = src;
            if (src.contains("#")) {
                String[] parts = src.split("#", 2);
                xmlRef  = parts[0];
                viewId  = parts[1];
            }
            final String fViewId = viewId;
            if (xmlRef.isEmpty()) {
                loadXml(currentXmlPath, fViewId);
            } else {
                final String finalXmlRef = xmlRef;
                final String finalUsrdirPath = usrdirPath;
                final String finalStoreRoot = storeRoot;
                final String finalDataDir = dataDir;
                executor.execute(() -> {
                    String target = StoreXmlParser.resolveLocalPath(
                            finalXmlRef, finalUsrdirPath, finalStoreRoot, finalDataDir);
                    if (target != null) {
                        final String ft = target;
                        uiHandler.post(() -> loadXml(ft, fViewId));
                    } else {
                        File direct = new File(finalXmlRef);
                        if (!direct.isAbsolute() && finalUsrdirPath != null) {
                            direct = new File(finalUsrdirPath, finalXmlRef);
                        }
                        if (direct.exists()) {
                            final String ft = direct.getAbsolutePath();
                            uiHandler.post(() -> loadXml(ft, fViewId));
                        } else {
                            uiHandler.post(() -> Toast.makeText(this,
                                    t("No se encontró: ", "Not found: ") + src,
                                    Toast.LENGTH_LONG).show());
                            if (!history.isEmpty()) history.pop();
                        }
                    }
                });
            }
            return;
        }
        if (!dl.isEmpty() && !"None".equals(dl)) {
            if (dl.startsWith("http")) {
                showDownloadDialog(item);
            } else if (dl.toLowerCase().contains("fcopy")) {
                Toast.makeText(this, "Comando PS3 interno: " + dl, Toast.LENGTH_LONG).show();
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(dl)));
            }
        }
    }

    private boolean onItemLongClick(StoreXmlParser.StoreItem item) {
        String url = !item.dl.isEmpty() ? item.dl : item.src;
        if (!url.isEmpty()) {
            copyToClipboard(url);
            Toast.makeText(this, t("✔ URL copiada", "✔ URL copied"), Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void showDownloadDialog(StoreXmlParser.StoreItem item) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(t("⏳ Resolviendo…", "⏳ Resolving…"))
                .setMessage(item.title)
                .setNegativeButton(t("Cancelar", "Cancel"), null)
                .create();
        progress.show();
        executor.execute(() -> {
            String resolved = UrlResolver.resolve(item.dl);
            uiHandler.post(() -> {
                progress.dismiss();
                showDownloadOptions(item, resolved);
            });
        });
    }

    private void showDownloadOptions(StoreXmlParser.StoreItem item, String resolvedUrl) {
        String msg = item.title;
        if (!item.gameId.isEmpty()) msg += "\n\nID: " + item.gameId;
        if (!item.infoTxt.isEmpty()) msg += "\n" + item.infoTxt;
        msg += "\n\n" + resolvedUrl;
        new AlertDialog.Builder(this)
                .setTitle(t("📦 Descarga", "📦 Download"))
                .setMessage(msg)
                .setPositiveButton(t("⬇ Descargar", "⬇ Download"),
                        (d, w) -> downloadManager.enqueue(resolvedUrl, item.title))
                .setNeutralButton(t("🔗 Copiar", "🔗 Copy"), (d, w) -> {
                    copyToClipboard(resolvedUrl);
                    Toast.makeText(this, t("✔ Copiado!", "✔ Copied!"),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(t("🌐 Abrir", "🌐 Open"),
                        (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(resolvedUrl))))
                .show();
    }

    @Override
    public void onBackPressed() {
        if (!history.isEmpty()) {
            NavEntry prev = history.pop();
            currentXmlPath = prev.xmlPath;
            currentViewId  = prev.viewId;
            tvStoreName.setText(prev.title);
            adapter.setItems(prev.items);
            rvItems.scrollToPosition(0);
            tvEmptyState.setVisibility(prev.items.isEmpty() ? View.VISIBLE : View.GONE);
        } else {
            super.onBackPressed();
        }
    }

    @Override public boolean onSupportNavigateUp() { onBackPressed(); return true; }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.store_viewer_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_ftp) {
            startActivity(new Intent(this, FtpActivity.class)); return true;
        } else if (item.getItemId() == R.id.menu_psn) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://ps3-pro.github.io/PSN-Content/files/website/modern.html")));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    class StoreItemAdapter extends RecyclerView.Adapter<StoreItemAdapter.VH> {
        private List<StoreXmlParser.StoreItem> items;
        private final OnItemClick     cl;
        private final OnItemLongClick lcl;

        StoreItemAdapter(List<StoreXmlParser.StoreItem> items, OnItemClick cl, OnItemLongClick lcl) {
            this.items=items; this.cl=cl; this.lcl=lcl;
        }

        void setItems(List<StoreXmlParser.StoreItem> i) { items=i; notifyDataSetChanged(); }
        List<StoreXmlParser.StoreItem> getCurrentItems() { return new ArrayList<>(items); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_store_game, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int pos) {
            holder.bind(items.get(pos));
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView  tvTitle, tvGameId, tvInfo, tvSize;
            View      vCategory;
            String currentIconPath; // Para verificar si la imagen cargada aún corresponde

            VH(View v) {
                super(v);
                ivIcon    = v.findViewById(R.id.ivGameIcon);
                tvTitle   = v.findViewById(R.id.tvGameTitle);
                tvGameId  = v.findViewById(R.id.tvGameId);
                tvInfo    = v.findViewById(R.id.tvGameInfo);
                tvSize    = v.findViewById(R.id.tvGameSize);
                vCategory = v.findViewById(R.id.vCategoryIndicator);
            }

            void bind(StoreXmlParser.StoreItem item) {
                tvTitle.setText(item.title);
                tvGameId.setText(item.gameId);
                tvGameId.setVisibility(item.gameId.isEmpty() ? View.GONE : View.VISIBLE);
                tvInfo.setText(item.infoTxt);
                tvInfo.setVisibility(item.infoTxt.isEmpty() ? View.GONE : View.VISIBLE);
                tvSize.setVisibility(View.GONE);
                boolean isCategory = !item.src.isEmpty() && item.dl.isEmpty();
                vCategory.setVisibility(isCategory ? View.VISIBLE : View.GONE);

                // Guardar el icono actual que se debe mostrar
                currentIconPath = item.iconPath;
                // Poner placeholder mientras carga
                ivIcon.setImageResource(R.drawable.ic_ps3_game_placeholder);

                if (!item.iconPath.isEmpty()) {
                    loadIconForViewHolder(this, item);
                }

                itemView.setOnClickListener(v -> cl.onClick(item));
                itemView.setOnLongClickListener(v -> lcl.onLongClick(item));
            }
        }
    }

    private void loadIconForViewHolder(StoreItemAdapter.VH holder, StoreXmlParser.StoreItem item) {
        if (item.iconPath == null || item.iconPath.isEmpty()) return;

        String cacheKey = item.iconPath + "|" + usrdirPath + "|" + storeRoot + "" + dataDir;
        Bitmap cached = bitmapCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            // Verificar que el ViewHolder aún corresponde a este item
            if (holder.currentIconPath != null && holder.currentIconPath.equals(item.iconPath)) {
                holder.ivIcon.setImageBitmap(cached);
            }
            return;
        }

        executor.execute(() -> {
            Bitmap bmp = resolveIcon(item.iconPath);
            if (bmp != null) {
                bitmapCache.put(cacheKey, bmp);
                uiHandler.post(() -> {
                    // Verificar que el ViewHolder no haya sido reciclado y que aún muestre el mismo item
                    if (holder.getAdapterPosition() != RecyclerView.NO_POSITION &&
                            holder.currentIconPath != null &&
                            holder.currentIconPath.equals(item.iconPath)) {
                        holder.ivIcon.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    private Bitmap resolveIcon(String iconPath) {
        if (iconPath == null || iconPath.isEmpty()) return null;
        try {
            String localPath = StoreXmlParser.resolveLocalPath(iconPath, usrdirPath, storeRoot, dataDir);
            if (localPath != null) {
                return BitmapFactory.decodeFile(localPath);
            }
            if (iconPath.startsWith("http")) {
                java.net.URL url = new java.net.URL(iconPath);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                try (InputStream is = conn.getInputStream()) {
                    return BitmapFactory.decodeStream(is);
                }
            }
        } catch (Exception e) {
            Log.d("StoreViewer", "Icon load failed: " + iconPath + " → " + e.getMessage());
        }
        return null;
    }

    private String readFile(String path) {
        try (FileInputStream fis = new FileInputStream(new File(path))) {
            byte[] buf = fis.readAllBytes();
            return new String(buf, "UTF-8");
        } catch (Exception e) { return null; }
    }

    private String readUri(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            byte[] buf = is.readAllBytes();
            is.close();
            return new String(buf, "UTF-8");
        } catch (Exception e) { return null; }
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("URL", text));
    }

    private void showProgress(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showError(String msg) {
        showProgress(false);
        tvEmptyState.setText(msg);
        tvEmptyState.setVisibility(View.VISIBLE);
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
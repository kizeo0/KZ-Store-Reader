package com.kizeo.kzstorereader.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kizeo.kzstorereader.R;
import com.kizeo.kzstorereader.utils.StoreScanner;

import java.io.File;
import java.util.List;

public class StoreListActivity extends AppCompatActivity {

    private boolean isEs = true;
    private String t(String es, String en) { return isEs ? es : en; }

    private List<StoreScanner.StoreEntry> stores;
    private StoresAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_store_list);

        isEs = !"en".equals(getIntent().getStringExtra("lang"));

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(t("Mis Tiendas", "My Stores"));
        }

        String dataDir = getIntent().getStringExtra("data_dir");
        stores = StoreScanner.scanStores(new File(dataDir));

        TextView tvHeader = findViewById(R.id.tvStoreListHeader);
        String storeWord = stores.size() == 1
                ? t("tienda", "store") : t("tiendas", "stores");
        tvHeader.setText(stores.size() + " " + storeWord
                + t(" en data/", " in data/"));

        RecyclerView rv = findViewById(R.id.recyclerStores);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        adapter = new StoresAdapter(stores);
        rv.setAdapter(adapter);
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }

    // Método para refrescar la lista después de renombrar/borrar
    private void refreshList() {
        String dataDir = getIntent().getStringExtra("data_dir");
        stores = StoreScanner.scanStores(new File(dataDir));
        adapter.updateStores(stores);
        TextView tvHeader = findViewById(R.id.tvStoreListHeader);
        String storeWord = stores.size() == 1
                ? t("tienda", "store") : t("tiendas", "stores");
        tvHeader.setText(stores.size() + " " + storeWord
                + t(" en data/", " in data/"));
    }

    class StoresAdapter extends RecyclerView.Adapter<StoresAdapter.VH> {
        private List<StoreScanner.StoreEntry> stores;

        StoresAdapter(List<StoreScanner.StoreEntry> s) { stores = s; }

        public void updateStores(List<StoreScanner.StoreEntry> newStores) {
            this.stores = newStores;
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_store_entry, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int pos) {
            StoreScanner.StoreEntry e = stores.get(pos);
            holder.tvName.setText(e.name);
            holder.tvPath.setText(e.xmlPath);
            // Click normal: abrir tienda
            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(StoreListActivity.this, StoreViewerActivity.class);
                intent.putExtra(StoreViewerActivity.EXTRA_XML_PATH, e.xmlPath);
                intent.putExtra(StoreViewerActivity.EXTRA_STORE_ROOT, e.folderPath);
                intent.putExtra("lang", isEs ? "es" : "en");
                startActivity(intent);
            });
            // Long click: mostrar menú contextual (renombrar / borrar)
            holder.itemView.setOnLongClickListener(v -> {
                showContextMenu(e, pos);
                return true;
            });
        }

        @Override public int getItemCount() { return stores.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvPath;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvStoreName);
                tvPath = v.findViewById(R.id.tvStorePath);
            }
        }
    }

    private void showContextMenu(StoreScanner.StoreEntry entry, int position) {
        String[] options = {
                t("Renombrar", "Rename"),
                t("Borrar tienda", "Delete store")
        };
        new AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        renameStore(entry, position);
                    } else {
                        deleteStore(entry, position);
                    }
                })
                .show();
    }

    private void renameStore(StoreScanner.StoreEntry entry, int position) {
        EditText input = new EditText(this);
        input.setText(entry.name);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(t("Renombrar tienda", "Rename store"))
                .setMessage(t("Nuevo nombre:", "New name:"))
                .setView(input)
                .setPositiveButton(t("Renombrar", "Rename"), (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, t("El nombre no puede estar vacío", "Name cannot be empty"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Sanitizar nombre (sin caracteres raros)
                    newName = newName.replaceAll("[\\\\/*?:\"<>|]", "");
                    File oldFolder = new File(entry.folderPath);
                    File newFolder = new File(oldFolder.getParentFile(), newName);
                    if (newFolder.exists()) {
                        Toast.makeText(this, t("Ya existe una tienda con ese nombre", "A store with that name already exists"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (oldFolder.renameTo(newFolder)) {
                        Toast.makeText(this, t("Renombrada a " + newName, "Renamed to " + newName), Toast.LENGTH_SHORT).show();
                        refreshList(); // actualizar la lista
                    } else {
                        Toast.makeText(this, t("Error al renombrar", "Rename failed"), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(t("Cancelar", "Cancel"), null)
                .show();
    }

    private void deleteStore(StoreScanner.StoreEntry entry, int position) {
        new AlertDialog.Builder(this)
                .setTitle(t("Borrar tienda", "Delete store"))
                .setMessage(t("¿Borrar \"" + entry.name + "\" permanentemente?", "Delete \"" + entry.name + "\" permanently?"))
                .setPositiveButton(t("Borrar", "Delete"), (d, w) -> {
                    boolean deleted = deleteRecursive(new File(entry.folderPath));
                    if (deleted) {
                        Toast.makeText(this, t("Tienda borrada", "Store deleted"), Toast.LENGTH_SHORT).show();
                        refreshList();
                    } else {
                        Toast.makeText(this, t("Error al borrar", "Delete failed"), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(t("Cancelar", "Cancel"), null)
                .show();
    }

    private boolean deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return fileOrDirectory.delete();
    }
}
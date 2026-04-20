# KZ Store Reader — Android Port
### By KiZeo | Android port of ps3_store.py

---

## Estructura del proyecto

```
KZStoreReader/
├── app/
│   ├── build.gradle.kts                  ← Dependencias del módulo
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/kizeo/kzstorereader/
│       │   ├── activities/
│       │   │   ├── MainActivity.java         ← Menú de inicio (4 cards)
│       │   │   ├── StoreViewerActivity.java  ← Lector de tienda XML
│       │   │   ├── StoreListActivity.java    ← Lista "Mis Tiendas"
│       │   │   ├── PkgExtractActivity.java   ← Extractor de .pkg
│       │   │   └── FtpActivity.java          ← Panel FTP dual local/remoto
│       │   └── utils/
│       │       ├── PkgExtractor.java         ← Motor cripto SHA-1 (port del .py)
│       │       ├── StoreXmlParser.java       ← Parser XMB + ZukoStore
│       │       ├── StoreScanner.java         ← Escaneo de carpeta data/
│       │       ├── DownloadManager.java      ← Cola de descargas con velocidad
│       │       └── UrlResolver.java          ← Bypass PS3 + Mediafire
│       └── res/
│           ├── layout/                       ← 7 layouts XML
│           ├── drawable/                     ← Íconos vectoriales
│           ├── menu/                         ← Menú toolbar
│           ├── values/                       ← Colores, temas, strings
│           └── xml/
│               └── network_security_config.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
    └── libs.versions.toml
```

---

## Equivalencias Python → Android

| Python (.py)            | Android                          |
|-------------------------|----------------------------------|
| `extract_pkg()`         | `PkgExtractor.extract()`         |
| `PkgCrypt` (SHA-1)      | `PkgCrypt` inner class (Java)    |
| `parse_store_xml()`     | `StoreXmlParser.parse()`         |
| `preprocess_xml()`      | `StoreXmlParser.preprocess()`    |
| `scan_stores()`         | `StoreScanner.scanStores()`      |
| `resolve_download_url()`| `UrlResolver.resolve()`          |
| `_resolve_mediafire()`  | `UrlResolver.resolveMediafire()` |
| `_resolve_ps3_*()`      | `UrlResolver.resolvePS3Protected()`|
| `DownloadTask`          | `DownloadManager.enqueue()`      |
| `FTPPanel`              | `FtpActivity` + Apache Commons Net|
| `StartupDialog`         | `MainActivity` con CardViews     |
| `App` (Treeview)        | `StoreViewerActivity` (RecyclerView)|

---

## Cómo importar en Android Studio

1. Abrí Android Studio → **Open** → seleccioná la carpeta `KZStoreReader/`
2. Esperá que Gradle sincronice (descarga `commons-net:3.10.0` automáticamente)
3. Conectá tu teléfono o creá un emulador API 26+
4. **Run ▶**

### Requisitos
- Android Studio Hedgehog o superior
- JDK 11+
- Android 8.0+ en el dispositivo (minSdk = 26)
- Conexión a internet para descargas y Mediafire

---

## Permisos necesarios

| Permiso | Para qué |
|---------|---------|
| `INTERNET` | Descargas, Mediafire, PSN DB |
| `READ/WRITE_EXTERNAL_STORAGE` | Leer PKGs desde el almacenamiento (Android ≤ 9) |
| `MANAGE_EXTERNAL_STORAGE` | Acceso total para Android 11+ |

En Android 11+, la app pedirá acceso a "Todos los archivos" al iniciar.

---

## Dónde se guardan las tiendas

```
Android/data/com.kizeo.kzstorereader/files/data/
    └── CONTENT_ID/
        └── USRDIR/
            ├── store.xml          ← XML principal
            ├── icons/
            └── ...
```

Los PKGs descargados van a:
```
Android/data/com.kizeo.kzstorereader/files/Downloads/
```

---

## FTP — Configuración PS3

La app usa **Apache Commons Net** con el mismo flujo que el `.py`:

1. **CWD** al directorio deseado
2. **LIST** sin argumento (compatible con `webMAN`, `MultiMAN`, `ps3ftp.c`)
3. Parse formato Unix LIST en big-endian

**Configuración en PS3:**
- webMAN MOS o MultiMAN debe estar activo
- Puerto por defecto: **21**
- Usuario/contraseña: **anonymous** / (vacío)
- IP: la IP local de tu PS3 (ej. `192.168.1.100`)

---

## Notas técnicas

### Motor PKG (`PkgExtractor.java`)
- Soporta `PKG_NORMAL (0x00000001)` — tiendas homebrew
- Rechaza `PKG_SIGNED (0x80000001)` — PKGs retail cifrados
- Keystream: SHA-1 sobre contexto de 64 bytes con contador big-endian
- PKGs > 128MB: descifrado por streaming a archivo temporal (evita OOM)
- PKGs ≤ 128MB: descifrado en memoria

### Parser XML (`StoreXmlParser.java`)
- Detecta automáticamente formato **XMB** (PS3 estándar) vs **ZukoStore** (compacto)
- Sanea comentarios `<!--- --->` inválidos antes de parsear
- Escapa `&` sueltos que rompen el parser DOM

### UrlResolver (`UrlResolver.java`)
- Prueba Mediafire API JSON primero (más rápido, sin scraping)
- Fallback a scraping HTML con User-Agent de Chrome
- Bypass PS3: prueba 5 User-Agents distintos de PlayStation 3
- Extrae quickkey de Mediafire para la API pública

---

## Dependencias clave

```kotlin
// Apache Commons Net — FTP con PASV, binary mode, CWD+LIST
implementation("commons-net:commons-net:3.10.0")

// Material Design 3 — Theming oscuro PS3
implementation("com.google.android.material:material:1.12.0")

// RecyclerView — Lista de juegos con iconos
implementation("androidx.recyclerview:recyclerview:1.3.2")

// CardView — Cards del menú principal
implementation("androidx.cardview:cardview:1.0.0")
```

---

## Diferencias con la versión PC

| Feature | PC (.py) | Android |
|---------|---------|---------|
| Selección de PKG | `filedialog` | `ACTION_OPEN_DOCUMENT` (SAF) |
| Iconos PS3 | PIL/ImageTk | `BitmapFactory` + coroutine |
| Descarga | `requests` streaming | `HttpURLConnection` + `ExecutorService` |
| FTP | `ftplib` (stdlib) | Apache Commons Net |
| UI items | `ttk.Treeview` 64px rows | `RecyclerView` + `CardView` |
| Idiomas | ES/EN selector | Futuro: `strings.xml` por locale |
| Drag & Drop FTP | Sí (tkinter) | No (táctil: botones → y ←) |

# 🎮 KZ Store Reader

**Emulador de Tienda PS3 para PC**  
Lee y navega por las tiendas digitales de PlayStation 3 (ZukoStore, PS3Store, etc.) directamente desde tu ordenador. Extrae PKGs, descarga juegos y accede a tu PS3 vía FTP.

![ICON](https://github.com/user-attachments/assets/f525ce1d-2cfb-49af-b415-604270dd7737)

---

## ✨ Características

- 📦 **Extracción de PKGs** – Desempaqueta cualquier PKG de tienda PS3 y extrae su estructura XML y recursos.
- 🏪 **Visor de tiendas** – Carga el XML principal de una tienda y muestra todos sus juegos/ítems con iconos, IDs e información.
- 🌐 **Bypass de enlaces protegidos** – Resuelve automáticamente enlaces de Mediafire y páginas que requieren User-Agent de PS3.
- 📡 **Cliente FTP integrado** – Conéctate a tu PS3 (webMAN / MultiMAN) para subir/descargar archivos, con arrastrar y soltar.
- 🧠 **Soporte multidioma** – Español e inglés, con selector en el inicio y persistencia en preferencias.
- 📁 **Carpeta `data` portátil** – Se crea siempre junto al ejecutable; todas las tiendas extraídas van ahí.
- 🔍 **Reconocimiento de ID de juego** – Detecta automáticamente el código del juego (BLES, NPUB, etc.) en la descripción.

---

## 🚀 ¿Para qué sirve?

- Explorar y descargar contenido de tiendas PS3 sin necesidad de una consola.
- Extraer PKGs oficiales o de terceros para analizar su estructura.
- Transferir archivos rápidamente entre PC y PS3 mediante FTP.
- Tener un historial local de tus tiendas favoritas y sus juegos.

---


> ⚠️ **Windows SmartScreen** puede mostrar una advertencia porque el programa no está firmado. Haz clic en "Más información" y luego "Ejecutar de todas formas".

---

## 🛠️ Uso básico

### Desde cero

1. Al iniciar, elige una opción:
   - **Extraer PKG** – Selecciona un archivo `.pkg` de tienda PS3. Se extraerá en `data/`.
   - **Abrir XML** – Carga directamente un archivo `main.xml` de una tienda ya extraída.
   - **Mis Tiendas** – Muestra todas las tiendas que ya tengas en `data/`.
   - **PSN Database** – Abre en el navegador la base de datos de juegos PSN by Luan Teles.

### Navegando por la tienda

- Haz **doble clic** en cualquier ítem para:
  - Si tiene `src` → navega a otra vista (subcategoría).
  - Si tiene `url` → abre el diálogo de descarga.
- El botón **Volver** regresa a la vista anterior.
- El combo **Tienda** cambia entre todas las tiendas detectadas en `data/`.

### Descargando juegos

- Al hacer doble clic en un juego con enlace, se abrirá una ventana con la URL resuelta.
- Puedes:
  - **Descargar** – Guarda el PKG en tu PC (con barra de progreso y velocidad).
  - **Copiar** – Copia el enlace directo al portapapeles.
  - **Abrir** – Abre la URL en tu navegador.
- Las descargas aparecen en la **cola lateral** y pueden pausarse/cancelarse.

### FTP con la PS3

1. Enciende tu PS3 y activa webMAN MOD o MultiMAN (servidor FTP).
2. En el programa, haz clic en **FTP**.
3. Introduce la IP de tu PS3 (ej. `192.168.1.100`), puerto `21`, usuario `anonymous`.
4. Conéctate. Podrás ver los archivos de la PS3 a la derecha y los de tu PC a la izquierda.
5. Arrastra archivos entre paneles o usa los botones **→ Enviar / ← Descargar / 🗑 Borrar / 📁 Nueva carpeta**.

---

## 🧪 Requisitos del sistema

- **Windows 7 / 8 / 10 / 11**
- **Conexión a Internet** (para descargas y resolución de enlaces)
- **PS3** (solo para FTP, opcional)



---

BY KIZEO

---

## ⚠️ Aviso legal

Este software es solo para fines educativos y de preservación. No fomenta la piratería. Los PKGs y XMLs que proceses deben ser de tu propiedad o de fuentes que respeten los derechos de autor.

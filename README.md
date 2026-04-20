# 🎮 KZ Store Reader

**Emulador de Tienda PS3 para PC y Android**  
Lee y navega por las tiendas digitales hechas por la comunidad de PlayStation 3 directamente desde tu ordenador o dispositivo Android. Extrae PKGs, descarga juegos y accede a tu PS3 vía FTP.

<p align="center">
    <img width="25%" height="25%" alt="logo2" src="https://github.com/user-attachments/assets/85c539e8-25bf-4598-bd39-e4ec048152f8" />
</p>

<h1 align="center"> KZ Store Reader </h1>
<p  align="center">
</p>

---

## ✨ Características

### Versión PC
- 📦 **Extracción de PKGs** – Desempaqueta cualquier PKG de tienda PS3 y extrae su estructura XML y recursos.
- 🏪 **Visor de tiendas** – Carga el XML principal de una tienda y muestra todos sus juegos/ítems con iconos, IDs e información.
- 🌐 **Bypass de enlaces protegidos** – Resuelve automáticamente enlaces de Mediafire y páginas que requieren User-Agent de PS3.
- 📡 **Cliente FTP integrado** – Conéctate a tu PS3 (webMAN / MultiMAN) para subir/descargar archivos, con arrastrar y soltar.
- 🧠 **Soporte multidioma** – Español e inglés, con selector en el inicio y persistencia en preferencias.
- 📁 **Carpeta `data` portátil** – Se crea siempre junto al ejecutable; todas las tiendas extraídas van ahí.
- 🔍 **Reconocimiento de ID de juego** – Detecta automáticamente el código del juego (BLES, NPUB, etc.) en la descripción.

### Versión Android
- 📱 **Totalmente funcional en móviles y tablets** – Interfaz adaptada a pantallas táctiles, con menús deslizables y selección múltiple.
- 📂 **Extrae PKGs directamente en el almacenamiento interno** – Los archivos extraídos se guardan en `Android/data/com.kizeo.kzstorereader/files/data/`.
- 🏪 **Navega por tus tiendas** – Lista todas las tiendas extraídas, entra en sus categorías y visualiza juegos con iconos, descripciones y enlaces de descarga.
- ⬇️ **Descarga de juegos integrada** – Resuelve enlaces protegidos (Mediafire, etc.) y descarga PKGs con barra de progreso, notificaciones y cola de descargas (pausa/reanuda/cancela).
- 📡 **Cliente FTP completo** – Conéctate a tu PS3 (webMAN / MultiMAN) para explorar archivos, subir, descargar, renombrar, crear carpetas y borrar elementos. Soporta selección múltiple y transferencias en segundo plano con notificaciones.
- 🌐 **Base de datos PSN** – Acceso directo a la PSN Database de Luan Teles desde el menú.
- 🔄 **Actualizaciones automáticas** – Comprueba si hay nuevas versiones en GitHub y te redirige para descargarlas.
- 🌍 **Multidioma** – Español e inglés, con cambio dinámico sin reiniciar la app.
- 🎨 **Diseño oscuro** – Interfaz limpia 

<p align="center">
<img width="720" height="480" alt="image" src="https://github.com/user-attachments/assets/8921d32b-2360-4eca-bcab-43cfe88fa561" />
</p>

---

## 🚀 ¿Para qué sirve?

- Explorar y descargar contenido de tiendas PS3 sin necesidad de una consola.
- Extraer PKGs oficiales o de terceros para analizar su estructura.
- Transferir archivos rápidamente entre PC y PS3 mediante FTP.
- Tener un historial local de tus tiendas favoritas y sus juegos.
- **En Android**: Lleva tu colección de tiendas PS3 a cualquier parte, descarga juegos directamente en tu móvil y adminístralos con FTP.

---

> ⚠️ **Windows SmartScreen** puede mostrar una advertencia porque el programa no está firmado. Haz clic en "Más información" y luego "Ejecutar de todas formas".

---

## 🛠️ Uso básico

### Versión PC

<p align="center">
<img width="639" height="282" alt="image" src="https://github.com/user-attachments/assets/2a9d0177-2c68-4c6f-b48e-3beff3d48c06" />
</p>

#### Desde cero
1. Al iniciar, elige una opción:
   - **Extraer PKG** – Selecciona un archivo `.pkg` de tienda PS3. Se extraerá en `data/`.
   - **Abrir XML** – Carga directamente un archivo `main.xml` de una tienda ya extraída.
   - **Mis Tiendas** – Muestra todas las tiendas que ya tengas en `data/`.
   - **PSN Database** – Abre en el navegador la base de datos de juegos PSN by Luan Teles.

#### Navegando por la tienda
- Haz **doble clic** en cualquier ítem para:
  - Si tiene `src` → navega a otra vista (subcategoría).
  - Si tiene `url` → abre el diálogo de descarga.
- El botón **Volver** regresa a la vista anterior.
- El combo **Tienda** cambia entre todas las tiendas detectadas en `data/`.

#### Descargando juegos
- Al hacer doble clic en un juego con enlace, se abrirá una ventana con la URL resuelta.
- Puedes:
  - **Descargar** – Guarda el PKG en tu PC (con barra de progreso y velocidad).
  - **Copiar** – Copia el enlace directo al portapapeles.
  - **Abrir** – Abre la URL en tu navegador.
- Las descargas aparecen en la **cola lateral** y pueden pausarse/cancelarse.

#### FTP con la PS3
1. Enciende tu PS3 y activa webMAN MOD o MultiMAN (servidor FTP).
2. En el programa, haz clic en **FTP**.
3. Introduce la IP de tu PS3 (ej. `192.168.1.100`), puerto `21`, usuario `anonymous`.
4. Conéctate. Podrás ver los archivos de la PS3 a la derecha y los de tu PC a la izquierda.
5. Arrastra archivos entre paneles o usa los botones **→ Enviar / ← Descargar / 🗑 Borrar / 📁 Nueva carpeta**.

### Versión Android

<p align="center">
<img width="171" height="320" alt="image" src="https://github.com/user-attachments/assets/3f8eeaec-b4a6-45e9-b7ee-8ba5b5d8eb69" />


#### Instalación
- Descarga el archivo APK desde la sección de [Releases](https://github.com/kizeo0/KZ-Store-Reader/releases) e instálalo.
- Permite los permisos de almacenamiento (para leer PKGs y guardar tiendas) y notificaciones (para descargas en segundo plano).

#### Uso
1. **Pantalla principal** – Cuatro tarjetas:
   - **Mis Tiendas** – Lista todas las tiendas extraídas. Pulsa para entrar, mantén pulsado para renombrar o borrar.
   - **Extraer PKG** – Selecciona un archivo `.pkg` de tu dispositivo. Se extraerá en la carpeta `data` de la app.
   - **Abrir XML** – Carga un archivo XML de una tienda directamente (sin extraer PKG).
   - **FTP Manager** – Conéctate a tu PS3 y transfiere archivos (ver sección FTP abajo).
   - **PSN Database** – Abre la base de datos online de juegos PSN.

2. **Visor de tiendas**:
   - Pulsa en cualquier ítem para navegar a subcategorías o iniciar descarga.
   - Mantén pulsado un ítem para copiar su URL al portapapeles.
   - Las descargas aparecen en la cola inferior, con barra de progreso y botones de pausa/cancelar.
   - Las notificaciones muestran el progreso incluso con la app cerrada.

3. **FTP en Android**:
   - Conéctate a tu PS3 igual que en PC.
   - Los paneles muestran: arriba la PS3 (remoto), abajo el almacenamiento local del dispositivo.
   - **Selección múltiple**: Mantén pulsado un archivo para activar los checkboxes. Marca varios y usa los botones **↑ Enviar** o **↓ Recibir** para transferirlos todos.
   - **Acciones contextuales**: En un solo archivo, mantén pulsado para abrir un menú con opciones: Abrir (si es carpeta), Nueva carpeta aquí, Renombrar, Borrar.
   - **Cancelar transferencia**: Durante la subida/descarga, aparece un diálogo con barra de progreso y botón **Cancelar**. También puedes cancelar desde la notificación.
   - **Navegación**: Pulsa en una carpeta para entrar, usa el elemento `..` para subir al directorio padre.

<p align="center">
<img width="171" height="320" alt="image" src="https://github.com/user-attachments/assets/2771ea29-6687-4f34-a9f9-4c1641433721" />


---

## 🧪 Requisitos del sistema

- **Windows 7 / 8 / 10 / 11**
- **Android 6.0 (API 23) o superior**
- **Conexión a Internet** (para descargas y resolución de enlaces)
- **PS3** (solo para FTP, opcional)

---

BY KIZEO

---

## ⚠️ Aviso legal

Este software es solo para fines educativos y de preservación. No fomenta la piratería. Los PKGs y XMLs que proceses deben ser de tu propiedad o de fuentes que respeten los derechos de autor.

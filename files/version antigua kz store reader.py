import os
import threading
import time
import xml.etree.ElementTree as ET
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
import requests
from PIL import Image, ImageTk
import webbrowser
import re
import logging
from io import BytesIO

# === CONFIGURACIÓN DE LOGS ===
logging.getLogger('PIL').setLevel(logging.WARNING)
logging.getLogger('urllib3').setLevel(logging.WARNING)

logging.basicConfig(
    filename='debug_log.txt',
    level=logging.DEBUG,
    format='%(asctime)s - %(levelname)s - %(message)s',
    filemode='w'
)

class DownloadTask:
    """Gestiona descargas individuales con pausa, reanudar y copiar link"""
    def __init__(self, url, dest, title, parent_container, root_app):
        self.url = url
        self.dest = dest
        self.title = title
        self.root_app = root_app 
        
        self.is_cancelled = False
        self.is_paused = False
        self.downloaded = 0
        self.total_size = 0
        
        self.frame = tk.Frame(parent_container, bg="#2d2d2d", bd=1, relief="solid")
        self.frame.pack(fill="x", pady=4, padx=5)
        
        display_title = (title[:40] + '..') if len(title) > 40 else title
        self.lbl_title = tk.Label(self.frame, text=display_title, bg="#2d2d2d", fg="white", font=("Segoe UI", 9, "bold"), anchor="w")
        self.lbl_title.pack(side="top", fill="x", padx=5, pady=2)
        
        self.progress = ttk.Progressbar(self.frame, orient="horizontal", length=100, mode="determinate")
        self.progress.pack(side="top", fill="x", padx=5)
        
        self.controls_frame = tk.Frame(self.frame, bg="#2d2d2d")
        self.controls_frame.pack(side="bottom", fill="x", padx=5, pady=2)
        
        self.lbl_status = tk.Label(self.controls_frame, text="Iniciando...", bg="#2d2d2d", fg="#aaaaaa", font=("Segoe UI", 8))
        self.lbl_status.pack(side="left")

        self.btn_cancel = tk.Button(self.controls_frame, text="✖", command=self.cancel, bg="#c62828", fg="white", borderwidth=0, width=3)
        self.btn_cancel.pack(side="right", padx=2)

        self.btn_copy = tk.Button(self.controls_frame, text="🔗", command=self.copy_link, bg="#1565c0", fg="white", borderwidth=0, width=3)
        self.btn_copy.pack(side="right", padx=2)

        self.btn_pause = tk.Button(self.controls_frame, text="⏸", command=self.toggle_pause, bg="#f9a825", fg="black", borderwidth=0, width=3)
        self.btn_pause.pack(side="right", padx=2)

    def copy_link(self):
        self.root_app.clipboard_clear()
        self.root_app.clipboard_append(self.url)
        messagebox.showinfo("Copiado", "Enlace copiado al portapapeles.")

    def toggle_pause(self):
        if self.is_paused:
            self.is_paused = False
            self.btn_pause.config(text="⏸", bg="#f9a825")
            self.lbl_status.config(text="Reanudando...")
        else:
            self.is_paused = True
            self.btn_pause.config(text="▶", bg="#2e7d32", fg="white")
            self.lbl_status.config(text="Pausado")

    def cancel(self):
        self.is_cancelled = True
        self.frame.destroy()

    def run(self):
        try:
            response = requests.get(self.url, stream=True, timeout=10)
            self.total_size = int(response.headers.get('content-length', 0))
            
            with open(self.dest, 'wb') as f:
                for chunk in response.iter_content(chunk_size=1024*64):
                    if self.is_cancelled:
                        f.close()
                        if os.path.exists(self.dest): os.remove(self.dest)
                        return
                    
                    while self.is_paused:
                        if self.is_cancelled: return
                        time.sleep(0.5)

                    if chunk:
                        f.write(chunk)
                        self.downloaded += len(chunk)
                        
                        if self.total_size > 0:
                            percent = (self.downloaded / self.total_size) * 100
                            self.progress['value'] = percent
                            mb_dl = self.downloaded / (1024 * 1024)
                            mb_total = self.total_size / (1024 * 1024)
                            self.lbl_status.config(text=f"{percent:.1f}% ({mb_dl:.1f}/{mb_total:.1f} MB)")
                        else:
                            mb_dl = self.downloaded / (1024 * 1024)
                            self.lbl_status.config(text=f"{mb_dl:.1f} MB")

            if not self.is_cancelled:
                self.lbl_status.config(text="Completado", fg="#4caf50")
                self.btn_pause.config(state="disabled")
                messagebox.showinfo("Descarga", f"Finalizado:\n{self.title}")
                
        except Exception as e:
            if not self.is_cancelled:
                self.lbl_status.config(text="Error", fg="red")
                logging.error(f"Error descarga {self.url}: {e}")

class PS3StoreApp:
    def __init__(self, root):
        self.root = root
        self.root.title("PS3 Store Downloader - Universal Edition")
        self.root.geometry("1300x850")
        self.root.configure(bg="#121212")

        self.base_dir = os.path.dirname(os.path.abspath(__file__))
        self.usrdir_path = os.path.join(self.base_dir, "USRDIR")
        
        # Búsqueda de carpeta USRDIR (arriba o abajo)
        if not os.path.exists(self.usrdir_path):
            self.usrdir_path = self.base_dir 

        self.history = []
        self.current_xml = None
        self.image_cache = {}

        self.setup_styles()
        self.create_widgets()
        
        main_xml = os.path.join(self.base_dir, "V)
        if os.path.exists(main_xml):
            self.load_xml(main_xml, "
        else:
            self.open_file()

    def setup_styles(self):
        style = ttk.Style()
        style.theme_use('clam')
        style.configure("Treeview", 
                        background="#1e1e1e", 
                        foreground="white", 
                        fieldbackground="#1e1e1e", 
                        font=('Segoe UI', 11),
                        rowheight=60)
        style.configure("Treeview.Heading", 
                        background="#333", 
                        foreground="white", 
                        font=('Segoe UI', 12, 'bold'))
        style.map("Treeview", background=[('selected', '#1976d2')])

    def create_widgets(self):
        top_bar = tk.Frame(self.root, bg="#1f1f1f", height=50)
        top_bar.pack(side=tk.TOP, fill=tk.X)
        
        btn_open = tk.Button(top_bar, text="📂 Abrir XML", command=self.open_file, bg="#333", fg="white")
        btn_open.pack(side=tk.LEFT, padx=10, pady=10)
        
        self.btn_back = tk.Button(top_bar, text="⬅ Atrás", command=self.go_back, state=tk.DISABLED, bg="#333", fg="white")
        self.btn_back.pack(side=tk.LEFT, padx=5, pady=10)

        self.lbl_path = tk.Label(top_bar, text="Inicio", bg="#1f1f1f", fg="#aaaaaa", font=("Consolas", 10))
        self.lbl_path.pack(side=tk.LEFT, padx=15, pady=12)

        main_split = tk.PanedWindow(self.root, orient=tk.HORIZONTAL, bg="#121212", sashwidth=4)
        main_split.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)

        list_frame = tk.Frame(main_split, bg="#121212")
        main_split.add(list_frame, minsize=500)

        columns = ('title', 'info')
        self.tree = ttk.Treeview(list_frame, columns=columns, show='tree headings', selectmode='browse')
        
        self.tree.heading('#0', text='Icono', anchor='w')
        self.tree.column('#0', width=80, anchor='center')
        
        self.tree.heading('title', text='Título', anchor='w')
        self.tree.column('title', width=450)
        
        self.tree.heading('info', text='ID / Info', anchor='w')
        self.tree.column('info', width=150)

        scrollbar = ttk.Scrollbar(list_frame, orient=tk.VERTICAL, command=self.tree.yview)
        self.tree.configure(yscroll=scrollbar.set)
        
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.tree.bind("<Double-1>", self.on_item_double_click)

        right_panel = tk.Frame(main_split, bg="#1a1a1a", width=350)
        main_split.add(right_panel, minsize=300)

        tk.Label(right_panel, text="Gestor de Descargas", font=("Segoe UI", 14, "bold"), bg="#1a1a1a", fg="white").pack(pady=10)
        
        canvas = tk.Canvas(right_panel, bg="#1a1a1a", highlightthickness=0)
        dl_scrollbar = ttk.Scrollbar(right_panel, orient="vertical", command=canvas.yview)
        self.dl_container = tk.Frame(canvas, bg="#1a1a1a")

        self.dl_container.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=self.dl_container, anchor="nw", width=330)
        canvas.configure(yscrollcommand=dl_scrollbar.set)

        canvas.pack(side="left", fill="both", expand=True, padx=5)
        dl_scrollbar.pack(side="right", fill="y")

    def open_file(self):
        file_path = filedialog.askopenfilename(filetypes=[("XML Files", "*.xml")])
        if file_path:
            self.load_xml(file_path)

    def sanitizar_xml(self, path):
        """Repara XMLs rotos de ZukoStore y otros formatos sucios"""
        try:
            with open(path, 'r', encoding='utf-8', errors='ignore') as f:
                content = f.read()
            
            # 1. Eliminar etiquetas basura de ZukoStore como <>, </>, <0>, etc.
            content = re.sub(r'<[/]?[0-9]?>', '', content) # Elimina <0>, </0>
            content = content.replace("<>", "").replace("</>", "") # Elimina <> vacíos
            
            # 2. Escapar ampersands sueltos (Causa principal de error "invalid token")
            # Busca & que NO sea parte de &amp;, &lt;, etc.
            content = re.sub(r'&(?!(amp|lt|gt|quot|apos);)', '&amp;', content)
            
            # 3. Arreglar rutas cortadas si las hay
            return ET.fromstring(content)
        except Exception as e:
            logging.error(f"Error fatal sanitizando {path}: {e}")
            return None

    def load_xml(self, path, view_id=None):
        logging.info(f"Cargando: {path} | View: {view_id}")
        
        try:
            tree = ET.parse(path)
            root = tree.getroot()
        except ET.ParseError:
            logging.warning("Parseo estándar falló. Usando sanitizador agresivo.")
            root = self.sanitizar_xml(path)
            if root is None:
                messagebox.showerror("Error", f"No se pudo leer el archivo XML:\n{os.path.basename(path)}")
                return

        self.current_xml = path
        self.lbl_path.config(text=f".../{os.path.basename(path)}")
        
        for item in self.tree.get_children():
            self.tree.delete(item)

        items_found = self.parse_xml_items(root, view_id)
        
        for item in items_found:
            icon_img = self.get_icon(item['icon'])
            # Aseguramos que 'info' sea string para evitar crash
            info_text = str(item['info']) if item['info'] else ""
            
            self.tree.insert('', 'end', 
                             text='', 
                             image=icon_img,
                             values=(item['title'], info_text),
                             tags=(item['src'], item['url']))

    def parse_xml_items(self, root, target_view_id=None):
        items = []
        
        # 1. Encontrar la View correcta
        target_root = root
        if target_view_id:
            # Buscar View por ID
            found = root.find(f".//View[@id='{target_view_id}']")
            # Soporte para Zuko (usa <V>)
            if found is None: found = root.find(f".//V[@id='{target_view_id}']")
            
            if found is not None: target_root = found
        
        # 2. Extraer Tablas (Datos de items)
        # Soportamos <Table> (Standard) y <T> (Zuko/Shorthand)
        # Buscamos en todo el árbol de la View para ser flexibles
        tables = {}
        
        # Combinamos búsqueda de Table y T
        all_tables = target_root.findall('.//Table') + target_root.findall('.//T')
        
        for table in all_tables:
            key = table.get('key')
            data = {'title': 'Sin Título', 'icon': None, 'info': '', 'url': None, 'src': None}
            
            # Buscamos Pairs y P
            all_pairs = table.findall('Pair') + table.findall('P')
            
            for pair in all_pairs:
                p_key = pair.get('key')
                
                # Obtener valor: puede estar en <String>, texto directo o texto tras limpiar tags
                p_val = ""
                string_tag = pair.find('String')
                if string_tag is not None:
                    p_val = string_tag.text
                elif pair.text and pair.text.strip():
                    p_val = pair.text
                else:
                    # Intento de leer contenido mixto (Zuko style)
                    p_val = "".join(pair.itertext())
                
                if not p_val: p_val = ""
                
                # Mapeo de campos
                if p_key == 'title': data['title'] = p_val
                elif p_key == 'icon': data['icon'] = p_val
                elif p_key == 'info': data['info'] = p_val
                elif p_key in ['pkg_src', 'url', 'module_action', 'pkg_src_qa']: data['url'] = p_val

            tables[key] = data

        # 3. Extraer Items visuales (Query / Q)
        # Soportamos <Query> y <Q>
        all_queries = target_root.findall('.//Items/Query') + target_root.findall('.//Items/Q') + \
                      target_root.findall('.//I/Q') # Zuko structure <I><Q>
        
        # Si no hay Queries pero hay tablas, mostramos las tablas (formato plano)
        if not all_queries and tables:
            for key in tables: items.append(tables[key])
        else:
            for q in all_queries:
                attr_key = q.get('attr')
                src = q.get('src')
                
                if attr_key in tables:
                    item_data = tables[attr_key].copy()
                    item_data['src'] = src
                    items.append(item_data)
        
        return items

    def resolve_local_path(self, path_str):
        if not path_str: return None
        
        # Limpieza de rutas XMB
        clean_name = os.path.basename(path_str.replace("\\", "/"))
        
        # 1. Búsqueda relativa a USRDIR (Estructura oficial)
        # Simula ruta PS3 en PC
        if "USRDIR" in path_str:
            parts = path_str.split("USRDIR")
            rel_path = parts[1].strip("/").strip("\\")
            full_path = os.path.join(self.usrdir_path, rel_path)
            if os.path.exists(full_path): return full_path

        # 2. Búsqueda "Sabueso" (Por nombre de archivo en todo el proyecto)
        # Esto arregla cuando las carpetas no coinciden
        for root, dirs, files in os.walk(self.base_dir):
            if clean_name in files:
                return os.path.join(root, clean_name)
                
        return None

    def get_icon(self, icon_path):
        if not icon_path: return None
        
        local_path = self.resolve_local_path(icon_path)
        
        if local_path in self.image_cache:
            return self.image_cache[local_path]

        try:
            if local_path and os.path.exists(local_path):
                img = Image.open(local_path)
                img = img.resize((54, 54), Image.Resampling.LANCZOS)
                photo = ImageTk.PhotoImage(img)
                self.image_cache[local_path] = photo
                return photo
        except Exception:
            pass
        return None

    def on_item_double_click(self, event):
        sel = self.tree.selection()
        if not sel: return
        
        item = self.tree.item(sel[0])
        tags = item['tags'] # (src, url)
        title = item['values'][0]
        
        src = tags[0]
        url = tags[1]

        # Navegación
        if src:
            self.history.append((self.current_xml, None))
            self.btn_back.config(state=tk.NORMAL)
            
            if "#" in src:
                xml_part, view_part = src.split("#")
                target_xml = self.resolve_local_path(xml_part) if xml_part else self.current_xml
                if target_xml: self.load_xml(target_xml, view_part)
            else:
                target_xml = self.resolve_local_path(src)
                if target_xml: self.load_xml(target_xml)
            return

        # Descarga
        if url and url != "None":
            if any(x in url for x in ["http", "https"]):
                if "mediafire" in url or "mega.nz" in url:
                     if messagebox.askyesno("Externo", "Enlace de navegador detectado. ¿Abrir?"):
                         webbrowser.open(url)
                else:
                    self.ask_download(url, title)

    def ask_download(self, url, title):
        clean_name = re.sub(r'[\\/*?:"<>|]', "", title).strip()
        path = filedialog.asksaveasfilename(initialfile=f"{clean_name}.pkg", filetypes=[("PKG", "*.pkg")])
        if path:
            task = DownloadTask(url, path, title, self.dl_container, self.root)
            threading.Thread(target=task.run, daemon=True).start()

    def go_back(self):
        if not self.history: return
        prev_xml, prev_view = self.history.pop()
        self.load_xml(prev_xml, prev_view)
        if not self.history: self.btn_back.config(state=tk.DISABLED)

if __name__ == "__main__":
    root = tk.Tk()
    app = PS3StoreApp(root)
    root.mainloop()

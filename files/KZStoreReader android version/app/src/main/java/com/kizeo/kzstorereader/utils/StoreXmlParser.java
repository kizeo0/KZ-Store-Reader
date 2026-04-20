package com.kizeo.kzstorereader.utils;

import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class StoreXmlParser {

    private static final String TAG = "StoreXmlParser";
    private static final Map<String, String> resolvedPathCache = new ConcurrentHashMap<>();

    public enum XmlFormat { XMB, ZUKO }

    public static class ParseResult {
        public final Document  document;
        public final XmlFormat format;
        public final String    error;
        ParseResult(Document d, XmlFormat f, String e) { document=d; format=f; error=e; }
    }

    public static class StoreItem {
        public String title    = "";
        public String infoTxt  = "";
        public String iconPath = "";
        public String src      = "";
        public String dl       = "";
        public String gameId   = "";
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  1. PARSE — preprocess_xml + DOM
    // ══════════════════════════════════════════════════════════════════════════

    public static ParseResult parse(String rawXml) {
        if (rawXml == null || rawXml.isEmpty())
            return new ParseResult(null, XmlFormat.XMB, "Empty XML content");
        try {
            String[] pp  = preprocessXml(rawXml);
            String   norm = pp[0];
            XmlFormat fmt = "zuko".equals(pp[1]) ? XmlFormat.ZUKO : XmlFormat.XMB;

            Document doc = buildDocument(norm);
            if (doc == null) {
                norm = aggressiveClean(norm);
                doc  = buildDocument(norm);
            }
            if (doc == null)
                return new ParseResult(null, fmt, "Could not parse XML after cleanup");

            doc.getDocumentElement().normalize();
            return new ParseResult(doc, fmt, null);
        } catch (Exception e) {
            Log.e(TAG, "parse()", e);
            return new ParseResult(null, XmlFormat.XMB, "Parse error: " + e.getMessage());
        }
    }

    private static Document buildDocument(String norm) {
        try {
            DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance();
            fac.setValidating(false);
            fac.setNamespaceAware(false);
            trySetFeature(fac, "http://xml.org/sax/features/external-general-entities", false);
            trySetFeature(fac, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = fac.newDocumentBuilder();
            builder.setEntityResolver((pub, sys) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(null);
            byte[] bytes = norm.getBytes(StandardCharsets.UTF_8);
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            Log.w(TAG, "buildDocument failed: " + e.getMessage());
            return null;
        }
    }

    private static void trySetFeature(DocumentBuilderFactory fac, String feat, boolean val) {
        try { fac.setFeature(feat, val); } catch (Exception ignored) {}
    }

    private static String[] preprocessXml(String raw) {
        raw = raw.replace("\uFEFF", "");
        raw = raw.replace("\r\n", "\n").replace("\r", "\n");
        raw = raw.replaceAll("(?i)<\\?xml[^?]*\\?>", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        raw = raw.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        raw = raw.replaceAll("<!-{3,}", "<!--");
        raw = raw.replaceAll("-{3,}>", "-->");
        Pattern cp = Pattern.compile("<!--(.*?)-->", Pattern.DOTALL);
        Matcher cm = cp.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (cm.find()) {
            String inner = cm.group(1).replace("--", "  ");
            cm.appendReplacement(sb, "<!--" + Matcher.quoteReplacement(inner) + "-->");
        }
        cm.appendTail(sb);
        raw = sb.toString();
        raw = replaceCdata(raw);
        raw = raw.replaceAll("&(?!(amp|lt|gt|apos|quot|#\\d+|#x[0-9a-fA-F]+);)", "&amp;");
        boolean isZuko = raw.contains("<>") || Pattern.compile("<X\\s+version").matcher(raw).find();
        if (!isZuko) return new String[]{raw, "xmb"};
        raw = raw.replace("<>", "<String>").replace("</>", "</String>");
        raw = raw.replaceAll("<I\\s+(class=)", "<Item $1");
        raw = raw.replaceAll("<I\\s*>", "<Items>").replaceAll("</I\\s*>", "</Items>");
        raw = raw.replaceAll("<Q\\s", "<Item ").replace("</Q>", "</Item>");
        raw = raw.replaceAll("<T\\s", "<Table ").replace("</T>", "</Table>");
        raw = raw.replaceAll("<P\\s", "<Pair ").replace("</P>", "</Pair>");
        raw = raw.replace("<A>","<Attributes>").replace("</A>","</Attributes>");
        raw = raw.replaceAll("<V\\s", "<View ").replace("</V>", "</View>");
        raw = raw.replaceAll("<X\\s[^>]*>", "<XMBML>").replace("</X>","</XMBML>");
        return new String[]{raw, "zuko"};
    }

    private static String replaceCdata(String raw) {
        Pattern p = Pattern.compile("<!\\[CDATA\\[(.*?)\\]\\]>", Pattern.DOTALL);
        Matcher m = p.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String content = m.group(1)
                    .replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;");
            m.appendReplacement(sb, Matcher.quoteReplacement(content));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String aggressiveClean(String raw) {
        raw = raw.replaceAll("<\\?(?!xml)[^?]*\\?>", "");
        raw = raw.replaceAll("(?i)<!DOCTYPE[^>]*>", "");
        raw = raw.replaceAll("value=\"([^\"]*)&(?!(amp|lt|gt|apos|quot|#))([^\"]*)\"",
                "value=\"$1&amp;$3\"");
        raw = raw.replaceAll("xmlns:[a-zA-Z0-9_]+\\s*=\\s*\"[^\"]*\"", "");
        raw = raw.replaceAll("xmlns\\s*=\\s*\"[^\"]*\"", "");
        return raw;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  2. EXTRACT ITEMS
    // ══════════════════════════════════════════════════════════════════════════

    public static List<StoreItem> extractItems(Document doc, XmlFormat fmt) {
        if (doc == null) return new ArrayList<>();
        Element view = firstTagElement(doc.getElementsByTagName("View"));
        if (view == null) { Log.w(TAG, "No <View> found"); return new ArrayList<>(); }
        return loadFromView(view);
    }

    public static List<StoreItem> extractItemsForView(Document doc, XmlFormat fmt, String viewId) {
        if (doc == null || viewId == null || viewId.isEmpty()) return new ArrayList<>();
        NodeList views = doc.getElementsByTagName("View");
        for (int i = 0; i < views.getLength(); i++) {
            Element v = (Element) views.item(i);
            if (viewId.equals(v.getAttribute("id"))) return loadFromView(v);
        }
        Log.w(TAG, "View not found: " + viewId);
        return new ArrayList<>();
    }

    private static List<StoreItem> loadFromView(Element view) {
        List<StoreItem> result = new ArrayList<>();
        Map<String, Map<String, String>> attr = new HashMap<>();
        NodeList attrEls = view.getElementsByTagName("Attributes");
        for (int i = 0; i < attrEls.getLength(); i++) {
            collectTables((Element) attrEls.item(i), attr);
        }
        NodeList kids = view.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "Table".equals(n.getNodeName()))
                parseTable((Element) n, attr);
        }
        Element itemsEl = firstKidElement(view, "Items");
        if (itemsEl == null) {
            NodeList nl = view.getElementsByTagName("Items");
            if (nl.getLength() > 0) itemsEl = (Element) nl.item(0);
        }
        if (itemsEl == null) { Log.w(TAG, "No <Items> in view id=" + view.getAttribute("id")); return result; }
        NodeList itemNodes = itemsEl.getChildNodes();
        for (int i = 0; i < itemNodes.getLength(); i++) {
            Node n = itemNodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element itemEl = (Element) n;
            String ref = itemEl.getAttribute("attr");
            if (ref.isEmpty()) ref = itemEl.getAttribute("key");
            Map<String, String> info = attr.containsKey(ref) ? attr.get(ref) : new HashMap<>();
            String title = info.getOrDefault("title", "");
            if (title.isEmpty()) title = ref;
            if (title.isEmpty()) title = itemEl.getAttribute("label");
            if (title.isEmpty()) continue;
            String infoTxt  = info.getOrDefault("info", "");
            String iconPath = info.getOrDefault("icon", "");
            if (iconPath.isEmpty()) iconPath = info.getOrDefault("prod_pict_path", "");
            if (iconPath.isEmpty()) iconPath = info.getOrDefault("thumbnail", "");
            String src = itemEl.getAttribute("src");
            String dl = info.getOrDefault("pkg_src", "");
            if (dl.isEmpty()) dl = info.getOrDefault("url", "");
            if (dl.isEmpty()) dl = info.getOrDefault("module_action", "");
            String gameId = extractGameId(infoTxt + " " + title + " " + ref + " " + dl);
            StoreItem item = new StoreItem();
            item.title = title;
            item.infoTxt = infoTxt;
            item.iconPath = iconPath;
            item.src = src;
            item.dl = dl;
            item.gameId = gameId;
            result.add(item);
        }
        Log.d(TAG, "loadFromView[" + view.getAttribute("id") + "]: " + result.size() + " items");
        return result;
    }

    private static void collectTables(Element parent, Map<String, Map<String, String>> attr) {
        NodeList tables = parent.getElementsByTagName("Table");
        for (int i = 0; i < tables.getLength(); i++)
            parseTable((Element) tables.item(i), attr);
    }

    private static void parseTable(Element tbl, Map<String, Map<String, String>> attr) {
        String tblKey = tbl.getAttribute("key");
        if (tblKey.isEmpty()) return;
        Map<String, String> d = new HashMap<>();
        NodeList pairs = tbl.getElementsByTagName("Pair");
        for (int i = 0; i < pairs.getLength(); i++) {
            Element pair = (Element) pairs.item(i);
            String pk = pair.getAttribute("key");
            if (pk.isEmpty()) continue;
            String val = pair.getAttribute("value");
            if (val.isEmpty()) val = firstStringText(pair);
            d.put(pk, val);
        }
        attr.put(tblKey, d);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  3. RESOLVE (con caché)
    // ══════════════════════════════════════════════════════════════════════════

    public static String resolveLocalPath(String ps3Path, String usrdirPath,
                                          String storeRoot, String dataDir) {
        if (ps3Path == null || ps3Path.isEmpty()) return null;
        if (ps3Path.startsWith("http")) return null;
        String cacheKey = ps3Path + "|" + usrdirPath + "|" + storeRoot + "|" + dataDir;
        if (resolvedPathCache.containsKey(cacheKey)) {
            return resolvedPathCache.get(cacheKey);
        }
        String result = resolveLocalPathInternal(ps3Path, usrdirPath, storeRoot, dataDir);
        resolvedPathCache.put(cacheKey, result);
        return result;
    }

    private static String resolveLocalPathInternal(String ps3Path, String usrdirPath,
                                                   String storeRoot, String dataDir) {
        String clean = ps3Path;
        clean = clean.replace("xmb://localhost/", "");
        clean = clean.replace("/dev_hdd0/game/", "");
        clean = clean.replace("/dev_hdd0/", "");
        clean = clean.replace("/dev_usb000/", "");
        clean = clean.replace("/dev_usb001/", "");
        clean = clean.replace("/dev_usb/", "");
        clean = clean.replace("/dev_flash/", "");
        clean = clean.replace("\\", "/");
        while (clean.startsWith("/")) clean = clean.substring(1);
        String[] parts = clean.split("/");
        if (parts.length == 0 || (parts.length == 1 && parts[0].isEmpty())) return null;
        // Caso USRDIR
        for (int i = 0; i < parts.length; i++) {
            if ("USRDIR".equalsIgnoreCase(parts[i]) && i < parts.length - 1) {
                StringBuilder rel = new StringBuilder();
                for (int j = i + 1; j < parts.length; j++) {
                    if (j > i + 1) rel.append("/");
                    rel.append(parts[j]);
                }
                if (usrdirPath != null && !usrdirPath.isEmpty()) {
                    java.io.File t = new java.io.File(usrdirPath, rel.toString());
                    if (t.exists()) return t.getAbsolutePath();
                }
                if (storeRoot != null) {
                    java.io.File t = new java.io.File(storeRoot + "/USRDIR", rel.toString());
                    if (t.exists()) return t.getAbsolutePath();
                }
            }
        }
        // Caso alias de tienda
        if (parts.length > 0 && !parts[0].startsWith(".") && dataDir != null) {
            String alias = parts[0].toUpperCase();
            java.io.File dataDirFile = new java.io.File(dataDir);
            java.io.File[] storeDirs = dataDirFile.listFiles(java.io.File::isDirectory);
            if (storeDirs != null) {
                for (java.io.File sd : storeDirs) {
                    if (!sd.getName().toUpperCase().contains(alias)) continue;
                    String[] relParts = new String[parts.length - 1];
                    System.arraycopy(parts, 1, relParts, 0, relParts.length);
                    if (relParts.length > 0 && "USRDIR".equalsIgnoreCase(relParts[0])) {
                        String[] tmp = new String[relParts.length - 1];
                        System.arraycopy(relParts, 1, tmp, 0, tmp.length);
                        relParts = tmp;
                    }
                    java.io.File usrdirCand = new java.io.File(sd, "USRDIR");
                    if (!usrdirCand.isDirectory()) usrdirCand = sd;
                    if (relParts.length > 0) {
                        java.io.File t = new java.io.File(usrdirCand, String.join("/", relParts));
                        if (t.exists()) return t.getAbsolutePath();
                    }
                    String fileName = parts[parts.length - 1];
                    String found = findFileRecursive(sd, fileName);
                    if (found != null) return found;
                }
            }
        }
        // Búsqueda por nombre de archivo
        String fileName = parts[parts.length - 1];
        if (!fileName.isEmpty()) {
            if (storeRoot != null) {
                String found = findFileRecursive(new java.io.File(storeRoot), fileName);
                if (found != null) return found;
            }
            if (usrdirPath != null && !usrdirPath.equals(storeRoot)) {
                String found = findFileRecursive(new java.io.File(usrdirPath), fileName);
                if (found != null) return found;
            }
            if (dataDir != null) {
                String found = findFileRecursive(new java.io.File(dataDir), fileName);
                if (found != null) return found;
            }
        }
        // Ruta directa
        java.io.File direct = new java.io.File(ps3Path);
        if (direct.exists()) return direct.getAbsolutePath();
        if (usrdirPath != null) {
            direct = new java.io.File(usrdirPath, ps3Path);
            if (direct.exists()) return direct.getAbsolutePath();
        }
        if (storeRoot != null) {
            direct = new java.io.File(storeRoot, ps3Path);
            if (direct.exists()) return direct.getAbsolutePath();
        }
        Log.d(TAG, "resolveLocalPath: no encontrado " + ps3Path);
        return null;
    }

    private static String findFileRecursive(java.io.File dir, String filename) {
        if (dir == null || !dir.isDirectory()) return null;
        java.io.File[] files = dir.listFiles();
        if (files == null) return null;
        for (java.io.File f : files) {
            if (f.isFile() && f.getName().equalsIgnoreCase(filename)) {
                return f.getAbsolutePath();
            }
            if (f.isDirectory()) {
                String r = findFileRecursive(f, filename);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  4. HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private static Element firstTagElement(NodeList nl) {
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) return (Element) n;
        }
        return null;
    }

    private static Element firstKidElement(Element parent, String tag) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName()))
                return (Element) n;
        }
        return null;
    }

    private static String firstStringText(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "String".equals(n.getNodeName())) {
                String t = n.getTextContent();
                return t != null ? t.trim() : "";
            }
        }
        String v = parent.getAttribute("value");
        if (!v.isEmpty()) return v;
        String t = parent.getTextContent();
        return t != null ? t.trim() : "";
    }

    private static final Pattern GAME_ID_RE = Pattern.compile(
            "\\b(BLES|BLUS|BCES|BCUS|NPEB|NPUB|NPJA|NPJB|BLJM|BCJS|BCJB|BCAS|NPAS"
                    + "|ULUS|ULES|ULKS|ULJM|SLES|SLUS|SLPS|SCUS|SCES|NPEZ|NPUZ|NPHZ"
                    + "|NPED|NPUD|NPJD|NPJC|NPEC|NPUC)\\d{4,6}\\b",
            Pattern.CASE_INSENSITIVE);

    public static String extractGameId(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = GAME_ID_RE.matcher(text);
        return m.find() ? m.group().toUpperCase() : "";
    }
}
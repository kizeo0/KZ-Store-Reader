package com.kizeo.kzstorereader.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class StoreScanner {

    public static class StoreEntry {
        public String name;
        public String xmlPath;
        public String folderPath;

        public StoreEntry(String name, String xmlPath, String folderPath) {
            this.name = name;
            this.xmlPath = xmlPath;
            this.folderPath = folderPath;
        }
    }

    public static List<StoreEntry> scanStores(File dataDir) {
        List<StoreEntry> stores = new ArrayList<>();
        if (dataDir == null || !dataDir.isDirectory()) return stores;

        File[] dirs = dataDir.listFiles(File::isDirectory);
        if (dirs == null) return stores;
        Arrays.sort(dirs, Comparator.comparing(File::getName));

        for (File dir : dirs) {
            File xml = findMainXml(dir);
            if (xml != null) {
                stores.add(new StoreEntry(dir.getName(), xml.getAbsolutePath(), dir.getAbsolutePath()));
            }
        }
        return stores;
    }

    public static File findMainXml(File folder) {
        File usrdir = findUsrDir(folder);
        File search = usrdir != null ? usrdir : folder;

        File[] xmlFiles = search.listFiles(f ->
                f.isFile() && f.getName().toLowerCase().endsWith(".xml"));

        if (xmlFiles == null || xmlFiles.length == 0) return null;
        Arrays.sort(xmlFiles, (a, b) -> Long.compare(b.length(), a.length()));
        return xmlFiles[0];
    }

    private static File findUsrDir(File root) {
        File usrdir = new File(root, "USRDIR");
        if (usrdir.isDirectory()) return usrdir;
        File[] children = root.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                File nested = new File(child, "USRDIR");
                if (nested.isDirectory()) return nested;
            }
        }
        return null;
    }
}
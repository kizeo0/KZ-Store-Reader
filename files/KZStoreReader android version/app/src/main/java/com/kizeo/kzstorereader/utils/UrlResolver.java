package com.kizeo.kzstorereader.utils;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of Python resolve_download_url + _resolve_mediafire + _resolve_ps3_protected_page.
 *
 * Resolves any URL to a direct downloadable link:
 *   1. Already a .pkg direct link → return as-is
 *   2. Mediafire page → API JSON + HTML scraping
 *   3. PS3-protected page → spoof PS3 User-Agent
 *   4. Generic page → scan HTML for .pkg URLs
 */
public class UrlResolver {

    private static final String TAG = "UrlResolver";

    private static final String[] FILE_EXTS = {".pkg", ".rap", ".edat", ".zip", ".7z", ".rar"};

    private static final String[] PS3_USER_AGENTS = {
            "Mozilla/5.0 (PLAYSTATION 3 4.87) AppleWebKit/531.22.8",
            "Mozilla/5.0 (PLAYSTATION 3; 4.80) AppleWebKit/531.22.8",
            "Mozilla/5.0 (PLAYSTATION 3 4.75) AppleWebKit/531.22.8",
            "Mozilla/5.0 (PLAYSTATION 3; 1.00)",
            "PLAYSTATION 3",
    };

    private static final Pattern[] MF_PATTERNS = {
            Pattern.compile("\"downloadUrl\"\\s*:\\s*\"(https://[^\"]+)\""),
            Pattern.compile("\"url\"\\s*:\\s*\"(https://download[^\\.]*\\.mediafire\\.com[^\"]*)\""),
            Pattern.compile("href=\"(https://download\\d*\\.mediafire\\.com/[^\"]+)\""),
            Pattern.compile("id=\"downloadButton\"[^>]+href=\"([^\"]+)\""),
            Pattern.compile("<a[^>]+aria-label=\"[Dd]ownload [Ff]ile\"[^>]+href=\"([^\"]+)\""),
            Pattern.compile("window\\.location\\.href\\s*=\\s*[\"'](https://[^\"']+\\.mediafire\\.com[^\"']+)[\"']"),
            Pattern.compile("\"(https://download\\d+\\.mediafire\\.com/[^\\\\\"]+)\""),
    };

    // ── Main entry point (call from background thread) ──────────────────────

    public static String resolve(String url) {
        url = url.trim().replaceAll("#$", "");
        if (isDirectFile(url)) return url;

        try {
            if (url.toLowerCase().contains("mediafire.com")) {
                String resolved = resolveMediafire(url);
                return resolved.equals(url) ? url : resolved;
            }

            // Try normal fetch first
            String html = fetch(url, null);
            if (html != null) {
                // Check if we were redirected to a direct file
                // (handled inside fetch by following redirects)
                Matcher m = Pattern.compile(
                        "(https?://[^\"'<>\\s]+\\.pkg[^\"'<>\\s]*)", Pattern.CASE_INSENSITIVE)
                        .matcher(html);
                if (m.find()) return m.group(1);

                // Check for PS3 protection keywords
                String lower = html.toLowerCase();
                boolean needsPS3 = lower.contains("playstation") || lower.contains("ps3") ||
                        lower.contains("disabled") || lower.contains("only ps3");
                if (needsPS3) {
                    return resolvePS3Protected(url);
                }
            }

            String ps3result = resolvePS3Protected(url);
            if (!ps3result.equals(url)) return ps3result;

        } catch (Exception e) {
            Log.e(TAG, "resolve error: " + url, e);
        }
        return url;
    }

    // ── Mediafire resolver ────────────────────────────────────────────────────

    private static String resolveMediafire(String url) {
        // Step 1: API JSON
        String qk = extractMfQuickKey(url);
        if (!qk.isEmpty()) {
            try {
                String apiUrl = "https://www.mediafire.com/api/1.5/file/get_links.php" +
                        "?quick_key=" + qk + "&link_type=normal_download&response_format=json";
                String json = fetch(apiUrl, null);
                if (json != null) {
                    // Extract direct_download or normal_download from JSON
                    for (String key : new String[]{"direct_download", "normal_download"}) {
                        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
                        Matcher m = p.matcher(json);
                        if (m.find()) {
                            String found = m.group(1).replace("\\/", "/");
                            if (!found.toLowerCase().contains("file_premium") &&
                                isDirectFile(found)) {
                                Log.d(TAG, "MF API ok: " + found);
                                return found;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "MF API error: " + e.getMessage());
            }
        }

        // Step 2: HTML scraping with browser UA
        try {
            String html = fetch(url, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            if (html != null) {
                for (Pattern pat : MF_PATTERNS) {
                    Matcher m = pat.matcher(html);
                    if (m.find()) {
                        String found = m.group(1).replace("\\/", "/");
                        if (!found.toLowerCase().contains("file_premium")) {
                            Log.d(TAG, "MF scrape ok: " + found);
                            return found;
                        }
                    }
                }
                // CDN pattern fallback
                Matcher m = Pattern.compile(
                        "(https?://download\\d*\\.mediafire\\.com/[^\"'<>\\s]+)",
                        Pattern.CASE_INSENSITIVE).matcher(html);
                if (m.find()) return m.group(1);

                // Any .pkg URL
                m = Pattern.compile(
                        "(https?://[^\"'<>\\s]+\\.pkg(?:[?#][^\"'<>\\s]*)?)",
                        Pattern.CASE_INSENSITIVE).matcher(html);
                if (m.find() && !m.group(1).toLowerCase().contains("file_premium")) {
                    return m.group(1);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "MF scrape error: " + e.getMessage());
        }
        return url;
    }

    // ── PS3 UA bypass ─────────────────────────────────────────────────────────

    private static String resolvePS3Protected(String url) {
        for (String ua : PS3_USER_AGENTS) {
            try {
                String html = fetch(url, ua);
                if (html == null) continue;

                // 1. Direct .pkg URLs in the page
                Matcher m = Pattern.compile(
                        "(https?://[^\"'<>)\\s]+\\.pkg[^\"'<>)\\s]*)",
                        Pattern.CASE_INSENSITIVE).matcher(html);
                while (m.find()) {
                    String found = m.group(1);
                    if (found.toLowerCase().contains("mediafire.com")) {
                        return resolveMediafire(found.replaceAll("[.,;)/]+$", ""));
                    }
                    if (isDirectFile(found)) return found;
                }

                // 2. Mediafire links without .pkg extension
                Matcher mf = Pattern.compile(
                        "(https?://(?:www\\.)?mediafire\\.com/[^\"'<>)\\s]+)",
                        Pattern.CASE_INSENSITIVE).matcher(html);
                if (mf.find()) {
                    String mfUrl = mf.group(1).replaceAll("[.,;)]+$", "");
                    return resolveMediafire(mfUrl);
                }

                // 3. href / data-url attributes with downloadable links
                Matcher attrM = Pattern.compile(
                        "(?:href|data-href|data-url|data-download)\\s*=\\s*[\"'](https?://[^\"']+)[\"']",
                        Pattern.CASE_INSENSITIVE).matcher(html);
                while (attrM.find()) {
                    String lnk = attrM.group(1);
                    if (isDirectFile(lnk)) return lnk;
                    if (lnk.toLowerCase().contains("mediafire.com")) return resolveMediafire(lnk);
                }

            } catch (Exception e) {
                Log.w(TAG, "PS3 UA [" + ua.substring(0, Math.min(30, ua.length())) + "] error: " + e.getMessage());
            }
        }
        return url;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isDirectFile(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        if (lower.contains("mediafire.com")) {
            return lower.matches("https?://download\\d*\\.mediafire\\.com/.*") &&
                    endsWithFileExt(lower.split("[?#]")[0]);
        }
        String clean = lower.split("[?#]")[0].replaceAll("/+$", "");
        return endsWithFileExt(clean);
    }

    private static boolean endsWithFileExt(String url) {
        for (String ext : FILE_EXTS) if (url.endsWith(ext)) return true;
        return false;
    }

    private static String extractMfQuickKey(String url) {
        Matcher m = Pattern.compile("mediafire\\.com/file/([a-z0-9]+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("mediafire\\.com/\\?([a-z0-9]+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("/([a-z0-9]{10,20})(?:/[^/]+)?(?:/file)?$", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) return m.group(1);
        return "";
    }

    private static String fetch(String urlStr, String userAgent) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
            conn.setRequestProperty("Connection", "keep-alive");
            if (userAgent != null) {
                conn.setRequestProperty("User-Agent", userAgent);
            } else {
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            }
            conn.connect();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return null;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            int maxLines = 2000;
            while ((line = reader.readLine()) != null && maxLines-- > 0) {
                sb.append(line).append('\n');
            }
            reader.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "fetch error [" + urlStr + "]: " + e.getMessage());
            return null;
        }
    }
}

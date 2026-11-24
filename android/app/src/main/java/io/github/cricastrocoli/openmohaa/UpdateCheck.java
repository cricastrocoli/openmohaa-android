package io.github.cricastrocoli.openmohaa;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// Asks GitHub, at most once a day, for the project's latest release, so the launcher can say
// when a newer version is out. Only GitHub's public release information is fetched; nothing
// about the device or the player is sent. Without a connection nothing happens.
final class UpdateCheck {
    private static final String API =
            "https://api.github.com/repos/cricastrocoli/openmohaa-android/releases/latest";
    private static final long INTERVAL_MS = 24L * 60 * 60 * 1000;

    interface Listener { void onChecked(); }

    private UpdateCheck() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("update", Context.MODE_PRIVATE);
    }

    // The newer version's {name, page}, from the last check, or null.
    static String[] available(Context c, String current) {
        SharedPreferences p = prefs(c);
        String tag = p.getString("tag", null), url = p.getString("url", null);
        if (tag == null || url == null || !newer(tag, current)) return null;
        return new String[]{tag.startsWith("v") ? tag.substring(1) : tag, url};
    }

    // Checks in the background if the last check is a day old; calls back on success.
    static void checkIfDue(Context c, Listener listener) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong("checked", 0) < INTERVAL_MS) return;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection http = null;
            try {
                http = (HttpURLConnection) new URL(API).openConnection();
                http.setConnectTimeout(8000);
                http.setReadTimeout(8000);
                http.setRequestProperty("Accept", "application/vnd.github+json");
                http.setRequestProperty("User-Agent", "OpenMoHAA-Android");
                if (http.getResponseCode() != 200) return;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = http.getInputStream()) {
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                JSONObject release = new JSONObject(out.toString("UTF-8"));
                prefs(app).edit()
                        .putString("tag", release.getString("tag_name"))
                        .putString("url", release.getString("html_url"))
                        .putLong("checked", now)
                        .apply();
                if (listener != null) listener.onChecked();
            } catch (Exception e) {
                Log.v("MOHAA", "update check: " + e);
            } finally {
                if (http != null) http.disconnect();
            }
        }, "update-check").start();
    }

    // Whether version a ("v1.2.1", "1.3") is newer than b, number by number.
    static boolean newer(String a, String b) {
        int[] x = numbers(a), y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int[] numbers(String v) {
        String[] parts = v.replaceAll("[^0-9.]", "").split("\\.");
        int[] n = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                n[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                n[i] = 0;
            }
        }
        return n;
    }
}

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

    // The newer version's {name, page, APK address or null}, from the last check, or null.
    static String[] available(Context c, String current) {
        SharedPreferences p = prefs(c);
        String tag = p.getString("tag", null), url = p.getString("url", null);
        if (tag == null || url == null || !newer(tag, current)) return null;
        return new String[]{tag.startsWith("v") ? tag.substring(1) : tag, url, p.getString("apk", null)};
    }

    // ---- Installing ------------------------------------------------------------------------

    interface Progress { void update(int percent); }

    // Downloads the APK straight into an installer session and commits it: Android then asks
    // the player to confirm (status STATUS_PENDING_USER_ACTION, sent to the statusIntent). It
    // only installs over this app if signed with the same key. Runs on the caller's thread.
    static void install(Context c, String apkUrl, android.content.IntentSender statusIntent, Progress progress)
            throws java.io.IOException {
        android.content.pm.PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        android.content.pm.PackageInstaller.SessionParams params = new android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        // A download left from an earlier attempt (cancelled) goes first.
        for (android.content.pm.PackageInstaller.SessionInfo old : installer.getMySessions()) {
            try {
                installer.abandonSession(old.getSessionId());
            } catch (SecurityException ignored) {
            }
        }
        int id = installer.createSession(params);
        HttpURLConnection http = null;
        try (android.content.pm.PackageInstaller.Session session = installer.openSession(id)) {
            http = (HttpURLConnection) new URL(apkUrl).openConnection();
            http.setConnectTimeout(10000);
            http.setReadTimeout(20000);
            http.setRequestProperty("User-Agent", "OpenMoHAA-Android");
            if (http.getResponseCode() != 200) throw new java.io.IOException("HTTP " + http.getResponseCode());
            long total = http.getContentLengthLong(), done = 0;
            int lastPercent = -1;
            try (InputStream in = http.getInputStream();
                 java.io.OutputStream out = session.openWrite("update.apk", 0, total > 0 ? total : -1)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    done += n;
                    int percent = total > 0 ? (int) (done * 100 / total) : -1;
                    if (percent != lastPercent && progress != null) progress.update(lastPercent = percent);
                }
                session.fsync(out);
            }
            session.commit(statusIntent);
        } catch (java.io.IOException | RuntimeException e) {
            installer.abandonSession(id);
            throw e;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    // Checks in the background if the last check is a day old; calls back on success.
    static void checkIfDue(Context c, Listener listener) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        // (A check saved before the APK's address was kept counts as not done.)
        if (now - p.getLong("checked", 0) < INTERVAL_MS && p.contains("apk")) return;
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
                String apk = null;  // the release's APK, to update from inside the app
                org.json.JSONArray assets = release.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url", null);
                }
                prefs(app).edit()
                        .putString("tag", release.getString("tag_name"))
                        .putString("url", release.getString("html_url"))
                        .putString("apk", apk)
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

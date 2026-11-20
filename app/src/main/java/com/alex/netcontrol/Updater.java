package com.alex.netcontrol;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The updater. NetControl is handed out from GitHub Releases, where nothing
 * else is watching for new versions, so it offers to fetch its own.
 *
 * This is the only code in the app that touches a network, and the only reason
 * the manifest declares INTERNET. Deliberate limits, so it stays inside the
 * rules the rest of the app follows:
 *
 *  - It only ever runs when the user taps "Check for updates". There is no
 *    background check, no scheduled job and no check on launch. The app still
 *    does nothing when you are not looking at it.
 *  - It talks to exactly one host, api.github.com, and sends no identifying
 *    information beyond what any HTTP request unavoidably carries.
 *  - It asks before downloading, and again before installing.
 */
final class Updater {

    private static final String API =
            "https://api.github.com/repos/mimbasher/NetControl/releases/latest";
    static final String RELEASES_URL =
            "https://github.com/mimbasher/NetControl/releases/latest";

    private static final String ASSET = "NetControl.apk";
    private static final int TIMEOUT_MS = 15000;
    /** A release APK is ~2MB; this is a sanity bound, not a real limit. */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private Updater() { }

    static void check(Activity a) {
        Toast.makeText(a, R.string.update_checking, Toast.LENGTH_SHORT).show();
        ExecutorService io = Executors.newSingleThreadExecutor();
        Handler ui = new Handler(Looper.getMainLooper());
        io.execute(() -> {
            String tag = null, url = null, err = null;
            try {
                JSONObject o = new JSONObject(get(API));
                tag = o.optString("tag_name", "");
                JSONArray assets = o.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (ASSET.equals(as.optString("name"))) {
                        url = as.optString("browser_download_url");
                        break;
                    }
                }
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }
            final String fTag = tag, fUrl = url, fErr = err;
            ui.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (fErr != null) {
                    Toast.makeText(a, a.getString(R.string.update_failed, fErr),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (fTag == null || fTag.isEmpty() || fUrl == null) {
                    Toast.makeText(a, R.string.update_none, Toast.LENGTH_LONG).show();
                    return;
                }
                if (!isNewer(fTag, BuildConfig.VERSION_NAME)) {
                    Toast.makeText(a, a.getString(R.string.update_current,
                            BuildConfig.VERSION_NAME), Toast.LENGTH_LONG).show();
                    return;
                }
                new MaterialAlertDialogBuilder(a)
                        .setTitle(a.getString(R.string.update_available, strip(fTag)))
                        .setMessage(a.getString(R.string.update_body,
                                BuildConfig.VERSION_NAME, strip(fTag)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.update_open_page,
                                (d, w) -> open(a, RELEASES_URL))
                        .setPositiveButton(R.string.update_download,
                                (d, w) -> start(a, fUrl))
                        .show();
            });
            io.shutdown();
        });
    }

    /** Compares dotted numeric versions, ignoring a leading "v". */
    static boolean isNewer(String remote, String local) {
        String[] r = strip(remote).split("\\."), l = strip(local).split("\\.");
        for (int i = 0; i < Math.max(r.length, l.length); i++) {
            int ri = num(r, i), li = num(l, i);
            if (ri != li) return ri > li;
        }
        return false;
    }

    private static int num(String[] parts, int i) {
        if (i >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[i].replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String strip(String v) {
        String s = v == null ? "" : v.trim();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            // GitHub rejects requests with no User-Agent. Send the app name and
            // nothing else; no device or user information belongs in here.
            c.setRequestProperty("User-Agent", "NetControl");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != 200) {
                throw new Exception("HTTP " + c.getResponseCode());
            }
            StringBuilder sb = new StringBuilder();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /**
     * Checks the sideload permission before spending the user's data on a
     * download. Asking afterwards means they wait for a few MB and only then
     * get told they cannot install it.
     */
    private static void start(Activity a, String url) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !a.getPackageManager().canRequestPackageInstalls()) {
            new MaterialAlertDialogBuilder(a)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_body)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.update_permission_open, (d, w) -> {
                        try {
                            a.startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(a, R.string.update_no_settings,
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .show();
            return;
        }
        download(a, url);
    }

    private static void download(Activity a, String url) {
        View v = a.getLayoutInflater().inflate(R.layout.dialog_update_progress, null);
        LinearProgressIndicator bar = v.findViewById(R.id.progress);
        TextView label = v.findViewById(R.id.progressText);
        label.setText(R.string.update_progress_starting);
        AlertDialog dlg = new MaterialAlertDialogBuilder(a)
                .setTitle(a.getString(R.string.update_progress_title, ASSET))
                .setView(v)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, null)
                .show();

        ExecutorService io = Executors.newSingleThreadExecutor();
        Handler ui = new Handler(Looper.getMainLooper());
        io.execute(() -> {
            String err = null;
            File out = new File(a.getCacheDir(), "updates");
            File apk = new File(out, ASSET);
            try {
                if (!out.isDirectory() && !out.mkdirs()) throw new Exception("cache unavailable");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", "NetControl");
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                final long len = c.getContentLengthLong();
                final String size = human(len);
                long total = 0;
                int lastPct = -1;
                try (InputStream in = c.getInputStream();
                     FileOutputStream fo = new FileOutputStream(apk)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) throw new Exception("file too large");
                        fo.write(buf, 0, n);
                        if (len > 0) {
                            int pct = (int) (total * 100 / len);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final int p = pct;
                                ui.post(() -> {
                                    bar.setProgress(p, true);
                                    label.setText(a.getString(R.string.update_progress, p, size));
                                });
                            }
                        }
                    }
                }
                c.disconnect();
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            }
            final String fErr = err;
            ui.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (dlg.isShowing()) dlg.dismiss();
                if (fErr != null) {
                    Toast.makeText(a, a.getString(R.string.update_failed, fErr),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                install(a, apk);
            });
            io.shutdown();
        });
    }

    private static String human(long bytes) {
        if (bytes <= 0) return "?";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static void install(Activity a, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    a, a.getPackageName() + ".updates", apk);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, a.getString(R.string.update_failed,
                    String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
        }
    }

    private static void open(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, R.string.update_no_browser, Toast.LENGTH_LONG).show();
        }
    }
}

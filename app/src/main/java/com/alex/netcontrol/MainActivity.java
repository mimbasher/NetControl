package com.alex.netcontrol;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Blocks an app's internet (Wi-Fi AND mobile data) with no VPN.
 * Uses Shizuku to run Android's own system firewall command:
 *   cmd connectivity set-package-networking-enabled false <package>
 * Rules are cleared on reboot; tap "Re-apply blocks" after starting Shizuku.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "netcontrol";
    private static final String KEY_BLOCK = "block:";
    private static final int REQ_SHIZUKU = 7;

    private SharedPreferences prefs;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AppAdapter adapter;
    private TextView status;
    private TextView log;
    private String filter = "";

    static class App {
        ApplicationInfo info;
        String pkg;
        String label;
    }

    static class Holder {
        ImageView icon;
        TextView name;
        CheckBox block;
    }

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> runOnUiThread(this::updateStatus);
    private final Shizuku.OnBinderDeadListener binderDead = () -> runOnUiThread(this::updateStatus);
    private final Shizuku.OnRequestPermissionResultListener permResult =
            (code, result) -> runOnUiThread(this::updateStatus);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setFitsSystemWindows(true);

        status = new TextView(this);
        status.setPadding(0, 0, 0, dp(6));
        status.setOnClickListener(v -> requestShizuku());
        root.addView(status);

        Button reapply = new Button(this);
        reapply.setText("Re-apply blocks (after reboot)");
        reapply.setOnClickListener(v -> reapplyAll());
        root.addView(reapply);

        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        root.addView(search);

        log = new TextView(this);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        log.setPadding(0, dp(6), 0, dp(6));
        log.setText("Tick Block to cut an app off Wi-Fi and mobile data.");
        root.addView(log);

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permResult);
        updateStatus();

        new Thread(this::loadApps).start();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permResult);
        worker.shutdown();
        super.onDestroy();
    }

    // ---------- Shizuku ----------

    private boolean shizukuRunning() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    private boolean shizukuReady() {
        return shizukuRunning()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    private void updateStatus() {
        if (!shizukuRunning()) {
            status.setText("⚠ Shizuku is not running. Open the Shizuku app and start it.");
        } else if (!shizukuReady()) {
            status.setText("⚠ Tap here to allow NetControl in Shizuku.");
        } else {
            status.setText("✓ Shizuku connected");
        }
    }

    private void requestShizuku() {
        if (shizukuRunning() && !shizukuReady()) Shizuku.requestPermission(REQ_SHIZUKU);
        updateStatus();
    }

    /** Runs a command with shell (ADB) privileges through Shizuku. */
    private static String run(String... cmd) {
        try {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, cmd, null, null);
            String out = (read(p.getInputStream()) + read(p.getErrorStream())).trim();
            int code = p.waitFor();
            return code == 0 ? (out.isEmpty() ? "OK" : out) : "exit " + code + ": " + out;
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String setBlocked(String pkg, boolean blocked) {
        String chain = run("cmd", "connectivity", "set-chain3-enabled", "true");
        String rule = run("cmd", "connectivity", "set-package-networking-enabled",
                blocked ? "false" : "true", pkg);
        return chain.equals("OK") ? rule : chain + " | " + rule;
    }

    private void apply(App a, boolean blocked) {
        if (!shizukuReady()) {
            updateStatus();
            log.setText("Saved, but not applied: Shizuku isn't connected.");
            return;
        }
        worker.execute(() -> {
            String result = setBlocked(a.pkg, blocked);
            runOnUiThread(() -> log.setText(
                    (blocked ? "Blocked " : "Unblocked ") + a.label + " → " + result));
        });
    }

    private void reapplyAll() {
        if (!shizukuReady()) { requestShizuku(); return; }
        List<String> pkgs = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_BLOCK) && Boolean.TRUE.equals(e.getValue())) {
                pkgs.add(e.getKey().substring(KEY_BLOCK.length()));
            }
        }
        log.setText("Re-applying " + pkgs.size() + " blocks…");
        worker.execute(() -> {
            String last = "nothing to apply";
            int ok = 0;
            for (String pkg : pkgs) {
                last = setBlocked(pkg, true);
                if (last.equals("OK")) ok++;
            }
            final String msg = "Re-applied " + ok + "/" + pkgs.size()
                    + (ok == pkgs.size() ? "" : " — last result: " + last);
            runOnUiThread(() -> log.setText(msg));
        });
    }

    // ---------- App list ----------

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<App> found = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(getPackageName())) continue;
            if (pm.checkPermission(Manifest.permission.INTERNET, ai.packageName)
                    != PackageManager.PERMISSION_GRANTED) continue;
            App a = new App();
            a.info = ai;
            a.pkg = ai.packageName;
            a.label = String.valueOf(ai.loadLabel(pm));
            found.add(a);
        }
        Collections.sort(found, (x, y) -> x.label.compareToIgnoreCase(y.label));
        runOnUiThread(() -> {
            all.clear();
            all.addAll(found);
            applyFilter();
        });
    }

    private void applyFilter() {
        shown.clear();
        for (App a : all) {
            if (filter.isEmpty()
                    || a.label.toLowerCase(Locale.ROOT).contains(filter)
                    || a.pkg.contains(filter)) {
                shown.add(a);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View row, ViewGroup parent) {
            Holder h;
            if (row == null) {
                LinearLayout l = new LinearLayout(MainActivity.this);
                l.setOrientation(LinearLayout.HORIZONTAL);
                l.setGravity(Gravity.CENTER_VERTICAL);
                l.setPadding(0, dp(6), 0, dp(6));

                h = new Holder();
                h.icon = new ImageView(MainActivity.this);
                l.addView(h.icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

                h.name = new TextView(MainActivity.this);
                h.name.setPadding(dp(10), 0, dp(6), 0);
                l.addView(h.name, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                h.block = new CheckBox(MainActivity.this);
                h.block.setText("Block");
                l.addView(h.block);

                l.setTag(h);
                row = l;
            } else {
                h = (Holder) row.getTag();
            }

            App a = shown.get(pos);
            h.icon.setImageDrawable(a.info.loadIcon(getPackageManager()));
            h.name.setText(a.label);

            String key = KEY_BLOCK + a.pkg;
            h.block.setOnCheckedChangeListener(null);
            h.block.setChecked(prefs.getBoolean(key, false));
            h.block.setOnCheckedChangeListener((b, checked) -> {
                prefs.edit().putBoolean(key, checked).apply();
                apply(a, checked);
            });
            return row;
        }
    }
}

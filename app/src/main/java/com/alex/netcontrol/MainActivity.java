package com.alex.netcontrol;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Cuts an app off Wi-Fi AND mobile data with no VPN and no root.
 *
 * Mechanism: Shizuku lends us shell (ADB) privileges just long enough to run
 * Android's own firewall commands, which write the app's UID into a kernel BPF
 * firewall map:
 *
 *   cmd connectivity set-chain3-enabled true
 *   cmd connectivity set-package-networking-enabled false <package>
 *
 * The rule then lives in the kernel, not in this app and not in Shizuku, so
 * Shizuku can be stopped afterwards and the block stays. A reboot clears it.
 *
 * Every write is read straight back with get-package-networking-enabled, so the
 * UI shows the real kernel state rather than just what you ticked.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "netcontrol";
    private static final String KEY_BLOCK = "block:";
    private static final int REQ_SHIZUKU = 7;

    // Filters
    private static final int F_ALL = 0, F_BLOCKED = 1, F_USER = 2, F_SYSTEM = 3;

    // Verified kernel state for one package
    private static final int S_UNKNOWN = 0, S_ENFORCED = 1, S_NOT_ENFORCED = 2;

    private static boolean autoAppliedThisProcess = false;

    // Folding, unfolding and rotating recreate the Activity but not the
    // process, so verified state is cached here instead of being lost.
    private static final Map<String, Integer> STATE_CACHE = new HashMap<>();
    private static final Map<String, String> DETAIL_CACHE = new HashMap<>();

    private SharedPreferences prefs;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService icons = Executors.newFixedThreadPool(3);

    private AppAdapter adapter;
    private View root;
    private TextView statusTitle, statusBody, summary;
    private ImageView statusIcon;
    private MaterialButton statusAction;
    private RecyclerView list;

    private String query = "";
    private int filter = F_ALL;

    static class App {
        ApplicationInfo info;
        String pkg;
        String label;
        boolean system;
        boolean wanted;
        int state = S_UNKNOWN;
        String detail;
        Drawable icon;
    }

    private final Shizuku.OnBinderReceivedListener binderReceived =
            () -> runOnUiThread(() -> { updateStatus(); maybeAutoApply(); });
    private final Shizuku.OnBinderDeadListener binderDead =
            () -> runOnUiThread(this::updateStatus);
    private final Shizuku.OnRequestPermissionResultListener permResult =
            (code, result) -> runOnUiThread(() -> { updateStatus(); maybeAutoApply(); });

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        root = findViewById(R.id.coordinator);
        statusTitle = findViewById(R.id.statusTitle);
        statusBody = findViewById(R.id.statusBody);
        statusIcon = findViewById(R.id.statusIcon);
        statusAction = findViewById(R.id.statusAction);
        summary = findViewById(R.id.summary);
        list = findViewById(R.id.list);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.main);
        toolbar.setOnMenuItemClickListener(this::onMenu);

        adapter = new AppAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setHasFixedSize(false);

        TextInputEditText search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        ChipGroup filters = findViewById(R.id.filters);
        filters.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            if (id == R.id.chipBlocked) filter = F_BLOCKED;
            else if (id == R.id.chipUser) filter = F_USER;
            else if (id == R.id.chipSystem) filter = F_SYSTEM;
            else filter = F_ALL;
            applyFilter();
        });

        statusAction.setOnClickListener(v -> requestShizuku());

        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permResult);

        updateStatus();
        worker.execute(this::loadApps);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permResult);
        worker.shutdownNow();
        icons.shutdownNow();
        super.onDestroy();
    }

    private boolean onMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_verify) { verifyAll(); return true; }
        if (id == R.id.action_reapply) { reapplyAll(false); return true; }
        return false;
    }

    // ---------- Shizuku ----------

    private boolean shizukuRunning() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    private boolean shizukuReady() {
        try {
            return shizukuRunning()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void updateStatus() {
        if (!shizukuRunning()) {
            statusTitle.setText("Shizuku is not running");
            statusBody.setText("Existing blocks stay active. Start Shizuku only to change them.");
            statusAction.setVisibility(View.GONE);
        } else if (!shizukuReady()) {
            statusTitle.setText("Permission needed");
            statusBody.setText("Let NetControl use Shizuku to run the firewall commands.");
            statusAction.setText("Allow");
            statusAction.setVisibility(View.VISIBLE);
        } else {
            statusTitle.setText("Shizuku connected");
            statusBody.setText("Blocks applied now survive stopping Shizuku, but not a reboot.");
            statusAction.setVisibility(View.GONE);
        }
    }

    private void requestShizuku() {
        if (shizukuRunning() && !shizukuReady()) {
            try { Shizuku.requestPermission(REQ_SHIZUKU); } catch (Throwable ignored) { }
        }
        updateStatus();
    }

    /** Re-applies saved blocks once per process launch, to cover a reboot. */
    private void maybeAutoApply() {
        if (autoAppliedThisProcess || !shizukuReady()) return;
        if (all.isEmpty()) return; // wait for the app list so rows can show their state
        if (blockedFromPrefs().isEmpty()) { autoAppliedThisProcess = true; return; }
        autoAppliedThisProcess = true;
        reapplyAll(true);
    }

    // ---------- Shell ----------

    /** Runs one command with shell (ADB) privileges through Shizuku. */
    private static String run(String... cmd) {
        Process p = null;
        try {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            p = (Process) m.invoke(null, cmd, null, null);
            String out = read(p.getInputStream());
            String err = read(p.getErrorStream());
            int code = p.waitFor();
            String text = (out + err).trim();
            if (code == 0) return text.isEmpty() ? "OK" : text;
            return "exit " + code + (text.isEmpty() ? "" : ": " + text);
        } catch (Throwable t) {
            String msg = t.getCause() != null ? String.valueOf(t.getCause()) : String.valueOf(t);
            return "error: " + msg;
        } finally {
            if (p != null) p.destroy();
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

    private static String enableChain() {
        return run("cmd", "connectivity", "set-chain3-enabled", "true");
    }

    private static String setNetworking(String pkg, boolean enabled) {
        return run("cmd", "connectivity", "set-package-networking-enabled",
                enabled ? "true" : "false", pkg);
    }

    /** Returns the token after the last colon, lowercased. "pkg:deny" -> "deny". */
    private static String verdict(String out) {
        int c = out.lastIndexOf(':');
        if (c < 0) return "";
        return out.substring(c + 1).trim().toLowerCase(Locale.ROOT);
    }

    /** True when FIREWALL_CHAIN_OEM_DENY_3 is actually being enforced. */
    private static boolean chainEnabled() {
        // "chain:enabled" or "chain:disabled". Note that "disabled" contains
        // "enabled" as a substring, so match on the token, not with contains().
        return verdict(run("cmd", "connectivity", "get-chain3-enabled")).startsWith("enabled");
    }

    /**
     * Reads the real kernel state back. Returns one of S_*.
     * Output format is "<package>:deny" or "<package>:allow".
     */
    private static int queryState(String pkg, boolean wantBlocked, String[] rawOut) {
        String out = run("cmd", "connectivity", "get-package-networking-enabled", pkg);
        rawOut[0] = out;
        String v = verdict(out);
        boolean blocked;
        if (v.startsWith("deny")) blocked = true;
        else if (v.startsWith("allow")) blocked = false;
        else return S_UNKNOWN;
        // A deny bit is inert while the chain is off, so that is not enforcement.
        if (blocked && !chainEnabled()) {
            rawOut[0] = "deny bit set but chain 3 is disabled";
            return S_NOT_ENFORCED;
        }
        return blocked == wantBlocked ? S_ENFORCED : S_NOT_ENFORCED;
    }

    // ---------- Actions ----------

    private Set<String> blockedFromPrefs() {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_BLOCK) && Boolean.TRUE.equals(e.getValue())) {
                out.add(e.getKey().substring(KEY_BLOCK.length()));
            }
        }
        return out;
    }

    /** Toggling one app: write the rule, then read it straight back. */
    private void toggle(App a, boolean blocked) {
        a.wanted = blocked;
        prefs.edit().putBoolean(KEY_BLOCK + a.pkg, blocked).apply();
        setState(a, S_UNKNOWN, null);

        if (!shizukuReady()) {
            setState(a, S_UNKNOWN, "Saved. Start Shizuku to apply");
            adapter.notifyDataSetChanged();
            updateStatus();
            toast("Not applied: Shizuku isn't connected");
            return;
        }

        worker.execute(() -> {
            String chain = enableChain();
            String write = setNetworking(a.pkg, !blocked);
            String[] raw = new String[1];
            int state = queryState(a.pkg, blocked, raw);

            runOnUiThread(() -> {
                String detail;
                if (state == S_ENFORCED) {
                    detail = blocked ? "Blocked, enforced by the kernel" : null;
                } else if (state == S_NOT_ENFORCED) {
                    detail = "Command ran but the rule did not stick";
                } else {
                    detail = "Could not read state: " + raw[0];
                }
                setState(a, state, detail);
                adapter.notifyDataSetChanged();
                refreshSummary();

                if (state == S_ENFORCED) {
                    toast((blocked ? "Blocked " : "Unblocked ") + a.label);
                } else {
                    String why = !"OK".equals(chain) ? chain
                            : (!"OK".equals(write) ? write : raw[0]);
                    toast("Failed on " + a.label + ": " + why);
                }
            });
        });
    }

    /** Re-writes every saved block. Needed after a reboot. */
    private void reapplyAll(boolean silent) {
        Set<String> pkgs = blockedFromPrefs();
        if (pkgs.isEmpty()) { if (!silent) toast("No blocks saved yet"); return; }
        if (!shizukuReady()) {
            if (!silent) { requestShizuku(); toast("Start Shizuku first"); }
            return;
        }
        if (!silent) toast("Re-applying " + pkgs.size() + " block(s)…");

        worker.execute(() -> {
            String chain = enableChain();
            int ok = 0;
            String lastErr = null;
            for (String pkg : pkgs) {
                setNetworking(pkg, false);
                String[] raw = new String[1];
                int st = queryState(pkg, true, raw);
                if (st == S_ENFORCED) ok++; else lastErr = raw[0];
                App a = find(pkg);
                if (a != null) {
                    setState(a, st, st == S_ENFORCED
                            ? "Blocked, enforced by the kernel"
                            : "Not enforced: " + raw[0]);
                }
            }
            final int good = ok;
            final String err = lastErr;
            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                refreshSummary();
                if (good == pkgs.size()) {
                    toast("All " + good + " block(s) active");
                } else {
                    toast(good + "/" + pkgs.size() + " active, last error: "
                            + (!"OK".equals(chain) ? chain : String.valueOf(err)));
                }
            });
        });
    }

    /** Reads kernel state for every saved block without changing anything. */
    private void verifyAll() {
        Set<String> pkgs = blockedFromPrefs();
        if (pkgs.isEmpty()) { toast("No blocks saved yet"); return; }
        if (!shizukuReady()) { requestShizuku(); toast("Start Shizuku to verify"); return; }
        toast("Checking " + pkgs.size() + " block(s)…");

        worker.execute(() -> {
            int enforced = 0;
            for (String pkg : pkgs) {
                String[] raw = new String[1];
                int st = queryState(pkg, true, raw);
                if (st == S_ENFORCED) enforced++;
                App a = find(pkg);
                if (a != null) {
                    setState(a, st, st == S_ENFORCED
                            ? "Blocked, enforced by the kernel"
                            : st == S_NOT_ENFORCED
                                ? "NOT enforced. Tap Re-apply blocks"
                                : "Unreadable: " + raw[0]);
                }
            }
            final int good = enforced;
            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                refreshSummary();
                toast(good == pkgs.size()
                        ? "Verified: all " + good + " block(s) live in the kernel"
                        : good + "/" + pkgs.size() + " still enforced");
            });
        });
    }

    /** Writes row state and remembers it across configuration changes. */
    private static void setState(App a, int state, String detail) {
        a.state = state;
        a.detail = detail;
        STATE_CACHE.put(a.pkg, state);
        if (detail == null) DETAIL_CACHE.remove(a.pkg);
        else DETAIL_CACHE.put(a.pkg, detail);
    }

    private App find(String pkg) {
        for (App a : all) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private void toast(String msg) {
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
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
            a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            a.wanted = prefs.getBoolean(KEY_BLOCK + a.pkg, false);
            Integer cached = STATE_CACHE.get(a.pkg);
            if (cached != null) {
                a.state = cached;
                a.detail = DETAIL_CACHE.get(a.pkg);
            }
            found.add(a);
        }
        Collections.sort(found, (x, y) -> x.label.compareToIgnoreCase(y.label));
        runOnUiThread(() -> {
            all.clear();
            all.addAll(found);
            applyFilter();
            maybeAutoApply();
        });
    }

    private void applyFilter() {
        shown.clear();
        for (App a : all) {
            if (filter == F_BLOCKED && !a.wanted) continue;
            if (filter == F_USER && a.system) continue;
            if (filter == F_SYSTEM && !a.system) continue;
            if (!query.isEmpty()
                    && !a.label.toLowerCase(Locale.ROOT).contains(query)
                    && !a.pkg.toLowerCase(Locale.ROOT).contains(query)) continue;
            shown.add(a);
        }
        adapter.notifyDataSetChanged();
        refreshSummary();
    }

    private void refreshSummary() {
        int blocked = 0, bad = 0;
        for (App a : all) {
            if (a.wanted) {
                blocked++;
                if (a.state == S_NOT_ENFORCED) bad++;
            }
        }
        String s = shown.size() + " shown · " + blocked + " blocked";
        if (bad > 0) s += " · " + bad + " NOT enforced";
        summary.setText(s);
    }

    private void loadIcon(App a, Holder h) {
        if (a.icon != null) { h.icon.setImageDrawable(a.icon); return; }
        h.icon.setImageDrawable(null);
        final String want = a.pkg;
        icons.execute(() -> {
            Drawable d;
            try { d = a.info.loadIcon(getPackageManager()); } catch (Throwable t) { return; }
            a.icon = d;
            runOnUiThread(() -> { if (want.equals(h.bound)) h.icon.setImageDrawable(d); });
        });
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name, pkg, state;
        final MaterialSwitch block;
        String bound;

        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            name = v.findViewById(R.id.name);
            pkg = v.findViewById(R.id.pkg);
            state = v.findViewById(R.id.state);
            block = v.findViewById(R.id.block);
        }
    }

    private class AppAdapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            App a = shown.get(pos);
            h.bound = a.pkg;
            h.name.setText(a.label);
            h.pkg.setText(a.pkg);
            loadIcon(a, h);

            h.block.setOnCheckedChangeListener(null);
            h.block.setChecked(a.wanted);
            h.block.setOnCheckedChangeListener((b, checked) -> toggle(a, checked));
            h.itemView.setOnClickListener(v -> h.block.toggle());

            if (a.detail == null) {
                h.state.setVisibility(View.GONE);
            } else {
                h.state.setVisibility(View.VISIBLE);
                h.state.setText(a.detail);
                int color = a.state == S_ENFORCED ? R.color.state_ok
                        : a.state == S_NOT_ENFORCED ? R.color.state_warn
                        : R.color.state_idle;
                h.state.setTextColor(getResources().getColor(color, getTheme()));
            }
        }

        @Override
        public int getItemCount() { return shown.size(); }
    }
}

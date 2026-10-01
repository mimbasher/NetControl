package com.alex.netcontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;

    private SharedPreferences prefs;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private AppAdapter adapter;
    private Button toggle;
    private String filter = "";

    static class App {
        ApplicationInfo info;
        String pkg;
        String label;
    }

    static class Holder {
        ImageView icon;
        TextView name;
        CheckBox wifi;
        CheckBox data;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(FirewallVpnService.PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setFitsSystemWindows(true);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle);

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

        TextView hint = new TextView(this);
        hint.setText("Tick a box to BLOCK that app on Wi-Fi and/or mobile data.");
        hint.setPadding(0, dp(8), 0, dp(8));
        root.addView(hint);

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateToggle();

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        }

        new Thread(this::loadApps).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateToggle();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<App> found = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(getPackageName())) continue;
            // Only apps that can use the internet at all.
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

    private boolean isEnabled() {
        return prefs.getBoolean(FirewallVpnService.KEY_ENABLED, false);
    }

    private void onToggle() {
        if (isEnabled()) {
            startService(new Intent(this, FirewallVpnService.class)
                    .setAction(FirewallVpnService.ACTION_STOP));
            prefs.edit().putBoolean(FirewallVpnService.KEY_ENABLED, false).apply();
            updateToggle();
        } else {
            Intent consent = VpnService.prepare(this);
            if (consent != null) {
                startActivityForResult(consent, REQ_VPN);
            } else {
                onActivityResult(REQ_VPN, RESULT_OK, null);
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_VPN && result == RESULT_OK) {
            prefs.edit().putBoolean(FirewallVpnService.KEY_ENABLED, true).apply();
            startForegroundService(new Intent(this, FirewallVpnService.class));
            updateToggle();
        }
    }

    private void updateToggle() {
        toggle.setText(isEnabled()
                ? "Firewall ON — tap to turn off"
                : "Firewall OFF — tap to turn on");
    }

    private void bind(CheckBox cb, String key) {
        cb.setOnCheckedChangeListener(null);
        cb.setChecked(prefs.getBoolean(key, false));
        cb.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            if (isEnabled()) {
                startService(new Intent(this, FirewallVpnService.class)
                        .setAction(FirewallVpnService.ACTION_RELOAD));
            }
        });
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

                h.wifi = new CheckBox(MainActivity.this);
                h.wifi.setText("Wi-Fi");
                l.addView(h.wifi);

                h.data = new CheckBox(MainActivity.this);
                h.data.setText("Data");
                l.addView(h.data);

                l.setTag(h);
                row = l;
            } else {
                h = (Holder) row.getTag();
            }

            App a = shown.get(pos);
            h.icon.setImageDrawable(a.info.loadIcon(getPackageManager()));
            h.name.setText(a.label);
            bind(h.wifi, FirewallVpnService.KEY_WIFI + a.pkg);
            bind(h.data, FirewallVpnService.KEY_DATA + a.pkg);
            return row;
        }
    }
}

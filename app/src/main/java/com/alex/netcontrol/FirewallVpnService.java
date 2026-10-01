package com.alex.netcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.Map;
import java.util.TreeSet;

/**
 * Per-app firewall without root.
 *
 * Only the apps blocked on the CURRENT network (Wi-Fi or mobile data) are routed
 * into a local VPN tunnel. Nothing ever reads from that tunnel, so their packets
 * go nowhere. Every other app keeps using the normal connection untouched.
 * When the phone switches between Wi-Fi and mobile data the tunnel is rebuilt
 * with the matching block list.
 */
public class FirewallVpnService extends VpnService {
    static final String PREFS = "netcontrol";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_WIFI = "wifi:";
    static final String KEY_DATA = "data:";
    static final String ACTION_STOP = "com.alex.netcontrol.STOP";
    static final String ACTION_RELOAD = "com.alex.netcontrol.RELOAD";

    private static final String CHANNEL = "status";
    private static final int NOTIF_ID = 1;

    private SharedPreferences prefs;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private ParcelFileDescriptor tun;
    private String lastKey;
    private volatile boolean onWifi = true;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        cm = getSystemService(ConnectivityManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
            stopFirewall();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Normal start, reload, or "Always-on VPN" start at boot.
        prefs.edit().putBoolean(KEY_ENABLED, true).apply();
        try {
            startForeground(NOTIF_ID, buildNotification("Starting…"));
        } catch (RuntimeException ignored) {
            // Keep working even if the system refuses the notification.
        }

        if (callback == null) {
            onWifi = isWifi(currentCaps());
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    onWifi = isWifi(caps);
                    rebuild();
                }
            };
            cm.registerDefaultNetworkCallback(callback);
        }

        rebuild();
        return START_STICKY;
    }

    private NetworkCapabilities currentCaps() {
        Network n = cm.getActiveNetwork();
        return n == null ? null : cm.getNetworkCapabilities(n);
    }

    /** Anything that isn't cellular (Wi-Fi, Ethernet, none) uses the Wi-Fi rules. */
    private static boolean isWifi(NetworkCapabilities caps) {
        return caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    private synchronized void rebuild() {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return;

        String prefix = onWifi ? KEY_WIFI : KEY_DATA;
        TreeSet<String> blocked = new TreeSet<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(prefix) && Boolean.TRUE.equals(e.getValue())) {
                blocked.add(e.getKey().substring(prefix.length()));
            }
        }

        String key = prefix + blocked;
        if (key.equals(lastKey)) return;
        lastKey = key;

        ParcelFileDescriptor old = tun;
        tun = null;

        Builder b = new Builder()
                .setSession("NetControl")
                .addAddress("10.111.222.1", 32)
                .addAddress("fd00:6e65:7463::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setConfigureIntent(openAppIntent());

        int added = 0;
        for (String pkg : blocked) {
            try {
                b.addAllowedApplication(pkg);
                added++;
            } catch (PackageManager.NameNotFoundException ignored) {
                // App was uninstalled.
            }
        }

        // IMPORTANT: a tunnel with zero allowed apps would capture EVERY app,
        // so only build one when there is something to block.
        if (added > 0) {
            tun = b.establish(); // new tunnel replaces the old one with no gap
        }
        closeQuietly(old);

        String net = onWifi ? "Wi-Fi" : "mobile data";
        String text = added == 0
                ? "On " + net + " · nothing blocked"
                : "On " + net + " · blocking " + added + (added == 1 ? " app" : " apps");
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text));
    }

    private synchronized void stopFirewall() {
        if (callback != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) { }
            callback = null;
        }
        closeQuietly(tun);
        tun = null;
        lastKey = null;
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd == null) return;
        try { fd.close(); } catch (IOException ignored) { }
    }

    private PendingIntent openAppIntent() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Firewall status", NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("NetControl firewall on")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .build();
    }

    @Override
    public void onRevoke() {
        // Another VPN took over, or the user turned us off in Settings.
        prefs.edit().putBoolean(KEY_ENABLED, false).apply();
        stopFirewall();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopFirewall();
        super.onDestroy();
    }
}

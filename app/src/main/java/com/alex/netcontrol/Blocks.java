package com.alex.netcontrol;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.service.quicksettings.TileService;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The saved block list, and whether what NetControl last read back is still
 * what the kernel holds.
 *
 * A reboot clears the firewall map, so the tile has to notice one without a
 * boot receiver or anything resident. It derives it: every successful check
 * stores when the device booted, taken as the wall clock minus the uptime.
 * That figure holds still while the device is up and moves when it reboots, so
 * a stored copy that no longer matches means the map has been wiped. The
 * uptime is kept too, because it cannot run backwards without a reboot, and
 * the clock can be corrected mid session by NTP.
 */
final class Blocks {

    static final String PREFS = "netcontrol";
    static final String KEY_BLOCK = "block:";

    private static final String KEY_BOOT = "checked_boot";
    private static final String KEY_UPTIME = "checked_uptime";
    private static final String KEY_ALL_OK = "checked_all_ok";

    /** Wider than any clock correction, narrower than any reboot. */
    private static final long BOOT_TOLERANCE_MS = 60_000L;

    static final int T_NONE = 0;       // nothing saved
    static final int T_UNAPPLIED = 1;  // saved, never applied
    static final int T_REBOOTED = 2;   // last applied during an earlier boot
    static final int T_UNSURE = 3;     // checked this boot, something was off
    static final int T_OK = 4;         // checked this boot, all enforced

    private Blocks() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> blocked(SharedPreferences prefs) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_BLOCK) && Boolean.TRUE.equals(e.getValue())) {
                out.add(e.getKey().substring(KEY_BLOCK.length()));
            }
        }
        return out;
    }

    /** Only after a real kernel read, never after merely saving a switch. */
    static void recordCheck(SharedPreferences prefs, boolean allEnforced) {
        prefs.edit()
                .putLong(KEY_BOOT, bootMillis())
                .putLong(KEY_UPTIME, SystemClock.elapsedRealtime())
                .putBoolean(KEY_ALL_OK, allEnforced)
                .apply();
    }

    static int tileState(SharedPreferences prefs) {
        if (blocked(prefs).isEmpty()) return T_NONE;
        if (prefs.getLong(KEY_BOOT, 0L) == 0L) return T_UNAPPLIED;
        if (!sameBoot(prefs)) return T_REBOOTED;
        return prefs.getBoolean(KEY_ALL_OK, false) ? T_OK : T_UNSURE;
    }

    private static long bootMillis() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    private static boolean sameBoot(SharedPreferences prefs) {
        if (SystemClock.elapsedRealtime() < prefs.getLong(KEY_UPTIME, 0L)) return false;
        return Math.abs(bootMillis() - prefs.getLong(KEY_BOOT, 0L)) <= BOOT_TOLERANCE_MS;
    }

    /** No-op when the tile is not on the panel. */
    static void refreshTile(Context c) {
        try {
            TileService.requestListeningState(
                    c, new ComponentName(c, BlockTileService.class));
        } catch (Throwable ignored) { }
    }
}

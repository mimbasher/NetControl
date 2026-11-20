package com.alex.netcontrol;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * A Quick Settings tile that says whether the saved blocks are still in the
 * kernel, so a reboot that wiped them is noticed without opening the app.
 *
 * A tile is a service, so to be plain about it: the system binds this only
 * while the panel is open and unbinds it afterwards. It reads
 * SharedPreferences and the clock, and never touches Shizuku or a network.
 *
 * It cannot re-apply anything, because writing rules needs Shizuku and asking
 * for a Shizuku permission needs an Activity. A tap opens the app, which
 * re-applies saved blocks on launch anyway.
 */
public class BlockTileService extends TileService {

    @Override
    public void onTileAdded() {
        refresh();
    }

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (isLocked()) unlockAndRun(() -> launch(open));
        else launch(open);
    }

    @SuppressWarnings("deprecation")
    private void launch(Intent open) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(open);
        }
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;

        SharedPreferences prefs = Blocks.prefs(this);
        int state = Blocks.tileState(prefs);
        int saved = Blocks.blocked(prefs).size();

        String sub;
        switch (state) {
            case Blocks.T_NONE:      sub = getString(R.string.tile_none); break;
            case Blocks.T_UNAPPLIED: sub = getString(R.string.tile_unapplied); break;
            case Blocks.T_REBOOTED:  sub = getString(R.string.tile_rebooted); break;
            case Blocks.T_UNSURE:    sub = getString(R.string.tile_unsure); break;
            default: sub = getResources().getQuantityString(R.plurals.tile_ok, saved, saved);
        }

        // Only a block read back from the kernel earns the lit state.
        boolean live = state == Blocks.T_OK;

        tile.setLabel(getString(R.string.app_name));
        tile.setState(live ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setIcon(Icon.createWithResource(this,
                live || state == Blocks.T_NONE
                        ? R.drawable.ic_tile_shield
                        : R.drawable.ic_tile_alert));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(sub);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.setStateDescription(sub);
        }
        tile.updateTile();
    }
}

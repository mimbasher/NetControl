package com.alex.netcontrol;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * FOSS flavour updater: there isn't one.
 *
 * This build declares no INTERNET permission, so it cannot check for or
 * download anything. It is the build shipped through F-Droid and IzzyOnDroid,
 * where the repository client already handles updates, and the one to use if
 * you want to be able to verify from the manifest that the app cannot reach a
 * network.
 *
 * "Check for updates" therefore just hands the Releases page to a browser.
 * Firing an ACTION_VIEW intent needs no permission, because the browser does
 * the fetching, not us.
 */
final class Updater {

    static final String RELEASES_URL =
            "https://github.com/mimbasher/NetControl/releases/latest";

    private Updater() { }

    static void check(Activity a) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, R.string.update_no_browser, Toast.LENGTH_LONG).show();
        }
    }
}

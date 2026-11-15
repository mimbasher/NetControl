# Contributing to NetControl

Thanks for taking an interest in NetControl. This is a small, deliberately
simple app, and that is a feature worth protecting. Bug reports, device reports
and focused pull requests are all welcome.

- [How to contribute](#how-to-contribute)
- [Device reports are the most useful thing you can send](#device-reports-are-the-most-useful-thing-you-can-send)
- [Project structure](#project-structure)
- [Build and run](#build-and-run)
- [Testing](#testing)
- [Code style](#code-style)
- [Submitting changes](#submitting-changes)
- [Issue reporting](#issue-reporting)
- [Design rules](#design-rules)
- [Documentation guidelines](#documentation-guidelines)
- [License](#license)

## How to contribute

1. Report a bug, a device result or a feature idea through the
   [issue tracker](../../issues).
2. Fork the repository, create a branch, make your change, and open a pull
   request against `main`.

If you are planning anything larger than a bug fix, open an issue first so we
can agree on the approach before you spend time on it.

## Device reports are the most useful thing you can send

NetControl leans on `cmd connectivity` subcommands that behave differently
across Android versions and OEM skins. An emulator cannot tell us whether your
manufacturer also writes to `FIREWALL_CHAIN_OEM_DENY_3` and clobbers these
rules. You can.

A good device report includes:

- Phone model, Android version and OEM skin (One UI, HyperOS, Pixel stock, and
  so on)
- What the line under the app name said after you flipped a switch
- Whether a blocked app actually lost network, on WiFi and on mobile data
  separately
- Whether blocks survived force stopping Shizuku
- Whether anything turned red after you changed a system network setting

Open these as issues with the `device-report` label. Even a plain "works as
documented on <model>, Android <version>" is worth filing.

## Project structure

One Activity, two layouts, no architecture framework. That is on purpose.

```
app/
├── build.gradle                        # AGP config, flavours, signing, deps
└── src/
    ├── main/                           # everything both flavours share
    │   ├── AndroidManifest.xml
    │   ├── java/com/alex/netcontrol/
    │   │   └── MainActivity.java       # UI, Shizuku calls, state
    │   └── res/
    │       ├── layout/
    │       │   ├── activity_main.xml   # portrait: everything stacked
    │       │   └── item_app.xml        # one app row
    │       ├── layout-land/
    │       │   └── activity_main.xml   # landscape: controls beside the list
    │       ├── values/                 # colors, dimens, strings, themes
    │       ├── values-night/           # dark theme
    │       ├── values-land/            # tighter header in landscape
    │       ├── values-w600dp/          # gutters: unfolded foldable
    │       ├── values-w720dp/          # gutters: tablet portrait
    │       ├── values-w840dp/          # gutters: tablet landscape
    │       └── drawable/ · mipmap-anydpi-v26/ · menu/
    ├── foss/                           # F-Droid build. No network, ever.
    │   └── java/.../Updater.java       # opens the Releases page in a browser
    └── full/                           # the only code that touches a network
        ├── AndroidManifest.xml         # INTERNET + REQUEST_INSTALL_PACKAGES
        ├── java/.../Updater.java       # checks GitHub, downloads, installs
        └── res/xml/update_paths.xml    # FileProvider path for the download
.github/workflows/build.yml             # builds and publishes both flavours
fastlane/metadata/android/en-US/        # F-Droid and IzzyOnDroid listing
docs/                                   # screenshots used by the README
```

`MainActivity.java` is deliberately one file. If you are tempted to split it
into a repository, a view model and three interfaces, please open an issue
first and make the case.

## Build and run

### Prerequisites

- **JDK 17.** This is what CI uses. JDK 21 also works. Newer JDKs fail: AGP
  8.5.2 runs `jlink` against `core-for-system-modules.jar` and that transform
  breaks on JDK 27 with `Failed to transform core-for-system-modules.jar`. If
  your system default is newer, point `JAVA_HOME` at 17 or 21 for this project:

  ```bash
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug
  ```
- **Android SDK with platform 34** and build tools. Put the path in
  `local.properties` as `sdk.dir=/path/to/Android/Sdk`, or set `ANDROID_HOME`.
- No Gradle install needed. Use the wrapper.

### Clone and build

```bash
git clone https://github.com/mimbasher/NetControl.git && cd NetControl
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Local builds fall back to the debug signing key. Release signing needs a
keystore and the `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` environment
variables, which only CI has.

### Running against Shizuku

To exercise the real code path you need Shizuku running at shell UID. On an
emulator:

```bash
adb install -r Shizuku.apk
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

Use an **Android 15 or 16** image. On Android 14 and older the
`cmd connectivity` subcommands do not exist and every toggle fails, which is
correct behaviour and not a bug to fix.

## Testing

There is no automated test suite yet. If you add one, keep it runnable in CI
without a device. Until then, changes are verified by hand, and a PR that
touches behaviour should say what was checked and on what.

### Checks worth running before a PR

```bash
./gradlew assembleDebug          # must compile clean
./gradlew lint                   # abortOnError is off, so read the report
```

### Screen sizes

Layout changes must be checked at every gutter breakpoint, not just on your own
phone. The quick way, on one emulator:

```bash
adb shell wm size 1080x2400 && adb shell wm density 420   # phone portrait
adb shell wm size 1768x2208 && adb shell wm density 420   # foldable unfolded
adb shell wm size 1600x2560 && adb shell wm density 320   # tablet portrait
adb shell wm size 2560x1600 && adb shell wm density 320   # tablet landscape
adb shell wm size reset && adb shell wm density reset
```

Check both themes:

```bash
adb shell cmd uimode night yes
adb shell cmd uimode night no
```

Rotating, folding and unfolding all recreate the Activity. After any of those,
verified kernel state must still be on screen. If the green lines vanish until
you re-check, that is a regression. Checked by rotating with a search query and
a filter active: both must survive, and the counts must stay consistent.

#### Baseline

Measured on an Android 16 emulator, gutters read back from live view bounds.
Treat these as the numbers to beat. A layout change that drops rows visible at
any size needs a reason.

| Screen | Size | Gutter | Rows visible |
|---|---|---|---|
| Small phone | 360x640dp | 0dp | 4.1 |
| Phone portrait | 411x914dp | 0dp | 8.2 |
| Phone landscape | 914x411dp | 32dp | 4.7 |
| Foldable, folded | 411x796dp | 0dp | 6.4 |
| Foldable, unfolded | 673x841dp | 56dp | 7.3 |
| Foldable, unfolded landscape | 841x673dp | 32dp | 8.6 |
| 7in tablet portrait | 600x960dp | 56dp | 8.9 |
| 10in tablet portrait | 800x1280dp | 104dp | 13.7 |
| 10in tablet landscape | 1280x800dp | 32dp | 10.6 |

Every gutter lands on its intended breakpoint. Landscape uses a separate
`layout-land/activity_main.xml` that puts the controls in a column beside the
list rather than stacked above it, because a phone in landscape is only ~411dp
tall and the stacked header took 73% of that. It carries the same ids as the
portrait layout, so `MainActivity` binds the same views either way. **If you add
a view to one layout, add it to both.**

Landscape also uses its own `screen_gutter_land` rather than `screen_gutter`.
The two pane layout already spends the width on the list, so it does not want
the large portrait gutters, and a width qualifier such as `values-w840dp` would
otherwise win over `values-land`.

## Code style

- **Java, 4 spaces, no tabs.** Match the file you are editing.
- **No new dependencies** without a reason in the PR description. The current
  list is AppCompat, RecyclerView, Material and the Shizuku API. That is the
  whole budget.
- **Keep `minifyEnabled` off.** `MainActivity` reaches `Shizuku.newProcess` by
  reflection and R8 renames it.
- **The `foss` flavour never gets `INTERNET`.** A tool whose job is blocking
  network access must have a build you can prove cannot reach a network. CI
  fails if that flavour ever declares it. The `full` flavour carries `INTERNET`
  solely for the updater, which runs only on an explicit tap. Anything that
  would make the app talk to a network on its own, on launch or in the
  background, will be closed.
- **Strings go in `strings.xml`**, not inline in code or layouts.
- **No em dashes** in code, comments, docs or user facing strings. Recast the
  sentence.
- Dimensions that change with screen width belong in `dimens.xml` and its
  qualified variants, not hard coded in a layout.

## Submitting changes

### Branching

Create a branch for your work:

| Prefix | For | Example |
|---|---|---|
| `feat/` | new features or changes | `feat/per-app-profiles` |
| `fix/` | bugs and fixes | `fix/stale-state-after-fold` |
| `docs/` | documentation only | `docs/device-report-template` |

### Commit messages

Imperative mood, present tense, no trailing period. "Fix kernel state parsing",
not "Fixed kernel state parsing" or "fixes parsing".

### Pull requests

Include a short description of what changed and why, the issue number if there
is one, and what you tested on. Example:

> Cache verified kernel state across configuration changes.
>
> Folding or rotating recreated the Activity and dropped every verified state,
> so the per app lines went blank until the user hit Verify blocks again.
>
> **Changes made:**
> - Hold verified state in a process level cache keyed by package
> - Restore from it in `onCreate` instead of showing nothing
>
> **Tested on:** Pixel 7 emulator API 36, and a 7.6in foldable profile folded,
> unfolded and rotated. Closes #12.

Documentation only changes are skipped by CI on purpose, so do not worry when
no build runs on them.

## Issue reporting

**Bug reports** should include:

- Phone model, Android version and OEM skin
- Shizuku version, and whether it was running at the time
- Exact text of the line under the app name
- Steps to reproduce
- `adb logcat -s NetControl` output if you can get it

**Feature requests** should say what you want and why the current behaviour is
not enough. Features that would require a VPN slot, root, a background service
or the `INTERNET` permission are out of scope by design. That is the entire
point of the app.

## Design rules

These are the constraints the app is built around. Changes that break them will
not be merged.

1. **No VPN slot.** Other no root firewalls declare themselves a VPN. NetControl
   does not, and will not.
2. **Nothing in the background.** No foreground service, no job scheduler, no
   boot receiver. Blocks live in the kernel and need no process to hold them.
3. **No network access without a tap.** The foss build has none at all. The
   full build may only talk to `api.github.com`, only when the user taps Check
   for updates, and never on launch or in the background.
4. **Tell the truth about state.** Every write is read straight back and the
   real kernel state is shown, including failures. Never report success you have
   not verified. The header counts anything not enforced so a silent failure
   cannot hide.
5. **Shizuku is a delivery mechanism, not a dependency at rest.** The app must
   keep working with Shizuku force stopped.

## Documentation guidelines

- The `README.md` is the user facing document: what it does, how to set it up,
  what is verified. Keep it accurate about what has actually been tested.
- If your change alters user visible behaviour, update the README in the same
  PR.
- New screenshots go in `docs/` at a width that matches the existing three.
- Update the [Verified behaviour](README.md#verified-behaviour) table when you
  confirm or contradict a row on real hardware.

## License

By contributing, you agree that your contributions are licensed under the
[MIT License](LICENSE).

## Thanks

To [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku), without which none
of this would be possible on an unrooted phone, and to everyone who files a
device report. Contributors are listed [here](../../graphs/contributors).

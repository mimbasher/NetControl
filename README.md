# NetControl

Cut any app off **Wi-Fi and mobile data** on an unrooted phone. No VPN, no root,
and nothing running in the background.

Android has no built-in way to do this. Samsung's *Allowed networks for apps*
lets you disable Wi-Fi **or** mobile data for an app, never both. Every other
no-root firewall (NetGuard, Blokada, TrackerControl, RethinkDNS) works by
declaring itself a VPN, which occupies your one and only VPN slot and costs
battery. NetControl does neither.

## How it works

Shizuku lends the app shell (ADB) privileges just long enough to run Android's
own firewall commands:

```
cmd connectivity set-chain3-enabled true
cmd connectivity set-package-networking-enabled false <package>
```

That writes the app's UID into a kernel BPF firewall map owned by `netd`, the
same machinery the OS uses for Data Saver. The block is enforced below the app
layer, so it covers Wi-Fi and cellular identically and costs no battery.

**The rule lives in the kernel, not in NetControl and not in Shizuku.** Shizuku
is only the delivery mechanism; once a command has run, nothing links the rule
back to it. That has one very useful consequence:

> ### Shizuku does not need to keep running
> You only need it at the moment you change a block. Apply your blocks, then
> force-stop Shizuku and turn Wireless debugging back off. The blocks stay
> active with nothing running in the background.

A reboot clears the kernel map. That is the one unavoidable cost of not using a
VPN or root.

## One-time setup

1. Install **Shizuku** (Play Store, or [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)).
2. Settings → About phone → Software information → tap **Build number** 7 times.
3. Settings → Developer options → turn on **Wireless debugging**.
4. Open Shizuku → **Start via Wireless debugging** → follow the pairing steps.
5. Open NetControl → tap **Allow** on the permission card.

## Daily use

Flip the switch next to any app. The line under its name reports the **real
kernel state**, read straight back after every write:

| | |
|---|---|
| *Blocked, enforced by the kernel* | the rule is live |
| *Command ran but the rule did not stick* | the write was rejected; the app is **not** blocked |
| *Could not read state: …* | the command failed, and the raw error is shown |

The header counts anything that is **NOT enforced**, so a silent failure can't
hide. **⋮ → Verify blocks** re-reads every saved block without changing
anything.

Once you're done, stop Shizuku. Your blocks remain.

## After a reboot

Start Shizuku (step 4, no re-pairing needed) and open NetControl. Saved blocks
are **re-applied automatically** on launch. Then stop Shizuku again.

**⋮ → Re-apply blocks** does the same thing manually if you need it.

## Confirming it works on your device

Two things are device-specific and worth checking once:

- **Shizuku grants shell UID (2000), not root.** If your ROM gates these
  `cmd connectivity` commands behind root, every toggle fails. The row will say
  so and show the error.
- **OEM_DENY_3 is a vendor chain.** If your OEM uses it too, it could overwrite
  NetControl's rules when it recomputes network policy.

To check both:

1. Block a browser. Confirm the row turns green and the browser has no internet.
2. Force-stop Shizuku. Reload a page. Still dead means persistence works.
3. Toggle Wi-Fi off and on, then **Verify blocks**. Still enforced means your
   OEM isn't clobbering the chain.

## Build

CI builds a debug APK on every push and attaches it to a
[Release](../../releases). Locally you need **JDK 17** (not newer, since AGP 8.5.2
won't run on it) and an Android SDK with platform 34:

```
./gradlew assembleDebug
```

> Installing over an older NetControl fails with a signature mismatch, because the CI
> debug key differs from earlier builds. Uninstall first.

## Limitations

- Blocks are cleared by a reboot (re-applied on next launch).
- Requires Android 13+; `cmd connectivity` lacks these subcommands on older releases.
- Reinstalling a blocked app changes its UID, so re-apply after that.

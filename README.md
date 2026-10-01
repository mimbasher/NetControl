# NetControl — per-app Wi-Fi / mobile data firewall (no root)

Block any app from the internet separately on Wi-Fi and on mobile data.
Works the same way as NetGuard: a local VPN that only captures the apps you
block on the current network and drops their traffic. Other apps are untouched.

## Get the APK (no PC needed)
1. Create a new GitHub repository and upload everything in this folder
   (keep the `.github` folder — it holds the build script).
2. Open the repo's **Actions** tab → **Build APK** → wait ~3 min.
3. Open the finished run → download **NetControl-apk** → unzip → `app-debug.apk`.
4. Install it on the phone (allow "install unknown apps" for your browser/files app).

Build locally instead: install Android Studio, open this folder, Build → Build APK.

## Use
- Open NetControl, tick **Wi-Fi** and/or **Data** next to apps to block.
- Tap the top button to turn the firewall on and accept the VPN prompt.
- Changes apply instantly; switching between Wi-Fi and data swaps the rules.

## Keep it on after reboot
Settings → Network & internet → VPN → NetControl (gear) →
turn on **Always-on VPN**. Leave "Block connections without VPN" OFF,
or every other app loses internet.

## Limits
- Android allows one VPN at a time, so it can't run alongside another VPN app.
- Debug builds from GitHub get a new signing key each build: uninstall the old
  version before installing a new build (your ticks reset).

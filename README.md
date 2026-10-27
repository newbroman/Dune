# Dune — a minimal text launcher for Android

Black, text-only home screen with: clock and date, weather (Open-Meteo, no API key),
notifications grouped by app, a task list, favourite apps, Termux shortcuts, and an L14 panel
(online status, SSH, Termius, Wake-on-LAN, one-tap remote commands).

## Gestures
- **Swipe up** (or tap *apps*) – app list with search. Enter launches the top match; no match offers a web search.
- **Swipe down** on home – pulls down the notification shade. Swipe down at the top of the app list – back home.
- **Long-press** an app – favourite / hide / app info / uninstall. Long-press a favourite – reorder or remove.
- Tap a task to tick it, long-press to delete. Tap a notification to open it, long-press to dismiss that app's.
- Tap the clock for alarms, the date for the calendar, the weather to refresh, the L14 status to recheck.

## Phone setup
1. Install the APK (from Releases, or over USB with `adb install -r Dune.apk`) (allow "install unknown apps" for your file manager/browser when asked).
2. Open Dune › tap "Make Dune your home app" › pick Dune.
3. Settings in Dune: allow notification access, location (or type a place), Termux permission.
   Android 13+ greys out notification access for sideloaded apps: Settings › Apps › Dune › ⋮ ›
   *Allow restricted settings*, then enable it.

### Termux (once, in Termux)
```
pkg install -y openssh
mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties
termux-reload-settings
ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519
ssh-copy-id john@L14_IP_ADDRESS
```

### L14 (once, on the laptop)
```
sudo apt install -y openssh-server ethtool
hostname -I                      # the IP to put in Dune settings
ip link | grep -A1 -E "^[0-9]+: (en|eth)"   # MAC (link/ether) of the wired port, for Wake
```
Wake-on-LAN only works over a wired connection with WoL enabled in the BIOS.

## Building
`./gradlew assembleRelease` (JDK 17+, Android SDK 35). Every push to `main` builds the APK on
GitHub (Actions › latest run › *Dune-apk*); pushing a tag such as `v1.4` also publishes it as a
GitHub Release.

The release signing key is not in this repo. Locally it is read from `keystore.properties` and
`app/dune-release.jks` (both git-ignored); on GitHub from the secrets `DUNE_KEYSTORE_B64` and
`DUNE_KEYSTORE_PASSWORD`. Builds signed with any other key will not install over an existing Dune.

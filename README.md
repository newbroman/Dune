# Dune — a minimal text launcher for Android

> **Dune 2** (in `dune2/`, installs alongside as *Dune 2*) rebuilds Dune from **hubs**: one page per area of life, each with
> Dune's own live view, a one-line summary on Home, and a hand-off row to the apps that do the job in full (*↗ last app* and
> *apps ▾*). You choose your hubs and their order in Settings › Hubs. 2.0 has **Home**, **Comms** (people-first: everyone who
> has messaged or emailed you this week, across apps, with reply / call / text / WhatsApp), **Tasks** (tasks and notes,
> by voice too), and Dune's Map, Weather, Groups, Widgets and Rozmowa pages as hubs. **Record** (2.2: Dune's own camera – photo
> and geotagged video into Pictures/Dune and Movies/Dune – an audio recorder that keeps going with the screen off, and a gallery of
> photos, videos, recordings and GPS tracks). The Map hub records **tracks** (● track; GPX to share). **Music** (2.3: what's playing in any app with cover art, progress
> and controls; play anything by name in Spotify or YouTube Music, typed or spoken; BBC radio; recently played). Coming: Connect, Agenda, Jobs,
> Wallet, Learn, Machines. Releases carry both `Dune.apk` and `Dune2.apk`.

Black, text-only home screen with: clock and date, weather (Open-Meteo, no API key),
notifications grouped by app, a task list, favourite apps, Termux shortcuts, and an L14 panel
(online status, warnings about its backups, services, disk and NRG jobs, SSH, Termius, Wake-on-LAN,
one-tap remote commands).

## Gestures
- **Swipe right** – your app groups, as a list of categories (tap one to see its apps); **twice** – Rozmowa, the Polish conversation app, with mic and voice (*full screen* opens it on its own, like an app; so does tapping it in My apps or Languages).
  **Swipe left** – your widgets; **twice** – the Inbox; **three times** – a map with you on it: Mapy.com
  (basic, outdoor, aerial, winter, traffic) or OpenStreetMap, contours, cycling and satellite maps from the menu, and *☀ lines* for the
  solstice, equinox, cross-quarter and lunar-standstill sunrise and sunset bearings from where you stand, as on the Spheres weather page;
  **four times** – your Weawow widgets, as large as you like.
  Home or Back returns to the middle.
- **Swipe up** (or tap *apps*) – the command bar: type to find apps, people (call / text / WhatsApp), tasks, messages and your web apps, run a command ("add task…", "navigate to…"), or ask the AI. Enter launches the top app; no match offers a web search.
- **Swipe down** on home – pulls down the notification shade. Swipe down at the top of the app list – back home.
- **Favourites** sit on the home screen as a grid of icons; long-press one to move it left or right or remove it. Web pages can be favourites too: long-press one in My apps › Add to favourites.
- **Long-press** an app – favourite / hide / move or copy to another group / app info / uninstall. Long-press a favourite – reorder or remove.
- Tap a task to tick it, long-press to delete. Tap a notification to open it, long-press to dismiss that app's.
- Tap the clock for alarms, the date for the calendar, the weather to refresh, the L14 status to recheck.
  Tap the L14's warnings to see every check, tap again to go back to just the problems.

## Home screen extras
- **Polish clock** – the date in Polish and the time in words (*osiemnasta czterdzieści jeden*). Tap to hear it.
- **Phrase of the day** – from Polish Phrase Master; moves on every third time you wake the phone. Tap to hear it, *another* for a different one, long-press to open the app.
- **Claude** – its icon sits beside the clock for one-tap access.
- **Ask Dune** – type or tap 🎤: "navigate to…", "set an alarm for 7:30", "5 minute timer", "add task…",
  "how do you say … in Polish", "play … on Spotify", "open …". Anything else offers Claude, Gemini, Google or Translate.
- **Coinbase** – your holdings and total in pounds, from a *view-only* Coinbase API key (ECDSA) stored encrypted
  on the phone. Tap to open Coinbase, long-press to refresh, *hide* to mask the figures.
- **Widgets** – "+ add a widget" puts any app's widget (Google Translate, Weawow, Maps…) on the home screen.

## Workspace
- **Inbox** (two swipes left) – messages, email and other notifications in one stream, newest first. Filter by
  Messages / Email / Other, reply in place (typed or 🎤) where the app allows it, ✕ to dismiss, and *✦ Catch me up*
  for an AI summary. Messages and emails are also kept in a 7-day log on the phone so they can still be found after
  you clear them (Settings › Dune AI › Forget the saved message history).
- **Dune AI** – anything Ask Dune can't do itself goes to an AI using your own OpenAI or OpenRouter key (stored
  encrypted): "when is Tom coming?", "what's on this week?". It can read your messages, tasks and calendar – each
  can be switched off – and nothing is sent until you ask.
- **Workspaces** – saved pairs of apps opened side by side in split screen, e.g. *Maps | Spotify*
  (Settings › Workspaces).

## Drive mode
Opens when the phone connects to your car's Bluetooth (Settings › Drive mode › choose your car), or tap *drive*.
Big buttons for Home, Navigate…, Spotify, BBC Sounds, Spheres and Messages; play/pause/skip for whatever is playing;
new messages are read aloud; *Reply* takes your answer by voice, reads it back and sends it after a few seconds unless
you cancel. The 🎤 Speak button takes any command above plus "read my messages", "reply …", "pause", "skip".

**Hands-free:** turn on *Wake word “OK Computer”* (Settings › Drive mode) and say "OK Computer" instead of tapping
Speak. It listens with [Vosk](https://alphacephei.com/vosk/), entirely offline, and only while Drive mode is on (a
notification shows when it is listening). A car or headset voice button can also open Speak: pick Dune if asked.

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

### L14 warnings (once, on the laptop)
The L14 panel lists what needs attention on the L14: backups (nightly USB, weekly Drive, the Drive copy,
USB drive plugged in), services (Drive mount, hydrobot, NRG ship watcher, failed units, Claude Remote
Control), the machine (disk space, on battery, updates, restart needed) and NRG jobs (last ship, report
PDF older than its sources, Water Watch overdue, dashboard publish), plus the slow findings of the
daily `l14-health` check, and the open tasks of NRG's task register (`task_lint.py`, re-run on the
L14 every 6 hours; tap the *NRG tasks* line to fold the list). It comes from `l14/l14-status.py`, a small read-only service on the L14:
```
curl -fsSL https://raw.githubusercontent.com/newbroman/Dune/main/l14/install.sh | bash
```
It prints the address, port and token to put in Dune › Settings › L14. To see the phone's warnings
away from home, put [Tailscale](https://tailscale.com) on the L14 and the phone and use the L14's
Tailscale address (100.x.y.z) as its address in Dune; no router ports are opened. The service answers
only localhost, home-network and Tailscale addresses, and only with the right token. Run
`l14-status.py` on the L14 to see the same checks in the terminal.

## Building
`./gradlew assembleRelease` (JDK 17+, Android SDK 35). Every push to `main` builds the APK on
GitHub (Actions › latest run › *Dune-apk*); pushing a tag such as `v1.4` also publishes it as a
GitHub Release.

The release signing key is not in this repo. Locally it is read from `keystore.properties` and
`app/dune-release.jks` (both git-ignored); on GitHub from the secrets `DUNE_KEYSTORE_B64` and
`DUNE_KEYSTORE_PASSWORD`. Builds signed with any other key will not install over an existing Dune.

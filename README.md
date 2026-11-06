# RFHN TV Launcher

RFHN TV Launcher is a fast, minimal home screen for **Android TV and Google TV** (Android 8.0 or newer), with prayer times, a live system card, Now Playing, a running-apps screen, a phone remote and a live globe screen saver. It was built on a Xiaomi Mi TV, which was also [debloated](docs/xiaomi-debloat.md).

**Explore the launcher:** [Landing page](https://nullifye.github.io/RFHN-TV-Launcher/) · [Bahasa Melayu](https://nullifye.github.io/RFHN-TV-Launcher/ms.html) · [All features](docs/features.md)

**Install it on your TV:** [Windows](docs/install-windows.md) · [macOS](docs/install-macos.md) · [Linux](docs/install-linux.md) · [with an AI agent](docs/install-with-ai-agent.md)

<table>
  <tr>
    <th width="50%">Before: stock Android TV home</th>
    <th width="50%">After: this launcher</th>
  </tr>
  <tr>
    <td width="50%"><img src="docs/launcher-old.jpg" width="100%" alt="Stock Android TV home screen with Favorite Apps, Play Next and AirConsole rows"></td>
    <td width="50%"><img src="docs/home.jpg" width="100%" alt="RFHN TV Launcher with Forest light wallpaper, clock, prayer times, weather, system status and Settings gear focused"></td>
  </tr>
  <tr>
    <td valign="top">Rows of recommendations and sponsored content, with your apps squeezed into one line.</td>
    <td valign="top">Your apps, the time and what matters at a glance: prayer times, most used apps, a live system card, weather and air quality, and Now Playing, over a calm wallpaper.</td>
  </tr>
</table>

## Demo

https://github.com/user-attachments/assets/8c54b279-a93a-4732-a06b-bfd6f9cd3b32

Recorded on the TV (2 minutes, no sound): the dock's name and glow following focus, Now Playing, the running-apps screen and its hold-OK menu, Settings with wallpapers previewed as you move, the prayer zone list, and moving an app in the dock.

https://github.com/user-attachments/assets/fefd8d49-178f-4e7a-b011-14a44a562c13

The Globe screen saver (21 seconds, no sound): Settings → Screen saver → **Start now**, then the Earth turning as it is right now, with the Maghrib line passing Malaysia, Makkah and the qibla, the sun's glint on the sea, city lights, the day's earthquakes, map edits as people save them, and the ISS and Tiangong overhead.

## Features

<table>
  <tr>
    <td width="50%"><img src="docs/home.jpg" width="100%" alt="Home screen with Settings gear focused, Forest light wallpaper, weather, prayer times and system status"></td>
    <td width="50%"><img src="docs/app-grid.jpg" width="100%" alt="All apps grid"></td>
  </tr>
  <tr>
    <td valign="top"><b>Home</b>: big clock with the date and Hijri date, today's prayer times with a countdown, your most used apps this week, today's weather and air quality (MET Malaysia, APIMS and Copernicus) with rain, lightning and haze on the glass as the weather is now, a system card (CPU, RAM, storage, network, USB debugging), Now Playing with cover art and controls, and an app dock you arrange yourself.</td>
    <td valign="top"><b>All apps</b> (Down from the dock): 5 per row (6 or 7 in Settings, the dock follows), most used first (all time), with a glass focus highlight that springs from tile to tile. Hold OK on any app to add it to the dock.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/running-apps.jpg" width="100%" alt="Running apps screen"></td>
    <td width="50%"><img src="docs/settings.jpg" width="100%" alt="Settings"></td>
  </tr>
  <tr>
    <td valign="top"><b>Running apps</b> (Up from the top bar): what's running, how much memory each app uses and what it's doing. OK force-closes; hold OK to hide an app from the list.</td>
    <td valign="top"><b>Settings</b> (the gear): prayer zone (found from the TV's location, or any of the 60 JAKIM zones), azan and prayer reminders, which cards show, 12/24-hour clock, wallpaper, the Most used week, apps per row, the screen saver, what the bell tells you, the phone remote, and more. Changes apply straight away.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/dock-reorder.jpg" width="100%" alt="Hold OK on a dock app: Move, Remove from dock, Open"></td>
    <td width="50%"><img src="docs/running-apps-hide.jpg" width="100%" alt="Hold OK on a running app: hide from this list, force close, open"></td>
  </tr>
  <tr>
    <td valign="top"><b>Your dock, your order</b>: hold OK on a dock app for <b>Move</b> (◀ ▶, then OK to place), <b>Remove from dock</b> or <b>Open</b>; hold OK on any app in the grid to add it. The phone remote can edit the dock too.</td>
    <td valign="top"><b>Hide apps you never close</b>: hold OK on a running app and choose <b>Hide from this list</b>. Hidden apps wait under a "Hidden apps" row at the bottom, and Settings → Running apps brings them back.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/quick-actions.jpg" width="100%" alt="Hold OK on the Settings gear: start screen saver, sleep timer, sleep now, restart, shut down"></td>
    <td width="50%"><img src="docs/settings-screensaver.jpg" width="100%" alt="Settings → Screen saver: on/off, Show, Start now"></td>
  </tr>
  <tr>
    <td valign="top"><b>Quick actions</b>: hold OK on the Settings gear (or press Menu on it) to <b>Start screen saver</b>, set a <b>Sleep timer</b> (30 to 120 min), <b>Sleep now</b>, <b>Restart</b> or <b>Shut down</b>. Restart and Shut down ask first, with Cancel focused. Sleep and the timer work with USB debugging off; Restart and Shut down need it, and without it open the TV's own power menu.</td>
    <td valign="top"><b>Settings → Screen saver</b>: switch it on or off, choose Prayer time, Live log, Globe or any other screen saver on the TV, and preview it with Start now. With Globe chosen, each of its layers has a switch.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/net-traffic.jpg" width="100%" alt="Settings → Net traffic: capture on, Clear history, and the latest connections with app, country, server name and data"></td>
    <td width="50%"><img src="docs/net-traffic-detail.jpg" width="100%" alt="One connection's details: app, package, server, address, protocol, request, country, time and data"></td>
  </tr>
  <tr>
    <td valign="top"><b>Net traffic</b> (new in 1.15, optional): see where the TV's apps connect. Start capture in Settings → Net traffic and it lists each connection, newest first: "RadioStream → United States", the server's name (<code>27223.live.streamtheworld.com</code>), how often and how much. The routes also arc across the Globe screen saver, from your zone to each country.</td>
    <td valign="top"><b>Connection details</b>: OK on a connection shows its app and package, server name, address and port, protocol (HTTPS asking for h2, HTTP, QUIC, DNS…), the request where it's visible, country, when, and data. It works as a local VPN on the TV: no outside server, nothing decrypted, the last 30 minutes kept in memory only. The home screen's IP shows a <b>VPN</b> badge while it runs.</td>
  </tr>
  <tr>
    <td colspan="2"><img src="docs/net-traffic-globe.jpg" width="100%" alt="Globe screen saver with cyan dotted routes arcing from Malaysia to Europe, the United States and Australia"></td>
  </tr>
  <tr>
    <td colspan="2" valign="top"><b>Net traffic on the Globe</b>: each connection arcs from your prayer zone to its country, a glowing dot hopping along the newest ones; older routes fade in steps over 30 minutes. The Globe's <b>Internet connections</b> layer (Settings → Screen saver) turns them on or off.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/button-mapper.jpg" width="100%" alt="Settings → Button Mapper: the Red (183) and Green (184) buttons with their actions, Add a button and timing"></td>
    <td width="50%"><img src="docs/button-mapper-actions.jpg" width="100%" alt="Actions for a press: Inputs, Start the screen saver, Sleep, Quick menu, Take a screenshot and more"></td>
  </tr>
  <tr>
    <td valign="top"><b>Button Mapper</b> (new in 1.16): Settings → Button Mapper → <b>Add a button</b>, press it on the remote, and give it a new <b>single</b>, <b>double</b> or <b>long press</b>. Each button shows its Android key code, e.g. Red (183). On this page every button works normally, so a mistake is always easy to undo; the phone remote's Settings tab can reset them all.</td>
    <td valign="top"><b>Actions</b>: open an app, Home, Back, Recent apps, Android / Wi-Fi / Sound / App settings, TV Home settings, the power menu, Inputs, the screen saver, sleep, the quick menu, a screenshot, mute, volume up/down, media keys, the info overlay or Net traffic. All work with USB debugging off. The arrows and OK can't be remapped, and a button whose own press Android can't replay (Menu, colour keys) gets a double or long press once its single press has an action.</td>
  </tr>
  <tr>
    <td colspan="2" align="center"><img src="docs/wallpapers.jpg" width="50%" alt="Wallpaper list"><br><b>Wallpapers</b>: move to preview, OK to set. Add your own from the phone remote: Settings → Wallpaper → Your photo.</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screensaver-prayer.jpg" width="100%" alt="Prayer time screen saver: big clock, date, Hijri date and today's prayer times"></td>
    <td width="50%"><img src="docs/screensaver-log.jpg" width="100%" alt="Live log screen saver: the TV's log scrolling on black with a small clock"></td>
  </tr>
  <tr>
    <td valign="top"><b>Screen saver: Prayer time</b>: a big clock with seconds, the date, the Hijri date and today's prayer times, the next one in amber.</td>
    <td valign="top"><b>Screen saver: Live log</b>: the TV's log as it happens, errors in red and warnings in amber. All the screen savers are on black and keep moving so nothing burns in (<a href="docs/features.md#screen-savers-prayer-time-and-live-log">more</a>).</td>
  </tr>
  <tr>
    <td colspan="2"><img src="docs/screensaver-globe.jpg" width="100%" alt="Globe screen saver with about two-thirds of Earth in daylight over South America, the Atlantic and western Africa, with night across eastern Africa and Arabia"></td>
  </tr>
  <tr>
    <td colspan="2" valign="top"><b>Screen saver: Globe</b>: the Earth as it sits in space right now, lit by the real sun, turning once every 2 minutes. On it: where it's Maghrib and Fajr right now, Makkah and the qibla from your zone, city lights, the sun's glint on the sea, snow and sea ice, the aurora, map edits as people save them, the day's earthquakes, and the ISS, Tiangong and Hubble where they are now. Clouds and moving wind can be switched on too. The remote's arrows turn it (<a href="docs/features.md#globe">more</a>).</td>
  </tr>
  <tr>
    <td colspan="2" valign="top">
      <b>Also:</b>
      <ul>
        <li><b>Azan</b> at each prayer time with a banner over any app, plus a silent reminder a few minutes before (you choose how early).</li>
        <li><b>Wallpapers</b>: Bing's photo of the day (scenery only, no people), seven photos from Wikimedia Commons, previewed as you move through the list, plus your own pictures from a <code>Wallpapers</code> folder.</li>
        <li><b>Notifications bell</b>: new TV Home versions (update from the bell in one click, or check any time in Settings → About), Play Store updates, new HDMI devices, Bluetooth devices and low memory.</li>
        <li><b>Connection status</b>: a banner over any app when Wi-Fi drops or loses internet.</li>
        <li><b>Info overlay</b> over apps (optional): network speed, uptime, a clock with seconds and time since you left the home screen; hidden on the home screen.</li>
        <li><b>Inputs</b> menu for HDMI 1–3, AV and Live TV.</li>
        <li><b>Button Mapper</b>: give any remote button a new single, double or long press (open an app, the screen saver, sleep, the quick menu, mute, media keys and more), with USB debugging off.</li>
        <li>Light: a 0.53 MB app that starts about twice as fast as the stock launcher, uses about a third less memory after navigating, and has a sixth of its janky frames (<a href="#benchmark">benchmark</a>).</li>
      </ul>
    </td>
  </tr>
</table>

<table>
  <tr>
    <td align="center" width="33%"><a href="docs/remote-home.jpg"><img src="docs/remote-home.jpg" alt="Phone remote, Remote tab: swipe pad, Back, Home, playback, volume, screenshot and keyboard"></a></td>
    <td align="center" width="33%"><a href="docs/remote-tv.jpg"><img src="docs/remote-tv.jpg" alt="Phone remote, TV tab: Now Playing, prayer times, system, power, inputs and Send to TV"></a></td>
    <td align="center" width="33%"><a href="docs/remote-apps.jpg"><img src="docs/remote-apps.jpg" alt="Phone remote, Apps tab: dock and all apps, most used first"></a></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/remote-files.jpg"><img src="docs/remote-files.jpg" alt="Phone remote, Files tab: internal storage and Wallpapers folder"></a></td>
    <td align="center"><a href="docs/remote-settings.jpg"><img src="docs/remote-settings.jpg" alt="Phone remote, Settings tab: clock, cards, wallpaper dim and wallpaper choices"></a></td>
    <td></td>
  </tr>
  <tr>
    <td colspan="3" align="center"><b>Phone remote</b> in the phone's browser, no app to install: swipe to move, type into any app, open and manage apps, see what's playing, prayer times and the bell, send a message or a link to the TV, change Settings and the wallpaper, a sleep timer, and send files to the TV. See <a href="docs/features.md#phone-remote">Phone remote</a>.</td>
  </tr>
</table>

## Install

You need an Android TV or Google TV running Android 8.0 or newer, and a computer on the same Wi-Fi. The scripts build the launcher, install it over network ADB and make it the home screen; they work with any TV brand.

- Easiest: the **setup app** ([docs/tui.md](docs/tui.md)). One download for Windows, macOS, Linux or an Android phone (in Termux), nothing else to install. It finds your TV on the Wi-Fi, installs TV Home, cleans up the TV, tweaks animations, HDMI-CEC and surround sound, and can undo everything, explaining each step before it runs. It also shows what the TV is, how it's doing (memory, storage, CPU) and what it can play (video formats, Widevine level, Netflix).
- Step by step: [Windows](docs/install-windows.md) · [macOS](docs/install-macos.md) · [Linux](docs/install-linux.md)
- No build tools? Download the ready-made APK from [Releases](https://github.com/nullifye/RFHN-TV-Launcher/releases/latest) and install it with only adb: [Windows](docs/install-windows.md#install-the-ready-made-apk-instead-no-building) · [macOS](docs/install-macos.md#install-the-ready-made-apk-instead-no-building) · [Linux](docs/install-linux.md#install-the-ready-made-apk-instead-no-building)
- Let an AI coding agent do it: [Install with an AI agent](docs/install-with-ai-agent.md)
- Debloat the TV too, from scratch: give an AI agent [PROMPT.md](PROMPT.md)

## Using it

| On the remote | Does |
|---|---|
| Left / Right on the dock | Move between your dock apps |
| Down from the dock | All apps, most used first |
| Up from the dock | Now Playing controls (when something plays), then the top bar |
| Up from the top bar | Running apps: OK force-closes, hold OK for options |
| Hold OK on an app | Move, add or remove dock apps, or open |
| The gear | Settings: wallpaper, prayer zone, azan, cards, screen saver, notifications, phone remote |
| Hold OK on the gear | Quick actions: start screen saver, sleep timer, sleep now, restart, shut down |
| Settings → Net traffic | Start or stop the connection monitor; OK on a connection for its details |
| Back / Home | Back to the dock |

**Phone remote:** open `http://<TV IP>:8486` in your phone's browser on the same Wi-Fi and pair with the PIN shown on the TV. Settings → Phone remote shows the exact address. Five tabs:

| Tab | What's on it |
|---|---|
| Remote | Swipe for the D-pad, double-tap for OK, Back, Home, Play/Pause, volume; with USB debugging on, a keyboard for any app and a screenshot of the TV |
| TV | Now Playing, prayer times, system info, the bell's notifications, inputs, power and a sleep timer, and **Send to TV**: a message (a box on the TV until OK) or a link (YouTube and Netflix open in their apps) |
| Apps | Running apps (close or hide), the dock, all apps; hold an app for dock, force close or disable |
| Files | The TV's storage and USB drives: upload, download, open on the TV |
| Settings | The TV's Settings from the phone, including the wallpaper (or upload a photo and set it) and a searchable prayer zone list |

More: [how every feature works](docs/features.md).

## Benchmark

Against the stock Android TV launcher on the Mi TV (2026-11-04, TV Home 1.16.0). Method and notes: [development.md](docs/development.md#benchmark).

| | TV Home 1.16.0 | Stock Android TV launcher |
|---|---|---|
| Memory, idle / after navigating (PSS) | 87 / **115 MB** | 90 / 178 MB |
| CPU, idle, 60 s | 0.18–0.27 s settled (0.71 s while the weather effects play) | 0.08 s |
| Cold start (process killed → first frame) | **0.76–0.81 s** | 1.47–2.34 s |
| Back to home from an app (wait time) | 18–20 ms | 15–17 ms |
| Janky frames while navigating | **4.8 % (59/1240)** | 29.4 % (109/371) |
| Frame time p50 / p90 / p99 | 9 / **12** / **38** ms | 7 / 28 / 150 ms |
| APK size | **0.53 MB** | 12.7 MB |

## Security

This setup is meant for a TV on your own network, and it deliberately makes the TV quite open. Worth knowing before you run it:

- **USB debugging stays on** (port 5555): anyone on your Wi-Fi can connect with `adb` and control the TV. The launcher uses the same loopback connection for the remote's D-pad and keyboard inside apps, screenshots, sleep/restart and disabling apps. Turn it off in Developer options when you don't need those — the rest of the phone remote keeps working.
- **What the launcher is granted over ADB:** "draw over other apps" (the pairing PIN and messages over any app), an accessibility service (Back and Home from the phone remote, and the remote's Input button), `DUMP` (Now Playing without USB debugging), `WRITE_SECURE_SETTINGS` (choosing and switching the screen saver from TV Home's Settings), `READ_LOGS` below Android 13 (the Live log screen saver and Play Store updates in the bell; it can read the whole system log, which stays on the TV) and All files access (the Files tab and the `Wallpapers` folder). The accessibility service could see what's on screen, but doesn't ask to; it sends Back and Home, and filters one key, the Input button, letting every other key through untouched.
- **Keep accessibility enabled (optional, off by default):** Settings → Misc can restore TV Home's accessibility service if firmware clears it, including while another app is open or after TV Home starts at boot. It uses the existing background service and `WRITE_SECURE_SETTINGS`, preserves other accessibility services and backs off during repeated resets. While enabled it also undoes a manual disable in Android Settings: **turn this option off in TV Home first**. Restore tools switch it off before removing access. Emergency opt-out: `adb shell settings put secure tvhome_keep_accessibility_enabled 0`. It cannot work while TV Home is force-stopped or the permission has been revoked.
- **The Weather card fetches public data** while TV Home is open (and keeps it for offline use): MET Malaysia's forecast and warnings for the place set in Settings (data.gov.my), the DOE's APIMS readings, and ECMWF's feels-like temperature and Copernicus air quality for the chosen station's spot (Open-Meteo). Like the Globe, the requests carry nothing about you beyond the TV's address and the `TvHome/1.0` user agent.
- **The Globe screen saver fetches public data while it's on screen** (and keeps it for offline use): NASA's Earth maps (once, from Wikimedia Commons), OpenStreetMap's edits, USGS's earthquakes, CelesTrak's satellite orbits, NOAA's aurora and wind, NASA's snow and ice, the cloud map, and Natural Earth's countries (once) with each country's flag from Flagpedia (the first time it pops up), each only if its layer is switched on. The requests carry nothing about you or the TV beyond what any web request does (its address and a `TvHome/1.0` user agent).
- **Net traffic (optional, off until you start it) is a local VPN.** Android asks before it runs, and a "VPN" badge shows on the home screen while it does. It forwards the TV's traffic on the TV itself (no outside VPN server) and reads only each connection's first outgoing bytes, for the server's name (the TLS hello's SNI or HTTP Host), a plain-HTTP request line or a plain-DNS name. Nothing is decrypted or stored beyond a 30-minute, in-memory list. Countries come from an offline IP list downloaded once, so no address is sent to a lookup service. The first start downloads its small engine (checked against fingerprints in the APK) and the country data (about 30 MB).
- **The phone remote is plain HTTP on port 8486.** Pairing needs a six-digit PIN shown on the TV (five tries, then a lockout that grows to 15 minutes), and the server refuses requests whose `Host` isn't an IP address and any cross-site request, so a web page can't drive the TV. Traffic isn't encrypted though: keep the TV on a trusted WPA2/WPA3 network, never forward the port from your router, and use Settings → Phone remote → Forget all phones if you lose a phone.
- **Releases are signed with the project's own key**, so every GitHub Release updates the last one in place, settings kept. Building it yourself without that key signs with your computer's debug key instead: switching between a self-built and a downloaded APK needs one uninstall, which resets the launcher's settings. See [development.md](docs/development.md#release-signing-and-github-releases).

`scripts/restore.sh` undoes the grants and the home-screen change.

## Undo

```bash
scripts/restore.sh                    # back to the TV's own launcher; re-enables anything this project disabled
scripts/restore.sh --remove-launcher  # ...and uninstalls this launcher
```

## More

- [How every feature works](docs/features.md): prayer times and azan, weather and air quality (with data credits), bell, screen savers (and the Globe's data credits), Now Playing, running apps, Settings, Most used, System card, phone remote, dock, wallpapers (with photo credits)
- [Debloating the original Mi TV](docs/xiaomi-debloat.md): what was disabled and kept, speed tweaks, ad-blocking DNS
- [Development](docs/development.md): folder structure, rebuilding, benchmark method
- [PROMPT.md](PROMPT.md): the prompt to repeat the whole setup on another TV with an AI agent

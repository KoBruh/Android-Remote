# Android Remote

A small Android phone app that copies the remote screen of Google's Google TV app and can
wake the TV from standby. It talks to the TV directly over Wi-Fi with the Android TV Remote
protocol (v2), the same one Google's app uses. No account, no cloud, no ads.

Differences from Google's remote: every button except the round D-pad has square corners
(one token, see [Customising](#customising)), and the Power button wakes a sleeping TV with
Wake-on-LAN.

## Install

APKs are built by GitHub Actions on every push.

1. Open the repository on GitHub in your phone's browser and sign in.
2. **Actions** → newest **Build** run with a green tick → scroll to **Artifacts**.
3. Download `android-remote-release` (or `android-remote-debug`), unzip it and open the APK.
   Android asks once to allow your browser or file manager to install apps.

Debug and release share the same app ID, so only one can be installed at a time. Both are
signed with the debug key in `app/debug.keystore` until you
[add your own keystore](#release-signing), so a new build installs over the old one and
keeps its pairing. The debug build is larger and has the reference overlay and the pairing
failure report.

## First use

1. Phone and TV must be on the same Wi-Fi. On Android 17 and newer, allow the "nearby
   devices" prompt, or the app cannot reach the TV.
2. The device menu opens by itself. Pick your TV under **Found on Wi-Fi**, or **Pair new**
   and type the TV's IP address.
3. The TV shows a 6-character code. Type it in. Done: the pill turns green.

The app reconnects by itself afterwards. Pairing again is only needed if the TV is reset.

### If pairing fails

Install the **debug** APK and try again. When pairing fails, the dialog shows a report:
the exception, each stage of the attempt (TCP connect, TLS handshake, pairing messages),
and a probe of every layer (permissions, networks, both TV ports over both routes, the
client key, the TLS handshake). **Copy report** puts it on the clipboard. The same lines go
to Logcat under the tag `AndroidRemote`.

### Waking the TV

The Power button sends POWER while the TV is reachable. If it is not, it sends a
Wake-on-LAN packet, reconnects for up to 20 seconds, and only sends POWER if the TV still
reports that it is off.

After the TV is turned off (with the app or with its own remote), the app stops reconnecting
to it: on some TVs a new remote connection wakes the screen again. Press Power or reopen the
app to connect again.

If the TV turns itself back on after the app switches it off, but stays off with its own
remote, change **Settings → Key that turns the TV off**: *Power* (KEYCODE_POWER, 26) is the
default, *Sleep* (223) and *TV power* (177) are what some TVs' own remotes send. A TV that
reports it is off is always woken with Power. The debug build's **Settings → Connection log** shows every connect,
disconnect, power state the TV reports, and key sent.

- The TV can only be woken if **Networked standby** is on in its settings and it was turned
  off with a short press of Power. A fully shut down TV cannot be woken by software.
- Wake-on-LAN needs the TV's MAC address. It is filled in from the TV's certificate at
  pairing, but that value can belong to another network interface. If the TV does not wake,
  check it in **Settings** against *TV Settings → Device Preferences → About → Status →
  Wi-Fi MAC address*.

### Buttons

| Button | Tap | Hold |
|---|---|---|
| D-pad, OK, Back | key press | long press |
| Vol-, Vol+ | one step | repeats |
| Home, Mute | key press | |
| Power | power toggle, or wake | |
| Mic | | hold to talk (phone microphone is streamed to the TV) |
| X | closes the app | |

The Mic button needs the microphone permission. Without it, or on a TV without voice
support, it opens the TV's own voice search instead. The phone's volume keys control the TV
while the app is open (can be turned off in Settings).

## Customising

Everything visual is in one file:
[`app/src/main/java/com/androidremote/app/ui/theme/Tokens.kt`](app/src/main/java/com/androidremote/app/ui/theme/Tokens.kt).
Each value has a comment saying what it changes.

- **Corners:** `ButtonCorner = 0.dp` makes the buttons square. Try `28.dp` for Google's look.
- **Colours:** `Background`, `PillFill`, `DpadFill`, ...
- **Sizes and positions:** in *reference pixels*, the pixels of the 1084 x 2412 screenshot
  in `docs/reference/`. They are scaled with `dp = refPx * (screenWidthDp / 1084)`, so the
  layout looks the same on every phone. Multiply a dp value by 2.63 to get reference pixels.
- **Feel:** hold delay, repeat rate, pulse speed, default haptic strength.

To **add, remove or change a button**, edit the `RemoteButtons` list in
[`ui/remote/RemoteScreen.kt`](app/src/main/java/com/androidremote/app/ui/remote/RemoteScreen.kt).
A button is an icon, a rectangle, an action (any Android `KEYCODE_*` number works) and a
hold behaviour. New icons go in `ui/icons/Icons.kt`: paste the SVG path of a
[Material Symbol](https://fonts.google.com/icons).

**Pixel check:** in a debug build, turn on *Settings → Show reference screenshot overlay*.
The reference screenshot is drawn half-transparent over the remote.

## Project layout

```
app/src/main/java/com/androidremote/app/
  MainActivity.kt        activity: edge-to-edge, lifecycle, hardware volume keys
  RemoteController.kt    the app's brain: connection upkeep, power/wake, voice, one state flow
  AppContainer.kt        builds the long-lived objects once per process (manual DI)
  RemoteApp.kt           Application class and the `Context.remoteController` shortcut
  ui/AppRoot.kt          root composable: screens, dialogs, button press -> action
  ui/theme/Tokens.kt     every colour, size, radius and timing
  ui/remote/             RemoteScreen (button list, layout), Dpad, RemoteButton, Presses
  ui/devices/            dropdown: paired + discovered TVs, Pair new, Settings
  ui/pairing/            pairing dialog, "Pair again?" dialog
  ui/settings/           settings screen
  ui/icons/Icons.kt      hand-copied Material Symbols
  tv/                    protocol: Framing, Tls, CertManager, CertSubject, PairingClient,
                         PairingSecret, RemoteClient, RemoteProtocol, Keys, Voice,
                         WakeOnLan, Discovery, NetworkMonitor
  data/DeviceStore.kt    paired TVs and settings (DataStore)
app/src/main/proto/      polo.proto (pairing), remotemessage.proto (control)
```

The UI only talks to `RemoteController`, which lives for the whole process and knows nothing
about the UI. That is the hook for later additions (Quick Settings tile, widget, notification
remote, keyboard, app shortcuts):

- `context.remoteController` gives any component the same instance.
- `acquire()` / `release()` keep the TV connection alive for as long as someone needs it.
  The activity holds it while it is visible.
- One-shot callers use suspend functions that hold the connection themselves, for example
  `togglePowerOrWake()`, which returns a `PowerResult` a tile can show.
- `sendKey(code, direction)` sends any Android key code.

### Protocol notes

- Pairing runs on TLS port **6467**, control on TLS port **6466**. Messages are protobufs
  with a varint length prefix.
- The phone's RSA-2048 key and self-signed certificate are generated in AndroidKeyStore on
  first run and never leave it. The TV's certificate is trusted on first use: the one it
  presents on the first control connection after pairing is pinned by its SHA-256
  fingerprint, and a different certificate later triggers a "Pair again?" prompt.
- The TV pings every 5 seconds and every ping is answered. If the TV is silent for 16
  seconds the connection is dropped and re-established with a 0.5 s to 5 s backoff. The
  connection only lives while the app is in the foreground.
- IME support is switched off in `RemoteClient` because it makes some TVs hide their
  on-screen keyboard. The price is that the foreground app is not reported.

## Building

GitHub Actions (`.github/workflows/build.yml`) runs
`./gradlew assembleDebug assembleRelease testDebugUnitTest lint` with JDK 17 and uploads the
artifacts `android-remote-debug` and `android-remote-release`. The run summary lists the
APK sizes.

To build on a computer: install JDK 17+ and the Android SDK (platform 37), then run
`./gradlew assembleDebug`.

### Release signing

Without secrets, the release APK is signed with the debug key so the build still works.
To sign it with your own key, create a keystore once and add four repository secrets. This
works from a phone with [Termux](https://termux.dev):

```sh
pkg install openjdk-17
keytool -genkeypair -v -keystore release.jks -alias androidremote \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks        # copy the output
```

Then on GitHub: **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the base64 output |
| `KEYSTORE_PASSWORD` | the keystore password you chose |
| `KEY_ALIAS` | `androidremote` |
| `KEY_PASSWORD` | the key password (the same as the keystore password unless you set another) |

Keep `release.jks` and its password somewhere safe: updates must be signed with the same
key. The first release APK signed with the new key cannot replace one signed with the debug
key; uninstall the old one once (you will have to pair the TV again).

## Credits

The TV protocol code is a Kotlin port of
[tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2) (Apache-2.0), the
library behind Home Assistant's Android TV Remote integration. The `.proto` files are copied
from it. Icons are [Material Symbols](https://github.com/google/material-design-icons)
(Apache-2.0). See [`NOTICE`](NOTICE).

This project is not affiliated with Google. Google TV and Android TV are trademarks of
Google LLC.

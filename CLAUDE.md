# Android Remote: build brief for Claude Code

This file tells you how to build **Android Remote**, a standalone Android phone app. It copies the remote screen of Google's Google TV app and can wake the TV from standby. Read all of it before writing any code.

---

## 0. How to work

### Plugins (required)
- **feature-dev**: drive the whole build with `/feature-dev:feature-dev`. Pass this file as the feature description. Go through every phase: discovery, codebase exploration, clarifying questions, architecture, implementation and review. Ask the clarifying questions before you write code.
- **caveman**: turn on caveman mode at the start of the session and keep it on for **chat replies to me only**. Code, code comments, commit messages, README and in-app text stay in normal English.
- If either plugin isn't available in the session, **stop and tell me**. Don't carry on without it.

### Ground rules
- When an error blocks you (permissions, network, a failing build you can't fix), stop and tell me. I'll grant access or fix it.
- Don't invent details I haven't given. If something matters and isn't covered here, ask.
- I'm on my phone and don't use a PC for this. **APKs are built by GitHub Actions** and I install them from the workflow artifacts (see §7).
- Commit in small logical steps with clear messages.

---

## 1. Tech stack (decided)

| Choice | Decision | Why |
|---|---|---|
| Language | **Kotlin** | First-class Android language, concise, full platform API access |
| UI | **Jetpack Compose (Material 3)** | Easiest toolkit for pixel-exact custom UI. Every size, colour and shape is plain code I can tweak |
| Min / target SDK | minSdk 26, target/compile latest stable | Covers my phone (Nothing Phone 2a, Android 14+) |
| Build | Gradle Kotlin DSL + version catalog (`libs.versions.toml`) | |
| Protobuf | `com.google.protobuf` Gradle plugin with **protobuf-kotlin-lite / javalite** | Lite runtime keeps the APK small |
| Concurrency | Kotlin coroutines + Flow | |
| Storage | Jetpack DataStore (preferences) | Paired devices and settings |
| TLS keys | **AndroidKeyStore** | It generates the self-signed client cert for us, so no BouncyCastle |
| DI | None (manual constructor injection) | Keep it light |
| Release build | R8 minify + resource shrinking **on** | Target: release APK under ~5 MB |

Don't add libraries beyond these without asking me. Icons are hand-copied Material Symbols vectors (Apache-2.0) as `ImageVector`s. **Don't** pull in `material-icons-extended`.

Package name: `com.androidremote.app` (ask me if you want another). App label: **Android Remote**.

---

## 2. Visual spec: copy the reference screenshot

The reference screenshot is `docs/reference/google-tv-remote.png` (1084 × 2412 px, dark theme). Match it **exactly**, with one change:

> **Change from the reference:** every button except the circular D-pad (and its round centre OK button) has **square corners**. That covers the close button, the device pill and its dropdown, power, back, home, mic, mute, vol- and vol+. Put the radius in one token, `ButtonCorner = 0.dp`, so I can change it later.

### 2.1 Scaling rule
All measurements below are in **reference pixels** on a 1084 px wide screen. Convert them with
`dp = refPx × (screenWidthDp / 1084f)`
so the layout scales to any phone and looks the same. The "≈dp" values are for a 412 dp wide screen and are only a guide. Use the formula.

The app draws edge-to-edge, with transparent light-icon status and nav bars over the background colour.

### 2.2 Colours (sampled from the screenshot)
| Token | Hex | Used for |
|---|---|---|
| `Background` | `#333537` | Screen background |
| `TopButtonFill` | `#1E1F20` | Close and power button fills |
| `TopIcon` | `#C4C7C5` | Close X and power icon |
| `PillFill` | `#6DD58C` | Device pill and dropdown (connected) |
| `PillContent` | `#0A3818` | TV icon, device name text, chevron |
| `DpadFill` | `#1B1B1B` | D-pad disc |
| `DpadStroke` | `#0E0E0E` | D-pad outer edge and centre-button ring |
| `DpadCenterFill` | `#444746` | Centre OK button |
| `DpadArrow` | `#444746` | The four chevrons on the D-pad |
| `BottomButtonFill` | `#1B1B1B` | Back, home, mic, mute, vol-, vol+ |
| `BottomIcon` | `#E3E3E3` | Icons on the bottom buttons |
| `PressedOverlay` | white at 8% alpha | Pressed state on any button (ripple) |

States that aren't in the screenshot (my additions, keep them subtle):
- Disconnected pill: fill `#444746`, content `#E3E3E3`.
- Connecting: same as connected, plus a slow alpha pulse (0.6 to 1.0) on the pill.

### 2.3 Layout (reference px, origin top-left of the full screen)

**Top row** (vertical centre ≈ y 208, under the status bar)
| Element | Bounds (x1–x2 × y1–y2) | Size | ≈dp @412 |
|---|---|---|---|
| Close button (X) | 53–136 × 158–263 | 84 × 106 | 32 × 40 |
| Device pill (TV icon + name) | 274–678 × 153–257 | 405 × 105 | 154 × 40 |
| Gap pill→dropdown | — | 5 | 2 |
| Dropdown (chevron down) | 684–809 × 153–257 | 126 × 105 | 48 × 40 |
| Power button | 937–1042 × 158–263 | 106 × 106 | 40 × 40 |

- The pill holds a TV icon at the left (~18 dp) and the device name centred in the remaining space (~16 sp, regular weight, `PillContent` colour). The name in the screenshot is "jp". Show the paired device's name.
- Icons: close X ≈ 20 dp, power ≈ 24 dp, chevron ≈ 20 dp.

**D-pad** (a circle, keep it round)
| Element | Spec |
|---|---|
| Outer disc | diameter 916 px (≈348 dp), x 84–999, y 611–1526, centre (541, 1068). Fill `DpadFill`, 1–2 dp `DpadStroke` edge |
| Centre OK button | diameter ≈358 px (≈136 dp), same centre, fill `DpadCenterFill`, ≈2 dp `DpadStroke` ring |
| Chevrons | 4 thin rounded chevrons (≈30 dp wide, ≈3 dp stroke), `DpadArrow`, each about 42 dp in from the disc's outer edge |

The disc splits into 4 pie-slice hit zones (up/right/down/left, 90° each, rotated 45°) outside the centre circle. Each zone shows its own pressed overlay clipped to its slice.

**Bottom row 1** (vertical centre ≈ y 1970)
| Element | Bounds | Size | ≈dp |
|---|---|---|---|
| Back (←) | 63–272 × 1866–2075 | 210 × 210 | 80 × 80 |
| Home (filled house) | 374–709 × 1845–2096 | 336 × 252 | 128 × 96 |
| Mic | 811–1020 × 1866–2075 | 210 × 210 | 80 × 80 |

**Bottom row 2** (vertical centre ≈ y 2233)
| Element | Bounds | Size | ≈dp |
|---|---|---|---|
| Mute (speaker with slash) | 63–251 × 2160–2306 | 189 × 147 | 72 × 56 |
| Vol- | 315–661 × 2160–2306 | 347 × 147 | 132 × 56 |
| Vol+ | 673–1020 × 2160–2306 | 348 × 147 | 132 × 56 |

- Bottom icons ≈ 24–28 dp, `BottomIcon` colour.
- Vertical placement: pin the two bottom rows to the bottom (row 2's bottom edge sits 106 px ≈ 40 dp above the screen bottom, including the nav-bar inset). Centre the D-pad at **44.3% of screen height**. On short screens, shrink the D-pad before it overlaps anything.

### 2.4 Feel
- Haptic tick (`HapticFeedbackConstants.KEYBOARD_TAP` or similar) on every press. Strength and on/off are settings.
- Press-and-hold on the D-pad, back and the volume buttons sends a long press (see §3.4). Volume repeats while held.
- Pixel-check: add a debug-only overlay toggle that draws the reference PNG at 50% alpha over the UI so I can compare them.

---

## 3. TV protocol (Android TV Remote Service, "v2")

Google's app uses this protocol. Base the implementation on the open-source Python library **tronikos/androidtvremote2** (used by Home Assistant). Check its licence, keep attribution in `NOTICE`/README, and **port the logic from its source rather than from memory**. Copy its `.proto` files (`polo.proto` for pairing, `remotemessage.proto` for control) into `app/src/main/proto/`.

### 3.1 Discovery
- `NsdManager` browse for `_androidtvremote2._tcp`. Show the found TVs in the device dropdown.
- Manual fallback: enter the IP address.

### 3.2 Client certificate
- On first run, generate an RSA-2048 key pair in **AndroidKeyStore**, with a self-signed cert (`setCertificateSubject`, long validity) and digests/paddings that TLS client auth needs (include `DIGEST_NONE`, SHA-256 and PKCS#1 signature padding). Test it on a real device.
- Use it through a `KeyManager` built from the `AndroidKeyStore` keystore in an `SSLContext`.
- TV server cert: **trust on first use**. Accept it during pairing, store its SHA-256 fingerprint, and reject a mismatch afterwards (show a "re-pair?" prompt).

### 3.3 Pairing (port 6466, TLS)
Messages are length-prefixed protobuf; copy the framing from androidtvremote2.
1. `PairingRequest` (service name + client name "Android Remote") → `PairingRequestAck`
2. `Options` (input encoding hexadecimal, symbol length 6, role input) → `Options`
3. `Configuration` → `ConfigurationAck`. The TV now shows a 6-character hex code.
4. The user types the code. Build the `Secret` as SHA-256 over the client RSA modulus and exponent, the server RSA modulus and exponent, and the code's nonce bytes. The first code byte is a checksum that must equal hash byte 0. Port this exactly from androidtvremote2.
5. On `SecretAck`, save the device: name, IP, port, MAC, server cert fingerprint.

### 3.4 Control (port 6467, TLS, same client cert)
- Do the `RemoteConfigure` / `RemoteSetActive` handshake as androidtvremote2 does.
- **Answer every `RemotePingRequest` with a `RemotePingResponse`**, or the TV drops the connection.
- Read power state from `RemoteStart.started`, volume from `RemoteSetVolumeLevel`, and the current app from `RemoteImeKeyInject` app info, if present.
- Keys go out as `RemoteKeyInject(key_code, direction)`. Use direction `SHORT` for a tap, `START_LONG`/`END_LONG` for a hold.

| Button | KeyEvent code |
|---|---|
| D-pad up / down / left / right | 19 / 20 / 21 / 22 |
| Centre OK | 23 (DPAD_CENTER) |
| Back | 4 |
| Home | 3 |
| Vol+ / Vol- / Mute | 24 / 25 / 164 |
| Power | 26 |
| Mic | Voice over the protocol if the protos support it (voice begin/payload/end with `RECORD_AUDIO`). Otherwise fall back to KEYCODE_SEARCH (84) to open voice search on the TV. Tell me which one you got working |

- Close (X) closes the app, as in Google's app.
- Auto-reconnect with backoff (0.5 s → 5 s max) while the app is in the foreground. Reconnect straight away on `onResume` and on Wi-Fi change.

### 3.5 Wake from standby (the main reason this app exists)
The TV is an older **TCL Android TV, firmware V8-R41KT01-LF1V391 (R41K platform, S6500-class)**. It can only be woken if *Networked standby* is on in the TV's settings and the TV was turned off with a short press of Power. Software can't wake it from a full shutdown. Show this as a one-time tip in Settings.

Power button logic:
1. **Connected** → send POWER (26).
2. **Not reachable** → send a Wake-on-LAN magic packet (6 × `0xFF` + 16 × MAC) over UDP to `255.255.255.255` and the subnet broadcast, on ports 9 and 7, 3 times, 100 ms apart. Then try to reconnect every 1 s for up to 20 s. The pill pulses while this runs.
3. Once reconnected, read `RemoteStart.started`. **Only send POWER if the TV still reports off**, or you'll turn it straight back off.
4. If it never comes back, show a short message: "TV didn't wake. Check Networked standby is on."

The TV's MAC address is entered once in Settings (Android can't read the ARP table). Show the hint: *TV Settings → Device Preferences → About → Status → Wi-Fi MAC address*.

---

## 4. App structure

```
app/src/main/java/com/androidremote/app/
  MainActivity.kt
  ui/
    theme/Tokens.kt        ← ALL colours, sizes (ref px), radii, haptics, in one file
    remote/RemoteScreen.kt ← the main screen (§2)
    remote/Dpad.kt
    remote/RemoteButton.kt ← one square/rounded button composable, reused everywhere
    devices/DevicePicker.kt← dropdown: discovered + paired TVs, "Pair new", "Settings"
    pairing/PairingDialog.kt
    settings/SettingsScreen.kt
    icons/Icons.kt         ← hand-copied Material Symbols ImageVectors
  tv/
    PairingClient.kt
    RemoteClient.kt        ← connection, ping, key injection, state Flow
    Framing.kt
    CertManager.kt
    WakeOnLan.kt
    Discovery.kt
  data/DeviceStore.kt
app/src/main/proto/ (polo.proto, remotemessage.proto)
docs/reference/google-tv-remote.png
```

The main screen must have **no extra visible controls**. Everything else is reached from the dropdown chevron.

---

## 5. Customisation (important to me)
- Every visual value lives in `Tokens.kt`: colours, every size in ref px, `ButtonCorner`, `DpadCorner` (stays a circle), icon sizes, haptic strength. Add a comment on each one saying what it changes.
- Settings screen: haptics on/off and strength, keep screen awake while open, hardware volume keys control the TV (on by default), TV MAC address, re-pair or forget a device, reference-overlay toggle (debug builds).
- Keep `RemoteButton` generic (shape, size, icon, key code, long-press behaviour) so I can add buttons by editing one list.

## 6. Phase 2 (only after Phase 1 works and I say go)
Keyboard/text input through the protocol's IME messages, app launch shortcuts (`RemoteAppLinkLaunchRequest`, e.g. open Stremio), a Quick Settings tile for power/wake, a home-screen widget, and a persistent notification remote.

---

## 7. Build and delivery (GitHub Actions)
- `.github/workflows/build.yml` runs on every push and on manual dispatch. It uses JDK 17 and Gradle cache, then runs `./gradlew assembleDebug assembleRelease lint`.
- Upload both APKs as workflow artifacts named `android-remote-debug` and `android-remote-release`.
- Release signing reads from repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. If they're missing, sign the release with the debug key so the build still works. Write a short README section on creating the keystore and adding the secrets, ideally from a phone.
- Commit the Gradle wrapper.
- Add unit tests for: framing encode/decode, WoL packet bytes, the pairing secret calculation (against a known vector from androidtvremote2's tests if it has one), and the dp scaling helper.

## 8. Done means
- [ ] Main screen matches the reference (overlay check) apart from the square corners
- [ ] Pairs with the TCL TV using the on-screen code, and reconnects after an app restart without re-pairing
- [ ] Every button works, and long-press works on the D-pad, back and volume
- [ ] Power wakes the TV from networked standby (WoL + reconnect + state check)
- [ ] Survives the TV going to standby and coming back, with no zombie connection
- [ ] CI builds both APKs and all tests pass
- [ ] Release APK size reported in the final summary

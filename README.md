# Tether

**A native Android bridge that lets an AI assistant take real actions on your phone (place a call, set a timer, read notifications, toggle the flashlight) with one native command at a time.**

Tether is part of the same family as [Emit](https://github.com/tkeehner/emit) (SMS) and Beacon (launcher). It has two halves:

- **Tether for Android** (`app/`, Kotlin + Jetpack Compose): a foreground service that listens on your home Wi-Fi, verifies every command cryptographically, and runs it through real Android APIs. No accessibility service, no screen scraping, no tapping, no root.
- **`plink` PC companion** (`pc/`, Python): the assistant-facing side. It exposes the same tool catalog as a CLI (`plink.bat`), a stdio MCP server (`plink-mcp.bat`) and a warm loopback-only agent (`plink-agent.bat`).

> Screenshots: _coming soon_ <!-- add screenshots/ images of the main screen, pairing code and call countdown -->

## How it works

```
AI assistant ──(Uplink: MCP / CLI / your webhook)──► plink on your PC ──(paired, encrypted LAN link)──► Tether on the phone ──► Android API
```

1. You pair the PC and phone once with a 6-digit code shown on the phone.
2. Your assistant calls a tool such as `call to=Mom` or `set_timer minutes=10`.
3. `plink` resolves names to numbers, signs the command and sends it over an encrypted session.
4. The phone checks the signature, timestamp and allowlist, then performs exactly that one action.

### Uplink: bring your own agent
**Uplink** is the channel your assistant uses to reach Tether. Nothing in this repo is tied to one AI product: any agent that can run a command or speak MCP can drive it. If you route requests through a webhook (for example a hosted agent's inbound webhook), its URL and key live in **your** configuration, never in source. This repo ships no endpoints, tokens or keys.

## Features

| Tool | Android API | Permission / grant |
|---|---|---|
| status | BatteryManager, ConnectivityManager, AudioManager, KeyguardManager | none |
| find_contact | ContactsContract | READ_CONTACTS |
| call (+3 s cancel countdown) | `ACTION_CALL` via CallCountdownActivity, speakerphone guard | CALL_PHONE + Display over other apps |
| cancel_call / hang_up | TelecomManager.endCall() | ANSWER_PHONE_CALLS |
| call_state | TelephonyManager | READ_PHONE_STATE |
| set_alarm / set_timer | `AlarmClock.ACTION_SET_ALARM` / `SET_TIMER` | SET_ALARM + Display over other apps |
| read / dismiss_notification | NotificationListenerService | Notification access |
| list_apps / launch_app | PackageManager | Display over other apps |
| open_url | `ACTION_VIEW` (http/https only) | Display over other apps |
| flashlight, media, volume (preview) | CameraManager, MediaSessionManager, AudioManager | media needs Notification access |
| send_text | Routed to **Emit**, which waits for your APPROVE tap | handled by Emit |

"Display over other apps" is needed because Android 10+ blocks background apps from opening screens like the dialer, clock or browser.

## Security model
- **Pairing:** X25519 key agreement with a 6-digit SAS code and hash commitment, confirmed on the phone (ALLOW / DENY).
- **Sessions:** ChaCha20-Poly1305 with ephemeral keys (forward secrecy) and strict counters. Kotlin and Python share byte-identical test vectors.
- **Command gate:** the phone generates a command token in the Android Keystore. Every command carries an HMAC over id, method, timestamp and params; bad MACs, stale timestamps (>120 s) and replayed ids are rejected.
- **Allowlist only:** validated arguments; USSD/MMI codes and emergency numbers refused; URLs must be http(s); no reset, uninstall, settings, shell, screen reading or input injection.
- **Visible and revocable:** a session alert with **Disconnect**, a Quick Settings kill-switch tile, an in-app toggle, and **Rotate key**.
- **Home Wi-Fi only:** no cloud relay. The PC agent binds to `127.0.0.1` only and requires a per-install bearer key.
- **Content-free audit logs:** tool, outcome and timing only; never numbers, message text or URL paths.

## Build

### Android app
Requirements: JDK 17, Android SDK 35.

    ./gradlew testReleaseUnitTest lintRelease assembleRelease

Release signing is **bring your own key**. No keystore or password is in this repo. To sign, set these as environment variables (or in `~/.gradle/gradle.properties`, never committed):

    TETHER_KEYSTORE=/path/to/your-release.jks
    TETHER_KEYSTORE_PASSWORD=...
    TETHER_KEY_ALIAS=...
    TETHER_KEY_PASSWORD=...

Without them you get an unsigned release APK. `InteropTest` runs the real Kotlin protocol against the real Python client when `PLINK_PY` (a Python with `cryptography`) and `PLINK_PC` (absolute path to `pc/`) are set, and is skipped otherwise.

The phone's LAN port is `LinkService.PORT`; keep it in sync with `TETHER_PORT` on the PC.

### PC companion
See [`pc/README.md`](pc/README.md) for install, pairing, the full command list and configuration.

## First run on the phone
1. Install the APK and open **Tether**. Grant each row under **PERMISSIONS**: Contacts, Phone, Notifications, Notification access, Display over other apps, and Battery → Unrestricted.
2. Tap **TURN ON**, then **PAIR NEW PC**, and follow the PC steps.
3. Optional: add the **Tether** Quick Settings tile as a kill switch.


## Keeping assistant-placed calls off speakerphone
Calls placed by `call` ask Telecom for no speakerphone. A guard then moves the call back off the loudspeaker if Samsung or Telecom switches it to speaker while dialing or within the first 8 s after the call connects. After that window, a speaker tap is treated as the user's choice and left alone. Calls you place yourself are never touched.
- **Reliable mode:** run `adb shell appops set com.tyler.primelink MANAGE_ONGOING_CALLS allow` once. Telecom then binds `CallWatchService`, a non-UI InCallService with no screen, so Samsung's dialer stays the phone app. It switches the route through Telecom's `setAudioRoute`. To undo: `... MANAGE_ONGOING_CALLS default`.
- **Without the app-op:** the app falls back to `AudioManager.setCommunicationDevice(earpiece)`. Android gives the phone process priority during a cellular call, so the platform may ignore this.
- `status.call_audio` and `call_state.audio` report the route, which guard is active, and the last action taken.

## Tests
- Android: `PolicyTest` (allowlist, lock rules, number/URL validation, content-free audit), `CommandGateTest` (token MAC, replay, staleness), `CryptoVectorsTest` (byte-identical with Python), `CallCountdownTest` (cancel never dials), `CallAudioTest`, `InteropTest`.
- PC: `pytest pc/tests` (crypto vectors, pairing and sessions against a simulated phone, name resolution, Emit bridge, agent auth).

## Tech
Kotlin, Jetpack Compose, Android foreground services / NSD / Telecom / NotificationListener, Bouncy Castle; Python 3.12, `cryptography`, `zeroconf`, MCP over stdio and HTTP.

## License
GPL-3.0-or-later (see [LICENSE](LICENSE)). The link crypto is adapted from Emit, which is GPL-3.0-or-later. Bundled fonts (Oxanium, JetBrains Mono) are under the SIL Open Font License.

### Third-party assets and attribution

- **JetBrains Mono** (`app/src/main/res/font/jbmono_regular.ttf`, `jbmono_bold.ttf`): SIL Open Font License 1.1. See `app/src/main/assets/licenses/JetBrainsMono-OFL.txt`.
- **Oxanium** (`app/src/main/res/font/oxanium.ttf`): SIL Open Font License 1.1. See `app/src/main/assets/licenses/Oxanium-OFL.txt`.

Note: the Android application id is `com.tyler.primelink`, kept from the original build so updates install over existing copies. Change `applicationId` if you fork it.

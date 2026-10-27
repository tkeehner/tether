# Tether PC companion (`plink`), v0.1.0

The PC side of **Tether**. Your AI assistant runs `plink` (CLI, stdio MCP server, or a warm local agent) and each tool becomes one native action on your Android phone: call, hang up, alarms, timers, notifications, apps and links. It never controls the screen: no tapping, swiping or screenshots. It works over your home Wi-Fi only, with no cloud hop.

**Texts always go through Emit.** `send_text` stages a draft in that contact's [Emit](https://github.com/tkeehner/emit) thread (creating the thread if needed). Emit then shows **APPROVE + SEND / DENY** on the phone. Nothing sends until you tap APPROVE. plink has no way to approve.

## Install (Windows)
1. Run `build_win.sh` (or use a release zip) and unzip `Tether-PC-win64.zip` somewhere you control, for example `%USERPROFILE%\Apps\Tether-PC\`. It bundles Python 3.12.
2. Build and install the Tether APK on the phone (see the root README).
3. Optional, for texting: install the Emit PC companion and point plink at it with `plink config msms_app <folder containing msms\>` or the `PLINK_MSMS_APP` environment variable.

## Pair (once)
1. Phone: open Tether and tap **[ PAIR NEW PC ]**. The pairing window stays open for 3 minutes.
2. PC: run `.\plink.bat pair --host <phone-ip>` (the phone shows its address), or leave out `--host` to use mDNS. Allow Windows Firewall on Private networks if asked.
3. Type the 6-digit code from the phone into the PC, then tap **ALLOW** on the phone.
4. Run `.\plink.bat status` to check.

## Use
Your assistant runs commands like these (key=value arguments, `--json` for machine output):

    plink --json status
    plink --json call to=Mom                 # 3 s "Calling… CANCEL" on the phone, then dials
    plink --json cancel_call | hang_up | call_state
    plink --json send_text to=Mom "text=On my way"   # via Emit, waits for APPROVE on the phone
    plink --json text_status draft_id=12 | cancel_text draft_id=12
    plink --json set_alarm time=6:45am label=gym days=mon,wed,fri
    plink --json set_timer minutes=10 label=pasta
    plink --json read_notifications limit=20
    plink --json dismiss_notification key=<key>  |  dismiss_notification all=true
    plink --json list_apps | launch_app app=Chrome | open_url url=https://example.com
    plink --json flashlight on=true | media action=pause | volume stream=media level=40   # v1.1 preview

`plink tools` prints the JSON schemas. `plink audit` shows the last 50 PC-side audit lines (tool, outcome, milliseconds, never any content).

**Warm agent (fast path):** `plink-agent.bat` keeps one encrypted session to the phone open and serves MCP over HTTP on `127.0.0.1` only (port from `TETHER_AGENT_PORT`). CLI calls use it automatically when it's running. Callers must send `Authorization: Bearer <agent.key>`; the key is generated per install in the user-only config directory and never printed.

**MCP (stdio)** for Cursor, Claude Desktop or any MCP-capable agent:
```json
{ "mcpServers": { "tether": { "command": "C:\\path\\to\\Tether-PC\\plink-mcp.bat", "args": [] } } }
```

## Configuration
| Variable | Purpose |
|---|---|
| `TETHER_PORT` | Phone's LAN port. Must match `LinkService.PORT` in the app. |
| `TETHER_AGENT_PORT` | Loopback port for the warm agent. |
| `PLINK_MSMS_APP` | Folder of the Emit PC companion (texting only). |
| `PLINK_HOME` | Override the config directory (tests use this). |

## Security
- **Link:** pairing uses X25519 with a 6-digit SAS code and a hash commitment. Sessions use ChaCha20-Poly1305 with ephemeral keys (forward secrecy) and strict counters. Tether uses its own domain-separation labels (`plink-*`).
- **The phone checks the assistant's key itself:** the phone generates a command token, stores it in the Android Keystore and hands it to the PC exactly once, sealed inside pairing. Every command carries `HMAC(token, id‖method‖timestamp‖params)`. Bad MACs, timestamps more than 120 s off, and reused or non-increasing ids are rejected. **Rotate key** on the phone cuts every PC off.
- **PC secrets:** the identity key and token are DPAPI-wrapped on Windows. They are never printed.
- **Allowlist only:** the phone accepts exactly the tools above, with validated arguments. Numbers must be plain digits: `*`/`#` USSD/MMI codes and emergency numbers are refused. URLs must be http(s). There is no factory reset, uninstall, settings, shell, screen reading or input injection.
- **Locked phone:** status, notifications, alarms/timers, media, flashlight and calls work while locked. `launch_app` and `open_url` need it unlocked.
- **Session alert:** when the assistant starts using the phone after 5 minutes idle, the phone posts a notification with **Disconnect**. Kill switches: that notification, the Quick Settings tile, the in-app toggle, and unlinking the PC.

## Dev / tests
    python3 -m venv .venv && .venv/bin/pip install cryptography zeroconf pytest
    .venv/bin/python -m pytest -q tests           # crypto vectors, pairing/session vs simulated phone, gate, Emit bridge, agent
    .venv/bin/python tests/gen_vectors.py         # regenerate shared vectors (then copy to ../app/src/test/resources)

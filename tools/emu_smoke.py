"""Smoke test Tether on an EMULATOR only (never a real phone): install, grant, pair via plink, run tools.
Usage: python emu_smoke.py <apk>   (adb serial forced to emulator-5554)"""
import json, os, re, subprocess, sys, tempfile, threading, time
ADB = [os.environ.get("ADB", "adb"), "-s", "emulator-5554"]
sys.path.insert(0, os.environ.get("TETHER_PC", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "pc")))
os.environ["PLINK_HOME"] = tempfile.mkdtemp()
from plink import keystore, client, tools, transport as T
from plink.resolve import Ambiguous

def adb(*a, check=True): return subprocess.run(ADB + list(a), capture_output=True, text=True, check=check).stdout
def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml"); return adb("shell", "cat", "/sdcard/ui.xml")
def tap_text(t, tries=20):
    for _ in range(tries):
        x = ui()
        m = re.search(r'text="' + re.escape(t) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
        if m:
            a, b, c, d = map(int, m.groups()); adb("shell", "input", "tap", str((a + c) // 2), str((b + d) // 2)); return True
        time.sleep(1)
    raise SystemExit(f"could not find '{t}' on screen")

PKG = "com.tyler.primelink"
apk = sys.argv[1]
if "package:" not in adb("shell", "pm", "path", PKG, check=False): print(adb("install", "-r", "-g", apk).strip())
for p in ["READ_CONTACTS", "CALL_PHONE", "ANSWER_PHONE_CALLS", "READ_PHONE_STATE", "POST_NOTIFICATIONS"]:
    adb("shell", "pm", "grant", PKG, "android.permission." + p, check=False)
adb("shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
adb("shell", "cmd", "notification", "allow_listener", f"{PKG}/{PKG}.actions.NotifListener")
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP"); adb("shell", "wm", "dismiss-keyguard", check=False)
adb("shell", "am", "start", "-n", f"{PKG}/.ui.MainActivity"); time.sleep(3)
tap_text("[ TURN ON ]"); time.sleep(2)
tap_text("[ PAIR NEW PC ]"); time.sleep(1)
adb("forward", f"tcp:{T.DEFAULT_PORT}", f"tcp:{T.DEFAULT_PORT}")
st = keystore.Store(); code = {}
def ask():
    for _ in range(40):
        m = re.search(r'text="(\d{6})"', ui())
        if m: code["c"] = m.group(1); return m.group(1)
        time.sleep(1)
def allow():
    while "c" not in code: time.sleep(0.5)
    time.sleep(2); tap_text("[ ALLOW ]")
threading.Thread(target=allow, daemon=True).start()
dev = client.pair(st, "127.0.0.1", T.DEFAULT_PORT, ask_code=ask)
print("paired:", dev["name"], "code", code["c"])
pc = client.PhoneClient(st, manual_host=f"127.0.0.1:{T.DEFAULT_PORT}", use_mdns=False); box = tools.Toolbox(pc)
res = {}
def t(name, args=None):
    t0 = time.time()
    try: r = box.run(name, args or {}); ok = "ok"
    except Ambiguous as e: r = {"ambiguous": e.choices}; ok = "ambiguous"
    except Exception as e: r = str(e); ok = "error"
    res[name + (json.dumps(args) if args else "")] = (ok, int((time.time() - t0) * 1000), r if isinstance(r, str) else json.dumps(r)[:300])
    return r
s = t("status"); print("status:", json.dumps(s)[:600])
t("list_apps"); t("call_state"); t("read_notifications", {"limit": 5}); t("set_timer", {"seconds": 90, "label": "smoke"})
t("set_alarm", {"time": "6:45am", "label": "smoke"}); t("flashlight", {"on": False}); t("volume", {"stream": "media", "level": 30})
t("open_url", {"url": "https://example.com"}); time.sleep(2); t("launch_app", {"app": PKG})
t("find_contact", {"query": "zzz"})
# call with cancel: start a call and cancel it before 3 s
th = threading.Thread(target=lambda: t("call", {"to": "5551234"})); th.start(); time.sleep(1.2)
adb("shell", "uiautomator", "dump", "/sdcard/ui.xml"); shot = adb("shell", "cat", "/sdcard/ui.xml")
res["countdown_visible"] = ("ok" if "CALLING" in shot and "CANCEL" in shot else "missing", 0, "")
t("cancel_call"); th.join()
# call that completes
t("call", {"to": "5551234"}); time.sleep(3); t("call_state"); t("hang_up"); time.sleep(1); t("call_state")
# security: forged command
import queue
from plink import crypto as C
ch = pc.ensure(); f = pc.request(10**15, "status", {}); f["mac"] = C.b64e(bytes(32)); q = queue.Queue(); pc._pending[f["id"]] = q; ch.send(f)
res["forged_mac"] = (q.get(timeout=5).get("e", {}).get("code"), 0, ""); time.sleep(0.5)
# not-allowlisted method via a correctly signed frame
try: pc.call("tap", {"x": 1, "y": 1}); res["tap"] = ("ALLOWED?!", 0, "")
except client.PhoneError as e: res["tap"] = (e.code, 0, "")
# locked phone rules: set a PIN on the EMULATOR, lock, try
adb("shell", "locksettings", "set-pin", "1111"); adb("shell", "input", "keyevent", "KEYCODE_SLEEP"); time.sleep(2)
t("status"); res["locked_status"] = res.pop("status")
t("read_notifications", {"limit": 1}); res["locked_read_notifications"] = res.pop('read_notifications{"limit": 1}')
t("open_url", {"url": "https://example.com/locked"}); res["locked_open_url"] = res.pop('open_url{"url": "https://example.com/locked"}')
adb("shell", "locksettings", "clear", "--old", "1111", check=False)
for k, v in res.items(): print(f"{k:55s} {v[0]:10s} {v[1]:6d}ms  {v[2][:160]}")

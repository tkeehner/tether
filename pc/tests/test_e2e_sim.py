import json, os, threading, time, pytest
from plink import keystore, client, tools, protocol as P, crypto as C, mcp_server
from plink.resolve import Ambiguous
from sim_phone import SimPhone

@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("PLINK_HOME", str(tmp_path))
    ph = SimPhone(); ph.pairing_open = True
    st = keystore.Store()
    def ask():
        for _ in range(100):
            if ph.code: return ph.code
            time.sleep(0.01)
    dev = client.pair(st, "127.0.0.1", ph.port, ask_code=ask)
    assert dev["phone_id"] == "simphone01" and "token" not in dev
    pc = client.PhoneClient(st, manual_host=f"127.0.0.1:{ph.port}", use_mdns=False)
    return ph, st, pc

def test_token_stored_wrapped_not_printed(env):
    ph, st, pc = env
    raw = open(st.path).read()
    assert C.b64e(ph.token) in raw or st.data["devices"][0]["token_kind"] == "dpapi"  # plain only off-Windows
    assert st.token() == ph.token

def test_status_and_tools(env):
    ph, st, pc = env
    box = tools.Toolbox(pc)
    assert box.run("status", {})["battery_pct"] == 80
    r = box.run("call", {"to": "Mom"})
    assert r["outcome"] == "dialed" and ph.calls[-1] == {"number": "+15555550100", "name": "Mom"}
    r = box.run("call", {"to": "(555) 555-0123"}); assert ph.calls[-1] == {"number": "5555550123"}
    with pytest.raises(Ambiguous): box.run("call", {"to": "Alex"})
    r = box.run("set_alarm", {"time": "7:30pm", "label": "gym", "days": ["mon", "wed"]})
    assert r["p"] == {"hour": 19, "minute": 30, "vibrate": True, "label": "gym", "days": [2, 4]}
    assert box.run("set_timer", {"minutes": 5})["p"] == {"seconds": 300}
    assert box.run("launch_app", {"app": "chrome"})["p"] == {"package": "com.android.chrome"}
    with pytest.raises(ValueError): box.run("open_url", {"url": "javascript:alert(1)"})
    with pytest.raises(ValueError): box.run("status", {"bogus": 1})

def test_locked_phone_rules(env):
    ph, st, pc = env
    ph.locked = True; box = tools.Toolbox(pc)
    assert box.run("read_notifications", {})["m"] == "read_notifications"
    assert box.run("call", {"to": "Mom"})["outcome"] == "dialed"
    with pytest.raises(client.PhoneError) as e: box.run("open_url", {"url": "https://example.com"})
    assert e.value.code == "locked"

def test_rotated_token_cuts_pc_off(env):
    ph, st, pc = env
    pc.call("status"); ph.rotate()
    with pytest.raises(client.PhoneError) as e: pc.call("status")
    assert e.value.code == "auth_fail"

def test_forged_and_replayed_commands_rejected(env):
    import queue
    ph, st, pc = env
    def send(frame):
        ch = pc.ensure(); q = queue.Queue(); pc._pending[frame["id"]] = q; ch.send(frame); return q.get(timeout=3)
    good = pc.request(10**15, "status", {})
    assert "r" in send(good)
    assert send(good)["e"]["code"] == "replay"                       # same id again
    t = pc.request(10**15 + 1, "status", {})
    assert send(dict(t, ts=t["ts"] - 600_000))["e"]["code"] == "auth_fail"   # ts is covered by the MAC
    time.sleep(0.1)                                                  # phone drops the session on auth_fail
    f = pc.request(10**15 + 2, "status", {})
    assert send(dict(f, mac=C.b64e(bytes(32))))["e"]["code"] == "auth_fail"
    time.sleep(0.1)
    f = pc.request(10**15 + 3, "status", {"x": 1})
    assert send(dict(f, ps='{"x":2}'))["e"]["code"] == "auth_fail"   # params are covered too

def test_stale_but_correctly_signed_rejected(env):
    ph, st, pc = env
    pc.clock = lambda: int(time.time() * 1000) - 10 * 60_000
    with pytest.raises(client.PhoneError) as e: pc.call("status")
    assert e.value.code == "stale"

def test_wrong_pairing_code_rejected(tmp_path, monkeypatch):
    monkeypatch.setenv("PLINK_HOME", str(tmp_path))
    ph = SimPhone(); ph.pairing_open = True
    with pytest.raises(P.ProtocolError): client.pair(keystore.Store(), "127.0.0.1", ph.port, ask_code=lambda: "000000" if ph.code != "000000" else "111111")
    assert ph.pcs == {}

def test_pairing_needs_phone_window(tmp_path, monkeypatch):
    monkeypatch.setenv("PLINK_HOME", str(tmp_path))
    ph = SimPhone()
    with pytest.raises(P.ProtocolError): client.pair(keystore.Store(), "127.0.0.1", ph.port, ask_code=lambda: "1")

def test_mcp_surface(env):
    ph, st, pc = env
    box = tools.Toolbox(pc)
    r = mcp_server.handle(box, {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    names = {t["name"] for t in r["result"]["tools"]}
    assert {"status", "find_contact", "send_text", "call", "hang_up", "call_state", "set_alarm", "set_timer",
            "read_notifications", "dismiss_notification", "launch_app", "open_url"} <= names
    assert not any(n in names for n in ("tap", "swipe", "screenshot", "type_text", "ui_tree", "approve_send"))
    r = mcp_server.handle(box, {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "call", "arguments": {"to": "Alex"}}})
    assert r["result"]["isError"] and r["result"]["structuredContent"]["error"] == "ambiguous"

"""Simulated Tether phone for tests: real pairing/session crypto + the same command gate rules as the app
(CommandGate.kt: MAC with the phone-made token, |skew| <= 120 s, strictly increasing ids) and the lock rule."""
import json, os, socket, threading, time
from plink import crypto as C, protocol as P

LOCKED_OK = {"status", "find_contact", "call", "cancel_call", "hang_up", "call_state", "set_alarm", "set_timer",
             "read_notifications", "dismiss_notification", "list_apps", "flashlight", "media", "volume"}
ALL = LOCKED_OK | {"launch_app", "open_url", "read_calendar"}

class SimPhone:
    def __init__(self):
        self.sk = os.urandom(32); self.phone_id = "simphone01"; self.token = os.urandom(32)
        self.pcs = {}; self.pairing_open = False; self.code = None; self.allow = True; self.locked = False
        self.calls = []; self.log = []
        self.contacts = [{"name": "Mom", "starred": True, "numbers": [{"number": "+1 555-555-0100", "type": "Mobile", "primary": False}]},
                         {"name": "Alex", "starred": False, "numbers": [{"number": "555-555-0111", "type": "Mobile", "primary": False},
                                                                        {"number": "555-555-0112", "type": "Mobile", "primary": False}]}]
        self.apps = [{"package": "com.android.chrome", "label": "Chrome"}, {"package": "com.tyler.matrixsms", "label": "Emit"}]
        self.srv = socket.socket(); self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", 0)); self.srv.listen(4); self.port = self.srv.getsockname()[1]
        threading.Thread(target=self._accept, daemon=True).start()

    def rotate(self): self.token = os.urandom(32)

    def _accept(self):
        while True:
            try: s, _ = self.srv.accept()
            except OSError: return
            threading.Thread(target=self._handle, args=(s,), daemon=True).start()

    def _handle(self, s):
        try:
            raw = P.read_frame(s); m = json.loads(raw)
            if m.get("t") == "pair1":
                if not self.pairing_open: P.write_frame(s, P.jdump({"t": "err", "msg": "not in pairing mode"})); return
                class Pre:  # replay the already-read first frame into pair_responder
                    def __init__(self, s, first): self.s, self.buf = s, (len(first)).to_bytes(4, "big") + first
                    def recv(self, n):
                        if self.buf: out, self.buf = self.buf[:n], self.buf[n:]; return out
                        return self.s.recv(n)
                    def sendall(self, b): self.s.sendall(b)
                def show(code): self.code = code
                pc = P.pair_responder(Pre(s, raw), self.phone_id, "SimPhone", self.sk, self.token, show, lambda n: self.allow)
                self.pcs[pc["pc_id"]] = C.b64d(pc["pub"]); return
            class Pre2:
                def __init__(self, s, first): self.s, self.buf = s, (len(first)).to_bytes(4, "big") + first
                def recv(self, n):
                    if self.buf: out, self.buf = self.buf[:n], self.buf[n:]; return out
                    return self.s.recv(n)
                def sendall(self, b): self.s.sendall(b)
            ch, pc_id = P.session_responder(Pre2(s, raw), self.phone_id, self.sk, self.pcs.get)
            last = 0
            while True:
                req = ch.recv(); rid = req.get("id")
                ps = req.get("ps", ""); ts = int(req.get("ts", 0)); meth = req.get("m", "")
                good = C.cmd_mac(C.cmd_key(self.token), int(rid), meth, ts, ps)
                if C.b64d(req.get("mac", "")) != good:
                    ch.send({"id": rid, "e": {"code": "auth_fail", "msg": "Prime token rejected"}}); break
                if abs(int(time.time() * 1000) - ts) > 120_000: ch.send({"id": rid, "e": {"code": "stale", "msg": "old"}}); continue
                if int(rid) <= last: ch.send({"id": rid, "e": {"code": "replay", "msg": "id"}}); continue
                last = int(rid)
                p = json.loads(ps) if ps else {}
                self.log.append((meth, p))
                if meth not in ALL: ch.send({"id": rid, "e": {"code": "forbidden", "msg": meth}}); continue
                if self.locked and meth not in LOCKED_OK: ch.send({"id": rid, "e": {"code": "locked", "msg": "unlock"}}); continue
                ch.send({"id": rid, "r": self._tool(meth, p)})
        except Exception:
            pass
        finally:
            try: s.close()
            except OSError: pass

    def _tool(self, m, p):
        if m == "status": return {"app": "Tether", "battery_pct": 80, "locked": self.locked}
        if m == "find_contact":
            q = p["query"].lower(); return {"contacts": [c for c in self.contacts if q in c["name"].lower()]}
        if m == "call": self.calls.append(p); return {"outcome": "dialed", "number": p["number"]}
        if m == "list_apps": return {"apps": self.apps}
        return {"ok": True, "m": m, "p": p}

"""Connection to the paired phone: connect (manual IP > last IP > mDNS), signed RPC calls."""
import itertools, json, queue, threading, time
from . import crypto as C, protocol as P, transport as T

class PhoneError(Exception):
    def __init__(self, code, msg): super().__init__(f"{code}: {msg}"); self.code = code

class PhoneClient:
    def __init__(self, store, manual_host=None, connector=None, use_mdns=True, clock=None):
        self.store, self.manual_host, self.use_mdns = store, manual_host, use_mdns
        self._connector = connector or T.lan_connect
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.ch = None; self.via = None; self._lock = threading.Lock()
        self._ids = itertools.count(int(time.time() * 1000)); self._pending = {}; self._plock = threading.Lock()
        self.last_error = None

    def device(self):
        d = self.store.device()
        if not d: raise PhoneError("not_paired", "No phone paired. On the phone: Tether > PAIR NEW PC, then run: plink pair --host <phone-ip>")
        return d

    def ensure(self):
        with self._lock:
            if self.ch: return self.ch
            dev = self.device(); errs = []
            tried = set()
            lists = [lambda: T.candidates(dev, manual=self.manual_host)]
            if self.use_mdns and not self.manual_host: lists.append(lambda: T.candidates(dev, discovered=T.browse()))
            for mk in lists:
                for host, port in mk():
                    if (host, port) in tried: continue
                    tried.add((host, port))
                    try:
                        sock = self._connector(host, port, 4)
                        ch = P.session_initiator(sock, self.store.pc_id, self.store.secret_key(), C.b64d(dev["pub"]), dev["phone_id"])
                        self.ch, self.via = ch, f"lan://{host}:{port}"
                        self.store.update_host(host, port)
                        threading.Thread(target=self._reader, args=(ch,), daemon=True).start()
                        self.last_error = None
                        return ch
                    except (OSError, P.ProtocolError, ValueError) as e:
                        errs.append(f"{host}:{port}: {e}")
            self.last_error = "; ".join(errs) or "no address known"
            raise PhoneError("unreachable", "Phone not reachable over home Wi-Fi (" + self.last_error +
                             "). Is Tether on? Same Wi-Fi? Try --host <phone-ip>.")

    def close(self):
        ch, self.ch = self.ch, None
        if ch: ch.close()

    def _reader(self, ch):
        try:
            while True:
                m = ch.recv()
                with self._plock: slot = self._pending.pop(m.get("id"), None)
                if slot: slot.put(m)
        except Exception as e:
            self.last_error = f"connection lost: {e}"
        finally:
            if self.ch is ch: self.ch = None
            ch.close()
            with self._plock:
                for slot in self._pending.values(): slot.put({"e": {"code": "disconnected", "msg": "connection lost"}})
                self._pending.clear()

    def request(self, rid, method, params):
        """Builds the signed request frame. Params travel as a JSON string so both sides MAC the exact same bytes."""
        tok = self.store.token()
        if not tok: raise PhoneError("not_paired", "no Prime token stored; pair again")
        ps = json.dumps(params or {}, separators=(",", ":"), ensure_ascii=False); ts = self.clock()
        return {"id": rid, "m": method, "ps": ps, "ts": ts, "mac": C.b64e(C.cmd_mac(C.cmd_key(tok), rid, method, ts, ps))}

    def call(self, method, params=None, timeout=20):
        for attempt in range(2):
            ch = self.ensure()
            with self._plock:
                rid = next(self._ids); slot = queue.Queue(1); self._pending[rid] = slot
                try: ch.send(self.request(rid, method, params))  # under _plock so ids hit the wire in increasing order
                except OSError:
                    self._pending.pop(rid, None); self.close(); continue
            try: m = slot.get(timeout=timeout)
            except queue.Empty:
                with self._plock: self._pending.pop(rid, None)
                raise PhoneError("timeout", f"{method} timed out")
            if "e" in m:
                if m["e"].get("code") == "disconnected" and attempt == 0: continue
                raise PhoneError(m["e"].get("code", "error"), m["e"].get("msg", ""))
            return m.get("r")
        raise PhoneError("disconnected", "connection lost")

def pair(store, host, port=T.DEFAULT_PORT, ask_code=None, connector=None):
    sock = (connector or T.lan_connect)(host, port, 6)
    sock.settimeout(180)
    try:
        dev = P.pair_initiator(sock, store.pc_id, store.name, store.secret_key(), ask_code or (lambda: input("Code shown on phone: ")))
    finally:
        sock.close()
    dev.update(host=host, port=int(port), paired_at=int(time.time()))
    store.set_device(dev)
    return {k: v for k, v in dev.items() if k not in ("token", "token_kind")}

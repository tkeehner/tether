"""Framing, pairing and session handshake (initiator = PC, responder = phone / simulator). Same flow as Emit."""
import json, os, socket, struct, threading
from . import crypto as C

VERSION = 1
MAX_FRAME = 1024 * 1024

class ProtocolError(Exception): pass

def write_frame(sock, data: bytes):
    if len(data) > MAX_FRAME: raise ProtocolError("frame too large")
    sock.sendall(struct.pack(">I", len(data)) + data)

def _read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk: raise ConnectionError("connection closed")
        buf += chunk
    return bytes(buf)

def read_frame(sock) -> bytes:
    (n,) = struct.unpack(">I", _read_exact(sock, 4))
    if n > MAX_FRAME: raise ProtocolError("frame too large")
    return _read_exact(sock, n)

def jdump(o) -> bytes: return json.dumps(o, separators=(",", ":")).encode()
def read_json(sock):
    raw = read_frame(sock); return raw, json.loads(raw)
def expect(o, t):
    if o.get("t") == "err": raise ProtocolError(o.get("msg", "peer error"))
    if o.get("t") != t: raise ProtocolError(f"expected {t}, got {o.get('t')}")
    return o

class SecureChannel:
    def __init__(self, sock, send_key, recv_key):
        self.sock = sock; self.tx = C.AeadStream(send_key); self.rx = C.AeadStream(recv_key); self._wlock = threading.Lock()
    def send(self, obj):
        with self._wlock: write_frame(self.sock, self.tx.seal(jdump(obj)))
    def recv(self): return json.loads(self.rx.open(read_frame(self.sock)))
    def close(self):
        try: self.sock.shutdown(socket.SHUT_RDWR)
        except OSError: pass
        try: self.sock.close()
        except OSError: pass

def pair_initiator(sock, pc_id, pc_name, sk_pc, ask_code):
    """Returns dict(phone_id, name, pub, token) — token is the Prime command token, unsealed with the confirm key."""
    pub_pc = C.public_key(sk_pc)
    write_frame(sock, jdump({"t": "pair1", "v": VERSION, "pc_id": pc_id, "name": pc_name, "pub": C.b64e(pub_pc)}))
    _, m2 = read_json(sock); expect(m2, "pair2")
    pub_ph = C.b64d(m2["pub"]); commit = C.b64d(m2["commit"])
    na = os.urandom(16)
    write_frame(sock, jdump({"t": "pair3", "na": C.b64e(na)}))
    _, m4 = read_json(sock); expect(m4, "pair4")
    nb = C.b64d(m4["nb"])
    if C.pair_commit(pub_ph, pub_pc, nb) != commit: raise ProtocolError("phone commitment mismatch (possible attack)")
    sas = C.pair_sas(pub_pc, pub_ph, na, nb)
    typed = "".join(ch for ch in str(ask_code()) if ch.isdigit())
    if typed != sas:
        write_frame(sock, jdump({"t": "err", "msg": "code mismatch"}))
        raise ProtocolError("code mismatch: the code you typed is not what this PC computed. Tap cancel on the phone.")
    ck = C.pair_confirm_key(C.dh(sk_pc, pub_ph), pub_pc, pub_ph, na, nb)
    write_frame(sock, jdump({"t": "pair5", "mac": C.b64e(C.hmac256(ck, b"pc-ok"))}))
    _, m6 = read_json(sock); expect(m6, "pair6")
    if C.b64d(m6["mac"]) != C.hmac256(ck, b"phone-ok"): raise ProtocolError("phone confirm MAC bad")
    token = C.open_token(ck, C.b64d(m6["tok"]))
    return {"phone_id": m2["phone_id"], "name": m2.get("name", "phone"), "pub": m2["pub"], "token": token}

def session_initiator(sock, pc_id, sk_pc, phone_pub: bytes, expect_phone_id=None) -> SecureChannel:
    prev = sock.gettimeout(); sock.settimeout(10)
    esk, epk = C.gen_keypair()
    hello = jdump({"t": "hello", "v": VERSION, "pc_id": pc_id, "e": C.b64e(epk), "n": C.b64e(os.urandom(16))})
    write_frame(sock, hello)
    raw2, m2 = read_json(sock); expect(m2, "hello_ok")
    if m2.get("v") != VERSION: raise ProtocolError(f"unsupported protocol version {m2.get('v')!r}")
    if expect_phone_id and m2.get("phone_id") != expect_phone_id: raise ProtocolError("unexpected phone id")
    th = C.transcript(hello, raw2)
    k_tx, k_rx = C.session_keys(C.dh(esk, C.b64d(m2["e"])), C.dh(sk_pc, phone_pub), th)
    ch = SecureChannel(sock, k_tx, k_rx); ch.th = th
    ch.send({"t": "auth", "th": C.b64e(th)})
    if ch.recv().get("t") != "auth_ok": raise ProtocolError("auth failed")
    sock.settimeout(prev)
    return ch

# ---------------- responder (simulated phone, tests) ----------------
def pair_responder(sock, phone_id, phone_name, sk_ph, token, show_code, allow):
    pub_ph = C.public_key(sk_ph)
    _, m1 = read_json(sock); expect(m1, "pair1")
    pub_pc = C.b64d(m1["pub"]); nb = os.urandom(16)
    write_frame(sock, jdump({"t": "pair2", "phone_id": phone_id, "name": phone_name, "pub": C.b64e(pub_ph),
                             "commit": C.b64e(C.pair_commit(pub_ph, pub_pc, nb))}))
    _, m3 = read_json(sock); expect(m3, "pair3"); na = C.b64d(m3["na"])
    write_frame(sock, jdump({"t": "pair4", "nb": C.b64e(nb)}))
    show_code(C.pair_sas(pub_pc, pub_ph, na, nb))
    _, m5 = read_json(sock); expect(m5, "pair5")
    ck = C.pair_confirm_key(C.dh(sk_ph, pub_pc), pub_pc, pub_ph, na, nb)
    if C.b64d(m5["mac"]) != C.hmac256(ck, b"pc-ok"): raise ProtocolError("pc confirm MAC bad")
    if not allow(m1.get("name", "PC")):
        write_frame(sock, jdump({"t": "err", "msg": "denied on phone"})); raise ProtocolError("denied")
    write_frame(sock, jdump({"t": "pair6", "mac": C.b64e(C.hmac256(ck, b"phone-ok")), "tok": C.b64e(C.seal_token(ck, token))}))
    return {"pc_id": m1["pc_id"], "name": m1.get("name", "PC"), "pub": m1["pub"]}

def session_responder(sock, phone_id, sk_ph, lookup_pc_pub):
    raw1, m1 = read_json(sock); expect(m1, "hello")
    if m1.get("v") != VERSION:
        write_frame(sock, jdump({"t": "err", "msg": "unsupported version"})); raise ProtocolError("bad version")
    pc_pub = lookup_pc_pub(m1.get("pc_id"))
    if pc_pub is None:
        write_frame(sock, jdump({"t": "err", "msg": "not paired"})); raise ProtocolError("unknown pc")
    esk, epk = C.gen_keypair()
    raw2 = jdump({"t": "hello_ok", "v": VERSION, "phone_id": phone_id, "e": C.b64e(epk), "n": C.b64e(os.urandom(16))})
    write_frame(sock, raw2)
    th = C.transcript(raw1, raw2)
    k_pc2ph, k_ph2pc = C.session_keys(C.dh(esk, C.b64d(m1["e"])), C.dh(sk_ph, pc_pub), th)
    ch = SecureChannel(sock, k_ph2pc, k_pc2ph)
    a = ch.recv()
    if a.get("t") != "auth" or a.get("th") != C.b64e(th): raise ProtocolError("auth expected")
    ch.send({"t": "auth_ok"})
    return ch, m1["pc_id"]

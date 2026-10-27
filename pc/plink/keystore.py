"""PC identity + paired phone. The X25519 secret and the phone's Prime command token are wrapped with Windows
DPAPI (CurrentUser). On non-Windows (tests/dev) they're base64 in a 0600 file marked 'plain'."""
import json, os, secrets, socket, sys, ctypes
from . import crypto as C

def config_dir():
    if os.environ.get("PLINK_HOME"): d = os.environ["PLINK_HOME"]
    elif sys.platform == "win32": d = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "PrimeLink-PC")
    else: d = os.path.join(os.path.expanduser("~"), ".config", "primelink-pc")
    os.makedirs(d, exist_ok=True); return d

if sys.platform == "win32":
    from ctypes import wintypes
    class _BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]
    def _blob(b):
        buf = ctypes.create_string_buffer(b, len(b)); return _BLOB(len(b), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char))), buf
    def _dpapi(data: bytes, protect: bool) -> bytes:
        crypt32, kernel32 = ctypes.windll.crypt32, ctypes.windll.kernel32
        inb, _keep = _blob(data); ent, _k2 = _blob(b"primelink-pc-v1"); out = _BLOB()
        ok = (crypt32.CryptProtectData(ctypes.byref(inb), "plink", ctypes.byref(ent), None, None, 0x1, ctypes.byref(out)) if protect
              else crypt32.CryptUnprotectData(ctypes.byref(inb), None, ctypes.byref(ent), None, None, 0x1, ctypes.byref(out)))
        if not ok: raise OSError("DPAPI failed")
        try: return ctypes.string_at(out.pbData, out.cbData)
        finally: kernel32.LocalFree(out.pbData)
    def protect(b): return "dpapi", C.b64e(_dpapi(b, True))
    def unprotect(kind, s):
        if kind != "dpapi": raise ValueError("unexpected key protection " + kind)
        return _dpapi(C.b64d(s), False)
else:
    def protect(b): return "plain", C.b64e(b)
    def unprotect(kind, s): return C.b64d(s)

class Store:
    def __init__(self, path=None):
        self.path = path or os.path.join(config_dir(), "identity.json")
        if os.path.exists(self.path):
            with open(self.path) as f: self.data = json.load(f)
        else:
            sk, _ = C.gen_keypair(); kind, blob = protect(sk)
            self.data = {"pc_id": secrets.token_hex(8), "name": socket.gethostname()[:40], "key_kind": kind, "key": blob, "devices": []}
            self.save()
        self._sk = None
    def save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f: json.dump(self.data, f, indent=1)
        try: os.chmod(tmp, 0o600)
        except OSError: pass
        os.replace(tmp, self.path)
    @property
    def pc_id(self): return self.data["pc_id"]
    @property
    def name(self): return self.data["name"]
    def secret_key(self):
        if self._sk is None: self._sk = unprotect(self.data["key_kind"], self.data["key"])
        return self._sk
    def device(self):
        ds = self.data["devices"]; return ds[0] if ds else None
    def set_device(self, dev):
        """Stores the paired phone (one phone). The token is DPAPI-wrapped; never stored or printed in the clear."""
        token = dev.pop("token"); kind, blob = protect(token)
        dev.update(token_kind=kind, token=blob)
        self.data["devices"] = [dev]; self.save()
    def token(self):
        d = self.device()
        return unprotect(d["token_kind"], d["token"]) if d and d.get("token") else None
    def remove_device(self):
        self.data["devices"] = []; self.save()
    def update_host(self, host, port):
        d = self.device()
        if d and (d.get("host"), d.get("port")) != (host, port): d["host"], d["port"] = host, port; self.save()
    def get(self, k, default=None): return self.data.get("settings", {}).get(k, default)
    def put(self, k, v): self.data.setdefault("settings", {})[k] = v; self.save()

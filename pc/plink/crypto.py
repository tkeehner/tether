"""Crypto shared with the phone (Tether app LinkCrypto.kt). Same construction as Emit's link with
Tether's own domain labels. Must stay byte-identical with the Kotlin side (tests/vectors)."""
import base64, hashlib, hmac, os, struct
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes, serialization

def b64e(b: bytes) -> str: return base64.b64encode(b).decode()
def b64d(s: str) -> bytes: return base64.b64decode(s)

def public_key(sk: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(sk).public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)

def gen_keypair():
    sk = os.urandom(32); return sk, public_key(sk)

def dh(sk: bytes, pk: bytes) -> bytes:
    out = X25519PrivateKey.from_private_bytes(sk).exchange(X25519PublicKey.from_public_bytes(pk))
    if out == b"\0" * 32: raise ValueError("low-order public key")
    return out

def sha256(*parts: bytes) -> bytes:
    h = hashlib.sha256()
    for p in parts: h.update(p)
    return h.digest()

def hkdf(ikm: bytes, salt: bytes, info: bytes, n: int) -> bytes:
    return HKDF(hashes.SHA256(), n, salt or None, info).derive(ikm)

def hmac256(key: bytes, msg: bytes) -> bytes: return hmac.new(key, msg, hashlib.sha256).digest()
def lp(b: bytes) -> bytes: return struct.pack(">I", len(b)) + b

def pair_commit(pub_ph, pub_pc, nb): return sha256(b"plink-commit-v1", pub_ph, pub_pc, nb)
def pair_sas(pub_pc, pub_ph, na, nb) -> str:
    return f"{int.from_bytes(sha256(b'plink-sas-v1', pub_pc, pub_ph, na, nb)[:4], 'big') % 1_000_000:06d}"
def pair_confirm_key(shared, pub_pc, pub_ph, na, nb):
    return hkdf(shared, sha256(b"plink-pair-v1", pub_pc, pub_ph, na, nb), b"plink-pair-confirm", 32)
def transcript(hello_pc: bytes, hello_ph: bytes) -> bytes: return sha256(b"plink-hs-v1", lp(hello_pc), lp(hello_ph))
def session_keys(ee: bytes, ss: bytes, th: bytes):
    okm = hkdf(ee + ss, th, b"plink-session-v1", 64); return okm[:32], okm[32:]

# Prime command token (made on the phone, delivered sealed inside pairing)
def _token_wrap_key(ck): return hkdf(ck, b"", b"plink-token-wrap", 32)
def seal_token(ck, token): return ChaCha20Poly1305(_token_wrap_key(ck)).encrypt(b"\0" * 12, token, None)
def open_token(ck, sealed): return ChaCha20Poly1305(_token_wrap_key(ck)).decrypt(b"\0" * 12, sealed, None)
def cmd_key(token): return hkdf(token, b"", b"plink-cmd-v1", 32)
def cmd_mac(key, rid: int, method: str, ts: int, params: str) -> bytes:
    return hmac256(key, b"plink-cmd-v1" + lp(str(rid).encode()) + lp(method.encode()) + lp(str(ts).encode()) + lp(params.encode()))

class AeadStream:
    """One direction. Nonce = 4 zero bytes || 8-byte BE counter; strictly sequential."""
    MAX = 2**63 - 1
    def __init__(self, key: bytes): self._a = ChaCha20Poly1305(key); self.counter = 0
    def _nonce(self): return b"\0\0\0\0" + struct.pack(">Q", self.counter)
    def seal(self, pt: bytes) -> bytes:
        if self.counter >= self.MAX: raise ValueError("nonce space exhausted")
        ct = self._a.encrypt(self._nonce(), pt, None); self.counter += 1; return ct
    def open(self, ct: bytes) -> bytes:
        if self.counter >= self.MAX: raise ValueError("nonce space exhausted")
        pt = self._a.decrypt(self._nonce(), ct, None); self.counter += 1; return pt

"""Writes tests/vectors/crypto_vectors.json: RFC KATs (copied from Emit's set) + Tether interop values from fixed inputs.
The Android app's CryptoVectorsTest reads the same file (copied to app/src/test/resources)."""
import json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from plink import crypto as C

H = bytes.fromhex
def build():
    d = json.load(open(os.path.join(os.path.dirname(__file__), "vectors", "rfc_kats.json")))
    sk_pc = H("a0" * 31 + "41"); sk_ph = H("b0" * 31 + "42"); e_pc = H("c0" * 31 + "43"); e_ph = H("d0" * 31 + "44")
    na = H("11" * 16); nb = H("22" * 16); token = H("33" * 32)
    pub_pc, pub_ph = C.public_key(sk_pc), C.public_key(sk_ph)
    ss = C.dh(sk_ph, pub_pc); ck = C.pair_confirm_key(ss, pub_pc, pub_ph, na, nb)
    hello = b'{"t":"hello","v":1,"pc_id":"pc1"}'; hello_ok = b'{"t":"hello_ok","v":1,"phone_id":"ph1"}'
    th = C.transcript(hello, hello_ok)
    ee = C.dh(e_pc, C.public_key(e_ph)); k1, k2 = C.session_keys(ee, ss, th)
    ck_cmd = C.cmd_key(token); ps = '{"query":"Mom","limit":8}'
    f0 = C.AeadStream(k1).seal(b'{"t":"auth"}')
    d["interop"] = {k: (v.hex() if isinstance(v, bytes) else v) for k, v in dict(
        sk_pc=sk_pc, sk_ph=sk_ph, e_pc=e_pc, e_ph=e_ph, na=na, nb=nb, token=token, pub_pc=pub_pc, pub_ph=pub_ph,
        static_shared=ss, commit=C.pair_commit(pub_ph, pub_pc, nb), sas=C.pair_sas(pub_pc, pub_ph, na, nb), confirm_key=ck,
        confirm_pc_ok=C.hmac256(ck, b"pc-ok"), confirm_phone_ok=C.hmac256(ck, b"phone-ok"), token_sealed=C.seal_token(ck, token),
        hello=hello.decode(), hello_ok=hello_ok.decode(), transcript=th, ee=ee, k_pc2ph=k1, k_ph2pc=k2, first_frame=f0,
        cmd_key=ck_cmd, cmd_id=1700000000001, cmd_method="find_contact", cmd_ts=1700000000000, cmd_ps=ps,
        cmd_mac=C.cmd_mac(ck_cmd, 1700000000001, "find_contact", 1700000000000, ps)).items()}
    return d

if __name__ == "__main__":
    out = os.path.join(os.path.dirname(__file__), "vectors", "crypto_vectors.json")
    json.dump(build(), open(out, "w"), indent=1); print("wrote", out)

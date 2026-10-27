import json, os
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from plink import crypto as C
import gen_vectors

V = json.load(open(os.path.join(os.path.dirname(__file__), "vectors", "crypto_vectors.json")))
H = bytes.fromhex

def test_rfc_kats():
    x = V["x25519_rfc7748"]
    assert C.public_key(H(x["alice_sk"])).hex() == x["alice_pk"]
    assert C.dh(H(x["alice_sk"]), H(x["bob_pk"])).hex() == x["shared"]
    a = V["chacha20poly1305_rfc8439"]
    assert ChaCha20Poly1305(H(a["key"])).encrypt(H(a["nonce"]), H(a["pt"]), H(a["aad"])).hex() == a["ct_tag"]
    k = V["hkdf_rfc5869_case1"]; assert C.hkdf(H(k["ikm"]), H(k["salt"]), H(k["info"]), k["len"]).hex() == k["okm"]
    m = V["hmac_sha256_rfc4231_case1"]; assert C.hmac256(H(m["key"]), H(m["data"])).hex() == m["mac"]

def test_vectors_file_is_current():
    assert gen_vectors.build() == V  # regenerate with tests/gen_vectors.py if crypto changes

def test_domain_separated_from_emit():
    i = V["interop"]
    # same inputs under Emit's labels must give different values (no cross-protocol key reuse)
    assert C.sha256(b"msms-commit-v1", H(i["pub_ph"]), H(i["pub_pc"]), H(i["nb"])).hex() != i["commit"]

def test_token_seal_roundtrip_and_tamper():
    i = V["interop"]; ck = H(i["confirm_key"])
    assert C.open_token(ck, H(i["token_sealed"])) == H(i["token"])
    import pytest
    bad = bytearray(H(i["token_sealed"])); bad[0] ^= 1
    with pytest.raises(Exception): C.open_token(ck, bytes(bad))

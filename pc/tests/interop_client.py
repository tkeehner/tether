"""Driven by the Android app's InteropTest: the REAL Kotlin phone protocol on one side, the real plink client here.
argv: port code_file home_dir. Prints one JSON line of results."""
import json, os, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
port, code_file, home = int(sys.argv[1]), sys.argv[2], sys.argv[3]
os.environ["PLINK_HOME"] = home
from plink import keystore, client, crypto as C
st = keystore.Store()
def ask():
    for _ in range(500):
        if os.path.exists(code_file) and open(code_file).read().strip(): return open(code_file).read().strip()
        time.sleep(0.02)
dev = client.pair(st, "127.0.0.1", port, ask_code=ask)
pc = client.PhoneClient(st, manual_host=f"127.0.0.1:{port}", use_mdns=False)
out = {"paired": dev["phone_id"], "status": pc.call("status"), "find": pc.call("find_contact", {"query": "Mom", "limit": 8})}
import queue
ch = pc.ensure(); f = pc.request(10**15, "status", {}); f["mac"] = C.b64e(bytes(32))
q = queue.Queue(); pc._pending[f["id"]] = q; ch.send(f); out["forged"] = q.get(timeout=5).get("e", {}).get("code")
print(json.dumps(out))

import http.client, json, threading, pytest
from plink import agent

class Box:
    class phone:
        @staticmethod
        def call(*a, **k): return {}
    def run(self, name, a): return {"ran": name}

def test_agent_requires_key_and_loopback(tmp_path, monkeypatch):
    monkeypatch.setenv("PLINK_HOME", str(tmp_path))
    holder = {}
    t = threading.Thread(target=agent.serve, args=(Box(), 0, lambda s: holder.setdefault("s", s)), daemon=True); t.start()
    import time
    for _ in range(100):
        if "s" in holder: break
        time.sleep(0.01)
    port = holder["s"].server_address[1]; key = agent.read_key(); assert key and len(key) > 30
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "status", "arguments": {}}})
    def post(headers):
        c = http.client.HTTPConnection("127.0.0.1", port, timeout=3); c.request("POST", "/mcp", body, headers); r = c.getresponse(); return r.status, r.read()
    assert post({"Host": "127.0.0.1"})[0] == 401
    assert post({"Host": "127.0.0.1", "Authorization": "Bearer nope"})[0] == 401
    assert post({"Host": "evil.example", "Authorization": f"Bearer {key}"})[0] == 403
    st, b = post({"Host": "127.0.0.1", "Authorization": f"Bearer {key}"})
    assert st == 200 and json.loads(b)["result"]["structuredContent"] == {"ran": "status"}
    assert agent.call_via_agent("status", {}, port=port)["structuredContent"] == {"ran": "status"}
    holder["s"].shutdown()

def test_agent_binds_loopback_only(): assert agent.HOST == "127.0.0.1" and 0 < agent.PORT < 65536

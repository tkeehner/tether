"""Warm agent: holds ONE encrypted session to the phone and serves MCP over HTTP on loopback only (port from
TETHER_AGENT_PORT; pick one that no other local service uses). CLI calls go through it so each command skips the handshake. Local callers must present the
per-install agent key (agent.key in the config dir, user-only); the Host header must be loopback."""
import hmac, http.server, json, os, secrets, socket, threading, time
from . import mcp_server, keystore

HOST = "127.0.0.1"  # loopback only, never exposed on the LAN
PORT = int(os.environ.get("TETHER_AGENT_PORT", "8700"))  # example default; set your own

def key_path(): return os.path.join(keystore.config_dir(), "agent.key")

def load_or_make_key():
    p = key_path()
    if os.path.exists(p):
        with open(p) as f: return f.read().strip()
    k = secrets.token_urlsafe(32)
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f: f.write(k)
    return k

def read_key():
    try:
        with open(key_path()) as f: return f.read().strip()
    except OSError: return None

def make_handler(box, key, lock):
    class H(http.server.BaseHTTPRequestHandler):
        server_version = "plink-agent"
        def log_message(self, *a): pass
        def _send(self, code, obj):
            b = json.dumps(obj).encode()
            self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
        def do_GET(self):
            if self.path == "/healthz": return self._send(200, {"ok": True})
            self._send(404, {"error": "not found"})
        def do_POST(self):
            host = (self.headers.get("Host") or "").split(":")[0]
            if host not in ("127.0.0.1", "localhost"): return self._send(403, {"error": "bad host"})
            auth = self.headers.get("Authorization") or ""
            if not hmac.compare_digest(auth.encode(), f"Bearer {key}".encode()): return self._send(401, {"error": "agent key required"})
            if self.path != "/mcp": return self._send(404, {"error": "not found"})
            n = int(self.headers.get("Content-Length") or 0)
            if n <= 0 or n > 256 * 1024: return self._send(413, {"error": "bad length"})
            try: msg = json.loads(self.rfile.read(n))
            except ValueError: return self._send(400, {"error": "parse error"})
            r = mcp_server.handle(box, msg)  # PhoneClient is thread-safe; a blocking call() must not stall cancel_call
            self._send(200, r or {})
    return H

def serve(box, port=PORT, ready=None):
    key = load_or_make_key(); lock = threading.Lock()
    srv = http.server.ThreadingHTTPServer((HOST, port), make_handler(box, key, lock))
    if ready: ready(srv)
    # keep the session warm: a cheap status ping every 4 minutes
    def keepalive():
        while True:
            time.sleep(240)
            try:
                box.phone.call("status", timeout=8)
            except Exception: pass
    threading.Thread(target=keepalive, daemon=True).start()
    srv.serve_forever()

def call_via_agent(name, args, port=PORT, timeout=25):
    """Returns the MCP tools/call result dict, or None if no agent is running."""
    key = read_key()
    if not key: return None
    try:
        s = socket.create_connection((HOST, port), timeout=0.5); s.close()
    except OSError: return None
    import urllib.request
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": name, "arguments": args}}).encode()
    req = urllib.request.Request(f"http://{HOST}:{port}/mcp", body, {"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(req, timeout=timeout) as r: return json.loads(r.read()).get("result")

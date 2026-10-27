"""LAN transport + mDNS (_primelink._tcp). Home Wi-Fi only: Tether v1 has no relay and no cloud hop."""
import os, socket, time

# Must match LinkService.PORT in the Android app. Example default; override with TETHER_PORT.
DEFAULT_PORT = int(os.environ.get("TETHER_PORT", "48800"))
SERVICE = "_primelink._tcp.local."

def lan_connect(host, port=DEFAULT_PORT, timeout=4.0):
    s = socket.create_connection((host, int(port)), timeout=timeout)
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    s.settimeout(None)
    return s

def browse(seconds=2.5):
    """{phone_id: "ip:port"} for Tether phones advertising on the LAN (empty if zeroconf is missing)."""
    try:
        from zeroconf import Zeroconf, ServiceBrowser
    except ImportError:
        return {}
    found = {}
    class L:
        def add_service(self, zc, t, name):
            info = zc.get_service_info(t, name, 1500)
            if not info: return
            ip4 = [a for a in info.parsed_addresses() if ":" not in a]
            if not ip4: return
            props = {k.decode(): (v or b"").decode() for k, v in (info.properties or {}).items()}
            found[props.get("id") or name] = f"{ip4[0]}:{info.port}"
        def update_service(self, *a): pass
        def remove_service(self, *a): pass
    zc = Zeroconf()
    try:
        ServiceBrowser(zc, SERVICE, L()); time.sleep(seconds)
    finally:
        zc.close()
    return found

def candidates(device, manual=None, discovered=None):
    out, seen = [], set()
    for h in [manual, (discovered or {}).get(device.get("phone_id")), device.get("host") and f"{device['host']}:{device.get('port', DEFAULT_PORT)}"]:
        if not h: continue
        host, _, port = str(h).partition(":")
        key = (host, int(port or DEFAULT_PORT))
        if key not in seen: seen.add(key); out.append(key)
    return out

"""plink CLI. Plain text by default, --json for machines (Prime runs `plink --json <tool> key=value ...`)."""
import argparse, json, sys, time
from . import __version__, tools, keystore, client, mcp_server, agent, audit, emit_bridge, transport

def make_box(store, host=None):
    phone = client.PhoneClient(store, manual_host=host)
    return audit.Audited(tools.Toolbox(phone, lambda: emit_bridge.EmitBridge(emit_bridge.load_msms_client(store))))

def coerce(tool, kv):
    props = tools.BY_NAME[tool]["inputSchema"]["properties"]; out = {}
    for item in kv:
        if "=" not in item: raise SystemExit(f"argument '{item}' must be key=value (keys: {', '.join(props) or 'none'})")
        k, v = item.split("=", 1)
        if k not in props: raise SystemExit(f"unknown key '{k}' for {tool} (keys: {', '.join(props) or 'none'})")
        t = props[k].get("type")
        if t == "integer": v = int(v)
        elif t == "boolean": v = v.lower() in ("1", "true", "yes", "on")
        elif t == "array": v = [x for x in v.split(",") if x]
        out[k] = v
    return out

def show(r, as_json):
    if as_json: print(json.dumps(r, ensure_ascii=False))
    else: print(json.dumps(r, ensure_ascii=False, indent=1))

def main(argv=None):
    ap = argparse.ArgumentParser(prog="plink", description="Tether PC companion")
    ap.add_argument("--json", action="store_true"); ap.add_argument("--host", help="phone IP (skip mDNS)")
    ap.add_argument("--direct", action="store_true", help="don't use the warm agent")
    ap.add_argument("--version", action="version", version=__version__)
    ap.add_argument("cmd", help="pair | unpair | mcp | agent | tools | config | audit | <tool>")
    ap.add_argument("args", nargs="*")
    a = ap.parse_args(argv)
    store = keystore.Store()

    if a.cmd == "pair":
        host = a.host or (a.args[0] if a.args else None)
        if not host:
            found = transport.browse(3.0); host = next(iter(found.values()), "").split(":")[0] or None
            if not host: print("Phone not found by mDNS. Use: plink pair --host <phone-ip>"); return 2
        print(f"Pairing with {host}. On the phone: Tether > PAIR NEW PC must be open.")
        dev = client.pair(store, host.split(":")[0])
        print(f"Paired with {dev['name']} ({dev['phone_id']}). Tap ALLOW on the phone if it's still asking.")
        return 0
    if a.cmd == "unpair": store.remove_device(); print("Unpaired on this PC (also tap 'unlink' on the phone)."); return 0
    if a.cmd == "tools": print(json.dumps(mcp_server.tool_list(), indent=1)); return 0
    if a.cmd == "config":
        if len(a.args) != 2 or a.args[0] not in ("msms_app",): print("usage: plink config msms_app <folder>"); return 2
        store.put(a.args[0], a.args[1]); print("saved"); return 0
    if a.cmd == "audit":
        try:
            with open(audit.path()) as f: print("".join(f.readlines()[-50:]), end="")
        except OSError: print("(empty)")
        return 0
    if a.cmd == "mcp": mcp_server.serve_stdio(make_box(store, a.host)); return 0
    if a.cmd == "agent":
        print(f"plink agent on http://{agent.HOST}:{agent.PORT}/mcp (agent key in {agent.key_path()})", file=sys.stderr)
        agent.serve(make_box(store, a.host)); return 0

    tool = a.cmd.replace("-", "_")
    if tool not in tools.BY_NAME: print(f"unknown command {a.cmd}. Tools: {', '.join(tools.BY_NAME)}"); return 2
    args = coerce(tool, a.args)
    t0 = time.time()
    res = None if (a.direct or a.host) else agent.call_via_agent(tool, args)
    if res is None: res = mcp_server.call_tool(make_box(store, a.host), tool, args)
    out = res.get("structuredContent") if "structuredContent" in res else {"error": res["content"][0]["text"]}
    if a.json and isinstance(out, dict): out = dict(out, _ms=int((time.time() - t0) * 1000))
    show(out, a.json)
    return 1 if res.get("isError") else 0

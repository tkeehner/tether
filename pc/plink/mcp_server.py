"""Minimal MCP server (JSON-RPC 2.0): stdio (newline-delimited) and a single-POST HTTP handler (agent.py)."""
import json, sys, traceback
from . import tools, __version__
from .resolve import Ambiguous

PROTOCOL_VERSIONS = ["2025-06-18", "2025-03-26", "2024-11-05"]
INSTRUCTIONS = ("Tether: native actions on the user's Android phone over home Wi-Fi (no screen control). "
                "Texts ALWAYS go through Emit (send_text) and need the user's APPROVE tap on the phone. Calls show a 3 s cancel countdown. "
                "launch_app/open_url need the phone unlocked. If a name is ambiguous you'll get choices: ask the user.")

def tool_list():
    return [{"name": t["name"], "description": t["description"], "inputSchema": t["inputSchema"],
             "annotations": {"readOnlyHint": t["readOnly"], "destructiveHint": False, "openWorldHint": not t["readOnly"], "idempotentHint": t["readOnly"]}}
            for t in tools.TOOLS]

def call_tool(box, name, args):
    try:
        r = box.run(name, args)
    except Ambiguous as e:
        r = {"error": "ambiguous", "message": str(e), "choices": e.choices}
        return {"content": [{"type": "text", "text": json.dumps(r, ensure_ascii=False)}], "structuredContent": r, "isError": True}
    except Exception as e:
        return {"content": [{"type": "text", "text": f"Error: {e}"}], "isError": True}
    return {"content": [{"type": "text", "text": json.dumps(r, ensure_ascii=False, indent=1)}], "structuredContent": r}

def handle(box, msg):
    mid, method, params = msg.get("id"), msg.get("method"), msg.get("params") or {}
    if mid is None: return None
    def ok(r): return {"jsonrpc": "2.0", "id": mid, "result": r}
    if method == "initialize":
        v = params.get("protocolVersion")
        return ok({"protocolVersion": v if v in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0], "capabilities": {"tools": {"listChanged": False}},
                   "serverInfo": {"name": "tether", "version": __version__}, "instructions": INSTRUCTIONS})
    if method == "ping": return ok({})
    if method == "tools/list": return ok({"tools": tool_list()})
    if method == "tools/call": return ok(call_tool(box, params.get("name"), params.get("arguments") or {}))
    return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"method not found: {method}"}}

def serve_stdio(box, inp=None, out=None):
    inp = inp or sys.stdin; out = out or sys.stdout
    for line in inp:
        line = line.strip()
        if not line: continue
        try: msg = json.loads(line)
        except ValueError:
            out.write(json.dumps({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}}) + "\n"); out.flush(); continue
        msgs = msg if isinstance(msg, list) else [msg]; resps = []
        for m in msgs:
            try: r = handle(box, m)
            except Exception as e:
                traceback.print_exc(file=sys.stderr); r = {"jsonrpc": "2.0", "id": m.get("id"), "error": {"code": -32603, "message": str(e)}}
            if r: resps.append(r)
        if resps: out.write(json.dumps(resps if isinstance(msg, list) else resps[0]) + "\n"); out.flush()

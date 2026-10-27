"""The Tether tool catalog (MCP + CLI). Each tool maps to one native phone action (or Emit, for texts).
The phone re-validates everything (actions/Policy.kt); this layer resolves names and gives Prime clear errors."""
from . import resolve as R

def _s(props=None, req=None):
    return {"type": "object", "properties": props or {}, "required": req or [], "additionalProperties": False}
STR = {"type": "string"}; INT = {"type": "integer"}; BOOL = {"type": "boolean"}

TOOLS = [
 {"name": "status", "description": "Phone battery, network, ringer/DND, locked?, call state, Tether permissions and link transport.",
  "inputSchema": _s(), "readOnly": True},
 {"name": "find_contact", "description": "Look up contacts by name on the phone. Returns names and numbers.",
  "inputSchema": _s({"query": STR, "limit": INT}, ["query"]), "readOnly": True},
 {"name": "send_text", "description": "Text someone THROUGH EMIT (the user's only texting route): stages a draft in that Emit thread (creating the thread if needed) and asks the user to APPROVE on the phone. Never sends by itself. Only use after the user asked for this exact text.",
  "inputSchema": _s({"to": {"type": "string", "description": "contact name or phone number"}, "text": STR}, ["to", "text"]), "readOnly": False},
 {"name": "text_status", "description": "Status of a text staged with send_text (awaiting, sent, rejected, cancelled, failed).",
  "inputSchema": _s({"draft_id": INT}, ["draft_id"]), "readOnly": True},
 {"name": "cancel_text", "description": "Cancel a staged text that hasn't been approved yet.",
  "inputSchema": _s({"draft_id": INT}, ["draft_id"]), "readOnly": False},
 {"name": "call", "description": "Call a contact or number. The phone shows a 3-second 'Calling… CANCEL' countdown, then dials. Works while locked. Returns outcome dialed / cancelled.",
  "inputSchema": _s({"to": {"type": "string", "description": "contact name or phone number"}}, ["to"]), "readOnly": False},
 {"name": "cancel_call", "description": "Cancel a call countdown before it dials.", "inputSchema": _s(), "readOnly": False},
 {"name": "hang_up", "description": "End the current phone call (also cancels a pending countdown).", "inputSchema": _s(), "readOnly": False},
 {"name": "call_state", "description": "idle / ringing / in_call.", "inputSchema": _s(), "readOnly": True},
 {"name": "set_alarm", "description": "Set an alarm in the phone's Clock app without opening it. time like '7:30', '19:05' or '7:30am'; days optional e.g. ['mon','wed'].",
  "inputSchema": _s({"time": STR, "label": STR, "days": {"type": "array", "items": STR}, "vibrate": BOOL}, ["time"]), "readOnly": False},
 {"name": "set_timer", "description": "Start a countdown timer in the Clock app.",
  "inputSchema": _s({"seconds": INT, "minutes": INT, "label": STR}), "readOnly": False},
 {"name": "read_notifications", "description": "Current notifications from all apps (title, text, app, key). Text is returned to you, never stored on the phone or PC.",
  "inputSchema": _s({"limit": INT, "package": STR}), "readOnly": True},
 {"name": "dismiss_notification", "description": "Dismiss one notification by key (from read_notifications) or all clearable ones.",
  "inputSchema": _s({"key": STR, "all": BOOL}), "readOnly": False},
 {"name": "list_apps", "description": "Launchable apps (label + package).", "inputSchema": _s(), "readOnly": True},
 {"name": "launch_app", "description": "Open an app by name or package. Needs the phone unlocked.",
  "inputSchema": _s({"app": STR}, ["app"]), "readOnly": False},
 {"name": "open_url", "description": "Open an http/https link on the phone. Needs the phone unlocked.",
  "inputSchema": _s({"url": STR}, ["url"]), "readOnly": False},
 # v1.1 preview
 {"name": "flashlight", "description": "Torch on/off.", "inputSchema": _s({"on": BOOL}, ["on"]), "readOnly": False},
 {"name": "media", "description": "Control what's playing: play, pause, toggle, next, previous, stop.",
  "inputSchema": _s({"action": {"type": "string", "enum": ["play", "pause", "toggle", "next", "previous", "stop"]}}, ["action"]), "readOnly": False},
 {"name": "volume", "description": "Set a volume stream (media, ring, alarm, notification) to 0-100%.",
  "inputSchema": _s({"stream": {"type": "string", "enum": ["media", "ring", "alarm", "notification"]}, "level": INT}, ["stream", "level"]), "readOnly": False},
]
BY_NAME = {t["name"]: t for t in TOOLS}

def _int(a, k, lo, hi, default=None):
    v = a.get(k, default)
    if v is None: raise ValueError(f"'{k}' is required")
    try: v = int(v)
    except (TypeError, ValueError): raise ValueError(f"'{k}' must be an integer")
    if not lo <= v <= hi: raise ValueError(f"'{k}' must be {lo}..{hi}")
    return v

class Toolbox:
    """phone = PhoneClient (Tether). emit = factory returning an EmitBridge (lazy: only texting needs msms)."""
    def __init__(self, phone, emit_factory=None):
        self.phone, self._emit_factory, self._emit = phone, emit_factory, None
    def emit(self):
        if self._emit is None:
            if not self._emit_factory: raise RuntimeError("texting needs the Emit PC companion (msms)")
            self._emit = self._emit_factory()
        return self._emit

    def _resolve_number(self, to):
        to = str(to or "").strip()
        if not to: raise ValueError("'to' is required")
        if R.looks_like_number(to): return None, R.digits(to)
        res = self.phone.call("find_contact", {"query": to, "limit": 8})
        who, number = R.pick_number(res.get("contacts", []), to)
        return who, R.digits(number)

    def run(self, name, a):
        a = a or {}
        if name not in BY_NAME: raise ValueError(f"unknown tool {name}")
        allowed = set(BY_NAME[name]["inputSchema"]["properties"])
        extra = set(a) - allowed
        if extra: raise ValueError(f"unexpected argument(s): {sorted(extra)}")
        p = self.phone
        if name == "status":
            r = p.call("status"); r["transport"] = p.via; return r
        if name == "find_contact":
            q = str(a.get("query", "")).strip()
            if not q: raise ValueError("'query' is required")
            return p.call("find_contact", {"query": q[:80], "limit": _int(a, "limit", 1, 20, 8)})
        if name == "send_text": return self.emit().send_text(a.get("to"), a.get("text"))
        if name == "text_status": return self.emit().draft_status(_int(a, "draft_id", 0, 2**62))
        if name == "cancel_text": return self.emit().cancel(_int(a, "draft_id", 0, 2**62))
        if name == "call":
            who, number = self._resolve_number(a.get("to"))
            params = {"number": number}
            if who: params["name"] = who
            return p.call("call", params, timeout=15)
        if name in ("cancel_call", "hang_up", "call_state", "list_apps"): return p.call(name)
        if name == "set_alarm":
            h, m = R.parse_time(a.get("time"))
            params = {"hour": h, "minute": m, "vibrate": bool(a.get("vibrate", True))}
            if a.get("label"): params["label"] = str(a["label"])[:60]
            d = R.parse_days(a.get("days"))
            if d: params["days"] = d
            return p.call("set_alarm", params)
        if name == "set_timer":
            sec = int(a.get("seconds") or 0) + 60 * int(a.get("minutes") or 0)
            if not 1 <= sec <= 86400: raise ValueError("timer must be 1 second to 24 hours")
            params = {"seconds": sec}
            if a.get("label"): params["label"] = str(a["label"])[:60]
            return p.call("set_timer", params)
        if name == "read_notifications":
            params = {"limit": _int(a, "limit", 1, 100, 20)}
            if a.get("package"): params["package"] = str(a["package"])
            return p.call("read_notifications", params)
        if name == "dismiss_notification":
            if a.get("all"): return p.call("dismiss_notification", {"all": True})
            if not a.get("key"): raise ValueError("give 'key' or all=true")
            return p.call("dismiss_notification", {"key": str(a["key"])})
        if name == "launch_app":
            q = str(a.get("app", "")).strip()
            if not q: raise ValueError("'app' is required")
            app = R.pick_app(p.call("list_apps").get("apps", []), q)
            r = p.call("launch_app", {"package": app["package"]}); r["label"] = app["label"]; return r
        if name == "open_url":
            u = str(a.get("url", "")).strip()
            if not (u.lower().startswith("http://") or u.lower().startswith("https://")): raise ValueError("only http/https URLs")
            return p.call("open_url", {"url": u})
        if name == "flashlight": return p.call("flashlight", {"on": bool(a.get("on"))})
        if name == "media": return p.call("media", {"action": str(a.get("action", ""))})
        if name == "volume": return p.call("volume", {"stream": str(a.get("stream", "")), "level": _int(a, "level", 0, 100)})
        raise ValueError(f"unhandled tool {name}")

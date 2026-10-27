"""Texting goes through Emit ONLY (the user's rule): find/create the thread, stage a draft, ask for approval on the
phone. This module never approves or sends. Uses the msms PC companion's own pairing with Emit (same Windows user)."""
import os, sys
from . import resolve as R

DEFAULT_MSMS_APP = os.path.join(os.path.expanduser("~"), "Apps", "MatrixSMS-PC", "app")  # override: PLINK_MSMS_APP

def load_msms_client(store):
    """Import msms from the installed Emit PC companion and open a client with its identity (read-only use of its files)."""
    path = os.environ.get("PLINK_MSMS_APP") or store.get("msms_app") or DEFAULT_MSMS_APP
    if path and os.path.isdir(path) and path not in sys.path: sys.path.insert(0, path)
    try:
        from msms import keystore as MK, client as MC
    except ImportError as e:
        raise RuntimeError(f"Emit PC companion (msms) not found at {path}. Set it with: plink config msms_app <folder containing msms\\>") from e
    return MC.PhoneClient(MK.Store())

class EmitBridge:
    def __init__(self, emit_client):
        self.emit = emit_client

    def resolve_thread(self, to):
        """-> (thread_id, display, number_or_None). 'to' is a name or a number."""
        to = str(to or "").strip()
        if not to: raise ValueError("'to' is required (a contact name or a phone number)")
        if R.looks_like_number(to):
            number = R.digits(to)
        else:
            res = self.emit.call("contacts", {"query": to}) or {}
            contacts = {}
            for c in res.get("contacts", []):
                contacts.setdefault(c["name"], {"name": c["name"], "numbers": []})["numbers"].append({"number": c["number"]})
            _, number = R.pick_number(list(contacts.values()), to)
        try:
            t = self.emit.call("thread_for", {"number": number})
        except Exception as e:
            if "unknown method" in str(e):
                raise RuntimeError("Emit on the phone is older than 0.1.48 (no thread_for). Update Emit, or text someone with an existing thread.") from e
            raise
        return t["thread_id"], t.get("name") or number, number

    def send_text(self, to, text):
        body = str(text or "").strip()
        if not body: raise ValueError("'text' is required")
        if len(body) > 2000: raise ValueError("text longer than 2000 characters")
        tid, who, number = self.resolve_thread(to)
        d = self.emit.call("create_draft", {"thread_id": int(tid), "body": body})["draft"]
        r = self.emit.call("request_send", {"draft_id": int(d["draft_id"])})["draft"]
        return {"via": "emit", "to": who, "thread_id": tid, "draft_id": r["draft_id"], "status": r.get("status"),
                "note": "Staged in Emit. Nothing is sent until the user taps APPROVE + SEND on the phone."}

    def draft_status(self, draft_id):
        ds = (self.emit.call("list_drafts", {"status": "all"}) or {}).get("drafts", [])
        d = next((x for x in ds if int(x.get("draft_id", -1)) == int(draft_id)), None)
        if not d: raise LookupError(f"draft {draft_id} not found")
        return {"draft_id": d["draft_id"], "status": d.get("status"), "to": d.get("to")}

    def cancel(self, draft_id):
        return self.emit.call("cancel_draft", {"draft_id": int(draft_id)})

import pytest
from plink.emit_bridge import EmitBridge
from plink import tools

class FakeEmit:
    def __init__(self, old=False): self.calls = []; self.old = old
    def call(self, m, p=None):
        self.calls.append((m, p))
        if m == "contacts": return {"contacts": [{"name": "Mom", "number": "+15555550100"}]} if "mom" in p["query"].lower() else {"contacts": []}
        if m == "thread_for":
            if self.old: raise Exception("bad_request: unknown method thread_for")
            return {"thread_id": 42, "number": p["number"], "name": "Mom"}
        if m == "create_draft": return {"draft": {"draft_id": 7, "thread_id": p["thread_id"], "status": "pending"}}
        if m == "request_send": return {"draft": {"draft_id": p["draft_id"], "status": "awaiting"}}
        if m == "list_drafts": return {"drafts": [{"draft_id": 7, "status": "sent", "to": ["+15555550100"]}]}
        if m == "cancel_draft": return {"draft": {"draft_id": p["draft_id"], "status": "cancelled"}}
        raise AssertionError("unexpected " + m)

def test_text_goes_through_emit_and_waits_for_approval():
    e = FakeEmit(); r = EmitBridge(e).send_text("Mom", "I love you")
    assert r["via"] == "emit" and r["status"] == "awaiting" and r["draft_id"] == 7
    assert [c[0] for c in e.calls] == ["contacts", "thread_for", "create_draft", "request_send"]
    assert not any(c[0] in ("approve_send", "send") for c in e.calls)

def test_new_number_creates_thread():
    e = FakeEmit(); EmitBridge(e).send_text("(555) 555-0199", "hi")
    assert e.calls[0] == ("thread_for", {"number": "5555550199"})

def test_old_emit_explains_update():
    with pytest.raises(RuntimeError, match="0.1.48"): EmitBridge(FakeEmit(old=True)).send_text("Mom", "hi")

def test_unknown_contact_and_empty_text():
    with pytest.raises(LookupError): EmitBridge(FakeEmit()).send_text("Nobody", "hi")
    with pytest.raises(ValueError): EmitBridge(FakeEmit()).send_text("Mom", "  ")

def test_toolbox_send_text_never_touches_phone_link():
    class NoPhone:
        def call(self, *a, **k): raise AssertionError("texting must not use the Tether phone channel")
    e = FakeEmit(); box = tools.Toolbox(NoPhone(), lambda: EmitBridge(e))
    assert box.run("send_text", {"to": "Mom", "text": "hey"})["via"] == "emit"
    assert box.run("text_status", {"draft_id": 7})["status"] == "sent"

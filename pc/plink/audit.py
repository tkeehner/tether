"""PC-side audit: one line per tool call with coarse metadata only (never text bodies, notification text or numbers)."""
import json, os, time
from . import keystore

def path(): return os.path.join(keystore.config_dir(), "audit.log")

def record(tool, outcome, ms):
    line = json.dumps({"t": int(time.time()), "tool": tool, "outcome": outcome, "ms": ms})
    try:
        with open(path(), "a") as f: f.write(line + "\n")
        if os.path.getsize(path()) > 512 * 1024:  # keep it small: trim to the last ~half
            with open(path()) as f: lines = f.readlines()
            with open(path(), "w") as f: f.writelines(lines[len(lines) // 2:])
    except OSError: pass

class Audited:
    """Wraps a Toolbox so every run() is logged."""
    def __init__(self, box): self.box = box; self.phone = box.phone
    def run(self, name, a):
        t0 = time.time()
        try:
            r = self.box.run(name, a); record(name, "ok", int((time.time() - t0) * 1000)); return r
        except Exception as e:
            record(name, type(e).__name__ + ":" + str(getattr(e, "code", ""))[:20], int((time.time() - t0) * 1000)); raise

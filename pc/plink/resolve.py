"""Pure helpers: pick a contact/number/app from what the phone returned. Ambiguity is an error with choices,
so Prime asks the user instead of guessing."""
import re

class Ambiguous(ValueError):
    def __init__(self, msg, choices): super().__init__(msg); self.choices = choices

def looks_like_number(s: str) -> bool:
    t = re.sub(r"[\s\-().]", "", s or "")
    return bool(re.fullmatch(r"\+?\d{3,15}", t))

# Pause / wait / extension separators a saved contact number may carry, e.g. "1-800-555-1234,,5#":
# ',' (pause) ';' (wait) 'p'/'w' (pause/wait as some phones store them), '#' '*' (tone/extension markers),
# and 'x' / 'ext' (typed extensions like "555-1234 x12").
# Choice: cut at the first separator and dial ONLY the base number (option b). We cannot pass the full
# string through for Android to handle (option a): the phone's Policy.normalizeNumber rejects * # , ; on
# purpose (blocks USSD/MMI codes), and stripping the separators, as this used to do, glued the extension
# onto the base number ("180055512345") and dialed the wrong digits. Truncating never dials wrong digits;
# the worst case is the user types the extension themselves. A number that starts with a separator (e.g. "*67...")
# yields "" here, which the phone rejects, so nothing is dialed.
_EXT_SEP = re.compile(r"[,;#*pPwWxX]|ext", re.IGNORECASE)

def base_number(s):
    """Everything before the first pause/wait/extension separator (unnormalized)."""
    s = s or ""
    m = _EXT_SEP.search(s)
    return s[:m.start()] if m else s

def has_extension(s): return base_number(s) != (s or "")

def digits(s): return re.sub(r"[^\d+]", "", base_number(s))

def pick_number(contacts, query):
    """contacts = phone find_contact result list [{name, numbers:[{number,type,primary}]}]. Returns (name, number)."""
    if not contacts: raise LookupError(f"no contact matches '{query}'")
    q = query.strip().lower()
    if len(contacts) > 1:
        exact = [c for c in contacts if c.get("name", "").strip().lower() == q]
        if len(exact) == 1: contacts = exact
        else:
            raise Ambiguous(f"'{query}' matches {len(contacts)} contacts; say which one",
                            [{"name": c.get("name"), "numbers": [n["number"] for n in c.get("numbers", [])]} for c in contacts[:8]])
    c = contacts[0]; nums = c.get("numbers") or []
    if not nums: raise LookupError(f"{c.get('name')} has no phone number")
    uniq = {digits(n["number"])[-10:]: n for n in nums}
    nums = list(uniq.values())
    if len(nums) == 1: return c.get("name"), nums[0]["number"]
    prim = [n for n in nums if n.get("primary")]
    if len(prim) == 1: return c.get("name"), prim[0]["number"]
    mob = [n for n in nums if str(n.get("type", "")).lower() == "mobile"]
    if len(mob) == 1: return c.get("name"), mob[0]["number"]
    raise Ambiguous(f"{c.get('name')} has {len(nums)} numbers; say which one",
                    [{"name": c.get("name"), "number": n["number"], "type": n.get("type")} for n in nums])

def pick_app(apps, query):
    """apps = [{package,label}]. Accepts a package name or a label (exact, then prefix, then substring)."""
    q = query.strip().lower()
    for a in apps:
        if a["package"].lower() == q: return a
    for test in (lambda l: l == q, lambda l: l.startswith(q), lambda l: q in l):
        hits = [a for a in apps if test(a["label"].strip().lower())]
        if len(hits) == 1: return hits[0]
        if len(hits) > 1: raise Ambiguous(f"'{query}' matches {len(hits)} apps", hits[:10])
    raise LookupError(f"no app matches '{query}'")

def parse_time(s):
    """'7:30', '07:30', '7:30pm', '19:05', '7pm' -> (hour, minute)."""
    m = re.fullmatch(r"\s*(\d{1,2})(?::(\d{2}))?\s*([ap]\.?m\.?)?\s*", (s or "").lower())
    if not m: raise ValueError("time must look like 7:30, 19:05 or 7:30pm")
    h, mi, ap = int(m.group(1)), int(m.group(2) or 0), m.group(3)
    if ap:
        if not 1 <= h <= 12: raise ValueError("12-hour time needs hour 1-12")
        h = (h % 12) + (12 if ap.startswith("p") else 0)
    if not (0 <= h <= 23 and 0 <= mi <= 59): raise ValueError("bad time")
    return h, mi

DAYS = {"sun": 1, "mon": 2, "tue": 3, "wed": 4, "thu": 5, "fri": 6, "sat": 7}
def parse_days(v):
    if v is None or v == []: return None
    out = []
    for d in v:
        k = DAYS.get(str(d).strip().lower()[:3]) if not isinstance(d, int) else d
        if k not in range(1, 8): raise ValueError("days: use sun..sat")
        if k not in out: out.append(k)
    return out

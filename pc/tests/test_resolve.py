import pytest
from plink import resolve as R

def test_time():
    assert R.parse_time("7:30") == (7, 30); assert R.parse_time("7:30pm") == (19, 30); assert R.parse_time("12am") == (0, 0)
    assert R.parse_time("12:05 p.m.") == (12, 5)
    for bad in ("25:00", "7:75", "13pm", "noon"):
        with pytest.raises(ValueError): R.parse_time(bad)

def test_days(): assert R.parse_days(["Mon", "friday", "mon"]) == [2, 6]

def test_numbers():
    assert R.looks_like_number("+1 (555) 555-0100") and not R.looks_like_number("Mom") and not R.looks_like_number("*#06#")
    c = [{"name": "A", "numbers": [{"number": "1", "type": "Work"}, {"number": "2", "type": "Mobile"}]}]
    assert R.pick_number(c, "A") == ("A", "2")
    c = [{"name": "A", "numbers": [{"number": "555-555-0100"}, {"number": "+15555550100"}]}]
    assert R.pick_number(c, "A")[1] in ("555-555-0100", "+15555550100")  # same number twice collapses
    two = [{"name": "Mom", "numbers": [{"number": "1"}]}, {"name": "Mom Work", "numbers": [{"number": "2"}]}]
    assert R.pick_number(two, "mom") == ("Mom", "1")
    with pytest.raises(R.Ambiguous): R.pick_number([{"name": "Ann B", "numbers": [{"number": "1"}]}, {"name": "Ann C", "numbers": [{"number": "2"}]}], "ann")
    with pytest.raises(LookupError): R.pick_number([], "x")

def test_apps():
    apps = [{"package": "com.android.chrome", "label": "Chrome"}, {"package": "com.x.a", "label": "Chrome Beta"}]
    assert R.pick_app(apps, "chrome")["package"] == "com.android.chrome"
    assert R.pick_app(apps, "com.x.a")["label"] == "Chrome Beta"
    with pytest.raises(LookupError): R.pick_app(apps, "zzz")

def test_numbers_with_extensions_dial_base_only():
    # the audit case: extension digits must never be glued onto the base number
    assert R.digits("1-800-555-1234,,5#") == "18005551234"
    assert R.has_extension("1-800-555-1234,,5#")
    assert R.digits("555-555-0100;123") == "5555550100"
    assert R.digits("(555) 555-0100 x12") == "5555550100"
    assert R.digits("555-555-0100 ext. 7") == "5555550100"
    assert R.digits("*67 555-555-0100") == ""  # leading code -> empty, phone refuses; never a wrong dial

def test_plain_and_international_numbers_unchanged():
    assert R.digits("(555) 555-0100") == "5555550100" and not R.has_extension("(555) 555-0100")
    assert R.digits("+1 555-555-0100") == "+15555550100"
    assert R.digits("+44 20 7946 0958") == "+442079460958"

def test_p_w_pauses():
    assert R.digits("5555550100p123") == "5555550100"
    assert R.digits("555-555-0100W45") == "5555550100"
    assert R.digits("+1 555 555 0100 p 2 w 3") == "+15555550100"

def test_pick_number_dedupes_on_base():
    c = [{"name": "Desk", "numbers": [{"number": "1-800-555-1234,,5#"}]}]
    name, num = R.pick_number(c, "Desk")
    assert R.digits(num) == "18005551234"

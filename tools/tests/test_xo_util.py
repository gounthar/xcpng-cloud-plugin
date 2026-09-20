"""Guards on the two helpers both XO harnesses share, chiefly on the poll.

XO's object cache lags a write. A xenstore key set through either backend is absent from
an immediate read and present a second or so later, so a single read-back turns a
successful write into an apparent failure, which is what it did the first time the
comparison ran, on both backends at once. It read as the API being broken rather than as
the reader being early.

That makes `poll` the load-bearing function in this repository's XO tooling: it stands
between a measured comparison and a page of confident wrong numbers. It also has to be
able to give up, or a genuine failure becomes a hang on a lab pool with a VM already made.

It lives in `xo_util` rather than in a harness because it had two homes and only one of
them was kept current: `xo_compare` polled and `xo_probe` read once, so the probe reported
a successful seed as a failed one for as long as both files existed.
"""

import types

import pytest

import xo_util
from xo_util import as_list, poll


@pytest.fixture
def no_sleep(monkeypatch):
    """Fake the sleep, so the timeout cases cost nothing and cannot flake under load."""
    slept = []
    monkeypatch.setattr(xo_util.time, "sleep", slept.append)
    return slept


def test_a_value_already_present_is_returned_without_sleeping(no_sleep):
    """The common case, and the one that must not add a second to every measured step."""
    satisfied, waited = poll(lambda: {"k": "v"}, lambda d: "k" in d)
    assert satisfied is True
    assert no_sleep == [], "it slept before looking"
    assert waited >= 0


def test_it_keeps_reading_until_the_write_becomes_visible(no_sleep):
    """The whole reason this function exists. A single read returns the stale cache and
    reports a write that succeeded as a write that did not happen."""
    reads = iter([{}, {}, {"k": "v"}])
    satisfied, _ = poll(lambda: next(reads), lambda d: "k" in d, interval=0)
    assert satisfied is True
    assert len(no_sleep) == 2, "it stopped at the first read, or slept after finding the value"


def test_it_gives_up_and_says_so_rather_than_raising(monkeypatch):
    """A genuine failure has to end the step. Reporting False is what lets a caller say
    "cannot judge the scrub without seeing the key first" instead of hanging on the lab.

    The clock is faked rather than waited out: a real 20s timeout in a unit test is 20s of
    CI on every run, and a shortened one measures the shortening rather than the loop.
    """
    ticks = iter([0.0, 0.0, 5.0, 10.0, 20.0, 20.0])
    slept = []
    monkeypatch.setattr(xo_util, "time",
                        types.SimpleNamespace(monotonic=lambda: next(ticks), sleep=slept.append))
    reads = []
    satisfied, waited = poll(lambda: reads.append(1) or {}, lambda d: "k" in d,
                             timeout=15.0, interval=0)
    assert satisfied is False
    assert waited == 20.0, "the reported wait is not the wait that happened"
    assert len(reads) == 3, "it stopped early, or kept reading past its own deadline"


def test_it_does_not_read_at_all_once_the_budget_is_gone(no_sleep):
    """A spent budget must not buy one free read: that read comes from the stale cache
    anyway, and letting it through makes a timeout indistinguishable from a result."""
    reads = []
    satisfied, _ = poll(lambda: reads.append(1) or {"k": "v"}, lambda d: "k" in d, timeout=0)
    assert satisfied is False
    assert reads == []


def test_a_predicate_looking_for_an_absence_works_the_same_way(no_sleep):
    """The scrub half polls for a key going away, and teardown polls for a VM going away.
    The delete direction was the one observed visible immediately, which is exactly why it
    must not be the only direction anybody tested."""
    reads = iter([{"k": "v"}, {}])
    satisfied, _ = poll(lambda: next(reads), lambda d: "k" not in d, interval=0)
    assert satisfied is True


@pytest.mark.parametrize(
    "objs, expected, why",
    [
        ({"a": 1, "b": 2}, [1, 2], "getAllObjects answers a map keyed by id"),
        ([1, 2], [1, 2], "and a list when a filter narrows to a collection"),
        (None, [], "a null result is not iterable; list(None) raises"),
        ({}, [], "an empty map"),
        ([], [], "an empty list"),
    ],
)
def test_as_list_normalises_every_shape_the_api_answers_with(objs, expected, why):
    assert as_list(objs) == expected, why


def test_a_sleep_never_outlasts_the_budget_it_is_sleeping_inside(monkeypatch):
    """`poll(timeout=20, interval=60)` took one failed read and then waited a full minute.
    The budget in the signature and the time actually spent were different numbers, and
    the caller was handed the first. An interval longer than the budget is not a silly
    call: it is what a caller writes when it wants one slow retry.

    The fake sleep advances the fake clock, rather than a fixed list of ticks standing in
    for both. A list would make this a test of the list: get the entries wrong and it
    fails against correct code, get them wrong the other way and it passes against the
    bug, and neither failure says which.
    """
    import types

    class Clock:
        def __init__(self):
            self.now = 0.0
            self.slept = []

        def monotonic(self):
            return self.now

        def sleep(self, seconds):
            self.slept.append(seconds)
            self.now += seconds

    clock = Clock()
    monkeypatch.setattr(xo_util, "time",
                        types.SimpleNamespace(monotonic=clock.monotonic, sleep=clock.sleep))
    satisfied, waited = poll(lambda: {}, lambda d: "k" in d, timeout=20.0, interval=60.0)

    assert satisfied is False
    assert clock.slept == [20.0], f"slept {clock.slept} against a 20s budget"
    assert waited == 20.0, "it overran the budget it reported"


def test_a_normal_interval_is_left_alone(monkeypatch):
    """The clamp must not shorten an interval that already fits, or a 0.5s poll becomes a
    busy loop against a live appliance the moment the budget gets low."""
    import types

    class Clock:
        def __init__(self):
            self.now = 0.0
            self.slept = []

        def monotonic(self):
            return self.now

        def sleep(self, seconds):
            self.slept.append(seconds)
            self.now += seconds

    clock = Clock()
    monkeypatch.setattr(xo_util, "time",
                        types.SimpleNamespace(monotonic=clock.monotonic, sleep=clock.sleep))
    poll(lambda: {}, lambda d: "k" in d, timeout=2.0, interval=0.5)
    assert clock.slept == [0.5, 0.5, 0.5, 0.5], f"the clamp altered a fitting interval: {clock.slept}"


# -- env_flag: the affirmative, in one place rather than two ----------------

@pytest.mark.parametrize("value", ["1", "true", "TRUE", "Yes", "yes"])
def test_an_affirmative_is_recognised_in_any_case(monkeypatch, value):
    monkeypatch.setenv("XO_PROBE_FLAG", value)
    assert xo_util.env_flag("XO_PROBE_FLAG") is True


@pytest.mark.parametrize("value", ["0", "no", "", "off", "false", "2", "sure"])
def test_anything_else_fails_closed(monkeypatch, value):
    """The trap this function exists for: plain truthiness makes a non-empty string true,
    so `=0` would enable the insecure behaviour the variable is meant to gate. "sure" and
    "2" are in the list on purpose -- a value meant as a yes still fails closed, which is
    the direction that costs a puzzled minute rather than a credential."""
    monkeypatch.setenv("XO_PROBE_FLAG", value)
    assert xo_util.env_flag("XO_PROBE_FLAG") is False


def test_an_unset_variable_takes_the_default(monkeypatch):
    monkeypatch.delenv("XO_PROBE_FLAG", raising=False)
    assert xo_util.env_flag("XO_PROBE_FLAG") is False
    assert xo_util.env_flag("XO_PROBE_FLAG", default=True) is True


# -- transport_refusal: may the token go down this URL? ---------------------

@pytest.mark.parametrize("url, secure", [
    ("https://xo.invalid", "https"),
    ("https://192.168.1.5:443/rest/v0", "https"),
    ("wss://xo.invalid/api/", "wss"),
    ("WSS://xo.invalid/api/", "wss"),
])
def test_an_encrypted_address_is_allowed_silently(url, secure, capsys):
    """The control. Three refusals below mean nothing without a case that passes: a
    function that refused everything would satisfy every negative test here."""
    assert xo_util.transport_refusal(url, secure) is None
    assert capsys.readouterr().err == "", "a verified address must not print a warning"


@pytest.mark.parametrize("url, secure", [
    ("http://xo.invalid", "https"), ("ws://xo.invalid/api/", "wss"),
])
def test_cleartext_is_refused_by_default(monkeypatch, url, secure):
    monkeypatch.delenv(xo_util.ALLOW_CLEARTEXT, raising=False)
    refusal = xo_util.transport_refusal(url, secure)
    assert refusal is not None
    assert xo_util.ALLOW_CLEARTEXT in refusal, "the refusal must name the way past it"


@pytest.mark.parametrize("url, secure", [
    ("http://xo.invalid", "https"), ("ws://xo.invalid/api/", "wss"),
])
def test_cleartext_can_be_opted_into_and_says_so_every_time(monkeypatch, capsys, url, secure):
    """Opt-in rather than hard refusal, matching XO_TRUST_SELF_SIGNED. The warning is the
    half that justifies the choice: a tool that cannot be told "yes, I mean it" gets
    worked around by editing the source, and the edit takes the warning with it."""
    monkeypatch.setenv(xo_util.ALLOW_CLEARTEXT, "1")
    assert xo_util.transport_refusal(url, secure) is None
    err = capsys.readouterr().err
    assert xo_util.ALLOW_CLEARTEXT in err and url in err


def test_the_explicit_argument_beats_the_environment(monkeypatch):
    monkeypatch.setenv(xo_util.ALLOW_CLEARTEXT, "1")
    assert xo_util.transport_refusal("http://xo.invalid", "https", allow=False) is not None


@pytest.mark.parametrize("url, why", [
    ("192.168.1.5", "no scheme at all: urlopen answers this with ValueError, not OSError"),
    ("//192.168.1.5/rest/v0", "a protocol-relative URL names no scheme either"),
    ("ftp://xo.invalid", "a scheme neither client speaks"),
])
def test_an_address_that_is_neither_encrypted_nor_cleartext_is_refused(monkeypatch, url, why):
    """And refused even with the opt-in set: XO_ALLOW_CLEARTEXT says the token may go out
    in the clear, not that any string is an address."""
    monkeypatch.setenv(xo_util.ALLOW_CLEARTEXT, "1")
    assert xo_util.transport_refusal(url, "https") is not None, why


def test_a_refusal_never_carries_the_token():
    """It is handed a URL, so it cannot leak the token -- but the refusal is printed and
    logged by callers that hold one, and this is the assertion that stays true if someone
    later passes the client in to make the message friendlier."""
    refusal = xo_util.transport_refusal("http://xo.invalid", "https", allow=False)
    assert "authenticationToken" not in refusal


# -- the encrypted scheme the OTHER client speaks is still wrong ------------

@pytest.mark.parametrize("url, secure, why", [
    ("wss://xo.invalid/api/", "https", "the REST client cannot open a WebSocket URL"),
    ("https://xo.invalid", "wss", "and create_connection cannot dial an https:// one"),
])
def test_the_other_clients_encrypted_scheme_is_refused(monkeypatch, url, secure, why):
    """Encrypted is not the same as usable, and a check keyed on "is it TLS" waves each
    client past the other's address. The token would be safe and the call would fail
    somewhere unrelated: `urlopen` raising "unknown url type" for the first, and
    create_connection reaching for a socket it will not get for the second.

    Raised on #234 by review. The first version of this function asked only whether the
    scheme was encrypted, which is the question the security finding suggested and not
    the question the client needs answered."""
    monkeypatch.setenv(xo_util.ALLOW_CLEARTEXT, "1")
    refusal = xo_util.transport_refusal(url, secure)
    assert refusal is not None, why
    assert f"{secure}://" in refusal, "the refusal must name the scheme this client wants"


def test_a_secure_scheme_neither_client_speaks_is_a_programming_error():
    """A caller typo is not an operator mistake, so it raises rather than returning a
    refusal an operator would then be shown."""
    with pytest.raises(ValueError, match="ftps"):
        xo_util.transport_refusal("https://xo.invalid", "ftps")

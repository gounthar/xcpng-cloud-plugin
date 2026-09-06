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

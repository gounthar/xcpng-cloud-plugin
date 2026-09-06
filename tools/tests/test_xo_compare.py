"""Guards on the comparison harness, chiefly on the poll that makes its readings honest.

XO's object cache lags a write. A xenstore key set through either backend is absent from
an immediate read and present a second or so later, so a single read-back turns a
successful write into an apparent failure -- which is what it did the first time this
comparison ran, on both backends at once, and read as the API being broken rather than as
the reader being early.

That makes `poll` the load-bearing function here: it is what stands between a measured
comparison and a page of confident wrong numbers. It also has to be able to give up, or a
genuine failure becomes a hang.
"""

import types

import pytest

import xo_compare
from fakes import FakeXo
from xo_compare import Result, as_list, cleanup, poll, summary


@pytest.fixture(autouse=True)
def empty_created(monkeypatch):
    monkeypatch.setattr(xo_compare, "CREATED", [])
    return xo_compare


@pytest.fixture
def no_sleep(monkeypatch):
    """Fake the clock and the sleep, so the timeout cases cost nothing and cannot flake."""
    slept = []
    monkeypatch.setattr(xo_compare.time, "sleep", slept.append)
    return slept


# -- poll -------------------------------------------------------------------

def test_a_value_already_present_is_returned_without_sleeping(no_sleep):
    """The common case, and the one that must not add a second to every measured step."""
    satisfied, waited = poll(lambda: {"k": "v"}, lambda d: "k" in d)
    assert satisfied is True
    assert no_sleep == [], "it slept before looking"
    assert waited >= 0


def test_it_keeps_reading_until_the_write_becomes_visible(no_sleep):
    """The whole reason this function exists. A single read here returns the stale cache
    and reports a write that succeeded as a write that did not happen."""
    reads = iter([{}, {}, {"k": "v"}])
    satisfied, _ = poll(lambda: next(reads), lambda d: "k" in d, interval=0)
    assert satisfied is True
    assert len(no_sleep) == 2, "it stopped at the first read, or slept after finding the value"


def test_it_gives_up_and_says_so_rather_than_raising(no_sleep, monkeypatch):
    """A genuine failure has to end the step. Reporting False is what lets the caller say
    "cannot judge the scrub without seeing the key first" instead of hanging on the lab."""
    ticks = iter([0.0, 0.0, 5.0, 10.0, 20.0, 20.0])
    monkeypatch.setattr(xo_compare, "time",
                        types.SimpleNamespace(monotonic=lambda: next(ticks), sleep=no_sleep.append))
    reads = []
    satisfied, waited = poll(lambda: reads.append(1) or {}, lambda d: "k" in d,
                             timeout=15.0, interval=0)
    assert satisfied is False
    assert waited == 20.0, "the reported wait is not the wait that happened"
    assert len(reads) == 3, "it stopped early, or kept reading past its own deadline"


def test_it_does_not_read_at_all_once_the_budget_is_gone(no_sleep):
    """A zero or negative budget must not buy one free read: that read is the difference
    between a timeout and a result, and it would come from the stale cache anyway."""
    reads = []
    satisfied, _ = poll(lambda: reads.append(1) or {"k": "v"}, lambda d: "k" in d, timeout=0)
    assert satisfied is False
    assert reads == []


def test_a_predicate_looking_for_an_absence_works_the_same_way(no_sleep):
    """The scrub half polls for a key going away. Both directions go through here, and the
    delete direction was the one observed to be visible immediately -- which is exactly why
    it must not be the only one anybody tested."""
    reads = iter([{"k": "v"}, {}])
    satisfied, _ = poll(lambda: next(reads), lambda d: "k" not in d, interval=0)
    assert satisfied is True


# -- cleanup ----------------------------------------------------------------

def test_cleanup_routes_each_id_to_the_backend_that_made_it(empty_created):
    """Two backends, one pool. Deleting a REST-made VM through JSON-RPC would work by
    accident here and mask a real id-shape difference on the day it stops working."""
    empty_created.CREATED.extend([("rest", "vm-rest"), ("jsonrpc", "vm-jrpc")])
    xo, rest = FakeXo(), FakeXo()
    assert cleanup(xo, rest) == 0
    assert rest.deleted == ["vm-rest"]
    assert xo.deleted == ["vm-jrpc"]
    assert empty_created.CREATED == []


def test_cleanup_touches_nothing_when_nothing_was_created(empty_created):
    xo, rest = FakeXo(), FakeXo()
    assert cleanup(xo, rest) == 0
    assert (xo.deleted, rest.deleted) == ([], [])


def test_one_stuck_vm_does_not_strand_the_others(empty_created, capsys):
    """Cleanup reports and never raises: a raise here would skip the baseline comparison,
    which is the only thing that notices a leak."""
    empty_created.CREATED.extend([("rest", "vm-a"), ("rest", "vm-stuck"), ("rest", "vm-c")])
    xo, rest = FakeXo(), FakeXo()
    rest.stuck = {"vm-stuck"}

    assert cleanup(xo, rest) == 1
    assert rest.deleted == ["vm-a", "vm-c"]
    assert empty_created.CREATED == [("rest", "vm-stuck")]
    assert "no marker-based sweep" in capsys.readouterr().err.lower()


# -- summary ----------------------------------------------------------------

def test_a_path_that_died_partway_prints_a_dash_rather_than_crashing(capsys):
    """The comparison is most worth reading on the run where one backend failed. Formatting
    a missing timing as a float would take the harness down at the moment it has something
    to say."""
    partial = Result("REST")
    partial.steps = {"create": 1.016}
    partial.notes = ["requires a BARE template uuid"]

    summary([partial])
    out = capsys.readouterr().out
    assert "1.016s" in out
    assert "-" in out
    assert "BARE template uuid" in out


def test_both_backends_appear_side_by_side(capsys):
    a, b = Result("REST"), Result("JSON-RPC")
    a.steps = {"create": 1.016, "delete": 0.618}
    b.steps = {"create": 1.219, "delete": 0.613}
    summary([a, b])
    out = capsys.readouterr().out
    assert "REST" in out and "JSON-RPC" in out
    assert "1.016s" in out and "1.219s" in out


# -- as_list ----------------------------------------------------------------

@pytest.mark.parametrize(
    "objs, expected",
    [({"a": 1}, [1]), ([1, 2], [1, 2]), (None, []), ({}, []), ([], [])],
)
def test_as_list_normalises_every_shape_the_api_answers_with(objs, expected):
    assert as_list(objs) == expected

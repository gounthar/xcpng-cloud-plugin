"""Guards on the XO probe's controls, which are the only reason its findings mean anything.

The probe reports on a live appliance, so nearly every assertion it makes is about an
absence: a key that is gone, a VM that is no longer there, a method that is not offered.
An absence seen by an instrument that cannot detect a presence is not evidence, and three
of this project's worst results came from exactly that. So `controls()` proves the probe
can see a presence and can fail, before anything downstream is believed, and exits 2
rather than 0 when it cannot.

The negatives below are only meaningful next to `the_controls_pass_when_everything_holds`,
which proves controls() can say yes at all. Four refusals from a function that refuses
everything is not a control, it is a stuck gate.
"""

import pytest

from fakes import FakeXo
from xo_probe import ControlFailed, as_list, cleanup, controls

TEMPLATE = "jenkins-agent-debian13-v7"
FOUND = {TEMPLATE: [{"id": "pool/uuid-1", "uuid": "uuid-1"}]}


@pytest.fixture(autouse=True)
def empty_created(monkeypatch):
    """CREATED is module state that cleanup() reads. Reset it around every test, or one
    test's leftovers become another's, which is the kind of coupling that hides a bug in
    exactly the function whose job is to not touch things it did not create."""
    import xo_probe

    monkeypatch.setattr(xo_probe, "CREATED", [])
    return xo_probe


# -- controls() -------------------------------------------------------------

def test_the_controls_pass_when_everything_holds():
    """The control on the controls. Without it the four refusals below are satisfied by a
    controls() that has stopped letting anything through."""
    template = controls(FakeXo(templates=FOUND), TEMPLATE)
    assert template["uuid"] == "uuid-1"


def test_an_appliance_offering_no_methods_stops_the_run():
    """listMethods returning nothing means the probe cannot see a presence at all, so
    every absence it goes on to report is uninterpretable."""
    with pytest.raises(ControlFailed, match="listMethods"):
        controls(FakeXo(methods=[], templates=FOUND), TEMPLATE)


def test_an_appliance_that_answers_yes_to_everything_stops_the_run():
    """A bogus method must raise. If it succeeds, so would every check below it, and the
    whole run would be a page of PASS lines about nothing."""
    with pytest.raises(ControlFailed, match="bogus"):
        controls(FakeXo(templates=FOUND, bogus_succeeds=True), TEMPLATE)


@pytest.mark.parametrize(
    "found, why",
    [
        ([], "no template by that name: everything downstream would have nothing to clone"),
        ([{"uuid": "a"}, {"uuid": "b"}], "two: the run would clone whichever came first"),
    ],
)
def test_a_template_that_does_not_resolve_to_exactly_one_stops_the_run(found, why):
    with pytest.raises(ControlFailed, match="expected exactly 1"):
        controls(FakeXo(templates={TEMPLATE: found}), TEMPLATE)


def test_a_filter_that_matches_a_name_that_cannot_exist_stops_the_run():
    """The discrimination control. A filter matching everything finds the template too,
    so the check above would pass while proving nothing about the filter."""
    everything = FakeXo(templates=FOUND)
    everything.resolve_template = lambda name: [{"uuid": "uuid-1"}]
    with pytest.raises(ControlFailed, match="does not exist"):
        controls(everything, TEMPLATE)


# -- cleanup() --------------------------------------------------------------

def test_cleanup_touches_nothing_when_nothing_was_created(empty_created):
    """CREATED is appended to only after a create has actually returned, so a dry run and
    a run that failed before creating anything must both leave the pool alone."""
    xo = FakeXo()
    assert cleanup(xo) == 0
    assert xo.deleted == []


def test_cleanup_only_ever_deletes_what_this_run_created(empty_created):
    """The reaper cannot help here: an XO-made VM carries no other_config owner marker, so
    it is invisible to a marker-based sweep and this list is the only record there is."""
    empty_created.CREATED.extend(["vm-mine-1", "vm-mine-2"])
    xo = FakeXo()
    assert cleanup(xo) == 0
    assert xo.deleted == ["vm-mine-1", "vm-mine-2"]
    assert empty_created.CREATED == []


def test_a_vm_that_will_not_delete_is_reported_and_kept_on_the_list(empty_created, capsys):
    """One stuck VM must not stop the ones behind it being removed, and must not be
    counted as gone: the exit code is what tells an operator to go and look."""
    empty_created.CREATED.extend(["vm-a", "vm-stuck", "vm-c"])
    xo = FakeXo()
    xo.stuck = {"vm-stuck"}

    assert cleanup(xo) == 1
    assert xo.deleted == ["vm-a", "vm-c"], "it gave up at the first failure"
    assert empty_created.CREATED == ["vm-stuck"], "a VM still on the pool was struck off the list"
    assert "vm-stuck" in capsys.readouterr().err


# -- as_list ----------------------------------------------------------------

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

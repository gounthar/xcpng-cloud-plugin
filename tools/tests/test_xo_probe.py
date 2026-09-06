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

import itertools

import pytest

from fakes import FakeXo
from xo_probe import (ControlFailed, Failed, as_list, check_boot, check_owner_tag, check_q1,
                      check_q3, cleanup, controls, is_link_local)

TEMPLATE = "jenkins-agent-debian13-v7"
FOUND = {TEMPLATE: [{"id": "pool/uuid-1", "uuid": "uuid-1"}]}


@pytest.fixture(autouse=True)
def fast_clock(monkeypatch):
    """Compress xo_util's clock so a 20s poll budget costs no wall time.

    Every poll in these tools carries a real-time budget, and spending it for real is 20
    seconds of CI per failure case. The step is 1.0s rather than something huge because
    the passing cases need several reads inside the budget: a coarse clock would exhaust
    it in one iteration and turn every "it polls" test into "it read once", which is the
    behaviour these tests exist to reject.
    """
    import types

    import xo_util

    clock = itertools.count(0.0, 1.0)
    monkeypatch.setattr(xo_util, "time",
                        types.SimpleNamespace(monotonic=lambda: next(clock), sleep=lambda *_: None))


@pytest.fixture(autouse=True)
def no_boot_sleep(monkeypatch):
    """check_boot keeps its own real clock, so only its sleep needs silencing."""
    import xo_probe

    monkeypatch.setattr(xo_probe.time, "sleep", lambda *_: None)


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


# -- check_q1: the seed and the #28 scrub -----------------------------------

class XenstoreXo(FakeXo):
    """A VM whose xenstore reads lag the writes, the way the real appliance behaves.

    `lag` is how many reads return the previous state before the write shows up. It
    defaults to 1 rather than 0 on purpose: a fixture that answered correctly on the first
    read would let a single-read implementation pass, and answering correctly on the first
    read is the one thing the appliance was measured not to do.
    """

    def __init__(self, lag=1, honour_writes=True):
        super().__init__()
        self.lag = lag
        self.honour_writes = honour_writes
        self.data = {}
        self._pending = None
        self._left = 0

    def set_xenstore(self, vm_id, data):
        if not self.honour_writes:
            return
        merged = dict(self.data)
        for k, v in data.items():
            merged.pop(k, None) if v is None else merged.update({k: v})
        self._pending, self._left = merged, self.lag

    def get_objects(self, filter_=None, limit=None):
        if self._pending is not None:
            if self._left <= 0:
                self.data, self._pending = self._pending, None
            else:
                self._left -= 1
        return [{"id": "vm-1", "xenStoreData": dict(self.data)}]


def test_the_seed_and_the_scrub_are_polled_not_read_once(capsys):
    """XO's object cache lags a write, so a read taken straight after the set finds
    nothing. This function read once until 2026-09-06 while xo_compare polled, so it
    reported a successful seed as a failed one. The fixture lags by design."""
    check_q1(XenstoreXo(lag=2), "vm-1")
    out = capsys.readouterr().out
    assert "PASS" in out and "#28 scrub" in out


def test_a_seed_that_never_lands_is_a_failure(capsys):
    """The positive control has a negative twin, or the polling above is satisfied by a
    fixture that always says yes eventually."""
    with pytest.raises(Failed, match="never appeared"):
        check_q1(XenstoreXo(honour_writes=False), "vm-1")


def test_a_scrub_that_does_not_remove_the_key_is_a_failure(capsys):
    """The #28 claim is that a null value deletes exactly that key. A write that leaves it
    in place must fail the run, not print a line."""
    class Sticky(XenstoreXo):
        def set_xenstore(self, vm_id, data):
            if any(v is None for v in data.values()):
                return  # accepts the delete and does nothing, the worst shape
            super().set_xenstore(vm_id, data)

    with pytest.raises(Failed, match="survived a null write"):
        check_q1(Sticky(), "vm-1")


# -- check_boot: a timeout has to fail the run ------------------------------

class BootingXo(FakeXo):
    def __init__(self, address=None):
        super().__init__()
        self.address = address
        self.calls = []

    def call(self, method, params=None, **kwargs):
        self.calls.append(method)
        return None

    def get_objects(self, filter_=None, limit=None):
        return [{"id": "vm-1", "mainIpAddress": self.address}]


def test_an_address_that_arrives_is_reported(capsys):
    xo = BootingXo(address="192.168.1.42")
    assert check_boot(xo, "vm-1", wait=0.1) == "192.168.1.42"
    assert "vm.stop" in xo.calls


def test_a_boot_that_never_reports_an_address_fails_the_run(capsys):
    """It used to print FAIL and return, leaving main() at exit 0. A --boot run that never
    got an address reported success to whatever read the exit code, which on this project
    is the whole point of having one."""
    xo = BootingXo(address=None)
    with pytest.raises(Failed, match="no mainIpAddress"):
        check_boot(xo, "vm-1", wait=0.1)


def test_the_vm_is_stopped_even_when_the_address_never_arrives(capsys):
    """The failure path still has to tear down what it started, or a failed probe leaves a
    running VM behind and the cleanup that follows has more to do than it expects."""
    xo = BootingXo(address=None)
    with pytest.raises(Failed):
        check_boot(xo, "vm-1", wait=0.1)
    assert "vm.stop" in xo.calls, "the failure path skipped the stop"


# -- check_q3: untrack only once the VM is confirmed gone -------------------

class DeletingXo(FakeXo):
    def __init__(self, survives=False):
        super().__init__()
        self.survives = survives

    def get_objects(self, filter_=None, limit=None):
        return [{"id": "vm-1"}] if self.survives else []


def test_a_confirmed_delete_untracks_the_vm(empty_created, capsys):
    empty_created.CREATED.append("vm-1")
    check_q3(DeletingXo(), "vm-1")
    assert empty_created.CREATED == []


def test_a_vm_that_survives_its_delete_stays_on_the_cleanup_list(empty_created, capsys):
    """The other order struck the id off the only list that knows about it and then
    raised, so cleanup() could not retry and a VM that survived its own delete was left on
    the pool with nothing able to find it: an XO-made VM carries no owner marker."""
    empty_created.CREATED.append("vm-1")
    with pytest.raises(Failed, match="still present"):
        check_q3(DeletingXo(survives=True), "vm-1")
    assert empty_created.CREATED == ["vm-1"], "cleanup can no longer reach the leaked VM"


# -- check_owner_tag: the one the unit tests missed and the pool caught -----

class TaggingXo(FakeXo):
    """A VM whose tag reads lag its tag writes, which is what the pool does.

    `lag` defaults to 1 for the same reason XenstoreXo's does: a fixture that answered
    correctly on the first read would let a single-read implementation pass, and answering
    correctly on the first read is precisely what the appliance was measured not to do.

    Measured 2026-09-06 on the lab pool: tag.add returned, and the tag was not on the
    object when read straight back. check_owner_tag read once and reported a working
    tag.add as broken, which is a false negative on the owner marker, the only handle any
    sweep has on an XO-made VM. xo_compare polled here and this file did not.
    """

    def __init__(self, lag=1, honour_writes=True):
        super().__init__()
        self.lag = lag
        self.honour_writes = honour_writes
        self.tags = []
        self._pending = None
        self._left = 0

    def _stage(self, tags):
        self._pending, self._left = list(tags), self.lag

    def add_tag(self, vm_id, tag):
        if self.honour_writes:
            self._stage(self.tags + [tag])

    def remove_tag(self, vm_id, tag):
        if self.honour_writes:
            self._stage([t for t in self.tags if t != tag])

    def get_objects(self, filter_=None, limit=None):
        if self._pending is not None:
            if self._left <= 0:
                self.tags, self._pending = self._pending, None
            else:
                self._left -= 1
        return [{"id": "vm-1", "tags": list(self.tags)}]


def test_the_owner_tag_is_polled_in_both_directions(capsys):
    """The failure the pool found. A single read after tag.add sees the pre-write state
    and calls a working tag broken."""
    check_owner_tag(TaggingXo(lag=2), "vm-1")
    out = capsys.readouterr().out
    assert out.count("PASS") == 2, out


def test_a_tag_that_never_lands_is_still_a_failure(capsys):
    """Polling must not turn the check into one that always passes eventually. The owner
    marker is the only handle a sweep has on an XO-made VM, since XoVm carries no
    other_config, so a tag that silently did not stick is a leak waiting to happen."""
    with pytest.raises(Failed, match="never appeared"):
        check_owner_tag(TaggingXo(honour_writes=False), "vm-1")


def test_a_tag_that_will_not_come_off_is_a_failure(capsys):
    """The remove direction needs its own negative, or it is pinned only by the add."""
    class Sticky(TaggingXo):
        def remove_tag(self, vm_id, tag):
            return None

    xo = Sticky()
    xo.tags = ["xcpng-cloud:xo-probe"]
    with pytest.raises(Failed, match="did not remove"):
        check_owner_tag(xo, "vm-1")


# -- a link-local address is not an answer ----------------------------------

@pytest.mark.parametrize(
    "address, link_local, why",
    [
        ("fe80::cd1c:16b1:f447:6874", True, "measured on the pool, reported as mainIpAddress"),
        ("FE80::1", True, "the same, upper case"),
        ("fe80::1%eth0", True, "with a scope id, which is a shape XO can return"),
        # The rest of fe80::/10. A prefix test on the literal "fe80:" is a /16 and calls
        # every one of these routable, which is what the first version of this function
        # did while its own docstring said /10. The first version of this test only fed it
        # fe80::, so the fixture agreed with the narrower behaviour and neither could see
        # the other was wrong.
        ("fe90::1", True, "still fe80::/10"),
        ("fea0::1", True, "still fe80::/10"),
        ("feb0::1", True, "still fe80::/10"),
        ("febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff", True, "the last address in the range"),
        ("fec0::1", False, "one past the end: site-local, deprecated, and not link-local"),
        ("169.254.13.7", True, "IPv4 autoconfiguration, which means DHCP did not answer"),
        ("169.254.0.0", True, "the bottom of that range"),
        ("169.255.0.1", False, "one past it"),
        ("192.168.1.152", False, "the address the same clone got 60s later"),
        ("2a01:e0a:96c:c250:69f3:fb6c:a93c:40a2", False, "a global IPv6 is routable"),
        ("10.0.0.1", False, "private, but reachable, which is all this needs to be"),
        ("", False, "an empty value is not an address and is not this function's problem"),
        ("nonsense", False, "nor is a string the appliance should never have sent"),
        (None, False, "nor is None, which mainIpAddress is until the guest reports"),
    ],
)
def test_link_local_is_recognised_across_the_whole_range(address, link_local, why):
    assert is_link_local(address) is link_local, why


class AddressXo(BootingXo):
    """Hands out a sequence of addresses, one per read, the way a booting guest does."""

    def __init__(self, sequence):
        super().__init__()
        self.sequence = list(sequence)

    def get_objects(self, filter_=None, limit=None):
        value = self.sequence.pop(0) if self.sequence else None
        return [{"id": "vm-1", "mainIpAddress": value}]


def test_a_link_local_address_is_skipped_and_the_real_one_taken(capsys):
    """MEASURED 2026-09-06: one clone reported fe80:: at 25.3s. A caller taking the first
    non-empty mainIpAddress gets an address nothing can connect to, and reports it as a
    pass, which is this probe doing the exact thing it exists to catch."""
    xo = AddressXo([None, "fe80::cd1c:16b1:f447:6874", "fe80::cd1c:16b1:f447:6874",
                    "192.168.1.152"])
    assert check_boot(xo, "vm-1", wait=30) == "192.168.1.152"
    assert "ignoring link-local" in capsys.readouterr().out


def test_a_clone_that_only_ever_gets_a_link_local_address_fails(capsys):
    """Otherwise the skip turns into a hang that ends in a pass on the next lucky read."""
    xo = AddressXo(["fe80::1"] * 200)
    with pytest.raises(Failed, match="no mainIpAddress"):
        check_boot(xo, "vm-1", wait=0.1)


def test_a_cache_that_flickers_empty_does_not_abort_the_tag_poll(capsys):
    """`as_list(...)[0]` inside a poll's read is not a read that fails, it is an
    IndexError that propagates out and stops the poll retrying. The lag this polls for
    makes a transient empty reply normal, so the subscript has to be the safe kind."""
    class Flickering(TaggingXo):
        def __init__(self):
            super().__init__(lag=0)
            self.reads = 0

        def get_objects(self, filter_=None, limit=None):
            self.reads += 1
            if self.reads in (1, 3):
                return []
            return super().get_objects(filter_, limit)

    check_owner_tag(Flickering(), "vm-1")
    assert capsys.readouterr().out.count("PASS") == 2

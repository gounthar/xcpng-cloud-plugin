"""Guards on the comparison harness's step functions, which is where its bugs actually were.

The first version of this file tested `poll`, `cleanup`, `summary` and `as_list`, and left
`run_jsonrpc` and `run_rest` untouched. Both bot reviewers then found four defects, all of
them inside those two functions: a scrub whose failure was logged and ignored, a tag doing
the same, an id struck off the cleanup list before the delete was confirmed, and an id put
on it before it was known to be an id at all.

That is the lesson worth keeping from this file. Testing the helpers a function calls says
nothing about the function, and the untested half is exactly where the reading happens
that turns a failed check into a reported success.

`poll` and `as_list` moved to `xo_util`; their tests moved with them.
"""

import itertools

import pytest

import xo_compare
from fakes import FakeXo
from xo import XoError
from xo_compare import Result, cleanup, confirm_gone, create_or_recover, summary
from xo_rest import XoRestError

TEMPLATE = {"id": "pool/uuid-1", "uuid": "uuid-1"}


@pytest.fixture(autouse=True)
def empty_created(monkeypatch):
    """CREATED is module state. Reset it around every test, or one test's leftovers become
    another's, which hides a bug in the very function whose job is to not touch what it
    did not create."""
    monkeypatch.setattr(xo_compare, "CREATED", [])
    return xo_compare


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


class CreatingXo(FakeXo):
    """A FakeXo whose create can return any shape, raise, and answer a recovery lookup."""

    def template_vifs(self, template):
        return [{"network": "net-1"}]

    def __init__(self, result=None, raises=None, resolves=()):
        super().__init__()
        self.result = result
        self.raises = raises
        self.resolves = list(resolves)
        self.created = []

    def create_from_template(self, template_id, name_label, clone=True, **extra):
        self.created.append(name_label)
        if self.raises is not None:
            raise self.raises
        return self.result

    def resolve_vm(self, name_label):
        return list(self.resolves)


# -- create_or_recover ------------------------------------------------------

def test_a_normal_create_is_tracked_and_timed(empty_created):
    """The control. Every refusal below is satisfied by a function that refuses
    everything, and a create path that never creates is not a harness."""
    xo = CreatingXo(result={"id": "vm-1"})
    vm_id, elapsed = create_or_recover(xo, "tmpl", "xo-cmp-jrpc-1", "jsonrpc")
    assert vm_id == "vm-1"
    assert elapsed >= 0
    assert empty_created.CREATED == [("jsonrpc", "vm-1")]


@pytest.mark.parametrize(
    "result, why",
    [
        (None, "vm.create answered null"),
        ({}, "an object with no id"),
        ({"id": ""}, "an empty id, which is falsy and was tracked all the same"),
        ("", "a bare empty string"),
    ],
)
def test_an_unusable_result_is_not_put_on_the_cleanup_list(empty_created, result, why):
    """Tracking before validating put None on the list. cleanup() then tried to delete
    None, while the real VM, if the call had made one, went untracked."""
    with pytest.raises(XoError) as caught:
        create_or_recover(CreatingXo(result=result), "tmpl", "n", "jsonrpc")
    assert caught.value.message == "CREATE_FAILED", why
    assert empty_created.CREATED == [], why


def test_a_timed_out_create_goes_looking_for_what_it_may_have_made(empty_created):
    """`vm.create` has no server-side timeout, so a TIMEOUT means our patience ran out and
    not that the appliance declined. The VM may exist, it carries no tag yet, and an
    XO-made VM has no other_config for the reaper, so the name is the only handle. Giving
    up without looking leaves a VM nothing in this repository can find."""
    xo = CreatingXo(raises=XoError("TIMEOUT", "vm.create did not answer within 120s"),
                    resolves=[{"id": "vm-orphan"}])
    with pytest.raises(XoError) as caught:
        create_or_recover(xo, "tmpl", "xo-cmp-jrpc-1", "jsonrpc")
    assert caught.value.message == "CREATE_TIMEOUT_RECOVERED"
    assert empty_created.CREATED == [("jsonrpc", "vm-orphan")], "the orphan was not tracked"


@pytest.mark.parametrize(
    "resolves, why",
    [
        ([], "nothing by that name: the call really did not create anything, or cannot be seen"),
        ([{"id": "a"}, {"id": "b"}], "two by that name: deleting either would be a guess"),
    ],
)
def test_a_timeout_that_cannot_be_resolved_to_one_vm_is_inconclusive(empty_created, resolves, why):
    """Not the same as a clean failure, and it must not be reported as one. Two matches is
    the case where acting is worse than reporting: cleanup would destroy a coin flip."""
    xo = CreatingXo(raises=XoError("TIMEOUT", "..."), resolves=resolves)
    with pytest.raises(XoError) as caught:
        create_or_recover(xo, "tmpl", "n", "jsonrpc")
    assert caught.value.message == "CREATE_TIMEOUT_UNRESOLVED", why
    assert empty_created.CREATED == [], why


def test_a_failure_that_is_not_a_timeout_is_not_swallowed(empty_created):
    """Recovery is scoped to the one error where the VM may exist anyway. Widening it
    would send a name lookup after every genuine refusal and rename the error."""
    xo = CreatingXo(raises=XoError("NO_SUCH_TEMPLATE", "tmpl"))
    with pytest.raises(XoError) as caught:
        create_or_recover(xo, "tmpl", "n", "jsonrpc")
    assert caught.value.message == "NO_SUCH_TEMPLATE"


# -- confirm_gone -----------------------------------------------------------

def test_a_vm_that_disappears_is_confirmed(empty_created):
    reads = iter([[{"id": "vm-1"}], []])
    assert confirm_gone(lambda: next(reads), "vm-1") >= 0


def test_a_vm_that_survives_its_own_delete_raises(empty_created):
    """A delete is not done when the call returns, only when the object stops being there.
    Without this the harness struck the id off the cleanup list on the strength of a
    return value and left the VM on the pool.

    The fast_clock fixture is what keeps `confirm_gone`'s 20s budget from being spent for
    real on every run.
    """
    reads = []
    with pytest.raises(XoError) as caught:
        confirm_gone(lambda: reads.append(1) or [{"id": "vm-1"}], "vm-1")
    assert caught.value.message == "STILL_PRESENT"
    assert reads, "it never actually looked"


# -- cleanup ----------------------------------------------------------------

def test_cleanup_routes_each_id_to_the_backend_that_made_it(empty_created, capsys):
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
    """The comparison is most worth reading on the run where one backend failed.
    Formatting a missing timing as a float would take the harness down at the moment it
    has something to say."""
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


# -- the two raises that turn a logged failure into a failed run ------------

def test_the_error_types_stay_per_backend(empty_created):
    """main() catches (XoError, XoRestError) and marks the path failed. A raise of the
    wrong family on the REST path would escape that handler and take the whole run down
    before cleanup, which is how a leak gets left behind by a failure handler."""
    assert issubclass(XoRestError, RuntimeError) and issubclass(XoError, RuntimeError)
    assert not issubclass(XoRestError, XoError)


# -- run_jsonrpc and run_rest, the functions the helpers above are called from ----
#
# The first round of these tests covered create_or_recover and confirm_gone and stopped
# there, and three mutations walked straight through: a scrub failure logged and ignored,
# a tag failure logged and ignored, and a delete never confirmed. Testing the helpers a
# function calls says nothing about the function. This is that lesson applied a second
# time in the same file, which is roughly how often it needs applying.


class Pool:
    """A tiny in-memory VM store both fakes read and write.

    One store behind both backends on purpose: the REST and JSON-RPC paths are meant to be
    doing the same thing, and two independently hand-tuned mocks would let them drift
    while both suites stayed green. The three flags break one operation at a time in a way
    that answers success and does nothing, which is the shape that made these bugs
    invisible: a call that raised would have been noticed years ago.
    """

    def __init__(self, scrub=True, tag=True, delete=True):
        self.vms = {}
        self.scrub_works, self.tag_works, self.delete_works = scrub, tag, delete
        self.n = 0

    def create(self, name):
        self.n += 1
        vm_id = f"vm-{self.n}"
        self.vms[vm_id] = {"id": vm_id, "name_label": name, "type": "VM",
                           "$VBDs": ["vbd-1"], "xenStoreData": {}, "tags": []}
        return vm_id

    def set_xenstore(self, vm_id, data):
        held = self.vms[vm_id]["xenStoreData"]
        for key, value in data.items():
            if value is None:
                if self.scrub_works:
                    held.pop(key, None)
            else:
                held[key] = value

    def add_tag(self, vm_id, tag):
        if self.tag_works:
            self.vms[vm_id]["tags"].append(tag)

    def delete(self, vm_id):
        if self.delete_works:
            self.vms.pop(vm_id, None)

    def get(self, vm_id):
        vm = self.vms.get(vm_id)
        return [vm] if vm else []


class PoolXo:
    def __init__(self, pool):
        self.pool = pool
        self.creates = []

    def create_from_template(self, tid, name_label, clone=True, **extra):
        # Recorded, not discarded. A fixture that swallows **extra lets a regression that
        # stops forwarding VIFs pass every test in this file, and a VIF-less clone is the
        # failure that boots perfectly and can never send a packet.
        self.creates.append(extra)
        return {"id": self.pool.create(name_label)}

    def template_vifs(self, template):
        """vm.create does not inherit the template's VIFs, so the harness passes them.
        Returning a non-empty list rather than [] on purpose: an empty one would let a
        caller that dropped the argument entirely pass this fixture unchanged."""
        return [{"network": "net-1"}]

    def get_objects(self, filter_=None, limit=None):
        return self.pool.get((filter_ or {}).get("id"))

    def resolve_vm(self, name_label):
        return [v for v in self.pool.vms.values() if v["name_label"] == name_label]

    def set_xenstore(self, vm_id, data):
        self.pool.set_xenstore(vm_id, data)

    def add_tag(self, vm_id, tag):
        self.pool.add_tag(vm_id, tag)

    def delete_vm(self, vm_id, delete_disks=True):
        self.pool.delete(vm_id)


class PoolRest:
    def __init__(self, pool):
        self.pool = pool

    def create_vm(self, pool_id, template_id, name_label, clone=True, boot=False, **extra):
        return self.pool.create(name_label), 1.016

    #: go blind on xenStoreData reads from the moment the scrub is written, not before.
    #: The timing is the whole test. During the SEED poll an empty read is correctly false
    #: whether the reader answers None or {}, so blinding there cannot tell the two apart.
    #: It is the SCRUB poll where `SEED_KEY not in {}` is true and a reader that collapses
    #: "unreadable" into "empty" reports a delete that never happened.
    blind_after_scrub = False
    _blind = False

    #: answer this instead of a document on reads whose path contains `garble_on`.
    #: A string, because XoRest.get decodes a non-JSON body to one and that is the case
    #: `or {}` does not cover: a string is truthy, so the fallback never fires.
    garble_on = None
    garble = "<html>502 Bad Gateway</html>"

    def get(self, path):
        if self.garble_on and self.garble_on in path:
            return self.garble
        if self._blind and "xenStoreData" in path:
            return None            # XoRest.get answers None on an empty HTTP body
        found = self.pool.get(path.split("/vms/")[1].split("?")[0])
        return found[0] if found else {}

    def set_xenstore(self, vm_id, data):
        self.pool.set_xenstore(vm_id, data)
        if self.blind_after_scrub and any(v is None for v in data.values()):
            self._blind = True
        return 200

    def add_tag(self, vm_id, tag):
        self.pool.add_tag(vm_id, tag)
        return 200

    def delete_vm(self, vm_id):
        self.pool.delete(vm_id)
        return 200, 0.618


def run_both(pool, xo=None):
    """Both paths against one store, so a case cannot be fixed on one side only."""
    xo = xo or PoolXo(pool)
    return [
        ("JSON-RPC", XoError, lambda: xo_compare.run_jsonrpc(xo, TEMPLATE, "xo-cmp-jrpc-1")),
        ("REST", XoRestError,
         lambda: xo_compare.run_rest(PoolRest(pool), xo, "pool-1", TEMPLATE, "xo-cmp-rest-1")),
    ]


@pytest.mark.parametrize("backend", ["JSON-RPC", "REST"])
def test_a_healthy_backend_completes_and_leaves_nothing_tracked(empty_created, capsys, backend):
    """The control. Every failure case below is satisfied by a path that always raises,
    and a comparison harness that cannot complete measures nothing."""
    name, _, run = next(b for b in run_both(Pool()) if b[0] == backend)
    result = run()
    assert result.backend == name
    assert set(result.steps) >= {"create", "seed", "scrub", "tag", "delete"}
    assert empty_created.CREATED == [], "a completed path left an id on the cleanup list"


@pytest.mark.parametrize("backend", ["JSON-RPC", "REST"])
def test_a_scrub_that_never_lands_fails_the_path(empty_created, capsys, backend):
    """`gone` was computed, printed, and thrown away. A #28 scrub that silently did
    nothing produced a full timing table and exit 0, which is this project's oldest
    failure shape wearing a new file name."""
    _, error, run = next(b for b in run_both(Pool(scrub=False)) if b[0] == backend)
    with pytest.raises(error) as caught:
        run()
    assert "SCRUB_FAILED" in str(caught.value)
    assert empty_created.CREATED, "the VM was left untracked when the path failed"


@pytest.mark.parametrize("backend", ["JSON-RPC", "REST"])
def test_a_tag_that_never_appears_fails_the_path(empty_created, capsys, backend):
    """The owner marker is the only handle a sweep has on an XO-made VM, since XoVm
    carries no other_config. A tag that quietly did not stick is a leak waiting to
    happen, and it was being logged as a `poll=False` in the middle of a passing run."""
    _, error, run = next(b for b in run_both(Pool(tag=False)) if b[0] == backend)
    with pytest.raises(error) as caught:
        run()
    assert "TAG_FAILED" in str(caught.value)


@pytest.mark.parametrize("backend", ["JSON-RPC", "REST"])
def test_a_delete_that_does_not_delete_fails_the_path(empty_created, capsys, backend):
    """The delete call returning is not the VM being gone. Without the confirmation the
    id came off CREATED on the strength of a return value, so cleanup could not retry and
    the end-of-run baseline was the only thing that noticed.

    The raise is asserted against both error families rather than the backend's own.
    `confirm_gone` is shared, so the REST path raises an XoError here, and the property
    that matters is not which family it belongs to but that main()'s
    `except (XoError, XoRestError)` catches it: a raise outside that pair would skip
    cleanup entirely, and skipping cleanup is how the VM this check just found gets left
    on the pool by the handler meant to report it.
    """
    _, _, run = next(b for b in run_both(Pool(delete=False)) if b[0] == backend)
    with pytest.raises((XoError, XoRestError)) as caught:
        run()
    assert "STILL_PRESENT" in str(caught.value)
    assert empty_created.CREATED, "the surviving VM was struck off the cleanup list"


def test_the_jsonrpc_path_forwards_the_template_s_vifs(empty_created, capsys):
    """MEASURED on the pool 2026-09-06: vm.create does not inherit the template's VIFs and
    create_vm over REST does. Without this argument the clone boots, the tools come up,
    os_version is correct, and the only field telling the truth is the address that never
    arrives. The fixture records what it was passed because a fixture that discards
    **extra lets the regression through while looking green."""
    # One Pool, per run_both's contract. Two stores is harmless while this selects only
    # the JSON-RPC branch and correct-looking forever after somebody adds the REST one,
    # at which point the two backends read different stores and drift while staying green.
    pool = Pool()
    xo = PoolXo(pool)
    _, _, run = next(b for b in run_both(pool, xo=xo) if b[0] == "JSON-RPC")
    run()
    assert xo.creates == [{"VIFs": [{"network": "net-1"}]}], xo.creates


def test_the_rest_path_survives_an_empty_body_and_does_not_call_it_an_absence(empty_created, capsys):
    """Two defects in one reader, both caught by the same fixture.

    XoRest.get answers None on an empty HTTP body, so `.get(...)` on it raised
    AttributeError and took the harness down with a VM already on the pool. And the `or {}`
    that would have prevented that makes every `d is not None` guard beside it inert, so a
    negative poll passes the instant the body is empty: `SEED_KEY not in {}` is true.

    The store goes blind the moment the scrub is written and the scrub genuinely does
    nothing, so every read the scrub poll makes comes back empty. A reader that calls
    `.get` on None crashes; a reader that answers {} reports a delete that never happened.
    Blinding earlier would prove neither, because during the seed poll an empty read is
    correctly false whichever way the reader is written.
    """
    pool = Pool(scrub=False)
    rest = PoolRest(pool)
    rest.blind_after_scrub = True
    with pytest.raises(XoRestError) as caught:
        xo_compare.run_rest(rest, PoolXo(pool), "pool-1", TEMPLATE, "xo-cmp-rest-1")
    assert "SCRUB_FAILED" in str(caught.value)
    assert empty_created.CREATED, "the VM was left untracked when the path failed"


def test_a_non_json_body_on_the_readability_poll_does_not_escape(empty_created, capsys):
    """XoRest.get decodes a non-JSON body to a string, so `rest.get(...) or {}` leaves it
    a string and `.get("type")` raises AttributeError. That escapes main()'s
    `except (XoError, XoRestError)`, skips cleanup, and leaves a VM on a pool where an
    XO-made clone carries no owner marker for any sweep to find.

    A proxy answering HTML instead of the appliance is the ordinary way to reach this.
    """
    pool = Pool()
    rest = PoolRest(pool)
    rest.garble_on = "fields=type"
    with pytest.raises(XoRestError) as caught:
        xo_compare.run_rest(rest, PoolXo(pool), "pool-1", TEMPLATE, "xo-cmp-rest-1")
    assert caught.value.message == "NOT_READABLE"
    assert empty_created.CREATED, "the VM was left untracked when the path failed"

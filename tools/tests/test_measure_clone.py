"""Guards on the measurement harness.

Its published numbers are load-bearing for the writeup, so the ways it can quietly lie
matter as much as the ways it can crash.
"""

import pytest

import measure_clone
from xapi import XapiError


class CycleXapi:
    """Enough XAPI for one_cycle. Set boot_fails or teardown_fails to steer it.

    Deliberately answers no synchronous VM.start: call() refuses any method it was not
    given, so a regression to the blocking form fails loudly instead of being humoured.
    await_task refuses the start task for the same reason -- a void verb settles with no
    reference, so routing one through the strict helper is a bug the fake must not absorb.
    """

    def __init__(self, boot_fails=False, teardown_fails=False, clear_fails=False):
        self.boot_fails = boot_fails
        self.teardown_fails = teardown_fails
        self.clear_fails = clear_fails
        self.destroyed = False
        self.methods = []

    def sr_free_bytes(self, sr):
        return 10 * 2**30

    def await_task(self, task):
        self.methods.append(f"await_task:{task}")
        assert task != "OpaqueRef:start-task", "a void verb must go through await_void_task"
        return "OpaqueRef:vm"

    def await_void_task(self, task, timeout=None):
        self.methods.append(f"await_void_task:{task}")
        if self.boot_fails:
            raise XapiError("BOOT_TIMEOUT", "OpaqueRef:vm")

    def destroy_with_disks(self, vm):
        self.destroyed = True
        if self.teardown_fails:
            raise XapiError("VM_DESTROY_FAILED", vm)
        return ["vdi-1"]

    def call(self, method, *params):
        self.methods.append(method)
        if method in ("Async.VM.clone", "Async.VM.copy"):
            return "OpaqueRef:task"
        if method == "Async.VM.start":
            return "OpaqueRef:start-task"
        if method == "VM.set_is_a_template":
            # Pinned, not waved through: a fake that accepts any arguments here passes
            # against production clearing the flag on the wrong VM, or setting it True.
            assert params == ("OpaqueRef:vm", False), params
            if self.clear_fails:
                raise XapiError("VM_BAD_POWER_STATE", "OpaqueRef:vm")
            return ""
        if method == "VM.get_power_state":
            return "Running"
        if method == "VM.get_guest_metrics":
            return "OpaqueRef:gm"
        if method == "VM_guest_metrics.get_networks":
            return {"0/ip": "10.0.0.5"}
        raise AssertionError(method)


@pytest.mark.parametrize(
    "cow, full_copy, expected",
    [
        (True, False, "VM.clone (CoW)"),
        (False, False, "VM.clone (full)"),
        (True, True, "VM.copy (full)"),
        (False, True, "VM.copy (full)"),
    ],
)
def test_clone_mode_is_labelled_from_the_sr_type(cow, full_copy, expected):
    """On an LVM SR, VM.clone full-copies. Labelling its timings CoW corrupts the verdict."""
    result = measure_clone.one_cycle(CycleXapi(), "src", "sr", 1, full_copy=full_copy, cow=cow)
    assert result["mode"] == expected


def test_the_probe_is_started_through_the_async_task_path():
    """#79: a synchronous VM.start blocks server-side until the VM is up, so a start
    crossing the client's 30s read timeout surfaced as TRANSPORT_ERROR while the VM kept
    booting. The cycle's timings were discarded and the finally tore the probe down
    mid-boot, so a slow-but-working host read as a failed cycle -- and, when every cycle
    tripped, as a failed kill criterion produced by the instrument rather than the pool.
    """
    x = CycleXapi()
    measure_clone.one_cycle(x, "src", "sr", 1, full_copy=False, cow=True)

    assert "Async.VM.start" in x.methods, "the probe was not started asynchronously"
    assert "await_void_task:OpaqueRef:start-task" in x.methods, "the start task went unawaited"


@pytest.mark.parametrize("full_copy", [False, True])
def test_the_template_flag_is_cleared_between_the_clone_and_the_start(full_copy):
    """#126: a clone of a template is itself a template, and XAPI will not start one.

    Every golden image on the pool is a template, and the only non-template candidates are
    Running (so main() rejects them) and carry no guest tools. So without this the tool that
    exists to produce the clone-to-online numbers cannot complete a single cycle, and the
    refusal surfaces at the start and reads like a boot problem.

    Ordering is the part worth pinning: clearing the flag after the start attempt would be
    useless, and a test that only asserted the call happened would pass against it.
    """
    x = CycleXapi()
    measure_clone.one_cycle(x, "src", "sr", 1, full_copy=full_copy, cow=True)

    clone = "Async.VM.copy" if full_copy else "Async.VM.clone"
    assert "VM.set_is_a_template" in x.methods, "the clone was left as a template"
    # Anchored on await_task rather than on the clone call: the submission only proves a task
    # exists, and the VM whose flag is being cleared does not exist until that task settles.
    assert (
        x.methods.index(clone)
        < x.methods.index("await_task:OpaqueRef:task")
        < x.methods.index("VM.set_is_a_template")
        < x.methods.index("Async.VM.start")
    ), f"the flag was not cleared between the clone and the start: {x.methods}"


def test_probe_vm_is_destroyed_when_boot_fails():
    """A BOOT_TIMEOUT that skipped teardown would leave the probe running on the pool."""
    x = CycleXapi(boot_fails=True)
    with pytest.raises(XapiError, match="BOOT_TIMEOUT"):
        measure_clone.one_cycle(x, "src", "sr", 1, full_copy=False, cow=True)
    assert x.destroyed, "the probe VM leaked on the failure path"


def test_probe_vm_is_destroyed_when_the_template_clear_fails():
    """The clear sits inside the try for this reason, and the reason deserves a test.

    Placed beside the clone call instead it would read the same and leak the clone on any
    failure, which is the one thing the finally exists to prevent.
    """
    x = CycleXapi(clear_fails=True)
    with pytest.raises(XapiError, match="VM_BAD_POWER_STATE"):
        measure_clone.one_cycle(x, "src", "sr", 1, full_copy=False, cow=True)
    assert x.destroyed, "the clone leaked when the template clear failed"


def test_a_failing_teardown_does_not_mask_the_original_error():
    """Report the BOOT_TIMEOUT that caused the unwind, not the teardown error it triggered."""
    x = CycleXapi(boot_fails=True, teardown_fails=True)
    with pytest.raises(XapiError) as caught:
        measure_clone.one_cycle(x, "src", "sr", 1, full_copy=False, cow=True)
    assert caught.value.message == "BOOT_TIMEOUT"


# -- main(): the orphan check must survive a failed cycle -------------------

class MainXapi:
    def __init__(self, vdis_before=5, vdis_after=5, name_matches=None):
        self._before, self._after = vdis_before, vdis_after
        self._seen = 0
        # refs VM.get_by_name_label returns; default is today's single-match case
        self.name_matches = ["OpaqueRef:src"] if name_matches is None else name_matches
        self.uuid_lookups = []
        self.uuid_error = None  # XapiError message VM.get_by_uuid raises, if any

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def default_sr(self):
        return "OpaqueRef:sr"

    def sr_free_bytes(self, sr):
        return 10 * 2**30

    def vdi_count(self, sr):
        self._seen += 1
        return self._before if self._seen == 1 else self._after

    def call(self, method, *params):
        if method == "VM.get_by_name_label":
            return self.name_matches
        if method == "VM.get_by_uuid":
            self.uuid_lookups.append(params[0])
            if self.uuid_error:
                raise XapiError(self.uuid_error, ["VM", params[0]])
            return "OpaqueRef:src"
        if method == "VM.get_record":
            return {
                "uuid": f"0000000-fake-uuid-for-{params[0]}",
                "is_a_template": params[0] == "OpaqueRef:src-template",
                "power_state": "Halted",
            }
        if method == "VM.get_power_state":
            return "Halted"
        if method == "SR.get_type":
            return "ext"
        raise AssertionError(method)


@pytest.fixture
def harness(monkeypatch):
    def _harness(xapi, cycle, clones=3, copies=0):
        monkeypatch.setattr(measure_clone, "Xapi", lambda *a, **k: xapi)
        monkeypatch.setattr(measure_clone, "one_cycle", cycle)
        monkeypatch.setattr(
            measure_clone.sys, "argv",
            ["measure_clone.py", "--clones", str(clones), "--copies", str(copies)],
        )

    return _harness


def _passing_cycle(x, source, sr, index, full_copy, cow):
    mode = "VM.copy (full)" if full_copy else "VM.clone (CoW)"
    return {"mode": mode, "clone": 0.5, "online": 12.0, "disk_mib": 0.0}


def _always_fails(x, source, sr, index, full_copy, cow):
    raise XapiError("BOOT_TIMEOUT", "OpaqueRef:vm")


def test_a_failed_cycle_still_reports_the_cycles_that_passed(harness, capsys):
    """Issue #2: one bad cycle used to unwind past the VDI-delta check entirely."""
    def cycle(x, source, sr, index, full_copy, cow):
        if index == 2:
            raise XapiError("BOOT_TIMEOUT", "OpaqueRef:vm")
        return {"mode": "VM.clone (CoW)", "clone": 0.5, "online": 12.0, "disk_mib": 0.0}

    harness(MainXapi(), cycle)
    rc = measure_clone.main()
    out = capsys.readouterr()

    assert "after 2/3 cycles" in out.out, "the summary never ran for the passing cycles"
    assert "KILL CRITERION" in out.out, "the verdict was skipped"
    assert "1 of 3 cycles failed: 2" in out.err
    assert rc == 1, "a failed cycle must exit non-zero"


def test_orphaned_vdis_are_still_caught_when_a_cycle_fails(harness, capsys):
    """The orphan check is why this script exists. A failed cycle must not hide a leak."""
    def cycle(x, source, sr, index, full_copy, cow):
        if index == 1:
            raise XapiError("BOOT_TIMEOUT", "OpaqueRef:vm")
        return {"mode": "VM.clone (CoW)", "clone": 0.5, "online": 12.0, "disk_mib": 0.0}

    harness(MainXapi(vdis_before=5, vdis_after=7), cycle)
    rc = measure_clone.main()

    assert "ORPHANED VDIs" in capsys.readouterr().err
    assert rc == 1


def test_a_duplicate_name_is_refused_and_every_candidate_is_named(harness, capsys):
    """#125: name_label is not unique, so matches[0] is whichever object XAPI lists first.

    Live on the pool when filed: jenkins-golden-debian resolved to a template and a
    non-template, and which one a run cloned was list order. When the template won, the
    failure surfaced at VM.start and read like a boot problem. Refuse instead, and print
    enough per candidate that the operator can tell which is which without a second tool.
    """
    x = MainXapi(name_matches=["OpaqueRef:src-template", "OpaqueRef:src"])
    ran = []
    harness(x, lambda *a, **k: ran.append(a))
    rc = measure_clone.main()
    err = capsys.readouterr().err

    assert rc == 2
    assert not ran, "a cycle ran against an ambiguous source"
    assert "2 VMs are named" in err
    assert err.count("uuid=") == 2, "not every candidate was named"
    assert "is_a_template=True" in err and "is_a_template=False" in err, (
        "the flag that distinguishes the candidates is missing")
    assert err.count("power=") == 2, "power state is part of telling the candidates apart"


def test_a_uuid_source_bypasses_the_name_lookup(harness):
    """A uuid is the unambiguous handle, so it must not go through get_by_name_label."""
    x = MainXapi()
    harness(x, _passing_cycle)
    monkey_uuid = "ea9c8a68-c875-35a4-1c6b-6a12f6d2c583"
    measure_clone.sys.argv[1:1] = ["--source", monkey_uuid]
    rc = measure_clone.main()

    assert rc == 0
    assert x.uuid_lookups == [monkey_uuid], "the uuid was not resolved via VM.get_by_uuid"


def test_an_unknown_uuid_reads_as_no_vm_not_as_a_crash(harness, capsys):
    """UUID_INVALID is what the pool raises for a well-formed uuid that matches nothing
    (measured on XAPI 26.1; the docs offered VM_NOT_FOUND and HANDLE_INVALID, both wrong
    for this call). That one means "no matches" and gets the friendly message."""
    x = MainXapi()
    x.uuid_error = "UUID_INVALID"
    harness(x, _passing_cycle)
    measure_clone.sys.argv[1:1] = ["--source", "00000000-0000-4000-8000-000000000000"]
    rc = measure_clone.main()

    assert rc == 2
    assert "no VM named" in capsys.readouterr().err


def test_a_transport_error_during_uuid_lookup_is_not_reported_as_no_vm(harness):
    """Anything else must propagate: 'no VM named X' over a dead network or a bad
    password points the operator at the wrong problem entirely."""
    x = MainXapi()
    x.uuid_error = "TRANSPORT_ERROR"
    harness(x, _passing_cycle)
    measure_clone.sys.argv[1:1] = ["--source", "00000000-0000-4000-8000-000000000000"]

    with pytest.raises(XapiError, match="TRANSPORT_ERROR"):
        measure_clone.main()


def test_a_clean_run_exits_zero(harness):
    harness(MainXapi(), _passing_cycle)
    assert measure_clone.main() == 0


def test_every_clone_cycle_failing_still_states_the_verdict(harness, capsys):
    """Only reachable since cycles stopped aborting the run.

    An absent KILL CRITERION line reads as a skipped check. A clone that never boots has
    not met the criterion, so say FAIL out loud rather than going quiet.
    """
    harness(MainXapi(), _always_fails, clones=3, copies=0)
    rc = measure_clone.main()
    out = capsys.readouterr()

    assert "KILL CRITERION" in out.out, "the verdict vanished when every clone failed"
    assert "FAIL" in out.out
    assert "no clone cycle completed" in out.out
    assert "after 0/3 cycles" in out.out
    assert rc == 1


def test_no_verdict_when_no_clone_cycles_were_requested(harness, capsys):
    """--clones 0 means the criterion was not attempted, which is not the same as failing."""
    harness(MainXapi(), _passing_cycle, clones=0, copies=1)
    rc = measure_clone.main()
    out = capsys.readouterr()

    assert "KILL CRITERION" not in out.out
    assert rc == 0

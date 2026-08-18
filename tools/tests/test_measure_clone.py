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

    def __init__(self, boot_fails=False, teardown_fails=False):
        self.boot_fails = boot_fails
        self.teardown_fails = teardown_fails
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


def test_probe_vm_is_destroyed_when_boot_fails():
    """A BOOT_TIMEOUT that skipped teardown would leave the probe running on the pool."""
    x = CycleXapi(boot_fails=True)
    with pytest.raises(XapiError, match="BOOT_TIMEOUT"):
        measure_clone.one_cycle(x, "src", "sr", 1, full_copy=False, cow=True)
    assert x.destroyed, "the probe VM leaked on the failure path"


def test_a_failing_teardown_does_not_mask_the_original_error():
    """Report the BOOT_TIMEOUT that caused the unwind, not the teardown error it triggered."""
    x = CycleXapi(boot_fails=True, teardown_fails=True)
    with pytest.raises(XapiError) as caught:
        measure_clone.one_cycle(x, "src", "sr", 1, full_copy=False, cow=True)
    assert caught.value.message == "BOOT_TIMEOUT"


# -- main(): the orphan check must survive a failed cycle -------------------

class MainXapi:
    def __init__(self, vdis_before=5, vdis_after=5):
        self._before, self._after = vdis_before, vdis_after
        self._seen = 0

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
            return ["OpaqueRef:src"]
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

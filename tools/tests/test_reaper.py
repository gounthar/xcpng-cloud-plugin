"""Guards on the tool that permanently destroys VMs and their disks.

Two questions only. Does it destroy something the operator meant to keep, and does it
report as destroyed something that survived?

A third has been added by experience: does it find anything at all? The tool defaulted to
the name prefix "jenkins-ci-" long after the plugin started naming clones
"xcpng-<template>-<uuid8>", so it matched none of them, printed "nothing to reap" and
exited 0 while the VMs it exists to catch held pool memory and SR space. Selection is now
by the marker the plugin stamps on each clone, and the first test below is that regression.
"""

import pytest

import reaper
from fakes import FakeXapi, vm_record

# What the plugin actually names its clones, and the cloud that owns them.
PLUGIN_VM = "xcpng-jenkins-golden-debian-518ab396"
CLOUD = "xcpng-lab"


@pytest.fixture
def pool(monkeypatch):
    """Install a fake pool and return it. Nothing here can reach a hypervisor."""

    def _pool(records, stuck=(), vdis_after=None, argv=("reaper.py", "--apply")):
        fake = FakeXapi(records=records, stuck=stuck, vdis_after=vdis_after)
        monkeypatch.setattr(reaper, "Xapi", lambda *a, **k: fake)
        monkeypatch.setattr(reaper.sys, "argv", list(argv))
        return fake

    return _pool


def test_a_vm_the_plugin_provisioned_is_found_by_default(pool):
    """The regression. A default run must see a real plugin-provisioned clone.

    Named as the plugin names them, which is nothing like the old "jenkins-ci-" prefix, and
    marked as the plugin marks them. Under the prefix default this VM matched nothing and the
    tool reported success while it leaked.
    """
    fake = pool({"vm-leaked": vm_record(PLUGIN_VM, owner=CLOUD)})
    assert reaper.main() == 0
    assert fake.destroyed == ["vm-leaked"], "the reaper cannot see the VMs it exists to catch"


def test_an_unmarked_vm_is_never_touched_however_it_is_named(pool):
    """The marker, not the name, is what condemns a VM.

    Every VM here is named to defeat a name-based filter: the golden image every future
    provision depends on shares the plugin's own "xcpng-" prefix, and one is named exactly
    like a plugin clone but was never stamped. None of them is the plugin's to destroy.
    """
    fake = pool(
        {
            "vm-golden": vm_record("xcpng-golden-debian"),
            "vm-impostor": vm_record(PLUGIN_VM),
            "vm-probe": vm_record("jenkins-ci-probe-2"),
            "vm-prod": vm_record("production-db"),
            "vm-mine": vm_record("xcpng-agent-of-another-tool", owner=CLOUD),
        }
    )
    assert reaper.main() == 0
    assert fake.destroyed == ["vm-mine"], "an unmarked VM was destroyed"


def test_cloud_filter_selects_only_that_clouds_vms(pool):
    """Two XCP-ng clouds can share a pool; one must not reap the other's agents."""
    fake = pool(
        {
            "vm-ours": vm_record("xcpng-a-1111", owner=CLOUD),
            "vm-theirs": vm_record("xcpng-b-2222", owner="some-other-cloud"),
        },
        argv=("reaper.py", "--apply", "--cloud", CLOUD),
    )
    assert reaper.main() == 0
    assert fake.destroyed == ["vm-ours"]


def test_snapshots_templates_and_the_control_domain_are_spared(pool):
    """A snapshot is a VM object, inherits the marker from the VM it was taken of, and reports
    Halted like a dead agent. Destroying one takes the operator's restore point with it.

    Every spared record here carries the marker on purpose. A snapshot of a marked agent really
    does inherit other_config, so the marker alone cannot save it and these guards stay
    load-bearing exactly as they were under the prefix.
    """
    fake = pool(
        {
            "vm-agent": vm_record(PLUGIN_VM, owner=CLOUD),
            "vm-snap": vm_record(f"{PLUGIN_VM}-preupgrade", owner=CLOUD, snapshot=True),
            "vm-template": vm_record("xcpng-golden-template", owner=CLOUD, template=True),
            "vm-dom0": vm_record("xcpng-control-domain", owner=CLOUD, control_domain=True),
            "vm-prod": vm_record("production-db"),
        }
    )
    assert reaper.main() == 0
    assert fake.destroyed == ["vm-agent"]


@pytest.mark.parametrize("flag", ["snapshot", "template", "control_domain"])
def test_each_guard_is_load_bearing_on_a_marked_vm(pool, flag):
    """Each flag alone must spare a VM that carries the marker."""
    fake = pool({"vm-x": vm_record(PLUGIN_VM, owner=CLOUD, **{flag: True})})
    assert reaper.main() == 0
    assert fake.destroyed == [], f"the {flag} guard did not spare a marked VM"


def test_a_stuck_vm_does_not_abandon_the_rest_of_the_sweep(pool, capsys):
    """The safety net must not fail open on the one occasion it is needed."""
    fake = pool(
        {
            "vm-stuck": vm_record("xcpng-agent-1111", owner=CLOUD),
            "vm-ok": vm_record("xcpng-agent-3333", owner=CLOUD),
        },
        stuck=["vm-stuck"],
    )
    rc = reaper.main()

    assert "vm-ok" in fake.destroyed, "the sweep stopped at the stuck VM"
    assert "vm-stuck" not in fake.destroyed
    assert rc == 1, "a VM it could not reap must be a non-zero exit"
    assert "not reaped" in capsys.readouterr().err


def test_orphaned_vdis_are_reported_as_failure(pool):
    """The VDI count rising across a reap means teardown leaked. That is the headline claim."""
    pool({"vm-1": vm_record(PLUGIN_VM, owner=CLOUD)}, vdis_after=9)
    assert reaper.main() == 1


def test_dry_run_destroys_nothing(pool):
    fake = pool({"vm-1": vm_record(PLUGIN_VM, owner=CLOUD)}, argv=("reaper.py",))
    assert reaper.main() == 0
    assert fake.destroyed == []


# ---- Legacy prefix mode: opt-in, and guarded ----


def test_prefix_mode_still_reaps_the_pre_plugin_probe_vms(pool):
    """measure_clone.py's VMs predate the marker, so the escape hatch has to keep working."""
    fake = pool(
        {
            "vm-probe": vm_record("jenkins-ci-probe-1"),
            "vm-prod": vm_record("production-db"),
        },
        argv=("reaper.py", "--apply", "--prefix", "jenkins-ci-", "--force"),
    )
    assert reaper.main() == 0
    assert fake.destroyed == ["vm-probe"]


def test_apply_with_empty_prefix_is_refused_before_the_pool_is_contacted(monkeypatch):
    """`"anything".startswith("")` is true, so an empty prefix matches every VM on the pool."""
    monkeypatch.setattr(reaper.sys, "argv", ["reaper.py", "--apply", "--prefix", ""])

    def explode(*args, **kwargs):
        raise AssertionError("connected to the pool despite an empty prefix")

    monkeypatch.setattr(reaper, "Xapi", explode)

    with pytest.raises(SystemExit) as caught:
        reaper.main()
    assert caught.value.code == 2


def test_empty_prefix_without_apply_is_allowed(pool):
    """A dry run destroys nothing, so an empty prefix is harmless and useful for listing."""
    fake = pool({"vm-1": vm_record("production-db")}, argv=("reaper.py", "--prefix", ""))
    assert reaper.main() == 0
    assert fake.destroyed == []


def test_a_short_prefix_apply_is_refused_without_force(monkeypatch):
    """`--apply --prefix jenkins-` destroys jenkins-golden-debian and its disk.

    docs/golden-image.md already documents that exact footgun, and the image is not
    recoverable without a rebuild. Refuse before the pool is contacted.
    """
    monkeypatch.setattr(reaper.sys, "argv", ["reaper.py", "--apply", "--prefix", "jenkins-"])

    def explode(*args, **kwargs):
        raise AssertionError("connected to the pool despite a golden-image-matching prefix")

    monkeypatch.setattr(reaper, "Xapi", explode)

    with pytest.raises(SystemExit) as caught:
        reaper.main()
    assert caught.value.code == 2


def test_a_non_interactive_prefix_apply_destroys_nothing_without_force(pool, capsys):
    """No TTY means nobody to confirm to. Fail closed rather than sweep by name unattended."""
    fake = pool(
        {"vm-probe": vm_record("jenkins-ci-probe-1")},
        argv=("reaper.py", "--apply", "--prefix", "jenkins-ci-"),
    )
    assert reaper.main() == 2
    assert fake.destroyed == []
    assert "without --force" in capsys.readouterr().err


def test_a_detached_stdin_prefix_apply_destroys_nothing(pool, capsys, monkeypatch):
    """sys.stdin is None, not merely closed, when the interpreter runs with stdin detached.

    That is still nobody to confirm to, so it must take the same refusal as a non-TTY rather than
    raise AttributeError out of the confirmation prompt. Both outcomes destroy nothing, but one of
    them prints the reason.
    """
    fake = pool(
        {"vm-probe": vm_record("jenkins-ci-probe-1")},
        argv=("reaper.py", "--apply", "--prefix", "jenkins-ci-"),
    )
    monkeypatch.setattr(reaper.sys, "stdin", None)

    assert reaper.main() == 2
    assert fake.destroyed == []
    assert "without --force" in capsys.readouterr().err


def test_a_record_with_a_null_other_config_is_not_a_crash(pool):
    """Defensive: a record whose other_config is None must read as unmarked, not raise.

    I could not get real XAPI to emit this (it types other_config as a map and returns {} when
    empty), so this pins the guard rather than a reproduced failure.
    """
    records = {"vm-odd": vm_record("xcpng-weird-1"), "vm-ok": vm_record(PLUGIN_VM, owner=CLOUD)}
    records["vm-odd"]["other_config"] = None
    fake = pool(records)

    assert reaper.main() == 0
    assert fake.destroyed == ["vm-ok"], "a null other_config must read as unmarked, not match or explode"


def test_prefix_and_cloud_together_are_refused(monkeypatch):
    """--cloud filters the marker, so combining it with a name prefix asks for two things at once."""
    monkeypatch.setattr(
        reaper.sys, "argv", ["reaper.py", "--prefix", "jenkins-ci-", "--cloud", CLOUD])
    with pytest.raises(SystemExit) as caught:
        reaper.main()
    assert caught.value.code == 2

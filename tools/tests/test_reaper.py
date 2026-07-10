"""Guards on the tool that permanently destroys VMs and their disks.

Two questions only. Does it destroy something the operator meant to keep, and does it
report as destroyed something that survived?
"""

import pytest

import reaper
from fakes import FakeXapi, vm_record


@pytest.fixture
def pool(monkeypatch):
    """Install a fake pool and return it. Nothing here can reach a hypervisor."""

    def _pool(records, stuck=(), vdis_after=None, argv=("reaper.py", "--apply")):
        fake = FakeXapi(records=records, stuck=stuck, vdis_after=vdis_after)
        monkeypatch.setattr(reaper, "Xapi", lambda *a, **k: fake)
        monkeypatch.setattr(reaper.sys, "argv", list(argv))
        return fake

    return _pool


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


def test_snapshots_templates_and_the_control_domain_are_spared(pool):
    """A snapshot is a VM object, inherits a matching name, and reports Halted like a dead agent.

    Destroying one takes the operator's restore point with it.

    Every spared record here carries a prefix-matching name on purpose. Give the template a
    realistic name like `jenkins-golden-debian` and the prefix alone spares it, so the
    is_a_template guard is never reached and could be deleted with the suite still green.
    `jenkins-ci-control-domain` is not a name dom0 would ever have; it is the only way to
    prove the flag, rather than the name, is what saves it.
    """
    fake = pool(
        {
            "vm-agent": vm_record("jenkins-ci-agent-3"),
            "vm-snap": vm_record("jenkins-ci-agent-3-preupgrade", snapshot=True),
            "vm-template": vm_record("jenkins-ci-golden-template", template=True),
            "vm-dom0": vm_record("jenkins-ci-control-domain", control_domain=True),
            "vm-golden": vm_record("jenkins-golden-debian", template=True),
            "vm-prod": vm_record("production-db"),
        }
    )
    assert reaper.main() == 0
    assert fake.destroyed == ["vm-agent"]


@pytest.mark.parametrize("flag", ["snapshot", "template", "control_domain"])
def test_each_guard_is_load_bearing_on_a_matching_name(pool, flag):
    """Each flag alone must spare a VM whose name matches the prefix."""
    fake = pool({"vm-x": vm_record("jenkins-ci-agent-9", **{flag: True})})
    assert reaper.main() == 0
    assert fake.destroyed == [], f"the {flag} guard did not spare a prefix-matching VM"


def test_a_stuck_vm_does_not_abandon_the_rest_of_the_sweep(pool, capsys):
    """The safety net must not fail open on the one occasion it is needed."""
    fake = pool(
        {
            "vm-stuck": vm_record("jenkins-ci-agent-1"),
            "vm-ok": vm_record("jenkins-ci-agent-3"),
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
    pool({"vm-1": vm_record("jenkins-ci-agent-1")}, vdis_after=9)
    assert reaper.main() == 1


def test_dry_run_destroys_nothing(pool):
    fake = pool({"vm-1": vm_record("jenkins-ci-agent-1")}, argv=("reaper.py",))
    assert reaper.main() == 0
    assert fake.destroyed == []

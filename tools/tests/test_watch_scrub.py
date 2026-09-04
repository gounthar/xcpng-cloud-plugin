"""Tests for watch_scrub.py.

Nothing here talks to a pool. What is worth guarding is the verdict logic, because this tool
exists to replace a check that could not fail (#205), and the way it failed was that a reading
which established nothing was printed as a pass. So the tests that matter are the ones that
pin what is *not* a pass: an absence with no matching presence, a poll that never saw a key at
all, and a secret that outlives the clone.

Every verdict is asserted against a history the tracker actually observed. A run whose clone
was already scrubbed before the first poll is INCONCLUSIVE by construction, and the test named
for it is the positive control the old check never had.
"""

from fakes import vm_record

from watch_scrub import (
    CONFIRMED,
    CONTROL_KEY,
    INCONCLUSIVE,
    NOT_SCRUBBED,
    SECRET_KEY,
    Tracker,
    clone_states,
    exit_status,
    format_state,
)

SEED = {SECRET_KEY: "s3cret", CONTROL_KEY: "xcpng-agent-1"}
SCRUBBED = {CONTROL_KEY: "xcpng-agent-1"}


def records(*vms):
    return {f"OpaqueRef:{vm['name_label']}": vm for vm in vms}


def test_a_marked_clone_reports_the_secret_and_the_control_key():
    payload = records(vm_record("clone", owner="lab", power="Running", xenstore_data=SEED))
    assert clone_states(payload) == {
        "clone": {"secret": True, "control": True, "power": "Running"}
    }


def test_the_secret_value_never_leaves_the_record():
    """Presence only. A tool that printed the value to prove it was removed has published it."""
    payload = records(vm_record("clone", owner="lab", xenstore_data=SEED))
    rendered = format_state(clone_states(payload)["clone"])
    assert "s3cret" not in rendered
    assert "secret=PRESENT" in rendered


def test_an_unmarked_vm_is_never_watched():
    """Selection is the owner marker, as in reaper.py. A VM we did not create is not ours."""
    payload = records(
        vm_record("someone-elses", xenstore_data=SEED),
        vm_record("clone", owner="lab", xenstore_data=SEED),
    )
    assert list(clone_states(payload)) == ["clone"]


def test_templates_snapshots_and_dom0_are_all_excluded():
    payload = records(
        vm_record("golden", owner="lab", template=True, xenstore_data=SEED),
        vm_record("restore-point", owner="lab", snapshot=True, xenstore_data=SEED),
        vm_record("dom0", owner="lab", control_domain=True, xenstore_data=SEED),
    )
    assert clone_states(payload) == {}


def test_cloud_narrows_to_one_cloud():
    payload = records(
        vm_record("mine", owner="lab", xenstore_data=SEED),
        vm_record("theirs", owner="other", xenstore_data=SEED),
    )
    assert list(clone_states(payload, cloud="lab")) == ["mine"]


def test_present_then_absent_is_confirmed():
    tracker = Tracker()
    tracker.observe("clone", {"secret": True, "control": True, "power": "Running"}, 10.0)
    tracker.observe("clone", {"secret": False, "control": True, "power": "Running"}, 42.0)
    kind, why = tracker.verdict("clone")
    assert kind == CONFIRMED
    assert "present at 10.0s" in why and "absent at 42.0s" in why


def test_absent_from_the_first_poll_is_inconclusive_not_a_pass():
    """The positive control, and the whole reason this tool replaced the in-guest check.

    Start watching after the scrub has run and every sample says ABSENT. That is exactly what a
    reader which cannot see the key at all would print, and #205 is the record of those two
    being reported as the same thing for fifteen green builds.
    """
    tracker = Tracker()
    tracker.observe("clone", {"secret": False, "control": True, "power": "Running"}, 3.0)
    tracker.observe("clone", {"secret": False, "control": True, "power": "Running"}, 9.0)
    kind, why = tracker.verdict("clone")
    assert kind == INCONCLUSIVE
    assert "never seen present" in why


def test_a_clone_with_no_readable_seed_says_so_separately():
    """An empty seed and an unreadable one are different failures and get different sentences."""
    tracker = Tracker()
    tracker.observe("clone", {"secret": False, "control": False, "power": "Running"}, 1.0)
    kind, why = tracker.verdict("clone")
    assert kind == INCONCLUSIVE
    assert "no seed key was ever readable" in why


def test_a_secret_that_is_never_cleared_fails():
    tracker = Tracker()
    tracker.observe("clone", {"secret": True, "control": True, "power": "Running"}, 5.0)
    tracker.observe("clone", {"secret": True, "control": True, "power": "Running"}, 65.0)
    kind, _ = tracker.verdict("clone")
    assert kind == NOT_SCRUBBED


def test_a_secret_that_comes_back_after_being_cleared_fails():
    """A cleared key that reappears is a finding, not a blip, so the last word wins."""
    tracker = Tracker()
    tracker.observe("clone", {"secret": True, "control": True, "power": "Running"}, 5.0)
    tracker.observe("clone", {"secret": False, "control": True, "power": "Running"}, 40.0)
    tracker.observe("clone", {"secret": True, "control": True, "power": "Running"}, 55.0)
    kind, _ = tracker.verdict("clone")
    assert kind == NOT_SCRUBBED


def test_each_clone_gets_its_own_verdict():
    tracker = Tracker()
    tracker.observe("good", {"secret": True, "control": True, "power": "Running"}, 1.0)
    tracker.observe("good", {"secret": False, "control": True, "power": "Running"}, 2.0)
    tracker.observe("bad", {"secret": True, "control": True, "power": "Running"}, 1.0)
    assert [(name, kind) for name, kind, _ in tracker.verdicts()] == [
        ("bad", NOT_SCRUBBED),
        ("good", CONFIRMED),
    ]


def test_the_worst_verdict_decides_the_exit_status():
    assert exit_status([("a", CONFIRMED, "")]) == 0
    assert exit_status([("a", CONFIRMED, ""), ("b", NOT_SCRUBBED, "")]) == 1
    assert exit_status([("a", INCONCLUSIVE, "")]) == 2
    assert exit_status([("a", CONFIRMED, ""), ("b", INCONCLUSIVE, "")]) == 0

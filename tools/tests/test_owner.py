"""The owner marker, read from either backend (#231).

Before owner.py, three tools each read other_config["xcpng-cloud"] themselves, and a clone
made through Xen Orchestra carries a tag instead, so none of them could see it. The reaper
then reported nothing to reap while an XO cloud's leaked VM held its disk.
"""

import pathlib
import re

import pytest

from fakes import vm_record
from owner import (
    OWNER_KEY,
    OWNER_TAG_PREFIX,
    SELF_KEY,
    SELF_TAG_PREFIX,
    inherited_marker,
    marker_values,
    owned_by,
    owners,
    self_stamps,
    stamped_itself,
)

_CLIENT = (
    pathlib.Path(__file__).resolve().parents[2]
    / "src/main/java/io/jenkins/plugins/xcpng/client"
)


def test_the_other_config_marker_names_its_cloud():
    assert owners(vm_record("a", owner="xcpng-lab")) == {"xcpng-lab"}


def test_the_xo_tag_names_its_cloud():
    """The regression: this is exactly what an XO-made clone looks like over XAPI."""
    assert owners(vm_record("a", owner_tag="xcpng-xo")) == {"xcpng-xo"}


def test_both_markers_are_read_and_may_disagree():
    """Nothing stops both, and an operator can add a tag by hand in the XO UI."""
    record = vm_record("a", owner="xcpng-lab", owner_tag="xcpng-xo")
    assert owners(record) == {"xcpng-lab", "xcpng-xo"}
    assert owned_by(record, "xcpng-lab") and owned_by(record, "xcpng-xo")


@pytest.mark.parametrize(
    "tag",
    [
        "xcpng-cloud",  # the key with no separator is not the XO marker
        "xcpng-cloudy:lab",  # shares the key as a prefix, is a different tag
        "XCPNG-CLOUD:lab",  # tags are case-sensitive in XAPI
        "lab",
    ],
)
def test_a_tag_that_is_not_the_marker_does_not_mark(tag):
    assert owners(vm_record("a", tags=[tag])) == set()


def test_an_unmarked_record_is_unowned_however_tags_and_other_config_are_shaped():
    """Missing, None and empty are all "not ours". Real XAPI returns [] and {}, but a
    reader that raised on a None would take the whole sweep down with it."""
    bare = vm_record("a")
    del bare["tags"]
    nulls = vm_record("b")
    nulls["tags"] = None
    nulls["other_config"] = None
    for record in (bare, nulls, vm_record("c", other_config={"other-key": "x"})):
        assert owners(record) == set()
        assert not owned_by(record)


def test_presence_is_the_marker_even_with_an_empty_cloud_name():
    """reaper.py has always selected on `OWNER_KEY in other_config`, not on its value. The
    tag is read the same way so the two backends cannot disagree about what is marked.
    """
    assert owned_by(vm_record("a", owner=""))
    assert owned_by(vm_record("b", owner_tag=""))


def test_cloud_narrows_on_either_marker():
    assert owned_by(vm_record("a", owner_tag="xcpng-xo"), "xcpng-xo")
    assert not owned_by(vm_record("a", owner_tag="xcpng-xo"), "xcpng-lab")
    assert not owned_by(vm_record("a", owner="xcpng-lab"), "xcpng-xo")


def test_the_constants_match_the_java_that_writes_the_markers():
    """Each copy of OWNER_KEY used to carry a comment warning that drift from the Java makes
    the tool select nothing, silently. Check it instead of warning about it."""
    xapi_client = (_CLIENT / "XapiClient.java").read_text(encoding="utf-8")
    key = re.search(r'String OWNER_KEY = "([^"]*)";', xapi_client)
    assert (
        key
    ), "OWNER_KEY is no longer a string literal in XapiClient.java; re-read it by hand"
    assert OWNER_KEY == key.group(1)

    xo_client = (_CLIENT / "XoRestClient.java").read_text(encoding="utf-8")
    assert (
        'String OWNER_TAG_PREFIX = XapiClient.OWNER_KEY + ":";' in xo_client
    ), "XoRestClient.OWNER_TAG_PREFIX changed shape; update owner.OWNER_TAG_PREFIX to match"
    assert OWNER_TAG_PREFIX == OWNER_KEY + ":"


# --- #246: the marker is inherited by VM.clone, the uuid stamped beside it is not -----------


@pytest.mark.parametrize("backend", ["owner", "owner_tag"])
def test_a_hand_made_clone_of_an_agent_is_not_owned_but_the_agent_is(backend):
    """The whole of #246, both backends, and both halves in one test on purpose.

    `agent` is a clone the plugin made. `copy` is what an operator gets by cloning it to poke
    at a failing build: `VM.clone` copies other_config and tags alike, so it carries the same
    marker. Measured on the pool, 8.3.0 / XAPI 26.1, with positive controls.

    Asserting only the refusal would pass against a check that refuses everything, which is
    the failure this repo keeps meeting: a blanket refusal reads as a fix and strands every
    leaked disk the reaper exists to reclaim.
    """
    agent = vm_record("agent", **{backend: "xcpng-lab"})
    copy = vm_record("copy", inherited_from="agent", **{backend: "xcpng-lab"})

    assert owners(agent) == {"xcpng-lab"}
    assert owned_by(agent) and owned_by(agent, "xcpng-lab")

    assert owners(copy) == set()
    assert not owned_by(copy) and not owned_by(copy, "xcpng-lab")


@pytest.mark.parametrize("backend", ["owner", "owner_tag"])
def test_a_clone_stamped_before_the_uuid_check_is_still_owned(backend):
    """The grace path, and the reason it is not optional.

    A clone the plugin made before #246 carries the marker and no uuid. Refusing it would
    strand exactly the VMs the reaper exists for, so an absent stamp reads as ownership. The
    inheritance hazard survives for those records, which is stated rather than fixed.
    """
    old = vm_record("old", stamped=False, **{backend: "xcpng-lab"})
    assert self_stamps(old) == set()
    assert owners(old) == {"xcpng-lab"}
    assert owned_by(old, "xcpng-lab")


@pytest.mark.parametrize("cloud", ["xo-probe", "xo-compare"])
def test_the_throwaway_probe_vms_stay_findable(cloud):
    """The grace path is not only a migration. xo_probe.py and xo_compare.py tag their
    throwaway VMs and stamp no uuid, and never will, so removing the grace path as
    "finishing the migration" would make those VMs invisible to every sweep -- including
    the one that cleans them up when a probe dies before its own teardown.
    """
    record = vm_record("xo-probe-vm", owner_tag=cloud, stamped=False)
    assert owners(record) == {cloud}
    assert owned_by(record, cloud)


def test_one_matching_stamp_is_enough_when_a_clone_inherited_another():
    """XO tags are a set and the plugin's PUT adds to it, so a clone whose template was itself
    a marked clone carries the inherited stamp beside its own. Requiring the only stamp to
    match would refuse every clone of a marked golden image -- ours, and reapable."""
    record = vm_record("agent", owner_tag="xcpng-xo")
    record["tags"].append(f"{SELF_TAG_PREFIX}uuid-ancestor")

    assert self_stamps(record) == {"uuid-agent", "uuid-ancestor"}
    assert stamped_itself(record)
    assert owners(record) == {"xcpng-xo"}


def test_a_stamp_from_the_other_backend_still_counts():
    """The stamp and the marker need not share a backend. A record carrying the XAPI marker
    and an XO-shaped uuid tag for itself is odd but is not someone else's VM, and the reader
    is deliberately one reader over both surfaces."""
    record = vm_record("agent", owner="xcpng-lab", stamped=False)
    record["tags"].append(f"{SELF_TAG_PREFIX}uuid-agent")
    assert owners(record) == {"xcpng-lab"}


def test_an_unmarked_vm_is_not_owned_however_it_is_stamped():
    """A stamp without a marker is not ownership. Nothing writes that pair today; the check
    is here so a future writer of one cannot make an operator's VM reapable by accident."""
    record = vm_record("plain")
    record["other_config"][SELF_KEY] = "uuid-plain"
    assert owners(record) == set()
    assert not inherited_marker(record)


def test_a_record_with_a_stamp_and_no_uuid_of_its_own_is_refused():
    """Fail closed. It cannot arise from a real sweep, which reads whole VM records, and the
    caller with the most to lose is the one that destroys disks."""
    record = vm_record("agent", owner="xcpng-lab")
    del record["uuid"]
    assert not stamped_itself(record)
    assert owners(record) == set()


def test_the_raw_marker_read_still_sees_what_ownership_refuses():
    """A skipped VM has to be reportable, or the sweep passes over a visibly marked VM in
    silence and the operator reaches for --prefix. marker_values is that read, and it is the
    one place the inherited marker is still visible."""
    copy = vm_record("copy", owner="xcpng-lab", inherited_from="agent")
    assert marker_values(copy) == {"xcpng-lab"}
    assert inherited_marker(copy)
    assert not inherited_marker(vm_record("agent", owner="xcpng-lab"))
    assert not inherited_marker(vm_record("plain"))


@pytest.mark.parametrize(
    "tag",
    [
        "xcpng-cloud-uuid",  # the key with no separator is not the stamp
        "xcpng-cloud-uuidy:x",  # shares the key as a prefix, is a different tag
        "XCPNG-CLOUD-UUID:x",  # tags are case-sensitive in XAPI
    ],
)
def test_a_tag_that_is_not_the_stamp_does_not_stamp(tag):
    assert self_stamps(vm_record("a", tags=[tag])) == set()


def test_the_stamp_prefix_cannot_be_read_as_a_cloud_name():
    """`xcpng-cloud-uuid:` must not start with `xcpng-cloud:`, or every stamp would read as a
    cloud named `-uuid:<uuid>` and the owner filter would match on the stamp alone."""
    assert not SELF_TAG_PREFIX.startswith(OWNER_TAG_PREFIX)
    assert marker_values(vm_record("a", tags=[f"{SELF_TAG_PREFIX}uuid-a"])) == set()


def test_the_stamp_constants_match_the_java_that_writes_them():
    """Same drift guard as the marker above. A stamp the Java spells differently makes every
    tool here refuse every clone, which is the loud failure rather than the silent one -- but
    it is still a failure nobody would attribute to a renamed constant."""
    xapi_client = (_CLIENT / "XapiClient.java").read_text(encoding="utf-8")
    assert (
        'String SELF_KEY = OWNER_KEY + "-uuid";' in xapi_client
    ), "XapiClient.SELF_KEY changed shape; update owner.SELF_KEY to match"
    assert SELF_KEY == OWNER_KEY + "-uuid"

    xo_client = (_CLIENT / "XoRestClient.java").read_text(encoding="utf-8")
    assert (
        'String SELF_TAG_PREFIX = XapiClient.SELF_KEY + ":";' in xo_client
    ), "XoRestClient.SELF_TAG_PREFIX changed shape; update owner.SELF_TAG_PREFIX to match"
    assert SELF_TAG_PREFIX == SELF_KEY + ":"

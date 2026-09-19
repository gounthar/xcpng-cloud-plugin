"""The owner marker, read from either backend (#231).

Before owner.py, three tools each read other_config["xcpng-cloud"] themselves, and a clone
made through Xen Orchestra carries a tag instead, so none of them could see it. The reaper
then reported nothing to reap while an XO cloud's leaked VM held its disk.
"""

import pathlib
import re

import pytest

from fakes import vm_record
from owner import OWNER_KEY, OWNER_TAG_PREFIX, owned_by, owners

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

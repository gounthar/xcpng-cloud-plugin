"""Which cloud, if any, provisioned a VM: the owner marker, read from either backend.

The plugin marks every clone it makes, and how depends on the backend that made it:

- `XapiClient` writes `other_config["xcpng-cloud"] = <cloud>` (`XapiClient.OWNER_KEY`).
- `XoRestClient` cannot, because XO's VM model has no other_config, so it adds the tag
  `xcpng-cloud:<cloud>` instead (`XoRestClient.OWNER_TAG_PREFIX`).

The tools read VM records over XAPI, and a XAPI record carries both `other_config` and
`tags`, so one reader covers both backends with no XO client involved. It used to be three
readers, one each in reaper.py, watch_scrub.py and watch_warm.py, every one of them on
other_config alone. So a VM leaked by an XO cloud was invisible to the reaper, which reported
nothing to reap: the one failure the reaper exists to catch (#231).

The throwaway VMs xo_probe.py and xo_compare.py make are tagged the same way, as clouds
named `xo-probe` and `xo-compare`, so they are found by this too.
"""

# Must match XapiClient.OWNER_KEY. If they drift, every tool here silently selects nothing.
OWNER_KEY = "xcpng-cloud"

# Must match XoRestClient.OWNER_TAG_PREFIX, which the Java builds from OWNER_KEY the same way.
OWNER_TAG_PREFIX = OWNER_KEY + ":"


def owners(record):
    """The cloud names a XAPI VM record is marked as owned by, from either marker.

    A set rather than one name, because nothing stops a VM carrying both markers, or two
    tags: an operator can tag a VM by hand in the XO UI, where tags are visible and
    other_config effectively is not. An empty set means the plugin did not make this VM.

    Presence is what counts. An other_config marker with an empty value is still a marker,
    which is how reaper.py has always read it; a tag with nothing after the prefix is the
    same thing written the other way.
    """
    found = set()
    config = record.get("other_config") or {}
    if OWNER_KEY in config:
        found.add(config[OWNER_KEY])
    for tag in record.get("tags") or []:
        if tag.startswith(OWNER_TAG_PREFIX):
            found.add(tag[len(OWNER_TAG_PREFIX) :])
    return found


def owned_by(record, cloud=None):
    """True when the plugin made this VM, and, if `cloud` is given, that cloud did."""
    found = owners(record)
    return bool(found) if cloud is None else cloud in found

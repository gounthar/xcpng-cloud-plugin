"""Which cloud, if any, provisioned a VM: the owner marker, read from either backend.

The plugin marks every clone it makes, and how depends on the backend that made it:

- `XapiClient` writes `other_config["xcpng-cloud"] = <cloud>` (`OwnerMarker.OWNER_KEY`).
- `XoRestClient` cannot, because XO's VM model has no other_config, so it adds the tag
  `xcpng-cloud:<cloud>` instead (`XoRestClient.OWNER_TAG_PREFIX`).

The tools read VM records over XAPI, and a XAPI record carries both `other_config` and
`tags`, so one reader covers both backends with no XO client involved. It used to be three
readers, one each in reaper.py, watch_scrub.py and watch_warm.py, every one of them on
other_config alone. So a VM leaked by an XO cloud was invisible to the reaper, which reported
nothing to reap: the one failure the reaper exists to catch (#231).

The throwaway VMs xo_probe.py and xo_compare.py make are tagged the same way, as clouds
named `xo-probe` and `xo-compare`, so they are found by this too.

## The marker alone is inherited, which is why there is a second one (#246)

`VM.clone` copies both `other_config` and `tags`. Measured on the lab pool, 8.3.0 / XAPI 26.1,
with positive controls: clone a marked agent by hand and the copy carries the marker, under
either backend. So the marker means "this VM, or something in its ancestry, was made by this
plugin" -- and a reaper selecting on it alone will destroy an operator's investigation clone
with its disks while reporting it as one of ours.

The plugin therefore also stamps the VM's own uuid, as `other_config["xcpng-cloud-uuid"]` or
the tag `xcpng-cloud-uuid:<uuid>`. A copy inherits its *source's* uuid and can be told apart;
a genuine clone carries its own. `owners()` applies that check, so every tool that reads
ownership through this module gets it, which is the three that matter: reaper.py,
watch_scrub.py and watch_warm.py.

Two consequences worth knowing before trusting a sweep:

- **A record carrying the marker and no uuid at all is treated as owned.** Two things produce
  one and neither is going away. A clone made by a plugin older than #246, where refusing would
  strand exactly the leaked disks the reaper exists to reclaim; and the throwaway VMs xo_probe.py
  and xo_compare.py tag as `xo-probe` and `xo-compare`, which stamp no uuid and never will. So
  the grace path is not a migration to be finished later: remove it and those probes' VMs stop
  being findable by any sweep. The inheritance hazard survives for these records and only these.
- **A VM can carry more than one uuid stamp.** XO tags are a set and the plugin's PUT adds to
  it, so a clone whose template was itself a marked clone carries the inherited stamp beside
  its own. Ownership therefore asks whether *any* stamp matches, not whether the only one does.
"""

# Must match OwnerMarker.OWNER_KEY. If they drift, every tool here silently selects nothing.
OWNER_KEY = "xcpng-cloud"

# Must match XoRestClient.OWNER_TAG_PREFIX, which the Java builds from OWNER_KEY the same way.
OWNER_TAG_PREFIX = OWNER_KEY + ":"

# Must match OwnerMarker.SELF_KEY, and XoRestClient.SELF_TAG_PREFIX, which the Java builds from
# it the same way. Note it is not under OWNER_TAG_PREFIX: "xcpng-cloud-uuid:" does not start
# with "xcpng-cloud:", so the owner reader below never mistakes a uuid stamp for a cloud name.
SELF_KEY = OWNER_KEY + "-uuid"

SELF_TAG_PREFIX = SELF_KEY + ":"


def self_stamps(record):
    """Every VM uuid stamped on this record, from either backend's marker.

    A set, for the reason in the module docstring: tags are a set and a clone of a clone
    carries the inherited stamp alongside its own. Empty means the record was stamped by a
    plugin that predates #246, or was never stamped at all.
    """
    found = set()
    config = record.get("other_config") or {}
    if SELF_KEY in config:
        found.add(config[SELF_KEY])
    for tag in record.get("tags") or []:
        if tag.startswith(SELF_TAG_PREFIX):
            found.add(tag[len(SELF_TAG_PREFIX) :])
    return found


def stamped_itself(record):
    """True when this record's own uuid is among the uuids stamped on it.

    The question that separates a clone this plugin made from a hand-made copy of one, both of
    which carry the owner marker. Unstamped records answer True: see the grace path in the
    module docstring.

    A record with stamps but no `uuid` field of its own answers False. That is fail-closed on
    purpose -- the caller with the most to lose is a destructive one -- and it cannot arise
    from a real sweep, which reads whole VM records.
    """
    stamps = self_stamps(record)
    if not stamps:
        return True
    return record.get("uuid") in stamps


def marker_values(record):
    """The cloud names marked on this record, inherited ones included.

    The raw read, with no ownership check. `owners()` is what a tool should select on; this is
    for saying *why* something was skipped, which is the difference between a sweep that
    quietly passes over a marked VM and one an operator can follow.
    """
    found = set()
    config = record.get("other_config") or {}
    if OWNER_KEY in config:
        found.add(config[OWNER_KEY])
    for tag in record.get("tags") or []:
        if tag.startswith(OWNER_TAG_PREFIX):
            found.add(tag[len(OWNER_TAG_PREFIX) :])
    return found


def inherited_marker(record):
    """True when this record carries an owner marker it got from something else.

    Exactly the case #246 is about: an operator's hand-made clone of a plugin agent. Not a
    plugin clone, not unmarked, and worth reporting rather than silently skipping.
    """
    return bool(marker_values(record)) and not stamped_itself(record)


def owners(record):
    """The cloud names this VM is marked as owned by, and was stamped by, from either marker.

    A set rather than one name, because nothing stops a VM carrying both markers, or two
    tags: an operator can tag a VM by hand in the XO UI, where tags are visible and
    other_config effectively is not. An empty set means this plugin did not make this VM.

    Presence is what counts. An other_config marker with an empty value is still a marker,
    which is how reaper.py has always read it; a tag with nothing after the prefix is the
    same thing written the other way.

    A record whose marker was inherited from the VM it was cloned from answers empty, however
    the marker is spelled. That is the #246 check, and it lives here rather than in each tool
    so that a caller cannot select on ownership without it.
    """
    if not stamped_itself(record):
        return set()
    return marker_values(record)


def ambiguous_owner(record):
    """True when this VM is the plugin's but no single cloud can be said to own it.

    A record carrying two owner markers, both corroborated by the one uuid stamp it has. On XO
    that arises from a golden image built off a marked agent: tags are a set and the plugin's
    PUT adds to it, so a clone made by cloud B inherits cloud A's marker and keeps its own,
    and nothing in the data says which of the two stamped it (#250).

    The stamp does not name a cloud, so this is not a gap that better reading closes. It is
    the reason `owned_by` refuses to attribute such a record to any one cloud.
    """
    return len(owners(record)) > 1


def owned_by(record, cloud=None):
    """True when the plugin made this VM, and, if `cloud` is given, that cloud did.

    With a `cloud`, a record carrying more than one owner marker answers False for every one
    of them, because nothing in the record says which cloud stamped it. That is deliberately
    asymmetric with the bare call, which still answers True: such a VM *is* the plugin's, so
    an un-narrowed sweep should still reclaim it, while `--cloud A` must not be able to
    destroy a VM cloud B is using (#250).

    The cost is a real leak going unreaped by a narrowed sweep, which is a false negative in a
    safety net rather than a destroyed VM, and a bare sweep still catches it.
    """
    found = owners(record)
    if cloud is None:
        return bool(found)
    return len(found) == 1 and cloud in found

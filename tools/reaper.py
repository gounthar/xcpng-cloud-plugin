"""Safety net: destroy every VM (and its disks) this plugin provisioned and lost track of.

Runs dry by default. Nothing is destroyed without --apply.

    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py
    curl -gfsu USER:TOKEN "$JENKINS_URL/computer/api/json?tree=computer[displayName]" \
      | XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py --apply --keep-nodes -
    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py --apply --include-live

The marker says the plugin made a VM, not that the plugin lost it. An agent mid-build and a
warm spare carry the same marker as a leak, and only the controller knows which ones it still
uses (#316). So a marker-mode --apply refuses to run until it is told: --keep-nodes takes the
controller's node list (plain names one per line, or the JSON above) and keeps every VM named
after a node in it, the node name being the VM's name_label; --include-live says there is no
node to keep, because the controller is gone or every agent is meant to go, and reaps
everything the marker selects. An empty --keep-nodes list is refused, since a failed curl
produces exactly that. A dry run needs neither. (curl needs -g: without it the brackets in
the query are read as a URL range and the fetch fails.)

The list is a snapshot. A node the controller adds after it was taken is not in it, and its
VM, cloned once the node exists, is reaped if it is already in the pool when this lists VMs.
Pipe the list straight in as above, so the gap is seconds, and run --apply while the cloud is
not provisioning: no build waiting for its labels and no warm pool refilling. A --keep-nodes
file written more than 5 minutes before an --apply is refused (--keep-nodes-max-age changes
the limit), so a saved list cannot be reused long after the fact; stdin is not checked.

Keep using this until destroy_with_disks has survived enough provision/destroy
cycles to be trusted: a half-working teardown leaks VDIs onto a shared pool.

--dom0-check reads `xl list` straight from dom0 and refuses to reap any VM XAPI
reports Halted while Xen still has a live domain for it. XAPI's power_state has
been seen to lie (Halted with domid -1 for a domain still running), and the reap
skips the shutdown on Halted, so without this check it can take a live domain's
disk. Recommended before any --apply on a shared pool; needs sshpass and dom0
root sharing XCPNG_PASS, and fails closed if dom0 cannot be reached.

Selection is by the owner marker the plugin stamps on each clone, not by name: the
`xcpng-cloud` key in other_config on a clone made over XAPI, or the `xcpng-cloud:<cloud>` tag
on one made through Xen Orchestra (owner.py reads both). This tool used to default to
the name prefix "jenkins-ci-" while the plugin named its clones
"xcpng-<template>-<uuid8>", so it matched none of them and exited 0 while
leaked VMs held pool memory and SR space. A marker cannot drift out of sync with
a naming convention, and cannot be acquired by an unluckily named golden image.

It CAN be acquired by being cloned from something that carries it, which is #246:
`VM.clone` copies other_config and tags alike, so a VM an operator clones by hand off a
failing agent inherits the marker. The plugin therefore also stamps each clone's own uuid,
and owner.py refuses a marker whose uuid belongs to some other VM. Such a VM is reported
as skipped rather than passed over in silence. A clone stamped by a plugin older than #246
carries no uuid and is still reaped, because it is a real leak; the hazard survives there.

--prefix still exists for the pre-plugin probe VMs (measure_clone.py names them
"jenkins-ci-probe-N"), which carry no marker. It is opt-in and guarded: a name
prefix cannot tell an agent from the golden image every future provision needs.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time

from owner import (
    OWNER_KEY,
    ambiguous_owner,
    inherited_marker,
    marker_values,
    owned_by,
    owners,
)
from xapi import Xapi, XapiError

# How old a --keep-nodes file may be before an --apply refuses it. Long enough to fetch the list
# and type the command, short enough that a list saved yesterday is not reused.
KEEP_NODES_MAX_AGE_MINUTES = 5

# The legacy tools-era prefix, kept only as the suggested value for --prefix.
LEGACY_PREFIX = "jenkins-ci-"


def live_domains_on_dom0(host, password):
    """The names Xen actually has a live domain for, read straight from dom0.

    The safety net's second opinion. XAPI's power_state can read Halted for a domain Xen still
    has running: on the lab pool a VM read power_state=Halted with domid -1 from XAPI while
    `xl list` on dom0 showed it live with climbing CPU time. destroy_with_disks gates its
    VM.hard_shutdown on that same power_state, so it skips the shutdown and takes the live
    domain's disk. Asking XAPI to check XAPI inherits the lie; dom0 is the only honest source.

    Returns the set of Xen domain names (the first column of `xl list`). On XCP-ng 8.3 that name
    is the VM name_label (measured 2026-07-18: every live guest matched by name_label, none by
    uuid); callers still match on both uuid and name_label so the check does not depend on it.
    Raises XapiError if dom0 cannot be reached, so an --apply that asked for the check fails
    closed rather than sweeping blind. Reads `xl list` on the connected host only, which is the
    whole pool on a single-host lab; on a multi-host pool x.host is just the master, so a domain
    resident on another host would not be seen (out of scope here). Assumes dom0 root shares
    XCPNG_PASS (true on a single-host lab); uses sshpass with password auth as tools/lab-cookbook.md
    does. Nothing is written to disk.
    """
    if shutil.which("sshpass") is None:
        raise XapiError(
            "DOM0_CHECK_UNAVAILABLE",
            "sshpass is required for --dom0-check (e.g. apt-get install sshpass)",
        )
    try:
        completed = subprocess.run(
            [
                "sshpass",
                "-e",
                "ssh",
                "-o",
                "PubkeyAuthentication=no",
                "-o",
                "PreferredAuthentications=password",
                "-o",
                "StrictHostKeyChecking=no",
                "-o",
                "UserKnownHostsFile=/dev/null",
                "-o",
                "ConnectTimeout=10",
                f"root@{host}",
                "xl list",
            ],
            env={**os.environ, "SSHPASS": password},
            capture_output=True,
            text=True,
            timeout=30,
            check=True,
        )
    except (subprocess.SubprocessError, OSError) as e:
        # A failed check must not read as "dom0 is clean". Raise so --apply fails closed.
        detail = getattr(e, "stderr", "") or str(e) or e.__class__.__name__
        raise XapiError(
            "DOM0_UNREACHABLE", f"xl list on {host}: {detail}".strip()
        ) from e

    # `xl list` prints a header row, then one row per running domain; Domain-0 is dom0 itself.
    # A genuinely halted guest is absent entirely, so anything here is a domain Xen is running.
    names = set()
    for line in completed.stdout.splitlines()[1:]:
        parts = line.split()
        if parts:
            names.add(parts[0])
    return names


def _live_but_halted(rec, live_names):
    """True when XAPI reports this VM Halted but dom0 still has a live domain for it.

    That exact pair is the desync that costs a disk: destroy_with_disks skips the shutdown on
    Halted and destroys the disk under the running domain. A VM XAPI reports Running that dom0
    also shows live is consistent, not the lie, and reaps normally (a leaked running agent must
    still be destroyable).
    """
    if rec["power_state"] != "Halted":
        return False
    return rec["uuid"] in live_names or rec["name_label"] in live_names


def _selector(args):
    """Return (predicate, description) for what this run reaps.

    Marker mode is the default and matches only a VM that carries the plugin's marker AND
    its own uuid stamped beside it, so a hand-made clone that inherited the marker from a
    plugin agent is not selected (#246). What it cannot separate is a clone stamped by a
    plugin older than that check, which carries no uuid: those are reaped, and a copy of
    one would be reaped with them. Prefix mode is opt-in, and matches on a string an
    operator's VM or the golden image can share by accident.
    """
    if args.prefix is None:
        if args.cloud:
            return (
                lambda rec: owned_by(rec, args.cloud),
                f"provisioned by cloud {args.cloud!r}",
            )
        return (
            lambda rec: owned_by(rec),
            f"carrying the {OWNER_KEY!r} marker",
        )
    return (
        lambda rec: rec["name_label"].startswith(args.prefix),
        f"named {args.prefix}*",
    )


def read_node_names(path):
    """The node names in a --keep-nodes file, or stdin for "-".

    Accepts either plain names, one per line (blank lines and `#` comments ignored), or the JSON
    the controller returns from /computer/api/json, so its output can be piped in unedited.
    Names that are not this plugin's (the built-in node, other clouds' agents) are harmless:
    they match no marked VM.
    """
    if path == "-":
        text = sys.stdin.read()
    else:
        with open(path, encoding="utf-8") as f:
            text = f.read()
    stripped = text.strip()
    if stripped.startswith("{"):
        try:
            data = json.loads(stripped)
        except ValueError as e:
            raise ValueError(f"{path}: looks like JSON but does not parse: {e}") from e
        computers = data.get("computer") if isinstance(data, dict) else None
        if not isinstance(computers, list):
            raise ValueError(
                f"{path}: JSON without a 'computer' list; expected /computer/api/json"
            )
        # Every entry or nothing: dropping one that carries no name would leave a shorter list
        # that still passes the empty check, and the agent it stood for would be reaped.
        for c in computers:
            if not (isinstance(c, dict) and isinstance(c.get("displayName"), str)):
                raise ValueError(
                    f"{path}: computer entry {str(c)[:60]!r} has no displayName; "
                    "fetch with ?tree=computer[displayName]"
                )
        return {c["displayName"] for c in computers}
    names = set()
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            # Jenkins refuses '<' and '>' in a node name, so a line carrying one is not a name. It is
            # what a failed login looks like: `curl -s` without -f passes the HTML error page through,
            # and read as names it keeps nothing while looking like a non-empty answer.
            if "<" in line or ">" in line:
                raise ValueError(
                    f"{path}: {line[:60]!r} is not a node name; this looks like an HTML "
                    "page, e.g. a failed login. Fetch the list with curl -f."
                )
            names.add(line)
    return names


def _confirm(doomed, what):
    """Make the operator retype the count before a prefix-mode --apply destroys anything.

    Prefix mode is the path that has already been documented as able to take the golden
    image (docs/golden-image.md), which is not recoverable without a rebuild. Skipped when
    stdin is not a TTY, where there is nobody to ask; use --force in automation.
    """
    # sys.stdin is None when the interpreter runs with stdin detached, not merely closed. Both mean
    # the same thing here: nobody to confirm to, so fail closed rather than sweep by name unattended.
    if sys.stdin is None or not sys.stdin.isatty():
        print(
            "refusing a non-interactive prefix --apply without --force.",
            file=sys.stderr,
        )
        return False
    print(
        f"\nAbout to permanently destroy {len(doomed)} VM(s) {what}, with their disks."
    )
    answer = input(f"Type the number {len(doomed)} to confirm: ").strip()
    if answer != str(len(doomed)):
        print("not confirmed; nothing destroyed.", file=sys.stderr)
        return False
    return True


def main():
    parser = argparse.ArgumentParser(
        description="Destroy VMs this plugin provisioned and lost track of, with their disks."
    )
    parser.add_argument(
        "--apply", action="store_true", help="actually destroy (default: dry run)"
    )
    parser.add_argument(
        "--cloud",
        default=None,
        help="only VMs marked as owned by this cloud name (default: any marked VM)",
    )
    parser.add_argument(
        "--prefix",
        default=None,
        help="legacy: select by name prefix instead of the marker, for pre-plugin probe "
        f"VMs (e.g. {LEGACY_PREFIX!r}). Guarded; prefer the default marker mode.",
    )
    parser.add_argument(
        "--keep-nodes",
        metavar="FILE",
        default=None,
        help="the controller's node list, one name per line or the JSON from "
        "/computer/api/json ('-' reads stdin). A VM named after a node in it is "
        "kept: it is a live agent or a warm spare, not a leak.",
    )
    parser.add_argument(
        "--keep-nodes-max-age",
        metavar="MINUTES",
        type=float,
        default=KEEP_NODES_MAX_AGE_MINUTES,
        help="refuse an --apply whose --keep-nodes file was last written longer ago than "
        f"this (default {KEEP_NODES_MAX_AGE_MINUTES:g}): agents provisioned since are not in "
        "it and would be reaped. stdin is always fresh.",
    )
    parser.add_argument(
        "--include-live",
        action="store_true",
        help="state that no VM is to be kept (the controller is gone, or every agent "
        "is meant to go). A marker-mode --apply needs this or --keep-nodes.",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="skip the confirmation prompt for a prefix-mode --apply",
    )
    parser.add_argument(
        "--dom0-check",
        action="store_true",
        help="before destroying, read `xl list` from dom0 and refuse any VM XAPI "
        "reports Halted that Xen still runs (power_state can lie; the reap "
        "would then take a live domain's disk). Needs sshpass and dom0 root "
        "sharing XCPNG_PASS; fails closed if dom0 is unreachable.",
    )
    args = parser.parse_args()

    if args.prefix is not None and args.cloud:
        parser.error("--cloud filters the marker; it cannot be combined with --prefix")

    # Every name starts with "", so an empty prefix matches the entire pool.
    if args.apply and args.prefix is not None and not args.prefix:
        parser.error(
            "refusing --apply with an empty --prefix: it matches every VM on the pool"
        )

    # A prefix shorter than the legacy default is too blunt to distinguish an agent from the
    # golden image: "jenkins-" already takes jenkins-golden-debian and its disk, unrecoverable
    # without a rebuild. Demand --force so it cannot happen through a typo.
    if (
        args.apply
        and args.prefix
        and len(args.prefix) < len(LEGACY_PREFIX)
        and not args.force
    ):
        parser.error(
            f"refusing --apply with a prefix shorter than {LEGACY_PREFIX!r} ({args.prefix!r}): it can match "
            "the golden image. Re-run with --force if you are certain."
        )

    if args.keep_nodes is not None and args.include_live:
        parser.error("--keep-nodes and --include-live contradict each other; pass one")

    # The marker cannot tell a leak from an agent the controller is using, so a marker-mode sweep
    # that destroys must be told which is which before it touches the pool (#316). Prefix mode has
    # its own retype-the-count confirmation.
    if (
        args.apply
        and args.prefix is None
        and args.keep_nodes is None
        and not args.include_live
    ):
        parser.error(
            "refusing a marker-mode --apply that does not know which agents are live: every agent "
            "and warm spare carries the marker too. Pass --keep-nodes with the controller's node "
            "list, or --include-live if nothing is to be kept."
        )

    # A saved list knows nothing of agents provisioned since it was written, and each of them
    # reads as a leak. Piping it in keeps that gap to seconds; reusing a file stretches it to
    # however long ago the file was made. A dry run destroys nothing, so it is not held to this.
    if args.apply and args.keep_nodes not in (None, "-"):
        try:
            age = (time.time() - os.path.getmtime(args.keep_nodes)) / 60
        except OSError as e:
            parser.error(f"--keep-nodes: {e}")
        if age > args.keep_nodes_max_age:
            parser.error(
                f"--keep-nodes {args.keep_nodes} was written {age:.0f} minutes ago; agents "
                "provisioned since are not in it and would be reaped. Fetch the list again, or "
                "pipe it in with '-'. --keep-nodes-max-age raises the limit."
            )

    keep = set()
    if args.keep_nodes is not None:
        try:
            keep = read_node_names(args.keep_nodes)
        except (OSError, ValueError) as e:
            parser.error(f"--keep-nodes: {e}")
        # A failed curl writes nothing, and "nothing is live" would reap every agent: the bug
        # this option exists to prevent. The built-in node alone also says nothing about agents,
        # but it is a real answer from a real controller, so it is accepted.
        if not keep:
            parser.error(
                f"--keep-nodes {args.keep_nodes}: no node names in it. If no node is "
                "live, say so with --include-live instead."
            )

    match, what = _selector(args)

    with Xapi() as x:
        sr = x.default_sr()
        free_before = x.sr_free_bytes(sr)
        vdis_before = x.vdi_count(sr)

        doomed = []
        inherited = []
        ambiguous = []
        kept = []
        for vm, rec in x.call("VM.get_all_records").items():
            # Snapshots are VM objects too, and a snapshot of jenkins-ci-agent-3 inherits a
            # matching name. It also reports power_state=Halted, so it is indistinguishable
            # from a dead agent in the listing below. Destroying one takes the operator's
            # restore point with it.
            if rec["is_a_template"] or rec["is_control_domain"] or rec["is_a_snapshot"]:
                continue
            if match(rec) and rec["name_label"] in keep:
                kept.append(rec)
            elif match(rec):
                doomed.append((vm, rec))
            elif args.prefix is None and inherited_marker(rec):
                # Marked, and not ours: the marker came from whatever this was cloned from.
                # Say so. A VM that visibly carries `xcpng-cloud` and is not in the list below
                # otherwise looks like the sweep missing it, which is the reading that gets a
                # --prefix run reached for next.
                #
                # Narrowed by --cloud the same way the sweep itself is. An operator asking about
                # one cloud is not helped by a notice about somebody else's inherited marker, and
                # a notice they have no use for is how a useful one stops being read.
                if args.cloud is None or args.cloud in marker_values(rec):
                    inherited.append(rec)
            elif (
                args.cloud is not None
                and ambiguous_owner(rec)
                and args.cloud in owners(rec)
            ):
                # Ours, but carrying a second cloud's marker as well, so nothing in the record
                # says which one stamped it (#250). A narrowed sweep refuses to attribute it
                # rather than risk destroying the other cloud's VM. Report it, because a VM
                # that visibly carries this cloud's name and is absent from the list below
                # reads as the sweep missing it.
                ambiguous.append(rec)
        doomed.sort(key=lambda pair: pair[1]["name_label"])
        inherited.sort(key=lambda rec: rec["name_label"])
        ambiguous.sort(key=lambda rec: rec["name_label"])
        kept.sort(key=lambda rec: rec["name_label"])

        print(f"SR free before : {free_before / 2**30:.2f} GiB   VDIs: {vdis_before}")
        print(f"matching VMs {what}: {len(doomed)} VM(s)")

        for rec in kept:
            print(
                f"  KEEPING {rec['name_label']!r} uuid={rec['uuid']}: a node on the controller, "
                f"so a live agent or a warm spare rather than a leak."
            )

        for rec in inherited:
            names = (
                ", ".join(sorted(repr(n) for n in marker_values(rec)))
                or "no cloud name"
            )
            print(
                f"  SKIPPING {rec['name_label']!r} uuid={rec['uuid']}: carries the "
                f"{OWNER_KEY!r} marker ({names}) inherited from the VM it was cloned from, "
                f"not stamped by this plugin. Destroy it by hand if it is yours."
            )

        for rec in ambiguous:
            names = ", ".join(sorted(repr(n) for n in owners(rec)))
            # Two things this must not say, both found in review. It carries however many uuid
            # stamps its ancestry left on it, not one, and only the count that MATCHES is one;
            # and "just run it without --cloud" is dangerous advice, because marker mode selects
            # every marked VM on the pool and, unlike prefix mode, never asks for confirmation.
            print(
                f"  SKIPPING {rec['name_label']!r} uuid={rec['uuid']}: carries markers for "
                f"more than one cloud ({names}), and the uuid stamp matching this record does "
                f"not say which of them provisioned it. Destroy it by hand if it is yours. A "
                f"run without --cloud does reap it, but it selects every marked VM on the "
                f"pool and marker mode does not confirm, so read its dry run first."
            )

        if not doomed:
            print(
                "\nnothing to reap." if args.apply else "\nnothing to reap (dry run)."
            )
            return 0

        # Second opinion from dom0 before anything is destroyed. XAPI's power_state can read
        # Halted for a domain Xen still has running; destroy_with_disks would then skip the
        # shutdown and take the live domain's disk. Refuse exactly that pair. A consistent
        # record (including a leaked *running* agent) is not the lie and reaps normally. If
        # dom0 is unreachable this raises and the sweep fails closed, destroying nothing.
        refused = []
        if args.dom0_check:
            live = live_domains_on_dom0(x.host, os.environ.get("XCPNG_PASS", ""))
            allowed = []
            for vm, rec in doomed:
                if _live_but_halted(rec, live):
                    refused.append(rec["name_label"])
                    print(
                        f"  REFUSING {rec['name_label']!r} uuid={rec['uuid']}: XAPI says "
                        f"{rec['power_state']} but dom0 has a live domain for it. Investigate "
                        f"with `xl list` / `xl destroy` before reaping it.",
                        file=sys.stderr,
                    )
                else:
                    allowed.append((vm, rec))
            doomed = allowed

        # Prefix mode only. Marker mode selects on a marker plus a matching uuid stamp, so the VMs
        # it can reach are the plugin's own clones and clones stamped before that check existed;
        # making it prompt too would train the operator to type through it.
        if args.apply and args.prefix is not None and not args.force:
            for _, rec in doomed:
                print(
                    f"  {rec['power_state']:8} {rec['name_label']!r} uuid={rec['uuid']}"
                )
            if not _confirm(doomed, what):
                return 2

        # One VM stuck mid-operation must not abandon the rest of the sweep. This is the
        # safety net; it fails soft, reports what it could not reach, and exits non-zero.
        failed = []
        for vm, rec in doomed:
            name = rec["name_label"]
            try:
                vdis = x.disk_vdis(vm)
                print(
                    f"  {rec['power_state']:8} {name!r} uuid={rec['uuid']} disks={len(vdis)}"
                )
                if args.apply:
                    destroyed = x.destroy_with_disks(vm)
                    print(f"     destroyed VM + {len(destroyed)} VDI(s)")
            except XapiError as e:
                failed.append(name)
                print(f"     ERROR: {name!r} not reaped: {e}", file=sys.stderr)

        if not args.apply:
            print("\nDRY RUN - nothing destroyed. Re-run with --apply.")
            return 1 if (failed or refused) else 0

        free_after = x.sr_free_bytes(sr)
        vdis_after = x.vdi_count(sr)
        print(f"\nSR free after  : {free_after / 2**30:.2f} GiB   VDIs: {vdis_after}")
        print(
            f"reclaimed      : {(free_after - free_before) / 2**30:+.2f} GiB   "
            f"VDI delta: {vdis_after - vdis_before:+d}"
        )

        rc = 0
        if failed:
            print(
                f"WARNING: {len(failed)} VM(s) not reaped: {', '.join(failed)}",
                file=sys.stderr,
            )
            rc = 1
        if refused:
            print(
                f"WARNING: {len(refused)} VM(s) refused by the dom0 check (live on dom0 while "
                f"XAPI read Halted): {', '.join(refused)}. Resolve on dom0 before re-running.",
                file=sys.stderr,
            )
            rc = 1
        if vdis_after > vdis_before:
            print("WARNING: VDI count rose. Disks were orphaned.", file=sys.stderr)
            rc = 1
        return rc


if __name__ == "__main__":
    try:
        sys.exit(main())
    except XapiError as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(2)

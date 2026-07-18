"""Safety net: destroy every VM (and its disks) this plugin provisioned and lost track of.

Runs dry by default. Nothing is destroyed without --apply.

    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py
    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py --apply
    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py --apply --dom0-check

Keep using this until destroy_with_disks has survived enough provision/destroy
cycles to be trusted: a half-working teardown leaks VDIs onto a shared pool.

--dom0-check reads `xl list` straight from dom0 and refuses to reap any VM XAPI
reports Halted while Xen still has a live domain for it. XAPI's power_state has
been seen to lie (Halted with domid -1 for a domain still running), and the reap
skips the shutdown on Halted, so without this check it can take a live domain's
disk. Recommended before any --apply on a shared pool; needs sshpass and dom0
root sharing XCPNG_PASS, and fails closed if dom0 cannot be reached.

Selection is by the `xcpng-cloud` marker the plugin stamps into each clone's
other_config (XapiClient.OWNER_KEY), not by name. This tool used to default to
the name prefix "jenkins-ci-" while the plugin named its clones
"xcpng-<template>-<uuid8>", so it matched none of them and exited 0 while
leaked VMs held pool memory and SR space. A marker cannot drift out of sync with
a naming convention, and cannot be acquired by an unluckily named golden image.

--prefix still exists for the pre-plugin probe VMs (measure_clone.py names them
"jenkins-ci-probe-N"), which carry no marker. It is opt-in and guarded: a name
prefix cannot tell an agent from the golden image every future provision needs.
"""

import argparse
import os
import shutil
import subprocess
import sys

from xapi import Xapi, XapiError

# Must match XapiClient.OWNER_KEY. If they drift, this tool silently reaps nothing again.
OWNER_KEY = "xcpng-cloud"

# The legacy tools-era prefix, kept only as the suggested value for --prefix.
LEGACY_PREFIX = "jenkins-ci-"


def live_domains_on_dom0(host, password):
    """The names Xen actually has a live domain for, read straight from dom0.

    The safety net's second opinion. XAPI's power_state can read Halted for a domain Xen still
    has running: on the lab pool a VM read power_state=Halted with domid -1 from XAPI while
    `xl list` on dom0 showed it live with climbing CPU time. destroy_with_disks gates its
    VM.hard_shutdown on that same power_state, so it skips the shutdown and takes the live
    domain's disk. Asking XAPI to check XAPI inherits the lie; dom0 is the only honest source.

    Returns the set of Xen domain names (the first column of `xl list`). On XCP-ng that name is
    the VM uuid; callers match on both uuid and name_label so the check does not depend on it.
    Raises XapiError if dom0 cannot be reached, so an --apply that asked for the check fails
    closed rather than sweeping blind. Reads `xl list` on the connected host only, which is the
    whole pool on a single-host lab; on a multi-host pool x.host is just the master, so a domain
    resident on another host would not be seen (out of scope here). Assumes dom0 root shares
    XCPNG_PASS (true on a single-host lab); uses sshpass with password auth as tools/lab-cookbook.md
    does. Nothing is written to disk.
    """
    if shutil.which("sshpass") is None:
        raise XapiError("DOM0_CHECK_UNAVAILABLE",
                        "sshpass is required for --dom0-check (e.g. apt-get install sshpass)")
    try:
        completed = subprocess.run(
            ["sshpass", "-e", "ssh",
             "-o", "PubkeyAuthentication=no",
             "-o", "PreferredAuthentications=password",
             "-o", "StrictHostKeyChecking=no",
             "-o", "UserKnownHostsFile=/dev/null",
             "-o", "ConnectTimeout=10",
             f"root@{host}", "xl list"],
            env={**os.environ, "SSHPASS": password},
            capture_output=True, text=True, timeout=30, check=True)
    except (subprocess.SubprocessError, OSError) as e:
        # A failed check must not read as "dom0 is clean". Raise so --apply fails closed.
        detail = getattr(e, "stderr", "") or str(e) or e.__class__.__name__
        raise XapiError("DOM0_UNREACHABLE", f"xl list on {host}: {detail}".strip()) from e

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

    Marker mode is the default and is safe by construction: only a VM the plugin stamped
    can match. Prefix mode is opt-in, and matches on a string an operator's VM or the
    golden image can share by accident.
    """
    if args.prefix is None:
        if args.cloud:
            return (
                lambda rec: (rec.get("other_config") or {}).get(OWNER_KEY) == args.cloud,
                f"provisioned by cloud {args.cloud!r}",
            )
        return (
            lambda rec: OWNER_KEY in (rec.get("other_config") or {}),
            f"carrying the {OWNER_KEY!r} marker",
        )
    return (
        lambda rec: rec["name_label"].startswith(args.prefix),
        f"named {args.prefix}*",
    )


def _confirm(doomed, what):
    """Make the operator retype the count before a prefix-mode --apply destroys anything.

    Prefix mode is the path that has already been documented as able to take the golden
    image (docs/golden-image.md), which is not recoverable without a rebuild. Skipped when
    stdin is not a TTY, where there is nobody to ask; use --force in automation.
    """
    # sys.stdin is None when the interpreter runs with stdin detached, not merely closed. Both mean
    # the same thing here: nobody to confirm to, so fail closed rather than sweep by name unattended.
    if sys.stdin is None or not sys.stdin.isatty():
        print("refusing a non-interactive prefix --apply without --force.", file=sys.stderr)
        return False
    print(f"\nAbout to permanently destroy {len(doomed)} VM(s) {what}, with their disks.")
    answer = input(f"Type the number {len(doomed)} to confirm: ").strip()
    if answer != str(len(doomed)):
        print("not confirmed; nothing destroyed.", file=sys.stderr)
        return False
    return True


def main():
    parser = argparse.ArgumentParser(
        description="Destroy VMs this plugin provisioned and lost track of, with their disks.")
    parser.add_argument("--apply", action="store_true", help="actually destroy (default: dry run)")
    parser.add_argument("--cloud", default=None,
                        help="only VMs marked as owned by this cloud name (default: any marked VM)")
    parser.add_argument("--prefix", default=None,
                        help="legacy: select by name prefix instead of the marker, for pre-plugin probe "
                             f"VMs (e.g. {LEGACY_PREFIX!r}). Guarded; prefer the default marker mode.")
    parser.add_argument("--force", action="store_true",
                        help="skip the confirmation prompt for a prefix-mode --apply")
    parser.add_argument("--dom0-check", action="store_true",
                        help="before destroying, read `xl list` from dom0 and refuse any VM XAPI "
                             "reports Halted that Xen still runs (power_state can lie; the reap "
                             "would then take a live domain's disk). Needs sshpass and dom0 root "
                             "sharing XCPNG_PASS; fails closed if dom0 is unreachable.")
    args = parser.parse_args()

    if args.prefix is not None and args.cloud:
        parser.error("--cloud filters the marker; it cannot be combined with --prefix")

    # Every name starts with "", so an empty prefix matches the entire pool.
    if args.apply and args.prefix is not None and not args.prefix:
        parser.error("refusing --apply with an empty --prefix: it matches every VM on the pool")

    # A prefix shorter than the legacy default is too blunt to distinguish an agent from the
    # golden image: "jenkins-" already takes jenkins-golden-debian and its disk, unrecoverable
    # without a rebuild. Demand --force so it cannot happen through a typo.
    if (args.apply and args.prefix and len(args.prefix) < len(LEGACY_PREFIX)
            and not args.force):
        parser.error(
            f"refusing --apply with a prefix shorter than {LEGACY_PREFIX!r} ({args.prefix!r}): it can match "
            "the golden image. Re-run with --force if you are certain.")

    match, what = _selector(args)

    with Xapi() as x:
        sr = x.default_sr()
        free_before = x.sr_free_bytes(sr)
        vdis_before = x.vdi_count(sr)

        doomed = []
        for vm, rec in x.call("VM.get_all_records").items():
            # Snapshots are VM objects too, and a snapshot of jenkins-ci-agent-3 inherits a
            # matching name. It also reports power_state=Halted, so it is indistinguishable
            # from a dead agent in the listing below. Destroying one takes the operator's
            # restore point with it.
            if rec["is_a_template"] or rec["is_control_domain"] or rec["is_a_snapshot"]:
                continue
            if match(rec):
                doomed.append((vm, rec))
        doomed.sort(key=lambda pair: pair[1]["name_label"])

        print(f"SR free before : {free_before / 2**30:.2f} GiB   VDIs: {vdis_before}")
        print(f"matching VMs {what}: {len(doomed)} VM(s)")

        if not doomed:
            print("\nnothing to reap." if args.apply else "\nnothing to reap (dry run).")
            return 0

        # Second opinion from dom0 before anything is destroyed. XAPI's power_state can read
        # Halted for a domain Xen still has running; destroy_with_disks would then skip the
        # shutdown and take the live domain's disk. Refuse exactly that pair. A consistent
        # record (including a leaked *running* agent) is not the lie and reaps normally. If
        # dom0 is unreachable this raises and the sweep fails closed, destroying nothing.
        refused = []
        if args.dom0_check:
            live = live_domains_on_dom0(x.host, os.environ.get("XCPNG_PASS", ""))
            kept = []
            for vm, rec in doomed:
                if _live_but_halted(rec, live):
                    refused.append(rec["name_label"])
                    print(f"  REFUSING {rec['name_label']!r} uuid={rec['uuid']}: XAPI says "
                          f"{rec['power_state']} but dom0 has a live domain for it. Investigate "
                          f"with `xl list` / `xl destroy` before reaping it.", file=sys.stderr)
                else:
                    kept.append((vm, rec))
            doomed = kept

        # Prefix mode only. Marker mode cannot select a VM the plugin did not create, so there is
        # nothing to second-guess; making it prompt too would train the operator to type through it.
        if args.apply and args.prefix is not None and not args.force:
            for _, rec in doomed:
                print(f"  {rec['power_state']:8} {rec['name_label']!r} uuid={rec['uuid']}")
            if not _confirm(doomed, what):
                return 2

        # One VM stuck mid-operation must not abandon the rest of the sweep. This is the
        # safety net; it fails soft, reports what it could not reach, and exits non-zero.
        failed = []
        for vm, rec in doomed:
            name = rec["name_label"]
            try:
                vdis = x.disk_vdis(vm)
                print(f"  {rec['power_state']:8} {name!r} uuid={rec['uuid']} disks={len(vdis)}")
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
        print(f"reclaimed      : {(free_after - free_before) / 2**30:+.2f} GiB   "
              f"VDI delta: {vdis_after - vdis_before:+d}")

        rc = 0
        if failed:
            print(f"WARNING: {len(failed)} VM(s) not reaped: {', '.join(failed)}", file=sys.stderr)
            rc = 1
        if refused:
            print(f"WARNING: {len(refused)} VM(s) refused by the dom0 check (live on dom0 while "
                  f"XAPI read Halted): {', '.join(refused)}. Resolve on dom0 before re-running.",
                  file=sys.stderr)
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

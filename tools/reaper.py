"""Safety net: destroy every VM (and its disks) this plugin provisioned and lost track of.

Runs dry by default. Nothing is destroyed without --apply.

    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py
    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py --apply

Keep using this until destroy_with_disks has survived enough provision/destroy
cycles to be trusted: a half-working teardown leaks VDIs onto a shared pool.

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
import sys

from xapi import Xapi, XapiError

# Must match XapiClient.OWNER_KEY. If they drift, this tool silently reaps nothing again.
OWNER_KEY = "xcpng-cloud"

# The legacy tools-era prefix, kept only as the suggested value for --prefix.
LEGACY_PREFIX = "jenkins-ci-"


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
            return 1 if failed else 0

        free_after = x.sr_free_bytes(sr)
        vdis_after = x.vdi_count(sr)
        print(f"\nSR free after  : {free_after / 2**30:.2f} GiB   VDIs: {vdis_after}")
        print(f"reclaimed      : {(free_after - free_before) / 2**30:+.2f} GiB   "
              f"VDI delta: {vdis_after - vdis_before:+d}")

        rc = 0
        if failed:
            print(f"WARNING: {len(failed)} VM(s) not reaped: {', '.join(failed)}", file=sys.stderr)
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

"""Safety net: destroy every spike VM (and its disks) matching a name prefix.

Runs dry by default. Nothing is destroyed without --apply.

    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py
    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py --apply

Keep using this until destroy_with_disks has survived enough provision/destroy
cycles to be trusted: a half-working teardown leaks VDIs onto a shared pool.
"""

import argparse
import sys

from xapi import Xapi, XapiError

PREFIX = "jenkins-ci-"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="actually destroy (default: dry run)")
    parser.add_argument("--prefix", default=PREFIX,
                        help=f"destroy VMs whose name starts with this (default: {PREFIX!r})")
    args = parser.parse_args()

    # Every name starts with "", so an empty prefix matches the entire pool.
    if args.apply and not args.prefix:
        parser.error("refusing --apply with an empty --prefix: it matches every VM on the pool")

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
            if rec["name_label"].startswith(args.prefix):
                doomed.append((vm, rec))
        doomed.sort(key=lambda pair: pair[1]["name_label"])

        print(f"SR free before : {free_before / 2**30:.2f} GiB   VDIs: {vdis_before}")
        print(f"matching '{args.prefix}*': {len(doomed)} VM(s)")

        if not doomed:
            print("\nnothing to reap." if args.apply else "\nnothing to reap (dry run).")
            return 0

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

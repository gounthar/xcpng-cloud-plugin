"""Safety net: destroy every spike VM (and its disks) matching a name prefix.

Runs dry by default. Nothing is destroyed without --apply.

    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py
    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py --apply

Keep using this until destroy_with_disks has survived enough provision/destroy
cycles to be trusted: a half-working teardown leaks VDIs onto a shared pool.
"""

import argparse
import sys

from xapi import Xapi

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
            if rec["is_a_template"] or rec["is_control_domain"]:
                continue
            if rec["name_label"].startswith(args.prefix):
                doomed.append((vm, rec))
        doomed.sort(key=lambda pair: pair[1]["name_label"])

        print(f"SR free before : {free_before / 2**30:.2f} GiB   VDIs: {vdis_before}")
        print(f"matching '{args.prefix}*': {len(doomed)} VM(s)")

        if not doomed:
            print("\nnothing to reap." if args.apply else "\nnothing to reap (dry run).")
            return 0

        for vm, rec in doomed:
            vdis = x.disk_vdis(vm)
            print(f"  {rec['power_state']:8} {rec['name_label']!r} uuid={rec['uuid']} disks={len(vdis)}")
            if args.apply:
                destroyed = x.destroy_with_disks(vm)
                print(f"     destroyed VM + {len(destroyed)} VDI(s)")

        if not args.apply:
            print("\nDRY RUN - nothing destroyed. Re-run with --apply.")
            return 0

        free_after = x.sr_free_bytes(sr)
        vdis_after = x.vdi_count(sr)
        print(f"\nSR free after  : {free_after / 2**30:.2f} GiB   VDIs: {vdis_after}")
        print(f"reclaimed      : {(free_after - free_before) / 2**30:+.2f} GiB   "
              f"VDI delta: {vdis_after - vdis_before:+d}")
        if vdis_after > vdis_before:
            print("WARNING: VDI count rose. Disks were orphaned.", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

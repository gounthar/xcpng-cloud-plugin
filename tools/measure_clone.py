"""M1 kill check: how long from VM.clone to an agent that could accept work?

Measures the three phases the Jenkins plugin will live inside:
    clone   - Async.VM.clone (copy-on-write) or Async.VM.copy (full disk copy)
    boot    - Async.VM.start until power_state == Running
    online  - until guest tools report an IP via VM_guest_metrics.networks

The last phase is the honest proxy for "agent could connect": it is the moment the
guest is up enough to talk to the outside world. Also verifies teardown reclaims
every VDI, so the storage-leak trap is measured, not assumed.

    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/measure_clone.py --source alpine-test-1
"""

import argparse
import re
import statistics
import sys
import time

from xapi import COW_SR_TYPES, Xapi, XapiError

IP_TIMEOUT = 180
BOOT_TIMEOUT = 120
UUID_ARG = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def wait_running(x, vm, timeout=BOOT_TIMEOUT):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if x.call("VM.get_power_state", vm) == "Running":
            return
        time.sleep(0.25)
    raise XapiError("BOOT_TIMEOUT", vm)


def wait_ip(x, vm, timeout=IP_TIMEOUT):
    """Poll guest metrics for an IPv4. Empty until the in-guest agent writes to xenstore."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        gm = x.call("VM.get_guest_metrics", vm)
        if gm and "NULL" not in gm:
            try:
                nets = x.call("VM_guest_metrics.get_networks", gm)
            except XapiError:
                nets = {}
            for key, value in sorted((nets or {}).items()):
                if key.endswith("/ip") and value:
                    return value
        time.sleep(0.5)
    return None


def one_cycle(x, source, sr, index, full_copy, cow):
    name = f"jenkins-ci-probe-{index}"
    if full_copy:
        mode = "VM.copy (full)"
    else:
        # On an LVM SR, VM.clone full-copies too. Do not label its timings CoW.
        mode = "VM.clone (CoW)" if cow else "VM.clone (full)"
    free_start = x.sr_free_bytes(sr)

    t0 = time.monotonic()
    if full_copy:
        task = x.call("Async.VM.copy", source, name, sr)
    else:
        task = x.call("Async.VM.clone", source, name)
    vm = x.await_task(task)
    t_clone = time.monotonic() - t0

    free_after_clone = x.sr_free_bytes(sr)
    disk_cost = (free_start - free_after_clone) / 2**20  # MiB

    # Past this point the VM exists, so teardown has to happen on every path out.
    # A BOOT_TIMEOUT that skipped it would leave the probe running and quietly
    # invalidate the orphan check this script exists to perform.
    try:
        # VM.clone of a template yields a template, and XAPI refuses to start one, so every
        # golden image on the pool is unusable as a --source without this. XapiClient does
        # the same thing at the same point. Inside the try so a failure here still tears the
        # clone down, and before t1 so the boot figure stays start-to-Running.
        x.call("VM.set_is_a_template", vm, False)

        t1 = time.monotonic()
        # Async, because a synchronous VM.start blocks server-side until the VM is up:
        # a start crossing the client's 30s read timeout surfaces as TRANSPORT_ERROR
        # while the VM boots on regardless, so a slow-but-working host reads as a
        # failed cycle and the finally below tears the probe down mid-boot.
        start = x.call("Async.VM.start", vm, False, False)
        x.await_void_task(start, timeout=BOOT_TIMEOUT)
        wait_running(x, vm)
        t_boot = time.monotonic() - t1

        t2 = time.monotonic()
        ip = wait_ip(x, vm)
        t_ip = time.monotonic() - t2
    finally:
        t3 = time.monotonic()
        try:
            vdis = x.destroy_with_disks(vm)
        except XapiError as e:
            # Never mask whatever sent us here. reaper.py reclaims jenkins-ci-* probes.
            print(f"warning: teardown of {name!r} failed: {e}", file=sys.stderr)
            vdis = []
        t_teardown = time.monotonic() - t3

    total = t_clone + t_boot + t_ip
    print(f"  {mode:16} clone={t_clone:6.2f}s boot={t_boot:6.2f}s ip={t_ip:6.2f}s "
          f"| online={total:6.2f}s | disk={disk_cost:8.1f}MiB | teardown={t_teardown:5.2f}s "
          f"| vdis={len(vdis)} ip={ip or 'NONE'}")
    if ip is None:
        print("     WARNING: no IP reported. Guest tools missing or not started.", file=sys.stderr)
    return {"mode": mode, "clone": t_clone, "online": total, "disk_mib": disk_cost}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", default="alpine-test-1",
                   help="VM name_label, or a uuid (the unambiguous handle when names collide)")
    p.add_argument("--clones", type=int, default=3)
    p.add_argument("--copies", type=int, default=1)
    args = p.parse_args()

    with Xapi() as x:
        if UUID_ARG.fullmatch(args.source):
            try:
                matches = [x.call("VM.get_by_uuid", args.source)]
            except XapiError as e:
                # Only "no such uuid" means no matches. Swallowing everything here would
                # print "no VM named ..." over a dead network or a bad password, which
                # points the operator at the wrong problem entirely. UUID_INVALID is what
                # this pool actually raises (measured, XAPI 26.1); the docs' VM_NOT_FOUND
                # and HANDLE_INVALID both turned out to be wrong for this call.
                if e.message != "UUID_INVALID":
                    raise
                matches = []
        else:
            matches = x.call("VM.get_by_name_label", args.source)
        if not matches:
            print(f"no VM named {args.source!r}", file=sys.stderr)
            return 2
        if len(matches) > 1:
            # name_label is not unique, so matches[0] is whichever object XAPI lists first.
            # The objects are not interchangeable (one live case: a template and a non-template
            # sharing a name), so picking one silently runs a different experiment per listing
            # order. Refuse and name every candidate, the same line XapiClient draws.
            print(f"{len(matches)} VMs are named {args.source!r}; "
                  "rename them or address the right one by uuid:", file=sys.stderr)
            for ref in matches:
                rec = x.call("VM.get_record", ref)
                print(f"  uuid={rec['uuid']}  is_a_template={rec['is_a_template']}  "
                      f"power={rec['power_state']}", file=sys.stderr)
            return 2
        source = matches[0]
        if x.call("VM.get_power_state", source) != "Halted":
            print(f"{args.source!r} must be Halted to clone", file=sys.stderr)
            return 2

        sr = x.default_sr()
        sr_type = x.call("SR.get_type", sr)
        cow = sr_type in COW_SR_TYPES
        vdis_before = x.vdi_count(sr)
        free_before = x.sr_free_bytes(sr)

        print(f"source={args.source!r}  SR type={sr_type} "
              f"({'CoW fast-clone' if cow else 'FULL COPY - no CoW!'})")
        print(f"baseline: {free_before / 2**30:.2f} GiB free, {vdis_before} VDIs\n")

        results = []
        failed = []

        def cycle(index, full_copy):
            # one_cycle tears its own VM down on the way out, so a failure leaks nothing.
            # What a failure must not do is skip the SR accounting below: the orphan check
            # is the reason this script exists, and the cycles that already passed have
            # evidence worth reading. Record it and carry on.
            try:
                results.append(one_cycle(x, source, sr, index, full_copy=full_copy, cow=cow))
            except XapiError as e:
                failed.append(index)
                print(f"  cycle {index} failed: {e}", file=sys.stderr)

        for i in range(args.clones):
            cycle(i + 1, full_copy=False)
        for i in range(args.copies):
            cycle(100 + i, full_copy=True)

        attempted = args.clones + args.copies
        vdis_after = x.vdi_count(sr)
        free_after = x.sr_free_bytes(sr)
        print(f"\nafter {len(results)}/{attempted} cycles: "
              f"{free_after / 2**30:.2f} GiB free, {vdis_after} VDIs")
        print(f"VDI delta: {vdis_after - vdis_before:+d}   "
              f"space delta: {(free_after - free_before) / 2**30:+.3f} GiB")

        clones = [r for r in results if r["mode"].startswith("VM.clone")]
        copies = [r for r in results if r["mode"].startswith("VM.copy")]
        if clones:
            med = statistics.median(r["online"] for r in clones)
            cl = statistics.median(r["clone"] for r in clones)
            print(f"\n{'CoW ' if cow else 'full'} median clone={cl:.2f}s  "
                  f"median clone->online={med:.2f}s")
        if copies:
            print(f"full median clone={statistics.median(r['clone'] for r in copies):.2f}s  "
                  f"clone->online={statistics.median(r['online'] for r in copies):.2f}s")
        if clones:
            verdict = "PASS" if med <= 90 else "FAIL"
            print(f"\nKILL CRITERION (clone->online <= 90s): {verdict}  [{med:.2f}s]")
        elif args.clones:
            # Every clone cycle failed. Only reachable since cycles stopped aborting the
            # run. Printing nothing here would be the same silence the per-cycle accounting
            # above exists to kill: a missing verdict reads as a skipped check rather than
            # as a criterion that was not met. A clone that never boots fails the criterion.
            print("\nKILL CRITERION (clone->online <= 90s): FAIL  [no clone cycle completed]")

        rc = 0
        if failed:
            print(f"{len(failed)} of {attempted} cycles failed: "
                  f"{', '.join(str(i) for i in failed)}", file=sys.stderr)
            rc = 1
        if vdis_after != vdis_before:
            print("ORPHANED VDIs - teardown is leaking.", file=sys.stderr)
            rc = 1
        return rc


if __name__ == "__main__":
    try:
        sys.exit(main())
    except XapiError as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(2)

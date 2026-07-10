"""M1 kill check: how long from VM.clone to an agent that could accept work?

Measures the three phases the Jenkins plugin will live inside:
    clone   - Async.VM.clone (copy-on-write) or Async.VM.copy (full disk copy)
    boot    - VM.start until power_state == Running
    online  - until guest tools report an IP via VM_guest_metrics.networks

The last phase is the honest proxy for "agent could connect": it is the moment the
guest is up enough to talk to the outside world. Also verifies teardown reclaims
every VDI, so the storage-leak trap is measured, not assumed.

    XCPNG_HOST=... XCPNG_USER=root XCPNG_PASS=... python3 tools/measure_clone.py --source alpine-test-1
"""

import argparse
import statistics
import sys
import time

from xapi import COW_SR_TYPES, Xapi, XapiError

IP_TIMEOUT = 180


def wait_running(x, vm, timeout=120):
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


def one_cycle(x, source, sr, index, full_copy):
    name = f"jenkins-ci-probe-{index}"
    mode = "VM.copy (full)" if full_copy else "VM.clone (CoW)"
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

    t1 = time.monotonic()
    x.call("VM.start", vm, False, False)
    wait_running(x, vm)
    t_boot = time.monotonic() - t1

    t2 = time.monotonic()
    ip = wait_ip(x, vm)
    t_ip = time.monotonic() - t2

    t3 = time.monotonic()
    vdis = x.destroy_with_disks(vm)
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
    p.add_argument("--source", default="alpine-test-1")
    p.add_argument("--clones", type=int, default=3)
    p.add_argument("--copies", type=int, default=1)
    args = p.parse_args()

    with Xapi() as x:
        matches = x.call("VM.get_by_name_label", args.source)
        if not matches:
            print(f"no VM named {args.source!r}", file=sys.stderr)
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
        for i in range(args.clones):
            results.append(one_cycle(x, source, sr, i + 1, full_copy=False))
        for i in range(args.copies):
            results.append(one_cycle(x, source, sr, 100 + i, full_copy=True))

        vdis_after = x.vdi_count(sr)
        free_after = x.sr_free_bytes(sr)
        print(f"\nafter {len(results)} cycles: {free_after / 2**30:.2f} GiB free, {vdis_after} VDIs")
        print(f"VDI delta: {vdis_after - vdis_before:+d}   "
              f"space delta: {(free_after - free_before) / 2**30:+.3f} GiB")

        clones = [r for r in results if "CoW" in r["mode"]]
        copies = [r for r in results if "full" in r["mode"]]
        if clones:
            med = statistics.median(r["online"] for r in clones)
            cl = statistics.median(r["clone"] for r in clones)
            print(f"\nCoW  median clone={cl:.2f}s  median clone->online={med:.2f}s")
        if copies:
            print(f"full median clone={statistics.median(r['clone'] for r in copies):.2f}s  "
                  f"clone->online={statistics.median(r['online'] for r in copies):.2f}s")
        if clones:
            verdict = "PASS" if med <= 90 else "FAIL"
            print(f"\nKILL CRITERION (clone->online <= 90s): {verdict}  [{med:.2f}s]")
        if vdis_after != vdis_before:
            print("ORPHANED VDIs - teardown is leaking.", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Measure XO's REST and JSON-RPC backends against each other, for issue #89.

The direction of #89 was decided twice on readings of source, and the second reading was
wrong: REST was called incapable of creating a VM from a template when the route simply
lives under /pools rather than /vms. So this decides it on measurements instead.

Both paths run the plugin's actual flow against the same template on the same pool:
create, seed xenstore, stamp the owner marker, scrub the key, destroy. Each step is timed
and its semantics recorded, and anything created is removed.

    XO_BASE=https://192.168.1.5 XO_URL=wss://192.168.1.5/api/ \
      XO_TOKEN=$(cat ~/xoa-rest.token) XO_TRUST_SELF_SIGNED=1 \
      python3 tools/xo_compare.py --pool <pool-uuid> --run

Dry run by default. Exit 0 both paths completed, 1 a path failed, 2 controls did not
hold, 3 something was left on the pool.
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from xo import Xo, XoError  # noqa: E402
from xo_rest import XoRest, XoRestError  # noqa: E402
from xo_util import as_list, poll  # noqa: E402

TEMPLATE = "jenkins-agent-debian13-v7"
SEED_KEY = "vm-data/jenkins/probe"
SEED_VALUE = "seeded-by-xo-compare"
OWNER_TAG = "xcpng-cloud:xo-compare"

CREATED = []  # (backend, id) pairs; cleanup never touches anything else


def create_or_recover(xo, tid, name, backend):
    """Create a VM, and if our own deadline fires, go and find what may have been made.

    `vm.create` has no server-side timeout, so a TIMEOUT here means our patience ran out,
    not that the appliance declined. The VM may well exist. It carries no tag yet, and an
    XO-made VM has no other_config for the reaper to select on, so the unique name this
    run chose is the only handle anything has on it. Giving up without looking leaves a VM
    that no sweep in this repository can find.

    Returns (vm_id, elapsed). Raises with the id already tracked when it can be resolved,
    so cleanup() takes it either way.
    """
    t0 = time.monotonic()
    try:
        res = xo.create_from_template(tid, name, clone=True)
    except XoError as exc:
        if exc.message != "TIMEOUT":
            raise
        recovered = as_list(xo.resolve_vm(name))
        if len(recovered) == 1:
            vm_id = recovered[0].get("id")
            CREATED.append((backend, vm_id))
            raise XoError("CREATE_TIMEOUT_RECOVERED",
                          f"{name} was created despite the timeout; tracked as {vm_id}") from None
        raise XoError("CREATE_TIMEOUT_UNRESOLVED",
                      f"{name} timed out and resolves to {len(recovered)} VMs. "
                      f"This run is inconclusive; check the pool by name before trusting it") from None

    elapsed = time.monotonic() - t0
    vm_id = res.get("id") if isinstance(res, dict) else res
    # Validated before tracking: an unexpected shape put None on the list, and cleanup
    # then tried to delete it while the real VM, if any, went untracked.
    if not vm_id:
        raise XoError("CREATE_FAILED", f"vm.create returned nothing usable: {res!r}")
    CREATED.append((backend, vm_id))
    return vm_id, elapsed


def confirm_gone(read, label):
    """A delete is not done when the call returns, only when the object stops being there."""
    gone, waited = poll(read, lambda found: not found)
    if not gone:
        raise XoError("STILL_PRESENT", f"{label} is still on the pool {waited:.1f}s after delete")
    return waited


def row(label, value):
    print(f"  {label:34} {value}")


class Result:
    def __init__(self, backend):
        self.backend = backend
        self.steps = {}
        self.notes = []
        self.failed = None


def run_jsonrpc(xo, template, name):
    r = Result("JSON-RPC")
    print(f"\n== JSON-RPC path ==")
    tid = template.get("id") or template.get("uuid")

    vm_id, r.steps["create"] = create_or_recover(xo, tid, name, "jsonrpc")
    row("vm.create", f"{r.steps['create']:.3f}s -> {vm_id}")
    r.notes.append("accepts the XO object id; no bare-uuid requirement observed")

    vm = as_list(xo.get_objects({"id": vm_id}))[0]
    if vm.get("type") != "VM":
        raise XoError("WRONG_TYPE", vm.get("type"))
    row("result type", f"{vm['type']}, {len(vm.get('$VBDs') or [])} VBD")

    t0 = time.monotonic()
    xo.set_xenstore(vm_id, {SEED_KEY: SEED_VALUE})
    r.steps["seed"] = time.monotonic() - t0
    read = lambda: (as_list(xo.get_objects({"id": vm_id}))[0].get("xenStoreData") or {})
    seen, waited = poll(read, lambda d: SEED_KEY in d)
    r.steps["seed_visible"] = waited
    row("vm.set seed", f"{r.steps['seed']:.3f}s, visible after {waited:.1f}s poll={seen}")
    if not seen:
        raise XoError("SEED_NOT_VISIBLE", "cannot judge the scrub without seeing the key first")

    t0 = time.monotonic()
    xo.set_xenstore(vm_id, {SEED_KEY: None})
    r.steps["scrub"] = time.monotonic() - t0
    gone, waited = poll(read, lambda d: SEED_KEY not in d)
    row("vm.set scrub (null)", f"{r.steps['scrub']:.3f}s, gone after {waited:.1f}s poll={gone}")
    if not gone:
        raise XoError("SCRUB_FAILED", f"{SEED_KEY} survived the null write")

    t0 = time.monotonic()
    xo.add_tag(vm_id, OWNER_TAG)
    r.steps["tag"] = time.monotonic() - t0
    tagged, waited = poll(lambda: as_list(xo.get_objects({"id": vm_id}))[0].get("tags") or [],
                          lambda t: OWNER_TAG in t)
    row("tag.add", f"{r.steps['tag']:.3f}s, visible after {waited:.1f}s poll={tagged}")
    if not tagged:
        raise XoError("TAG_FAILED", f"{OWNER_TAG} never appeared on the VM")

    t0 = time.monotonic()
    xo.delete_vm(vm_id, delete_disks=True)
    r.steps["delete"] = time.monotonic() - t0
    row("vm.delete(deleteDisks=True)", f"{r.steps['delete']:.3f}s")
    r.notes.append("deleteDisks is a parameter, so keeping the disk is possible")
    confirm_gone(lambda: as_list(xo.get_objects({"id": vm_id})), vm_id)
    CREATED.remove(("jsonrpc", vm_id))
    return r


def run_rest(rest, xo_for_reads, pool_id, template, name):
    r = Result("REST")
    print(f"\n== REST path ==")
    bare = template.get("uuid") or (template.get("id") or "").split("/")[-1]

    vm_id, elapsed = rest.create_vm(pool_id, bare, name, clone=True, boot=False)
    r.steps["create"] = elapsed
    CREATED.append(("rest", vm_id))
    row("POST /pools/{id}/actions/create_vm", f"{elapsed:.3f}s -> {vm_id}")
    r.notes.append("requires a BARE template uuid; the pool-prefixed form 404s")

    vm = rest.get(f"/rest/v0/vms/{vm_id}?fields=type,$VBDs")
    if vm.get("type") != "VM":
        raise XoRestError("WRONG_TYPE", data=vm.get("type"))
    row("result type", f"{vm['type']}, {len(vm.get('$VBDs') or [])} VBD")

    t0 = time.monotonic()
    rest.set_xenstore(vm_id, {SEED_KEY: SEED_VALUE})
    r.steps["seed"] = time.monotonic() - t0
    read = lambda: rest.get(f"/rest/v0/vms/{vm_id}?fields=xenStoreData").get("xenStoreData") or {}
    seen, waited = poll(read, lambda d: SEED_KEY in d)
    r.steps["seed_visible"] = waited
    row("PATCH /vms/{id} seed", f"{r.steps['seed']:.3f}s, visible after {waited:.1f}s poll={seen}")
    if not seen:
        raise XoRestError("SEED_NOT_VISIBLE", data="cannot judge the scrub without a presence")

    t0 = time.monotonic()
    rest.set_xenstore(vm_id, {SEED_KEY: None})
    r.steps["scrub"] = time.monotonic() - t0
    gone, waited = poll(read, lambda d: SEED_KEY not in d)
    row("PATCH /vms/{id} scrub (null)", f"{r.steps['scrub']:.3f}s, gone after {waited:.1f}s poll={gone}")
    if not gone:
        raise XoRestError("SCRUB_FAILED", data=f"{SEED_KEY} survived the null write")

    t0 = time.monotonic()
    rest.add_tag(vm_id, OWNER_TAG)
    r.steps["tag"] = time.monotonic() - t0
    tagged, waited = poll(lambda: rest.get(f"/rest/v0/vms/{vm_id}?fields=tags").get("tags") or [],
                          lambda t: OWNER_TAG in t)
    row("PUT /vms/{id}/tags/{tag}", f"{r.steps['tag']:.3f}s, visible after {waited:.1f}s poll={tagged}")
    if not tagged:
        raise XoRestError("TAG_FAILED", data=f"{OWNER_TAG} never appeared on the VM")

    status, elapsed = rest.delete_vm(vm_id)
    r.steps["delete"] = elapsed
    row("DELETE /vms/{id}", f"{elapsed:.3f}s, http={status}")
    r.notes.append("no deleteDisks parameter; disks always go, no opt-out")
    # Read back through the JSON-RPC client on purpose: both backends talk to the same
    # appliance and the same object cache, and resolve/get_objects is verified here. The
    # REST collection-filter syntax is not, and guessing at it on a teardown-verification
    # path is how a leak gets certified clean.
    confirm_gone(lambda: as_list(xo_for_reads.get_objects({"id": vm_id})), vm_id)
    CREATED.remove(("rest", vm_id))
    return r


def cleanup(xo, rest):
    if not CREATED:
        return 0
    print(f"\n== cleanup: {len(CREATED)} left over ==")
    stuck = []
    for backend, vm_id in list(CREATED):
        try:
            if backend == "rest":
                rest.delete_vm(vm_id)
            else:
                xo.delete_vm(vm_id, delete_disks=True)
            print(f"  removed {vm_id} ({backend})")
            CREATED.remove((backend, vm_id))
        except Exception as exc:  # noqa: BLE001 - cleanup reports, never raises
            stuck.append(vm_id)
            print(f"  FAILED to remove {vm_id}: {exc}", file=sys.stderr)
    if stuck:
        print(f"\nLEFT ON THE POOL: {', '.join(stuck)}", file=sys.stderr)
        print("No marker-based sweep can find these; delete them by uuid.", file=sys.stderr)
    return len(stuck)


def summary(results):
    print("\n== side by side ==")
    steps = ["create", "seed", "seed_visible", "scrub", "tag", "delete"]
    width = max(len(s) for s in steps) + 2
    header = "  " + "step".ljust(width) + "".join(f"{r.backend:>12}" for r in results)
    print(header)
    for s in steps:
        line = "  " + s.ljust(width)
        for r in results:
            v = r.steps.get(s)
            line += f"{v:>11.3f}s" if v is not None else f"{'-':>12}"
        print(line)
    for r in results:
        print(f"\n  {r.backend}:")
        for n in r.notes:
            print(f"    - {n}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--run", action="store_true", help="actually create VMs")
    ap.add_argument("--pool", required=True, help="pool uuid that holds the template")
    ap.add_argument("--template", default=TEMPLATE)
    args = ap.parse_args()

    rest = XoRest()
    exit_code = 0

    with Xo() as xo:
        found = xo.resolve_template(args.template)
        if len(found) != 1:
            print(f"CONTROL FAILED: expected 1 template named {args.template}, "
                  f"found {len(found)}", file=sys.stderr)
            return 2
        template = found[0]
        row("template", f"{args.template} -> {template.get('uuid')}")

        before = {v.get("id") for v in as_list(xo.get_objects({"type": "VM"}))}
        row("baseline VMs", len(before))

        if not args.run:
            print("\nDry run. Re-run with --run to create one VM on each backend.")
            return 0

        results = []
        stamp = int(time.time())
        for label, fn in (("rest", lambda: run_rest(rest, xo, args.pool, template, f"xo-cmp-rest-{stamp}")),
                          ("jsonrpc", lambda: run_jsonrpc(xo, template, f"xo-cmp-jrpc-{stamp}"))):
            try:
                results.append(fn())
            except (XoError, XoRestError) as exc:
                print(f"\n{label} path FAILED: {exc}", file=sys.stderr)
                exit_code = 1

        if results:
            summary(results)

        stuck = cleanup(xo, rest)
        after = {v.get("id") for v in as_list(xo.get_objects({"type": "VM"}))}
        extra = after - before
        if extra or stuck:
            print(f"\nPOOL NOT BACK TO BASELINE: {extra}", file=sys.stderr)
            exit_code = max(exit_code, 3)
        else:
            print("\nPool is back to baseline.")

    return exit_code


if __name__ == "__main__":
    sys.exit(main())

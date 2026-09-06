"""Exercise the XO JSON-RPC verb set against a live appliance, for issue #89.

Every JSON-RPC answer on #89 was read from `vatesfr/xen-orchestra` at master and none of
it has been run. This is the run. It walks the verbs the Jenkins plugin actually needs,
in the order the plugin needs them, and cleans up after itself.

    XO_URL=wss://192.168.1.5/api/ XO_TOKEN=$(cat ~/xoa-rest.token) \
      XO_TRUST_SELF_SIGNED=1 python3 tools/xo_probe.py            # dry run, reads only
    ... python3 tools/xo_probe.py --run                            # actually creates a VM
    ... python3 tools/xo_probe.py --run --boot                     # also starts it and waits for an IP

Dry run is the default, the same posture as `reaper.py`. Nothing is created without --run.

The controls are not decoration. Three of this project's worst hours went into results
that could not have come out any other way: a check that fires on everything, a filter
that matches nothing and reads as "absent", a watcher satisfied by a run that predated it.
So before any finding is reported, this asserts that it can see a presence and that it can
fail. If a control does not hold, it exits 2 and reports nothing, because an absence
observed by an instrument that cannot detect presence is not evidence.

Exit codes: 0 every check passed, 1 a check failed, 2 the controls did not hold so
nothing was concluded, 3 cleanup left something behind on the pool.
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from xo import Xo, XoError  # noqa: E402
from xo_util import as_list, poll  # noqa: E402

TEMPLATE = "jenkins-agent-debian13-v7"
CLONE_PREFIX = "xo-probe-"
OWNER_TAG = "xcpng-cloud:xo-probe"
SEED_KEY = "vm-data/jenkins/probe"
SEED_VALUE = "seeded-by-xo-probe"

# Set only after --run has actually created something, so cleanup can never reach a VM
# this probe did not make. The reaper cannot help here: a clone made through XO carries
# no other_config owner marker, so it is invisible to a marker-based sweep.
CREATED = []


class Failed(Exception):
    pass


class ControlFailed(Exception):
    pass


def is_link_local(address):
    """IPv6 fe80::/10 and IPv4 169.254.0.0/16. Neither can reach the controller."""
    text = str(address).lower()
    return text.startswith("fe80:") or text.startswith("169.254.")


def ok(msg):
    print(f"  PASS  {msg}")


def info(msg):
    print(f"        {msg}")


def controls(xo, template_name):
    """Prove the instrument works before trusting anything it says."""
    print("\n== controls ==")

    methods = xo.list_methods()
    if not methods:
        raise ControlFailed("system.listMethods returned nothing; the probe sees no presences")
    ok(f"listMethods returned {len(methods)} methods, so the probe can see a presence")

    try:
        xo.call("system.thisMethodDoesNotExist")
    except XoError:
        ok("a bogus method raises, so the probe is not answering yes to everything")
    else:
        raise ControlFailed("a bogus method succeeded; every result below would be meaningless")

    found = xo.resolve_template(template_name)
    if len(found) != 1:
        raise ControlFailed(f"expected exactly 1 template named {template_name}, found {len(found)}")
    ok(f"template filter finds exactly one {template_name}")

    bogus = xo.resolve_template(template_name + "-does-not-exist")
    if bogus:
        raise ControlFailed("the template filter matches a name that does not exist")
    ok("the same filter finds nothing for a name that does not exist, so it discriminates")

    return found[0]


def baseline(xo):
    vms = as_list(xo.get_objects({"type": "VM"}))
    ids = {v.get("id") or v.get("uuid") for v in vms}
    info(f"baseline: {len(ids)} VMs")
    return ids


def check_q5(xo):
    print("\n== Q5, version negotiation ==")
    version = xo.server_version()
    ok(f"system.getServerVersion() -> {version}")
    info("the plugin can negotiate on this rather than pin a floor and 404")
    return version


def check_q2(xo, template, name):
    print("\n== Q2, create a VM from a template (the reason for issue #89) ==")
    tid = template.get("id") or template.get("uuid")
    # vm.create does not inherit the template's VIFs and create_vm over REST does, which
    # is measured rather than assumed; see Xo.template_vifs. Without this the clone boots
    # perfectly and can never send a packet, and --boot times out against a guest that
    # reports healthy on every other field.
    vifs = xo.template_vifs(template)
    started = time.monotonic()
    result = xo.create_from_template(tid, name, clone=True, VIFs=vifs)
    elapsed = time.monotonic() - started

    vm_id = result.get("id") if isinstance(result, dict) else result
    if not vm_id:
        raise Failed(f"vm.create returned nothing usable: {result!r}")
    CREATED.append(vm_id)
    ok(f"vm.create from a template succeeded in {elapsed:.3f}s -> {vm_id}")
    if elapsed > 10:
        info(f"WARNING: {elapsed:.1f}s is full-copy territory. CoW may not have engaged; "
             f"the XAPI figures on this SR are 0.56s clone against 49.59s copy")
    info("vm.create takes no xenStoreData and no tags, so the seed and the owner marker "
         "are separate calls after this one")

    # Polled, not read once. The cache lags a write, and a create is a write: reading
    # straight back can answer an empty list, and `made[0]` on that is an IndexError
    # rather than a failed check, which is a traceback with a VM already on the pool.
    seen, waited = poll(lambda: as_list(xo.get_objects({"id": vm_id})), lambda found: len(found) == 1)
    made = as_list(xo.get_objects({"id": vm_id}))
    if not seen:
        raise Failed(f"created {vm_id} but could not read it back within {waited:.1f}s")
    vm = made[0]
    if vm.get("type") != "VM":
        raise Failed(f"expected a VM, got type={vm.get('type')!r}. vm.clone would do this")
    ok("the result is a VM, not another template")
    if not vm.get("$VBDs"):
        raise Failed("the new VM has no VBD, so the disk did not come across")
    ok(f"it has {len(vm['$VBDs'])} VBD, so a disk came with it")

    # Checked here rather than left for --boot to discover as a timeout. A VIF-less clone
    # boots fine and reports healthy on every field except the address, so without this
    # the failure surfaces 180s later and looks like a broken golden image.
    got, waited = poll(lambda: as_list(xo.get_objects({"id": vm_id})),
                       lambda found: bool(found and (found[0].get("VIFs") or [])))
    if not got:
        raise Failed(f"the new VM has no VIF after {waited:.1f}s, so it can never reach "
                     f"the network. vm.create does not inherit the template's VIFs")
    ok("it has a VIF, so the guest has somewhere to send a packet")
    return vm_id, elapsed


def check_q1(xo, vm_id):
    """The seed and the #28 scrub. The delete half is only evidence after the set half.

    Both directions poll. XO's object cache lags a write, so a read taken straight after
    the set finds nothing and reports a successful seed as a failed one. This function
    read once until 2026-09-06, while xo_compare.py polled, which is the whole reason
    `poll` now lives in xo_util rather than in whichever file last needed it.
    """
    print("\n== Q1, xenstore seed and the #28 per-key scrub ==")

    read = lambda: read_xenstore(xo, vm_id)  # noqa: E731

    xo.set_xenstore(vm_id, {SEED_KEY: SEED_VALUE})
    seen, waited = poll(read, lambda d: d.get(SEED_KEY) == SEED_VALUE)
    if not seen:
        raise Failed(f"seeded {SEED_KEY} but it never appeared; read back "
                     f"{read().get(SEED_KEY)!r} after {waited:.1f}s")
    ok(f"set {SEED_KEY} and read it back after {waited:.1f}s, so this reader sees the key present")

    xo.set_xenstore(vm_id, {SEED_KEY: None})
    gone, waited = poll(read, lambda d: SEED_KEY not in d)
    if not gone:
        raise Failed(f"{SEED_KEY} survived a null write, so the per-key delete did not happen")
    ok(f"a null value removed exactly that key after {waited:.1f}s, which is the #28 scrub")


def read_xenstore(xo, vm_id):
    found = as_list(xo.get_objects({"id": vm_id}))
    if not found:
        raise Failed(f"could not read {vm_id} back")
    return found[0].get("xenStoreData") or {}


def check_owner_tag(xo, vm_id):
    print("\n== owner marker, which has to be a tag here ==")
    # Both directions poll, for the reason check_q1 does. Measured on the pool
    # 2026-09-06: tag.add returned and the tag was NOT on the object when read back
    # immediately, so this reported a working tag.add as broken. xo_compare polled here
    # and this file did not, which is the same rule-with-two-homes split that check_q1
    # had, in a function the review never named. Fixing the instance that fires and not
    # sweeping for its siblings is how it survived the first pass.
    tags = lambda: as_list(xo.get_objects({"id": vm_id}))[0].get("tags") or []  # noqa: E731

    xo.add_tag(vm_id, OWNER_TAG)
    tagged, waited = poll(tags, lambda t: OWNER_TAG in t)
    if not tagged:
        raise Failed(f"added tag {OWNER_TAG} but it never appeared on the object")
    ok(f"tag.add put {OWNER_TAG} on the VM after {waited:.1f}s, so a sweep can select on it")

    xo.remove_tag(vm_id, OWNER_TAG)
    gone, waited = poll(tags, lambda t: OWNER_TAG not in t)
    if not gone:
        raise Failed(f"tag.remove did not remove the tag within {waited:.1f}s")
    ok(f"tag.remove takes it off again after {waited:.1f}s")


def check_boot(xo, vm_id, wait):
    print("\n== boot, and whether an address is reported ==")
    started = time.monotonic()
    xo.call("vm.start", {"id": vm_id})
    ok(f"vm.start returned in {time.monotonic() - started:.3f}s")

    # A link-local address is not an answer. MEASURED 2026-09-06, n=2 and one each way:
    # one clone reported mainIpAddress='fe80::cd1c:...' at 25.3s, and another held None
    # until 85.1s and then produced IPv4, global IPv6 and link-local together. So
    # mainIpAddress can transiently carry an address nothing can connect to, and a caller
    # taking the first non-empty value gets it. Accepting it here would be this probe
    # reporting success for a check that failed, which is the whole thing these tools
    # exist to stop doing.
    deadline = time.monotonic() + wait
    address = None
    while time.monotonic() < deadline:
        vm = as_list(xo.get_objects({"id": vm_id}))[0]
        candidate = vm.get("mainIpAddress")
        if candidate and not is_link_local(candidate):
            address = candidate
            break
        if candidate:
            info(f"ignoring link-local {candidate}, nothing can connect to it")
        time.sleep(3)

    if address:
        ok(f"mainIpAddress reported after {time.monotonic() - started:.1f}s: {address}")
        info("computed for us, so the guest_metrics round trip and the stale-husk check go away")

    # Stop the VM before deciding, so the failure path still tears down what it started.
    xo.call("vm.stop", {"id": vm_id, "force": True})
    ok("vm.stop returned")

    if not address:
        info("this is the golden image's problem, not the API's, but it blocks the plugin")
        # Printing FAIL and returning left main() at exit 0, so a --boot run that never
        # got an address reported success to whatever read the exit code.
        raise Failed(f"no mainIpAddress within {wait}s")
    return address


def check_q3(xo, vm_id):
    print("\n== Q3, destroy with disks ==")
    started = time.monotonic()
    xo.delete_vm(vm_id, delete_disks=True)
    ok(f"vm.delete(deleteDisks=True) returned in {time.monotonic() - started:.3f}s")
    info("deleteDisks is a parameter here; over REST it is forced on with no opt-out")

    # Confirm the absence BEFORE untracking. The other order struck the id off the only
    # list that knows about it and then raised, so cleanup() could not retry and a VM that
    # survived its own delete was left on the pool with nothing able to find it.
    gone, waited = poll(lambda: as_list(xo.get_objects({"id": vm_id})), lambda found: not found)
    if not gone:
        raise Failed(f"{vm_id} is still present {waited:.1f}s after delete")
    if vm_id in CREATED:
        CREATED.remove(vm_id)
    ok(f"the VM is gone after {waited:.1f}s")


def cleanup(xo):
    """Only ever touches ids this run created."""
    if not CREATED:
        return 0
    print(f"\n== cleanup: {len(CREATED)} left over ==")
    stuck = []
    for vm_id in list(CREATED):
        try:
            xo.delete_vm(vm_id, delete_disks=True)
            print(f"  removed {vm_id}")
            CREATED.remove(vm_id)
        except XoError as exc:
            stuck.append(vm_id)
            print(f"  FAILED to remove {vm_id}: {exc}", file=sys.stderr)
    if stuck:
        print(f"\nLEFT ON THE POOL: {', '.join(stuck)}", file=sys.stderr)
        print("The reaper cannot see these: an XO-made VM carries no other_config marker.",
              file=sys.stderr)
    return len(stuck)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--run", action="store_true",
                    help="actually create a VM. Without it, only reads and controls run")
    ap.add_argument("--boot", action="store_true",
                    help="also start the VM and wait for an address. Implies a slower run")
    ap.add_argument("--wait", type=int, default=180,
                    help="seconds to wait for mainIpAddress (default 180)")
    ap.add_argument("--template", default=TEMPLATE, help=f"template name (default {TEMPLATE})")
    args = ap.parse_args()

    name = f"{CLONE_PREFIX}{int(time.time())}"
    exit_code = 0

    with Xo() as xo:
        info(f"connected as {(xo.user or {}).get('email', '?')}")
        try:
            template = controls(xo, args.template)
        except ControlFailed as exc:
            print(f"\nCONTROL FAILED: {exc}", file=sys.stderr)
            print("Nothing is concluded. An absence seen by an instrument that cannot "
                  "detect presence is not evidence.", file=sys.stderr)
            return 2

        check_q5(xo)
        before = baseline(xo)

        if not args.run:
            print("\n== dry run ==")
            print("  Controls hold and the template resolves. Re-run with --run to create a VM.")
            return 0

        try:
            vm_id, _ = check_q2(xo, template, name)
            check_q1(xo, vm_id)
            check_owner_tag(xo, vm_id)
            if args.boot:
                check_boot(xo, vm_id, args.wait)
            check_q3(xo, vm_id)
        except (Failed, XoError) as exc:
            print(f"\nFAILED: {exc}", file=sys.stderr)
            exit_code = 1

        stuck = cleanup(xo)

        after = baseline(xo)
        extra = after - before
        if extra:
            print(f"\nPOOL NOT BACK TO BASELINE, {len(extra)} new VM(s): {extra}", file=sys.stderr)
            exit_code = max(exit_code, 3)
        elif stuck:
            exit_code = max(exit_code, 3)
        else:
            print("\nPool is back to baseline.")

    return exit_code


if __name__ == "__main__":
    sys.exit(main())

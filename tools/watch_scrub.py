"""Watch the JNLP secret leave a clone's XAPI record, so #28's scrub has an observed run.

The live-test job used to check the scrub from inside the guest, with
`xenstore-read vm-data/jenkins/secret`. That check was wrong twice over (#205). It could not
run — the agent user gets `open /proc/xen/xenbus: permission denied`, and its `||` fallback
printed the success message anyway — and even as root it would have been looking at the wrong
surface. `XapiClient.clearGuestSecret` says so in its own comment: setting xenstore-data on a
running VM does not propagate to the guest, so the scrub only ever clears the **VM record**.
That record is the exposure #26 is about, because any XAPI read-only role can read it.

So this reads the record, from outside, while a clone is alive:

     12.3s  xcpng-...-588938c9  secret=PRESENT control=PRESENT power=Running
     54.9s  xcpng-...-588938c9  secret=ABSENT  control=PRESENT power=Running
     71.2s  xcpng-...-588938c9  GONE (destroyed)

    VERDICT xcpng-...-588938c9  CONFIRMED  present at 12.3s, absent at 54.9s

**An absence only counts once this same reader has seen a presence on the same key.** That is
the positive control #205 asks for, and it is why a verdict of CONFIRMED needs two observations
rather than one: start watching after the scrub has already run and the honest answer is
INCONCLUSIVE, not a pass. The control key `vm-data/jenkins/name` is read alongside, so a run
that can see nothing at all is separated from one that sees a genuinely empty seed.

Credentials come from the environment, never from a file and never into git:

    XCPNG_HOST=192.168.1.87 XCPNG_USER=root XCPNG_PASS=... XCPNG_TRUST_SELF_SIGNED=1 \
      python3 tools/watch_scrub.py --duration 300

Start it, then queue the build. Read-only: it calls `VM.get_all_records` and nothing else, and
selects on the plugin's owner marker rather than a name prefix, so a VM this plugin did not
create can never appear in the output. It prints whether the secret key is set, never its value.

Exit status is the verdict, so it can gate a lab run:

    0  at least one clone went PRESENT then ABSENT, and none finished still carrying it
    1  a clone finished with the secret still in its record -- the scrub did not happen
    2  nothing conclusive: no owner-marked clone appeared, or none was seen holding the secret
"""

import argparse
import os
import sys
import time

from xapi import Xapi

# Must match XapiClient.OWNER_KEY, and the copies in reaper.py and watch_warm.py. If they drift,
# this watches nothing and reports it as "no clone appeared", which is exit 2 rather than a pass.
OWNER_KEY = "xcpng-cloud"

# Must match XapiClient.GUEST_DATA_PREFIX. SECRET_KEY is what the scrub removes; CONTROL_KEY is
# seeded beside it and is never removed, so it answers "could this reader have seen a key at all".
SECRET_KEY = "vm-data/jenkins/secret"
CONTROL_KEY = "vm-data/jenkins/name"

CONFIRMED = "CONFIRMED"
NOT_SCRUBBED = "NOT SCRUBBED"
INCONCLUSIVE = "INCONCLUSIVE"


def clone_states(records, cloud=None):
    """Owner-marked clones from a VM.get_all_records payload, as {name: state}.

    Only presence is reported. The secret's value is never read out of the record, because a
    tool that prints it to prove it was removed has published it for the life of the log.

    Templates, snapshots and dom0 are all excluded, for the reason reaper.py spells out: a
    filter that misses any of them reports an operator's restore point as a live agent.
    """
    states = {}
    for record in records.values():
        if record["is_a_template"] or record["is_a_snapshot"] or record["is_control_domain"]:
            continue
        marker = (record.get("other_config") or {}).get(OWNER_KEY)
        if marker is None or (cloud is not None and marker != cloud):
            continue
        data = record.get("xenstore_data") or {}
        states[record["name_label"]] = {
            "secret": SECRET_KEY in data,
            "control": CONTROL_KEY in data,
            "power": record["power_state"],
        }
    return states


def format_state(state):
    """One clone's state, padded so the columns line up between lines of a run."""
    return (
        f"secret={'PRESENT' if state['secret'] else 'ABSENT':<7} "
        f"control={'PRESENT' if state['control'] else 'ABSENT':<7} "
        f"power={state['power']}"
    )


class Tracker:
    """Per-clone history, and the verdict that follows from it.

    Keeps only what a verdict needs: when the secret was first seen present, when it was first
    seen absent *after* that, and whether the control key was ever readable. Nothing here infers
    a transition it did not observe -- a clone that appears already scrubbed is INCONCLUSIVE,
    not a pass, because this reader never proved it could see that key on that VM.
    """

    def __init__(self):
        self.seen = {}

    def observe(self, name, state, at):
        history = self.seen.setdefault(
            name, {"present_at": None, "absent_at": None, "control": False, "last": None}
        )
        if state["control"]:
            history["control"] = True
        if state["secret"]:
            if history["present_at"] is None:
                history["present_at"] = at
            # A key that comes back after being cleared is a real finding, not a blip: drop the
            # earlier absence so the verdict reads NOT SCRUBBED off the last observation.
            history["absent_at"] = None
        elif history["present_at"] is not None and history["absent_at"] is None:
            history["absent_at"] = at
        history["last"] = state

    def verdict(self, name):
        history = self.seen[name]
        if history["present_at"] is not None and history["absent_at"] is not None:
            return CONFIRMED, (
                f"present at {history['present_at']:.1f}s, absent at {history['absent_at']:.1f}s"
            )
        if history["last"] is not None and history["last"]["secret"]:
            return NOT_SCRUBBED, (
                f"still in the record at {history['present_at']:.1f}s and never cleared"
            )
        if not history["control"]:
            return INCONCLUSIVE, "no seed key was ever readable on this clone"
        return INCONCLUSIVE, "the secret was never seen present, so its absence proves nothing"

    def verdicts(self):
        return [(name, *self.verdict(name)) for name in sorted(self.seen)]


def exit_status(verdicts):
    """0 confirmed, 1 a clone kept its secret, 2 nothing conclusive. Worst answer wins."""
    kinds = {kind for _, kind, _ in verdicts}
    if NOT_SCRUBBED in kinds:
        return 1
    if CONFIRMED in kinds:
        return 0
    return 2


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--duration", type=float, default=300.0, help="seconds to watch")
    parser.add_argument("--interval", type=float, default=1.0, help="seconds between polls")
    parser.add_argument(
        "--cloud",
        default=os.environ.get("XCPNG_CLOUD"),
        help="only clones marked as owned by this cloud name (default: any marked clone)",
    )
    parser.add_argument(
        "--until-verdict",
        action="store_true",
        help="stop as soon as every clone seen so far has a verdict of CONFIRMED",
    )
    args = parser.parse_args(argv)

    started = time.monotonic()
    tracker = Tracker()
    previous = {}

    def emit(message):
        print(f"{time.monotonic() - started:7.1f}s  {message}", flush=True)

    with Xapi() as xapi:
        scope = f"cloud={args.cloud}" if args.cloud else "any marked clone"
        emit(f"START pool={xapi.host} {scope} interval={args.interval:.1f}s")
        deadline = started + args.duration
        while time.monotonic() < deadline:
            at = time.monotonic() - started
            try:
                current = clone_states(xapi.call("VM.get_all_records"), args.cloud)
            except Exception as exc:
                # A poll that failed is not a poll that saw nothing, and the difference decides
                # whether a later absence means anything. Say which it was and keep the history.
                emit(f"POLL FAILED ({type(exc).__name__}) -- no observation for this tick")
                time.sleep(args.interval)
                continue

            for name in sorted(current):
                tracker.observe(name, current[name], at)
                if previous.get(name) != current[name]:
                    emit(f"{name}  {format_state(current[name])}")
            for name in sorted(set(previous) - set(current)):
                emit(f"{name}  GONE (destroyed)")
            previous = current

            if args.until_verdict and tracker.seen and not current:
                if all(kind == CONFIRMED for _, kind, _ in tracker.verdicts()):
                    break

            time.sleep(args.interval)

    verdicts = tracker.verdicts()
    if not verdicts:
        emit("VERDICT  INCONCLUSIVE  no owner-marked clone appeared while watching")
        return 2
    for name, kind, why in verdicts:
        emit(f"VERDICT {name}  {kind}  {why}")
    return exit_status(verdicts)


if __name__ == "__main__":
    sys.exit(main())

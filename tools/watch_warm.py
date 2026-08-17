"""Watch a warm pool: print one line whenever Jenkins or the pool changes.

Built to measure what a controller restart costs a warm pool against a real pool, which is a
question no unit test can answer. It polls Jenkins and XAPI independently and prints a
timestamped line on every change, so a run is a timeline rather than a snapshot:

      0.9s  JENKINS xcpng-...-a22f972e online=true accepting=true warm=true reloaded=false
     15.7s  JENKINS UNREACHABLE (HTTPError)
     54.1s  JENKINS REACHABLE
     56.6s  XAPI    xcpng-...-a22f972e GONE (destroyed)

Jenkins going away and coming back is itself logged, so measuring a restart needs no clock
coordination between this box and the controller: t0 is wherever REACHABLE next appears.

Why the script console instead of /computer/api/json: that endpoint does not export
`acceptingTasks` on the 2.555.x baseline this plugin targets, and `warm`/`reloaded` are
transient runtime state on XcpngAgent that is deliberately never serialized, so REST cannot
see them at all. The first version of this tool read `acceptingTasks` off the REST record,
got None every sample, coerced it to False, and produced a 230-second log of a perfectly
healthy spare apparently refusing work. isAcceptingTasks(), isWarm() and isReloaded() are
read off the live objects instead. A missing field is not a false field.

Credentials come from the environment, never from a file and never into git:

    XCPNG_HOST=192.168.1.87 XCPNG_USER=root XCPNG_PASS=... XCPNG_TRUST_SELF_SIGNED=1 \
    JENKINS_URL=http://192.168.1.102:8080 JENKINS_USER=admin JENKINS_PASS=... \
      python3 tools/watch_warm.py --duration 360

Read-only: it runs one Groovy read per poll and never provisions, destroys or reconfigures
anything. The pool side selects on the plugin's owner marker rather than a name prefix, so a
VM this plugin did not create can never appear in the output.
"""

import argparse
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from xapi import Xapi

# Must match XapiClient.OWNER_KEY, and reaper.py's copy. If they drift, this watches nothing.
OWNER_KEY = "xcpng-cloud"

# Read the agent flags off the live objects. The class is matched by name rather than with
# `instanceof` so the probe degrades to "no agents" on a controller without the plugin
# installed, instead of failing with a ClassNotFoundException that looks like a poll error.
AGENT_PROBE = """
jenkins.model.Jenkins.instance.computers.each { c ->
  def n = c.node
  if (n == null || !n.getClass().getName().startsWith('io.jenkins.plugins.xcpng.')) return
  println "AGENT ${c.name}|online=${c.isOnline()}|accepting=${c.isAcceptingTasks()}" +
    "|idle=${c.isIdle()}|warm=${n.isWarm()}|reloaded=${n.isReloaded()}"
}
"""

FIELD_ORDER = ("online", "accepting", "idle", "warm", "reloaded")


def _from_env(name, hint):
    """Credentials live in the environment, so a missing one is a user error, not a bug."""
    try:
        return os.environ[name]
    except KeyError:
        raise SystemExit(f"{name} is not set. {hint}") from None


def parse_agents(text):
    """Parse the probe's output into {agent name: {field: value}}.

    Only fields the probe actually reported are present. Absent stays absent: inventing a
    False for a field the payload never carried is the bug this tool was rewritten to fix.
    """
    agents = {}
    for line in text.splitlines():
        if not line.startswith("AGENT "):
            continue
        name, _, rest = line[len("AGENT "):].partition("|")
        if not name:
            continue
        fields = {}
        for pair in rest.split("|"):
            key, sep, value = pair.partition("=")
            if sep and key:
                fields[key] = value
        agents[name] = fields
    return agents


def format_state(fields):
    """Render a field map for the log, known fields first so columns line up between lines."""
    known = [f"{k}={fields[k]}" for k in FIELD_ORDER if k in fields]
    extra = [f"{k}={v}" for k, v in sorted(fields.items()) if k not in FIELD_ORDER]
    return " ".join(known + extra)


def vm_states(records):
    """Plugin-owned VMs from a VM.get_all_records payload, as {name: power_state}.

    Selection is by the owner marker, never a name prefix, for the reason reaper.py spells
    out: a marker cannot drift out of sync with a naming convention. Templates, snapshots and
    dom0 are excluded, all three, because a filter that misses any of them reports an
    operator's restore point as a live agent.
    """
    states = {}
    for record in records.values():
        if record["is_a_template"] or record["is_a_snapshot"] or record["is_control_domain"]:
            continue
        if (record.get("other_config") or {}).get(OWNER_KEY):
            states[record["name_label"]] = record["power_state"]
    return states


def changes(previous, current, gone_suffix):
    """Messages describing how `current` differs from `previous`, in a stable order.

    `previous` of None means "first observation", which reports everything currently present
    rather than nothing: after a restart the whole node list is news again.
    """
    old = {} if previous is None else previous
    messages = []
    for name in sorted(current):
        if old.get(name) != current[name]:
            value = current[name]
            messages.append(f"{name} {format_state(value) if isinstance(value, dict) else value}")
    for name in sorted(set(old) - set(current)):
        messages.append(f"{name} {gone_suffix}")
    return messages


class JenkinsProbe:
    """Runs the agent probe through /scriptText, re-issuing the CSRF crumb when it goes stale."""

    def __init__(self, url, user, password):
        self.url = url.rstrip("/")
        self._user = user
        self._password = password
        self._crumb = None
        self._opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )

    def _request(self, path, data=None):
        import base64

        request = urllib.request.Request(self.url + path, data=data)
        token = base64.b64encode(f"{self._user}:{self._password}".encode()).decode()
        request.add_header("Authorization", "Basic " + token)
        return request

    def _refresh_crumb(self):
        with self._opener.open(self._request("/crumbIssuer/api/json"), timeout=6) as response:
            self._crumb = json.load(response)["crumb"]

    def agents(self):
        """{agent name: {field: value}}. Raises if the controller cannot be read."""
        # A restart invalidates both the crumb and the session, and the retry has to survive
        # that: without it every measured restart reports a spurious poll failure afterwards.
        for attempt in (1, 2):
            try:
                if self._crumb is None:
                    self._refresh_crumb()
                body = urllib.parse.urlencode({"script": AGENT_PROBE}).encode()
                request = self._request("/scriptText", data=body)
                request.add_header("Jenkins-Crumb", self._crumb)
                with self._opener.open(request, timeout=8) as response:
                    return parse_agents(response.read().decode("utf-8", "replace"))
            except urllib.error.HTTPError as exc:
                if exc.code in (401, 403) and attempt == 1:
                    self._crumb = None
                    continue
                raise
        raise AssertionError("unreachable")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--duration", type=float, default=300.0, help="seconds to watch")
    parser.add_argument("--interval", type=float, default=2.0, help="seconds between polls")
    parser.add_argument(
        "--jenkins-url",
        default=os.environ.get("JENKINS_URL"),
        help="controller root URL; defaults to $JENKINS_URL",
    )
    args = parser.parse_args(argv)

    if not args.jenkins_url:
        raise SystemExit("--jenkins-url or JENKINS_URL is required.")
    probe = JenkinsProbe(
        args.jenkins_url,
        _from_env("JENKINS_USER", "Export JENKINS_USER and JENKINS_PASS."),
        _from_env("JENKINS_PASS", "Export JENKINS_USER and JENKINS_PASS."),
    )

    started = time.monotonic()

    def emit(source, message):
        print(f"{time.monotonic() - started:7.1f}s  {source:<7} {message}", flush=True)

    previous_agents = None
    previous_vms = None
    reachable = None

    with Xapi() as xapi:
        emit("START", f"jenkins={probe.url} pool={xapi.host} interval={args.interval:.1f}s")
        deadline = started + args.duration
        while time.monotonic() < deadline:
            try:
                agents = probe.agents()
                if reachable is not True:
                    emit("JENKINS", "REACHABLE")
                    previous_agents = None  # the whole node list is news after a restart
                reachable = True
            except Exception as exc:
                if reachable is not False:
                    emit("JENKINS", f"UNREACHABLE ({type(exc).__name__})")
                reachable, agents = False, None

            if agents is not None:
                for message in changes(previous_agents, agents, "REMOVED from node list"):
                    emit("JENKINS", message)
                previous_agents = agents

            try:
                vms = vm_states(xapi.call("VM.get_all_records"))
            except Exception as exc:
                emit("XAPI", f"poll failed: {type(exc).__name__}")
                vms = None

            if vms is not None:
                for message in changes(previous_vms, vms, "GONE (destroyed)"):
                    emit("XAPI", message)
                previous_vms = vms

            time.sleep(args.interval)

    emit("END", "duration reached")
    return 0


if __name__ == "__main__":
    sys.exit(main())

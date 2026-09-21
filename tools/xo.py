"""Minimal Xen Orchestra JSON-RPC client for the spike's operational tooling.

The companion to `xapi.py`, aimed at the other backend. Deliberately tiny for the same
reason: it exists to prove how few methods the Jenkins plugin actually needs, and to let
the JSON-RPC answers on issue #89 be exercised against a live appliance rather than only
read from source.

    XO_URL=wss://192.168.1.5/api/ XO_TOKEN=... XO_TRUST_SELF_SIGNED=1 python3 tools/xo_probe.py

A ws:// URL is refused: /api/ is served on both schemes, so the missing letter connects,
signs in, works, and puts the token on the wire in the clear every run. XO_ALLOW_CLEARTEXT=1
overrides it and warns.

Three things about this transport that the REST one does not have, and that the plugin
will have to handle:

  * It is a WebSocket. There is no HTTP form. `/api/` is served only on the `upgrade`
    event (xo-server/src/index.mjs), so the connection is persistent and stateful, and
    a dropped socket loses the session.
  * The server pushes notifications down the same socket, unprompted. A reply is matched
    by JSON-RPC id, never by "the next frame that arrives". Reading the next frame is the
    bug this class exists to not have.
  * There is no server-side timeout on a long call, and no task-polling API to fall back
    on (api/task.mjs is `cancel` and `destroy`, nothing else). The deadline is ours.

Credentials come from the environment; nothing is ever written to disk.
"""

import json
import os
import ssl
import sys
import time

from xo_util import env_flag, transport_refusal

try:
    import websocket  # websocket-client
except ImportError:  # pragma: no cover - dependency check, mirrors the other tools
    print(
        "error: websocket-client is required but not installed.\n"
        "Install with: python3 -m pip install websocket-client",
        file=sys.stderr,
    )
    raise

DEFAULT_TIMEOUT = 120.0

# The one handshake status that means a WebSocket exists. Named rather than inlined because
# 101 appears beside a list of redirect codes below and the two must not blur together.
SWITCHING_PROTOCOLS = 101


class XoError(RuntimeError):
    def __init__(self, message, data=None):
        super().__init__(f"{message} {data if data is not None else ''}".strip())
        self.message = message
        self.data = data


def _from_env(name):
    """Credentials live in the environment, so a missing one is a user error, not a bug."""
    try:
        return os.environ[name]
    except KeyError:
        raise XoError(
            "MISSING_ENV",
            f"{name} is not set. Export XO_URL and XO_TOKEN.",
        ) from None


class Xo:
    def __init__(self, url=None, token=None, trust_self_signed=None, timeout=DEFAULT_TIMEOUT):
        self.url = url or _from_env("XO_URL")

        # ws:// is the trap this catches, and it is easier to reach than http:// is on the
        # REST side: the appliance serves both schemes on /api/, so a URL with the one
        # letter missing connects, signs in, and works -- while putting the token on the
        # wire in the clear on every run. Nothing downstream would ever complain.
        refusal = transport_refusal(self.url, "wss")
        if refusal:
            raise XoError("INSECURE_TRANSPORT", refusal)

        self._token = token or _from_env("XO_TOKEN")
        self.timeout = timeout
        self._ws = None
        self._id = 0
        self.user = None

        # Same posture as the XAPI client and as the plugin's trustSelfSigned checkbox:
        # opt-in, off by default, and it says so out loud when it is on.
        if trust_self_signed is None:
            trust_self_signed = env_flag("XO_TRUST_SELF_SIGNED")
        self._trust_self_signed = trust_self_signed
        if trust_self_signed:
            print(
                f"warning: TLS verification disabled for {self.url} "
                f"(XO_TRUST_SELF_SIGNED). Traffic is exposed to interception.",
                file=sys.stderr,
            )

    # -- connection ---------------------------------------------------------------

    def connect(self):
        sslopt = None
        if self._trust_self_signed:
            sslopt = {"cert_reqs": ssl.CERT_NONE, "check_hostname": False}
        # redirect_limit=0, and then check the handshake ourselves. Both halves are needed.
        #
        # websocket-client follows up to THREE redirects by default (_core.py, the loop over
        # `options.pop("redirect_limit", 3)`), reconnecting to whatever `location` names, on
        # 301/302/303/307/308 and with no check on the target's scheme or host. The token is
        # sent after create_connection returns, so a vetted wss:// URL that redirects can put
        # it on a socket to another host, or to plain ws://. The scheme check above vets the
        # address we dial; it cannot vet an address the library dials for us. Same class of
        # bug as the Cookie-on-redirect one in xo_rest.py, on the transport that carries the
        # same token.
        #
        # The status check is not belt and braces. handshake() treats the five redirect codes
        # as SUCCESS_STATUSES and returns rather than raising (_handshake.py), so with the
        # limit at zero the loop simply does not run and `connected = True` is set on a
        # connection that never upgraded. Read in websocket-client 1.9.0, the version CI
        # installs; a later release added a "Redirect limit exhausted" raise, so this check is
        # also what keeps the behaviour the same across versions the lock file does not pin.
        self._ws = websocket.create_connection(
            self.url, sslopt=sslopt, timeout=self.timeout, redirect_limit=0
        )
        handshake = getattr(self._ws, "handshake_response", None)
        status = getattr(handshake, "status", None)
        if status != SWITCHING_PROTOCOLS:
            self._close_quietly()
            raise XoError(
                "NOT_UPGRADED",
                f"{self.url} answered {status} rather than 101 and no token was sent. A "
                f"redirect here is refused on purpose: following it would hand the token "
                f"to whatever the appliance pointed at.",
            )
        try:
            self.user = self.call("session.signInWithToken", {"token": self._token})
        except BaseException:
            # The socket is open and unauthenticated at this point. __enter__ propagates
            # this, so __exit__ never runs and nothing else will ever close it: the
            # appliance holds the connection until it times out, and a harness that
            # retries in a loop stacks one per attempt.
            self._close_quietly()
            raise
        return self.user

    def _close_quietly(self):
        """Close the socket, swallowing a close that itself fails.

        Both failure paths in connect() need this, and for the same reason: the socket has
        to go, and the error already in hand explains why the caller is here, so a dying
        socket reporting that it is dying must not replace it. The handle goes either way,
        because close() clears it in a finally.

        It is a method rather than four inlined lines because the first version of connect()
        inlined it on the sign-in path and left the handshake path bare, so a close that
        raised there replaced XoError("NOT_UPGRADED") with the OSError and escaped through
        every caller that handles only XoError. Caught by review on #234. Two paths with one
        rule between them is the shape that stops it recurring.
        """
        try:
            self.close()
        except Exception:
            pass

    def close(self):
        if self._ws is not None:
            try:
                self._ws.close()
            finally:
                self._ws = None

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *_):
        self.close()

    # -- the one method that matters ----------------------------------------------

    def call(self, method, params=None, timeout=None):
        """Send a request and return its result, matching the reply by id.

        The server interleaves its own notifications with replies, so anything whose id
        does not match is skipped rather than returned. A naive `recv()` here would
        return an event and read as a successful call with a nonsense result.
        """
        if self._ws is None:
            raise XoError("NOT_CONNECTED", "call connect() first")

        self._id += 1
        rid = self._id

        # One budget, named once. Reporting self.timeout while waiting on a per-call value
        # names a number nobody chose, and a probe that says "did not answer within 120s"
        # after waiting 5 sends the reader looking for a slow appliance.
        #
        # It is established before the send, not after, and the socket is set to it. The
        # send can block: settimeout governs both directions, so a request written to a
        # stalled socket otherwise waits out the connection timeout rather than the budget
        # this call was given, and a short per-call timeout silently means the long one.
        budget = timeout if timeout is not None else self.timeout
        deadline = time.monotonic() + budget
        self._ws.settimeout(budget)
        try:
            self._ws.send(json.dumps({
                "jsonrpc": "2.0",
                "id": rid,
                "method": method,
                "params": params if params is not None else {},
            }))
        except websocket.WebSocketTimeoutException:
            raise XoError("TIMEOUT", f"{method} could not be sent within {budget}s") from None
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise XoError("TIMEOUT", f"{method} did not answer within {budget}s")
            self._ws.settimeout(remaining)
            try:
                frame = self._ws.recv()
            except websocket.WebSocketTimeoutException:
                raise XoError("TIMEOUT", f"{method} did not answer within {budget}s") from None

            try:
                msg = json.loads(frame)
            except ValueError:
                continue
            # A bare scalar is valid JSON and is not subscriptable. Same trap as the XAPI side.
            if not isinstance(msg, dict) or msg.get("id") != rid:
                continue
            if "error" in msg:
                err = msg["error"]
                if isinstance(err, dict):
                    raise XoError(err.get("message", "UNKNOWN"), err.get("data"))
                raise XoError(str(err))
            return msg.get("result")

    # -- the handful of verbs the plugin needs ------------------------------------

    def get_objects(self, filter_=None, limit=None):
        params = {}
        if filter_ is not None:
            params["filter"] = filter_
        if limit is not None:
            params["limit"] = limit
        return self.call("xo.getAllObjects", params)

    def resolve_template(self, name_label):
        """The JSON-RPC shape of HypervisorClient.resolveTemplate.

        Exact match on name_label, never a prefix: clones are named after the template
        they came from, so a prefix match would find the template's own offspring.
        """
        found = self.get_objects({"type": "VM-template", "name_label": name_label})
        if isinstance(found, dict):
            return list(found.values())
        # `or []` rather than list(found): a null result is not iterable, and this is the
        # call the probes' controls make first. A TypeError here escapes as a traceback
        # instead of the ControlFailed that exists to stop the run and conclude nothing.
        return list(found or [])

    def resolve_vm(self, name_label):
        """Exact match on a VM's name_label, the mirror of resolve_template.

        This exists for one case: `vm.create` has no server-side timeout, so a call that
        hits *our* deadline may still have made a VM. Nothing on that VM carries an owner
        marker yet, and an XO-made VM has no other_config for the reaper to select on, so
        its name is the only handle there is. A harness that gives up without looking
        leaves a VM no sweep in this repository can find.
        """
        found = self.get_objects({"type": "VM", "name_label": name_label})
        if isinstance(found, dict):
            return list(found.values())
        return list(found or [])

    def template_vifs(self, template):
        """The `VIFs` argument vm.create needs to give a clone the template's networks.

        MEASURED on the lab pool 2026-09-06, and it is the single biggest behavioural
        difference found between the two backends: **vm.create does NOT inherit the
        template's VIFs, and the REST create_vm route DOES.** Same appliance, same
        template, same minute: the template carries 1 VIF, a bare vm.create clone carries
        0, a bare REST clone carries 1.

        A VIF-less clone is the worst shape this project knows. The guest boots, the tools
        come up, `pvDriversDetected` goes true, `os_version` is correct and complete, and
        the only field telling the truth is the address that never arrives. Measured here:
        240s of a perfectly healthy-looking Debian 13 guest with no way to send a packet.

        The mirror-image trap is on the REST side, so do not copy this call there: `vifs`
        is ADDITIVE, not a replacement. Passing the template's own network to create_vm
        produced a clone with 2 VIFs, which is a two-NIC agent rather than an error.
        """
        vifs = []
        for vif_id in template.get("VIFs") or []:
            found = self.get_objects({"id": vif_id})
            found = list(found.values()) if isinstance(found, dict) else list(found or [])
            network = found[0].get("$network") if found else None
            if network:
                vifs.append({"network": network})
        return vifs

    def create_from_template(self, template_id, name_label, clone=True, **extra):
        """The JSON-RPC shape of HypervisorClient.cloneFromTemplate.

        `clone=True` is what buys copy-on-write: it reaches `_cloneVm`, which is
        `VM.clone`, the same call the XAPI backend makes today. `clone=False` goes to
        `_copyVm` and full-copies every VDI, which on this lab measured 49.59s against
        0.56s. If a create here takes tens of seconds, CoW did not engage.

        Note what is *not* accepted: `vm.create` has no `xenStoreData` and no `tags`
        parameter. The seed and the owner marker are separate calls afterwards, so
        there is a window between creating a VM and stamping it, and a crash inside
        that window leaves a VM no marker-based sweep can find.
        """
        params = {"template": template_id, "name_label": name_label, "clone": clone}
        params.update(extra)
        return self.call("vm.create", params)

    def set_xenstore(self, vm_id, data):
        """Values of None delete the key, via VM.remove_from_xenstore_data underneath."""
        return self.call("vm.set", {"id": vm_id, "xenStoreData": data})

    def delete_vm(self, vm_id, delete_disks=True):
        return self.call("vm.delete", {"id": vm_id, "deleteDisks": delete_disks})

    def add_tag(self, obj_id, tag):
        return self.call("tag.add", {"id": obj_id, "tag": tag})

    def remove_tag(self, obj_id, tag):
        return self.call("tag.remove", {"id": obj_id, "tag": tag})

    def server_version(self):
        return self.call("system.getServerVersion")

    def list_methods(self):
        return self.call("system.listMethods")

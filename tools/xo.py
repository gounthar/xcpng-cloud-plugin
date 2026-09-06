"""Minimal Xen Orchestra JSON-RPC client for the spike's operational tooling.

The companion to `xapi.py`, aimed at the other backend. Deliberately tiny for the same
reason: it exists to prove how few methods the Jenkins plugin actually needs, and to let
the JSON-RPC answers on issue #89 be exercised against a live appliance rather than only
read from source.

    XO_URL=wss://192.168.1.5/api/ XO_TOKEN=... XO_TRUST_SELF_SIGNED=1 python3 tools/xo_probe.py

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
        self._token = token or _from_env("XO_TOKEN")
        self.timeout = timeout
        self._ws = None
        self._id = 0
        self.user = None

        # Same posture as the XAPI client and as the plugin's trustSelfSigned checkbox:
        # opt-in, off by default, and it says so out loud when it is on.
        if trust_self_signed is None:
            trust_self_signed = os.environ.get("XO_TRUST_SELF_SIGNED", "").lower() in (
                "1", "true", "yes",
            )
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
        self._ws = websocket.create_connection(self.url, sslopt=sslopt, timeout=self.timeout)
        self.user = self.call("session.signInWithToken", {"token": self._token})
        return self.user

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
        self._ws.send(json.dumps({
            "jsonrpc": "2.0",
            "id": rid,
            "method": method,
            "params": params if params is not None else {},
        }))

        deadline = time.monotonic() + (timeout if timeout is not None else self.timeout)
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise XoError("TIMEOUT", f"{method} did not answer within {self.timeout}s")
            self._ws.settimeout(remaining)
            try:
                frame = self._ws.recv()
            except websocket.WebSocketTimeoutException:
                raise XoError("TIMEOUT", f"{method} did not answer within {self.timeout}s") from None

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
        return list(found.values()) if isinstance(found, dict) else list(found)

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

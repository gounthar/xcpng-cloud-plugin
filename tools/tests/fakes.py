"""Fake XAPI objects for the tools tests.

A normal module rather than conftest.py, which is a pytest implementation detail and not a
stable import target: `from conftest import ...` breaks under --import-mode=importlib.
conftest.py puts this directory on sys.path so both modes find it.
"""

import json

import websocket

from xapi import XapiError
from xo import XoError


class FakeResponse:
    """Stands in for the object urlopen returns: a context manager that reads bytes.

    `status` exists for the REST client, which branches on it. It defaults to 200 so the
    XAPI tests, which never read it, are unaffected.
    """

    def __init__(self, body, status=200):
        self._body = body
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def read(self, *args):
        return self._body


def vm_record(name, snapshot=False, template=False, control_domain=False, power="Halted",
              owner=None, other_config=None, xenstore_data=None):
    """The subset of a XAPI VM record that reaper.py filters on.

    `owner` stamps the `xcpng-cloud` marker the plugin writes into other_config, i.e. this is
    a VM the plugin provisioned and is answerable for. Leave it None for everything the plugin
    did not create: an operator's VM, the golden image, a pre-plugin probe. Defaulting to None
    matters, because a fake that marked every VM would make the marker filter untestable.
    """
    config = dict(other_config or {})
    if owner is not None:
        config["xcpng-cloud"] = owner
    return {
        "name_label": name,
        "uuid": f"uuid-{name}",
        "power_state": power,
        "is_a_snapshot": snapshot,
        "is_a_template": template,
        "is_control_domain": control_domain,
        "other_config": config,
        # The per-clone seed the plugin writes, and the surface #28's scrub clears. Defaults to
        # empty rather than to a populated seed: a fake that seeded every VM would make a
        # watcher that never reads the key look like one that reads it correctly.
        "xenstore_data": dict(xenstore_data or {}),
    }


class FakeXapi:
    """A pool with the VMs you hand it. Records what was destroyed.

    `stuck` names VMs whose destroy_with_disks raises, standing in for a VM held by an
    in-flight XAPI operation.
    """

    def __init__(self, records=None, stuck=(), vdis_after=None):
        self.host = "dom0.invalid"
        self.records = records or {}
        self.stuck = set(stuck)
        self.destroyed = []
        self._vdi_count = 5
        self._vdis_after = vdis_after

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def default_sr(self):
        return "OpaqueRef:sr"

    def sr_free_bytes(self, sr):
        return 10 * 2**30

    def vdi_count(self, sr):
        if self.destroyed and self._vdis_after is not None:
            return self._vdis_after
        return self._vdi_count

    def disk_vdis(self, vm):
        return [f"vdi-{vm}"]

    def destroy_with_disks(self, vm):
        if vm in self.stuck:
            raise XapiError("VM_BAD_POWER_STATE", vm)
        self.destroyed.append(vm)
        return [f"vdi-{vm}"]

    def call(self, method, *params):
        if method == "VM.get_all_records":
            return self.records
        raise AssertionError(f"unexpected XAPI call: {method}")


class FakeWs:
    """Stands in for a websocket-client connection.

    `frames` is what recv() hands back in order: a dict or list is serialised for you, a
    str is passed through verbatim so a test can feed malformed JSON, and an exception
    instance is raised. Running out of frames raises WebSocketTimeoutException, which is
    what a reply that never arrives actually looks like.

    Everything sent is recorded, decoded, so a test can assert on the JSON-RPC envelope
    the client built rather than only on what the call returned.
    """

    def __init__(self, frames=()):
        self.frames = list(frames)
        self.sent = []
        self.timeouts = []
        self.closed = False

    def send(self, payload):
        self.sent.append(json.loads(payload))

    def recv(self):
        if not self.frames:
            raise websocket.WebSocketTimeoutException("no more frames")
        frame = self.frames.pop(0)
        if isinstance(frame, Exception):
            raise frame
        return frame if isinstance(frame, str) else json.dumps(frame)

    def settimeout(self, value):
        self.timeouts.append(value)

    def close(self):
        self.closed = True


class FakeXo:
    """An XO JSON-RPC client carrying only the verbs the two probes call.

    `templates` maps a name to what resolve_template answers with, so a test can make the
    filter match nothing, one thing, or two. `bogus_succeeds` makes a method that does not
    exist return a result instead of raising -- an appliance answering yes to everything,
    which is the one condition that makes every finding downstream meaningless.
    """

    def __init__(self, methods=("vm.create",), templates=None, bogus_succeeds=False):
        self.methods = list(methods)
        self.templates = dict(templates or {})
        self.bogus_succeeds = bogus_succeeds
        self.deleted = []
        self.stuck = set()

    def list_methods(self):
        return self.methods

    def call(self, method, params=None, **kwargs):
        if self.bogus_succeeds:
            return "yes"
        raise XoError("NO_SUCH_METHOD", method)

    def resolve_template(self, name):
        return list(self.templates.get(name, []))

    def delete_vm(self, vm_id, delete_disks=True):
        if vm_id in self.stuck:
            raise XoError("VM_BAD_POWER_STATE", vm_id)
        self.deleted.append(vm_id)

"""Fake XAPI objects for the tools tests.

A normal module rather than conftest.py, which is a pytest implementation detail and not a
stable import target: `from conftest import ...` breaks under --import-mode=importlib.
conftest.py puts this directory on sys.path so both modes find it.
"""

from xapi import XapiError


class FakeResponse:
    """Stands in for the object urlopen returns: a context manager that reads bytes."""

    def __init__(self, body):
        self._body = body

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

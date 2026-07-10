"""Shared fakes for the tools tests.

Every test here runs against a fake XAPI. Nothing touches a hypervisor, nothing needs
credentials, and the whole suite finishes in well under a second. That is the point: these
guard control flow, not the XAPI contract. The contract is checked by running the tools
against the lab pool by hand, which is what they exist for.
"""

import pathlib
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from xapi import Xapi, XapiError  # noqa: E402


@pytest.fixture
def client():
    """An Xapi with its __init__ skipped, so no environment and no network."""
    x = Xapi.__new__(Xapi)
    x.host = "pool.invalid"
    x.session = "OpaqueRef:session"
    x._id = 0
    x._ctx = None
    return x


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


@pytest.fixture
def respond_with(monkeypatch):
    """Make the next urlopen return this raw body."""
    import urllib.request

    def _respond(body):
        monkeypatch.setattr(urllib.request, "urlopen", lambda *a, **k: FakeResponse(body))

    return _respond


@pytest.fixture
def raise_from_urlopen(monkeypatch):
    """Make the next urlopen raise this exception."""
    import urllib.request

    def _raise(exc):
        def boom(*args, **kwargs):
            raise exc

        monkeypatch.setattr(urllib.request, "urlopen", boom)

    return _raise


def vm_record(name, snapshot=False, template=False, control_domain=False, power="Halted"):
    """The subset of a XAPI VM record that reaper.py filters on."""
    return {
        "name_label": name,
        "uuid": f"uuid-{name}",
        "power_state": power,
        "is_a_snapshot": snapshot,
        "is_a_template": template,
        "is_control_domain": control_domain,
    }


class FakeXapi:
    """A pool with the VMs you hand it. Records what was destroyed.

    `stuck` names VMs whose destroy_with_disks raises, standing in for a VM held by an
    in-flight XAPI operation.
    """

    def __init__(self, records=None, stuck=(), vdis_after=None):
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

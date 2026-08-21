"""Tests for build_controller.py.

Nothing here touches a pool. What they guard is the part that is easy to get wrong and
expensive to discover: the seed cloud-init is handed, the ordering the XAPI calls go out in,
and the cleanup that runs when an import fails. Each of those has already cost a rebuild
once.
"""

import pathlib
import sys

import pytest
from xapi import XapiError

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import build_controller as bc  # noqa: E402


class RecordingXapi:
    """A pool that answers with whatever you seed it and remembers the call order."""

    def __init__(self, responses=None):
        self.host = "pool.invalid"
        self.session = "OpaqueRef:session"
        self._ctx = None
        self.calls = []
        self.responses = responses or {}
        self.destroyed_vdis = []

    def call(self, method, *params):
        self.calls.append(method)
        if method == "VDI.destroy":
            self.destroyed_vdis.append(params[0])
        value = self.responses.get(method, {})
        return value(*params) if callable(value) else value

    def methods(self, prefix):
        return [c for c in self.calls if c.startswith(prefix)]


# -- the derived MAC --------------------------------------------------------


def test_stable_mac_is_deterministic_and_inside_the_xen_oui():
    first = bc.stable_mac("jenkins-controller")
    assert first == bc.stable_mac(
        "jenkins-controller"
    ), "a rebuild must reuse the same lease"
    assert first.startswith(bc.XEN_OUI)
    assert len(first.split(":")) == 6


def test_stable_mac_differs_per_name():
    # Two controllers on one pool sharing a MAC would fight over one DHCP lease.
    assert bc.stable_mac("controller-a") != bc.stable_mac("controller-b")


# -- the cloud-init seed ----------------------------------------------------


def test_network_config_matches_the_interface_by_glob_not_by_name():
    # Under Xen the NIC comes up as enX0. A config naming eth0 yields a VM with no network
    # and no way in, which is only discoverable by console.
    assert 'name: "en*"' in bc.network_config("dhcp", None, None)
    assert "eth0" not in bc.network_config("dhcp", None, None)


def test_dhcp_network_config_asks_for_dhcp():
    assert "dhcp4: true" in bc.network_config("dhcp", None, None)


def test_static_network_config_carries_address_gateway_and_dns():
    cfg = bc.network_config("192.168.1.99/24", "192.168.1.254", "1.1.1.1")
    assert "dhcp4: false" in cfg
    assert "addresses: [192.168.1.99/24]" in cfg
    assert "via: 192.168.1.254" in cfg
    assert "addresses: [1.1.1.1]" in cfg


def test_user_data_installs_the_guest_tools_from_the_iso():
    # xe-guest-utilities is not in Debian's repositories, so a packages: entry can never
    # work and the .deb on the mounted ISO is the only route.
    data = bc.user_data("ctl", "ssh-ed25519 AAAA test@host", "-----KEY-----")
    assert "/mnt/tools/Linux/xe-guest-utilities_*_amd64.deb" in data
    assert "packages:\n  - ca-certificates" in data


def test_user_data_reports_missing_guest_tools_loudly():
    # A blank line in the build record reads like success. It has to say the opposite.
    assert "guest tools NOT installed" in bc.user_data(
        "ctl", "ssh-ed25519 AAAA k", "-KEY-"
    )


def test_user_data_authorises_the_given_key():
    data = bc.user_data("ctl", "ssh-ed25519 AAAAdistinctive operator@host", "-KEY-")
    assert "ssh-ed25519 AAAAdistinctive operator@host" in data


# -- import cleanup ---------------------------------------------------------


class _FakeResponse:
    """The shape urlopen really returns: a context manager that closes.

    The previous fake returned None, which only worked because the caller dropped the
    response on the floor without closing it. A fake that accepts what the real API would
    not is how an unclosed-response bug stays invisible.
    """

    def __init__(self, body=b""):
        self._body = body
        self.closed = False

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.closed = True
        return False


def fake_urlopen(*args, **kwargs):
    """Hands back the response it produced, so a test can assert it was closed."""
    fake_urlopen.last = _FakeResponse()
    return fake_urlopen.last



def test_a_failed_import_destroys_its_own_vdi(monkeypatch, tmp_path):
    """reaper.py iterates VMs, so a VDI attached to nothing leaks where nothing will find it."""
    image = tmp_path / "disk.raw"
    image.write_bytes(b"0" * 16)

    def boom(*args, **kwargs):
        raise OSError("connection reset mid-upload")

    monkeypatch.setattr(bc.urllib.request, "urlopen", boom)
    x = RecordingXapi({"VDI.create": "OpaqueRef:vdi"})

    with pytest.raises(OSError):
        bc.import_vdi(x, "OpaqueRef:sr", image, "disk")

    assert x.destroyed_vdis == ["OpaqueRef:vdi"], "the stranded VDI must be destroyed"


def test_import_sizes_the_vdi_up_front_rather_than_resizing(monkeypatch, tmp_path):
    """VDI.resize straight after an import fails VDI_IN_USE, so the size is set at create."""
    image = tmp_path / "disk.raw"
    image.write_bytes(b"0" * 16)
    monkeypatch.setattr(bc.urllib.request, "urlopen", fake_urlopen)

    seen = {}

    def create(record):
        seen.update(record)
        return "OpaqueRef:vdi"

    x = RecordingXapi({"VDI.create": create})
    bc.import_vdi(x, "OpaqueRef:sr", image, "disk", virtual_size=40 * 1024**3)

    assert seen["virtual_size"] == str(40 * 1024**3)
    assert "VDI.resize" not in x.calls
    # The upload response has to be closed. Without this the `with` could be dropped and
    # every other assertion here would still pass.
    assert fake_urlopen.last.closed, "the import_raw_vdi response must be closed"


# -- void task handling -----------------------------------------------------


def test_await_void_task_accepts_a_task_with_no_opaque_ref():
    """Xapi.await_task raises TASK_RESULT_UNPARSEABLE for a void task such as VM.start (#79)."""
    x = RecordingXapi({"task.get_status": "success"})
    bc.await_void_task(x, "OpaqueRef:task", poll=0)
    assert "task.destroy" in x.calls


def test_await_void_task_raises_on_failure():
    x = RecordingXapi(
        {"task.get_status": "failure", "task.get_error_info": ["INTERNAL_ERROR"]}
    )
    with pytest.raises(XapiError):
        bc.await_void_task(x, "OpaqueRef:task", poll=0)
    # The failing path is the one that leaks: a task record stays on the pool for every
    # failed start unless the destroy runs from a finally.
    assert "task.destroy" in x.calls


def test_await_void_task_destroys_the_task_on_timeout():
    """A timeout raises from a different branch than a failure, so it needs its own case."""
    x = RecordingXapi({"task.get_status": "pending"})
    with pytest.raises(XapiError):
        bc.await_void_task(x, "OpaqueRef:task", poll=0, timeout=0)
    assert "task.destroy" in x.calls


# -- the reservation hook ---------------------------------------------------


def test_a_failing_reservation_hook_is_not_fatal(monkeypatch):
    """The address is read back from XAPI regardless, so a missing hook must not stop a build."""
    monkeypatch.setattr(
        bc.subprocess,
        "run",
        lambda *a, **k: (_ for _ in ()).throw(OSError("no such file")),
    )
    assert (
        bc.run_reservation_hook("/nonexistent/hook", "00:16:3e:00:00:01", "ctl") is None
    )


# -- the orphan sweep -------------------------------------------------------


def sweep_pool(vdis):
    """A pool holding `vdis`, with the VM scan empty so only the sweep runs."""
    return RecordingXapi({"VM.get_all_records": {}, "VDI.get_all_records": vdis})


def orphan(label, description=bc.VDI_MARKER, sr="OpaqueRef:sr", vbds=None):
    return {
        "name_label": label,
        "name_description": description,
        "SR": sr,
        "VBDs": vbds or [],
    }


def test_the_sweep_destroys_this_scripts_own_stranded_disks():
    x = sweep_pool(
        {
            "OpaqueRef:root": orphan("debian-13-genericcloud"),
            "OpaqueRef:seed": orphan("ctl-cidata"),
        }
    )
    bc.destroy_existing(x, "ctl", "OpaqueRef:sr")
    assert sorted(x.destroyed_vdis) == ["OpaqueRef:root", "OpaqueRef:seed"]


def test_the_sweep_spares_a_matching_label_this_script_did_not_create():
    """The pool is shared. `debian-13-genericcloud` is the name anyone importing a stock
    Debian cloud image lands on, so a label match alone would destroy another operator's
    disk with no confirmation and no recovery."""
    x = sweep_pool(
        {"OpaqueRef:theirs": orphan("debian-13-genericcloud", description="")}
    )
    bc.destroy_existing(x, "ctl", "OpaqueRef:sr")
    assert x.destroyed_vdis == [], "a disk without our marker is not ours to destroy"


def test_the_sweep_stays_inside_the_sr_this_run_writes_to():
    x = sweep_pool(
        {"OpaqueRef:elsewhere": orphan("debian-13-genericcloud", sr="OpaqueRef:other")}
    )
    bc.destroy_existing(x, "ctl", "OpaqueRef:sr")
    assert x.destroyed_vdis == []


def test_the_sweep_leaves_an_attached_disk_alone():
    """A VDI with a VBD belongs to a live VM, marker or not."""
    x = sweep_pool(
        {"OpaqueRef:live": orphan("debian-13-genericcloud", vbds=["OpaqueRef:vbd"])}
    )
    bc.destroy_existing(x, "ctl", "OpaqueRef:sr")
    assert x.destroyed_vdis == []


def test_the_seed_vdi_is_scoped_to_the_name_being_rebuilt():
    """Two controllers on one pool: rebuilding one must not take the other's seed."""
    x = sweep_pool({"OpaqueRef:other-seed": orphan("other-cidata")})
    bc.destroy_existing(x, "ctl", "OpaqueRef:sr")
    assert x.destroyed_vdis == []


def test_the_marker_the_sweep_matches_is_the_one_the_create_writes(monkeypatch, tmp_path):
    """These two moved apart once already because each held its own string literal. If a
    rename breaks this, the sweep silently stops finding anything rather than failing."""
    image = tmp_path / "disk.raw"
    image.write_bytes(b"0" * 16)
    monkeypatch.setattr(bc.urllib.request, "urlopen", fake_urlopen)

    seen = {}

    def create(record):
        seen.update(record)
        return "OpaqueRef:vdi"

    bc.import_vdi(
        RecordingXapi({"VDI.create": create}),
        "OpaqueRef:sr",
        image,
        "debian-13-genericcloud",
    )

    x = sweep_pool(
        {
            "OpaqueRef:o": orphan(
                "debian-13-genericcloud", description=seen["name_description"]
            )
        }
    )
    bc.destroy_existing(x, "ctl", "OpaqueRef:sr")
    assert x.destroyed_vdis == ["OpaqueRef:o"]


# -- the image checksum -----------------------------------------------------


def test_a_missing_checksum_line_names_the_artifact(monkeypatch, tmp_path):
    """A renamed upstream artifact hits this. A bare next() raises StopIteration, and the
    operator gets a traceback naming nothing."""
    monkeypatch.setattr(
        bc.urllib.request, "urlretrieve", lambda url, dest: pathlib.Path(dest).write_bytes(b"x")
    )
    monkeypatch.setattr(bc.urllib.request, "urlopen", lambda *a, **k: _FakeSums(b"abc  some-other-image.qcow2\n"))

    with pytest.raises(SystemExit) as exc:
        bc.fetch_image(tmp_path)
    assert "debian-13-genericcloud-amd64.qcow2" in str(exc.value)


class _FakeSums:
    def __init__(self, body):
        self.body = body

    def read(self):
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


# -- the binary preflight ---------------------------------------------------


def test_missing_binaries_are_reported_before_anything_is_downloaded(monkeypatch, tmp_path):
    """Without this the FileNotFoundError lands after a few hundred MiB have been fetched."""
    key = tmp_path / "k.pub"
    key.write_text("ssh-ed25519 AAAA test\n")

    def nothing_installed(binary):
        return None

    monkeypatch.setattr(bc.shutil, "which", nothing_installed)
    downloaded = []
    monkeypatch.setattr(
        bc.urllib.request, "urlretrieve", lambda *a, **k: downloaded.append(a)
    )

    with pytest.raises(SystemExit) as exc:
        bc.main(["--pubkey", str(key)])
    assert "genisoimage" in str(exc.value) and "qemu-img" in str(exc.value)
    assert downloaded == [], "the check must run before the download, not after"


# -- argument validation ----------------------------------------------------


def test_static_address_requires_a_gateway_and_nameserver():
    with pytest.raises(SystemExit):
        bc.parse_args(["--pubkey", "/dev/null", "--address", "192.168.1.99/24"])


def test_address_must_be_dhcp_or_cidr():
    with pytest.raises(SystemExit):
        bc.parse_args(["--pubkey", "/dev/null", "--address", "192.168.1.99"])


def test_dhcp_is_the_default_and_needs_no_network_knowledge():
    args = bc.parse_args(["--pubkey", "/dev/null"])
    assert args.address == "dhcp"
    assert args.mac == bc.stable_mac("jenkins-controller")

"""Minimal XAPI JSON-RPC client for the spike's operational tooling.

Deliberately tiny: it exists to prove how few methods the Jenkins plugin actually needs.
Credentials come from the environment; nothing is ever written to disk.

    XCPNG_HOST=192.168.1.87 XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py

Note the two XAPI transports return *different* error envelopes:
  /jsonrpc  -> {"jsonrpc":"2.0","error":{"message":"SESSION_INVALID","data":[...]}}
  /         -> XML-RPC, {"Status":"Failure","ErrorDescription":[...]}
This client speaks JSON-RPC only.
"""

import json
import os
import re
import ssl
import sys
import time
import urllib.request

OPAQUE_REF = re.compile(r"OpaqueRef:[0-9a-fA-F-]+")

# SR types whose VM.clone yields a copy-on-write fast clone. LVM-based SRs full-copy instead.
COW_SR_TYPES = {"ext", "ext4", "nfs", "file", "xfs", "zfs", "largeblock"}


class XapiError(RuntimeError):
    def __init__(self, message, data=None):
        super().__init__(f"{message} {data if data is not None else ''}".strip())
        self.message = message
        self.data = data


class Xapi:
    def __init__(self, host=None, user=None, password=None, trust_self_signed=None):
        self.host = host or os.environ["XCPNG_HOST"]
        self._user = user or os.environ["XCPNG_USER"]
        self._password = password or os.environ["XCPNG_PASS"]
        self.session = None

        # Pools typically present a self-signed cert, but trusting it must be a deliberate,
        # visible act rather than the default. Same posture the plugin's trustSelfSigned
        # checkbox will have: opt-in, off by default, and it says so out loud.
        if trust_self_signed is None:
            trust_self_signed = os.environ.get("XCPNG_TRUST_SELF_SIGNED", "").lower() in (
                "1", "true", "yes",
            )

        self._ctx = ssl.create_default_context()
        if trust_self_signed:
            self._ctx.check_hostname = False
            self._ctx.verify_mode = ssl.CERT_NONE
            print(
                f"warning: TLS verification disabled for {self.host} "
                f"(XCPNG_TRUST_SELF_SIGNED). Traffic is exposed to interception.",
                file=sys.stderr,
            )
        self._id = 0

    # -- plumbing ---------------------------------------------------------

    def _raw(self, method, params):
        self._id += 1
        body = json.dumps({"jsonrpc": "2.0", "id": self._id, "method": method, "params": params})
        req = urllib.request.Request(
            f"https://{self.host}/jsonrpc",
            data=body.encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, context=self._ctx, timeout=30) as r:
            payload = json.load(r)
        if "error" in payload:
            err = payload["error"]
            raise XapiError(err.get("message", "UNKNOWN"), err.get("data"))
        return payload["result"]

    def call(self, method, *params):
        """Call an authenticated method, transparently re-logging in on SESSION_INVALID."""
        try:
            return self._raw(method, [self.session, *params])
        except XapiError as e:
            if e.message != "SESSION_INVALID":
                raise
            self.login()
            return self._raw(method, [self.session, *params])

    def login(self):
        self.session = self._raw(
            "session.login_with_password", [self._user, self._password, "", "xcpng-cloud-spike"]
        )
        return self.session

    def logout(self):
        if self.session:
            try:
                self._raw("session.logout", [self.session])
            finally:
                self.session = None

    def __enter__(self):
        self.login()
        return self

    def __exit__(self, *exc):
        self.logout()

    # -- async task helper ------------------------------------------------

    def await_task(self, task, poll=0.25, timeout=900):
        """Block until an Async.* task settles; return the OpaqueRef it produced."""
        deadline = time.monotonic() + timeout
        try:
            while True:
                status = self.call("task.get_status", task)
                if status == "success":
                    match = OPAQUE_REF.search(self.call("task.get_result", task) or "")
                    if not match:
                        raise XapiError("TASK_RESULT_UNPARSEABLE", task)
                    return match.group(0)
                if status in ("failure", "cancelled"):
                    raise XapiError(f"TASK_{status.upper()}", self.call("task.get_error_info", task))
                if time.monotonic() > deadline:
                    raise XapiError("TASK_TIMEOUT", task)
                time.sleep(poll)
        finally:
            try:
                self.call("task.destroy", task)
            except XapiError:
                pass

    # -- the verbs the plugin needs ---------------------------------------

    def disk_vdis(self, vm):
        """VDIs backing a VM's real disks. MUST be collected before VM.destroy:
        destroying the VM removes its VBDs, orphaning the VDIs beyond reach."""
        vdis = []
        for vbd in self.call("VM.get_VBDs", vm):
            if self.call("VBD.get_type", vbd) != "Disk":
                continue
            vdi = self.call("VBD.get_VDI", vbd)
            if vdi and "NULL" not in vdi:
                vdis.append(vdi)
        return vdis

    def destroy_with_disks(self, vm):
        """The teardown the blog post is about. Returns the VDIs destroyed."""
        vdis = self.disk_vdis(vm)  # capture first
        if self.call("VM.get_power_state", vm) != "Halted":
            self.call("VM.hard_shutdown", vm)
        self.call("VM.destroy", vm)
        for vdi in vdis:  # then reap the disks the VM left behind
            self.call("VDI.destroy", vdi)
        return vdis

    def sr_free_bytes(self, sr):
        self.call("SR.scan", sr)
        size = int(self.call("SR.get_physical_size", sr) or 0)
        used = int(self.call("SR.get_physical_utilisation", sr) or 0)
        return size - used

    def default_sr(self):
        """The single non-ISO SR on a simple host."""
        for sr in self.call("SR.get_all"):
            if self.call("SR.get_type", sr) not in ("iso", "udev"):
                return sr
        raise XapiError("NO_USABLE_SR")

    def vdi_count(self, sr):
        return len(self.call("SR.get_VDIs", sr))

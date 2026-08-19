"""Minimal XAPI JSON-RPC client for the spike's operational tooling.

Deliberately tiny: it exists to prove how few methods the Jenkins plugin actually needs.
Credentials come from the environment; nothing is ever written to disk.

    XCPNG_HOST=192.168.1.87 XCPNG_USER=root XCPNG_PASS=... python3 tools/reaper.py

Note the two XAPI transports return *different* error envelopes:
  /jsonrpc  -> {"jsonrpc":"2.0","error":{"message":"SESSION_INVALID","data":[...]}}
  /         -> XML-RPC, {"Status":"Failure","ErrorDescription":[...]}
This client speaks JSON-RPC only.
"""

import http.client
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


def _from_env(name):
    """Credentials live in the environment, so a missing one is a user error, not a bug."""
    try:
        return os.environ[name]
    except KeyError:
        raise XapiError(
            "MISSING_ENV",
            f"{name} is not set. Export XCPNG_HOST, XCPNG_USER and XCPNG_PASS.",
        ) from None


class Xapi:
    def __init__(self, host=None, user=None, password=None, trust_self_signed=None):
        self.host = host or _from_env("XCPNG_HOST")
        self._user = user or _from_env("XCPNG_USER")
        self._password = password or _from_env("XCPNG_PASS")
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
        try:
            with urllib.request.urlopen(req, context=self._ctx, timeout=30) as r:
                payload = json.load(r)
        except OSError as e:
            # urlopen returns once the headers land; json.load then reads the body, and the
            # timeout above applies per socket operation. So a stall mid-body raises
            # TimeoutError, and a dropped connection raises ConnectionResetError, neither of
            # which is a URLError. All three are OSError, which is also URLError's base, so
            # one clause covers the connect and read phases alike.
            reason = getattr(e, "reason", None)
            if reason is None:
                reason = str(e) or e.__class__.__name__
            hint = ""
            if isinstance(reason, ssl.SSLCertVerificationError):
                hint = "; set XCPNG_TRUST_SELF_SIGNED=1 to accept a self-signed pool cert"
            raise XapiError("TRANSPORT_ERROR", f"{method}: {reason}{hint}") from e
        except http.client.HTTPException as e:
            # IncompleteRead and friends are HTTPException, not OSError.
            raise XapiError("TRANSPORT_ERROR", f"{method}: truncated response: {e}") from e
        except ValueError as e:
            # JSONDecodeError, and UnicodeDecodeError from a non-UTF-8 body. Both ValueError,
            # neither a subclass of the other.
            raise XapiError("MALFORMED_RESPONSE", f"{method}: {e}") from e
        # A bare JSON scalar (5, null, true) is not iterable, so `"error" in payload` would
        # raise TypeError rather than an XapiError. A list or string happens to survive that
        # and falls through to NO_RESULT, but only by accident. Check the shape once.
        if not isinstance(payload, dict):
            raise XapiError("MALFORMED_RESPONSE", f"{method}: expected a JSON object, got {type(payload).__name__}")
        if "error" in payload:
            err = payload["error"]
            if not isinstance(err, dict):
                raise XapiError("UNKNOWN", f"{method}: {err}")
            raise XapiError(err.get("message", "UNKNOWN"), err.get("data"))
        if "result" not in payload:
            raise XapiError("NO_RESULT", f"{method}: {payload}")
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

    # -- async task helpers -----------------------------------------------

    def _settle_task(self, task, poll, timeout):
        """Block until an Async.* task settles; return its raw result string.

        The result has to be carried back from here rather than re-read by the caller: the
        finally below destroys the task, so once this returns there is no task record left
        to ask what happened.
        """
        deadline = time.monotonic() + timeout
        try:
            while True:
                status = self.call("task.get_status", task)
                if status == "success":
                    return self.call("task.get_result", task) or ""
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

    def await_task(self, task, poll=0.25, timeout=900):
        """Await a task that yields an object reference (VM.clone); return the OpaqueRef.

        Strict on purpose: a caller that asked for a ref and silently got None would carry
        it into the next call as a VM ref. Void verbs want await_void_task instead. The
        raise quotes the result because the task is gone by the time it surfaces.
        """
        result = self._settle_task(task, poll, timeout)
        match = OPAQUE_REF.search(result)
        if not match:
            raise XapiError("TASK_RESULT_UNPARSEABLE", f"{task} settled with {result!r}")
        return match.group(0)

    def await_void_task(self, task, poll=0.25, timeout=900):
        """Await a task whose verb returns nothing: VM.start, hard_shutdown, destroy.

        Those settle with an empty result, which is success and not a parse failure. The
        Java client draws the same line: awaitTask hands back the raw result, awaitTaskRef
        is the only one that insists on a reference.
        """
        self._settle_task(task, poll, timeout)

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
        """The teardown the blog post is about. Returns the VDIs actually destroyed.

        Every VDI is attempted even after one fails: giving up halfway is how disks get
        orphaned. Failures are reported but not raised, so a reap sweep over many VMs is
        not halted by one stuck disk. The caller's VDI-count delta is what proves the
        teardown was clean.
        """
        vdis = self.disk_vdis(vm)  # capture first
        if self.call("VM.get_power_state", vm) != "Halted":
            self.call("VM.hard_shutdown", vm)
        self.call("VM.destroy", vm)
        destroyed = []
        for vdi in vdis:  # then reap the disks the VM left behind
            try:
                self.call("VDI.destroy", vdi)
            except XapiError as e:
                print(f"warning: VDI {vdi} survived teardown: {e}", file=sys.stderr)
            else:
                destroyed.append(vdi)
        return destroyed

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

"""Minimal Xen Orchestra REST client, the third transport in this toolbox.

`xapi.py` speaks XAPI JSON-RPC over HTTP, `xo.py` speaks XO JSON-RPC over a WebSocket,
and this speaks XO's REST API. It exists so issue #89's two candidate backends can be
measured against each other on the same pool rather than argued about from source.

    XO_BASE=https://192.168.1.5 XO_TOKEN=... XO_TRUST_SELF_SIGNED=1

The one thing worth knowing before using it: **VM creation hangs off /pools, not /vms**.
`POST /pools/{id}/actions/create_vm` is the route, and it wants a BARE template uuid.
The pool-prefixed form that XO hands you nearly everywhere else returns 404 "no such
object", which reads exactly like the capability being missing. That mistake has now been
made twice on this project, so it gets a guard in `create_vm` below rather than a comment.
"""

import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request

BARE_UUID_LEN = 36


class XoRestError(RuntimeError):
    def __init__(self, message, status=None, data=None):
        super().__init__(f"{message} {data if data is not None else ''}".strip())
        self.message = message
        self.status = status
        self.data = data


def _from_env(name):
    try:
        return os.environ[name]
    except KeyError:
        raise XoRestError("MISSING_ENV", data=f"{name} is not set.") from None


class XoRest:
    def __init__(self, base=None, token=None, trust_self_signed=None, timeout=300):
        self.base = (base or _from_env("XO_BASE")).rstrip("/")
        self._token = token or _from_env("XO_TOKEN")
        self.timeout = timeout

        if trust_self_signed is None:
            trust_self_signed = os.environ.get("XO_TRUST_SELF_SIGNED", "").lower() in (
                "1", "true", "yes",
            )
        self._ctx = ssl.create_default_context()
        if trust_self_signed:
            self._ctx.check_hostname = False
            self._ctx.verify_mode = ssl.CERT_NONE
            print(
                f"warning: TLS verification disabled for {self.base} (XO_TRUST_SELF_SIGNED).",
                file=sys.stderr,
            )

    def request(self, method, path, body=None):
        url = f"{self.base}{path}"
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Cookie", f"authenticationToken={self._token}")
        if data is not None:
            req.add_header("Content-Type", "application/json")

        try:
            with urllib.request.urlopen(req, timeout=self.timeout, context=self._ctx) as resp:
                raw = resp.read()
                status = resp.status
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            try:
                payload = json.loads(raw)
            except ValueError:
                payload = raw.decode(errors="replace")
            raise XoRestError(
                payload.get("error") if isinstance(payload, dict) else str(payload),
                status=exc.code,
                data=payload.get("data") if isinstance(payload, dict) else None,
            ) from None
        # urlopen's timeout is per socket operation and covers the body read, and a read
        # failure is an OSError rather than a URLError. Same trap as the XAPI client.
        except OSError as exc:
            raise XoRestError("TRANSPORT", data=str(exc)) from None

        if not raw:
            return None, status
        try:
            return json.loads(raw), status
        except ValueError:
            return raw.decode(errors="replace"), status

    def get(self, path):
        return self.request("GET", path)[0]

    def create_vm(self, pool_id, template_id, name_label, clone=True, boot=False, **extra):
        """POST /pools/{id}/actions/create_vm. Template id must be BARE."""
        if "/" in template_id:
            raise XoRestError(
                "PREFIXED_TEMPLATE_ID",
                data=(f"{template_id!r} is pool-prefixed. This route needs the bare uuid; "
                      f"the prefixed form returns 404 and looks like a missing capability."),
            )
        if len(template_id) != BARE_UUID_LEN:
            raise XoRestError("ODD_TEMPLATE_ID", data=f"{template_id!r} is not a bare uuid")

        body = {"name_label": name_label, "template": template_id, "clone": clone, "boot": boot}
        body.update(extra)
        started = time.monotonic()
        payload, status = self.request(
            "POST", f"/rest/v0/pools/{pool_id}/actions/create_vm?sync=true", body
        )
        elapsed = time.monotonic() - started
        vm_id = payload.get("id") if isinstance(payload, dict) else payload
        if status != 201 or not vm_id:
            raise XoRestError("CREATE_FAILED", status=status, data=payload)
        return vm_id, elapsed

    def set_xenstore(self, vm_id, data):
        return self.request("PATCH", f"/rest/v0/vms/{vm_id}", {"xenStoreData": data})[1]

    def add_tag(self, vm_id, tag):
        return self.request("PUT", f"/rest/v0/vms/{vm_id}/tags/{tag}")[1]

    def remove_tag(self, vm_id, tag):
        return self.request("DELETE", f"/rest/v0/vms/{vm_id}/tags/{tag}")[1]

    def delete_vm(self, vm_id):
        """No deleteDisks parameter exists on this route. Disks always go."""
        started = time.monotonic()
        _, status = self.request("DELETE", f"/rest/v0/vms/{vm_id}")
        return status, time.monotonic() - started

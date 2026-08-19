"""Guards on the XAPI client's failure paths.

Every bug found by review on PR #1 was in an error path, never the happy path. The happy
path runs on every trip to the lab; the error paths run on the day something goes wrong on
a live hypervisor, which is when a traceback costs the most.
"""

import http.client
import ssl
import urllib.error

import pytest

from xapi import Xapi, XapiError


# -- _raw: transport --------------------------------------------------------

@pytest.mark.parametrize(
    "exc, why",
    [
        (urllib.error.URLError(ssl.SSLCertVerificationError("self-signed")), "connect: bad cert"),
        (TimeoutError(), "read: body stalled, and TimeoutError is not a URLError"),
        (ConnectionResetError("reset by peer"), "read: peer dropped, not a URLError"),
        (http.client.IncompleteRead(b"half"), "read: truncated, an HTTPException not an OSError"),
    ],
)
def test_transport_failures_become_xapi_error(client, raise_from_urlopen, exc, why):
    """call() only ever retries on XapiError, so nothing may escape as a raw exception."""
    raise_from_urlopen(exc)
    with pytest.raises(XapiError) as caught:
        client._raw("VM.get_all", [])
    assert caught.value.message == "TRANSPORT_ERROR", why


def test_self_signed_cert_names_the_env_var(client, raise_from_urlopen):
    """A lab pool presents a self-signed cert. Say which variable turns verification off."""
    raise_from_urlopen(urllib.error.URLError(ssl.SSLCertVerificationError("self-signed")))
    with pytest.raises(XapiError, match="XCPNG_TRUST_SELF_SIGNED"):
        client._raw("session.login_with_password", [])


# -- _raw: envelope shape ---------------------------------------------------

@pytest.mark.parametrize(
    "body, why",
    [
        (b"5", "a bare number is not iterable: `'error' in payload` used to raise TypeError"),
        (b"null", "so is null"),
        (b"true", "so is a bare bool"),
        (b"[1, 2]", "a list survived the membership test by accident, not by design"),
        (b'"hi"', "so did a string"),
        (b"<html>nope</html>", "not JSON at all"),
        ("café".encode("latin-1") + b"{", "valid HTTP, non-UTF-8 body: UnicodeDecodeError"),
    ],
)
def test_malformed_payloads_become_xapi_error(client, respond_with, body, why):
    respond_with(body)
    with pytest.raises(XapiError) as caught:
        client._raw("VM.get_all", [])
    assert caught.value.message == "MALFORMED_RESPONSE", why


def test_non_dict_error_envelope_does_not_raise_attribute_error(client, respond_with):
    """`{"error": "boom"}` used to reach err.get() and raise AttributeError."""
    respond_with(b'{"error": "boom"}')
    with pytest.raises(XapiError) as caught:
        client._raw("VM.get_all", [])
    assert caught.value.message == "UNKNOWN"


def test_payload_without_result_raises_rather_than_key_error(client, respond_with):
    respond_with(b'{"jsonrpc": "2.0", "id": 1}')
    with pytest.raises(XapiError) as caught:
        client._raw("VM.get_all", [])
    assert caught.value.message == "NO_RESULT"


def test_result_envelope_returns_the_result(client, respond_with):
    respond_with(b'{"jsonrpc": "2.0", "id": 1, "result": "OpaqueRef:vm"}')
    assert client._raw("VM.get_all", []) == "OpaqueRef:vm"


def test_session_invalid_message_survives_for_the_retry(client, respond_with):
    """call() re-logs in on exactly this message. If it is mangled, the retry dies."""
    respond_with(b'{"error": {"message": "SESSION_INVALID", "data": ["x"]}}')
    with pytest.raises(XapiError) as caught:
        client._raw("VM.get_all", [])
    assert caught.value.message == "SESSION_INVALID"


# -- credentials ------------------------------------------------------------

def test_missing_env_var_is_named(monkeypatch):
    """Credentials come from the environment by design, so an absent one is a user error."""
    monkeypatch.delenv("XCPNG_HOST", raising=False)
    monkeypatch.setenv("XCPNG_USER", "root")
    monkeypatch.setenv("XCPNG_PASS", "hunter2")
    with pytest.raises(XapiError, match="XCPNG_HOST is not set"):
        Xapi()


def test_missing_env_var_does_not_leak_the_password(monkeypatch):
    """The password must be present and unmentioned, or this asserts nothing.

    Delete XCPNG_USER, not XCPNG_PASS: the failure has to happen while the password is
    actually in the environment, otherwise the assertion below is vacuously true and would
    survive an error handler that echoed os.environ wholesale.
    """
    monkeypatch.setenv("XCPNG_HOST", "pool.invalid")
    monkeypatch.delenv("XCPNG_USER", raising=False)
    monkeypatch.setenv("XCPNG_PASS", "hunter2")
    with pytest.raises(XapiError, match="XCPNG_USER is not set") as caught:
        Xapi()
    assert "hunter2" not in str(caught.value)


# -- async task helpers -----------------------------------------------------

class TaskCalls:
    """The task.* calls the settle loop makes, plus a record of which ones it made."""

    def __init__(self, result="", status="success", error_info="none"):
        self.result = result
        self.status = status
        self.error_info = error_info
        self.methods = []

    def __call__(self, method, *params):
        self.methods.append(method)
        if method == "task.get_status":
            return self.status
        if method == "task.get_result":
            return self.result
        if method == "task.get_error_info":
            return self.error_info
        if method == "task.destroy":
            return None
        raise AssertionError(method)


@pytest.mark.parametrize(
    "result, why",
    [
        ("<value></value>", "measured on the pool: what a settled void task really holds"),
        ("", "an empty body, should a backend answer that instead"),
        (None, "get_result may answer null rather than a string"),
        ("<value/>", "the self-closing spelling of the same empty value"),
    ],
)
def test_a_void_task_settles_without_raising(client, result, why):
    """A void verb settles with no result, and that is success rather than a parse failure.

    Measured on the lab pool: Async.VM.start left the VM at power_state=Running with a
    domid, while await_task raised TASK_RESULT_UNPARSEABLE at the empty result. Every void
    verb the plugin uses is affected -- start, hard_shutdown, destroy.
    """
    client.call = TaskCalls(result=result)
    assert client.await_void_task("OpaqueRef:task") is None, why


def test_await_task_still_demands_a_reference(client):
    """Keeping two helpers is only worth anything if the strict one stayed strict.

    Should this go green, await_task began handing None to callers that asked for a VM
    ref, and one_cycle would carry that None into the start call as its VM.
    """
    client.call = TaskCalls(result="")
    with pytest.raises(XapiError) as caught:
        client.await_task("OpaqueRef:task")
    assert caught.value.message == "TASK_RESULT_UNPARSEABLE"


def test_await_task_extracts_the_reference_from_the_result(client):
    client.call = TaskCalls(result="<value>OpaqueRef:41be2902-cc37-1371-f036-519b05a820c6</value>")
    assert client.await_task("OpaqueRef:t") == "OpaqueRef:41be2902-cc37-1371-f036-519b05a820c6"


def test_the_unparseable_raise_quotes_the_result_it_rejected(client):
    """The task is destroyed on the way out, so this raise is the last chance to say what
    settled. Without it a caller cannot tell a void success from a genuine surprise."""
    client.call = TaskCalls(result="<value>nonsense</value>")
    with pytest.raises(XapiError, match="nonsense"):
        client.await_task("OpaqueRef:task")


def test_a_settled_task_is_destroyed_on_both_paths(client):
    """One leaked task record per probe is how a long measuring run silts up the pool."""
    void = TaskCalls(result="")
    client.call = void
    client.await_void_task("OpaqueRef:task")
    assert "task.destroy" in void.methods, "the void path left its task behind"

    strict = TaskCalls(result="")
    client.call = strict
    with pytest.raises(XapiError):
        client.await_task("OpaqueRef:task")
    assert "task.destroy" in strict.methods, "the raising path left its task behind"


@pytest.mark.parametrize("status", ["failure", "cancelled"])
def test_a_task_that_did_not_succeed_raises_with_its_error_info(client, status):
    """Void tolerance must not reach a real failure: an empty result is success only when
    the status says so, and the error info is what names the cause."""
    client.call = TaskCalls(status=status, error_info=["VM_BAD_POWER_STATE"])
    with pytest.raises(XapiError) as caught:
        client.await_void_task("OpaqueRef:task")
    assert caught.value.message == f"TASK_{status.upper()}"
    assert caught.value.data == ["VM_BAD_POWER_STATE"]


def test_a_task_that_never_settles_times_out(client):
    """The deadline moved into _settle_task along with the rest of the loop."""
    calls = TaskCalls(status="pending")
    client.call = calls
    with pytest.raises(XapiError) as caught:
        client.await_void_task("OpaqueRef:task", poll=0, timeout=-1)
    assert caught.value.message == "TASK_TIMEOUT"
    assert "task.destroy" in calls.methods, "a timed-out task was left on the pool"


# -- destroy_with_disks -----------------------------------------------------

def test_teardown_attempts_every_vdi_and_reports_only_the_dead(client, monkeypatch):
    """One stuck disk must not orphan the disks behind it, and must not be counted as gone.

    The reviewer's suggested fix swallowed the failure and still returned every VDI, which
    would have made the tool report a clean teardown over disks still on the SR.
    """
    attempted = []
    monkeypatch.setattr(Xapi, "disk_vdis", lambda self, vm: ["vdi-a", "vdi-b", "vdi-c"])

    def fake_call(method, *params):
        if method == "VM.get_power_state":
            return "Running"
        if method in ("VM.hard_shutdown", "VM.destroy"):
            return None
        if method == "VDI.destroy":
            attempted.append(params[0])
            if params[0] == "vdi-b":
                raise XapiError("VDI_IN_USE", params[0])
            return None
        raise AssertionError(method)

    client.call = fake_call
    destroyed = client.destroy_with_disks("OpaqueRef:vm")

    assert attempted == ["vdi-a", "vdi-b", "vdi-c"], "gave up after the first failure"
    assert destroyed == ["vdi-a", "vdi-c"], "reported a disk as destroyed that survived"

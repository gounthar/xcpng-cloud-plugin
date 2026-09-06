"""Guards on the XO JSON-RPC client, whose transport has failure modes the others do not.

This is the only client in the toolbox speaking a persistent, stateful socket that the
server also pushes unprompted notifications down. The reply to a call is whichever frame
carries the matching id, never the next frame to arrive, and reading the next frame is a
bug that presents as a successful call with a nonsense result rather than as an error.
That is what most of this file is about.

It is also the backend #89 did not choose. These tests exist so the measured comparison
behind that decision rests on a client that was doing what it claimed, and so the losing
half of the comparison can be re-run later without re-deriving it.
"""

import json
import types

import pytest
import websocket

import xo as xo_module

from conftest import SECRET
from fakes import FakeWs
from xo import Xo, XoError

REPLY = {"jsonrpc": "2.0", "id": 1, "result": "mine"}
NOTIFICATION = {"jsonrpc": "2.0", "method": "all", "params": {"type": "update", "id": "vm-9"}}


# -- call(): matching a reply to its request --------------------------------

def test_a_notification_arriving_first_is_skipped_rather_than_returned(xo):
    """The server interleaves its own events with replies on the same socket.

    This is the one test here that must go red if call() is ever reduced to a single
    recv(): a notification has no id and no result, so a naive read returns None and the
    caller records a successful create that created nothing.
    """
    xo._ws = FakeWs([NOTIFICATION, REPLY])
    assert xo.call("vm.create") == "mine"


def test_another_caller_s_reply_is_skipped(xo):
    """Matching on "a frame with an id" is not enough on a shared socket."""
    xo._ws = FakeWs([{"jsonrpc": "2.0", "id": 99, "result": "someone else's"}, REPLY])
    assert xo.call("vm.create") == "mine"


def test_each_call_takes_a_fresh_id(xo):
    """Reusing an id makes the second call match the first call's reply, which is a stale
    result reported as a fresh one -- the same shape as every other staleness trap here."""
    ws = FakeWs([{"id": 1, "result": "a"}, {"id": 2, "result": "b"}])
    xo._ws = ws
    assert (xo.call("system.getServerVersion"), xo.call("system.listMethods")) == ("a", "b")
    assert [frame["id"] for frame in ws.sent] == [1, 2]


def test_the_request_envelope_is_well_formed(xo):
    ws = FakeWs([REPLY])
    xo._ws = ws
    xo.call("vm.create", {"template": "t"})
    assert ws.sent[0] == {
        "jsonrpc": "2.0", "id": 1, "method": "vm.create", "params": {"template": "t"},
    }


def test_no_params_is_sent_as_an_empty_object_not_as_null(xo):
    """xo-server rejects a null params. A method taking no arguments is the common case."""
    ws = FakeWs([REPLY])
    xo._ws = ws
    xo.call("system.listMethods")
    assert ws.sent[0]["params"] == {}


@pytest.mark.parametrize(
    "frame, why",
    [
        ("5", "a bare number is valid JSON and is not subscriptable"),
        ("null", "so is null"),
        ("true", "so is a bare bool"),
        ('[1, 2]', "a list survives the membership test by accident, not by design"),
        ('"hi"', "so does a string"),
        ("<html>nope</html>", "a proxy answering instead of xo-server; not JSON at all"),
        ("", "an empty frame"),
    ],
)
def test_a_frame_that_is_not_an_object_is_skipped_rather_than_crashed_on(xo, frame, why):
    """Same trap as the XAPI side: `msg.get("id")` on a bare scalar raises AttributeError,
    and here it would kill a call that the very next frame was about to answer."""
    xo._ws = FakeWs([frame, REPLY])
    assert xo.call("vm.create") == "mine", why


# -- call(): errors ---------------------------------------------------------

def test_an_error_object_raises_with_its_message_and_data(xo):
    xo._ws = FakeWs([{"id": 1, "error": {"message": "VM_BAD_POWER_STATE", "data": ["halted"]}}])
    with pytest.raises(XoError) as caught:
        xo.call("vm.start")
    assert (caught.value.message, caught.value.data) == ("VM_BAD_POWER_STATE", ["halted"])


def test_an_error_that_is_not_an_object_does_not_raise_attribute_error(xo):
    """`{"error": "boom"}` would reach err.get() and die there instead of raising XoError,
    so a caller catching XoError would not catch it. Same trap as the XAPI side."""
    xo._ws = FakeWs([{"id": 1, "error": "boom"}])
    with pytest.raises(XoError, match="boom"):
        xo.call("vm.start")


def test_an_error_object_with_no_message_still_raises(xo):
    xo._ws = FakeWs([{"id": 1, "error": {"data": "x"}}])
    with pytest.raises(XoError) as caught:
        xo.call("vm.start")
    assert caught.value.message == "UNKNOWN"


def test_a_reply_with_neither_result_nor_error_returns_none_rather_than_hanging(xo):
    """A void verb answers `{"id": n}`. Treating that as unmatched would spin to the
    deadline and report a timeout for a call that had already succeeded."""
    xo._ws = FakeWs([{"jsonrpc": "2.0", "id": 1}])
    assert xo.call("vm.stop") is None


# -- call(): the deadline, which is entirely ours ---------------------------

def test_a_reply_that_never_comes_times_out(xo):
    """There is no server-side timeout on this transport and no task-polling API to fall
    back on: api/task.mjs is cancel and destroy, nothing else. A call that can spin
    forever is a probe that hangs on a lab pool with a VM already created."""
    xo._ws = FakeWs([])
    with pytest.raises(XoError) as caught:
        xo.call("vm.create")
    assert caught.value.message == "TIMEOUT"


def test_a_socket_timeout_mid_wait_is_a_timeout_and_not_a_traceback(xo):
    xo._ws = FakeWs([NOTIFICATION, websocket.WebSocketTimeoutException("read timed out")])
    with pytest.raises(XoError) as caught:
        xo.call("vm.create")
    assert caught.value.message == "TIMEOUT"


def test_an_expired_deadline_stops_before_reading_the_socket_again(xo):
    ws = FakeWs([REPLY])
    xo._ws = ws
    with pytest.raises(XoError) as caught:
        xo.call("vm.create", timeout=-1)
    assert caught.value.message == "TIMEOUT"
    assert ws.timeouts == [], "it read the socket after its own deadline had passed"


def test_the_socket_deadline_shrinks_across_skipped_frames(xo, monkeypatch):
    """settimeout gets what is left of the budget, not the whole of it. Handing it the
    full timeout each time round the loop lets a chatty server keep one call alive
    indefinitely, which is a hang that looks like a slow pool.

    The clock is faked rather than read, and that is the point of the test rather than a
    convenience. Asserting the budgets merely descend passes against a client handing out
    the same number three times, since a constant sequence is its own reverse sort -- which
    is what the first version of this test did, and the mutation walked straight through it.
    Exact expected values are the only form that cannot be satisfied by a constant.
    """
    ticks = iter([0.0, 0.0, 1.0, 2.0])
    monkeypatch.setattr(xo_module, "time", types.SimpleNamespace(monotonic=lambda: next(ticks)))
    xo.timeout = 10.0
    ws = FakeWs([NOTIFICATION, NOTIFICATION, REPLY])
    xo._ws = ws
    xo.call("vm.create")
    assert ws.timeouts == [10.0, 9.0, 8.0]


def test_calling_before_connect_says_which_step_is_missing(xo):
    with pytest.raises(XoError, match="connect"):
        xo.call("system.getServerVersion")


# -- the verbs the plugin would need ----------------------------------------

def test_resolve_template_filters_on_an_exact_name(xo):
    """Clones are named after the template they came from, so a prefix match would find
    the template's own offspring. The XAPI backend learned this the same way."""
    ws = FakeWs([{"id": 1, "result": {}}])
    xo._ws = ws
    xo.resolve_template("jenkins-agent-debian13-v7")
    assert ws.sent[0]["params"]["filter"] == {
        "type": "VM-template", "name_label": "jenkins-agent-debian13-v7",
    }


@pytest.mark.parametrize(
    "result, why",
    [
        ({"a": {"uuid": "1"}, "b": {"uuid": "2"}}, "getAllObjects answers a map keyed by id"),
        ([{"uuid": "1"}, {"uuid": "2"}], "and a list when a filter narrows to a collection"),
    ],
)
def test_resolve_template_normalises_both_reply_shapes(xo, result, why):
    xo._ws = FakeWs([{"id": 1, "result": result}])
    assert len(xo.resolve_template("t")) == 2, why


@pytest.mark.parametrize("result", [{}, [], None])
def test_resolve_template_answers_empty_rather_than_raising(xo, result):
    """A template that is not there is an ordinary answer, and callers count the list."""
    xo._ws = FakeWs([{"id": 1, "result": result}])
    assert xo.resolve_template("nope") == []


def test_create_from_template_asks_for_a_clone_and_not_a_copy(xo):
    """clone=True reaches _cloneVm, which is VM.clone and copy-on-write. clone=False goes
    to _copyVm and full-copies every VDI: 0.56s against 49.59s measured on this lab's ext
    SR. A flipped default is not an error anywhere, only two orders of magnitude slower."""
    ws = FakeWs([{"id": 1, "result": {"id": "vm-1"}}])
    xo._ws = ws
    xo.create_from_template("tmpl", "agent-1")
    assert ws.sent[0]["params"] == {"template": "tmpl", "name_label": "agent-1", "clone": True}


def test_a_null_xenstore_value_is_sent_as_json_null_rather_than_dropped(xo):
    """None reaches VM.remove_from_xenstore_data, which is #28's per-key scrub. An omitted
    key is a silent no-op that answers success all the same, so this asserts on the
    serialised frame: in Python "key present, value None" and "key dropped by the encoder"
    are only distinguishable after the encoding."""
    ws = FakeWs([{"id": 1, "result": True}])
    xo._ws = ws
    xo.set_xenstore("vm-1", {"vm-data/jenkins/secret": None})
    assert "null" in json.dumps(ws.sent[0]["params"]["xenStoreData"])
    assert ws.sent[0]["params"]["xenStoreData"] == {"vm-data/jenkins/secret": None}


def test_delete_asks_for_the_disks_by_default(xo):
    """VM.destroy leaves the disks behind and removes the VBDs on the way out, so a
    teardown that forgets this orphans a VDI that no sweep can reach through its VM."""
    ws = FakeWs([{"id": 1, "result": True}])
    xo._ws = ws
    xo.delete_vm("vm-1")
    assert ws.sent[0]["params"] == {"id": "vm-1", "deleteDisks": True}


def test_tags_are_added_and_removed_through_the_tag_verbs(xo):
    ws = FakeWs([{"id": 1, "result": True}, {"id": 2, "result": True}])
    xo._ws = ws
    xo.add_tag("vm-1", "xcpng-cloud:lab")
    xo.remove_tag("vm-1", "xcpng-cloud:lab")
    assert [frame["method"] for frame in ws.sent] == ["tag.add", "tag.remove"]


# -- connection lifecycle ---------------------------------------------------

def test_connect_signs_in_with_the_token_and_records_the_user(xo, monkeypatch):
    ws = FakeWs([{"id": 1, "result": {"email": "lab@invalid"}}])
    monkeypatch.setattr(websocket, "create_connection", lambda *a, **k: ws)
    assert xo.connect() == {"email": "lab@invalid"}
    assert xo.user == {"email": "lab@invalid"}
    assert ws.sent[0]["method"] == "session.signInWithToken"
    assert ws.sent[0]["params"] == {"token": SECRET}


def test_close_is_safe_to_call_twice(xo):
    """__exit__ runs it, and a caller that closed early would otherwise hit None.close()."""
    ws = FakeWs()
    xo._ws = ws
    xo.close()
    xo.close()
    assert ws.closed is True and xo._ws is None


def test_close_drops_the_socket_even_when_closing_it_raises(xo):
    """A socket the peer already dropped raises on close. Keeping the dead handle means
    the next call sends into it instead of saying NOT_CONNECTED."""
    class Angry(FakeWs):
        def close(self):
            raise OSError("already gone")

    xo._ws = Angry()
    with pytest.raises(OSError):
        xo.close()
    assert xo._ws is None


# -- credentials ------------------------------------------------------------

def test_missing_env_var_is_named(monkeypatch):
    monkeypatch.delenv("XO_URL", raising=False)
    monkeypatch.setenv("XO_TOKEN", SECRET)
    with pytest.raises(XoError, match="XO_URL is not set"):
        Xo()


def test_the_token_is_not_echoed_when_another_variable_is_missing(monkeypatch):
    """XO_URL is the one deleted, not XO_TOKEN: the failure has to happen while the token
    is genuinely in the environment, or the assertion below is vacuously true."""
    monkeypatch.delenv("XO_URL", raising=False)
    monkeypatch.setenv("XO_TOKEN", SECRET)
    with pytest.raises(XoError) as caught:
        Xo()
    assert SECRET not in str(caught.value)


def test_tls_verification_is_on_by_default(monkeypatch, capsys):
    monkeypatch.setenv("XO_URL", "wss://xo.invalid/api/")
    monkeypatch.setenv("XO_TOKEN", SECRET)
    monkeypatch.delenv("XO_TRUST_SELF_SIGNED", raising=False)
    assert Xo()._trust_self_signed is False
    assert capsys.readouterr().err == "", "a verified connection must not print a warning"


@pytest.mark.parametrize("value", ["1", "true", "TRUE", "yes"])
def test_disabling_verification_says_so_on_stderr(monkeypatch, capsys, value):
    monkeypatch.setenv("XO_URL", "wss://xo.invalid/api/")
    monkeypatch.setenv("XO_TOKEN", SECRET)
    monkeypatch.setenv("XO_TRUST_SELF_SIGNED", value)
    client = Xo()
    err = capsys.readouterr().err
    assert client._trust_self_signed is True
    assert "XO_TRUST_SELF_SIGNED" in err
    assert SECRET not in err


@pytest.mark.parametrize("value", ["0", "no", "", "off"])
def test_anything_that_is_not_an_affirmative_leaves_verification_on(monkeypatch, value):
    """A typo must fail closed. Plain truthiness would make XO_TRUST_SELF_SIGNED=0 disable
    verification, which reads as the opposite of what it does."""
    monkeypatch.setenv("XO_URL", "wss://xo.invalid/api/")
    monkeypatch.setenv("XO_TOKEN", SECRET)
    monkeypatch.setenv("XO_TRUST_SELF_SIGNED", value)
    assert Xo()._trust_self_signed is False


def test_connect_passes_the_insecure_ssl_options_only_when_asked(xo, monkeypatch):
    """The flag has to reach create_connection. Warning about disabled verification while
    still verifying is the harmless direction; the reverse is the one that matters, and
    only an assertion on what was passed separates them."""
    seen = {}
    monkeypatch.setattr(websocket, "create_connection",
                        lambda url, **kw: seen.update(kw) or FakeWs([{"id": 1, "result": {}}]))
    xo.connect()
    assert seen["sslopt"] is None

    xo2 = Xo.__new__(Xo)
    xo2.__dict__.update(xo.__dict__, _ws=None, _id=0, _trust_self_signed=True)
    xo2.connect()
    assert seen["sslopt"]["check_hostname"] is False

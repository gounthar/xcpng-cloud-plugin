"""Guards on the XO REST client, and on the two traps it encodes as raises rather than prose.

This client exists because a route was twice declared absent before it was found: VM
creation hangs off /pools rather than /vms, and it wants a bare template uuid. Both facts
live inside create_vm as guards, which is what makes them testable, and this is where they
are tested.

The negative cases here are worthless without the positive one beside them. Three probes
that each correctly refused something is exactly how the route came to be called missing,
so `a_bare_uuid_is_accepted_and_sent_verbatim` is not a nicety: it is the control proving
create_vm can say yes at all, and every refusal below only means something because of it.
"""

import http.client
import json
import ssl
import urllib.error

import pytest

from conftest import SECRET
from xo_rest import XoRest, XoRestError

POOL = "23ac115e-5f9a-0d96-c3b2-fb92f87fd1ec"
BARE = "5436f445-a341-cc10-7312-e1077b0c4e69"
PREFIXED = f"{POOL}/{BARE}"
CREATED = json.dumps({"id": "vm-1"}).encode()


def body_of(req):
    return json.loads(req.data)


# -- create_vm: the bare-uuid guard -----------------------------------------

def test_a_pool_prefixed_template_id_is_refused_before_anything_is_sent(rest, capture):
    """The prefixed form is what XO hands you nearly everywhere else, including
    GET /vm-templates, and this route answers it with 404 "no such object". That reads
    exactly like the route not existing, which is the reading that cost this project two
    wrong findings. Refusing it locally turns a misleading 404 into a message.

    The assertion on `sent` is the half that matters: a guard moved below the request
    would still raise, and would still have burned the round trip that misleads.
    """
    sent = capture(CREATED, status=201)
    with pytest.raises(XoRestError) as caught:
        rest.create_vm(POOL, PREFIXED, "agent-1")
    assert caught.value.message == "PREFIXED_TEMPLATE_ID"
    assert sent == [], "the prefixed id reached the pool, which is the 404 this guard exists to avoid"


def test_the_refusal_says_what_to_do_instead(rest, capture):
    """A message naming only the symptom sends the reader back to the 404."""
    capture(CREATED, status=201)
    with pytest.raises(XoRestError, match="bare uuid"):
        rest.create_vm(POOL, PREFIXED, "agent-1")


@pytest.mark.parametrize(
    "template_id, why",
    [
        ("", "empty: the shape a missing lookup returns"),
        ("5436f445", "a truncated uuid"),
        (BARE + "-extra", "longer than a uuid"),
        ("OpaqueRef:5436f445-a341-cc10-7312-e1077b0c4e69", "a XAPI ref, from mixing the two clients"),
    ],
)
def test_an_id_that_is_not_a_bare_uuid_is_refused(rest, capture, template_id, why):
    sent = capture(CREATED, status=201)
    with pytest.raises(XoRestError) as caught:
        rest.create_vm(POOL, template_id, "agent-1")
    assert caught.value.message in ("ODD_TEMPLATE_ID", "PREFIXED_TEMPLATE_ID"), why
    assert sent == [], why


def test_a_bare_uuid_is_accepted_and_sent_verbatim(rest, capture):
    """The control. Without it every refusal above is satisfied by a create_vm that
    refuses everything, which is a guard that has stopped being a guard."""
    sent = capture(CREATED, status=201)
    vm_id, elapsed = rest.create_vm(POOL, BARE, "agent-1")

    assert vm_id == "vm-1"
    assert elapsed >= 0
    assert len(sent) == 1
    assert sent[0].get_method() == "POST"
    assert sent[0].full_url == (
        f"https://xo.invalid/rest/v0/pools/{POOL}/actions/create_vm?sync=true")
    assert body_of(sent[0])["template"] == BARE


# -- create_vm: the body ----------------------------------------------------

def test_the_request_asks_for_a_clone_and_not_a_copy(rest, capture):
    """clone=True is what buys copy-on-write. clone=False full-copies every VDI, which on
    this lab's file-based ext SR measured 49.59s against 0.56s. A default that flipped
    would show up as a provisioning path an order of magnitude slower, and nowhere else."""
    sent = capture(CREATED, status=201)
    rest.create_vm(POOL, BARE, "agent-1")
    assert body_of(sent[0])["clone"] is True


def test_the_vm_is_not_started_by_default(rest, capture):
    """The plugin seeds xenstore between creating a clone and booting it. A create that
    booted would race the seed, and the agent would come up without its secret."""
    sent = capture(CREATED, status=201)
    rest.create_vm(POOL, BARE, "agent-1")
    assert body_of(sent[0])["boot"] is False


def test_extra_fields_reach_the_body(rest, capture):
    """cloud_config is the one that matters: this route takes it directly, which is half
    of why REST won #89. If extras were dropped the seed would silently not happen."""
    sent = capture(CREATED, status=201)
    rest.create_vm(POOL, BARE, "agent-1", cloud_config="#cloud-config\n", memory=2147483648)
    body = body_of(sent[0])
    assert body["cloud_config"] == "#cloud-config\n"
    assert body["memory"] == 2147483648


# -- create_vm: what counts as success --------------------------------------

@pytest.mark.parametrize(
    "body, status, why",
    [
        (CREATED, 200, "a 200 is not a create; this route answers 201"),
        (CREATED, 202, "202 is the async form, which returns a task rather than a VM"),
        (b'{"id": ""}', 201, "201 with an empty id: the status alone would go green"),
        (b"{}", 201, "201 with no id at all"),
        (b"null", 201, "a bare null body"),
    ],
)
def test_anything_but_a_201_carrying_an_id_is_a_failure(rest, capture, body, status, why):
    capture(body, status=status)
    with pytest.raises(XoRestError) as caught:
        rest.create_vm(POOL, BARE, "agent-1")
    assert caught.value.message == "CREATE_FAILED", why
    assert caught.value.status == status, "the raise must carry the status it rejected"


# -- transport --------------------------------------------------------------

@pytest.mark.parametrize(
    "exc, why",
    [
        (urllib.error.URLError(ssl.SSLCertVerificationError("self-signed")), "connect: bad cert"),
        (TimeoutError(), "read: the body stalled, and TimeoutError is not a URLError"),
        (ConnectionResetError("reset by peer"), "read: peer dropped, not a URLError"),
        (http.client.IncompleteRead(b"half"), "read: truncated, an HTTPException not an OSError"),
    ],
)
def test_transport_failures_become_xo_rest_error(rest, raise_from_urlopen, exc, why):
    """Every caller of this client handles XoRestError and nothing else, so anything that
    escapes as a raw exception surfaces as a traceback halfway through a probe that has
    already created a VM on the pool."""
    raise_from_urlopen(exc)
    with pytest.raises(XoRestError) as caught:
        rest.get("/rest/v0/vms")
    assert caught.value.message == "TRANSPORT", why


def test_an_http_error_carries_its_status_and_payload(rest, monkeypatch):
    """404 "no such object" is the bare-uuid trap's signature. Losing the body loses the
    only thing that separates it from a route that genuinely is not there."""
    import urllib.request

    payload = json.dumps({"error": "no such object", "data": {"id": PREFIXED}}).encode()

    def boom(*args, **kwargs):
        raise urllib.error.HTTPError("https://xo.invalid", 404, "Not Found", {}, None)

    monkeypatch.setattr(urllib.request, "urlopen", boom)
    monkeypatch.setattr(urllib.error.HTTPError, "read", lambda self: payload, raising=False)

    with pytest.raises(XoRestError) as caught:
        rest.get("/rest/v0/vms/x")
    assert caught.value.status == 404
    assert caught.value.message == "no such object"
    assert caught.value.data == {"id": PREFIXED}


@pytest.mark.parametrize(
    "payload, why",
    [
        (b"<html>Gateway Timeout</html>", "a proxy answering instead of XO, so not JSON at all"),
        (b"5", "a bare number: valid JSON, and payload.get() would raise AttributeError"),
        (b"null", "so is null"),
        (b'["a", "b"]', "a list survives a membership test by accident, not by design"),
    ],
)
def test_an_http_error_with_an_unexpected_body_still_raises_cleanly(rest, monkeypatch, payload, why):
    import urllib.request

    def boom(*args, **kwargs):
        raise urllib.error.HTTPError("https://xo.invalid", 502, "Bad Gateway", {}, None)

    monkeypatch.setattr(urllib.request, "urlopen", boom)
    monkeypatch.setattr(urllib.error.HTTPError, "read", lambda self: payload, raising=False)

    with pytest.raises(XoRestError) as caught:
        rest.get("/rest/v0/vms")
    assert caught.value.status == 502, why


@pytest.mark.parametrize(
    "body, expected, why",
    [
        (b"", None, "204 and friends have no body; json.loads('') would raise"),
        (b"not json", "not json", "a non-JSON 200 comes back as text rather than exploding"),
    ],
)
def test_an_empty_or_unparseable_success_body_does_not_raise(rest, capture, body, expected, why):
    capture(body, status=200)
    assert rest.get("/rest/v0/vms") == expected, why


# -- the per-key xenstore write, which is #28's scrub -----------------------

def test_a_null_value_is_serialised_as_json_null_rather_than_dropped(rest, capture):
    """XO turns a null into VM.remove_from_xenstore_data, which is the per-key delete the
    #28 scrub needs. An *omitted* key is a silent no-op that answers 200 all the same, so
    the assertion has to be on the serialised body: a dict comparison in Python cannot
    tell "key present, value None" from a key the encoder dropped on the way out."""
    sent = capture(b"{}", status=200)
    rest.set_xenstore("vm-1", {"vm-data/jenkins/secret": None})
    raw = sent[0].data.decode()
    assert '"vm-data/jenkins/secret": null' in raw or '"vm-data/jenkins/secret":null' in raw
    assert body_of(sent[0]) == {"xenStoreData": {"vm-data/jenkins/secret": None}}


def test_the_xenstore_write_is_a_patch_on_the_vm(rest, capture):
    sent = capture(b"{}", status=200)
    rest.set_xenstore("vm-1", {"vm-data/jenkins/name": "agent-1"})
    assert sent[0].get_method() == "PATCH"
    assert sent[0].full_url == "https://xo.invalid/rest/v0/vms/vm-1"


# -- tags, which are the owner marker on this backend -----------------------

def test_the_owner_marker_is_a_tag_route(rest, capture):
    """XoVm carries no other_config, so the xcpng-cloud marker the XAPI backend writes has
    to become a tag here. A clone leaked by one backend is invisible to a sweep written
    for the other, which is why the route is pinned rather than assumed."""
    sent = capture(b"{}", status=200)
    rest.add_tag("vm-1", "xcpng-cloud")
    assert sent[0].get_method() == "PUT"
    assert sent[0].full_url == "https://xo.invalid/rest/v0/vms/vm-1/tags/xcpng-cloud"

    rest.remove_tag("vm-1", "xcpng-cloud")
    assert sent[1].get_method() == "DELETE"


@pytest.mark.parametrize(
    "tag, encoded, why",
    [
        ("xcpng-cloud/lab", "xcpng-cloud%2Flab", "a slash addresses a different route entirely"),
        ("xcpng-cloud lab", "xcpng-cloud%20lab", "a space is not legal in a URL at all"),
        ("xcpng-cloud?x=1", "xcpng-cloud%3Fx%3D1", "a question mark turns the rest into a query"),
        ("xcpng-cloud#lab", "xcpng-cloud%23lab", "a hash truncates the path at the fragment"),
        ("xcpng-cloud:lab", "xcpng-cloud%3Alab", "a colon is legal in a segment, and encoding it is harmless"),
    ],
)
def test_a_tag_is_encoded_as_one_path_segment(rest, capture, tag, encoded, why):
    """The tag is the owner marker, and on the plugin side it will carry an operator-
    supplied cloud name. Interpolated raw, a slash makes the request address a different
    route, which answers something rather than erroring, so a sweep later finds no tag on
    a VM the tool believes it tagged.

    Asserting the full URL rather than that the raw character is absent: a check for
    "no slash present" passes against a tag that was silently dropped.
    """
    sent = capture(b"{}", status=200)
    rest.add_tag("vm-1", tag)
    assert sent[0].full_url == f"https://xo.invalid/rest/v0/vms/vm-1/tags/{encoded}", why

    rest.remove_tag("vm-1", tag)
    assert sent[1].full_url == f"https://xo.invalid/rest/v0/vms/vm-1/tags/{encoded}", why


def test_a_vm_id_is_left_alone(rest, capture):
    """The line is operator input versus server output. vm_id comes from XO and
    /rest/v0/vms/{id} is one segment, so encoding it is a no-op on every id seen here;
    encoding everything reflexively would hide where the untrusted input actually is."""
    sent = capture(b"{}", status=200)
    rest.add_tag("5436f445-a341-cc10-7312-e1077b0c4e69", "t")
    assert "5436f445-a341-cc10-7312-e1077b0c4e69/tags/t" in sent[0].full_url


# -- teardown ---------------------------------------------------------------

def test_delete_is_a_plain_delete_on_the_vm(rest, capture):
    """This route has no deleteDisks parameter and no async form: disks always go, and
    there is no task to wait on. Teardown is the plugin's longest call and the one with
    no escape hatch, so the shape it sends is worth pinning."""
    sent = capture(b"", status=200)
    status, elapsed = rest.delete_vm("vm-1")
    assert (status, sent[0].get_method()) == (200, "DELETE")
    assert sent[0].full_url == "https://xo.invalid/rest/v0/vms/vm-1"
    assert sent[0].data is None
    assert elapsed >= 0


# -- credentials ------------------------------------------------------------

def test_the_token_travels_in_a_cookie_and_never_in_the_url(rest, capture):
    """A token in a query string lands in every proxy and appliance access log it passes."""
    sent = capture(CREATED, status=201)
    rest.create_vm(POOL, BARE, "agent-1")
    assert sent[0].get_header("Cookie") == f"authenticationToken={SECRET}"
    assert SECRET not in sent[0].full_url


def test_a_body_is_declared_as_json_and_a_bodyless_request_declares_nothing(rest, capture):
    sent = capture(b"{}", status=200)
    rest.set_xenstore("vm-1", {"k": "v"})
    rest.get("/rest/v0/vms")
    assert sent[0].get_header("Content-type") == "application/json"
    assert sent[1].get_header("Content-type") is None


def test_missing_env_var_is_named(monkeypatch):
    monkeypatch.delenv("XO_BASE", raising=False)
    monkeypatch.setenv("XO_TOKEN", SECRET)
    with pytest.raises(XoRestError, match="XO_BASE is not set"):
        XoRest()


def test_the_token_is_not_echoed_when_another_variable_is_missing(monkeypatch):
    """XO_BASE is the one deleted, not XO_TOKEN: the failure has to happen while the token
    is genuinely in the environment, or this passes vacuously and would survive a handler
    that printed os.environ wholesale."""
    monkeypatch.delenv("XO_BASE", raising=False)
    monkeypatch.setenv("XO_TOKEN", SECRET)
    with pytest.raises(XoRestError) as caught:
        XoRest()
    assert SECRET not in str(caught.value)


def test_tls_verification_is_on_by_default(monkeypatch):
    """Off by default, opt-in, and the same posture as the plugin's trustSelfSigned box."""
    monkeypatch.setenv("XO_BASE", "https://xo.invalid")
    monkeypatch.setenv("XO_TOKEN", SECRET)
    monkeypatch.delenv("XO_TRUST_SELF_SIGNED", raising=False)
    client = XoRest()
    assert client._ctx.verify_mode == ssl.CERT_REQUIRED
    assert client._ctx.check_hostname is True


@pytest.mark.parametrize("value", ["1", "true", "TRUE", "yes"])
def test_disabling_verification_says_so_on_stderr(monkeypatch, capsys, value):
    """Silence would make an insecure lab default indistinguishable from a verified one."""
    monkeypatch.setenv("XO_BASE", "https://xo.invalid")
    monkeypatch.setenv("XO_TOKEN", SECRET)
    monkeypatch.setenv("XO_TRUST_SELF_SIGNED", value)
    client = XoRest()
    assert client._ctx.verify_mode == ssl.CERT_NONE
    err = capsys.readouterr().err
    assert "XO_TRUST_SELF_SIGNED" in err
    assert SECRET not in err


@pytest.mark.parametrize("value", ["0", "no", "", "off"])
def test_anything_that_is_not_an_affirmative_leaves_verification_on(monkeypatch, value):
    """A typo in the variable must fail closed. Truthiness on a non-empty string would
    make XO_TRUST_SELF_SIGNED=0 disable verification, which reads as the opposite."""
    monkeypatch.setenv("XO_BASE", "https://xo.invalid")
    monkeypatch.setenv("XO_TOKEN", SECRET)
    monkeypatch.setenv("XO_TRUST_SELF_SIGNED", value)
    assert XoRest()._ctx.verify_mode == ssl.CERT_REQUIRED

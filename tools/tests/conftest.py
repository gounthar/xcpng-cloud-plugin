"""Fixtures for the tools tests.

Every test here runs against a fake XAPI. Nothing touches a hypervisor, nothing needs
credentials, and the whole suite finishes in well under a second. That is the point: these
guard control flow, not the XAPI contract. The contract is checked by running the tools
against the lab pool by hand, which is what they exist for.

The fakes live in fakes.py. Both this directory and tools/ go on sys.path so the tests
import cleanly under either pytest import mode.
"""

import pathlib
import sys

import pytest

_HERE = pathlib.Path(__file__).resolve().parent
for _path in (_HERE.parent, _HERE):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

from fakes import FakeResponse  # noqa: E402
from xapi import Xapi  # noqa: E402
from xo import Xo  # noqa: E402
from xo_rest import XoRest  # noqa: E402


@pytest.fixture
def client():
    """An Xapi with its __init__ skipped, so no environment and no network."""
    x = Xapi.__new__(Xapi)
    x.host = "pool.invalid"
    x.session = "OpaqueRef:session"
    x._id = 0
    x._ctx = None
    return x


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


# The token these two carry is deliberately conspicuous. Several tests assert it does not
# turn up in an error message or a URL, and a bland value would make that hard to read.
SECRET = "sekrit-token-value"


@pytest.fixture
def rest():
    """An XoRest with its __init__ skipped, so no environment and no network."""
    r = XoRest.__new__(XoRest)
    r.base = "https://xo.invalid"
    r._token = SECRET
    r.timeout = 5
    r._ctx = None
    return r


@pytest.fixture
def xo():
    """An Xo with its __init__ skipped and no socket. Tests attach a FakeWs to `_ws`."""
    x = Xo.__new__(Xo)
    x.url = "wss://xo.invalid/api/"
    x._token = SECRET
    x.timeout = 5
    x._ws = None
    x._id = 0
    x.user = None
    x._trust_self_signed = False
    return x


@pytest.fixture
def capture(monkeypatch):
    """Answer the next urlopen with a canned response, recording the Request it was given.

    Returns the list the requests land in, so a test can assert on the URL, the method,
    the headers and the serialised body -- not only on what the client returned. Several
    of the REST guards are about what goes out rather than what comes back, and those are
    invisible to a fixture that only fakes the reply.
    """
    import urllib.request

    sent = []

    def _capture(body=b"{}", status=200):
        def fake_urlopen(req, *args, **kwargs):
            sent.append(req)
            return FakeResponse(body, status=status)

        monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
        return sent

    return _capture

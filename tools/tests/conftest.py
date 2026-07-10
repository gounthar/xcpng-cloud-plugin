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

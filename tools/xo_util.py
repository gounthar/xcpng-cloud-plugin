"""The helpers both XO harnesses need, in one place rather than two.

`as_list` was already copy-pasted into `xo_probe.py` and `xo_compare.py`, identically.
`poll` was not: `xo_compare` had it and `xo_probe` read once, so the probe reported a
successful xenstore write as a failed one. Copying `poll` across would have fixed that
instance and left the next one, since the defect was never the missing call. It was the
rule having two homes and only one of them being kept up to date.

`env_flag` and `transport_refusal` are here for the same reason rather than because the
harnesses share them: both XO clients have to answer "is this an affirmative?" and "may
the token go down this URL?", and two copies of a security decision drift exactly the way
`poll` did. The clients raise their own error types on the answer, so this module decides
and they refuse -- it has no exception of its own to force on either.

Deliberately free of imports beyond the standard library, so a REST-only caller does not
drag in websocket-client to get a sleep loop.
"""

import os
import sys
import time
import urllib.parse

# Each client speaks exactly one of these, and the cleartext twin of it is the only other
# thing it can be handed. Keyed by the secure scheme, because that is what a caller names.
CLEARTEXT_TWIN = {"https": "http", "wss": "ws"}
ALLOW_CLEARTEXT = "XO_ALLOW_CLEARTEXT"


def env_flag(name, default=False):
    """Is this environment variable set to an affirmative?

    Plain truthiness is the trap: a non-empty string is true, so `XO_TRUST_SELF_SIGNED=0`
    would disable TLS verification, which reads as the opposite of what it does. An
    unrecognised value fails closed, which is why a typo in the variable name or its value
    leaves the secure behaviour in place rather than the convenient one.
    """
    value = os.environ.get(name)
    if value is None:
        return default
    return value.lower() in ("1", "true", "yes")


def transport_refusal(url, secure, allow=None, stream=None):
    """Return a reason this URL must not carry the XO token, or None when it may.

    `secure` is the one scheme the calling client speaks, "https" for the REST client and
    "wss" for the WebSocket one. It is required rather than defaulted: a shared default of
    "https" would silently wave `https://` past the WebSocket client and `wss://` past the
    REST one, neither of which can use the other's scheme, and the failure would land
    somewhere unrelated -- `urlopen` raising "unknown url type", or create_connection
    reaching for a socket it will not get.

    The token is a bearer credential: it goes out on every request, in a Cookie header,
    and anyone who reads one off the wire has the appliance. `https://` and `wss://` are
    the only two addresses that protect it, and nothing in either client checked.

    Cleartext is refused by default and opt-in through XO_ALLOW_CLEARTEXT, matching the
    shape XO_TRUST_SELF_SIGNED already set rather than inventing a second posture. A hard
    refusal was the other candidate and is rejected deliberately: an appliance served over
    plain http on a lab segment is a thing someone may have, and a tool that cannot be
    told "yes, I mean it" gets worked around by editing the source, which removes the
    warning too. Opting in prints one, every time, naming the variable that did it.

    Anything that is neither `secure` nor its cleartext twin is refused with no opt-in,
    because this client cannot speak it whatever the operator meant. The case worth naming
    is the empty scheme: `XO_BASE=192.168.1.5` reaches urlopen as ValueError("unknown url
    type"), which is neither an OSError nor an HTTPException, so it escapes the transport
    guard as a raw traceback through every caller that handles only XoRestError.
    """
    if secure not in CLEARTEXT_TWIN:
        raise ValueError(f"{secure!r} is not a scheme either XO client speaks")
    cleartext = CLEARTEXT_TWIN[secure]
    scheme = urllib.parse.urlsplit(str(url)).scheme.lower()
    if scheme == secure:
        return None
    if scheme != cleartext:
        said = f"{scheme}:// is not a scheme this client speaks" if scheme else (
            "it names no scheme, and a bare host reaches urlopen as an unknown url type"
        )
        return f"{url} cannot carry the XO token: {said}. Use {secure}://."
    if allow is None:
        allow = env_flag(ALLOW_CLEARTEXT)
    if not allow:
        return (
            f"{url} would send the XO token in the clear, where anyone on the path can "
            f"read it and reuse it. Use {secure}://, or set {ALLOW_CLEARTEXT}=1 to send "
            f"it anyway."
        )
    print(
        f"warning: the XO token is going to {url} in the clear ({ALLOW_CLEARTEXT}). "
        f"Anyone on the path can read it.",
        file=stream if stream is not None else sys.stderr,
    )
    return None


def as_list(objs):
    """Normalise what XO answers a collection query with.

    `xo.getAllObjects` returns a map keyed by object id; a filtered REST collection
    returns a list; and either can answer null, which `list()` cannot take.
    """
    if isinstance(objs, dict):
        return list(objs.values())
    return list(objs or [])


def readable(objs):
    """The first object of a collection reply, or None when the reply held nothing.

    None rather than {} is the whole point, and it is the correction to the first version
    of this function. `as_list(...)[0]` inside a poll is an IndexError that stops the poll
    retrying, so the subscript has to be safe; but making it safe by answering {} is
    worse, because a *negative* predicate then passes on a failed read. `SEED_KEY not in
    {}` is true, so a scrub poll reported a successful delete while the VM was simply not
    readable yet. That is an absence seen by an instrument that could not detect presence,
    which is the one thing every check in these tools is written to avoid.

    So callers get None and must say what they mean: `d is not None and KEY not in d`
    keeps polling through an unreadable moment, where `KEY not in d` would call it done.
    """
    items = as_list(objs)
    return items[0] if items else None


def poll(read, want, timeout=20.0, interval=0.5):
    """Read until `want` is satisfied, or the budget runs out. Returns (satisfied, waited).

    XO's object cache lags a write. Measured on this pool: a xenstore key written through
    either backend is absent from an immediate read and present a second or so later. A
    single read-back therefore turns a successful write into an apparent failure, which is
    what it did the first time the comparison ran, on both backends at once, and it read
    as the API being broken rather than as the reader being early.

    Reports rather than raises, so a caller can say which check it could not judge. A
    spent budget buys no free read: that read would come from the stale cache anyway, and
    letting it through makes a timeout indistinguishable from a result.
    """
    started = time.monotonic()
    while True:
        remaining = timeout - (time.monotonic() - started)
        if remaining <= 0:
            return False, time.monotonic() - started
        if want(read()):
            return True, time.monotonic() - started
        # Never sleep past the deadline. poll(timeout=20, interval=60) used to take one
        # failed read and then wait a full minute, so the budget in the signature and the
        # time actually spent were different numbers, and the caller was told the first.
        time.sleep(min(interval, remaining))

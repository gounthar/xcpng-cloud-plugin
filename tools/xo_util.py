"""The two helpers both XO harnesses need, in one place rather than two.

`as_list` was already copy-pasted into `xo_probe.py` and `xo_compare.py`, identically.
`poll` was not: `xo_compare` had it and `xo_probe` read once, so the probe reported a
successful xenstore write as a failed one. Copying `poll` across would have fixed that
instance and left the next one, since the defect was never the missing call. It was the
rule having two homes and only one of them being kept up to date.

Deliberately free of imports beyond the standard library, so a REST-only caller does not
drag in websocket-client to get a sleep loop.
"""

import time


def as_list(objs):
    """Normalise what XO answers a collection query with.

    `xo.getAllObjects` returns a map keyed by object id; a filtered REST collection
    returns a list; and either can answer null, which `list()` cannot take.
    """
    if isinstance(objs, dict):
        return list(objs.values())
    return list(objs or [])


def first(objs, default=None):
    """The first object of a collection reply, or `default` when there is not one.

    Exists because `as_list(...)[0]` inside a poll's read function is not a read that
    fails, it is an IndexError that propagates out of the poll and stops it retrying. The
    cache lag this whole module is about makes a transient empty reply normal, so the
    subscript has to be the safe kind. Three sites had the unsafe form and the reviewer
    found the fourth, which is why it is a function rather than three careful edits.
    """
    items = as_list(objs)
    return items[0] if items else (default if default is not None else {})


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

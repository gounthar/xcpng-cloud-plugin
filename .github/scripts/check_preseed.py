#!/usr/bin/env python3
"""Syntax-check the shell embedded in a debian-installer preseed's late_command.

`d-i preseed/late_command string ...` is one logical line built from backslash continuations. In
this repo it spans ~25 of them and mixes three things: d-i's own shell, `in-target` (which chroots
into the installed system), and single-quoted `sh -c` bodies. Nothing validates it. `packer
validate` checks the HCL template's schema and never reads the preseed; the golden-image CI job runs
provision.sh with SKIP_GUEST_TOOLS=1, so it never reaches this code either.

A quoting slip here therefore ships green and shows up as a Packer build that prints

    Wait for VM's IP to become known to us

and then nothing, forever, which is indistinguishable from a slow boot. That is the signature of
every one of the five bugs in issue #107, and each one cost a VNC console session to find.

So: join the continuations, pull out each single-quoted `sh -c` body, and hand it to `sh -n`. That
parses without executing, so this is safe to run anywhere and touches no installer. It catches
unbalanced quotes, an `if` without its `fi`, and a `do` without its `done`. It cannot catch a
command that parses but does the wrong thing.
"""

import re
import subprocess
import sys
import tempfile
from pathlib import Path

# `sh -c '...'`, where the body is everything up to the next single quote. POSIX sh has no way to
# escape a single quote inside a single-quoted string, so the first one always closes it and this
# non-greedy match is exact rather than approximate.
SH_C_BODY = re.compile(r"sh -c '([^']*)'")

CONTINUATION = re.compile(r"\\\n\s*")


def check(path: Path) -> int:
    text = path.read_text()
    joined = CONTINUATION.sub(" ", text)

    lines = [
        ln for ln in joined.splitlines() if ln.startswith("d-i preseed/late_command")
    ]
    if not lines:
        print(f"{path}: no late_command found; nothing to check")
        return 0
    if len(lines) > 1:
        # d-i takes the last one silently, so two definitions means one is dead and the author
        # almost certainly does not know which.
        print(
            f"::error::{path}: {len(lines)} late_command definitions; only the last one runs"
        )
        return 1
    line = lines[0]

    # An odd count means a quote is unbalanced across the whole command, which the per-body check
    # below would misreport as a body that merely looks strange.
    if line.count("'") % 2 != 0:
        print(f"::error::{path}: unbalanced single quote in late_command")
        return 1

    bodies = SH_C_BODY.findall(line)
    if not bodies:
        print(
            f"::error::{path}: late_command contains no sh -c body; did the quoting change?"
        )
        return 1

    failed = 0
    for i, body in enumerate(bodies):
        with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False) as handle:
            handle.write(body + "\n")
            probe = handle.name
        result = subprocess.run(["sh", "-n", probe], capture_output=True, text=True)
        Path(probe).unlink()
        if result.returncode == 0:
            print(f"sh -c body {i}: ok ({len(body)} chars)")
        else:
            failed = 1
            print(
                f"::error::{path}: sh -c body {i} is not valid shell: {result.stderr.strip()}"
            )
            print(body)

    return failed


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <preseed.cfg>", file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(check(Path(sys.argv[1])))

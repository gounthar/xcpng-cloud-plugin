#!/usr/bin/env python3
"""Capture a VM's console framebuffer as a PNG, and optionally send it a keystroke.

`docs/golden-image-boot-bisect.md` already reached the conclusion this exists to serve, in July:
"We reasoned from four indirect proxies for a full day and never once looked at the console. One
VNC capture settled it in seconds. Look at the actual screen first. Step one, not step fifty."

That advice was re-learned the hard way in August while fixing the Packer build (#107), where five
separate failures all produced the same Packer log line and only the console distinguished them.
The step-one advice named `vncdo`, which is a dependency this repo does not otherwise carry, so
this is the same technique with nothing to install: a minimal RFB 3.8 client over the unix socket
qemu already exposes per domain on dom0.

    # find the domain, forward its socket, look at it
    xe vm-list name-label=<vm> params=dom-id --minimal
    ssh -L 5901:/var/run/xen/vnc-<domid> root@<host>
    python3 tools/vnc_console.py 127.0.0.1 5901 screen.png

    # answer a blocking installer dialog (Return)
    python3 tools/vnc_console.py 127.0.0.1 5901 --key 0xff0d

Authentication is None, which is why this stays short. Only raw encoding is requested, so the
server never has to agree to anything clever.
"""

import argparse
import socket
import struct
import sys
import time


def recv_exact(sock, n):
    """Read exactly n bytes, or raise. RFB is length-prefixed throughout, so short reads are bugs."""
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError(f"connection closed with {len(buf)}/{n} bytes read")
        buf += chunk
    return buf


def handshake(sock):
    """RFB 3.8 version + security + ClientInit. Returns (width, height)."""
    version = recv_exact(sock, 12)
    print(f"server version: {version.decode(errors='replace').strip()}", file=sys.stderr)
    sock.sendall(b"RFB 003.008\n")

    count = recv_exact(sock, 1)[0]
    if count == 0:
        reason_len = struct.unpack(">I", recv_exact(sock, 4))[0]
        raise SystemExit("server refused: " + recv_exact(sock, reason_len).decode(errors="replace"))
    types = recv_exact(sock, count)
    if 1 not in types:
        raise SystemExit(f"only None auth is supported; server offered {list(types)}")
    sock.sendall(bytes([1]))
    result = struct.unpack(">I", recv_exact(sock, 4))[0]
    if result != 0:
        raise SystemExit(f"auth failed, result={result}")

    sock.sendall(bytes([1]))  # ClientInit, shared, so this never kicks Packer off the console
    width, height = struct.unpack(">HH", recv_exact(sock, 4))
    recv_exact(sock, 16)  # server pixel format, replaced below
    name_len = struct.unpack(">I", recv_exact(sock, 4))[0]
    recv_exact(sock, name_len)
    return width, height


def capture(sock, width, height, out):
    from PIL import Image

    # Ask for 32bpp little-endian BGRX rather than decoding whatever the server prefers.
    sock.sendall(struct.pack(">Bxxx", 0)
                 + struct.pack(">BBBBHHHBBBxxx", 32, 24, 0, 1, 255, 255, 255, 16, 8, 0))
    sock.sendall(struct.pack(">BxH", 2, 1) + struct.pack(">i", 0))  # SetEncodings: raw only
    sock.sendall(struct.pack(">BBHHHH", 3, 0, 0, 0, width, height))  # full, non-incremental

    img = Image.new("RGB", (width, height))
    msg = recv_exact(sock, 1)[0]
    if msg != 0:
        raise SystemExit(f"expected FramebufferUpdate (0), got message type {msg}")
    recv_exact(sock, 1)
    for _ in range(struct.unpack(">H", recv_exact(sock, 2))[0]):
        x, y, w, h, enc = struct.unpack(">HHHHi", recv_exact(sock, 12))
        if enc != 0:
            raise SystemExit(f"server sent encoding {enc}, only raw (0) was requested")
        tile = Image.frombytes("RGBX", (w, h), recv_exact(sock, w * h * 4)).convert("RGB")
        b, g, r = tile.split()
        img.paste(Image.merge("RGB", (r, g, b)), (x, y))
    img.save(out)
    print(f"wrote {out} ({width}x{height})", file=sys.stderr)


def send_key(sock, keysym):
    """Press and release one key. 0xff0d is Return, which is what unblocks installer dialogs."""
    sock.sendall(struct.pack(">BBxxI", 4, 1, keysym))
    time.sleep(0.05)
    sock.sendall(struct.pack(">BBxxI", 4, 0, keysym))
    time.sleep(0.5)
    print(f"sent keysym {hex(keysym)}", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("host")
    parser.add_argument("port", type=int)
    parser.add_argument("output", nargs="?", help="PNG to write; omit when only sending a key")
    parser.add_argument("--key", help="X11 keysym to send, e.g. 0xff0d for Return")
    args = parser.parse_args()

    if not args.output and not args.key:
        parser.error("give an output PNG, a --key, or both")

    sock = socket.create_connection((args.host, args.port), timeout=20)
    width, height = handshake(sock)
    if args.key:
        send_key(sock, int(args.key, 0))
    if args.output:
        capture(sock, width, height, args.output)


if __name__ == "__main__":
    main()

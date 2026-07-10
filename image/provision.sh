#!/bin/bash
#
# Provisions a Debian 12 (bookworm) VM into a Jenkins agent golden image.
#
# Run as root inside the VM, either from Packer's shell provisioner or by hand. Idempotent:
# running it twice is a no-op. The env-var switches exist so CI can exercise the Java path in a
# plain debian:12 container, where there is no CD-ROM and no cloud-init to clean.
#
#   SKIP_GUEST_TOOLS=1   do not install xe-guest-utilities from /dev/sr0
#   SKIP_CLEANUP=1       do not run cloud-init clean or reset machine identity
#
# Why Temurin and not the distro JDK: Jenkins core is compiled at class-file major 65, so an agent
# needs a Java 21 JVM. Debian 12 ships no openjdk-21 at all, not even in bookworm-backports. An
# agent on Java 17 opens its WebSocket, logs "Connected", then dies in a silent reconnect loop
# with UnsupportedClassVersionError. The controller and agents must run the same Java major.

set -euo pipefail

JAVA_MAJOR=21
ADOPTIUM_KEYRING=/etc/apt/keyrings/adoptium.asc
ADOPTIUM_LIST=/etc/apt/sources.list.d/adoptium.list
GUEST_TOOLS_DEB='Linux/xe-guest-utilities_7.30.0-18_amd64.deb'

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

require_root() {
    [ "$(id -u)" -eq 0 ] || die "must run as root"
}

require_deps() {
    local missing=()
    for cmd in curl apt-get dpkg; do
        command -v "$cmd" >/dev/null 2>&1 || missing+=("$cmd")
    done
    if [ ${#missing[@]} -gt 0 ]; then
        die "missing: ${missing[*]}. Install with: apt-get install -y ${missing[*]}"
    fi
}

install_java() {
    log "adding the Adoptium apt repository"
    install -m 0755 -d /etc/apt/keyrings

    # An ASCII-armored key, deliberately. The base Debian cloud image has no gpg binary, so the
    # usual `curl ... | gpg --dearmor` fails, and leaves a truncated keyring behind that breaks
    # every later apt-get update until it is deleted. apt >= 2.4 reads armored keys directly.
    curl -fsSL -o "$ADOPTIUM_KEYRING" https://packages.adoptium.net/artifactory/api/gpg/key/public
    chmod a+r "$ADOPTIUM_KEYRING"

    local codename
    codename="$(. /etc/os-release && echo "$VERSION_CODENAME")"
    echo "deb [signed-by=${ADOPTIUM_KEYRING}] https://packages.adoptium.net/artifactory/deb ${codename} main" \
        > "$ADOPTIUM_LIST"

    # -q, not -qq: -qq swallows error output too, so a flaky mirror or a repo outage would fail
    # with empty logs. -q keeps the messages and only drops the progress animation.
    log "installing temurin-${JAVA_MAJOR}-jre"
    apt-get update -q
    DEBIAN_FRONTEND=noninteractive apt-get install -y -q "temurin-${JAVA_MAJOR}-jre"
}

verify_java() {
    command -v java >/dev/null 2>&1 || die "java not on PATH after install"
    local version major
    version="$(java -version 2>&1 | head -1)"
    major="$(printf '%s' "$version" | sed -E 's/.*version "([0-9]+).*/\1/')"
    log "java: $version"
    [ "$major" = "$JAVA_MAJOR" ] \
        || die "expected Java ${JAVA_MAJOR}, found ${major}. A Jenkins agent on this JVM will fail with UnsupportedClassVersionError."
}

install_guest_tools() {
    # Without these the guest never writes its IP to xenstore, and XAPI reports networks={} for the
    # lifetime of the VM. The inbound launcher does not need an IP, but every other tool does.
    [ -e /dev/sr0 ] || die "no /dev/sr0: attach guest-tools.iso, or set SKIP_GUEST_TOOLS=1"
    log "installing xe-guest-utilities from /dev/sr0"
    mkdir -p /mnt/gt

    # A subshell with an EXIT trap, not `trap ... RETURN`: under `set -e`, a failing command aborts
    # the shell rather than returning from the function, so a RETURN trap never fires and /dev/sr0
    # stays mounted for the next run to trip over. An EXIT trap in a subshell fires on both the
    # clean exit and the set -e abort. Verified against `set -e` + a failing install.
    (
        trap 'umount /mnt/gt 2>/dev/null || true' EXIT
        mount -o ro /dev/sr0 /mnt/gt
        # apt-get, not dpkg -i: the .deb may pull dependencies, which dpkg does not resolve.
        apt-get install -y -q "/mnt/gt/${GUEST_TOOLS_DEB}"
    )
    rmdir /mnt/gt 2>/dev/null || true

    systemctl enable --now xe-linux-distribution
}

harden_credentials() {
    # The build path leaves a login every clone would inherit: preseed sets user `debian` with
    # password `debian` and drops passwordless sudo in /etc/sudoers.d/debian, for Packer's SSH
    # provisioner. The inbound agent runs as `debian` under systemd and needs neither, so a
    # template must not ship them. Lock password auth and remove the sudoers grant.
    #
    # Locking does not disable the account: systemd still starts services as `debian`, and SSH key
    # auth still works if a key is ever added. It disables only password login.
    log "locking the build-time debian password and removing passwordless sudo"
    passwd -l debian 2>/dev/null || true
    rm -f /etc/sudoers.d/debian
}

cleanup_for_template() {
    # cloud-init re-runs its per-instance modules only when the datasource's instance-id differs
    # from the one cached on disk. If this image is templated with a cached id, and a clone's seed
    # happens to carry the same id, cloud-init treats the clone as a reboot and silently skips
    # everything: no agent secret is written, no agent starts, and nothing is logged anywhere.
    log "resetting cloud-init and machine identity"
    cloud-init clean --logs --seed 2>/dev/null || true
    : > /etc/machine-id
    rm -f /var/lib/dbus/machine-id
    rm -f /etc/ssh/ssh_host_*
    rm -rf /var/lib/cloud/instances/*
    apt-get clean
}

main() {
    require_root
    require_deps

    install_java
    verify_java

    if [ "${SKIP_GUEST_TOOLS:-0}" = "1" ]; then
        log "skipping guest tools (SKIP_GUEST_TOOLS=1)"
    else
        install_guest_tools
    fi

    if [ "${SKIP_CLEANUP:-0}" = "1" ]; then
        log "skipping template cleanup (SKIP_CLEANUP=1)"
    else
        harden_credentials
        cleanup_for_template
        touch /var/lib/golden-image-ready
    fi

    log "done"
}

main "$@"

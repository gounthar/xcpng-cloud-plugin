#!/bin/bash
#
# Provisions a Debian VM into a Jenkins agent golden image. Runs on bookworm (12) and trixie (13);
# the Packer template and CI both build on trixie.
#
# Run as root inside the VM, either from Packer's shell provisioner or by hand. Idempotent:
# running it twice is a no-op. The env-var switches exist so CI can exercise the Java path in a
# plain debian:13 container, where there is no CD-ROM and no cloud-init to clean.
#
#   SKIP_GUEST_TOOLS=1   do not install xe-guest-utilities from /dev/sr0
#   SKIP_CLEANUP=1       do not run cloud-init clean or reset machine identity
#
# Why Temurin and not the distro JDK: Jenkins core is compiled at class-file major 65, so an agent
# needs a Java 21 JVM. Bookworm ships no openjdk-21 at all, not even in bookworm-backports; trixie
# does, but Temurin pins the vendor and version to match the controller on either base. An agent on
# Java 17 opens its WebSocket, logs "Connected", then dies in a silent reconnect loop with
# UnsupportedClassVersionError. The controller and agents must run the same Java major.

set -euo pipefail

JAVA_MAJOR=21
ADOPTIUM_KEYRING=/etc/apt/keyrings/adoptium.asc
ADOPTIUM_LIST=/etc/apt/sources.list.d/adoptium.list
GUEST_TOOLS_DEB='Linux/xe-guest-utilities_7.30.0-18_amd64.deb'
# Fallback source of xenstore-read, the client the agent service uses to read its per-clone seed. On
# Debian 13 xe-guest-utilities 7.30 already ships /usr/bin/xenstore-read, so ensure_xenstore_client
# usually finds it present and skips this. xenstore-utils covers an image that has no guest tools.
XENSTORE_PKG='xenstore-utils'
AGENT_LAUNCHER=/usr/local/bin/jenkins-agent-launch
AGENT_UNIT=/etc/systemd/system/jenkins-agent.service

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

require_root() {
    [ "$(id -u)" -eq 0 ] || die "must run as root"
}

require_deps() {
    # apt-get and dpkg are part of any Debian base, so checking them is pointless and, worse, the
    # old message told you to `apt-get install apt-get`, which is not a package. curl is the only
    # real prerequisite the base cloud image can lack; it comes from the curl package.
    command -v apt-get >/dev/null 2>&1 || die "apt-get not found: this is not a Debian system"
    command -v curl >/dev/null 2>&1 || die "curl not found. Install it with: apt-get install -y curl"
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

ensure_xenstore_client() {
    # The agent service reads its seed from xenstore, so the guest needs a xenstore-read binary. On a
    # real image xe-guest-utilities already provides /usr/bin/xenstore-read (verified on the pool: the
    # 7.30 package ships it on Debian 13), so this normally no-ops; the install is a fallback for an
    # image built without guest tools. It sits with the guest-integration concern and is skipped
    # alongside guest tools in the CI container (which has no xenbus to talk to anyway).
    if command -v xenstore-read >/dev/null 2>&1; then
        log "xenstore-read already present"
        return
    fi
    log "installing a xenstore client (${XENSTORE_PKG})"
    apt-get install -y -q "$XENSTORE_PKG"
    command -v xenstore-read >/dev/null 2>&1 || die "xenstore-read still missing after installing ${XENSTORE_PKG}"
}

install_agent_service() {
    # Bake the inbound-agent launcher and its systemd unit into the image. Unlike the M2 hand path and
    # the earlier cloud-init sketch (image/seed/user-data.tmpl), the per-clone values are not templated
    # in: the launcher reads them from xenstore at boot, where the plugin writes vm-data/jenkins/{url,
    # name,secret} onto the clone before it starts. One baked unit serves every clone; nothing per-agent
    # is rendered at provision time.
    log "installing the xenstore-seeded inbound agent service"

    cat > "$AGENT_LAUNCHER" <<'LAUNCH'
#!/bin/sh
# Launch the Jenkins inbound agent from the per-clone seed in xenstore. Run as root by
# jenkins-agent.service: read vm-data/jenkins/{url,name,secret}, drop the secret to a debian-owned
# file (kept off the java command line, which any user can read via /proc), fetch agent.jar if it is
# missing, then exec the agent as the unprivileged debian user. Exit non-zero when the seed is not
# present yet, so systemd's Restart=on-failure retries until the clone's xenstore is populated.
set -eu

xs_read() { xenstore-read "vm-data/jenkins/$1" 2>/dev/null || true; }

url=$(xs_read url)
name=$(xs_read name)
secret=$(xs_read secret)
if [ -z "$url" ] || [ -z "$name" ] || [ -z "$secret" ]; then
    echo "jenkins-agent: seed not yet in xenstore (vm-data/jenkins/{url,name,secret}); will retry" >&2
    exit 1
fi

agent_dir=/home/debian/agent
install -d -o debian -g debian -m 0700 "$agent_dir"

secret_file="$agent_dir/.jnlp-secret"
( umask 077; printf '%s' "$secret" > "$secret_file" )
chown debian:debian "$secret_file"
chmod 0400 "$secret_file"

# The controller root URL carries its trailing slash (Jenkins.getRootUrl()), so jnlpJars sits directly
# after it. Only fetch when missing or empty; Restart=on-failure would otherwise re-download in a loop.
# Fetching the controller's own agent.jar keeps remoting version-matched, so it is preferred over baking
# a copy into the image. Retry a few times so a controller that is still coming up does not fail the
# whole service (systemd would restart it anyway, but a short in-launcher retry connects sooner).
if [ ! -s "$agent_dir/agent.jar" ]; then
    tries=0
    until curl -sSfL -o "$agent_dir/agent.jar" "${url}jnlpJars/agent.jar"; do
        tries=$((tries + 1))
        if [ "$tries" -ge 5 ]; then
            echo "jenkins-agent: could not fetch agent.jar from ${url}jnlpJars/agent.jar after $tries tries" >&2
            exit 1
        fi
        sleep 3
    done
    chown debian:debian "$agent_dir/agent.jar"
fi

# -webSocket tunnels the agent connection over the controller's HTTP port, so no inbound JNLP TCP port
# is needed. -secret @file keeps the secret out of /proc/*/cmdline.
exec runuser -u debian -- /usr/bin/java -jar "$agent_dir/agent.jar" \
    -webSocket \
    -url "$url" \
    -secret "@$secret_file" \
    -name "$name" \
    -workDir "$agent_dir"
LAUNCH
    chmod 0755 "$AGENT_LAUNCHER"

    cat > "$AGENT_UNIT" <<'UNIT'
[Unit]
Description=Jenkins inbound agent (xenstore-seeded)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/jenkins-agent-launch
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

    # Parse the launcher here so CI, which runs this in a container, catches a heredoc typo even with
    # no systemd and no xenbus to run it against.
    sh -n "$AGENT_LAUNCHER"

    # Enable for the next boot only where systemd is running. The CI container has none, and a clone
    # boots with the unit already enabled; it is never started here, only on the clone.
    if [ -d /run/systemd/system ]; then
        systemctl enable jenkins-agent.service
    else
        log "no systemd here; wrote and validated the unit but skipped enable"
    fi
}

install_ssh_seed_service() {
    # Optional per-clone SSH access. When the plugin sets vm-data/jenkins/ssh_authorized_key (from an
    # operator-supplied *public* key on the agent template), this oneshot writes it to the debian user's
    # authorized_keys at boot. The private half never touches a guest, and there is no shared baked key.
    # A clone with no such key set gets no authorized_keys and stays SSH-closed, which is the norm: the
    # inbound launcher needs no SSH. Kept independent of jenkins-agent.service so SSH still comes up when
    # the agent is flapping, which is exactly when an operator wants to get in.
    log "installing the optional per-clone SSH-key seed service"

    cat > /usr/local/bin/jenkins-agent-ssh-seed <<'SSHSEED'
#!/bin/sh
# Trust the operator's per-clone public key, delivered over xenstore, for the debian user. No key set
# means an inbound-only clone: exit cleanly, leaving SSH closed. The single managed key is rewritten
# each boot so clearing the template field revokes access on the next clone.
set -eu
key=$(xenstore-read vm-data/jenkins/ssh_authorized_key 2>/dev/null || true)
[ -n "$key" ] || exit 0
d=/home/debian/.ssh
install -d -o debian -g debian -m 0700 "$d"
printf '%s\n' "$key" > "$d/authorized_keys"
chown debian:debian "$d/authorized_keys"
chmod 0600 "$d/authorized_keys"
SSHSEED
    chmod 0755 /usr/local/bin/jenkins-agent-ssh-seed

    cat > /etc/systemd/system/jenkins-agent-ssh-seed.service <<'SSHUNIT'
[Unit]
Description=Seed the debian user's authorized_keys from xenstore
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/jenkins-agent-ssh-seed
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
SSHUNIT

    sh -n /usr/local/bin/jenkins-agent-ssh-seed

    # Regenerate SSH host keys on boot when they are missing. cleanup_for_template removes them so each
    # clone gets unique keys, but a plugin clone is seeded over xenstore and has no cloud-init datasource
    # (DataSourceNone), so cloud-init's cc_ssh never runs to recreate them. Without this drop-in sshd
    # refuses to start on such a clone and the per-clone authorized_keys the seed service wrote is
    # unreachable. Verified on the pool: a clone with removed host keys and no cidata left sshd down.
    mkdir -p /etc/systemd/system/ssh.service.d
    cat > /etc/systemd/system/ssh.service.d/regen-host-keys.conf <<'REGEN'
[Service]
ExecStartPre=/bin/sh -c 'test -e /etc/ssh/ssh_host_ed25519_key || ssh-keygen -A'
REGEN

    if [ -d /run/systemd/system ]; then
        systemctl enable jenkins-agent-ssh-seed.service
    else
        log "no systemd here; wrote and validated the ssh-seed unit but skipped enable"
    fi
}

install_growroot_service() {
    # Grow the root partition and filesystem to fill the virtual disk on boot. genericcloud does this
    # through cloud-init's growpart module, but a plugin clone is seeded over xenstore and has no
    # cloud-init datasource (DataSourceNone), so without this the root FS stays at the base image size
    # and a real build fills it (the DiskSpaceMonitor took an agent offline in the first live run).
    # growpart comes from cloud-guest-utils; resize2fs from e2fsprogs. Both no-op once the FS is full.
    log "installing the root-filesystem grow service"
    if ! command -v growpart >/dev/null 2>&1; then
        apt-get install -y -q cloud-guest-utils
    fi

    cat > /usr/local/bin/jenkins-agent-growroot <<'GROW'
#!/bin/sh
# Expand the root partition and ext filesystem to the whole disk. Idempotent: growpart and resize2fs
# both no-op when the partition and filesystem already fill the device.
set -eu
src=$(findmnt -no SOURCE /)                 # e.g. /dev/xvda1
dev=$(lsblk -no PKNAME "$src" 2>/dev/null)  # e.g. xvda
num=$(printf '%s' "$src" | grep -o '[0-9]*$')
[ -n "$dev" ] && [ -n "$num" ] || exit 0
growpart "/dev/$dev" "$num" 2>/dev/null || true
resize2fs "$src" 2>/dev/null || true
GROW
    chmod 0755 /usr/local/bin/jenkins-agent-growroot

    cat > /etc/systemd/system/jenkins-agent-growroot.service <<'GROWUNIT'
[Unit]
Description=Grow the root filesystem to fill the disk
DefaultDependencies=no
After=local-fs.target
Before=jenkins-agent.service multi-user.target

[Service]
Type=oneshot
ExecStart=/usr/local/bin/jenkins-agent-growroot
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
GROWUNIT

    sh -n /usr/local/bin/jenkins-agent-growroot

    if [ -d /run/systemd/system ]; then
        systemctl enable jenkins-agent-growroot.service
    else
        log "no systemd here; wrote and validated the growroot unit but skipped enable"
    fi
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
        log "skipping guest tools and xenstore client (SKIP_GUEST_TOOLS=1)"
    else
        install_guest_tools
        ensure_xenstore_client
    fi

    # The launcher and the two seed units are baked from heredocs and validated with `sh -n`; only the
    # apt install of the xenstore client and the guest-tools step are gated above. So these run in CI's
    # container too (systemctl enable is skipped where there is no systemd), which is what exercises them.
    install_agent_service
    install_ssh_seed_service
    install_growroot_service

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

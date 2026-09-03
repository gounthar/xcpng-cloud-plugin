#!/bin/bash
#
# Provisions a Debian VM into a Jenkins agent golden image. Runs on bookworm (12) and trixie (13);
# the Packer template and CI both build on trixie.
#
# Run as root inside the VM, either from Packer's shell provisioner or by hand. Idempotent:
# running it twice is a no-op. The env-var switches exist so CI can exercise the Java path in a
# plain debian:13 container, where there is no CD-ROM and no cloud-init to clean.
#
#   SKIP_GUEST_TOOLS=1   do not install xe-guest-utilities from the tools ISO
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
    # Find the tools ISO rather than assuming /dev/sr0. Under Packer there are two optical devices:
    # sr0 is the Debian netinst the VM booted from and sr1 is the XCP-ng Tools ISO, so hardcoding sr0
    # mounts the installer and then dies claiming the deb is missing. On a hand-built VM with only the
    # tools ISO attached, sr0 is correct and this loop finds it first anyway.
    local tools_dev=""
    for dev in /dev/sr*; do
        [ -b "$dev" ] || continue
        mkdir -p /mnt/gt
        if mount -o ro "$dev" /mnt/gt 2>/dev/null; then
            if ls /mnt/gt/Linux/xe-guest-utilities_*_amd64.deb >/dev/null 2>&1; then
                umount /mnt/gt
                tools_dev="$dev"
                break
            fi
            umount /mnt/gt
        fi
    done
    [ -n "$tools_dev" ] \
        || die "no XCP-ng guest tools ISO on any /dev/sr*: attach guest-tools.iso, or set SKIP_GUEST_TOOLS=1"
    log "installing xe-guest-utilities from ${tools_dev}"
    mkdir -p /mnt/gt

    # A subshell with an EXIT trap, not `trap ... RETURN`: under `set -e`, a failing command aborts
    # the shell rather than returning from the function, so a RETURN trap never fires and the ISO
    # stays mounted for the next run to trip over. An EXIT trap in a subshell fires on both the
    # clean exit and the set -e abort. Verified against `set -e` + a failing install.
    (
        trap 'umount /mnt/gt 2>/dev/null || true' EXIT
        mount -o ro "$tools_dev" /mnt/gt
        # Glob the deb rather than pin its version: the exact xe-guest-utilities filename is a property
        # of whatever guest-tools.iso the pool ships, so pinning it makes the next XCP-ng point update
        # break the build inside apt-get with no hint the ISO layout changed. Fail loudly on zero or on
        # more than one match, so a future ISO shipping two versions is an error, not an arbitrary pick.
        set -- /mnt/gt/Linux/xe-guest-utilities_*_amd64.deb
        [ -e "$1" ] || die "no xe-guest-utilities deb on ${tools_dev} (looked in /mnt/gt/Linux)"
        [ "$#" -eq 1 ] || die "multiple xe-guest-utilities debs on the tools ISO: $*"
        # apt-get, not dpkg -i: the .deb may pull dependencies, which dpkg does not resolve.
        apt-get install -y -q "$1"
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
    # an earlier, since-removed cloud-init sketch, the per-clone values are not templated in: the
    # launcher reads them from xenstore at boot, where the plugin writes vm-data/jenkins/{url,
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
    until curl -sSfL --connect-timeout 10 --max-time 120 -o "$agent_dir/agent.jar" "${url}jnlpJars/agent.jar"; do
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

    # Regenerate SSH host keys before sshd when a clone has none. cleanup_for_template removes them so
    # each clone gets unique keys, but a plugin clone is seeded over xenstore with no cloud-init
    # datasource (DataSourceNone), so cloud-init's cc_ssh never recreates them. Debian's own
    # sshd-keygen.service would, but it is gated on ConditionFirstBoot, which an image sealed with an
    # empty /etc/machine-id does not reliably trigger. Without host keys, ssh.service's built-in
    # ExecStartPre=/usr/sbin/sshd -t fails ("no hostkeys available -- exiting") and sshd never listens,
    # so the per-clone authorized_keys the seed service wrote is unreachable. A dedicated oneshot,
    # ordered Before=ssh.service and guarded only on the keys being absent, regenerates them in time on
    # every clone. This supersedes the earlier ssh.service.d drop-in, whose ExecStartPre was appended
    # after ssh.service's own sshd -t and so never fired on a keyless clone; remove that stale file.
    rm -f /etc/systemd/system/ssh.service.d/regen-host-keys.conf
    rmdir /etc/systemd/system/ssh.service.d 2>/dev/null || true

    cat > /etc/systemd/system/jenkins-agent-sshd-keygen.service <<'KEYGEN'
[Unit]
Description=Generate SSH host keys before sshd when a clone has none
ConditionPathExists=!/etc/ssh/ssh_host_ed25519_key
Before=ssh.service ssh.socket sshd.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/bin/ssh-keygen -A

[Install]
WantedBy=multi-user.target ssh.service ssh.socket sshd.service
KEYGEN

    if [ -d /run/systemd/system ]; then
        systemctl enable ssh.service jenkins-agent-ssh-seed.service jenkins-agent-sshd-keygen.service
    else
        log "no systemd here; wrote and validated the ssh units but skipped enable"
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
# The || true on the lookups matters under set -e: lsblk can exit non-zero on an unusual root SOURCE
# (device-mapper, LVM) and grep exits 1 when the source has no trailing digit. Without it the whole
# oneshot would abort before the guard below instead of cleanly no-oping on a layout it can't grow.
src=$(findmnt -no SOURCE / || true)                  # e.g. /dev/xvda1
dev=$(lsblk -no PKNAME "$src" 2>/dev/null || true)   # e.g. xvda
num=$(printf '%s' "$src" | grep -o '[0-9]*$' || true)
[ -n "$dev" ] && [ -n "$num" ] || exit 0
# growpart exits 1 for NOCHANGE (the partition already fills the disk) and 2 for a real error: a
# missing sfdisk, an unexpected layout, or a filesystem needing fsck first. Treat only NOCHANGE as
# success; on a real error let stderr reach the journal and exit non-zero so the unit goes failed in
# systemctl status, rather than leaving a root FS silently at base-image size (first symptom:
# DiskSpaceMonitor benching the agent mid-build) behind a green oneshot. Exiting non-zero is safe:
# the unit is only Before= ordered, with nothing Requires-ing it, so a failure shows red without
# blocking jenkins-agent.service. resize2fs no-ops cleanly (exit 0) once the FS already fills.
rc=0
growpart "/dev/$dev" "$num" || rc=$?
if [ "$rc" -gt 1 ]; then
    echo "jenkins-agent-growroot: growpart exit $rc, root partition not grown" >&2
    exit 1
fi
resize2fs "$src" || {
    echo "jenkins-agent-growroot: resize2fs failed, root filesystem not grown" >&2
    exit 1
}
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

move_tmp_off_tmpfs() {
    # Debian 13 mounts /tmp as a tmpfs sized to about half of RAM through systemd's tmp.mount. On the
    # golden's 2 GiB that is ~983 MiB, just under Jenkins' default TemporarySpaceMonitor threshold of
    # 1 GiB, so a freshly connected agent is marked temporarily offline ("Disk space is below threshold
    # of 1.00 GiB ... on /tmp") and refuses queued builds. A warm spare that Jenkins benches on connect
    # is worse than none: the build waits in the queue instead of landing on the hot agent, so the whole
    # point of keeping one hot is lost. Mask tmp.mount so /tmp is a plain directory on the root disk,
    # which jenkins-agent-growroot expands to fill the 10 GiB virtual disk; that clears the threshold
    # with no controller-side monitor tuning. systemd-tmpfiles restores /tmp's 1777 mode on boot.
    log "moving /tmp off tmpfs so it clears the disk-space monitor"

    # This symlink is exactly what `systemctl mask tmp.mount` writes, done by hand so the mask also lands
    # in the CI container where systemd is not running. No daemon-reload is needed: the image is templated
    # (shut down) right after, and the clone's systemd reads the mask naturally on its next boot; on this
    # build VM a reload would not unmount the live tmpfs anyway, so it buys nothing.
    ln -sf /dev/null /etc/systemd/system/tmp.mount
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

    unpin_network_from_mac
}

# cloud-init renders /etc/netplan/50-cloud-init.yaml during the build with a `match: macaddress:`
# stanza naming the *builder's* NIC. That is correct for the machine it was generated on and wrong
# for every copy of it, because VM.clone regenerates an autogenerated MAC rather than copying it.
# A clone therefore has a NIC matching no stanza: netplan configures nothing, the interface never
# comes up, and not one packet leaves the guest. No DHCP DISCOVER is even attempted.
#
# The agent can never dial back, and from the outside almost everything still looks healthy. The
# guest tools report PV_drivers_detected=True and live=True over xenstore, which needs no network,
# so the only field telling the truth is XAPI's networks={} -- and that reads like "tools not ready
# yet" rather than "this guest has no address and never will".
#
# Matching on interface name instead is enough: xen-netfront names the interface enX0, and unlike
# the MAC that name is stable across clones.
#
# This belongs beside the ssh host keys and /etc/machine-id above. All three are builder identity a
# clone must not inherit; this one was simply missed.
unpin_network_from_mac() {
    # No netplan means some other renderer is in charge, usually installer-written ifupdown. Nothing
    # here can be rewritten and the MAC check below has nothing to read, so say so rather than
    # returning quietly: a silent skip looks identical to a successful check in the build log.
    # Validating an arbitrary renderer is deliberately out of scope; what this function owns is the
    # file cloud-init renders. An image using something else must make its own guarantee that the
    # interface config does not name a MAC.
    if [ ! -d /etc/netplan ]; then
        log "no /etc/netplan; skipping the MAC-pin rewrite and its check (another renderer is in use)"
        return 0
    fi
    log "unpinning netplan from the builder MAC"
    cat > /etc/netplan/50-cloud-init.yaml <<'NETPLAN'
# Rewritten by provision.sh at template time. Do not reintroduce a `match: macaddress:` here:
# VM.clone regenerates the MAC, so a MAC-pinned stanza matches nothing on a clone and the guest
# boots with no network at all.
network:
  version: 2
  ethernets:
    xen-net:
      match:
        name: "en*"
      dhcp4: true
      dhcp6: true
NETPLAN
    # netplan refuses to apply a globally-readable config, and warns loudly about it.
    chmod 600 /etc/netplan/50-cloud-init.yaml

    # Fail the build rather than ship an image whose clones are mute. Same reasoning as the Java
    # major check above: cheap here, and roughly forty minutes plus one baffling offline node to
    # diagnose in the field.
    #
    # Comments are stripped before matching, because the warning written just above contains the word
    # `macaddress`; a bare `grep -q macaddress` matches this function's own output and fails every
    # build including the correct ones. Caught by running the check against a patched disk.
    #
    # The pattern then accepts any way netplan can spell the key, not just the block style cloud-init
    # happens to emit: `macaddress: x`, the flow mapping `match: {macaddress: x}`, and a quoted
    # `"macaddress": x`. An anchored line-based match passed the last two, and the whole point of
    # this check is to catch a config we did not write.
    if sed 's/#.*//' /etc/netplan/*.yaml /etc/netplan/*.yml 2>/dev/null \
        | grep -qE '(^|[[:space:],{])"?macaddress"?[[:space:]]*:'; then
        die "netplan still pins a MAC after cleanup; clones of this image would boot with no network"
    fi
}

# Operator-supplied trust anchors, uploaded by the Packer file provisioner.
# Empty on a stock build, in which case this returns without touching anything.
#
# Both stores have to end up holding the anchor. Java does not read the system
# store, so an anchor that reaches only there leaves an image where curl succeeds
# against the controller and the agent still cannot connect — a failure that
# gives no hint where to look.
#
# Only the system store is written here. The Java side is populated by
# update-ca-certificates(8), through the hook /etc/ca-certificates/update.d/adoptium-cacerts
# that adoptium-ca-certificates ships and install_java pulls in with Temurin. That
# hook regenerates the Adoptium truststore from the system store under an alias of
# its own making. This function used to import each anchor with keytool as well,
# and the hook discarded it a few lines later, so that import was doing nothing.
# Because the route is somebody else's hook rather than a call of ours, whether it
# worked is verified at the end rather than assumed. See #181.
#
# Fixed, not taken from the environment. The Packer file provisioner uploads to a
# path this script does not get to choose, so an override bought nothing, and it
# is read below by an rm -rf running as root: EXTRA_CA_DIR=/etc would have taken
# /etc with it. readonly rather than a plain assignment so a later edit that
# reintroduces the override has to do so deliberately.
readonly EXTRA_CA_DIR=/tmp/ca-certificates

# Everything the Packer file provisioner uploaded is removed again, whatever
# happened. /tmp is a plain directory on the root disk here, not a tmpfs -- the
# tmp.mount mask a hundred lines above sees to that -- so anything left behind
# is baked into the template and into every clone made from it. An operator who
# puts a PKCS#12 in this directory by mistake, which the docs warn is the easy
# mistake to make, would otherwise ship its private key on every agent.
discard_extra_ca_dir() {
    [ -d "$EXTRA_CA_DIR" ] || return 0
    # Belt and braces against a future edit to the constant above: this only ever
    # deletes below /tmp, whatever it is pointed at.
    case "$EXTRA_CA_DIR" in
        /tmp/?*) rm -rf -- "$EXTRA_CA_DIR" ;;
        *) log "refusing to remove ${EXTRA_CA_DIR}: not under /tmp" ;;
    esac
}

install_extra_ca_certificates() {
    [ -d "$EXTRA_CA_DIR" ] || { log "no operator CA directory, skipping"; return 0; }
    # Removed on every exit from here on, including the failure paths below, each
    # of which calls die and would otherwise leave the upload on disk.
    trap discard_extra_ca_dir EXIT

    # Collect entries under EXTRA_CA_DIR (excluding the placeholder .gitkeep) so the empty-directory
    # case is a true no-op.
    local -a everything=()
    local -a certs=()
    local entry
    while IFS= read -r -d '' entry; do
        everything+=("$entry")
        [ -f "$entry" ] || continue
        case "$entry" in
            *.crt|*.pem) certs+=("$entry") ;;
        esac
    done < <(find "$EXTRA_CA_DIR" -mindepth 1 -maxdepth 1 ! -name '.gitkeep' -print0)

    # Scan every regular file, not only the ones that will be installed. A key in
    # a file this function skips is still a key sitting in the image.
    local f
    while IFS= read -r -d '' f; do
        if grep -q 'PRIVATE KEY' "$f"; then
            die "$(basename "$f") contains a private key; only certificates belong in ${EXTRA_CA_DIR}"
        fi
    done < <(find "$EXTRA_CA_DIR" -type f -print0)

    if [ "${#certs[@]}" -eq 0 ]; then
        # Silence here is what the whole feature exists to avoid. A file that was
        # dropped in and not installed means the operator believes the image has
        # an anchor it does not have, and they find out when an agent will not
        # connect and nothing says why.
        [ "${#everything[@]}" -eq 0 ] || die "no .crt or .pem certificates in ${EXTRA_CA_DIR}, but it is not empty: $(basename -a "${everything[@]}" | tr '\n' ' ')"
        log "no operator CA certificates supplied"
        return 0
    fi

    local unrecognised=()
    local entry
    for entry in "${everything[@]}"; do
        case "$entry" in
            *.crt|*.pem) ;;
            *) unrecognised+=("$entry") ;;
        esac
    done
    [ "${#unrecognised[@]}" -eq 0 ] || die "unrecognised entries in ${EXTRA_CA_DIR}: $(basename -a \"${unrecognised[@]}\" | tr '\n' ' ')"

    command -v openssl >/dev/null 2>&1 || die "openssl not found, needed to validate ${EXTRA_CA_DIR}"
    command -v keytool >/dev/null 2>&1 || die "keytool not found; install_java must run before this"
    command -v update-ca-certificates >/dev/null 2>&1 \
        || die "update-ca-certificates not found. Install it with: apt-get install -y ca-certificates"

    local cert alias count fingerprint seen=" "
    for cert in "${certs[@]}"; do
        alias="$(basename "$cert")"; alias="${alias%.*}"

        # Both globs above feed this one loop, so foo.crt and foo.pem arrive as the
        # same alias twice and the second one wins in the system store while the
        # first one stays in the Java store, which the keytool branch below reports
        # as already present. Measured with two distinct CAs named vates.crt and
        # vates.pem: the system store ends up holding one of them, the Java store
        # the other, and the build is green. The stores disagreeing is the failure
        # this function's header exists to prevent.
        case "$seen" in
            *" ${alias} "*) die "$cert and an earlier file both give alias ${alias}; rename one" ;;
        esac
        seen="${seen}${alias} "

        openssl x509 -in "$cert" -noout >/dev/null 2>&1 \
            || die "$cert is not a PEM certificate"

        # A concatenated chain is a common export format and neither consumer
        # handles one: keytool imports the first certificate and drops the rest
        # without an error, and update-ca-certificates(8) says outright that
        # there should be one certificate per file. Measured: two CAs in one file
        # gives a keystore with one entry. Refuse rather than half-install.
        count="$(grep -c 'BEGIN CERTIFICATE' "$cert" || true)"
        [ "$count" -eq 1 ] \
            || die "$cert holds $count certificates; split the chain into one file per certificate"

        log "installing trust anchor ${alias} into the system store"
        install -m 0644 "$cert" "/usr/local/share/ca-certificates/${alias}.crt"
    done

    update-ca-certificates

    # One listing for all of them, and its exit status is read rather than its
    # emptiness. On an unreadable store keytool exits 1 with `keytool error:
    # java.io.IOException: Keystore was tampered with, or password was incorrect`;
    # discarding that and testing only for the fingerprint reports every anchor as
    # missing, which is a confident diagnosis of the wrong fault. Measured on
    # Temurin 21. In the healthy case keytool writes nothing to stderr, so folding
    # it in costs no noise.
    #
    # Not -v. The default listing already carries a `Certificate fingerprint
    # (SHA-256):` line per entry on Temurin 21, which is what install_java puts
    # here; -v prints the full chain for every system anchor to say the same thing.
    # Both forms were checked against the same certificate and both match.
    local truststore
    if ! truststore="$(keytool -list -cacerts -storepass changeit 2>&1)"; then
        die "cannot read the Java truststore: $(printf '%s\n' "$truststore" | head -1)"
    fi

    # Match on the SHA-256 fingerprint rather than the alias. The hook names the
    # entry itself -- ci-root.crt lands as `ciroot` -- so the alias is not ours to
    # predict, and an alias that happens to exist proves nothing about which
    # certificate sits under it.
    for cert in "${certs[@]}"; do
        alias="$(basename "$cert")"; alias="${alias%.*}"
        fingerprint="$(openssl x509 -in "$cert" -noout -fingerprint -sha256 | cut -d= -f2)"
        if printf '%s\n' "$truststore" | grep -qF "$fingerprint"; then
            log "trust anchor ${alias} reached the Java truststore"
        else
            # Reached if install_java stops installing Temurin from packages.adoptium.net,
            # or that package drops the hook. Failing the build here is the point: the
            # alternative is a green build producing an image whose agents cannot connect,
            # which is the silence this whole function exists to prevent.
            die "${alias} did not reach the Java truststore; update-ca-certificates ran, but the Adoptium hook did not place it"
        fi
    done
    return 0
}


main() {
    require_root
    require_deps

    install_java
    verify_java

    install_extra_ca_certificates

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
    move_tmp_off_tmpfs

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

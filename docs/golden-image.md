# The golden image

This plugin does not ship an image, and it does not build one. It clones a template you already
have on your pool. This document is how to get that template.

## Why no image is shipped

An XCP-ng template is not a file. It is a VM record plus VDIs living in a Storage Repository on a
particular pool. There is no artifact to download unless it is exported as an XVA, and Vates' own
Packer builder describes its XVA path as untested. Publishing one would mean hosting several
gigabytes of Debian and owning its security updates indefinitely, which is a larger commitment than
this plugin makes.

The plugin also never builds the image itself. It runs on the Jenkins controller, not on the
hypervisor, and a Jenkins plugin that constructs operating system disks is a surface nobody wants.
Neither `proxmox-cloud-plugin` nor `vsphere-cloud-plugin` does this either. Both document template
preparation, as this does.

## What the image must contain

| requirement | why |
|---|---|
| **Java 21** | Jenkins core is compiled at class-file **major 65**. An agent on Java 17 opens its WebSocket, logs `Connected`, then dies in a silent reconnect loop with `UnsupportedClassVersionError`. It never reports a useful reason; you have to read `agent.log` inside the VM. The controller and its agents must run the same Java major. |
| **`xenstore-read`** | The baked agent service reads its per-clone data (controller URL, agent name, JNLP secret) from xenstore, so the guest needs a `xenstore-read` binary. On Debian 13 `xe-guest-utilities` 7.30 already ships one; `provision.sh` installs `xenstore-utils` only as a fallback when it is missing. |
| **`xe-guest-utilities`** | Without the guest agent the VM never writes its address to xenstore, and XAPI reports `networks={}` for its whole life. The inbound launcher does not need an address, but `tools/measure_clone.py` and any future SSH launcher do. |
| **cloud-init** | Needed for the from-scratch `import_raw_vdi` bootstrap, which relies on a seed `network-config` to bring the network up (see below). The clone path no longer consumes it: per-clone data arrives over xenstore, not a NoCloud seed. |
| **`/tmp` on disk, not tmpfs** | Debian 13 mounts `/tmp` as a tmpfs sized to about half of RAM. On a 2 GiB agent that is ~983 MiB, under Jenkins' default `TemporarySpaceMonitor` threshold of 1 GiB, so a freshly connected agent is benched (`Disk space is below threshold of 1.00 GiB ... on /tmp`) and refuses queued builds. `provision.sh` masks `tmp.mount` so `/tmp` lives on the root disk that `jenkins-agent-growroot` expands to 10 GiB, clearing the threshold with no controller-side monitor tuning. |

`sshd` is in the image already. An authorized key is **opt-in**: set the agent template's SSH public
key field and each clone trusts it for the `debian` user (delivered per-clone over xenstore, see
below). An inbound-only agent needs no key at all, so leaving the field blank writes no
`authorized_keys` and refuses SSH. The field is there for one-off verification and a future SSH
launcher; the **private key never leaves you**.

One image serves both launchers. What differs is the per-clone xenstore data, not the disk.

## Building it with Packer (recommended)

`image/xcpng-jenkins-agent.pkr.hcl` uses `github.com/vatesfr/xenserver`, the builder Vates maintains
and develops alongside their Terraform provider. That is the ecosystem sentence worth having: Packer
builds the image, Terraform builds the static fleet, and this plugin scales agents to a build queue,
which is the one thing neither of the others can do.

```sh
export PKR_VAR_remote_host=192.168.1.87
export PKR_VAR_remote_username=root
export PKR_VAR_remote_password=...        # never commit this

packer init  image/
packer validate image/
packer build image/
```

The build produces a template named `jenkins-agent-debian13`, which is what you put in the cloud's
template field.

> **Honesty about state (updated 2026-07-12).** `packer validate` passes and CI runs it on every push.
> `packer build` has now been run against the lab pool, and the previously-unverified step **works**:
> the builder uploaded the netinst ISO to the `local-iso` SR, created the VM, connected VNC, and drove
> the Debian installer via `boot_command`, and the installer kernel loaded. The build then blocked at the
> **preseed fetch**, for an environment reason, not a template one: the builder serves
> `image/http/preseed.cfg` over HTTP on the machine running `packer`, and when that machine is behind
> WSL2 NAT the building VM on the LAN cannot reach it (confirmed: dom0 could not reach the packer HTTP
> port on either the WSL or Windows address). This is the same inbound-reachability wall as the M2
> agent. **Run `packer build` from a host the pool can reach on the LAN**: either WSL2 mirrored
> networking, a `netsh portproxy` for the packer HTTP port (pin `http_port_min`/`max` so it is stable),
> or, cleanest, a golden-image **clone as the builder** (pool-adjacent, disposable, CI-shaped). The
> `boot_command`/preseed pairing itself is validated up to that fetch. Everything durable about the
> image is in `image/provision.sh`, which CI executes for real inside a `debian:13` container on every
> push. The manual path below also works.

## Building it by hand

1. Create a VM from the shipped **Debian Trixie 13** template, or import a Debian generic cloud
   image. Give it a disk on a **file-based SR** (`ext`, `nfs`): `VM.clone` is copy-on-write there.
   On an LVM SR it silently becomes a full copy, and the whole performance story evaporates.
2. Install `sudo curl ca-certificates cloud-init`.
3. Attach `guest-tools.iso` to the VM's CD drive.
4. Run `image/provision.sh` as root inside the VM. It installs Temurin 21 from Adoptium, installs
   `xe-guest-utilities` from the CD and `xenstore-utils` for the guest xenstore client, bakes the
   `jenkins-agent` and `jenkins-agent-ssh-seed` systemd units that read the per-clone data from
   xenstore, resets cloud-init and the machine identity, and drops a `/var/lib/golden-image-ready`
   marker.
5. Shut the VM down.
6. Make it a **template**: `xe vm-param-set uuid=<uuid> is-a-template=true`.

Step 6 is not cosmetic. `tools/reaper.py` refuses to destroy templates, which is the guard that does
not depend on what the image is called.

The reaper now selects on the `xcpng-cloud` marker the plugin stamps into each clone's `other_config`,
so a golden image cannot be caught by a default run however it is named: it carries no marker. The
name-prefix mode still exists for the pre-plugin probe VMs, and there a golden image named
`jenkins-golden-debian` is still one typo away from `reaper.py --apply --prefix jenkins-` if it is an
ordinary VM. That path now refuses a prefix shorter than `jenkins-ci-` without `--force`, and confirms
interactively before destroying, but making the image a template is what makes the question moot.

### Debian 13 and Temurin

Trixie ships `openjdk-21-jre-headless` natively, so Temurin is a choice rather than a necessity
there. It is chosen to pin the JVM vendor and version independently of the distribution, and to
match what the Jenkins project itself installs. On Debian 12 there was no choice at all: bookworm
ships no `openjdk-21`, not even in `bookworm-backports`.

## Per-clone data over xenstore

The clone path delivers each agent's data through **xenstore**, not a cloud-init seed. Before it
starts a clone, the plugin writes these keys under `vm-data/jenkins/` (only `vm-data/*` keys are
pushed into the guest):

| key | what |
|---|---|
| `url` | the controller root URL the agent dials back to |
| `name` | the Jenkins node name the agent registers as |
| `secret` | the node's JNLP secret, an HMAC of the name computable before the node exists |
| `ssh_authorized_key` | **optional**; the operator's OpenSSH public key, present only when the template's SSH key field is set |

`provision.sh` bakes two systemd units that read these at boot. `jenkins-agent.service` runs the
launcher as **root** (a xenstore read is root-only: the `debian` user is denied on
`/proc/xen/xenbus`), writes the secret to a `debian`-owned file kept off the java command line, and
execs the `-webSocket` agent as `debian`. `jenkins-agent-ssh-seed.service` is an independent oneshot
that writes the `debian` user's `authorized_keys` from `ssh_authorized_key` when set, so SSH comes up
even if the agent is flapping.

The SSH key is **per-clone and opt-in**. The operator supplies a **public** key on the agent
template; the private half never touches the plugin or any guest. It is delivered on the same
xenstore channel as the JNLP secret, so `XapiClient` needs no special handling. To revoke, clear the
template field: the next clones read no key and trust nothing (the managed `authorized_keys` is
rewritten each boot). An inbound-only fleet sets no key and stays SSH-closed.

## The from-scratch seed (cloud-init)

`image/seed/` holds the files that make up a NoCloud seed for the from-scratch `import_raw_vdi`
bootstrap: `user-data.tmpl`, `meta-data.tmpl`, and a static `network-config`. The clone path no
longer uses these (it seeds over xenstore, above); they remain for the from-scratch path, whose
freshly imported disk has no persisted network and so needs cloud-init to bring one up.

`meta-data.tmpl` carries `instance-id`, and it must be **unique per clone**. Use the VM's XAPI UUID.

cloud-init re-runs its per-instance modules only when the datasource's `instance-id` differs from
the one cached on disk. Pin a constant id and the first clone works. Then boot the golden image once
with that same seed attached, and from then on **every clone silently skips cloud-init**: no secret
is written, no agent starts, and nothing appears in any log. The VM simply sits there. This is why
`provision.sh` runs `cloud-init clean` before the image is templated.

`network-config` is the third file, and it is the one a **from-scratch `import_raw_vdi` bootstrap
cannot omit**. A pristine cloud image relies on cloud-init to bring the network up; a freshly
imported disk has no persisted network state, so without a `network-config` in the seed the VM boots
to a login prompt with no DHCP, no address, and no agent that can reach the controller. Through
indirect signals (empty serial console, low CPU, MAC absent from the bridge) that looks like a boot
stall, but it is not: the VM is up with no network. Proven on the lab pool: a fresh import that
"stalled" through all those proxies came up as `<hostname> login:` with a real DHCP lease the moment
a `network-config` (`dhcp4: true`, `match: name "e*"`) was added to its seed. A **clone** of the
golden image already carries a working network config on disk and does not strictly need it; the seed
ships it anyway, because DHCP on an ethernet interface is what both paths want.

## Automated bootstrap via `import_raw_vdi`

> **Corrected 2026-07-12.** An earlier version of this section claimed a fresh import "does not boot"
> and that the seed content "gates a pre-kernel OVMF boot." Both are wrong. The VMs booted the whole
> time. A VNC capture of a supposed "staller" showed a Debian login prompt. The real cause is mundane:
> **cloud-init network configuration**, not firmware. Full record in the "Correction" section of
> `golden-image-boot-bisect.md`. What follows is the corrected understanding.

**The import is proven and reliable.** `VDI.create` (`virtual_size` == the raw's exact byte length) +
`PUT /import_raw_vdi?...&format=raw` streams a raw/vhd cloud disk onto the pool byte-perfectly, verified
by a full `export_raw_vdi` sha512 round-trip. ~31 s for 3 GiB over LAN. `physical_utilisation` reads
stale until `SR.scan`, so always scan before trusting VDI stats.

**The imported disk boots.** A fresh import boots normally: OVMF hands off to grub, then the kernel,
systemd, and a login prompt (confirmed by console capture). There is no firmware / NVRAM / Secure-Boot
/ device-model fragility. A full day of contrary theorising was a misdiagnosis from indirect signals;
see the bisect record. What a fresh import lacks is **network**: the genericcloud image relies on cloud-init to bring
it up, and a fresh VM whose cloud-init did not apply a working network config comes up with no DHCP, no
IP, and no guest-reported address. Through indirect proxies (`ttyS0` is on VGA not serial, low idle CPU,
MAC absent from the bridge) that looks like a boot stall, but is not. golden's *clones* work because
they inherit golden's persisted, cloud-init-configured network from disk.

**So a from-scratch bootstrap is ordinary, given one thing: a correct cloud-init network config in the
seed.** `image/seed/` now includes a static `network-config` (`dhcp4: true`, `match: name "e*"`)
beside `user-data` and `meta-data`, and cloud-init applies it as long as the `instance-id` is fresh
and unique per clone (the silent-skip trap above). These are seed *files*; assembling and attaching
the NoCloud seed is still M3, so nothing in the plugin consumes them yet. Verified **by hand** on the
lab pool: a fresh bare `import_raw_vdi` disk (golden's pristine source, no persisted network) plus a
seed carrying this exact `network-config` booted and brought up DHCP (the guest MAC was learned on
the `xenbr0` bridge and the vif showed real bidirectional traffic), where the same disk with a seed
lacking `network-config` came up networkless. When M3 wires the seed, the from-scratch path works;
until then the reliable path is to clone the golden template.

This scopes to a **DHCP** network and a single primary interface (`match: name "e*"` covers `eth0`
and predictable `en*` names). A static-IP or multi-NIC agent would need a different `network-config`;
that is out of v0 scope.

**Options for producing the template:**

- **Clone the golden template** (`VM.clone`). Simplest and reliable: the clone carries golden's working
  network config. This is what the plugin does at provision time anyway.
- **From-scratch `import_raw_vdi`** with a seed carrying a DHCP network config and a unique
  `instance-id`. Ordinary once the network config is right; the import itself is proven.
- **Packer** (`image/xcpng-jenkins-agent.pkr.hcl`, installer path). `packer build` was run against the
  pool: ISO auto-upload to `local-iso`, VM create, and the VNC `boot_command` all work; it blocked only
  on the preseed HTTP fetch when packer ran behind WSL2 NAT. Run packer from a pool-reachable host (a
  golden clone is ideal). Installer-made disks carry a persisted network config, so no clone-time
  cloud-init-network step is needed.
- **The Xen Orchestra backend.** XO does import + VM-create + cloud-init injection + boot as first-class
  operations, the cleanest home for an automated bootstrap if one is built.

Bottom line: `import_raw_vdi` works and the VMs boot. A from-scratch bootstrap needs a correct
cloud-init network config, which is ordinary. Clone golden for the simplest reliable path; use Packer
(from a pool-reachable host) or XO for a from-scratch build.

Methodological note, earned expensively: this section was rewritten after a day of misdiagnosis that
never once looked at the VM console. For a "won't boot" VM on this stack, **capture the screen first**
(`xe vm-list params=dom-id`, then grab the `vnc-<domid>` unix socket on dom0 and run `vncdo capture`) before theorising.

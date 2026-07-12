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
| **cloud-init** | The plugin passes the controller URL, agent name and JNLP secret to each clone through a NoCloud seed. Without cloud-init nothing consumes it. |
| **`xe-guest-utilities`** | Without the guest agent the VM never writes its address to xenstore, and XAPI reports `networks={}` for its whole life. The inbound launcher does not need an address, but `tools/measure_clone.py` and any future SSH launcher do. |
| **a CD-ROM drive** | Where the NoCloud seed is attached. The shipped Debian templates already have one. |

`sshd` and an authorized key are needed **only** if you intend to use an SSH launcher later. The
inbound launcher never opens an SSH connection, so an inbound-only image can drop both.

One image serves both launchers. What differs is what you attach at clone time, not the disk.

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
> the Debian installer via `boot_command` — the installer kernel loaded. The build then blocked at the
> **preseed fetch**, for an environment reason, not a template one: the builder serves
> `image/http/preseed.cfg` over HTTP on the machine running `packer`, and when that machine is behind
> WSL2 NAT the building VM on the LAN cannot reach it (confirmed: dom0 could not reach the packer HTTP
> port on either the WSL or Windows address). This is the same inbound-reachability wall as the M2
> agent. **Run `packer build` from a host the pool can reach on the LAN** — either WSL2 mirrored
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
   `xe-guest-utilities` from the CD, resets cloud-init and the machine identity, and drops a
   `/var/lib/golden-image-ready` marker.
5. Shut the VM down.
6. Make it a **template**: `xe vm-param-set uuid=<uuid> is-a-template=true`.

Step 6 is not cosmetic. `tools/reaper.py` refuses to destroy templates, and it destroys by name
prefix. A golden image named `jenkins-golden-debian` is one typo away from
`reaper.py --apply --prefix jenkins-` if it is an ordinary VM.

### Debian 13 and Temurin

Trixie ships `openjdk-21-jre-headless` natively, so Temurin is a choice rather than a necessity
there. It is chosen to pin the JVM vendor and version independently of the distribution, and to
match what the Jenkins project itself installs. On Debian 12 there was no choice at all: bookworm
ships no `openjdk-21`, not even in `bookworm-backports`.

## The per-clone seed

`image/seed/` holds the templates the plugin renders per clone. They are **not** part of the image.

`meta-data.tmpl` carries `instance-id`, and it must be **unique per clone**. Use the VM's XAPI UUID.

cloud-init re-runs its per-instance modules only when the datasource's `instance-id` differs from
the one cached on disk. Pin a constant id and the first clone works. Then boot the golden image once
with that same seed attached, and from then on **every clone silently skips cloud-init**: no secret
is written, no agent starts, and nothing appears in any log. The VM simply sits there. This is why
`provision.sh` runs `cloud-init clean` before the image is templated.

## Automated bootstrap via `import_raw_vdi` — investigated, not recommended over raw XAPI

The idea of having the plugin build the golden image itself — import a Debian cloud disk, boot it,
let cloud-init run `provision.sh`, then templatize — was investigated end-to-end against the lab pool
(XCP-ng 8.3.0, XAPI 26.1). The detailed bisect is in `golden-image-boot-bisect.md`. Summary:

**Proven and reliable — the import.** `VDI.create` (`virtual_size` == the raw's exact byte length) +
`PUT /import_raw_vdi?...&format=raw` streams a raw/vhd cloud disk onto the pool byte-perfectly. Verified
by a full `export_raw_vdi` read-back whose sha512 matches the source. A 3 GiB image streams in ~31 s
over LAN. Note: `physical_utilisation` reads stale until `SR.scan`; always scan before trusting VDI
stats or booting.

**Booting the imported disk — a working recipe exists, but it is fragile.** A freshly-imported,
pristine Debian genericcloud image on a *lone* disk does not UEFI-boot as a fresh XCP-ng guest: it
reaches `Running`, burns a few CPU-seconds, then stalls pre-kernel (empty `ttyS0`, MAC never on the
bridge). Golden's exact device layout, however, boots reproducibly (4/4 across two sessions):

- `xe vm-install "Debian Bookworm 12"`, **not** `VM.clone`;
- root `deb.raw` at **device 0**, bootable;
- the cloud-init seed as a **data disk at device 1** (RO);
- `guest-tools.iso` as a **CD at device 3**, boot order `cdn`;
- 2 vCPU, set **before the first boot**.

Mechanism: a single disk with an uninitialised UEFI boot-option list is not auto-booted by this pool's
OVMF; a CD in the `cdn` order gives OVMF a device-enumeration path that then reaches the disk's
`\EFI\BOOT\BOOTX64.EFI` removable-media loader. Ruled out by controlled tests (not reasoning): the
import (byte-perfect), the UEFI NVRAM (empty is fine — `varstored` seeds defaults), Secure Boot,
`device-model`, and the image version (golden's source raw is sha512-identical to a fresh download).

**Two fragile levers make this undependable to hand-roll:**

1. *Boot-then-reconfigure poisons it.* Booting once at 1 vCPU (a stall), then setting 2 vCPU and
   restarting, does not recover — only a clean 2-vCPU first boot works. Do not reconfigure a UEFI VM
   between boots.
2. *The seed ISO's build silently gates the OS boot.* With golden's exact recipe and only the seed VDI
   varied: golden's own `cidata.iso` boots (4/4); every seed built here with
   `genisoimage -volid cidata -joliet -rock` stalls (0/3) — heavy **and** a minimal seed byte-for-byte
   the same 374 KB size as golden's. Same tool, same size, differing only in the file-data and
   directory-record bytes. A cloud-init *data disk*'s ISO structure silently determining whether the
   *root* disk boots is a deep, unexplained OVMF quirk. Golden's seed-build command was not recovered.

**Use these instead:**

- **Clone the golden template** (`VM.clone`). Proven to boot repeatedly (this is what the plugin does
  at provision time anyway). If you need a working agent image now, this is it.
- **Build with Packer** (the installer path, `image/xcpng-jenkins-agent.pkr.hcl`). The Debian installer
  sets up the boot entry that a raw cloud image lacks.
- **The Xen Orchestra backend**, if a from-scratch bootstrap is ever pursued. XO imports a cloud disk,
  creates the VM, injects cloud-init, and boots it correctly — it already solves the exact step that is
  unreliable over raw XAPI. This is the recommended home for any future automated bootstrap.

Bottom line: `import_raw_vdi` works, but a Tier-2 "plugin builds its own golden image from a cloud disk"
feature should **not** be built over raw XAPI. Clone the golden image, or defer the bootstrap to XO.

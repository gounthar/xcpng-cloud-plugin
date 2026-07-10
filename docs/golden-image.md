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

> **Honesty about state.** `packer validate` passes, and CI runs it on every push. **`packer build`
> has not been run against a pool.** The unverified part is `boot_command` plus `image/http/preseed.cfg`,
> that is, whether the Debian installer can be driven over XCP-ng's VNC console. Everything durable
> about the image is in `image/provision.sh`, which CI executes for real inside a `debian:13`
> container on every push. If the Packer path fights you, use the manual path below, which was
> performed by hand and does work.

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

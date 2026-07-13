# Why the `import_raw_vdi` bootstrap *appeared* not to boot: a bisect

> **SUPERSEDED (2026-07-12, evening) — the premise below is wrong. The VMs were booting the whole
> time.** A VNC capture of a "stalling" VM shows a full Debian 12 login prompt (`localhost login:`).
> There is no pre-kernel OVMF stall. Every "no boot" verdict in this document was inferred from
> indirect proxies (empty `ttyS0`, low CPU-seconds, MAC-not-in-fdb, no reported IP) that are *equally
> consistent with a booted, idle VM that never brought up networking*. The real issue is
> **cloud-init not configuring the network** on fresh/reassembled VMs, not firmware. See the
> "Correction — we never had a boot problem" section at the end. Read that first; treat the OVMF /
> seed-content / device-model theorising below as a record of a wrong turn, not as findings.

*Recorded 2026-07-12 from the `vates-2` session, against the lab pool (XCP-ng 8.3.0, XAPI 26.1).
Two controlled experiments on real hardware. Every test VM was destroyed by ref in a `finally`;
`jenkins-golden-debian` and `jenkins-ci-m2-probe` were never touched.*

## The claim being tested

The automated Tier-2 bootstrap (`VDI.create` + `import_raw_vdi` + clone the shipped template + assemble)
imports a Debian cloud disk fine but the VM never boots to a kernel: it reaches `Running`, burns
~7 CPU-seconds, `ttyS0` stays empty, and its MAC never enters the bridge. The end-of-day CONTEXT.md
hypothesis was **UEFI NVRAM** — that a fresh VM has no default varstore, so the plan was to learn how
to initialise one.

**That hypothesis is wrong.** So were two follow-on guesses (`xe vm-install` seeds NVRAM; `secureboot=true`
is the fix). The data below refutes all three and isolates the real cause.

## Experiment 1 — is it NVRAM / secureboot?

Cloned `jenkins-golden-debian` (a known-good disk), wiped NVRAM to 0 bytes (the fresh-import state),
and booted it twice changing **only** `platform:secureboot`. `device-model` held at `qemu-upstream-uefi`.

| secureboot | pre-boot NVRAM | result |
|---|---|---|
| `auto` | 0 bytes | **booted**, IP in 48 s |
| `true` | 0 bytes | **booted**, IP in 15 s |

Post-boot NVRAM populated itself to ~49 KB. Conclusions:

- **Empty NVRAM is not the blocker.** XCP-ng's `varstored` seeds default Secure Boot keys at VM start;
  the disk's `\EFI\BOOT\BOOTX64.EFI` removable-boots against them. The "initialise a default varstore"
  plan is chasing a non-problem.
- **`secureboot` auto-vs-true does not matter.** Both boot. And *clearing* NVRAM (CONTEXT attempt 3) is
  not the same as leaving it empty-and-default; that change made things worse for no reason.

## Experiment 2 — the disk-vs-assembly bisect

Assembled a VM the bootstrap's exact way — cloned the **`Debian Trixie 13`** template, inheriting
`device-model=None`, `secureboot=auto`, NVRAM 0 bytes, **nothing changed** — but attached a **copy of
golden's known-good disk** instead of a fresh import.

```
[as-cloned-from-Trixie-template] device-model=None secureboot=auto boot_params={firmware:uefi, order:cdn} NVRAM_bytes=0
round 1: params UNCHANGED  ->  ip=192.168.1.189  ->  BOOTED
```

**It booted on the first try, with the untouched Trixie-template params.** Therefore:

- The **assembly is fine.** `device-model=None` boots. `secureboot=auto` boots. Empty NVRAM boots.
- The only variable left — the one every prior test held constant — is the **disk**. A fresh Debian 13
  Trixie `import_raw_vdi` produces a disk that does not boot; the identical assembly boots a known-good disk.

## Verdict

**It is the imported disk, not the firmware, the varstore, or the VM assembly.** The ESP shape-checks
(GPT signature, `EFI PART`, `BOOTX64.EFI`/`GRUBX64.EFI`/`SHIMX64.EFI` present) all pass because they only
read the front of the disk. The boot chain fails further in — consistent with a **truncated or short
import**: a valid GPT + ESP at the start, with the kernel / root filesystem missing or corrupt after it.

## Follow-up tests to pin the exact import failure (not yet run)

Ordered cheapest-first. These belong to the session that owns the `import_raw_vdi` code.

1. **Bookworm through the same import path.** Import the *Bookworm 12* genericcloud raw that golden uses,
   via `import_raw_vdi`, and boot it.
   - Boots → the failure is Trixie-image-specific (its cloud disk differs in a way that matters).
   - Fails → the failure is in the `import_raw_vdi` mechanics, independent of the image.
2. **Read-back integrity.** After import, `export_raw_vdi` the VDI and compare its `sha512` and byte length
   to the source raw. A mismatch is a truncated/short write, caught directly.
3. **Size and flush invariants.** Assert `VDI.create` `virtual_size` == the raw file size exactly
   (3 GiB = 3221225472 for the current image); confirm the `PUT` returned 200 with the full body sent;
   run `SR.scan` and assert `physical_utilisation` ≈ the full image size **before** starting the VM.
   (The CONTEXT already noted `physical_utilisation` read a stale 9216 bytes until `SR.scan` — a strong
   hint that stats, and possibly the write, were being read before completion.)

## What to stop doing

Do not clear NVRAM, do not set `secureboot=false`, do not switch to BIOS/`qemu-upstream-compat`, and do
not build a default-varstore seeding step. Experiment 1 cleared all of that. The fix is entirely on the
import side.

## Shortcut, if a working image is needed now rather than a working rebuild

`jenkins-golden-debian` is already a template with good NVRAM, and `jenkins-ci-m2-probe` proves a
`VM.clone` of it boots. Cloning the existing golden image sidesteps the import path entirely. The
from-scratch bootstrap only matters for the automated Tier-2 rebuild story.

---

## Update from the `import_raw_vdi` session (2026-07-12)

Ran the follow-up tests. Two of the three hypotheses are now settled, and the conclusion is narrower
and different from "truncated import."

**Test 2 (read-back integrity) — the import is BYTE-PERFECT, not truncated.** `VDI.create` (3 GiB) →
`import_raw_vdi` PUT (my exact `urllib`, file-object body) → `SR.scan` → `export_raw_vdi` → sha512.
Readback == source: full 3221225472 bytes, sha512 identical. So `import_raw_vdi` stores the disk
correctly and my import code is exonerated. `physical_utilisation` read ~3078 MiB after scan (full),
confirming the write completed. (Aside: the stale-stat effect is a general flush race — I also caught
the *local download* reporting 3191431168 bytes mid-flush, then 3221225472 once synced. Stats read
before a write settles lie, on both sides.)

**Test 1 (Bookworm through the same path) — it is NOT Trixie-specific.** Imported the *Bookworm 12*
genericcloud raw (sha512-verified) byte-perfect, assembled the identical clean way (cloned Trixie
template, `device-model=None`, `secureboot=auto`, NVRAM empty, nothing changed), booted. **Same
failure**: ~5 CPU-s, empty `ttyS0`, MAC never in fdb. So a *fresh imported genericcloud image*
(Bookworm or Trixie) does not boot; golden's installed disk (Exp 2) does.

Also tested `secureboot=false` on the imported Trixie disk — still no boot. And walked the ESP's FAT
directory directly (not just `strings`): `\EFI\BOOT\` really contains `BOOTX64.EFI`, `GRUBX64.EFI`,
`MMX64.EFI` as files. So the removable-media fallback exists on the image.

**Could not screenshot the stuck screen.** QMP `screendump` is blocked — XCP-ng runs `qemu-dm`
privilege-dropped in a chroot (`/var/xen/qemu/root-<domid>`), so it can't write the ppm anywhere
dom0-readable. A dom0-side RFB grab off `vnc-<domid>` is the remaining way to see it.

### Narrowed verdict

Not the import, not the assembly, not NVRAM, not Secure Boot, not the ESP fallback file. The disk is
byte-identical to Debian's published cloud image. **A freshly-imported Debian genericcloud image does
not get its bootloader launched by this pool's OVMF, while golden's disk does** — the difference is in
how golden's bootable disk was *prepared*, not in the import path.

### Best next moves (cheapest first)

1. **How was `jenkins-golden-debian` actually built?** It boots on this exact pool with empty NVRAM;
   whatever created it is the working recipe. This is the shortcut and it's a question for whoever
   built it, not another 3 GiB test.
2. **The bootstrap may need to write a UEFI `Boot####` entry** (what an installer's `efibootmgr` does)
   rather than rely on OVMF removable-media boot, which empirically isn't happening for these images.
3. **Try the `generic` (non-cloud) image** vs `genericcloud` — `generic` carries a fuller GRUB/boot
   setup; worth one test if the above don't resolve it.
4. Reinforces the plan's standing note: **XO boots cloud images routinely** (it sets up VM + NVRAM +
   cloud-init correctly). If Tier-2 bootstrap is pursued, XO is the backend that already solved this.

### Later same day — the vm-install recipe was reproduced, and it also fails

The `vates-2` session identified golden's creation path as `xe vm-install` (vs my `VM.clone`), with
`platform:device-model=qemu-upstream-uefi` as the tell. Reproduced it directly:

- `xe vm-install template="Debian Bookworm 12"` → destroy the auto-provisioned disk → attach the
  byte-perfect Bookworm import (device 0 bootable) → VIF → `vm-start`. **Same failure**: 4.9 CPU-s,
  empty `ttyS0`, MAC not in fdb.
- Correction to the tell: `xe vm-install` left `platform:device-model` **empty** on this pool, not
  `qemu-upstream-uefi`. And a separate test setting `device-model=qemu-upstream-uefi` explicitly on
  the `VM.clone` path (NVRAM/secureboot untouched) **also** failed.

So VM creation is fully cleared — vm-install and VM.clone both fail identically; device-model doesn't
matter. **Four hypotheses are now disproven by reproduction** (NVRAM, truncation, secureboot,
device-model/vm-install). The one remaining variable is the **disk content / image version**: golden
(built days ago from a genericcloud raw) boots; every *current* genericcloud raw (Bookworm and Trixie,
byte-perfect) does not.

Leading hypothesis: a Debian `latest/` genericcloud rebuild introduced a UEFI-boot regression on
XCP-ng 8.3 OVMF after golden was made. Decisive tests (for whoever has golden's build history):
(1) re-import the *exact* raw golden was built from and boot it; (2) `export_raw_vdi` golden's disk
and diff its ESP/grub/kernel against a fresh genericcloud raw.

Method note: every advance on this whole thread came from a **controlled reproduction** (the disk-swap
bisect, the sha512 round-trip, the vm-install repro). Every hypothesis argued from params/reasoning was
wrong. Run the path; don't reason about it.

## RESOLVED — it was the device layout at first boot (2026-07-12)

The "image regression" hypothesis above is **disproven**, and the real cause is found and reproduced.
Both the image and the pool are exonerated:

- **The pool never changed.** Every firmware/edk2/varstored/xen/xapi package on the host dates to
  **June 27 or earlier** — before golden was built (July 10). Today's reboot applied nothing.
- **The current Debian image is byte-identical to golden's source** (`content-length 348913664`,
  `Last-Modified 2026-07-06`). Debian did not rebuild it.
- **golden's own source raw** (`/var/tmp/deb.raw` on dom0, the literal file it was built from),
  re-imported and booted **bare**, stalls at 4.9 CPU-s — same as every fresh import.

The July-10 session transcript settles it. golden booted first-try **twice** that day, with **no**
installer, retry, NVRAM edit, grub reinstall, or `efibootmgr`. The only difference from our stalling
reproductions is the **device layout present at first boot**:

| | golden (July 10, boots) | our failing reproductions (July 12) |
|---|---|---|
| device 0 | `deb.raw` root, bootable | `deb.raw` root, bootable |
| device 1 | **cidata seed, imported as a data disk, RO** | *absent* |
| device 3 | **`guest-tools.iso` as a CD** (`xe vm-cd-add`) | *absent* |
| boot order | `cdn` | `cdn` |

**Reproduced today**: golden's exact recipe — `deb.raw` (dev 0) + `cidata.iso` seed (dev 1) +
`guest-tools.iso` CD (dev 3) + 2 vCPU, via `xe vm-install "Debian Bookworm 12"` — **booted**,
`0/ip 192.168.1.139` at ~90s, CPU-s climbing 5.9→21. Drop the seed and CD and the identical raw
stalls. Same raw, same pool, same firmware.

**Mechanism** (strong, not fully isolated): a pristine cloud image on a **lone disk with an
uninitialised UEFI boot-option list** does not get auto-booted by this pool's OVMF. A **CD in the
`cdn` boot order** gives OVMF a device-enumeration path that reaches the disk's
`\EFI\BOOT\BOOTX64.EFI` removable-media loader. Empty NVRAM is fine (see Experiment 1); the missing
piece is the extra boot device, not the varstore.

**Honest caveat — the single lever is not isolated.** A `VM.clone`-Trixie assembly *with* a seed and
CD also stalled, while `vm-install`-Bookworm *with* seed and CD booted — so it may be the combination,
or a difference in the earlier seed/CD attach. The **proven-working recipe is golden's exact one**. To
pin the single factor, bisect: `vm-install` + CD-only, then `vm-install` + seed-only.

### Actionable fix for the `import_raw_vdi` bootstrap

> **SUPERSEDED — see "Correction — we never had a boot problem" at the end.** The CD-in-boot-order
> and seed-ISO-build theories below were a misdiagnosis: there was no boot stall. The only fix a
> from-scratch bootstrap needs is a cloud-init `network-config` in the seed. Kept for the record.

1. Create the VM via `xe vm-install <template>` (not `VM.clone`).
2. Attach the `guest-tools.iso` as a **CD at device 3**, keep boot order `cdn`.
3. Attach the cloud-init seed as a **data disk at device 1**.
4. Then `vm-start`. This exact combination is reproduced-working on this pool today.

This supersedes the earlier "build via the Debian installer" suggestion — unnecessary. The cloud
image boots fine; it just needs the CD (extra boot device) present at first boot.

## Isolated further — the seed ISO build is the fragile lever (import-code session, 2026-07-12)

Reconciling a discrepancy from the RESOLVED section above: my earlier `vm-install`-Bookworm run *with*
seed and CD **stalled**, contradicting the reproduced boot. Chased it down by running golden's exact
recipe with golden's **literal** dom0 artifacts and varying one thing at a time. Two levers found; the
"single lever not isolated" caveat is now largely resolved.

**Confirmed: golden's exact layout boots.** `xe vm-install "Debian Bookworm 12"` + `/var/tmp/deb.raw`
import (dev 0, bootable) + `/var/tmp/cidata.iso` seed (dev 1, RO data disk) + `guest-tools.iso` CD
(dev 3) + 2 vCPU + `cdn` → **booted**, `0/ip 192.168.1.152`. So the recipe is dependable — 4/4 across
both sessions with golden's own artifacts.

**Lever 1 — boot-then-reconfigure poisons the VM.** My original stall booted once at 1 vCPU (the
template default → stall), then set 2 vCPU and restarted → still stalled. A *clean* 2-vCPU first boot
works. Set the vCPU count (and everything else) **before the first `vm-start`**; do not reconfigure a
UEFI VM between boots.

**Lever 2 — the seed ISO's build silently gates the OS boot.** Holding golden's exact recipe fixed and
varying **only the seed VDI**:

| seed at device 1 | boots? |
|---|---|
| golden's `/var/tmp/cidata.iso` | **yes** (this run + 3 prior = **4/4**) |
| mine, `genisoimage -volid cidata -joliet -rock`, 9 KB user-data (provision.sh embedded), 382976 B | **no** (×2, fresh, clean 2-vCPU) |
| mine, same `genisoimage` flags, 284 B user-data, **374784 B — byte-identical size to golden's** | **no** |

So **0/3 for every seed built here, 4/4 for golden's** — p≈0.008 for a coin-flip, i.e. not the boot
race the caveat feared. Not size, not content-length (the minimal seed matches golden's byte size). Both
ISOs are `genisoimage`, no MBR/partition table; they differ only in the file-data and directory-record
regions (first byte diff at 33589). **A cloud-init *data disk*'s ISO structure decides whether OVMF
boots the *root* disk** — a deep, unexplained OVMF block-enumeration quirk.

**Open:** golden's exact `cidata.iso` build command was not in the artifacts. If it is `cloud-localds`,
or `genisoimage`/`xorriso` with different flags (charset, no `-joliet`/`-rock`), that is the missing
reproducible detail — a fresh seed built that way should boot. Until then, a hand-built seed is a
coin-flip-worse-than-coin-flip lever.

### Bottom line for the plugin

> **SUPERSEDED — see "Correction — we never had a boot problem" at the end.** The "two fragile levers"
> below do not exist: there was no boot stall to be fragile about. A from-scratch bootstrap needs only
> a cloud-init `network-config` in the seed, which is now shipped in `image/seed/network-config` and
> verified on the pool. Clone-golden remains the simplest path; XO remains the cleanest automated one.

The recipe boots, but it now rests on two undocumented fragile levers (clean-first-boot ordering, and
an exact-but-unknown seed-ISO build). That is disqualifying for a plugin that must be reliable.
**Clone golden (proven, repeatable) or run the bootstrap through the XO backend** (which builds the seed
and VM correctly). Do not hand-roll a raw-XAPI from-scratch bootstrap on top of these levers. This is
also captured in the tracked `golden-image.md`.

### VFAT sidestep tested — also stalls; it is content, not the filesystem (2026-07-12)

The other session proposed building the NoCloud seed as VFAT instead of ISO9660, in case an OVMF
ISO9660-driver quirk on the dev1 data disk was stalling dev0. Tested with my `provision.sh` content:
`truncate -s 1M` + `mkfs.vfat -n cidata` + `mcopy` the same user-data/meta-data, imported as the dev1
data disk, golden's exact recipe. **Stalled** (8.7 CPU-s flat, no IP). So the filesystem is exonerated
too — ISO9660 **and** VFAT both stall with my content. golden's content boots on either.

Tally: golden's seed content boots (4/4 + the other session's rebuild); mine stalls **0/4** (heavy ISO,
minimal ISO byte-identical in size to golden's, and VFAT). It is the user-data **content**, independent
of build tool, size, and filesystem. Neither session can explain how a cloud-init data disk's *content*
gates a pre-kernel OVMF boot of the root disk — but the reproductions are unanimous. Isolating *which
bytes* of golden's 912-byte user-data matter would need a content bisect (many boots); not pursued.

**This does not change the recommendation — it hardens it.** The from-scratch raw-XAPI bootstrap depends
on a seed whose content must match an unknown-good reference or the OS silently won't boot. Use
`xe vm-install` + clone-golden (reliable), the Packer installer path (robust boot entry), or the XO
backend. Do not hand-roll it.

## Correction — we never had a boot problem (2026-07-12, evening)

We looked at the screen for the first time. It changes everything above.

A fresh bare import of golden's exact source disk (`/var/tmp/deb.raw`, device 0 only — a proven
"staller") was booted, and its qemu VNC framebuffer captured over an SSH-forwarded unix socket
(`-vnc unix:/var/run/xen/vnc-<domid>` → `vncdo capture`). The screen showed:

```
Debian GNU/Linux 12 localhost tty1
localhost login: _
```

**The VM booted.** OVMF → grub → kernel → systemd → a getty login prompt on tty1. There is no
pre-kernel OVMF stall, no firmware bug, no hardware quirk. Every "no boot" in this document was a
misread of indirect signals:

| what we measured | what we concluded | what it actually was |
|---|---|---|
| `ttyS0` empty | not reaching kernel | console is on **tty1 (VGA)**, not serial |
| ~5–9 CPU-s, flat | pre-kernel stall | **booted and idle at a login prompt** |
| MAC never in bridge fdb | not booting | **networking never came up** (no DHCP traffic) |
| no guest-reported IP | stalled | no network → guest agent has nothing to report |
| — | — | hostname is `localhost` → **cloud-init never applied its per-instance config** |

### What the real problem is

The genericcloud Debian image boots fine but relies on **cloud-init** to configure networking. A
fresh import with no working datasource (or a datasource cloud-init doesn't apply) comes up with no
network → no DHCP → no IP → looks, through our proxies, exactly like a boot stall. golden's *clones*
work because they inherit golden's already-configured network state, persisted on disk from its first
cloud-init run. The whole "seed content gates a pre-kernel OVMF boot" saga was really "seed content
gates whether cloud-init brings up the network" — mundane and well-understood, not exotic.

- **Certain** (the screenshot proves it): the VM boots to a login prompt. Not a boot stall.
- **Strong but not yet proven when this was written**: the cause is cloud-init not configuring the
  network (hostname `localhost` + zero network traffic). Inferred, not yet confirmed by logging in and
  reading `ip a` / `cloud-init status`. The confirming test — a fresh import given a proper cloud-init
  DHCP network config — is being run next.

### Methodological lesson (the expensive one)

We reasoned from four indirect proxies for a full day and never once looked at the console. One VNC
capture settled it in seconds. **Look at the actual screen first.** For a "won't boot" VM on this
stack that is: `xe vm-list params=dom-id` → on dom0 `-vnc unix:/var/run/xen/vnc-<domid>` → SSH-forward
the unix socket → `vncdo capture`. Step one, not step fifty.

### Impact on the plugin recommendation

The recommendation to **clone golden** (or drive the bootstrap through the XO backend) still stands —
but for a plain reason now, not a scary one: clones carry a working, cloud-init-configured network;
a from-scratch VM needs a correct cloud-init **network** config to get one. No OVMF fragility, no
unknown-good seed magic. The from-scratch path is ordinary once it's given network config.

### CONFIRMED — the confirming test passed (2026-07-12, evening)

Ran the isolating test. Cloned golden's **UEFI shell only** (params + NVRAM), stripped its disk, and
attached a **fresh bare `import_raw_vdi` of `deb.raw`** (dev0) plus a NoCloud seed carrying an explicit
netplan v2 **DHCP** `network-config` and a distinctive hostname `nettest` (dev1). Same disk image and
same UEFI shell as the proven staller; the only added variable is a cloud-init datasource with network
config. Signals are guest-tools-independent (the bare `deb.raw` has no `xe-guest-utilities`, so
guest-metrics can't report an IP either way — hence measuring at the bridge, not via XAPI):

| signal | staller (bare, no seed) | nettest (fresh disk + DHCP network-config seed) |
|---|---|---|
| console hostname | `localhost login:` | **`nettest login:`** — cloud-init applied its config |
| guest MAC in `ovs-appctl fdb/show xenbr0` | never | **learned (port 54)** |
| `vifN.0` counters | idle | **RX 34 pkts / TX 391 pkts** — real bidirectional traffic |

Both VMs booted to a getty (never in doubt after the screenshot). The difference is entirely
networking: give cloud-init a datasource with a DHCP `network-config` and the fresh import comes up
networked; withhold it and it sits at a login prompt with a dead NIC. **Root cause confirmed: cloud-init
network configuration, not firmware, not the import, not the OVMF/seed-content quirk theorised earlier.**

Practical fix for the from-scratch bootstrap: ship a NoCloud seed that includes a `network-config`
(netplan v2, `dhcp4: true`, matched broadly e.g. `match: {name: "e*"}`), not just user-data/meta-data.
The XO backend already does this; a hand-rolled seed must too.

Strictly, this compares "no seed at all" against "a full NoCloud seed that includes a network-config",
so it proves *a seed with a network-config* brings up the network — not that `network-config` is the
sole causal byte in isolation. The mechanism (cloud-init has no other way to configure the NIC on a
pristine image, and the seedless case stays `localhost`/networkless) makes the attribution safe, and a
strict one-variable test (identical seed ± the `network-config` file) is left as unneeded given the fix
is now shipped and reproduced.

### Independent reproduction with the shipped seed file (2026-07-13)

Re-ran it from the plugin session using the committed `image/seed/network-config` verbatim (comment
header + `---` document-start included), to confirm cloud-init accepts *that exact file*. `vm-install`
from the `jenkins-golden-debian` template (proven UEFI shell) → stripped golden's cloned disk → attached
a fresh `import_raw_vdi` of dom0's `/var/tmp/deb.raw` (dev0) + a `genisoimage` NoCloud seed (user-data +
meta-data with a unique `instance-id` + the repo's `network-config`) as dev1 → `vm-start`. After ~80 s:
guest MAC `02:c0:77:dc:be:ef` **learned in `xenbr0` fdb**, `vif5.0` **rx 31 / tx 164 pkts (2652 / 16811
bytes)** — DHCP happening. VM + both VDIs torn down; pool left clean. So the file as committed works.

### Independent corroboration — Atomic XOA (2026-07-13)

A separate Vates project reached the same conclusion by a different route. Dale Morgan's
**Atomic XOA** (`git.vates.tech/dale.morgan/atomic-xoa`, v0.1) is an AlmaLinux 10 bootc image of
Xen Orchestra. Its first-boot design encodes exactly the finding above:

- `bootc/cloud-init.cfg` pins `datasource_list: [ NoCloud, None ]` — an XO config drive supplies
  "first-boot users, SSH keys, hostnames, **and network configuration**", while `None` lets a bare
  import still boot cleanly.
- `bootc/atomic-xoa-hostname.service` runs `Before=cloud-init-local.service network-pre.target` and
  `ensure-hostname.sh` replaces an empty / `localhost` / `localhost.localdomain` hostname with a
  fallback that NoCloud metadata then overrides. That is the same `localhost login:` vs
  configured-hostname signal we used to tell a seedless boot from a provisioned one.
- The README's import steps explicitly require attaching the VM to a **DHCP-enabled network** and
  **enabling the config drive** before boot.

Different distro (AlmaLinux vs our Debian genericcloud), same root cause: a fresh cloud image needs a
NoCloud datasource carrying a `network-config` to bring its NIC up. Two independent implementations
now say the boot was never the problem.

Its Packer path (`packer/`, using the `vatesfr/xenserver` plugin v0.11.4+ to leave native XCP-ng UEFI
templates, `ip_getter = "tools"`, `keep_vm = "on_success"`, convert-in-place) is a useful reference if
the plugin ever moves toward a Packer-golden workflow instead of clone-golden. As of v0.1 that path is
blocked upstream (osbuild image-builder cannot resolve AlmaLinux 10 x86-64-v2 RPMs for ISO generation,
osbuild/image-builder#2305), so only the VHD-import path is validated today.

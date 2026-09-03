// Builds the Jenkins agent golden image as an XCP-ng template.
//
// The builder is Vates' own: github.com/vatesfr/xenserver, MPL-2.0, verified by its maintainers
// against XCP-ng 8.3 and developed alongside the Xen Orchestra Terraform provider. Using it puts
// this plugin in the toolchain a Vates user already has: Packer builds the image, Terraform builds
// the static fleet, and this plugin does the one thing neither can, which is watch a build queue
// and scale to it.
//
// STATUS. `packer validate` passes and CI runs it on every push. `packer build` runs unattended
// against the lab pool and registers the template: 8m31s on 2026-08-12 and 11m12s on 2026-09-03, no
// keystroke either time. (The second run then failed writing its XVA to a full drive and took the
// template with it, which is #195 and is why `format` defaults to "none" below. It reached the
// export step unattended.) Both served the preseed from a LAN host via preseed_url; Packer's own HTTP server is
// still unexercised from a build host the installer VM can reach.
//
// Everything durable about the image lives in image/provision.sh, which CI executes on every push
// inside a debian:13 container. docs/golden-image.md has the full write-up and the manual path.
//
//   export PKR_VAR_remote_host=192.168.1.87
//   export PKR_VAR_remote_username=root
//   export PKR_VAR_remote_password=...
//   packer init  image/
//   packer build image/

packer {
  required_plugins {
    xenserver = {
      version = "= 0.11.4"
      source  = "github.com/vatesfr/xenserver"
    }
  }
}

variable "remote_host" {
  type        = string
  description = "XCP-ng pool master. From PKR_VAR_remote_host."
  sensitive   = true
  default     = null
}

variable "remote_username" {
  type      = string
  sensitive = true
  default   = "root"
}

variable "remote_password" {
  type        = string
  description = "XAPI password. From PKR_VAR_remote_password. Never commit this."
  sensitive   = true
  default     = null
}

variable "sr_name" {
  type        = string
  description = "SR for the image's disk. A file-based SR (ext, nfs) gives VM.clone copy-on-write; LVM full-copies instead."
  default     = "Local storage"
}

variable "sr_iso_name" {
  type    = string
  default = "local-iso"
}

variable "template_name" {
  type        = string
  description = "The template this build produces. XcpngCloud's template field points at this name."
  default     = "jenkins-agent-debian13"
}

// Pinned to a point release on purpose. Debian moves point releases out of /release into /archive,
// so an unpinned URL turns a reproducible build into a time-dependent one.
variable "debian_version" {
  type    = string
  default = "13.4.0"
}

// Where the installer fetches the preseed from.
//
// WHO FETCHES IT, because the answer decides everything below: debian-installer, running inside the
// VM this build is creating, from the `url=` that boot_command types at the isolinux prompt. So what
// has to reach the HTTP server is the installer VM's network. Not dom0's, and not XAPI's. On this
// single-host lab those are one network and the distinction never shows; on a pool whose management
// network is separate from its VM network they are different questions with different answers.
//
// Empty means "use Packer's own built-in server", bound on the machine running Packer. That is the
// simplest correct answer on a build host sitting on the pool's VM network with nothing in between,
// and it is worth trying first there. No build here has confirmed it: the runs that left it empty
// were behind WSL2's NAT, and every run since has set the variable.
//
// It is the wrong answer when Packer sits behind NAT, WSL2 in its default mode being the case this
// project keeps hitting. Packer still starts its server and still advertises an address, so nothing
// looks wrong: the installer boots, tries to fetch, cannot, and stalls on a screen that says
// nothing. The build then sits until ssh_wait_timeout with a log that only ever says "Wait for VM's
// IP to become known to us". Measured here: Packer advertised 192.168.1.145:8000 while its server
// was bound inside WSL at 172.18.157.37:8000, and neither address answered. That probe was run from
// dom0 rather than from a VM, which on this lab is the same network; on a segmented pool it would be
// the wrong test, for the reason above.
//
// Then set this to a URL the installer VM can reach, and serve image/http/preseed.cfg there.
variable "preseed_url" {
  type        = string
  description = "Full URL to preseed.cfg. Empty uses Packer's built-in HTTP server, which the installer VM must be able to reach."
  default     = ""
}

// What the build leaves on the machine running Packer, on top of the template it registers on the
// pool. "none" writes no artifact; it still creates output_directory, see below.
//
// The builder's export step runs LAST, after the VM has already been turned into a template
// (builder/xenserver/iso/builder.go orders StepSetVmToTemplate before StepExport), and it halts the
// whole build if it cannot write. So a default of "xva" means an eleven-minute build that installed,
// provisioned and registered the template correctly is reported as a total failure, and the teardown
// destroys the template, because a download afterwards ran out of disk. Measured on 2026-09-03: the
// XVA write failed with `input/output error` at 1.05 GiB free and no template survived. See #195.
//
// The deliverable here is the template record on the pool, which is what XcpngCloud clones. The XVA
// is a side effect costing an image-sized write, and docs/golden-image.md already explains why this
// project does not ship one. So the default is "none", and anyone who does want a portable file sets
// this and gets it.
//
// Accepted values, read from the builder at v0.11.4 (common_config.go:208): "xva", "xva_compressed",
// "vdi_raw", "vdi_vhd", "none". Note "xva_compressed" is accepted by the code but named in neither
// the builder's docs nor its own validation error.
variable "format" {
  type        = string
  description = "Artifact written to output_directory: xva, xva_compressed, vdi_raw, vdi_vhd, or none."
  default     = "none"

  validation {
    condition     = contains(["xva", "xva_compressed", "vdi_raw", "vdi_vhd", "none"], var.format)
    error_message = "Format must be one of xva, xva_compressed, vdi_raw, vdi_vhd, none."
  }
}

// Where that artifact lands. Relative paths resolve against the working directory, and every other
// path in this template (http_directory, the file provisioner source, the shell provisioner script)
// is relative to the repository root, so a build has to run from there. That put the XVA inside the
// checkout with no way to send it elsewhere, which is the other half of #195.
//
// The builder creates this directory whether or not it writes anything into it
// (StepPrepareOutputDir runs unconditionally), so expect an empty directory at format = "none".
variable "output_directory" {
  type        = string
  description = "Directory for the exported artifact. Created even when format is none."
  default     = "packer-jenkins-agent"
}

variable "iso_name" {
  type        = string
  default     = ""
  description = <<-EOT
      Name of an ISO VDI already present in sr_iso_name. Set it and the builder
      uses that VDI in place: no download, no upload. Leave empty to have the ISO
      fetched from iso_url and uploaded, which is the behaviour without this.

      Useful where uploading is not possible: an SR of type `iso` is populated by
      dropping the file on the share and running `xe sr-scan`, and import_raw_vdi
      against one fails with VDI_IO_ERROR.

      NOTE: this does not stop the builder from destroying the ISO VDI at cleanup,
      despite what the builder's PreserveVdi flag suggests. Reported measured on
      XCP-ng 8.3 against a shared NFS ISO SR: the file is removed on both the
      success and the failure path. Not yet reproduced here, and not yet filed.
    EOT
}

source "xenserver-iso" "jenkins-agent" {
  iso_url = "https://cdimage.debian.org/cdimage/archive/${var.debian_version}/amd64/iso-cd/debian-${var.debian_version}-amd64-netinst.iso"
  // From that directory's SHA256SUMS. Never hand-written.
  iso_checksum = "sha256:0b813535dd76f2ea96eff908c65e8521512c92a0631fd41c95756ffd7d4896dc"
  iso_name     = var.iso_name

  remote_host     = var.remote_host
  remote_username = var.remote_username
  remote_password = var.remote_password

  sr_name        = var.sr_name
  sr_iso_name    = var.sr_iso_name
  tools_iso_name = "guest-tools.iso"

  // Shipped by XCP-ng 8.3. Confirmed present on the lab pool alongside Bookworm 12.
  clone_template = "Debian Trixie 13"

  vm_name         = var.template_name
  vm_description  = "Jenkins inbound agent: Debian 13, Temurin 21, xe-guest-utilities, cloud-init"
  vm_memory       = 2048
  vcpus_atstartup = 2
  disk_size       = 10240

  // Debian netinst is driven by preseed, not by cloud-init autoinstall, so this types kernel
  // arguments at the isolinux boot prompt over VNC. The upstream Ubuntu example uses floppy_files
  // with cloud-init, which does not apply here. The VM is legacy BIOS (no firmware/device-model
  // override below), so the ISO boots isolinux and ESC at its menu drops to a `boot:` prompt.
  //
  // Two things were wrong here before, and together they meant no kernel argument ever reached the
  // installer. Confirmed by screenshotting the VNC console rather than inferring from the fact that
  // the build hung.
  //
  // First, boot_wait was 5s, which is shorter than this host takes to get the isolinux menu up. The
  // keystrokes landed in the menu instead of at a prompt and were read as menu shortcuts, which
  // selected the accessible/speech-synthesis entry. It probed for a sound card for 20s, found none,
  // and sat on "Press enter to continue anyway" until the 60m SSH timeout. The VM burned 19s of CPU
  // in 48 minutes and never touched the network, so from outside it looked like a slow install.
  //
  // Second, the parameters were typed with no kernel label in front of them. At a `boot:` prompt the
  // first word is the label to boot, so `auto=true` was being offered as a label rather than as a
  // parameter. `auto` is a real Debian label and already implies auto=true and priority=critical,
  // so naming it is both correct and shorter.
  http_directory = "image/http"
  // Generous on purpose: too long only costs seconds, too short costs a 60-minute timeout and a
  // hang that does not look like a boot problem.
  boot_wait = "20s"
  boot_command = [
    "<esc><wait>",
    "auto ",
    "url=${var.preseed_url != "" ? var.preseed_url : "http://{{ .HTTPIP }}:{{ .HTTPPort }}/preseed.cfg"} ",
    "<enter>"
  ]

  ssh_username     = "debian"
  ssh_password     = "debian"
  ssh_wait_timeout = "60m"

  output_directory = var.output_directory
  format           = var.format
  keep_vm          = "on_success"
}

build {
  sources = ["source.xenserver-iso.jenkins-agent"]

  # Operator trust anchors. The directory is committed empty; anything dropped
  # into it before a build is installed by provision.sh. Uploading the
  # directory rather than a variable means no path juggling and no default
  # that has to point somewhere.
  provisioner "file" {
    source      = "image/ca-certificates"
    destination = "/tmp"
  }

  // Everything durable about the image. Exercised by CI on every push, in a debian:13 container.
  provisioner "shell" {
    script = "image/provision.sh"
    # The preseed grants debian passwordless sudo, so no password needs piping in. provision.sh
    # then locks that sudo grant during template cleanup, so it does not survive into clones.
    execute_command = "sudo bash '{{ .Path }}'"
  }
}

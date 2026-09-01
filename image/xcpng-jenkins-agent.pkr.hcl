// Builds the Jenkins agent golden image as an XCP-ng template.
//
// The builder is Vates' own: github.com/vatesfr/xenserver, MPL-2.0, verified by its maintainers
// against XCP-ng 8.3 and developed alongside the Xen Orchestra Terraform provider. Using it puts
// this plugin in the toolchain a Vates user already has: Packer builds the image, Terraform builds
// the static fleet, and this plugin does the one thing neither can, which is watch a build queue
// and scale to it.
//
// STATUS. `packer validate` passes and CI runs it on every push. `packer build` HAS now been run
// against the lab pool: the builder uploaded the netinst ISO, created the VM, connected VNC, and
// drove the Debian installer via boot_command (the installer kernel loaded). It then blocked at the
// preseed HTTP fetch, an environment wall, not a template one: packer serves the preseed on the
// host running it, unreachable from the pool when that host is behind WSL2 NAT. Run `packer build`
// from a pool-reachable host (WSL2 mirrored networking, a netsh portproxy, or a golden-image clone
// as the builder). Everything durable about the image lives in image/provision.sh, which CI executes
// on every push inside a debian:13 container. docs/golden-image.md has the full write-up and the
// manual path, which was performed by hand and does work.
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

// Where the installer fetches the preseed from. Empty means "use Packer's own HTTP server", which is
// right whenever the machine running Packer is reachable from the pool on the LAN.
//
// It is not reachable when Packer runs behind NAT, which includes WSL2 in its default mode. Packer
// still starts its server and still advertises an address, so nothing looks wrong: the installer
// boots, tries to fetch, cannot, and stalls on a screen that says nothing. The build then sits until
// ssh_wait_timeout with a log that only ever says "Wait for VM's IP to become known to us".
//
// Measured on this lab from dom0: Packer advertised 192.168.1.145:8000 while its server was bound
// inside WSL at 172.18.157.37:8000, and neither address answered from the pool.
//
// Set this to a URL the pool can reach and serve image/http/preseed.cfg there yourself.
variable "preseed_url" {
  type        = string
  description = "Full URL to preseed.cfg. Empty uses Packer's built-in HTTP server (needs LAN reachability)."
  default     = ""
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

  output_directory = "packer-jenkins-agent"
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

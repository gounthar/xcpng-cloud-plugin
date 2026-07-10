// Builds the Jenkins agent golden image as an XCP-ng template.
//
// The builder is Vates' own: github.com/vatesfr/xenserver, MPL-2.0, verified by its maintainers
// against XCP-ng 8.3 and developed alongside the Xen Orchestra Terraform provider. Using it puts
// this plugin in the toolchain a Vates user already has: Packer builds the image, Terraform builds
// the static fleet, and this plugin does the one thing neither can, which is watch a build queue
// and scale to it.
//
// STATUS. `packer validate` passes. `packer build` has NOT been run against a pool. Everything
// durable about the image lives in image/provision.sh, which CI executes on every push inside a
// debian:13 container. What remains unverified here is boot_command and the preseed, i.e. whether
// the Debian installer can be driven over XCP-ng's VNC console. docs/golden-image.md documents the
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

source "xenserver-iso" "jenkins-agent" {
  iso_url = "https://cdimage.debian.org/cdimage/archive/${var.debian_version}/amd64/iso-cd/debian-${var.debian_version}-amd64-netinst.iso"
  // From that directory's SHA256SUMS. Never hand-written.
  iso_checksum = "sha256:0b813535dd76f2ea96eff908c65e8521512c92a0631fd41c95756ffd7d4896dc"

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

  // UNVERIFIED. Debian netinst is driven by preseed, not by cloud-init autoinstall, so this types
  // kernel arguments at the isolinux prompt over VNC. The upstream Ubuntu example uses floppy_files
  // with cloud-init, which does not apply here.
  http_directory = "image/http"
  boot_wait      = "5s"
  boot_command = [
    "<esc><wait>",
    "auto=true ",
    "priority=critical ",
    "url=http://{{ .HTTPIP }}:{{ .HTTPPort }}/preseed.cfg ",
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

  // Everything durable about the image. Exercised by CI on every push, in a debian:13 container.
  provisioner "shell" {
    script          = "image/provision.sh"
    execute_command = "echo 'debian' | sudo -S bash '{{ .Path }}'"
  }
}

# Security Policy

## Reporting a vulnerability

Please report suspected vulnerabilities through
[GitHub's private vulnerability reporting](../../security/advisories/new) on this repository, or by
email to `gounthar@gmail.com`. Do not open a public issue for one.

Expect an acknowledgement within a few days. This is a spike maintained by one person in the open;
there is no security team behind it and no service-level commitment, and saying so plainly seems
better than implying a response process that does not exist.

**The Jenkins security team's process does not cover this plugin.** That process
([reporting under the SECURITY project in the Jenkins issue tracker](https://www.jenkins.io/security/))
applies to plugins hosted in the `jenkinsci` organisation and distributed through the update
centre. This one is neither: releases here are GitHub releases carrying an `.hpi`, installed by
hand. If the plugin is ever donated, the Jenkins process takes over and this file goes away.

## Supported versions

The most recent [release](../../releases) only. There are no maintenance branches and no backports;
a fix ships in the next release cut from `main`.

## Already known, and not a report

These are documented in [Security notes](README.md#security-notes) and are consequences of how the
plugin currently works rather than undiscovered weaknesses:

- **The agent connection secret is briefly readable from the VM record.** The plugin writes it into
  `xenstore-data` so the guest can read it at first boot, which keeps it off any command line and
  out of the golden image. Until the agent connects — roughly the boot window — anyone with XAPI
  read access to the pool can read it. The plugin removes that key as soon as the agent comes
  online.
- **Anything with XAPI credentials for the pool can do what those credentials allow.** The plugin
  stores a credential ID and resolves the credential at point of use; the account you give it
  should be scoped to what provisioning needs.
- **A clone gets an SSH key only if one is seeded into `xenstore-data` before first boot.** No
  maintenance key is baked into the `jenkins-agent-debian13` template.

New findings about the reach or duration of any of these are worth reporting. So is anything
touching the `Descriptor` web endpoints, credential handling, or the teardown path.

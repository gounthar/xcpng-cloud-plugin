# XCP-ng Cloud

A Jenkins [Cloud](https://www.jenkins.io/doc/book/using/using-agents/) plugin that provisions
ephemeral build agents on an [XCP-ng](https://xcp-ng.org/) pool. When the build queue needs
capacity, the plugin fast-clones a golden-image VM, the agent connects back to the controller,
one build runs on a pristine machine, and the VM and its disks are destroyed when it goes idle.

## Why this exists

XCP-ng users already have a declarative provisioning path through
[Terraform](https://registry.terraform.io/providers/vatesfr/xenorchestra/latest). Terraform
describes a desired state and reconciles to it. It cannot watch a Jenkins build queue, scale agents
to match the pending workload, and tear them down when the work drains. That feedback loop, from
queue depth to running VMs and back to zero, is what a Jenkins `Cloud` plugin does and what a
desired-state tool structurally cannot. This plugin fills that one gap.

## Status

This is an early spike, developed and proven against a single-host lab pool. The full loop works
end to end: `provision()` clones the golden image, seeds the agent secret over xenstore, the agent
boots and connects over an inbound WebSocket, a build runs, and the VM is reaped with its disks.
Provisioning is reported to the [cloud-stats](https://plugins.jenkins.io/cloud-stats/) plugin, and
the whole cloud can be configured through the UI or
[Configuration as Code](https://plugins.jenkins.io/configuration-as-code/).

It has not been run at scale, on multiple hosts, or against a shared production pool. Treat it as a
working proof of concept, not a supported plugin. See [Not in this version](#not-in-this-version)
for the deliberate scope cuts.

## How it works

1. A build enters the queue with a label that matches a configured template.
2. `XcpngCloud.provision()` clones the named golden image on the pool. On a file-backed storage
   repository the clone is copy-on-write and returns in under a second; no disk is copied up front.
3. The clone starts. The plugin writes the agent name and the JNLP connection secret into the
   guest's xenstore, so the secret never appears on a command line, in a disk image, or in the VM
   configuration.
4. A systemd unit baked into the golden image reads the secret from xenstore and launches the
   Jenkins agent, which dials out to the controller over an inbound WebSocket. No inbound
   reachability to the agent, and no SSH credential, is required.
5. The build runs on the fresh VM. When the agent finishes its build or sits idle past the timeout,
   the VM and its disks are destroyed on a background thread.

Single-use is intentional: every build gets a clean machine, and nothing survives between builds.

## Requirements

- An XCP-ng pool reachable over XAPI (developed against XCP-ng 8.3, XAPI 26.1).
- A golden-image VM on that pool, prepared as described below.
- A Jenkins controller on the 2.555.x line or newer. The controller and the agent must run the same
  Java major version; the baseline here is Java 21 (Temurin on the agent side).
- XAPI credentials stored in Jenkins as a username/password credential.

## Configuration

### Through the UI

1. **Manage Jenkins** then **Clouds**, and add a new **XCP-ng Cloud**.
2. Set the **Pool URL** (for example `https://192.168.1.87`) and select the XAPI **Credentials**.
3. Tick **Trust self-signed certificate** only if the pool presents a self-signed certificate. See
   [Security notes](#security-notes) before enabling it.
4. Add one or more **Templates**. Each template names a golden image, a label expression, and the
   shape of the agents cloned from it.
5. Use **Test connection** to confirm the controller can authenticate against the pool.

### Through Configuration as Code

Every field binds reflectively, so the plugin needs no extra glue for JCasC. A minimal
configuration:

```yaml
jenkins:
  clouds:
    - xcpng:
        name: "xcpng-lab"
        poolUrl: "https://192.168.1.87"
        credentialsId: "xcpng-root"
        trustSelfSigned: false
        maxInstances: 3
        idleMinutes: 10
        templates:
          - templateName: "jenkins-agent-debian13"
            labelString: "xcpng-linux"
            numExecutors: 2
            numCpus: 4
            memoryMb: 8192
            sshAuthorizedKey: "ssh-ed25519 AAAA...replace-with-your-public-key you@example"
```

Set `trustSelfSigned: true` only for a lab pool whose self-signed certificate you cannot replace;
see [Security notes](#security-notes).

The exported configuration never contains the XAPI password; it holds only the credential ID, which
the controller resolves at the point of use.

### Configuration reference

Cloud fields:

| Field | Symbol | Description |
| --- | --- | --- |
| Name | `name` | Display name for this cloud. |
| Pool URL | `poolUrl` | Base URL of the XCP-ng pool master, for example `https://192.168.1.87`. Do not embed credentials in the URL. |
| Credentials | `credentialsId` | ID of the username/password credential used for XAPI login. |
| Trust self-signed certificate | `trustSelfSigned` | Skip TLS verification against the pool. Off by default. |
| Max instances | `maxInstances` | Upper bound on agents this cloud provisions at once. |
| Idle minutes | `idleMinutes` | Minutes an agent may sit idle before it is reclaimed. Optional; defaults to 10. A build normally reaps its agent on completion (single-use), so this is only the safety net for a clone that connects but never receives work. A non-positive value is clamped to the default. |
| Templates | `templates` | One or more agent templates (see below). |

Template fields:

| Field | Symbol | Description |
| --- | --- | --- |
| Template name | `templateName` | Name of the golden-image VM or template on the pool to clone. |
| Label | `labelString` | Label expression a build must request to be matched to this template. |
| Executors | `numExecutors` | Executors per agent. |
| vCPUs | `numCpus` | Virtual CPUs for the cloned VM. Defaults to 2. |
| Memory (MiB) | `memoryMb` | Memory for the cloned VM in mebibytes (MiB). Defaults to 2048. |
| SSH authorized key | `sshAuthorizedKey` | A public key seeded into the clone over xenstore. Paste a public key only; a pasted private key is rejected. |

## Preparing a golden image

The plugin clones an existing VM or template on the pool. That image must be able to run a Jenkins
agent on first boot with no manual steps. It needs, at minimum:

- The XCP-ng guest tools, so the guest can read xenstore.
- A JRE matching the controller's Java major version (Temurin 21 here).
- A systemd unit that reads the agent name and secret from xenstore and launches the agent over an
  inbound WebSocket.

The `image/` directory holds the recipe used for the lab image: `image/provision.sh` installs and
enables the units, and `image/xcpng-jenkins-agent.pkr.hcl` is a Packer template for building the
image from scratch. Adapt these to your own distribution and controller URL.

## Security notes

- **Credentials are never stored in the plugin configuration.** Only the credential ID is persisted;
  the XAPI password is resolved from the Jenkins credentials store when a connection is opened.
- **`trustSelfSigned` disables TLS verification** against the pool and exposes the XAPI session to a
  man-in-the-middle. Use it only on a trusted network against a pool whose certificate you cannot
  replace, and prefer installing the pool's certificate into the controller's trust store instead.
- **Only public SSH keys belong in `sshAuthorizedKey`.** The field seeds a public key into each
  clone; the form rejects anything that looks like a private key. The private half must never reach
  an agent.
- **`Test connection` requires the overall administer permission**, so a lower-privileged user
  cannot use it to probe arbitrary hosts.

## Not in this version

Deliberately out of scope for the spike: multiple templates matched by a single build, Windows
agents, warm pools of pre-booted agents, pinning agents to specific hosts, per-label instance
accounting, and controller-initiated SSH launching. The idle timeout is currently a fixed constant
rather than a configurable field. These are cuts, not dead ends.

## Building and testing

```sh
mvn -B -ntp verify
```

`verify` compiles the plugin, runs the test suite, runs SpotBugs, and packages the `.hpi`. The
tests use an in-memory fake of the hypervisor client and recorded XAPI fixtures, so they need no
live pool. To run a local Jenkins with the plugin loaded:

```sh
mvn hpi:run -Dhost=0.0.0.0 -Dport=8080
```

Jenkins is then available at `http://localhost:8080/jenkins`.

## License

MIT. See [`LICENSE`](LICENSE); it is also declared in `pom.xml`.

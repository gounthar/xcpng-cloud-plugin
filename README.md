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
3. The clone starts. The plugin writes the agent name and the JNLP connection secret into the VM
   record's `xenstore-data`, where the guest reads them at first boot. This keeps the secret off any
   command line and out of the golden image, but `xenstore-data` is part of the VM record: until the
   agent connects, the secret is readable by anyone with XAPI read access to the pool. The plugin
   removes it as soon as the agent comes online. See [Security notes](#security-notes) for the
   scoping of that window.
4. A systemd unit baked into the golden image reads the secret from xenstore and launches the
   Jenkins agent, which dials out to the controller over an inbound WebSocket. No inbound
   reachability to the agent, and no SSH credential, is required.
5. The build runs on the fresh VM. When the agent finishes its build or sits idle past the timeout,
   the VM and its disks are destroyed on a background thread.

Single-use is intentional: every build gets a clean machine, and nothing survives between builds.

Agents serve only builds whose label expression matches their template's labels. A build with no label
expression is never routed here, and never causes a clone: these VMs are single-use, so an unlabeled
job landing on one would consume and destroy it, and a warm spare taken that way sends the labeled
build it was held for back to a cold clone. That is also why a template must carry at least one label
— a template with none could never be reached at all.

Each agent therefore runs exactly one executor, and this is not configurable. A second executor would
put two builds on one VM, and the reap fires when a build completes, so the first to finish would
destroy the VM under the other. Scale throughput with `maxInstances` (more clones), not with executors
per clone.

### Warm pool

A template with a **Warm pool size** above 0 keeps that many pre-booted, idle agents ready, so a queued
build lands on a live executor instead of waiting for a cold clone. A background task reconciles the pool
roughly once a minute, so a spare appears up to a minute after you save the configuration, and after a
spare is consumed by a build its replacement is cloned within about a minute. Warm agents are still
single-use and count against `maxInstances`. A spare that boots but never connects is still reclaimed by
the idle timeout.

The "warm" marker is intentionally not persisted, so a controller restart costs every existing spare its
exemption. That churn is deliberate: it guarantees a VM that has already run a build can never be revived
as a spare. It does not happen all at once, though, and the order is worth knowing before you size a
pool. The old spare reconnects as an ordinary agent, the maintainer sees no warm spares and clones a
replacement within about a minute, and only then does the idle net reclaim the old one, after
`idleMinutes` has elapsed. Both run at the same time in between.

**So a restart can temporarily hold twice the VMs a template is configured for, for up to the length of
the idle timeout.** Two things bound that. The replacement is only cloned if `maxInstances` has room for
it, so a cloud already at its cap simply waits instead of doubling. And the old spare is an ordinary
single-use agent, so a build landing on it destroys it and frees the slot early; the full idle timeout is
the worst case, not the normal one. While the window is open a cloud sitting at `maxInstances` provisions
nothing new, though builds still run, since both agents are idle and carry the template's labels.
Measured on the lab pool with `maxInstances` 2 and `idleMinutes` 10: replacement cloned 65 s after the
restart, old spare destroyed 10 min 45 s after it reconnected.

## Requirements

- An XCP-ng pool reachable over XAPI (developed against XCP-ng 8.3, XAPI 26.1).
- A golden-image VM on that pool, prepared as described below.
- A Jenkins controller on the 2.555.x line or newer. The controller and the agent must run the same
  Java major version; the baseline here is Java 21 (Temurin on the agent side).
- The Jenkins root URL (**Manage Jenkins** then **System**) must be set and reachable over HTTP(S) from
  the network the clones boot on. Agents dial out to it over an inbound WebSocket; if it is unset or
  unreachable, a clone never connects and is destroyed after the connect timeout.
- XAPI credentials stored in Jenkins as a username/password credential.

## Configuration

### Through the UI

1. **Manage Jenkins** then **Clouds**, and add a new **XCP-ng Cloud**.
2. Set the **Pool URL** (for example `https://192.168.1.87`) and select the XAPI **Credentials**.
3. Leave **Certificate fingerprint** empty and press **Test connection**. If the pool's certificate is
   signed by a CA the controller already trusts, it connects and there is nothing more to do. XCP-ng
   ships a self-signed certificate, so the usual answer is that the result shows you the SHA-256
   fingerprint the pool presented. Check it against the pool itself, then paste it into the field. See
   [Security notes](#security-notes).
4. Add one or more **Templates**. Each template names a golden image, the labels its agents serve, and
   the shape of the agents cloned from it. At least one label is required, and labels are how builds
   reach these agents: give the jobs you want on XCP-ng a matching label expression.
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
        certificateFingerprint: ""
        maxInstances: 3
        idleMinutes: 10
        templates:
          - templateName: "jenkins-agent-debian13"
            labelString: "xcpng-linux"
            numCpus: 4
            memoryMb: 8192
            sshAuthorizedKey: "ssh-ed25519 AAAA...replace-with-your-public-key you@example"
```

**Upgrading from a version with "Trust self-signed certificate":** that option is gone, and there is
no automatic replacement for it. A cloud saved with it and no fingerprint logs a warning naming the
cloud at startup and then fails to connect, so provisioning stops until an administrator opens the
cloud's configuration, presses **Test connection**, and saves the fingerprint it reports. That is
deliberate rather than a migration oversight: the old setting accepted any certificate from any host,
and silently carrying that forward would have preserved the exact weakness this replaces.

Leave `certificateFingerprint` empty for a pool whose certificate chains to a CA the controller
trusts. For a stock pool, set it to that pool's SHA-256 certificate fingerprint, which you can read on
the host with `openssl x509 -in /etc/xensource/xapi-ssl.pem -noout -fingerprint -sha256`. Colons are
optional and case does not matter. See [Security notes](#security-notes).

The exported configuration never contains the XAPI password; it holds only the credential ID, which
the controller resolves at the point of use.

### Configuration reference

Cloud fields:

| Field | Symbol | Description |
| --- | --- | --- |
| Name | `name` | Display name for this cloud. |
| Pool URL | `poolUrl` | Base URL of the XCP-ng pool master, for example `https://192.168.1.87`. Do not embed credentials in the URL. |
| Credentials | `credentialsId` | ID of the username/password credential used for XAPI login. |
| Certificate fingerprint | `certificateFingerprint` | SHA-256 fingerprint of the certificate the pool is expected to present, with or without colons. Empty means ordinary verification against the controller's JVM trust store, which is right for a CA-signed certificate; a stock XCP-ng pool is self-signed and needs its fingerprint here. Once set, only that exact certificate is accepted. |
| Max instances | `maxInstances` | Upper bound on agents this cloud provisions at once. |
| Idle minutes | `idleMinutes` | Minutes before an agent that has not completed a build is reclaimed. Optional; defaults to 10. A build normally reaps its agent on completion (single-use), so this covers the clones that never get that far: one that connects but is never given work, **and one that has not connected yet**. That second case is why the value **must exceed the time a clone takes to boot and connect** — an agent that has never come online holds no idle exemption, so too short a value reclaims it mid-boot and no build ever runs (see [Troubleshooting](#troubleshooting)). A non-positive value is clamped to the default. Does not apply to online warm-pool spares that have not yet run a build; those are held ready regardless (see [How it works](#how-it-works)). |
| Templates | `templates` | One or more agent templates (see below). |

Template fields:

| Field | Symbol | Description |
| --- | --- | --- |
| Template name | `templateName` | Name of the golden-image VM or template on the pool to clone. |
| Labels | `labelString` | Space-separated labels the agents cloned from this template will carry. A build is matched to this template when its label expression is satisfied by them. Required: agents are exclusive to their labels, so a template without any is unreachable. |
| vCPUs | `numCpus` | Virtual CPUs for the cloned VM. Defaults to 2. |
| Memory (MiB) | `memoryMb` | Memory for the cloned VM in mebibytes (MiB). Defaults to 2048. |
| Warm pool size | `minInstances` | Pre-booted idle agents of this template to keep hot, so a queued build connects to a ready executor instead of waiting for a cold clone. Defaults to 0 (off). Warm agents are still single-use (one build each) and count against `maxInstances`. See [Warm pool](#warm-pool). |
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
image from scratch. Adapt these to your own distribution. The image is controller-agnostic by design,
which is a feature: the launcher reads the controller URL, agent name, and secret per clone from
xenstore at boot, so one golden image serves any controller and there is nothing controller-specific to
bake in.

[`docs/golden-image.md`](docs/golden-image.md) is the authoritative guide, with the full requirements
table, the Packer workflow and its honest status, and the produced template name
(`jenkins-agent-debian13`, matching the Configuration as Code example above).

## Security notes

- **Credentials are never stored in the plugin configuration.** Only the credential ID is persisted;
  the XAPI password is resolved from the Jenkins credentials store when a connection is opened.
- **The JNLP secret is delivered through the VM record's `xenstore-data`.** It is not hidden from the
  pool: until the agent connects, anyone with XAPI read access (an RBAC read-only role, Xen Orchestra,
  a metadata export or a backup) can read it through `xe vm-param-get param-name=xenstore-data` or the
  XO advanced tab. The plugin scrubs the secret from the VM record the moment the agent comes online,
  so the exposure is the boot-until-connect window (seconds on the lab pool), not the life of the
  build. The secret is an HMAC bound to a single node name on a short-lived, single-use VM, so reading
  it in that window lets an attacker impersonate that one agent, not the controller. The optional SSH
  key is seeded the same way and is not scrubbed; because it is a public key, its presence in the VM
  record is not a secret disclosure.
- **A pool certificate is either trusted by the JVM or pinned by fingerprint.** There is no setting
  that accepts an unrecognised certificate, because the first thing sent over that connection is the
  XAPI credential. Pinning is checked in addition to the ordinary hostname check, not instead of it:
  a connection succeeds only if the certificate both matches the fingerprint and names the host being
  dialled. If the pool's certificate is later replaced, connections fail until an administrator
  confirms the new fingerprint — that failure is the feature, since a replaced certificate is either
  routine maintenance or an interception and only a human can tell which.
- **Reading a fingerprint does not trust it.** `Test connection` inspects the certificate an unknown
  pool presents and then refuses the connection, so the credential is never offered to a host nobody
  has confirmed. Check the fingerprint it reports against the pool before pasting it in.
- **Only public SSH keys belong in `sshAuthorizedKey`.** The field seeds a public key into each
  clone; the form rejects anything that looks like a private key. The private half must never reach
  an agent.
- **`Test connection` requires the overall administer permission**, so a lower-privileged user
  cannot use it to probe arbitrary hosts.

## Troubleshooting

**An agent is provisioned but never connects.** This is the most common first-run failure, and the VM
is destroyed after the connect timeout (five minutes), so the evidence is gone unless you look while it
is up.

- Confirm the Jenkins root URL is set (**Manage Jenkins** then **System**) and is reachable over
  HTTP(S) from the network the clone booted on. The launcher fetches `agent.jar` from that URL and
  opens the WebSocket back to it; if it is unset or wrong, the clone boots, finds no controller, and is
  reaped.
- On the clone, check the launcher: `journalctl -u jenkins-agent`. The unit reads the agent name and
  secret from xenstore and starts the agent; its log shows whether it got them and what the connection
  attempt did.
- If the agent logs `Connected` and then drops into a silent reconnect loop, suspect a Java major
  mismatch: the agent JRE must match the controller's Java major version (Java 21 here). A mismatch
  throws `UnsupportedClassVersionError` and the agent never stays up, without ever reporting a useful
  offline cause in the UI.
- **If the clone has no address at all, none of the above is reachable** and there is nothing to read
  with `journalctl`, because you cannot log in. Check `networks` on the VM record
  (`xe vm-param-get uuid=<vm> param-name=networks`, or the guest metrics in Xen Orchestra). An empty
  `networks={}` while the guest tools report `PV_drivers_detected: True` and `live: True` means the
  guest booted, the tools are running over xenstore, and the interface never came up. Confirm from
  dom0 with `tcpdump -i vifN.0 -e 'ether src <clone MAC>'`: no packets at all, not even a DHCP
  `DISCOVER`, points at the image rather than at the network. The usual cause is a network config in
  the image pinned to a MAC, which `VM.clone` regenerates; see
  [`docs/golden-image.md`](docs/golden-image.md).

**A build never starts and agents are provisioned in a loop.** Check that `idleMinutes` comfortably
exceeds the time a clone takes to boot and connect. An agent that has never come online holds no idle
exemption, so a short timeout reclaims it mid-boot, the queue asks for another, and the cycle repeats
with nothing ever connecting. The default of 10 is fine; a value like 1 is not.

## Known limitations

- **Teardown trusts XAPI's `power_state`, which can occasionally lie.** The plugin destroys a VM by
  reading its power state, shutting the domain down only if it is not already `Halted`, then
  destroying the VM and its disks. A VM has been observed on the lab pool reading `Halted` (with
  `domid -1`) from XAPI while the domain was still running on dom0. Trusting that record skips the
  shutdown and destroys a live domain's disk, leaving an orphan that holds memory. The plugin speaks
  only XAPI (JSON-RPC) and, by the inbound/JNLP design, holds no SSH credential, so it has no
  independent second opinion; asking XAPI to re-check the same record inherits the same lie. This is
  a rare, accepted risk for this version rather than a fixable bug in the plugin. The root cause,
  frequency, and a reliable reproduction are unknown (observed once). The operator-side safety net is
  `tools/reaper.py --dom0-check`, which reads `xl list` directly from dom0 and refuses to reap any VM
  that XAPI reports `Halted` while Xen still has a live domain for it; run it before and after a batch
  of provisioning on a shared pool.

- **Deleting a cloud whose credential is also gone leaves its VMs to the reaper.** An agent snapshots
  its cloud's pool URL, credential ID and TLS-trust setting when it is provisioned, so deleting or
  renaming a cloud while its agents run no longer strands their VMs: teardown resolves the credential
  from the store and destroys the VM anyway. Two cases still cannot be recovered automatically, and
  both are logged at SEVERE with the VM reference. If the referenced credential has itself been
  deleted, or the pool is unreachable at that moment, there is no cloud left to hold the reference for
  a later retry — the durable leaked-VM sweep lives on the cloud that was removed. The same applies to
  an agent provisioned by a version of the plugin older than the snapshot, which reloads with no
  connection details at all. In both cases `tools/reaper.py` is the recovery path: it selects on the
  `xcpng-cloud` owner marker stamped into each clone, so it finds these VMs without needing Jenkins to
  still know about them.

## Not in this version

Deliberately out of scope for the spike: multiple templates matched by a single build, Windows
agents, pinning agents to specific hosts, per-label instance accounting, and controller-initiated
SSH launching. These are cuts, not dead ends.

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

## Dependency updates

[Dependabot](.github/dependabot.yml) owns everything it can read: `pom.xml`, and the `uses:` lines
in the workflows. Two versions have no manifest behind them — the Packer and actionlint releases
that [`ci.yml`](.github/workflows/ci.yml) downloads in `run:` steps, and the builder plugin pinned
inside the Packer template. Those are bumped weekly by
[updatecli](.github/workflows/updatecli.yaml), which opens a pull request per manifest in
[`updatecli/updatecli.d/`](updatecli/updatecli.d).

**updatecli needs a repository secret named `UPDATECLI_TOKEN`, and fails with a named error
without one.** The built-in `GITHUB_TOKEN` cannot do the job: it is a GitHub App installation
token, and GitHub refuses any push from one that touches `.github/workflows/`, which is where
`PACKER_VERSION` and `ACTIONLINT_VERSION` live. No workflow `permissions:` setting changes that —
there is no `workflows` scope to grant, and `contents: write` does not cover workflow files.

Create it as a classic personal access token with the `repo` and `workflow` scopes, or a
fine-grained token with Contents, Pull requests, and Workflows all set to read and write. Give it
the shortest expiry you are willing to renew: it can rewrite this repository's CI, which is the
most valuable thing in it.

Two consequences of using a token that belongs to a person rather than to the Actions app. Its
pull requests do trigger CI, where `GITHUB_TOKEN` ones would arrive unchecked. And the pushes are
attributed to the token's owner, while the commits themselves stay authored as `GitHub Actions`
per [`updatecli/values.github-action.yaml`](updatecli/values.github-action.yaml).

Forks skip the job entirely, so a fork needs no secret and will never try to open pull requests
against this repository.

## Releases

The plugin is not in the Jenkins update centre, which only distributes plugins hosted in the
`jenkinsci` organisation. A release here is a [GitHub release](../../releases) carrying an `.hpi`.
Install it through *Manage Jenkins → Plugins → Advanced settings → Deploy Plugin*; updates are
manual, and the update centre will not offer them.

Releases are cut when the shipped `.hpi` would behave differently for someone running it — a new
capability, or a correctness fix on the provision and teardown paths. Dependency bumps and
test-only changes ride along with the next such release rather than earning one.

Mechanically, [Release Drafter](.github/release-drafter.yml) keeps a draft release open on `main`,
sorting merged pull requests into notes and deriving the next version from their labels: `major`
or `breaking`, `minor` or `enhancement`, and everything else a patch. Publishing that draft is a
human decision. Publishing creates the tag, which fires
[`release.yml`](.github/workflows/release.yml) to build the `.hpi` at exactly that version and
attach it.

If the plugin is donated to `jenkinsci`, this is replaced by
[JEP-229 continuous delivery](https://www.jenkins.io/redirect/continuous-delivery-of-plugins): a
`cd.yaml` calling `jenkins-infra/github-reusable-workflows`, which publishes to
`repo.jenkins-ci.org` and needs organisation secrets. It reads the same labels, so only the
publishing half changes. The `.mvn/` incrementals wiring that donation requires is already here.

## License

MIT. See [`LICENSE`](LICENSE); it is also declared in `pom.xml`.

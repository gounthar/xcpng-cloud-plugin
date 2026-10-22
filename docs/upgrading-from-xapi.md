# Upgrading from a release that spoke XAPI

Releases before 1.6.0 could talk to a pool master directly over XAPI, and that was the default.
1.6.0 removed the XAPI backend; Xen Orchestra is now the only one. This page is the full procedure
for moving a controller across that line, for example from 0.3.0 to 2.0.0. The README section
[Upgrading from a release that spoke XAPI](../README.md#upgrading-from-a-release-that-spoke-xapi)
is the short version.

If your cloud already points at a Xen Orchestra appliance, none of this applies. The only thing to
check before 2.0.0 is that its URL is `https://`: a plain `http://` URL now provisions nothing (#334).

## What changes

| | Before (XAPI) | After (Xen Orchestra) |
|---|---|---|
| URL (`poolUrl`) | the pool master | the Xen Orchestra appliance in front of the same pool, `https://` only |
| Credential | username/password for the pool | secret text holding a Xen Orchestra token |
| Certificate fingerprint | the pool master's | the appliance's, or empty if it chains to a CA the controller trusts |
| Dependencies | none extra | `plain-credentials` (since 1.0.0) |

Templates, labels, `maxInstances`, `idleMinutes` and the rest of the cloud carry over unchanged.

After the upgrade, a cloud still configured for XAPI keeps its settings, logs one warning at startup
naming it, shows up in a banner under **Manage Jenkins**, and provisions nothing. A build waiting
for its label sits in the queue as "Waiting for next available executor", which says nothing about
the cloud, and nothing is logged per provisioning round. The controller itself stays healthy.

## Before you start

- **A Xen Orchestra appliance that manages the pool**, reachable from the controller over https.
- **A token for it**, stored in Jenkins as a *Secret text* credential. A scoped user is covered in
  [A least-privilege Xen Orchestra user](../README.md#a-least-privilege-xen-orchestra-user).
- **The appliance's certificate.** Either it chains to a CA the controller trusts, or you pin its
  SHA-256 fingerprint. **Test connection** in the cloud form reports the fingerprint the appliance
  actually presents.
- **The pool's root credentials**, kept to hand. `tools/reaper.py` talks to the pool directly over
  XAPI, and it is how you clean up if anything is left behind.

## Procedure

### 1. Drain the cloud

Let running builds finish and wait until no XCP-ng agent is left on the controller. *Prepare for
shutdown* stops new builds from starting meanwhile.

This step matters more than it looks; see [If you could not drain](#if-you-could-not-drain).

### 2. Back up `JENKINS_HOME`

Stop the controller and archive the whole home. That archive is your rollback.

### 3. Install the new plugin

The new release depends on `plain-credentials`, which releases before 1.0.0 did not. Whichever way
you install, make sure it ends up installed.

- **Plugin manager** (*Manage Jenkins → Plugins → Advanced settings → Deploy Plugin* with the
  `.hpi` from the release page).
- **`plugins.txt` with `jenkins-plugin-cli`**: replace the xcpng-cloud line with the new release's
  URL, and make sure `plain-credentials` is listed. Check the download against the sha256 published
  with the release. `jenkins-plugin-cli` prints "No checksum found for xcpng-cloud (probably custom
  built plugin)" for a URL plugin; that is expected.
- **Copying the `.hpi` into `plugins/` by hand**: add `plain-credentials` yourself. What happens
  without it was not tested.

### 4. Point the cloud at Xen Orchestra

**From the UI:** open the cloud, set **Xen Orchestra URL** to the appliance, select the token
credential, press **Test connection**, pin the certificate if it asks, and save. Saving is what
moves the cloud; nothing is migrated behind your back.

**Under configuration as code**, this is the whole change to a cloud and its credential:

```diff
 jenkins:
   clouds:
     - xcpng:
         name: "my-pool"
-        poolUrl: "https://pool-master.example.com"
-        credentialsId: "pool-root"
-        certificateFingerprint: "<pool master fingerprint>"
+        poolUrl: "https://xo.example.com"
+        credentialsId: "xo-token"
+        certificateFingerprint: "<appliance fingerprint, or empty>"
 ...
 credentials:
   system:
     domainCredentials:
       - credentials:
-          - usernamePassword:
-              id: "pool-root"
-              username: "root"
-              password: "${POOL_PASSWORD}"
+          - string:
+              id: "xo-token"
+              secret: "${XO_TOKEN}"
```

Delete `backend: XAPI` if your document has it. Most documents written for XAPI name no backend at
all, because XAPI was the default; after the upgrade such a cloud comes up as Xen Orchestra while
still selecting the old username/password credential. It is listed in the banner and provisions
nothing until the credential is fixed.

Changing `plugins.txt` and the configuration in one restart worked, in the run with a build left
running. Upgrading first and switching the configuration in a later restart worked in the drained
run. The second order was not tried on an undrained controller.

**Check that the token actually reached the controller.** A secret variable that is empty inside the
container gets a 401 that reads "The appliance did not accept the token. It may be wrong, revoked or
expired", even though the token is fine. With Docker Compose, `docker compose restart` does not
re-read `env_file`; recreate the container instead.

### 5. Check it

- The banner under **Manage Jenkins** is gone.
- One labelled build runs on a fresh clone and passes.
- Once the agent idles out, its VM and disks are gone from the pool.

## If you could not drain

An agent that was running at the upgrade was provisioned over XAPI, and the new release cannot open
that connection. What happened in the one lab run where a build was left running during the
upgrade:

- **The VM kept running.** The build lost its agent at the restart; the VM did not stop.
- **The plugin tried to destroy it during startup, failed, and logged a SEVERE** from
  `XcpngAgent` saying the XAPI backend has been removed. It recorded the VM as leaked.
- **About ten seconds later it forgot the VM.** A SEVERE from `XcpngCloud#giveUp` reads "Giving up
  on leaked XCP-ng VM ... its connection can no longer be opened", and from then on nothing in the
  UI mentions it. The agent was torn down during startup, so the banner never listed it either.
- **Nothing else removed the VM** in the 17 minutes it was watched. The agent inside kept retrying
  the connection, about every 10 seconds in that run; that interval is the agent's, not the
  plugin's. Nothing in the code goes back to a VM once it has been
  given up on, so expect it to stay until you remove it.
- **The build that was running fails, and its record is not kept.** Its log ends in
  `ClosedChannelException` and `Finished: FAILURE`, but it does not load afterwards.

New builds were not affected: a build started after the upgrade ran on a new clone, which was
destroyed as usual.

To clean up, run the reaper from a machine that can reach the pool master. It selects VMs by the
owner marker the plugin stamps on every clone, and it is a dry run by default. It reads the pool's
credentials from the environment; prompting for the password keeps it out of your shell history:

```bash
export XCPNG_HOST=pool-master.example.com XCPNG_USER=root
read -rsp 'Pool root password: ' XCPNG_PASS && export XCPNG_PASS
python3 tools/reaper.py
```

Check that it lists only VMs you expect to go. Then apply it, passing the controller's current node
list so that agents still in use are kept. The Jenkins API token goes in a netrc file (one line,
`machine <jenkins host> login <user> password <api token>`, readable only by you) rather than on the
`curl` command line, where other users can see it in the process list:

```bash
curl -gfs --netrc-file ~/.jenkins-netrc "$JENKINS_URL/computer/api/json?tree=computer[displayName]" \
  | python3 tools/reaper.py --apply --keep-nodes -
```

Run it while the cloud is not provisioning. The reaper's own header explains why, and lists its other
options.

Making the plugin reclaim these VMs through Xen Orchestra itself is tracked in #330.

## Rolling back

Stop the controller, restore the `JENKINS_HOME` archive, put the old plugin version back (and the
XAPI cloud back in your configuration as code, if you use it), then start it. The old release loaded
and ran a build after this.

Build records from the period on the new release are lost, because the restored home brings back
the old `nextBuildNumber`. Archive the new jobs' builds first if you need them.

## How this was tested

Rehearsed on a lab pool, XCP-ng 8.3, Jenkins 2.580.1 LTS on JDK 25 in Docker, plugins installed by
`jenkins-plugin-cli` into an emptied `plugins/` directory, upgrading 0.3.0 to 2.0.0 with one
template. Each step ran once:

- the drained upgrade, with the configuration switched in a second restart: two builds passed;
- the undrained upgrade, with `plugins.txt` and the configuration changed in the same restart;
- the rollback to 0.3.0: one build passed.

Not tested: the undrained upgrade with the configuration switched afterwards, several templates or
`maxInstances` above 1, and a `.hpi` copied by hand into a `plugins/` directory populated some other
way. The undrained behaviour was seen once on 2.0.0 and once on 1.6.0 (#331).

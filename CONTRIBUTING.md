# Contributing

Thanks for looking. This is a spike rather than a supported plugin, so the most useful
contribution is usually a report of what happened when you pointed it at a pool that is not the
one it was written against — a different XCP-ng version, more than one host, a shared storage
repository, a golden image built some other way.

## Before you start

Read [`README.md`](README.md) first; it covers what the plugin does, how to configure it, and how
to prepare a golden image. [Known limitations](README.md#known-limitations) and
[Not in this version](README.md#not-in-this-version) list what is already understood to be missing,
so a report against one of those is a confirmation rather than news.

Search the [open issues](../../issues) before filing. If you are planning something larger than a
fix, open an issue first and say what you have in mind — the design has a few deliberate
constraints (see below) and it is cheaper to disagree about them before the code exists.

## Building

```sh
mvn -B -ntp verify
```

Three things about that command:

- **JDK 21, and only 21.** `maven-hpi-plugin` refuses to build on 17 outright, and the plugin
  parent POM pins `maven.compiler.release` to 21. The 2.555.3 LTS baseline governs what Jenkins
  *runs* on, not what compiles the plugin.
- **`verify`, not `test`.** It also runs SpotBugs and packages the `.hpi`, which is where errors in
  `index.jelly` and `config.jelly` paths surface. A green `mvn test` proves less than it looks like.
- **Spotless is enforced.** This repository sets `spotless.check.skip=false`, so any formatting
  drift fails the whole build rather than a lint step. Run `mvn spotless:apply` before committing
  anything under `src/` or touching `pom.xml`.

`mvn hpi:run -Dhost=0.0.0.0 -Dport=8080` gives you a local Jenkins at
`http://localhost:8080/jenkins` with the plugin loaded.

## Tests

The test suite needs no hypervisor. The Jenkins half runs against an in-memory
`FakeHypervisorClient` that records the call sequence; the XAPI half runs against recorded JSON
fixtures. Please keep it that way — a test that needs a pool is a test nobody can run.

Two habits that have paid for themselves here:

- **Watch the test fail first.** Break the code the test is meant to guard and confirm it goes red
  for the right reason. Removing the `reaping` guard should produce two `destroyWithDisks` calls on
  one VM reference; if it does not, the test is not testing that. This matters double for a
  negative assertion (*X is never touched*), which passes happily against code that cannot do X at
  all.
- **Ask what the fake said yes to.** Two bugs here were certified green by fixtures too agreeable
  to fail — one answered on an interrupted thread, one accepted an illegal vCPU ordering. A fake
  more polite than a real pool hides exactly the bugs a real pool would find.

CI also runs `actionlint` over the workflows, `pytest` over `tools/tests`, `image/provision.sh`
inside a `debian:13` container, and `packer validate` on the image template. None of that needs a
pool either. Building the golden image itself does, and cannot happen in CI.

## Claims about behaviour

If a change, a comment, or a documentation line tells a reader how something behaves, say where
that came from. One of three:

- you ran it, and here is the command and its output;
- you read the artifact, and here is the file and line;
- neither — in which case write that down ("not verified", "reported once, mechanism unknown")
  rather than phrasing a guess as a fact.

This is not ceremony. XAPI has been observed reporting a VM as `Halted` with `domid -1` while
`xl list` on dom0 showed the domain running, and the plugin destroyed that disk. Reconciling two
plausible-sounding descriptions in prose is how that kind of thing survives review.

## Branches, commits, and pull requests

- Branch off `main`; never commit to it directly. `type/short-description`, matching the commit
  type: `feat/`, `fix/`, `docs/`, `refactor/`, `build/`, `ci/`, `test/`, `chore/`.
- [Conventional commits](https://www.conventionalcommits.org/): `type(scope): imperative summary`,
  subject under 72 characters. Put the reasoning in the body — why, and which alternatives you
  rejected. The pull request gets read once; `git log` and `git blame` get read for years.
- End every text file with a newline.
- **The pull request title becomes the changelog entry.** Release Drafter builds the release notes
  from merged pull request titles, and derives the next version from their labels. Label yours:
  `enhancement`, `bug`, `documentation`, `build`, `dependencies`, or `skip-changelog` to leave it
  out of the notes entirely. `major` and `breaking` bump the major version, `minor` and
  `enhancement` the minor, everything else a patch.
- One logical change per pull request. Fill in the template, especially the testing section.

## Design constraints worth knowing before you propose something

These are decisions, not accidents, and a change that reverses one needs an argument:

- **`HypervisorClient` is a small interface of lifecycle verbs**, named in plugin terms rather than
  XAPI terms, so a Xen Orchestra REST backend can sit beside `XapiClient` later. Backend-specific
  behaviour — task polling, session re-login, master redirects — stays inside the implementation
  and never reaches the interface. No capability negotiation, no generic `execute()`.
- **The agent connects inbound over JNLP**, so the plugin needs no route from controller to agent
  and no SSH credential. `SSHLauncher` remains a documented later option, not a replacement.
- **Configuration stores credential IDs, never secrets.** Credentials are resolved at point of use
  through `CredentialsProvider`.
- **Every `do*` method on a `Descriptor` is a web endpoint.** All of them take `@POST` (or
  `@RequirePOST` for action-shaped ones like `doTestConnection`) *and* a
  `Jenkins.get().checkPermission(Jenkins.ADMINISTER)` as the first statement — not only the ones
  that look dangerous. A rule written for one method got implemented for exactly one method here
  once, and that was 14 of 17 open code-scanning alerts.
- **No references to AI tools, assistants, or automation** in code, comments, commits, pull
  requests, or issues.

## Testing against a real pool

Anything touching provisioning or teardown deserves a run against real hardware, and the
maintainer can do that if you cannot. Say so in the pull request rather than describing what you
expect would happen. `tools/reaper.py` is dry-run by default and selects on the `xcpng-cloud` owner
marker the plugin stamps into each clone, so it can only destroy VMs the plugin provisioned; run it
before and after anything that clones, and keep `maxInstances` low.

## Licence

Contributions are accepted under the [MIT licence](LICENSE) that covers the rest of the repository.
There is no CLA and no sign-off requirement. If the plugin is ever donated to the `jenkinsci`
organisation, that donation would be announced in an issue first.

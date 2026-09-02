<!-- The title of this pull request becomes the changelog entry, so write it for someone reading
     release notes. Label it so Release Drafter can file it and resolve the next version:
     enhancement / bug / documentation / build / dependencies, or skip-changelog. -->

### What this changes, and why

<!-- The diff says what. This is for why, and for the alternatives you rejected. -->

### Testing done

<!-- What did you actually run, and where?

     - The command and its output, if you ran something.
     - The file and line, if you read the artifact instead.
     - "Not verified" if neither — that is an acceptable answer and a guess dressed as a fact is not.

     Anything touching provisioning or teardown wants a run against a real pool. If you cannot do
     that, say so here and the maintainer will. -->

### Checklist

- [ ] Opened from a branch, not from your fork's own `main` (see CONTRIBUTING.md for why, and how to move it)
- [ ] The title reads as the changelog entry it will become, and the pull request is labelled
- [ ] `mvn spotless:apply` run, if this touches `src/` or `pom.xml`
- [ ] `mvn -B -ntp verify` passes locally
- [ ] Tests added, or an explanation above of why there are none
- [ ] Linked to the relevant issue

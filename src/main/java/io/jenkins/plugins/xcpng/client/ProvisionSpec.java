package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * What to make when cloning a template, in backend-neutral terms.
 *
 * <p>Sizing lives here, not on the template: {@code VM.clone} copies the source's vCPU and memory,
 * so each clone overrides them. Sizes are in bytes at this seam; the config layer converts from the
 * MiB/GiB a human types. {@code diskBytes} null means "inherit the template's disk" (a genericcloud
 * root filesystem auto-grows on first boot when the disk is larger).
 *
 * <p>{@code placementHint} is an opaque string; null means "let the pool schedule". {@code userData}
 * (a cloud-init NoCloud payload) is optional and the XAPI backend ignores it in v0 (the seed is
 * attached as a separate concern, not through this field).
 */
public record ProvisionSpec(
        @NonNull String name,
        int vcpus,
        long memoryBytes,
        @CheckForNull Long diskBytes,
        @CheckForNull String placementHint,
        @CheckForNull String userData) {

    public ProvisionSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (vcpus < 1) {
            throw new IllegalArgumentException("vcpus must be >= 1, was " + vcpus);
        }
        if (memoryBytes < 1) {
            throw new IllegalArgumentException("memoryBytes must be > 0, was " + memoryBytes);
        }
        if (diskBytes != null && diskBytes < 1) {
            throw new IllegalArgumentException("diskBytes, when set, must be > 0, was " + diskBytes);
        }
    }
}

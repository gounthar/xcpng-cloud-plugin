package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * What to make when cloning a template, in backend-neutral terms.
 *
 * <p>This record is the backend-neutral carrier for sizing: {@code VM.clone} copies the source's vCPU
 * and memory, so each clone overrides them from the values an operator set on the agent template. Sizes
 * are in bytes at this seam; the config layer converts from the MiB/GiB a human types. {@code diskBytes}
 * null means "inherit the template's disk" (a genericcloud root filesystem auto-grows on first boot when
 * the disk is larger).
 *
 * <p>{@code placementHint} is an opaque string; null means "let the pool schedule". {@code userData}
 * (a cloud-init NoCloud payload) is optional and no backend reads it in v0 (the seed is
 * attached as a separate concern, not through this field).
 *
 * <p>{@code guestData} carries per-clone key/value pairs the guest reads on first boot to launch its
 * inbound agent (the controller URL, the node name, and the JNLP secret). It is backend-neutral: the
 * keys are plain logical names ({@code url}, {@code name}, {@code secret}) and each backend chooses how
 * to deliver them. The Xen Orchestra backend writes them into the clone's xenstore data (under
 * {@code vm-data/jenkins/}) in the same call that sizes the clone, before the VM starts. Empty
 * when no seed is needed. Distinct from {@code userData}, which is an opaque cloud-init blob v0 ignores.
 *
 * <p>{@code owner} names the cloud this clone belongs to, and asks the backend to record it <em>on the VM
 * itself</em> so the VM can be identified later without the plugin. Null means "do not mark", which is what
 * a caller that only sizes a clone wants. It exists for recovery: the plugin's own teardown covers the
 * normal path, but a controller that crashes mid-provision, or a {@code destroyWithDisks} that throws,
 * leaves a VM only an out-of-band sweep will ever find. Matching such VMs by name is what left
 * {@code tools/reaper.py} unable to see a single one of them, so the mark is a property of the record
 * rather than a naming convention. The backend picks the field: the Xen Orchestra backend uses tags
 * ({@link XoRestClient#OWNER_TAG_PREFIX}); the removed XAPI backend used {@code other_config}.
 *
 * <p>{@code networkName} names the network the clone's NIC is cabled to, by name-label, resolved in the
 * template's own pool. Null means "inherit the template's NIC", which is what every clone did before #243.
 * It is a name rather than a handle because the point is portability: a golden image copied to another pool
 * arrives cabled to a network that pool may not have, and the same name resolves to that pool's own network.
 * It selects a network, not a VLAN: the 802.1Q tag lives on the host side, whatever the name-label says.
 */
public record ProvisionSpec(
        @NonNull String name,
        int vcpus,
        long memoryBytes,
        @CheckForNull Long diskBytes,
        @CheckForNull String placementHint,
        @CheckForNull String userData,
        @NonNull Map<String, String> guestData,
        @CheckForNull String owner,
        @CheckForNull String networkName) {

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
        // Defensive immutable copy; null collapses to empty. Map.copyOf also rejects null keys/values,
        // so a malformed seed fails here rather than deep inside a backend's guest-data write.
        guestData = guestData == null ? Map.of() : Map.copyOf(guestData);
        // Blank collapses to null so a backend can gate on null alone; the config layer already trims, this
        // only stops a caller that skipped it from asking for a network named "".
        networkName = networkName == null || networkName.isBlank() ? null : networkName;
    }

    /** An owned, seeded spec that keeps the template's NIC as it is. */
    public ProvisionSpec(
            @NonNull String name,
            int vcpus,
            long memoryBytes,
            @CheckForNull Long diskBytes,
            @CheckForNull String placementHint,
            @CheckForNull String userData,
            @NonNull Map<String, String> guestData,
            @CheckForNull String owner) {
        this(name, vcpus, memoryBytes, diskBytes, placementHint, userData, guestData, owner, null);
    }

    /** A spec that carries no guest seed data, for callers (and tests) that only size a clone. */
    public ProvisionSpec(
            @NonNull String name,
            int vcpus,
            long memoryBytes,
            @CheckForNull Long diskBytes,
            @CheckForNull String placementHint,
            @CheckForNull String userData) {
        this(name, vcpus, memoryBytes, diskBytes, placementHint, userData, Map.of(), null, null);
    }

    /** A seeded spec for an unowned clone, i.e. one no out-of-band sweep is expected to have to find. */
    public ProvisionSpec(
            @NonNull String name,
            int vcpus,
            long memoryBytes,
            @CheckForNull Long diskBytes,
            @CheckForNull String placementHint,
            @CheckForNull String userData,
            @NonNull Map<String, String> guestData) {
        this(name, vcpus, memoryBytes, diskBytes, placementHint, userData, guestData, null, null);
    }
}

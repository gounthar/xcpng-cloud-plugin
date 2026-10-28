package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;

/**
 * What Xen Orchestra reports about a VM looked up by the XAPI ref a release before the move to Xen Orchestra
 * recorded for it (#364). The answer to {@link XoRestClient#lookUpXapiEraVm}, and nothing more: it says which VM
 * XO resolved the ref to, and leaves deciding whether that is the VM the ref was recorded for to the caller.
 *
 * <p>{@link #id()} is the handle every later call uses. The ref itself never goes into a mutating path, because
 * XO resolves a ref to whatever it names on the pools it manages, and that is the hazard {@code unaddressable}
 * exists to refuse.
 *
 * @param id XO's object id for the VM, as a handle this backend can address
 * @param uuid the uuid the VM's own record reports
 * @param xapiRef the record's {@code _xapiRef}, exactly as XO reports it; not necessarily the ref asked for
 * @param nameLabel the VM's name-label
 * @param poolId the pool XO files the VM under, its {@code $pool}
 * @param powerState the power state XO reports, which can lie (#48) and is shown rather than trusted
 * @param legacyOwner the {@link OwnerMarker#OWNER_KEY} entry of the VM's {@code other_config}, which XO exposes
 *     read-only as {@code other}: the owner marker in the shape the XAPI backend stamped it, or null without one
 * @param tags the VM's tags, as XO reports them
 */
public record XapiEraVm(
        @NonNull VmRef id,
        @NonNull String uuid,
        @NonNull String xapiRef,
        @NonNull String nameLabel,
        @NonNull String poolId,
        @NonNull VmState powerState,
        @CheckForNull String legacyOwner,
        @NonNull List<String> tags) {

    public XapiEraVm {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(xapiRef, "xapiRef");
        Objects.requireNonNull(nameLabel, "nameLabel");
        Objects.requireNonNull(poolId, "poolId");
        Objects.requireNonNull(powerState, "powerState");
        tags = List.copyOf(tags);
    }
}

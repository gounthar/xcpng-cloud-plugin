package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.xcpng.client.XapiEraVm;
import io.jenkins.plugins.xcpng.client.XoRestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether the VM Xen Orchestra resolved a XAPI-era ref to is the one a leaked entry was recorded for (#364).
 *
 * <p>A ref is resolved by XO, not by the pool that minted it, and on every pool that XO manages, so the answer can
 * name a VM that only happens to carry the ref the entry holds. Every check below has to hold before anything acts
 * on the VM; none of them is enough on its own, and each failure is reported, so the preview can say which one
 * kept the entry.
 *
 * <ul>
 *   <li>The record's {@code _xapiRef} equals the entry's ref exactly. XO's by-ref lookup is an exact key today;
 *       this does not depend on it staying one.
 *   <li>The VM's name-label equals the agent name the entry recorded. An entry with no name recorded, from before
 *       #363, is never eligible: nothing then ties the ref to a VM a person could recognise.
 *   <li>The VM carries the XAPI-era owner marker naming this cloud, in {@code other_config}.
 *   <li>No owner tag names another cloud, and no uuid stamp names another VM. Either means the VM is, or was
 *       copied from, something this cloud did not provision.
 * </ul>
 *
 * <p>The pool is not checked here, because it cannot be checked by string. The stored URL is whatever the old
 * cloud was configured with, an alias on the lab, while XO knows the pool by its members' addresses. The preview
 * shows both and the administrator is the pool check.
 */
final class XcpngXapiEraReclaim {

    private XcpngXapiEraReclaim() {}

    /** Why {@code entry} of {@code cloudName} may not be reclaimed as {@code found}; empty when it may. */
    @NonNull
    static List<String> refusals(
            @NonNull String cloudName, @NonNull XcpngLeakedVm entry, @NonNull Optional<XapiEraVm> found) {
        List<String> refused = new ArrayList<>();
        if (entry.getBackend().isSupported()) {
            refused.add(Messages.XcpngXapiEraReclaim_NotXapiEra());
            return refused;
        }
        if (entry.getNodeName() == null) {
            refused.add(Messages.XcpngXapiEraReclaim_NoNodeName());
        }
        if (found.isEmpty()) {
            refused.add(Messages.XcpngXapiEraReclaim_NotFound());
            return refused;
        }
        XapiEraVm vm = found.get();
        if (!vm.xapiRef().equals(entry.getVmRef())) {
            refused.add(Messages.XcpngXapiEraReclaim_OtherRef(vm.xapiRef()));
        }
        if (entry.getNodeName() != null && !entry.getNodeName().equals(vm.nameLabel())) {
            refused.add(Messages.XcpngXapiEraReclaim_OtherName(vm.nameLabel()));
        }
        if (!cloudName.equals(vm.legacyOwner())) {
            refused.add(Messages.XcpngXapiEraReclaim_NotMarked(
                    vm.legacyOwner() == null ? Messages.XcpngXapiEraReclaim_NoMarker() : vm.legacyOwner()));
        }
        String ownTag = XoRestClient.OWNER_TAG_PREFIX + cloudName;
        String selfTag = XoRestClient.SELF_TAG_PREFIX + vm.uuid();
        for (String tag : vm.tags()) {
            // The uuid prefix first: it does not start with the owner prefix today, and this does not rely on it.
            boolean foreign = tag.startsWith(XoRestClient.SELF_TAG_PREFIX)
                    ? !tag.equals(selfTag)
                    : tag.startsWith(XoRestClient.OWNER_TAG_PREFIX) && !tag.equals(ownTag);
            if (foreign) {
                refused.add(Messages.XcpngXapiEraReclaim_ForeignTag(tag));
            }
        }
        return refused;
    }
}

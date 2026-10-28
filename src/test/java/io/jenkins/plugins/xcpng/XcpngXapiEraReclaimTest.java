package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jenkins.plugins.xcpng.client.VmRef;
import io.jenkins.plugins.xcpng.client.VmState;
import io.jenkins.plugins.xcpng.client.XapiEraVm;
import io.jenkins.plugins.xcpng.client.XoRestClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The identity checks a XAPI-era entry must pass before its VM is reclaimed (#364). The eligible case is the shape
 * the lab appliance answered for a 0.3.0 clone on 2026-10-28; every other case changes one thing about it, so a
 * check that passed everything, or refused everything, fails here.
 */
class XcpngXapiEraReclaimTest {

    private static final String CLOUD = "LAB-POOL-87";
    private static final String REF = "OpaqueRef:47d08d21-17c9-a450-2738-546217643c7d";
    private static final String UUID = "d3aa4c31-2d52-6ab0-e766-157f524ea8b4";
    private static final String NODE = "xcpng-jenkins-agent-debian13-v8-60cd9585";

    private static XcpngLeakedVm entry(String nodeName) {
        XcpngLeakedVm vm = XcpngLeakedVm.of(REF, XcpngBackend.XAPI, "https://xcp-ng-hhpfmhok", "xcpng-lab-xapi", null);
        return nodeName == null ? vm : vm.named(nodeName);
    }

    private static XapiEraVm found(String ref, String name, String owner, List<String> tags) {
        return new XapiEraVm(
                new VmRef(UUID), UUID, ref, name, "23ac115e-5f9a-0d96-c3b2-fb92f87fd1ec", VmState.RUNNING, owner, tags);
    }

    private static XapiEraVm lab() {
        return found(REF, NODE, CLOUD, List.of());
    }

    private static List<String> refusals(XcpngLeakedVm entry, XapiEraVm vm) {
        return XcpngXapiEraReclaim.refusals(CLOUD, entry, Optional.ofNullable(vm));
    }

    @Test
    void theLabCloneIsEligible() {
        assertEquals(List.of(), refusals(entry(NODE), lab()));
    }

    /** This cloud's own tags, as a reclaim stamps them, are not foreign: the control for the tag check below. */
    @Test
    void thisCloudsOwnTagsAreNotForeign() {
        XapiEraVm tagged = found(
                REF,
                NODE,
                CLOUD,
                List.of(XoRestClient.OWNER_TAG_PREFIX + CLOUD, XoRestClient.SELF_TAG_PREFIX + UUID, "build"));
        assertEquals(List.of(), refusals(entry(NODE), tagged));
    }

    @Test
    void anEntryOnASupportedBackendIsTheSweepsNotTheButtons() {
        XcpngLeakedVm xo = XcpngLeakedVm.of(UUID, XcpngBackend.XO, "https://xo.example.test", "cred", null)
                .named(NODE);
        assertEquals(List.of(Messages.XcpngXapiEraReclaim_NotXapiEra()), refusals(xo, lab()));
    }

    /** Before #363 no name was recorded, and then nothing ties the ref to a VM a person would recognise. */
    @Test
    void anEntryWithNoNameIsNeverEligibleEvenWhenEverythingElseMatches() {
        assertEquals(List.of(Messages.XcpngXapiEraReclaim_NoNodeName()), refusals(entry(null), lab()));
    }

    @Test
    void noVmForTheRefIsNotEligible() {
        assertEquals(List.of(Messages.XcpngXapiEraReclaim_NotFound()), refusals(entry(NODE), null));
    }

    /** Exact, not by prefix and not ignoring case: the filter query XO offers is both, which is why it is not used. */
    @Test
    void aRecordForAnotherRefIsNotEligible() {
        for (String other : new String[] {REF.toUpperCase(java.util.Locale.ROOT), REF + "0", "OpaqueRef:47d08d21"}) {
            assertEquals(
                    List.of(Messages.XcpngXapiEraReclaim_OtherRef(other)),
                    refusals(entry(NODE), found(other, NODE, CLOUD, List.of())));
        }
    }

    /** A renamed VM, or a different one that XO resolved the ref to. */
    @Test
    void aVmWithAnotherNameIsNotEligible() {
        String renamed = NODE + "-kept";
        assertEquals(
                List.of(Messages.XcpngXapiEraReclaim_OtherName(renamed)),
                refusals(entry(NODE), found(REF, renamed, CLOUD, List.of())));
    }

    /** No marker, another cloud's marker, and a cloud whose name only starts like this one's. */
    @Test
    void aVmThisCloudDidNotMarkIsNotEligible() {
        assertEquals(
                List.of(Messages.XcpngXapiEraReclaim_NotMarked(Messages.XcpngXapiEraReclaim_NoMarker())),
                refusals(entry(NODE), found(REF, NODE, null, List.of())));
        for (String owner : new String[] {"other-cloud", CLOUD + "-2", "LAB-POOL-8"}) {
            assertEquals(
                    List.of(Messages.XcpngXapiEraReclaim_NotMarked(owner)),
                    refusals(entry(NODE), found(REF, NODE, owner, List.of())));
        }
    }

    /** Another cloud's owner tag, or a uuid stamp naming another VM: the VM is, or was copied from, someone else's. */
    @Test
    void aForeignOwnerTagOrUuidStampIsNotEligible() {
        for (String tag : new String[] {
            XoRestClient.OWNER_TAG_PREFIX + "other-cloud",
            XoRestClient.OWNER_TAG_PREFIX + CLOUD + "-2",
            XoRestClient.SELF_TAG_PREFIX + "b81c6d2f-40a7-4e93-8f15-6ad3c0e72b94"
        }) {
            assertEquals(
                    List.of(Messages.XcpngXapiEraReclaim_ForeignTag(tag)),
                    refusals(entry(NODE), found(REF, NODE, CLOUD, List.of(tag))),
                    tag);
        }
    }

    /** Every failed check is reported, so the preview names all of them rather than the first. */
    @Test
    void everyFailedCheckIsReported() {
        assertEquals(
                4,
                refusals(entry(NODE), found("OpaqueRef:x", "other", "other-cloud", List.of("xcpng-cloud:x")))
                        .size());
    }
}

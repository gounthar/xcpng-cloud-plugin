package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The warm-pool retention behaviour: the pure idle-reap exemption rule, and the warm to used flip an
 * agent undergoes when it accepts its first build. The reaping mechanics themselves (single-use on
 * task completion, the async teardown) are exercised through the provisioning tests.
 */
@WithJenkins
class XcpngRetentionStrategyTest {

    /**
     * The exemption keeps a spare hot only when it is warm AND online AND still attached to a live
     * cloud. Every other combination falls through to the idle net, so a spare can never leak: an
     * offline spare that never connected is reaped, and one whose cloud was deleted is reaped.
     */
    @Test
    void exemptOnlyWhenWarmOnlineAndCloudPresent(JenkinsRule r) {
        assertTrue(XcpngRetentionStrategy.exemptFromIdleReap(true, true, true), "warm, online, cloud present");
        assertFalse(XcpngRetentionStrategy.exemptFromIdleReap(true, true, false), "cloud deleted must not be exempt");
        assertFalse(XcpngRetentionStrategy.exemptFromIdleReap(true, false, true), "offline spare must be reapable");
        assertFalse(XcpngRetentionStrategy.exemptFromIdleReap(false, true, true), "a used agent is never exempt");
        assertFalse(XcpngRetentionStrategy.exemptFromIdleReap(false, false, false), "the spent, offline case");
    }

    /** A warm spare reports warm until it accepts work, then markUsed() flips it to single-use for good. */
    @Test
    void markUsedClearsTheWarmFlag(JenkinsRule r) throws Exception {
        XcpngTemplate template = new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 1, 2, 2048);
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", "xcpng-warm-1");
        XcpngAgent spare = new XcpngAgent("xcpng-warm-1", "xcpng", "vm/xcpng-warm-1/1", template, 10, id, true);

        assertTrue(spare.isWarm(), "a spare is warm at birth");
        spare.markUsed();
        assertFalse(spare.isWarm(), "accepting work clears the warm flag");
        spare.markUsed();
        assertFalse(spare.isWarm(), "markUsed is idempotent");
    }
}

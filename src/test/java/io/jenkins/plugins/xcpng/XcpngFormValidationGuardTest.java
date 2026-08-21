package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

/**
 * Every {@code do*} method on a descriptor is a web endpoint, so each of the seven field validators
 * takes a permission check and a POST guard, matching {@code doTestConnection}. Read alone they are
 * harmless — each returns an ok or an error for the string it was handed — but the guard has to be on
 * the method before one of them grows a lookup that asks the pool something.
 *
 * <p>The plugin's own rule used to name {@code doTestConnection} alone, and precisely that one method
 * had the check while seven validators went bare. These tests are per-method for that reason: a single
 * assertion over one representative validator would pass again the next time only one gets fixed.
 */
@WithJenkins
class XcpngFormValidationGuardTest {

    /** A realm with an administrator and a bystander who can see Jenkins and nothing else. */
    private static void lockDown(JenkinsRule r) {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("alice")
                .grant(Jenkins.READ)
                .everywhere()
                .to("bob"));
    }

    private static void asUser(String name, Runnable body) {
        try (ACLContext ignored = ACL.as2(User.getById(name, true).impersonate2())) {
            body.run();
        }
    }

    /**
     * The seven validators, each as a call that exercises the guard. The value passed is whatever that
     * method would accept, so a failure here is the permission check and never the validation logic.
     */
    private static List<NamedCheck> validators(JenkinsRule r) {
        XcpngCloud.DescriptorImpl cloud = r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class);
        XcpngTemplate.DescriptorImpl tpl = r.jenkins.getDescriptorByType(XcpngTemplate.DescriptorImpl.class);
        return List.of(
                new NamedCheck("doCheckPoolUrl", () -> cloud.doCheckPoolUrl("https://pool.example.test")),
                new NamedCheck("doCheckTemplateName", () -> tpl.doCheckTemplateName("jenkins-agent-debian13")),
                new NamedCheck("doCheckLabelString", () -> tpl.doCheckLabelString("xcpng-linux")),
                new NamedCheck("doCheckNumCpus", () -> tpl.doCheckNumCpus("2")),
                new NamedCheck("doCheckMemoryMb", () -> tpl.doCheckMemoryMb("2048")),
                new NamedCheck("doCheckMinInstances", () -> tpl.doCheckMinInstances(null, "0")),
                new NamedCheck("doCheckSshAuthorizedKey", () -> tpl.doCheckSshAuthorizedKey("")));
    }

    private record NamedCheck(String name, java.util.function.Supplier<FormValidation> call) {}

    /** A user without ADMINISTER is refused by every one of the seven, not merely by the first. */
    @Test
    void aBystanderCannotReachAnyFieldValidator(JenkinsRule r) {
        lockDown(r);
        for (NamedCheck check : validators(r)) {
            asUser(
                    "bob",
                    () -> assertThrows(
                            AccessDeniedException.class,
                            () -> check.call().get(),
                            check.name() + " answered a user without ADMINISTER"));
        }
    }

    /** And the guard does not break the form for the administrator it was added to protect. */
    @Test
    void anAdministratorStillGetsAnAnswerFromEveryValidator(JenkinsRule r) {
        lockDown(r);
        for (NamedCheck check : validators(r)) {
            asUser(
                    "alice",
                    () -> assertEquals(
                            FormValidation.Kind.OK,
                            check.call().get().kind,
                            check.name() + " refused a valid value for an administrator"));
        }
    }

    /**
     * The POST guard, asserted over HTTP rather than by reading the annotation back. {@code @POST}
     * cancels request handling on a GET rather than sending 405, so the route falls through to a 404 —
     * unlike {@code @RequirePOST} on {@code doTestConnection}, which answers 405. Asserting the status
     * this way means the test fails if the annotation is dropped, which reading it via reflection would
     * not distinguish from the annotation being present but unwired.
     */
    @Test
    void aGetIsNotRoutedToAnyFieldValidator(JenkinsRule r) throws Exception {
        lockDown(r);
        JenkinsRule.WebClient wc = r.createWebClient().login("alice");
        Consumer<String> assertGetIsNotRouted = url -> {
            try {
                wc.assertFails(url, 404);
            } catch (Exception e) {
                throw new AssertionError(url + " was reachable over GET", e);
            }
        };
        String cloud = "descriptorByName/io.jenkins.plugins.xcpng.XcpngCloud/";
        String tpl = "descriptorByName/io.jenkins.plugins.xcpng.XcpngTemplate/";
        assertGetIsNotRouted.accept(cloud + "checkPoolUrl?value=https://pool.example.test");
        assertGetIsNotRouted.accept(tpl + "checkTemplateName?value=jenkins-agent-debian13");
        assertGetIsNotRouted.accept(tpl + "checkLabelString?value=xcpng-linux");
        assertGetIsNotRouted.accept(tpl + "checkNumCpus?value=2");
        assertGetIsNotRouted.accept(tpl + "checkMemoryMb?value=2048");
        assertGetIsNotRouted.accept(tpl + "checkMinInstances?value=0");
        assertGetIsNotRouted.accept(tpl + "checkSshAuthorizedKey?value=");
    }

    /**
     * The counterpart, and the one that would catch a broken form: the same URLs answer over POST. Since
     * Jenkins 2.285 field validation POSTs by default ({@code registerValidator} in
     * {@code hudson-behavior.js} reads {@code checkMethod || "post"}), so no {@code checkMethod="post"}
     * is needed in {@code config.jelly} at this baseline — which is what this asserts.
     */
    @Test
    void aPostReachesTheValidatorAndItAnswers(JenkinsRule r) throws Exception {
        lockDown(r);
        JenkinsRule.WebClient wc = r.createWebClient().login("alice");
        String body = postCheck(r, wc, "io.jenkins.plugins.xcpng.XcpngTemplate", "checkLabelString", "");
        assertTrue(
                body.contains("error") || body.contains("required"),
                "a blank label should come back as an error, got: " + body);
        String ok = postCheck(r, wc, "io.jenkins.plugins.xcpng.XcpngTemplate", "checkLabelString", "xcpng-linux");
        assertTrue(ok.isBlank() || !ok.contains("error"), "a valid label should not come back as an error: " + ok);
    }

    /**
     * Sends the check the way the browser does: POST, with the CSRF crumb. Without the crumb this is a
     * 403, which is the other half of {@code @POST} doing its job — so the crumb is added deliberately
     * rather than by turning the issuer off for the test.
     */
    private static String postCheck(
            JenkinsRule r, JenkinsRule.WebClient wc, String descriptor, String method, String value) throws Exception {
        WebRequest req = new WebRequest(
                new java.net.URI(r.getURL() + "descriptorByName/" + descriptor + "/" + method).toURL(),
                HttpMethod.POST);
        // addCrumb sets the parameter list rather than appending to it, so read it back before adding
        // the field under test; that holds whichever way the harness implements it.
        wc.addCrumb(req);
        List<NameValuePair> params = new ArrayList<>(req.getRequestParameters());
        params.add(new NameValuePair("value", value));
        req.setRequestParameters(params);
        return wc.getPage(req).getWebResponse().getContentAsString();
    }
}

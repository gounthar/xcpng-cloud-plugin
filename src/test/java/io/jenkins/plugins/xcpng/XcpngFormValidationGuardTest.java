package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                new NamedCheck(
                        "doCheckTemplateName",
                        () -> tpl.doCheckTemplateName("jenkins-agent-debian13", null, null, null)),
                new NamedCheck("doCheckLabelString", () -> tpl.doCheckLabelString("xcpng-linux")),
                new NamedCheck("doCheckNumCpus", () -> tpl.doCheckNumCpus("2")),
                new NamedCheck("doCheckMemoryMb", () -> tpl.doCheckMemoryMb("2048")),
                new NamedCheck("doCheckMinInstances", () -> tpl.doCheckMinInstances(null, "0")),
                new NamedCheck("doCheckSshAuthorizedKey", () -> tpl.doCheckSshAuthorizedKey("")));
    }

    private record NamedCheck(String name, java.util.function.Supplier<FormValidation> call) {}

    /** One validator, with a value it must accept and a value it must reject. */
    private record Endpoint(String descriptor, String method, String accepted, String rejected) {}

    private static final String CLOUD = "io.jenkins.plugins.xcpng.XcpngCloud";
    private static final String TEMPLATE = "io.jenkins.plugins.xcpng.XcpngTemplate";

    /**
     * All seven, so neither the GET nor the POST assertion can be satisfied by one representative method.
     * {@code checkMinInstances} is reached over HTTP with no cloud in the ancestor path, so it sees a null
     * cloud and falls back to the plain non-negative rule.
     */
    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(CLOUD, "checkPoolUrl", "https://pool.example.test", "http://pool.example.test"),
            new Endpoint(TEMPLATE, "checkTemplateName", "jenkins-agent-debian13", ""),
            new Endpoint(TEMPLATE, "checkLabelString", "xcpng-linux", ""),
            new Endpoint(TEMPLATE, "checkNumCpus", "2", "0"),
            new Endpoint(TEMPLATE, "checkMemoryMb", "2048", "0"),
            new Endpoint(TEMPLATE, "checkMinInstances", "0", "-1"),
            new Endpoint(TEMPLATE, "checkSshAuthorizedKey", "", "-----BEGIN OPENSSH PRIVATE KEY-----"));

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
        for (Endpoint e : ENDPOINTS) {
            assertGetIsNotRouted.accept("descriptorByName/" + e.descriptor() + "/" + e.method() + "?value="
                    + java.net.URLEncoder.encode(e.accepted(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * The counterpart, and the one that would catch a broken form: the same URLs answer over POST, for
     * every validator rather than one representative. Since Jenkins 2.285 field validation POSTs by
     * default ({@code registerValidator} in {@code hudson-behavior.js} reads
     * {@code checkMethod || "post"}), so no {@code checkMethod="post"} is needed in
     * {@code config.jelly} at this baseline, and this is what asserts that.
     *
     * <p>Each validator gets both halves: a value it must accept, and a value it must reject. Accepting
     * everything would pass against a validator wired to a method that never returns an error, which is
     * the too-agreeable fixture this repo keeps getting caught by.
     */
    @Test
    void aPostReachesEveryValidatorAndItAnswers(JenkinsRule r) throws Exception {
        lockDown(r);
        JenkinsRule.WebClient wc = r.createWebClient().login("alice");
        for (Endpoint e : ENDPOINTS) {
            String ok = postCheck(r, wc, e.descriptor(), e.method(), e.accepted());
            assertFalse(
                    isError(ok), e.method() + " rejected " + quoted(e.accepted()) + ", which it should accept: " + ok);
            String bad = postCheck(r, wc, e.descriptor(), e.method(), e.rejected());
            assertTrue(
                    isError(bad),
                    e.method() + " accepted " + quoted(e.rejected()) + ", which it should reject: " + bad);
        }
    }

    /**
     * {@code FormValidation.ok()} renders as an empty {@code <div/>} and an error as
     * {@code <div class="error">}, both over HTTP 200, so the status line says nothing about the verdict
     * and the body has to be read.
     */
    private static boolean isError(String body) {
        return body.contains("class=\"error\"");
    }

    private static String quoted(String value) {
        return value.isEmpty() ? "the empty string" : "\"" + value + "\"";
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

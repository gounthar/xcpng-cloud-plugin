package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Node;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import io.jenkins.plugins.xcpng.client.FakeHypervisorClient;
import java.util.List;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Saving the node configuration page of a provisioned agent.
 *
 * <p>These run the real endpoint rather than calling {@code reconfigure} directly, because both halves of
 * #185 sat outside that method: one in the view, which has no {@code numExecutors} field for
 * {@code Computer.doConfigSubmit} to read before it delegates, and one in core's default rebuild, which
 * constructs a replacement node instead of editing this one. A unit test on {@code reconfigure} alone
 * would have passed against the broken page.
 *
 * <p>The load-bearing assertion is not that Save returns 200. It is that the node Jenkins holds afterwards
 * is still the node that knows which VM to destroy.
 */
@WithJenkins
class XcpngAgentReconfigureTest {

    private static final String POOL_URL = "https://pool.example.test";
    private static final String CREDENTIALS_ID = "xcpng-creds";
    // A real 32-byte SHA-256, because XcpngCloud runs it through CertificateFingerprint.normalize and
    // quietly stores null for anything that will not parse. A short stand-in would make the assertion
    // that this survives a save pass against a null on both sides.
    private static final String PINNED_FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
    private static final XcpngTemplate LINUX_TEMPLATE =
            new XcpngTemplate("jenkins-golden-debian", "xcpng-linux", 2, 2048);
    private static final String AGENT_NAME = "xcpng-agent-1";
    private static final String VM_REF = "vm/xcpng-agent-1/1";

    /**
     * Set explicitly because the default is 90 seconds and this page can take most of that.
     * {@code WebClientOptions} initialises {@code timeout_} to 90000, and {@code JenkinsRule.WebClient} does
     * not override it — its own {@code setTimeout(60*1000)} is commented out, above a note saying the value
     * should be long enough not to produce false positives on slow systems.
     *
     * <p>Every test here pays the cost, not just the first: each one gets a fresh Jenkins, so the Jelly for
     * this page and for everything core wraps around it is compiled again. Measured on a Windows-mounted
     * checkout, the six loads took 58.1, 51.8, 42.6, 48.0, 47.7 and 44.3 seconds — a margin against 90000 thin
     * enough that three of the six expired on an earlier run and the same one then failed in isolation.
     *
     * <p>The failure mode is why this is worth a comment rather than a bare number: expiring raises
     * {@code SocketTimeoutException: Read timed out} on the initial GET, which is indistinguishable from a
     * server deadlocked while rendering the view under test. It is not one — the Jetty threads are idle in a
     * dump taken while it waits.
     */
    private static final int PAGE_TIMEOUT_MILLIS = 180_000;

    /**
     * A registered agent with a VM reference on it, which is the state every assertion here cares about: a
     * rebuild-based reconfigure loses exactly this, and loses it silently.
     */
    private static XcpngAgent registeredAgent(JenkinsRule r) throws Exception {
        XcpngCloud cloud =
                new XcpngCloud("xcpng", POOL_URL, CREDENTIALS_ID, PINNED_FINGERPRINT, 3, List.of(LINUX_TEMPLATE));
        cloud.setClientFactory(c -> new FakeHypervisorClient("jenkins-golden-debian"));
        cloud.setWaitForOnline(false);
        r.jenkins.clouds.add(cloud);

        XcpngAgent agent = cloud.createAgent(
                LINUX_TEMPLATE,
                AGENT_NAME,
                new ProvisioningActivity.Id("xcpng", "jenkins-golden-debian", AGENT_NAME),
                false);
        agent.setVmRef(VM_REF);
        r.jenkins.addNode(agent);
        return agent;
    }

    private static HtmlForm configForm(JenkinsRule r, String agentName) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient();
        wc.getOptions().setTimeout(PAGE_TIMEOUT_MILLIS);
        return wc.goTo("computer/" + agentName + "/configure").getFormByName("config");
    }

    private static XcpngAgent nodeInJenkins(JenkinsRule r) {
        Node node = r.jenkins.getNode(AGENT_NAME);
        assertNotNull(node, "the agent must still be registered after a save");
        return (XcpngAgent) node;
    }

    /**
     * #185 end to end, and the reason {@code reconfigure} edits this node rather than letting core build a
     * replacement.
     *
     * <p>Pressing Save used to return 500, first on a {@code JSONException} for the missing
     * {@code numExecutors} key and then, past that, on a {@code NoStaplerConstructorException} from the
     * rebuild. Submitting the form unchanged is the smallest thing an operator can do to this page.
     *
     * <p>The status code is the least of it. None of the fields asserted below is on the form or derivable
     * from it, so a rebuilt node comes back with a null VM reference and the clone it was tracking is
     * orphaned: nothing left in Jenkins knows which VM to destroy, and it runs until somebody finds it by
     * hand. Asserting the node identity as well as the fields is deliberate — a replacement that happened to
     * copy the fields would still be a different object, and the next design that reintroduces a rebuild
     * would fail here rather than pass quietly.
     */
    @Test
    void savingKeepsEverythingNeededToDestroyTheVm(JenkinsRule r) throws Exception {
        XcpngAgent before = registeredAgent(r);

        HtmlPage saved = r.submit(configForm(r, AGENT_NAME));

        assertEquals(200, saved.getWebResponse().getStatusCode(), "Save must not fail");
        XcpngAgent after = nodeInJenkins(r);
        assertSame(before, after, "the saved node must be the node that was edited, not a replacement");
        assertEquals(VM_REF, after.getVmRef(), "losing the VM reference leaks the clone and its disks");
        assertEquals("xcpng", after.getCloudName());
        assertEquals(POOL_URL, after.getPoolUrl());
        assertEquals(CREDENTIALS_ID, after.getCredentialsId());
        assertEquals(PINNED_FINGERPRINT, after.getCertificateFingerprint());
        assertNotNull(after.getId(), "the cloud-stats activity id must survive a save");
    }

    /**
     * Guards the view, not the Java. {@code Computer.doConfigSubmit} reads {@code numExecutors} out of the
     * submitted JSON with {@code JSONObject.getString}, which throws on an absent key, so the field has to
     * be on the form even though nothing in this plugin reads it back. Removing it again is an easy and
     * well-intentioned edit — it was our own request on #182 — and this is what catches it.
     */
    @Test
    void theFormCarriesAReadOnlyExecutorCount(JenkinsRule r) throws Exception {
        registeredAgent(r);

        HtmlInput executors = configForm(r, AGENT_NAME).getInputByName("_.numExecutors");

        assertEquals("1", executors.getValue());
        assertTrue(
                executors.hasAttribute("readonly"),
                "a plain input invites an operator to set a value #23 then ignores; core's readOnlyMode is "
                        + "not the answer either, it renders a <pre> that submits nothing");
    }

    /**
     * A tampered executor count does not reach the node. {@code readonly} constrains a browser and says
     * nothing about what a POST may carry, so this strips the attribute before submitting, which is what any
     * client that is not a browser does for free. #23 is why it matters: a second executor on a single-use
     * agent has its VM destroyed under it the moment the first build finishes.
     *
     * <p>Read what this pins carefully, because it is weaker than it looks and mutation testing is the only
     * reason that is known. <strong>Today it would pass even with {@code setNumExecutors} deleted from
     * {@code reconfigure}</strong>: that method never binds {@code numExecutors}, so the field simply keeps
     * the value the constructor gave it and the re-assertion is a no-op. Deleting the line and running this
     * test was measured, and it passed.
     *
     * <p>It is still worth having, because the submitted form genuinely carries the tampered value — binding
     * {@code numExecutors} from the form with no re-assertion after it fails here with <em>expected 1 but was
     * 4</em>, also measured. So this pins the contract rather than the current mechanism, and it fails the
     * day someone replaces the hand-applied fields with a blanket {@code req.bindJSON(this, form)}, which is
     * the obvious refactor and would bind every setter {@code Slave} exposes. The re-assertion sits after the
     * field assignments for exactly that day.
     */
    @Test
    void anExecutorCountPostedAnywayIsIgnored(JenkinsRule r) throws Exception {
        registeredAgent(r);

        HtmlForm form = configForm(r, AGENT_NAME);
        HtmlInput executors = form.getInputByName("_.numExecutors");
        executors.removeAttribute("readonly");
        executors.setValue("4");
        r.submit(form);

        assertEquals(
                XcpngAgent.EXECUTORS_PER_AGENT,
                nodeInJenkins(r).getNumExecutors(),
                "the form is a request, not an authority");
    }

    /**
     * A submitted usage mode does not reach the node either, for the same reason as the executor count and
     * with more teeth. {@link XcpngAgent#USAGE_MODE} is {@code EXCLUSIVE} because {@code NORMAL} means "use
     * this node as much as possible", which makes any unlabeled build eligible for a single-use VM: it takes
     * a warm spare held for a label, and the VM is destroyed when that build finishes, so the labeled build
     * the pool exists for then waits for a cold clone.
     *
     * <p>{@code readResolve} already re-asserts the mode on reload, which covers a controller restart and
     * nothing else. Until Save worked there was no other way in. Making it work opened one, so this closes
     * it at the same layer: the mode is re-asserted rather than read, and the run that added this test
     * against the unfixed code failed here with {@code NORMAL}, which is what makes it worth keeping.
     *
     * <p>Deliberately indifferent to whether the page still renders a Usage control. The first version of
     * this test reached for {@code getSelectByName("mode")}, which pinned the presence of the selector
     * rather than the behaviour, and broke #186 when it removed the control this very invariant makes
     * pointless. What is being asserted is that a submitted mode does not reach the node, and that has to
     * hold whether the value arrives from a rendered selector or from a POST that never saw the page.
     */
    @Test
    void aSubmittedUsageModeIsIgnored(JenkinsRule r) throws Exception {
        registeredAgent(r);

        HtmlForm form = configForm(r, AGENT_NAME);
        putMode(form, "NORMAL");

        // The payload really carries NORMAL. Without this the test could pass because nothing was
        // submitted at all, which is how the executor-count test in #187 turned out to pin nothing.
        List<String> modeFields = form.getElementsByAttribute("input", "name", "mode").stream()
                .map(e -> e.getAttribute("value"))
                .toList();
        assertEquals(List.of("NORMAL"), modeFields, "exactly one mode field, carrying NORMAL");

        // A positive control in the same submit: something the form IS allowed to change must change,
        // or an unchanged mode proves only that the submit never happened.
        form.getTextAreaByName("_.nodeDescription").setText("mode probe");
        r.submit(form);

        XcpngAgent after = nodeInJenkins(r);
        assertEquals("mode probe", after.getNodeDescription(), "the submit has to have taken effect");
        assertEquals(
                XcpngAgent.USAGE_MODE,
                after.getMode(),
                "NORMAL would make every unlabeled build eligible for a single-use VM");
    }

    /**
     * Put {@code mode} on the form and submit it, through the Usage selector when the view offers one and
     * as an injected field when it does not.
     *
     * <p>Never both: two fields of the same name would leave the submitted JSON ambiguous, and the point of
     * the test is to know exactly what was posted. The injected path is not a lesser substitute either, it
     * is the threat model written down. {@code readonly} and an absent control both constrain a browser and
     * neither constrains a client that simply posts the field.
     */
    @SuppressWarnings("SameParameterValue")
    private static void putMode(HtmlForm form, String mode) {
        // Drop the rendered control if the view still has one, then post the field directly. One path,
        // so it is exercised on every run rather than only on whichever tree the test happens to meet:
        // branching on the selector left the injected half dead on a tree that still renders it, which
        // is the half that has to keep working when #186 removes the control.
        //
        // Removing rather than reusing also keeps it to a single field of that name. Two would leave the
        // submitted JSON ambiguous, and knowing exactly what was posted is the entire point.
        form.getSelectsByName("mode").forEach(DomNode::remove);

        DomElement injected = ((HtmlPage) form.getPage()).createElement("input");
        injected.setAttribute("type", "hidden");
        injected.setAttribute("name", "mode");
        injected.setAttribute("value", mode);
        form.appendChild(injected);
    }

    /**
     * The fields an operator is actually offered have to round-trip, or the page is decoration.
     *
     * <p>The node property is here rather than in a test of its own because it shares this scenario and each
     * page load costs the better part of a minute. It is the part worth having: node properties go through
     * {@code DescribableList.rebuild}, the one step of {@code reconfigure} that is not a plain setter, and
     * the step core wraps in a bind interceptor in the implementation being overridden. A property the form
     * was never asked to change has to survive the save.
     */
    @Test
    void theEditableFieldsAndAnExistingNodePropertyRoundTrip(JenkinsRule r) throws Exception {
        XcpngAgent agent = registeredAgent(r);
        agent.getNodeProperties()
                .add(new EnvironmentVariablesNodeProperty(new EnvironmentVariablesNodeProperty.Entry("FOO", "bar")));

        HtmlForm form = configForm(r, AGENT_NAME);
        form.getTextAreaByName("_.nodeDescription").setText("held for the release build");
        form.getInputByName("_.labelString").setValue("xcpng-linux xcpng-release");
        r.submit(form);

        XcpngAgent after = nodeInJenkins(r);
        assertEquals("held for the release build", after.getNodeDescription());
        assertEquals("xcpng-linux xcpng-release", after.getLabelString());
        EnvironmentVariablesNodeProperty property =
                after.getNodeProperties().get(EnvironmentVariablesNodeProperty.class);
        assertNotNull(property, "a configured node property must not be dropped by a save");
        assertEquals("bar", property.getEnvVars().get("FOO"));
    }
}

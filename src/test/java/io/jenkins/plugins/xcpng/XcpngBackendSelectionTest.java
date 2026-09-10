package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.xcpng.client.HypervisorClient;
import io.jenkins.plugins.xcpng.client.XapiClient;
import io.jenkins.plugins.xcpng.client.XoRestClient;
import java.util.List;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Selecting the backend a cloud speaks, and the consequences of getting the pairing wrong.
 *
 * <p>The interesting property here is not that two classes exist -- {@code XoRestClientTest} already
 * covers the XO client itself -- but that the choice actually reaches the four places a client is
 * constructed, and that a credential of the wrong kind is reported as that rather than as a cast failure
 * or an authentication error against the appliance.
 *
 * <p>Nothing here opens a socket. Both clients build their transport in the constructor and connect on
 * the first call, so asserting which one came back costs no network and needs no fixture.
 */
@WithJenkins
class XcpngBackendSelectionTest {

    private static final String POOL_URL = "https://192.168.1.87";

    private static final String XO_URL = "https://192.168.1.5";

    private static final String PINNED_FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    private static final String PASSWORD_ID = "xcpng-root";

    private static final String TOKEN_ID = "xo-token";

    /** A username/password credential, the kind the XAPI backend authenticates with. */
    private static void addPasswordCredential(String id) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, id, "XAPI", "root", "hunter2"));
        SystemCredentialsProvider.getInstance().save();
    }

    /** A secret-text credential, the kind the XO backend authenticates with. */
    private static void addTokenCredential(String id) throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL, id, "XO token", Secret.fromString("an-xo-authentication-token")));
        SystemCredentialsProvider.getInstance().save();
    }

    private static XcpngCloud cloud(String name, String url, String credentialsId) {
        return new XcpngCloud(name, url, credentialsId, PINNED_FINGERPRINT, 2, List.of());
    }

    // ---- What the choice means when nothing was chosen ----

    /**
     * A cloud that was never told which backend to speak reports XAPI. Every persisted cloud predates this
     * field, so the absence has to resolve to the only backend that existed when they were written; a null
     * leaking out instead would be an NPE somewhere in provisioning.
     */
    @Test
    void aCloudThatNamesNoBackendSpeaksXapi(JenkinsRule r) {
        assertEquals(XcpngBackend.XAPI, cloud("unset", POOL_URL, PASSWORD_ID).getBackend());
    }

    /**
     * The form and the configuration-as-code document both submit a name rather than a constant, and both
     * can submit nothing at all -- a half-filled form, or a document written before this field existed.
     * Neither absence nor an unrecognised name is an error at this level: this is what a {@code doCheck} on
     * an incomplete form calls, and it has nowhere useful to report one.
     */
    @Test
    void anAbsentOrUnrecognisedNameParsesAsXapi(JenkinsRule r) {
        assertEquals(XcpngBackend.XO, XcpngBackend.parse("XO"), "a named constant must parse to itself");
        assertEquals(XcpngBackend.XO, XcpngBackend.parse("  XO  "), "the form submits whitespace of its own");
        assertEquals(XcpngBackend.XAPI, XcpngBackend.parse(null));
        assertEquals(XcpngBackend.XAPI, XcpngBackend.parse(""));
        assertEquals(XcpngBackend.XAPI, XcpngBackend.parse("xo"), "the constant names are case-sensitive");
        assertEquals(XcpngBackend.XAPI, XcpngBackend.parse("XenOrchestra"));
    }

    // ---- Which client comes back ----

    /**
     * The XO selection has to reach the construction, and this is the assertion the whole change exists
     * for. Asserted by class rather than by behaviour because the two clients differ in every call they
     * make; if the wrong one came back, nothing later in provisioning would say so in terms an operator
     * could act on.
     */
    @Test
    void theXoBackendBuildsAnXoClient(JenkinsRule r) throws Exception {
        addTokenCredential(TOKEN_ID);
        try (HypervisorClient client =
                XcpngCloud.openClient(XO_URL, TOKEN_ID, PINNED_FINGERPRINT, XcpngBackend.XO, "a test")) {
            assertInstanceOf(XoRestClient.class, client);
        }
    }

    /**
     * The control for the test above: the default selection still builds the XAPI client. Without this,
     * a change that returned an {@code XoRestClient} unconditionally would pass the XO assertion and be
     * caught only by the rest of the suite failing for unrelated-looking reasons.
     */
    @Test
    void theXapiBackendBuildsAnXapiClient(JenkinsRule r) throws Exception {
        addPasswordCredential(PASSWORD_ID);
        try (HypervisorClient client =
                XcpngCloud.openClient(POOL_URL, PASSWORD_ID, PINNED_FINGERPRINT, XcpngBackend.XAPI, "a test")) {
            assertInstanceOf(XapiClient.class, client);
        }
        // And a null selection, which is what an agent snapshotted before the backend existed carries.
        try (HypervisorClient client = XcpngCloud.openClient(POOL_URL, PASSWORD_ID, null, null, "a test")) {
            assertInstanceOf(XapiClient.class, client);
        }
    }

    /**
     * A {@code config.xml} written before the backend field existed reloads with it null: XStream runs no
     * constructor, so the field initializer that covers a fresh cloud does not apply. Every such cloud was
     * provisioning over XAPI when it was saved, and it has to keep doing so. Without the normalisation in
     * {@code readResolve} the field stays null and the value written back out is an absence rather than a
     * name, which is what the getter has to paper over on every read instead.
     */
    @Test
    void aConfigPersistedBeforeTheBackendExistedReloadsAsXapi(JenkinsRule r) {
        String xml = "<io.jenkins.plugins.xcpng.XcpngCloud>\n"
                + "  <name>xcpng</name>\n"
                + "  <poolUrl>https://pool.example.test</poolUrl>\n"
                + "  <maxInstances>2</maxInstances>\n"
                + "</io.jenkins.plugins.xcpng.XcpngCloud>\n";
        XcpngCloud cloud = (XcpngCloud) jenkins.model.Jenkins.XSTREAM2.fromXML(xml);

        assertEquals(XcpngBackend.XAPI, cloud.getBackend(), "a missing backend must read as XAPI, not as null");
        assertTrue(
                jenkins.model.Jenkins.XSTREAM2.toXML(cloud).contains("<backend>XAPI</backend>"),
                "the reloaded cloud must write the backend out by name rather than as an absence");
    }

    // ---- The pairing, and what a mismatch says ----

    /**
     * A username/password credential under an XO backend is the mistake this pairing invites: the two
     * fields sit next to each other in the form, switching one does not refill the other, and nothing at
     * bind time objects. It has to fail by naming the kind the backend needs. A widened lookup that found
     * the credential and cast it would instead throw a {@code ClassCastException} from inside the client,
     * which reads as a bug in the plugin rather than as a configuration error.
     */
    @Test
    void theXoBackendRefusesAUsernamePasswordCredential(JenkinsRule r) throws Exception {
        addPasswordCredential(PASSWORD_ID);
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> XcpngCloud.openClient(XO_URL, PASSWORD_ID, null, XcpngBackend.XO, "cloud 'xo-lab'"));
        assertTrue(e.getMessage().contains("cloud 'xo-lab'"), e.getMessage());
        assertTrue(e.getMessage().contains("secret-text"), "the message must name the kind needed: " + e.getMessage());
    }

    /** The mirror: a token under the XAPI backend is equally unusable, and equally has to say so. */
    @Test
    void theXapiBackendRefusesASecretTextCredential(JenkinsRule r) throws Exception {
        addTokenCredential(TOKEN_ID);
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> XcpngCloud.openClient(POOL_URL, TOKEN_ID, null, XcpngBackend.XAPI, "cloud 'pool-lab'"));
        assertTrue(e.getMessage().contains("cloud 'pool-lab'"), e.getMessage());
    }

    /**
     * {@code XoRestClient}'s base URL is {@code @NonNull}, so a cloud saved with the XO backend and no URL
     * has to be answered before the constructor rather than by it: an unguarded null would surface as a
     * bare {@code NullPointerException} naming neither the cloud nor the field. The XAPI half of this is
     * #101 and is deliberately still failing deeper in.
     */
    @Test
    void theXoBackendNamesTheCloudWhenTheUrlIsMissing(JenkinsRule r) throws Exception {
        addTokenCredential(TOKEN_ID);
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> XcpngCloud.openClient("  ", TOKEN_ID, null, XcpngBackend.XO, "cloud 'xo-lab'"));
        assertTrue(e.getMessage().contains("cloud 'xo-lab'"), e.getMessage());
    }

    // ---- What the form says before anything is saved ----

    /**
     * The credential check is a warning rather than an error, and it fires only on the credential field's
     * own change -- an operator who picks the credential first and the backend second sees nothing. It
     * catches the common ordering, which is worth having; the assertion that matters is that it does not
     * cry wolf on a correct pair.
     */
    @Test
    void theCredentialCheckWarnsOnlyOnAMismatchedPair(JenkinsRule r) throws Exception {
        addPasswordCredential(PASSWORD_ID);
        addTokenCredential(TOKEN_ID);
        XcpngCloud.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class);

        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckCredentialsId(PASSWORD_ID, POOL_URL, "XAPI").kind,
                "a username/password credential under XAPI is the correct pair");
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckCredentialsId(TOKEN_ID, XO_URL, "XO").kind,
                "a secret-text credential under XO is the correct pair");
        assertEquals(
                FormValidation.Kind.WARNING,
                d.doCheckCredentialsId(PASSWORD_ID, XO_URL, "XO").kind,
                "a username/password credential under XO must be flagged");
        assertEquals(
                FormValidation.Kind.WARNING,
                d.doCheckCredentialsId(TOKEN_ID, POOL_URL, "XAPI").kind,
                "a secret-text credential under XAPI must be flagged");
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckCredentialsId("", POOL_URL, "XAPI").kind,
                "an empty field is a fresh form, not a mistake to nag about");
    }

    /**
     * Test connection is the authoritative gate on the pairing, since the field check above cannot fire on
     * a backend change. It has to report the mismatch as such rather than attempting the connection: a
     * token sent to XAPI as a password would come back as an authentication failure, which sends the
     * operator to check a password that is not the problem.
     */
    @Test
    void testConnectionNamesTheCredentialKindItNeeds(JenkinsRule r) throws Exception {
        addPasswordCredential(PASSWORD_ID);
        XcpngCloud.DescriptorImpl d = r.jenkins.getDescriptorByType(XcpngCloud.DescriptorImpl.class);

        FormValidation v = d.doTestConnection(XO_URL, PASSWORD_ID, null, "XO");

        assertEquals(FormValidation.Kind.ERROR, v.kind);
        assertTrue(v.getMessage().contains("secret-text"), "the message must name the kind needed: " + v.getMessage());
    }

    // ---- What survives a save ----

    /** The choice is configuration, so it has to come back off the round trip the UI performs. */
    @Test
    void theBackendSurvivesAConfigRoundTrip(JenkinsRule r) throws Exception {
        XcpngCloud configured = cloud("xo-lab", XO_URL, TOKEN_ID);
        configured.setBackend(XcpngBackend.XO);
        r.jenkins.clouds.add(configured);

        r.configRoundtrip();

        XcpngCloud reloaded = (XcpngCloud) r.jenkins.clouds.getByName("xo-lab");
        assertEquals(XcpngBackend.XO, reloaded.getBackend(), "the backend must survive a save");
    }
}

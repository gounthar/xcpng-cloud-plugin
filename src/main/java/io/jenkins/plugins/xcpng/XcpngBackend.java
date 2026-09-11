package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Which hypervisor API a cloud speaks: XAPI straight to the pool, or the Xen Orchestra REST API on an
 * appliance in front of it.
 *
 * <p>This is a staging device rather than a permanent axis of configuration. Issue #89 decided that XO
 * becomes the backend and {@code XapiClient} goes away; the second choice exists so the new client can be
 * exercised against the real pool while the plugin still works on the old one. When step 3 removes
 * {@code XapiClient}, this enum and the field selecting it go with it.
 *
 * <p>Persisted by name in {@code config.xml}, so the constant names are configuration and cannot be
 * renamed without a migration. {@link #XAPI} is the default, and is what a cloud saved before this
 * existed deserializes to -- see {@code XcpngCloud.readResolve}.
 */
public enum XcpngBackend {

    /**
     * JSON-RPC to {@code /jsonrpc} on a pool master, authenticated with a username and password. The
     * original backend, and still the default.
     */
    XAPI(CredentialKind.USERNAME_PASSWORD),

    /**
     * The Xen Orchestra REST API under {@code /rest/v0} on an appliance, authenticated with a token sent
     * as {@code Cookie: authenticationToken=<token>}. Requires XO 6.5.0 or newer, the release that added
     * {@code PATCH /vms/{id}} -- the route both the xenstore seed and the #28 secret scrub need.
     */
    XO(CredentialKind.SECRET_TEXT);

    /**
     * What kind of credential this backend authenticates with. The two are not interchangeable and the
     * mismatch is silent at bind time -- a cloud can be saved with a username/password credential and an
     * XO backend, and nothing in the form model objects -- so the pair is checked where it is used.
     */
    public enum CredentialKind {
        USERNAME_PASSWORD,
        SECRET_TEXT
    }

    private final CredentialKind credentialKind;

    XcpngBackend(CredentialKind credentialKind) {
        this.credentialKind = credentialKind;
    }

    /** The credential kind this backend authenticates with. */
    @NonNull
    public CredentialKind getCredentialKind() {
        return credentialKind;
    }

    /**
     * Resolve a persisted or bound value, defaulting to {@link #XAPI}.
     *
     * <p>Null is the ordinary case rather than an error: a cloud saved before this field existed carries
     * no value, and a configuration-as-code document that does not mention a backend is asking for the
     * default. A name that is not one of these constants never reaches here at all -- XStream and JCasC
     * both fail the bind -- so this method has only the absent case to answer for.
     */
    @NonNull
    public static XcpngBackend resolve(@CheckForNull XcpngBackend backend) {
        return backend == null ? XAPI : backend;
    }

    /**
     * Read a backend name off a form submission, defaulting to {@link #XAPI}.
     *
     * <p>Absent is the ordinary case: a {@code doCheck} on a form the operator has half filled, or one
     * whose enclosing cloud predates this field. An unparseable name is not treated as an error either,
     * and that is deliberate -- the callers are form validators and a connection test, none of which is
     * the right place to report that the browser submitted a value the enum does not name. The bind
     * itself refuses that, before anything is saved.
     */
    @NonNull
    public static XcpngBackend parse(@CheckForNull String backend) {
        if (backend == null || backend.isBlank()) {
            return XAPI;
        }
        try {
            return valueOf(backend.trim());
        } catch (IllegalArgumentException notANamedConstant) {
            return XAPI;
        }
    }
}

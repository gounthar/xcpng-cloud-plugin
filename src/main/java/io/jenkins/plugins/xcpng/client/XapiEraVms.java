package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

/**
 * What a client can do about a VM the removed XAPI backend provisioned (#364): find it by the ref that backend
 * recorded, and take it over by stamping this backend's owner markers on it. Only the Xen Orchestra backend can;
 * a separate interface rather than two more verbs on {@link HypervisorClient}, because nothing in a VM's ordinary
 * lifecycle needs either, and a caller asks for it with {@code instanceof}.
 */
public interface XapiEraVms {

    /** See {@link XoRestClient#lookUpXapiEraVm}. */
    @NonNull
    Optional<XapiEraVm> lookUpXapiEraVm(@NonNull String xapiRef);

    /** See {@link XoRestClient#adoptXapiEraVm}. */
    @NonNull
    VmRef adoptXapiEraVm(@NonNull XapiEraVm vm, @NonNull String owner);
}

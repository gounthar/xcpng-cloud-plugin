package io.jenkins.plugins.xcpng.client;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Thrown by {@link HypervisorClient#cloneFromTemplate} when the clone was created, a later step failed, and
 * the backend's own cleanup of the partial clone failed too (#310).
 *
 * <p>Every other failure of that verb leaves nothing behind, so a caller can treat it as "no VM". This one
 * does leave a VM, and {@link #getVmRef()} is the only handle anything still holds on it: the caller must
 * keep it, so a later teardown can retry the destroy or record it as leaked. Logging it and dropping it is
 * the leak, and the VM may carry no owner tag yet, so no sweep selecting on the marker would ever find it.
 *
 * <p>The cause is the failure that stopped the clone, which is what the caller needs to report; the cleanup
 * failure travels as a suppressed exception.
 */
public class CloneLeftBehindException extends HypervisorException {

    private static final long serialVersionUID = 1L;

    private final String vmRef;

    public CloneLeftBehindException(@NonNull String message, @NonNull VmRef vm, @NonNull Throwable cause) {
        super(message, cause);
        this.vmRef = vm.value();
    }

    /** The clone that was left behind. */
    @NonNull
    public VmRef getVmRef() {
        return new VmRef(vmRef);
    }
}

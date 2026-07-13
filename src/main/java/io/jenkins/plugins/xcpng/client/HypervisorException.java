package io.jenkins.plugins.xcpng.client;

/**
 * Thrown when a hypervisor operation fails.
 *
 * <p>Unchecked on purpose: the lifecycle verbs are called from Jenkins provisioning callbacks that
 * cannot usefully recover mid-clone, so a failure aborts the attempt and Jenkins retries the queue
 * item later. Backend-specific error handling (XAPI {@code SESSION_INVALID} re-login, master
 * redirect) stays inside the implementation and never surfaces as this exception.
 */
public class HypervisorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HypervisorException(String message) {
        super(message);
    }

    public HypervisorException(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.jenkins.plugins.xcpng.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thrown when a hypervisor operation fails.
 *
 * <p>Unchecked on purpose: the lifecycle verbs are called from Jenkins provisioning callbacks that
 * cannot usefully recover mid-clone, so a failure aborts the attempt and Jenkins retries the queue
 * item later. Backend-specific error handling (XAPI {@code SESSION_INVALID} re-login, master
 * redirect) stays inside the implementation and never surfaces as this exception.
 *
 * <p>{@link #getErrorCode()} and {@link #getErrorParams()} carry the backend's own error envelope
 * when there was one, so a caller can branch on a specific failure without reading the human-facing
 * message back. Teardown needs exactly that: {@code HANDLE_INVALID} naming the VM being destroyed
 * means the VM is already gone, which is the goal state rather than a failure (#145). Matching on
 * {@link #getMessage()} instead would also match a code quoted inside some other failure's text --
 * a task's error info, say -- and swallow a real one.
 */
public class HypervisorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The backend's error code (XAPI puts it in the JSON-RPC {@code error.message}), or null if there was none. */
    private final String errorCode;

    /**
     * The backend's error parameters (XAPI's {@code error.data}), empty when there were none. Declared as
     * {@link ArrayList} rather than {@link List} because this class is serializable and the interface is
     * not: a non-transient field of a type SpotBugs cannot prove serializable is an SE_BAD_FIELD finding,
     * and CI fails on those.
     */
    private final ArrayList<String> errorParams;

    public HypervisorException(String message) {
        this(message, null, null, List.of());
    }

    public HypervisorException(String message, Throwable cause) {
        this(message, cause, null, List.of());
    }

    public HypervisorException(String message, String errorCode, List<String> errorParams) {
        this(message, null, errorCode, errorParams);
    }

    private HypervisorException(String message, Throwable cause, String errorCode, List<String> errorParams) {
        super(message, cause);
        this.errorCode = errorCode;
        this.errorParams = new ArrayList<>(errorParams);
    }

    /** The backend's error code, or null when this failure did not come from one (transport, parse, our own guards). */
    public String getErrorCode() {
        return errorCode;
    }

    /** The backend's error parameters, never null. */
    public List<String> getErrorParams() {
        return Collections.unmodifiableList(errorParams);
    }
}

package io.jenkins.plugins.xcpng.client;

/**
 * Thrown by {@link HypervisorClient#state} when the backend says it has no such VM.
 *
 * <p>An answer about the VM rather than a failed read, and a caller deciding whether a VM still needs
 * destroying has to tell the two apart: a VM that is gone needs nothing more, and one whose state could not
 * be read may be running. Only the backend's own word that the object does not exist produces this; a route
 * it does not serve, or a proxy's 404, is an ordinary {@link HypervisorException}.
 */
public class VmGoneException extends HypervisorException {

    private static final long serialVersionUID = 1L;

    public VmGoneException(String message) {
        super(message);
    }
}

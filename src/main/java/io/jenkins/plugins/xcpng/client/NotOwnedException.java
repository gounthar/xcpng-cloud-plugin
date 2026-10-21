package io.jenkins.plugins.xcpng.client;

/**
 * Thrown by {@link HypervisorClient#destroyWithDisks} when the VM does not carry this cloud's owner markers, so
 * the backend refuses to destroy it.
 *
 * <p>A verdict about the VM rather than a failed call: retrying gives the same answer until someone marks or
 * removes the VM by hand. The leaked-VM sweep keeps such an entry but stops counting it against the cloud's
 * {@code maxInstances} (#312), since a VM that no retry can reclaim would otherwise hold a slot for good.
 */
public class NotOwnedException extends HypervisorException {

    private static final long serialVersionUID = 1L;

    public NotOwnedException(String message) {
        super(message);
    }
}

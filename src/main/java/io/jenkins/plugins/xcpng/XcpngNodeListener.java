package io.jenkins.plugins.xcpng;

import hudson.Extension;
import hudson.model.Node;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.NodeListener;

/**
 * Removes an {@link XcpngAgent} that is registered again after its teardown has already run.
 *
 * <p>Two separate places add the node for one provision, and only one of them knows the agent is dead.
 * {@link XcpngCloud#launch} adds it as soon as the VM is running, because an inbound agent cannot dial in
 * to a node Jenkins does not have, and only then waits for it to come online before completing the planned
 * node's future. Core's {@code NodeProvisioner} adds the same node a second time when it next polls that
 * completed future — unconditionally, with no check that the node is still registered or still wanted
 * (verified in {@code hudson.slaves.NodeProvisioner.update} on the 2.555.3 baseline).
 *
 * <p>Normally the second add is a harmless no-op on a node that is already there. It stops being harmless
 * when the build is short enough to finish inside that window: the completion reap destroys the VM and
 * removes the node, then the provisioner's poll puts the node back. What comes back is worse than a stale
 * entry, because {@code Nodes.handleAddedNode} builds it a fresh {@code Computer}: {@code acceptingTasks}
 * constructs {@code true}, the connection log is empty, and the refusal the completion reap stamped on the
 * old computer is gone. The result is an agent advertising itself as available, holding a slot against
 * {@code maxInstances}, backed by a VM that was destroyed seconds earlier — and nothing reclaims it until
 * the idle net fires {@code idleMinutes} later against a handle whose VM is long gone.
 *
 * <p>Measured on the lab pool on 2026-08-27 at plugin build {@code 87ff3e9}: builds of 1.7s reproduced it
 * on both attempts, with the provisioner's add landing 3.1s and 4.0s after the node was removed, while a
 * 63s build did not reproduce it at all — the future completes and the node is added long before such a
 * build ends. That is issue #145, and it is why the symptom read as intermittent.
 *
 * <p>Removing the node here rather than leaving it to the retention strategy is deliberate: {@code check}
 * runs on the retention thread once a minute, so routing this through it would still leave a phantom agent
 * accepting work for up to that long. The removal is safe to do inline because
 * {@code Nodes.handleAddedNode} fires this listener after its {@code Queue.runWithLock} section has
 * returned, so nothing here re-enters a lock the add is still holding.
 *
 * <p>The {@code setAcceptingTasks(false)} below ships asserted rather than demonstrated. Reddening a test
 * without it would need {@code removeNode} to fail, and the successful path leaves neither node nor
 * computer to inspect, so there is nothing a test could observe. It is kept because the reasoning stands
 * on its own: the re-added computer constructs {@code acceptingTasks} true, and a build scheduled onto it
 * would lose its VM immediately.
 */
@Extension
public class XcpngNodeListener extends NodeListener {

    private static final Logger LOGGER = Logger.getLogger(XcpngNodeListener.class.getName());

    /** Drop a re-registered {@link XcpngAgent} whose teardown has already run. */
    @Override
    protected void onCreated(Node node) {
        if (!(node instanceof XcpngAgent agent) || !agent.isTerminated()) {
            return;
        }
        LOGGER.log(
                Level.INFO,
                () -> "Agent " + agent.getNodeName()
                        + " was registered again after its teardown had already run, which the node"
                        + " provisioner does when it polls a planned node whose agent has since been"
                        + " reclaimed; removing it rather than leaving a node whose VM is gone");
        // Refuse work before attempting the removal, not after it succeeds. The re-add gave this agent a
        // brand new Computer (Nodes.handleAddedNode calls updateNewComputer before firing this listener),
        // and acceptingTasks constructs true, so between here and the removal the queue is entitled to
        // schedule a build onto a VM that no longer exists. Doing it first also means the failure path
        // below leaves the node refusing work rather than advertising itself.
        if (node.toComputer() instanceof SlaveComputer computer) {
            computer.setAcceptingTasks(false);
        }
        try {
            Jenkins.get().removeNode(node);
        } catch (IOException e) {
            // Logged rather than thrown: this runs inside addNode's listener fan-out, and throwing would
            // only break whatever else that add still has to do. The node is left for the idle net to
            // reclaim, as before this class existed, but non-accepting thanks to the call above.
            //
            // No retry here, deliberately. This runs on the provisioner's thread inside a listener
            // callback, and a bounded retry loop in that position would block the add for as long as it
            // ran. The idle net is the retry, and it is the same one that covers every other teardown
            // that could not finish.
            LOGGER.log(
                    Level.WARNING,
                    e,
                    () -> "Could not remove re-registered agent " + agent.getNodeName()
                            + "; it stays registered but refuses work, and the idle timeout reclaims it");
        }
    }
}

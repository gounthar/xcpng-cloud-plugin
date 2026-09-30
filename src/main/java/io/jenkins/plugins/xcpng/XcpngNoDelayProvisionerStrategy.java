package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;

/**
 * Asks {@link XcpngCloud} for an agent as soon as a matching build is waiting and nothing can take it,
 * instead of after core's load averages have caught up (#265).
 *
 * <p>Core's {@code StandardStrategyImpl} reads the queue length and the free executors through
 * exponential moving averages on a 10 second clock, and only provisions once the averaged excess
 * crosses a margin. With no executor at all for a label it skips the averages, so a first build is
 * served at once; with even one busy agent it does not, and a second build waits while the queue
 * average climbs. One report measured that wait at 1 minute 42 seconds. Core holds off in the hope that
 * an existing executor frees up first, and that is a poor bet here: every agent is single-use, so a busy
 * one is never going to take the waiting build.
 *
 * <p>Modelled on the kubernetes plugin's {@code NoDelayProvisionerStrategy}, with one deliberate
 * difference in what counts as capacity. That strategy subtracts all of core's connecting executors from
 * the demand before calling {@code provision}. {@link XcpngCloud#provision} already subtracts the agents
 * it is building for the template, and "being built" there is the same {@code isConnecting()} that core's
 * connecting count reads. Subtracting both would count every booting agent twice, warm spares included,
 * and a second build arriving while the first agent boots would get nothing. Subtracting neither would
 * miss every booting agent the cloud does not own: another cloud's, another template's, a static agent
 * reconnecting. So each cloud is offered the demand minus the connecting executors that are not its own
 * ({@link XcpngCloud#connectingExecutorsFor}), and its own subtraction, which also sees reservations core
 * cannot, covers the rest. See {@link #workloadToProvision}.
 *
 * <p>Only {@link XcpngCloud} instances are asked, and a label none of them serves is passed straight to
 * the next strategy untouched. Set the system property {@link #DISABLE_PROPERTY} to {@code true} to fall
 * back to core's behaviour; it is read on every round, so it can be flipped from the script console.
 */
@Extension(ordinal = 100)
public class XcpngNoDelayProvisionerStrategy extends NodeProvisioner.Strategy {

    private static final Logger LOGGER = Logger.getLogger(XcpngNoDelayProvisionerStrategy.class.getName());

    /** System property that turns this strategy and its queue listener off. */
    static final String DISABLE_PROPERTY = XcpngNoDelayProvisionerStrategy.class.getName() + ".disabled";

    static boolean isDisabled() {
        return SystemProperties.getBoolean(DISABLE_PROPERTY);
    }

    @NonNull
    @Override
    public NodeProvisioner.StrategyDecision apply(@NonNull NodeProvisioner.StrategyState state) {
        if (isDisabled()) {
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
        Label label = state.getLabel();
        if (!anyCloudServes(label)) {
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
        LoadStatistics.LoadStatisticsSnapshot snapshot = state.getSnapshot();
        int demand = snapshot.getQueueLength();
        int planned = provision(state, label, snapshot);
        if (planned > 0 && label != null) {
            // Our planned nodes are already settled futures, but core only registers them on its next
            // round. Ask for that round now rather than waiting out the provisioner's own period.
            Timer.get().schedule(label.nodeProvisioner::suggestReviewNow, 1L, TimeUnit.SECONDS);
        }
        // additionalPlannedCapacity now includes what was just recorded, so it is read again here.
        boolean covered = isCovered(
                demand,
                snapshot.getAvailableExecutors(),
                snapshot.getConnectingExecutors(),
                state.getPlannedCapacitySnapshot(),
                state.getAdditionalPlannedCapacity());
        LOGGER.log(Level.FINE, "Label {0}: demand {1}, planned {2}, covered {3}", new Object[] {
            label, demand, planned, covered
        });
        return covered
                ? NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED
                : NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
    }

    /**
     * The workload to offer one cloud: waiting builds minus executors that can take one now, that core
     * already knows are planned, or that are booting somewhere this cloud cannot see.
     *
     * <p>{@code ownConnecting} is the part of {@code connecting} the asked cloud will subtract itself in
     * {@link XcpngCloud#provision}, together with reservations core has not registered yet. Subtracting it
     * here as well would count those booting agents twice. The difference is clamped at zero because the
     * snapshot and the cloud's own count are read at two different instants.
     */
    static int workloadToProvision(
            int demand, int available, int connecting, int ownConnecting, int plannedSnapshot, int additionalPlanned) {
        int elsewhere = Math.max(0, connecting - ownConnecting);
        return demand - (available + elsewhere + plannedSnapshot + additionalPlanned);
    }

    /**
     * Whether the executors that exist, are booting or are planned cover the waiting builds, in which case
     * the remaining strategies have nothing left to do. Unlike {@link #workloadToProvision} this does count
     * connecting executors: a booting agent is capacity, it just must not be subtracted twice on the way
     * into {@code provision}.
     */
    static boolean isCovered(int demand, int available, int connecting, int plannedSnapshot, int additionalPlanned) {
        return available + connecting + plannedSnapshot + additionalPlanned >= demand;
    }

    private static boolean anyCloudServes(Label label) {
        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof XcpngCloud xcpng && xcpng.servesLabel(label)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Offer the waiting work to each XCP-ng cloud in turn until it is met, the way core's standard strategy
     * offers its own: {@code canProvision}, then every {@link CloudProvisioningListener} veto, then
     * {@code provision}, {@code onStarted} and {@code recordPendingLaunches}. The {@code onStarted} call is
     * what cloud-stats records the activity from, so it is not optional.
     *
     * @return the number of executors planned
     */
    private static int provision(
            NodeProvisioner.StrategyState state, Label label, LoadStatistics.LoadStatisticsSnapshot snapshot) {
        int planned = 0;
        clouds:
        for (Cloud cloud : Jenkins.get().clouds) {
            if (!(cloud instanceof XcpngCloud xcpng)) {
                continue;
            }
            // What this round has planned so far is in additionalPlannedCapacity already, through
            // recordPendingLaunches below, so it is not subtracted a second time here.
            int remaining = workloadToProvision(
                    snapshot.getQueueLength(),
                    snapshot.getAvailableExecutors(),
                    snapshot.getConnectingExecutors(),
                    xcpng.connectingExecutorsFor(label),
                    state.getPlannedCapacitySnapshot(),
                    state.getAdditionalPlannedCapacity());
            if (remaining <= 0) {
                continue;
            }
            Cloud.CloudState cloudState = new Cloud.CloudState(label, state.getAdditionalPlannedCapacity());
            if (!cloud.canProvision(cloudState)) {
                continue;
            }
            for (CloudProvisioningListener listener : CloudProvisioningListener.all()) {
                if (listener.canProvision(cloud, cloudState, remaining) != null) {
                    continue clouds;
                }
            }
            Collection<NodeProvisioner.PlannedNode> nodes = cloud.provision(cloudState, remaining);
            fireOnStarted(cloud, label, nodes);
            for (NodeProvisioner.PlannedNode node : nodes) {
                planned += node.numExecutors;
                LOGGER.log(
                        Level.INFO,
                        "Started provisioning {0} from {1} for a waiting build, without waiting for the load"
                                + " average",
                        new Object[] {node.displayName, cloud.name});
            }
            state.recordPendingLaunches(nodes);
        }
        return planned;
    }

    private static void fireOnStarted(Cloud cloud, Label label, Collection<NodeProvisioner.PlannedNode> nodes) {
        for (CloudProvisioningListener listener : CloudProvisioningListener.all()) {
            try {
                listener.onStarted(cloud, label, nodes);
            } catch (Error e) {
                throw e;
            } catch (Throwable e) {
                // As core does: one listener's failure must not stop the node being provisioned.
                LOGGER.log(Level.WARNING, e, () -> "Provisioning listener " + listener + " failed in onStarted");
            }
        }
    }

    /**
     * Starts a provisioning round the moment a build that an XCP-ng cloud could serve becomes buildable.
     * Without it the strategy above would still only run on the provisioner's own 10 second period.
     */
    @Extension
    public static class FastProvisioning extends QueueListener {

        @Override
        public void onEnterBuildable(Queue.BuildableItem item) {
            if (isDisabled()) {
                return;
            }
            Label label = item.getAssignedLabel();
            // A null label never matches an XCP-ng template (the agents are EXCLUSIVE), so there is no
            // unlabeled provisioner to wake here.
            if (label != null && anyCloudServes(label)) {
                label.nodeProvisioner.suggestReviewNow();
            }
        }
    }
}

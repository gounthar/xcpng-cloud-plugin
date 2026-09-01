package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;

/**
 * Builds the VM behind an {@link XcpngAgent}, at the moment Jenkins connects that agent's computer.
 *
 * <p>The clone, the xenstore seed and the start used to run inside {@link XcpngCloud#provision}, on a
 * background thread, and the node was registered there too — an inbound agent cannot dial in to a node
 * Jenkins does not have. Core's {@code NodeProvisioner} then registered the same node a second time when it
 * polled the planned node's completed future, which is the race behind #145; it was undone from a
 * {@code NodeListener} until this class removed the second registration instead.
 *
 * <p>Doing the work here inverts that ordering, following the shape kubernetes-plugin uses: {@code provision}
 * hands back an agent with no VM behind it yet, core registers it exactly once, and the VM is built while
 * core holds the computer in its connecting state. Nothing has to undo a second registration, because there
 * is no longer a second one.
 *
 * <p>Blocking here is the contract rather than a liberty taken with it. {@code ComputerLauncher.launch}'s
 * javadoc says outright that the method must operate synchronously and that {@code Computer.connect}'s
 * correct operation depends on it, and core runs it on {@code Computer.threadPoolForRemoting}, a cached pool
 * that grows for exactly this. Two things follow from holding that thread for the whole clone-and-boot
 * window, and both are load-bearing: {@code SlaveComputer.isConnecting()} stays true throughout, so the
 * node's executors are counted in {@code LoadStatistics}' {@code connectingExecutors} and the provisioner
 * does not treat the agent as delivered capacity while it boots; and a failure has somewhere to surface,
 * since core marks the launch failed and this class tears the VM down on the way out.
 */
public class XcpngLauncher extends JNLPLauncher {

    private static final Logger LOGGER = Logger.getLogger(XcpngLauncher.class.getName());

    /**
     * The template this agent is cloned from: which golden image, how big the clone is, and the optional SSH
     * key the seed carries. Held here rather than on the agent because this is the only thing that needs it,
     * and it is persisted with the node for the same reason the VM reference is — a controller restart
     * between registration and a successful clone must not leave a node whose launcher has nothing to clone.
     */
    private final XcpngTemplate template;

    XcpngLauncher(@NonNull XcpngTemplate template) {
        this.template = template;
    }

    /** The template this launcher clones. */
    @NonNull
    public XcpngTemplate getTemplate() {
        return template;
    }

    /**
     * True, unlike {@link JNLPLauncher#isLaunchSupported()}, because this launcher does have something to do
     * when asked to launch.
     *
     * <p>It is deliberately <em>not</em> what gets {@link #launch} called on the cloud path:
     * {@code SlaveComputer._connect} never consults it, and reads the launcher straight off the computer. It
     * matters for {@code RetentionStrategy.Always} and {@code Demand}, which both guard on it, and for
     * whether the UI offers a launch button. kubernetes-plugin's launcher overrides it for the same reasons.
     */
    @Override
    public boolean isLaunchSupported() {
        return true;
    }

    /**
     * Clone the template, start the VM, and block until the inbound agent dials back in.
     *
     * <p>Throws, rather than returning quietly, on every failure. Core turns that into a failed launch with
     * the reason on the computer's connection log, which is where an administrator looks; returning quietly
     * would leave core to notice the missing channel and report an error naming nothing.
     */
    @Override
    public void launch(SlaveComputer computer, TaskListener listener) {
        Node node = computer.getNode();
        if (!(node instanceof XcpngAgent agent)) {
            // The node was removed between the connect being scheduled and this running, or something other
            // than an XcpngAgent was given this launcher. Either way there is nothing to build a VM for.
            throw new IllegalStateException(
                    "Cannot launch " + computer.getName() + ": it is no longer an XCP-ng agent.");
        }
        XcpngCloud cloud = agent.getCloud();
        if (cloud == null) {
            throw new IllegalStateException("XCP-ng cloud '" + agent.getCloudName()
                    + "' is gone; cannot provision a VM for " + agent.getNodeName() + ".");
        }
        String displayName = agent.getNodeName();
        // This node is registered -- a computer exists for it -- so the slot the cloud reserved for it while
        // it was only planned is now held by the node itself.
        cloud.noteRegistered(displayName);
        try {
            String existing = agent.getVmRef();
            if (existing == null) {
                cloud.provisionVm(agent, template, listener);
            } else {
                // A reconnect, not a first launch: the VM is already there. Cloning again would leak the
                // first one and hand the controller a second agent presenting the same name.
                listener.getLogger()
                        .println("XCP-ng VM " + existing
                                + " is already running for this agent; waiting for it to connect.");
            }
            cloud.awaitOnline(computer, displayName);
            cloud.noteLaunchSucceeded(template.getTemplateName());
        } catch (InterruptedException e) {
            // Shutdown, or the computer being disconnected under us. Tear the VM down on the way out for the
            // same reason as any other failure, then restore the flag so core's own shutdown still sees it.
            // Restored after the teardown, not before: the teardown ends in a blocking HTTP call, which
            // throws immediately on a thread whose interrupt flag is already set, and that would leak the
            // very VM the teardown exists to remove.
            terminateQuietly(agent, listener, e, alreadyRemoved(agent));
            Thread.currentThread().interrupt();
            throw asLaunchFailure(displayName, e);
        } catch (Exception e) {
            // Decided before the teardown, because the teardown is what removes the node this asks about.
            boolean cancelled = alreadyRemoved(agent);
            // Teardown first, bookkeeping second. The teardown is the half that must not be skipped: it is
            // the only thing that destroys a VM, and anything that threw while recording a backoff -- an
            // extension lookup during shutdown, say -- would otherwise leak the running clone.
            terminateQuietly(agent, listener, e, cancelled);
            if (!cancelled) {
                // Held against the template, so a golden image the pool does not have stops being retried on
                // every provisioning round (#157).
                //
                // Not held when the node was removed under the launcher, which is a cancellation rather than
                // a verdict on the template: a controller restart reloads a mid-boot agent, the retention
                // strategy reclaims it, and awaitOnline throws because computer.getNode() has gone. Counting
                // those would withhold a perfectly healthy template after a few restarts. Same condition
                // recordFailure already uses, for the same reason.
                //
                // Nor for the interrupt above: that says the controller changed its mind -- a shutdown, or
                // the computer disconnected under us -- and says nothing about whether this template clones.
                cloud.noteLaunchFailed(template.getTemplateName());
            }
            throw asLaunchFailure(displayName, e);
        }
    }

    /**
     * Tear down an agent whose launch failed, VM and node together.
     *
     * <p>{@link XcpngAgent#_terminate} is the single teardown path, so routing the failure through
     * {@code terminate()} is what keeps the leaked-VM recording working here: a destroy that throws hands the
     * reference to the cloud's durable orphan set exactly as it does on the idle and completion paths. It also
     * removes the node, which matters because a node left behind after a failed launch holds a slot against
     * {@code maxInstances} until the idle net reclaims it.
     *
     * <p>Failures of the teardown itself are logged and swallowed: the caller is already on its way out with
     * the original failure, which is the one worth reporting.
     */
    private static void terminateQuietly(
            @NonNull XcpngAgent agent,
            @NonNull TaskListener listener,
            @NonNull Throwable cause,
            boolean alreadyRemoved) {
        if (alreadyRemoved) {
            // The node is already gone, so its teardown has already run: this is the reloaded-agent case,
            // where a controller restart reconnects an agent that XcpngRetentionStrategy then reclaims
            // underneath the launcher. Terminating again would issue a second destroy for a VM that is
            // already destroyed, and recording a failure would fire against a closed activity. Neither is
            // an error worth a WARNING, so this says what happened at FINE and stops.
            LOGGER.log(
                    Level.FINE,
                    cause,
                    () -> "Launch of agent " + agent.getNodeName()
                            + " ended after its node had already been removed; nothing left to tear down");
            return;
        }
        LOGGER.log(
                Level.WARNING,
                cause,
                () -> "Launch of agent " + agent.getNodeName() + " failed; tearing down whatever it had built");
        recordFailure(agent, cause);
        try {
            agent.terminate();
        } catch (InterruptedException e) {
            // Restored by the caller, which is itself unwinding an interrupt in that case.
            Thread.currentThread().interrupt();
            listener.getLogger().println("Interrupted while tearing down the failed launch of " + agent.getNodeName());
        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING,
                    e,
                    () -> "Could not tear down agent " + agent.getNodeName() + " after a failed launch");
            listener.getLogger()
                    .println(
                            "Could not tear down " + agent.getNodeName() + " after a failed launch: " + e.getMessage());
        }
    }

    /**
     * Whether this agent's node has already been removed, so a failure reaching the launcher is a
     * cancellation rather than a verdict on the pool or the template.
     *
     * <p>Read once per failure and passed down, rather than evaluated at each place that needs it: the
     * teardown itself removes the node, so a second read after it would answer a different question and the
     * two decisions would disagree.
     */
    private static boolean alreadyRemoved(@NonNull XcpngAgent agent) {
        return Jenkins.get().getNode(agent.getNodeName()) != agent;
    }

    /**
     * Mark this agent's cloud-stats activity failed, in the phase it actually failed in.
     *
     * <p>Needed because nothing else does it. On the on-demand path the failure used to reach cloud-stats
     * through the planned node's exceptional future; the future now settles before the VM exists, so that
     * route is gone. cloud-stats' own {@code ComputerListener.onLaunchFailure} is an empty TODO in the
     * version this plugin builds against, and the node deletion that follows a failed launch only enters
     * {@code COMPLETED}. Without this, a provision that never produced a working agent would be recorded as a
     * short successful one.
     *
     * <p>Every failure is swallowed: this is monitoring, and it sits on the path that tears a VM down.
     */
    private static void recordFailure(@NonNull XcpngAgent agent, @NonNull Throwable cause) {
        try {
            CloudStatistics stats = CloudStatistics.get();
            ProvisioningActivity activity = stats.getActivityFor(agent);
            if (activity != null) {
                stats.attach(
                        activity,
                        ProvisioningActivity.Phase.LAUNCHING,
                        new PhaseExecutionAttachment.ExceptionAttachment(ProvisioningActivity.Status.FAIL, cause));
            }
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    e,
                    () -> "Could not record the failed launch of " + agent.getNodeName() + " in cloud-stats");
        }
    }

    /**
     * Wrap a provisioning failure as something {@link #launch} may throw.
     *
     * <p>{@link JNLPLauncher#launch} narrows {@code ComputerLauncher}'s {@code throws IOException,
     * InterruptedException} to nothing at all, so an override of it can only throw unchecked. Core catches
     * {@code RuntimeException} in {@code SlaveComputer._connect}, prints it to the computer's connection log
     * and marks the launch failed, which is exactly the treatment a checked one would have got. An unchecked
     * failure is rethrown as it is, so the trace reaching that log is the original one.
     */
    @NonNull
    private static RuntimeException asLaunchFailure(@NonNull String displayName, @NonNull Exception failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("Could not provision a VM for agent " + displayName, failure);
    }

    /**
     * The descriptor, handed out directly rather than looked up, so that it exists without being offered.
     *
     * <p>{@code AbstractDescribableImpl.getDescriptor} resolves a descriptor through
     * {@code Jenkins.getDescriptorOrDie}, which throws an {@code AssertionError} when there is none registered.
     * Every view that renders a node hits that, including the ordinary agent configuration page, so an agent
     * this plugin provisioned needs a descriptor to exist. Registering one as an {@code @Extension} is the
     * usual way to arrange that, and it also puts an entry in the New Node launch-method dropdown, where
     * picking it can only fail: this class has no {@code @DataBoundConstructor} and no {@code config.jelly}, so
     * there is nothing for the form to bind, and a launcher built that way would have no template and no cloud
     * behind it.
     *
     * <p>Overriding {@link #getDescriptor()} answers the first need without the second. Nothing enumerates a
     * descriptor that was never registered, so the dropdown no longer offers it (#158), while the agent
     * configuration page resolves one here and renders. kubernetes-plugin does the same thing for the same
     * reason, and says so in a comment on its own descriptor: <em>"Only there to avoid throwing unnecessary
     * exceptions. KubernetesLauncher is never instantiated via UI."</em>
     */
    @NonNull
    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        return DESCRIPTOR;
    }

    /**
     * Built once, at class initialisation, rather than per call.
     *
     * <p>Safe there because {@code Descriptor}'s no-argument constructor touches no Jenkins singleton: it reads
     * the enclosing class to infer what it describes and checks the type parameter against it, and nothing
     * else. Held as one instance because a descriptor is conventionally a singleton, and handing out a fresh
     * one on each call would hand two callers objects that compare unequal.
     */
    private static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    /**
     * Deliberately not an {@code @Extension}, and deliberately still nested: {@code Descriptor}'s no-argument
     * constructor infers what it describes from {@code getClass().getEnclosingClass()} and fails an assertion
     * when that is null, so moving this to the top level would need the {@code Descriptor(Class)} constructor
     * instead.
     */
    private static class DescriptorImpl extends Descriptor<ComputerLauncher> {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.XcpngLauncher_DisplayName();
        }
    }
}

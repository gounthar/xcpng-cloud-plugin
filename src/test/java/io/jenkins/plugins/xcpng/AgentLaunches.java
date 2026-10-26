package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.fail;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.SlaveComputer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.model.Jenkins;

/**
 * Waits out core's launcher threads before {@code JenkinsRule} deletes the temporary home (#328).
 *
 * <p>Adding an XCP-ng node makes core connect its computer on {@code Computer.threadPoolForRemoting}, a pool no
 * test owns. {@link XcpngLauncher} clones the VM there and {@link XcpngAgent#adoptVmRef} saves the node and the
 * issued-agent store. A test that returns while that launch is still running lets those saves land after
 * {@code JenkinsRule.after()} has started deleting the home, which fails the test in teardown with {@code
 * DirectoryNotEmptyException} and {@code These files still exist : nodes}. Call {@link #awaitAll} from an
 * {@code @AfterEach}: JUnit runs those before the {@code @WithJenkins} extension tears Jenkins down.
 *
 * <p>The live computer list is not enough on its own. A failed launch removes its node from the launch thread
 * and then keeps going: core writes the failure to the computer's log under the home after the computer has
 * left {@link Jenkins#getComputers()}. So every launch is also recorded as it starts, by {@link Recorder}, and
 * waited on through its own computer.
 */
final class AgentLaunches {

    private static final Set<SlaveComputer> STARTED = ConcurrentHashMap.newKeySet();

    private AgentLaunches() {}

    /** Wait until no computer is connecting, and fail if one is still connecting after 30 seconds. */
    static void awaitAll() throws InterruptedException {
        try {
            awaitLaunches();
        } finally {
            // Cleared even on failure, so the next test does not wait on this one's computers.
            STARTED.clear();
        }
    }

    private static void awaitLaunches() throws InterruptedException {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 30_000;
        // Rescanned until a whole pass finds nothing connecting: a launch can start while an earlier one is
        // being waited on.
        boolean waited;
        do {
            waited = false;
            Set<SlaveComputer> launching = new HashSet<>(STARTED);
            for (Computer computer : jenkins.getComputers()) {
                if (computer instanceof SlaveComputer slave) {
                    launching.add(slave);
                }
            }
            for (SlaveComputer slave : launching) {
                while (slave.isConnecting()) {
                    waited = true;
                    if (System.currentTimeMillis() > deadline) {
                        fail("core was still launching " + slave.getName() + " when the test ended");
                    }
                    Thread.sleep(10);
                }
            }
        } while (waited);
    }

    /** Records each launch on its own thread, before the launcher runs, so a removed computer is still waited on. */
    @Extension
    public static final class Recorder extends ComputerListener {
        @Override
        public void preLaunch(Computer computer, TaskListener listener) {
            if (computer instanceof SlaveComputer slave) {
                STARTED.add(slave);
            }
        }
    }
}

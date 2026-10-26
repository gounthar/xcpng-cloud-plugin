package io.jenkins.plugins.xcpng;

import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Computer;
import hudson.slaves.SlaveComputer;
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
 */
final class AgentLaunches {

    private AgentLaunches() {}

    /** Wait until no computer is connecting, and fail if one is still connecting after 30 seconds. */
    static void awaitAll() throws InterruptedException {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + 30_000;
        for (Computer computer : jenkins.getComputers()) {
            while (computer instanceof SlaveComputer slave && slave.isConnecting()) {
                if (System.currentTimeMillis() > deadline) {
                    fail("core was still launching " + computer.getName() + " when the test ended");
                }
                Thread.sleep(10);
            }
        }
    }
}

package io.jenkins.plugins.xcpng;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Node;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * What this plugin itself gave each of its agents to connect with, held outside the agents.
 *
 * <p>Every session an agent opens to the pool, and every VM it destroys, is decided by fields persisted in
 * that agent's node definition. This store keeps the plugin's own copy of those fields, so that the
 * definition is never the only source of them. {@link XcpngAgent}'s constructor, which only plugin code
 * calls, issues the snapshot it took from its cloud; {@link XcpngAgent#setVmRef} adds the VM handle. Before
 * an agent opens a session it checks that its fields are still exactly what was issued under its name, and
 * an agent that does not match is given no session at all. The fields are compared rather than replaced by
 * the issued copy so that every existing reader of them stays correct without being rewritten.
 *
 * <p>A plain {@link Saveable} in its own file under {@code JENKINS_HOME}, for the reasons given on
 * {@link XcpngLeakedVmStore}: runtime state, not configuration, and kept out of configuration-as-code.
 *
 * <p>Agents that existed before this store did are seeded from their own definitions once, at the first
 * start that has the store, by {@link #seedAtStartup}. That runs before any request is served, so what is on
 * disk then is trusted exactly as it was before the upgrade. Until the seed has run, {@link #matches} answers
 * true for everything, which is the earlier behaviour and lasts only for that part of the first startup.
 */
@Extension
public class XcpngIssuedAgentStore implements Saveable {

    private static final Logger LOGGER = Logger.getLogger(XcpngIssuedAgentStore.class.getName());

    /** Guards the file write; see {@link XcpngLeakedVmStore} for why the whole write is serialized. */
    private static final Object SAVE_LOCK = new Object();

    /**
     * Node name to what was issued under it. Not final: {@link XmlFile#unmarshal} writes whatever map XStream
     * built straight into the field, and {@link #load} swaps it for a concurrent one.
     */
    private Map<String, Issued> issuedByNode = new ConcurrentHashMap<>();

    /**
     * Whether the agents on disk at the first start with this store have been seeded into it. Volatile because
     * {@link #matches} reads it from request threads without the lock the seed holds, and a stale {@code false}
     * there would answer true for every agent.
     */
    private volatile boolean seeded;

    public XcpngIssuedAgentStore() {
        load();
    }

    static XcpngIssuedAgentStore get() {
        return ExtensionList.lookupSingleton(XcpngIssuedAgentStore.class);
    }

    /**
     * Seed the agents already on disk, once, then drop any entry whose node is gone.
     *
     * <p>{@code JOB_LOADED} is after nodes are loaded and before the controller serves requests. The prune
     * runs on every start: an entry whose node did not survive a restart has nothing left to describe.
     */
    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void seedAtStartup() {
        get().seedAndPrune(Jenkins.get().getNodes());
    }

    synchronized void seedAndPrune(@NonNull Iterable<Node> nodes) {
        Set<String> present = new HashSet<>();
        for (Node node : nodes) {
            if (node instanceof XcpngAgent agent) {
                present.add(agent.getNodeName());
                if (!seeded) {
                    issuedByNode.putIfAbsent(agent.getNodeName(), Issued.of(agent));
                }
            }
        }
        if (!seeded) {
            LOGGER.log(Level.INFO, () -> "Recorded " + present.size() + " existing XCP-ng agent(s) as issued");
        }
        boolean pruned = issuedByNode.keySet().retainAll(present);
        if (!seeded || pruned) {
            seeded = true;
            saveQuietly();
        }
    }

    /** Record the snapshot an agent was built with. Called from its constructor and nowhere else. */
    void issue(@NonNull XcpngAgent agent) {
        issuedByNode.put(agent.getNodeName(), Issued.of(agent));
        saveQuietly();
    }

    /** Record the VM handle the plugin stamped onto an agent it issued. A name it never issued is ignored. */
    void issueVmRef(@NonNull String nodeName, @CheckForNull String vmRef) {
        Issued updated = issuedByNode.computeIfPresent(nodeName, (k, issued) -> issued.withVmRef(vmRef));
        if (updated != null) {
            saveQuietly();
        }
    }

    /**
     * Whether this agent's fields are exactly what the plugin issued under its name. False for a node the
     * plugin never issued and for one whose file was rewritten since.
     */
    boolean matches(@NonNull XcpngAgent agent) {
        if (!seeded) {
            return true;
        }
        Issued issued = issuedByNode.get(agent.getNodeName());
        return issued != null && issued.equals(Issued.of(agent));
    }

    /** Forget a node once its teardown is over. */
    void forget(@NonNull String nodeName) {
        if (issuedByNode.remove(nodeName) != null) {
            saveQuietly();
        }
    }

    private void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not write the XCP-ng issued-agent store");
        }
    }

    @Override
    public void save() throws IOException {
        XmlFile file = configFile();
        synchronized (SAVE_LOCK) {
            file.write(this);
        }
        SaveableListener.fireOnChange(this, file);
    }

    private void load() {
        XmlFile file = configFile();
        if (file.exists()) {
            try {
                file.unmarshal(this);
            } catch (IOException e) {
                // Starting empty and unseeded falls back to trusting the node files until the next startup seed,
                // which is the behaviour before this store existed. Failing extension load would cost the plugin.
                LOGGER.log(Level.WARNING, e, () -> "Could not read the XCP-ng issued-agent store; starting empty");
                seeded = false;
            }
        }
        Map<String, Issued> concurrent = new ConcurrentHashMap<>();
        if (issuedByNode != null) {
            issuedByNode.forEach((name, issued) -> {
                if (name != null && issued != null) {
                    concurrent.put(name, issued);
                }
            });
        }
        issuedByNode = concurrent;
    }

    private static XmlFile configFile() {
        return new XmlFile(
                Jenkins.XSTREAM, new File(Jenkins.get().getRootDir(), XcpngIssuedAgentStore.class.getName() + ".xml"));
    }

    /** One agent's issued snapshot: every field its sessions and its teardown read. */
    static final class Issued {
        private final String cloudName;
        private final String vmRef;
        private final String poolUrl;
        private final String credentialsId;
        private final String certificateFingerprint;
        private final String caCertificates;
        private final XcpngBackend backend;
        private final String poolId;

        private Issued(
                String cloudName,
                String vmRef,
                String poolUrl,
                String credentialsId,
                String certificateFingerprint,
                String caCertificates,
                XcpngBackend backend,
                String poolId) {
            this.cloudName = cloudName;
            this.vmRef = vmRef;
            this.poolUrl = poolUrl;
            this.credentialsId = credentialsId;
            this.certificateFingerprint = certificateFingerprint;
            this.caCertificates = caCertificates;
            this.backend = backend;
            this.poolId = poolId;
        }

        static Issued of(@NonNull XcpngAgent agent) {
            return new Issued(
                    agent.getCloudName(),
                    agent.getVmRef(),
                    agent.getPoolUrl(),
                    agent.getCredentialsId(),
                    agent.getCertificateFingerprint(),
                    agent.getCaCertificates(),
                    agent.getBackend(),
                    agent.getPoolId());
        }

        Issued withVmRef(@CheckForNull String ref) {
            return new Issued(
                    cloudName, ref, poolUrl, credentialsId, certificateFingerprint, caCertificates, backend, poolId);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Issued that
                    && Objects.equals(cloudName, that.cloudName)
                    && Objects.equals(vmRef, that.vmRef)
                    && Objects.equals(poolUrl, that.poolUrl)
                    && Objects.equals(credentialsId, that.credentialsId)
                    && Objects.equals(certificateFingerprint, that.certificateFingerprint)
                    && Objects.equals(caCertificates, that.caCertificates)
                    && backend == that.backend
                    && Objects.equals(poolId, that.poolId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    cloudName, vmRef, poolUrl, credentialsId, certificateFingerprint, caCertificates, backend, poolId);
        }
    }
}

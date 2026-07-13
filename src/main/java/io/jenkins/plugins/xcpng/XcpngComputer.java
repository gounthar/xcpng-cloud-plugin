package io.jenkins.plugins.xcpng;

import hudson.slaves.AbstractCloudComputer;

/**
 * The {@link hudson.model.Computer} for an {@link XcpngAgent}. Nothing beyond the cloud-computer base
 * is needed yet; it exists so the agent has a typed computer and a place for future per-agent state.
 */
public class XcpngComputer extends AbstractCloudComputer<XcpngAgent> {

    public XcpngComputer(XcpngAgent agent) {
        super(agent);
    }
}

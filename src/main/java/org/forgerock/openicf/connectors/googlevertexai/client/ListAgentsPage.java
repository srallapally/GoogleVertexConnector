package org.forgerock.openicf.connectors.googlevertexai.client;

import java.util.List;

/**
 * One page of results from the Dialogflow CX agents.list API.
 */
public class ListAgentsPage {

    private final List<GoogleVertexAgentDescriptor> agents;
    private final String nextPageToken;

    public ListAgentsPage(List<GoogleVertexAgentDescriptor> agents, String nextPageToken) {
        this.agents = agents;
        this.nextPageToken = nextPageToken;
    }

    public List<GoogleVertexAgentDescriptor> getAgents() {
        return agents;
    }

    /** Null when there are no more pages. */
    public String getNextPageToken() {
        return nextPageToken;
    }
}
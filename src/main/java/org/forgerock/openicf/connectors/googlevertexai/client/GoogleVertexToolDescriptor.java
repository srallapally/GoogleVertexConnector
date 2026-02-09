package org.forgerock.openicf.connectors.googlevertexai.client;

/**
 * Describes a Dialogflow CX tool or webhook.
 *
 * Tools come from the agents.tools.list API. Webhooks are merged in with
 * toolType = "WEBHOOK".
 */
public class GoogleVertexToolDescriptor {

    private final String name;          // full resource name
    private final String displayName;
    private final String toolType;      // CUSTOMIZED_TOOL, DATA_STORE_TOOL, WEBHOOK, etc.
    private final String description;
    private final String endpoint;      // webhook URI if applicable
    private final String agentResourceName; // owning agent

    public GoogleVertexToolDescriptor(String name,
                                String displayName,
                                String toolType,
                                String description,
                                String endpoint,
                                String agentResourceName) {
        this.name = name;
        this.displayName = displayName;
        this.toolType = toolType;
        this.description = description;
        this.endpoint = endpoint;
        this.agentResourceName = agentResourceName;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getToolType() {
        return toolType;
    }

    public String getDescription() {
        return description;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getAgentResourceName() {
        return agentResourceName;
    }
}
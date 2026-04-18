// src/main/java/org/forgerock/openicf/connectors/googlevertexai/client/GoogleVertexToolDescriptor.java
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
    // OPENICF-4011: tool authentication metadata
    private final String authType;      // SERVICE_ACCOUNT, OAUTH, API_KEY, NONE
    private final String credentialRef; // SA email or secret resource name; never secret value
    // OPENICF-4010: search-safe tool identifier ('/' replaced by '_')
    private final String toolKey;

    public GoogleVertexToolDescriptor(String name,
                                      String displayName,
                                      String toolType,
                                      String description,
                                      String endpoint,
                                      String agentResourceName,
                                      String authType,
                                      String credentialRef,
                                      String toolKey) {
        this.name = name;
        this.displayName = displayName;
        this.toolType = toolType;
        this.description = description;
        this.endpoint = endpoint;
        this.agentResourceName = agentResourceName;
        this.authType = authType;
        this.credentialRef = credentialRef;
        this.toolKey = toolKey;
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

    // OPENICF-4011
    public String getAuthType() {
        return authType;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    // OPENICF-4010
    public String getToolKey() {
        return toolKey;
    }
}
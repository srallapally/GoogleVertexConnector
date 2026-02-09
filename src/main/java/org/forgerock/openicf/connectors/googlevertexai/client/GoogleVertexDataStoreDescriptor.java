package org.forgerock.openicf.connectors.googlevertexai.client;

/**
 * Describes a Dialogflow CX data store connection (used as a knowledge base).
 */
public class GoogleVertexDataStoreDescriptor {

    private final String name;          // full resource name
    private final String displayName;
    private final String dataStoreType; // PUBLIC_WEB, UNSTRUCTURED, STRUCTURED
    private final String status;
    private final String agentResourceName; // owning agent

    public GoogleVertexDataStoreDescriptor(String name,
                                     String displayName,
                                     String dataStoreType,
                                     String status,
                                     String agentResourceName) {
        this.name = name;
        this.displayName = displayName;
        this.dataStoreType = dataStoreType;
        this.status = status;
        this.agentResourceName = agentResourceName;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDataStoreType() {
        return dataStoreType;
    }

    public String getStatus() {
        return status;
    }

    public String getAgentResourceName() {
        return agentResourceName;
    }
}
package org.forgerock.openicf.connectors.googlevertexai.client;

import java.util.Collections;
import java.util.List;

/**
 * Describes an agent as returned by either:
 * <ul>
 *   <li>Dialogflow CX agents.list / agents.get API</li>
 *   <li>Vertex AI Agent Engine reasoningEngines.list / reasoningEngines.get API</li>
 * </ul>
 *
 * The {@code name} field is the full resource name:
 * <ul>
 *   <li>Dialogflow CX: projects/{project}/locations/{location}/agents/{agentId}</li>
 *   <li>Vertex AI: projects/{project}/locations/{location}/reasoningEngines/{id}</li>
 * </ul>
 */
public class GoogleVertexAgentDescriptor {

    private final String name;
    private final String displayName;
    private final String description;
    private final String defaultLanguageCode;
    private final String timeZone;
    private final String startFlow;
    private final String startPlaybook;
    private final String createTime;   // RFC-3339 string from API
    private final String updateTime;
    private final String generativeModel;   // extracted from generativeSettings
    private final String safetySettingsJson; // serialized safety settings
    private final List<String> toolIds;
    private final List<String> dataStoreIds;

    // Vertex AI Agent Engine (reasoningEngine) specific
    private final String agentFramework;   // e.g. "google-adk", "langchain", "langgraph"
    private final String serviceAccount;

    public GoogleVertexAgentDescriptor(String name,
                                       String displayName,
                                       String description,
                                       String defaultLanguageCode,
                                       String timeZone,
                                       String startFlow,
                                       String startPlaybook,
                                       String createTime,
                                       String updateTime,
                                       String generativeModel,
                                       String safetySettingsJson,
                                       List<String> toolIds,
                                       List<String> dataStoreIds,
                                       String agentFramework,
                                       String serviceAccount) {
        this.name = name;
        this.displayName = displayName;
        this.description = description;
        this.defaultLanguageCode = defaultLanguageCode;
        this.timeZone = timeZone;
        this.startFlow = startFlow;
        this.startPlaybook = startPlaybook;
        this.createTime = createTime;
        this.updateTime = updateTime;
        this.generativeModel = generativeModel;
        this.safetySettingsJson = safetySettingsJson;
        this.toolIds = toolIds != null ? toolIds : Collections.emptyList();
        this.dataStoreIds = dataStoreIds != null ? dataStoreIds : Collections.emptyList();
        this.agentFramework = agentFramework;
        this.serviceAccount = serviceAccount;
    }

    public String getName() {
        return name;
    }

    /** The full resource name, same as {@link #getName()}. Used as __UID__. */
    public String getResourceName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getDefaultLanguageCode() {
        return defaultLanguageCode;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public String getStartFlow() {
        return startFlow;
    }

    public String getStartPlaybook() {
        return startPlaybook;
    }

    public String getCreateTime() {
        return createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public String getGenerativeModel() {
        return generativeModel;
    }

    public String getSafetySettingsJson() {
        return safetySettingsJson;
    }

    public List<String> getToolIds() {
        return toolIds;
    }

    public List<String> getDataStoreIds() {
        return dataStoreIds;
    }

    public String getAgentFramework() {
        return agentFramework;
    }

    public String getServiceAccount() {
        return serviceAccount;
    }

    /**
     * Extract the short agent ID from the full resource name.
     * e.g. "projects/p1/locations/us-central1/agents/abc-123" → "abc-123"
     */
    public String getShortAgentId() {
        if (name == null) {
            return null;
        }
        int idx = name.lastIndexOf('/');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }
}
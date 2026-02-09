package org.forgerock.openicf.connectors.googlevertexai;

import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.spi.AbstractConfiguration;
import org.identityconnectors.framework.spi.ConfigurationProperty;

import static org.forgerock.openicf.connectors.googlevertexai.utils.GoogleVertexAIConstants.FLAVOR_DIALOGFLOW_CX;
import static org.forgerock.openicf.connectors.googlevertexai.utils.GoogleVertexAIConstants.FLAVOR_VERTEX_AI;

/**
 * Configuration for the Google Vertex AI OpenICF connector.
 *
 * <p>Supports two API flavors via {@code agentApiFlavor}:
 * <ul>
 *   <li>{@code dialogflowcx} — Dialogflow CX agents (default)</li>
 *   <li>{@code vertexai} — Vertex AI Agent Engine (reasoningEngines)</li>
 * </ul>
 */
public class GoogleVertexAIConfiguration extends AbstractConfiguration {

    private static final Log LOG = Log.getLog(GoogleVertexAIConfiguration.class);

    private String projectId;
    private String location;
    private String agentApiFlavor = FLAVOR_DIALOGFLOW_CX;
    private boolean useWorkloadIdentity = true;
    private GuardedString serviceAccountKeyJson;
    private boolean identityBindingScanEnabled = false;
    private String agentNameFilterRegex;

    // ---------------------------------------------------------------------
    // Getters / setters
    // ---------------------------------------------------------------------

    @ConfigurationProperty(
            order = 1,
            displayMessageKey = "projectId.display",
            helpMessageKey = "projectId.help",
            required = true
    )
    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    @ConfigurationProperty(
            order = 2,
            displayMessageKey = "location.display",
            helpMessageKey = "location.help",
            required = true
    )
    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    @ConfigurationProperty(
            order = 3,
            displayMessageKey = "agentApiFlavor.display",
            helpMessageKey = "agentApiFlavor.help",
            required = false
    )
    public String getAgentApiFlavor() {
        return agentApiFlavor;
    }

    public void setAgentApiFlavor(String agentApiFlavor) {
        this.agentApiFlavor = agentApiFlavor;
    }

    @ConfigurationProperty(
            order = 4,
            displayMessageKey = "useWorkloadIdentity.display",
            helpMessageKey = "useWorkloadIdentity.help",
            required = true
    )
    public boolean isUseWorkloadIdentity() {
        return useWorkloadIdentity;
    }

    public void setUseWorkloadIdentity(boolean useWorkloadIdentity) {
        this.useWorkloadIdentity = useWorkloadIdentity;
    }

    @ConfigurationProperty(
            order = 5,
            displayMessageKey = "serviceAccountKeyJson.display",
            helpMessageKey = "serviceAccountKeyJson.help",
            required = false,
            confidential = true
    )
    public GuardedString getServiceAccountKeyJson() {
        return serviceAccountKeyJson;
    }

    public void setServiceAccountKeyJson(GuardedString serviceAccountKeyJson) {
        this.serviceAccountKeyJson = serviceAccountKeyJson;
    }

    @ConfigurationProperty(
            order = 6,
            displayMessageKey = "identityBindingScanEnabled.display",
            helpMessageKey = "identityBindingScanEnabled.help",
            required = false
    )
    public boolean isIdentityBindingScanEnabled() {
        return identityBindingScanEnabled;
    }

    public void setIdentityBindingScanEnabled(boolean identityBindingScanEnabled) {
        this.identityBindingScanEnabled = identityBindingScanEnabled;
    }

    @ConfigurationProperty(
            order = 7,
            displayMessageKey = "agentNameFilterRegex.display",
            helpMessageKey = "agentNameFilterRegex.help",
            required = false
    )
    public String getAgentNameFilterRegex() {
        return agentNameFilterRegex;
    }

    public void setAgentNameFilterRegex(String agentNameFilterRegex) {
        this.agentNameFilterRegex = agentNameFilterRegex;
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    @Override
    public void validate() {
        LOG.ok("Validating GoogleVertexAIConfiguration...");

        if (StringUtil.isBlank(projectId)) {
            throw new IllegalArgumentException(
                    "projectId must be specified for Google Vertex AI connector.");
        }
        if (StringUtil.isBlank(location)) {
            throw new IllegalArgumentException(
                    "location must be specified for Google Vertex AI connector.");
        }

        if (!useWorkloadIdentity && serviceAccountKeyJson == null) {
            throw new IllegalArgumentException(
                    "serviceAccountKeyJson must be specified when not using workload identity.");
        }

        if (agentApiFlavor != null
                && !FLAVOR_DIALOGFLOW_CX.equals(agentApiFlavor)
                && !FLAVOR_VERTEX_AI.equals(agentApiFlavor)) {
            throw new IllegalArgumentException(
                    "agentApiFlavor must be '" + FLAVOR_DIALOGFLOW_CX
                            + "' or '" + FLAVOR_VERTEX_AI
                            + "', got: " + agentApiFlavor);
        }

        LOG.ok("GoogleVertexAIConfiguration validated. projectId={0}, location={1}, " +
                        "agentApiFlavor={2}, useWorkloadIdentity={3}, identityBindingScanEnabled={4}",
                projectId, location, agentApiFlavor, useWorkloadIdentity, identityBindingScanEnabled);
    }
}
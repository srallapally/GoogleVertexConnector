// src/main/java/org/forgerock/openicf/connectors/googlevertexai/client/GoogleVertexGuardrailDescriptor.java
package org.forgerock.openicf.connectors.googlevertexai.client;

import java.util.Collections;
import java.util.List;

/**
 * Describes guardrail/safety settings extracted from a Dialogflow CX agent.
 *
 * <p>Guardrails are not standalone resources in Google Cloud; they are embedded
 * in the agent's {@code generativeSettings.safetySettings}. This descriptor
 * represents a synthetic object parsed from that JSON structure.
 *
 * <p>UID format: {@code {agentResourceName}:guardrail}
 *
 * OPENICF-4004
 */
public class GoogleVertexGuardrailDescriptor {

    private final String id;                      // {agentResourceName}:guardrail
    private final String agentResourceName;
    private final String safetyEnforcement;       // BLOCK_NONE, BLOCK_FEW, BLOCK_SOME, BLOCK_MOST
    private final List<String> bannedPhrases;
    private final List<String> defaultBannedPhrases;
    private final String rawSettingsJson;

    public GoogleVertexGuardrailDescriptor(String agentResourceName,
                                           String safetyEnforcement,
                                           List<String> bannedPhrases,
                                           List<String> defaultBannedPhrases,
                                           String rawSettingsJson) {
        this.agentResourceName = agentResourceName;
        this.id = agentResourceName + ":guardrail";
        this.safetyEnforcement = safetyEnforcement;
        this.bannedPhrases = bannedPhrases != null ? bannedPhrases : Collections.emptyList();
        this.defaultBannedPhrases = defaultBannedPhrases != null ? defaultBannedPhrases : Collections.emptyList();
        this.rawSettingsJson = rawSettingsJson;
    }

    /**
     * Synthetic guardrail ID: {@code {agentResourceName}:guardrail}
     */
    public String getId() {
        return id;
    }

    /**
     * The parent agent's full resource name.
     */
    public String getAgentResourceName() {
        return agentResourceName;
    }

    /**
     * Safety enforcement level from generativeSafetySettings.
     * Values: BLOCK_NONE, BLOCK_FEW, BLOCK_SOME, BLOCK_MOST, or null if not set.
     */
    public String getSafetyEnforcement() {
        return safetyEnforcement;
    }

    /**
     * Banned phrases from generativeSafetySettings.bannedPhrases.
     */
    public List<String> getBannedPhrases() {
        return bannedPhrases;
    }

    /**
     * Default banned phrases from defaultBannedPhrases at the agent level.
     */
    public List<String> getDefaultBannedPhrases() {
        return defaultBannedPhrases;
    }

    /**
     * The raw safetySettings JSON for extensibility.
     */
    public String getRawSettingsJson() {
        return rawSettingsJson;
    }

    /**
     * Returns true if this guardrail has any meaningful configuration.
     */
    public boolean hasConfiguration() {
        return safetyEnforcement != null
                || !bannedPhrases.isEmpty()
                || !defaultBannedPhrases.isEmpty();
    }
}
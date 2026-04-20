// src/main/java/org/forgerock/openicf/connectors/googlevertexai/utils/GoogleVertexAIConstants.java
package org.forgerock.openicf.connectors.googlevertexai.utils;

/**
 * Shared constants for the Google Vertex AI connector.
 *
 * Supports two API flavors:
 * <ul>
 *   <li>{@code dialogflowcx} — Dialogflow CX agents API</li>
 *   <li>{@code vertexai} — Vertex AI Agent Engine (reasoningEngines) API</li>
 * </ul>
 */
public abstract class GoogleVertexAIConstants {

    // ---------------------------------------------------------------------
    // Connector identification
    // ---------------------------------------------------------------------
    public static final String CONNECTOR_NAME = "googlevertexai";
    public static final String PLATFORM_VALUE = "GOOGLE_VERTEX_AI";

    // ---------------------------------------------------------------------
    // API flavor values
    // ---------------------------------------------------------------------
    public static final String FLAVOR_DIALOGFLOW_CX = "dialogflowcx";
    public static final String FLAVOR_VERTEX_AI = "vertexai";
    // RFE-1: discover CX agents + RE agents in a single connector instance
    public static final String FLAVOR_BOTH = "both";

    // ---------------------------------------------------------------------
    // Object class names (aligned with Azure / Bedrock connectors)
    // ---------------------------------------------------------------------
    public static final String OC_AGENT = "agent";
    public static final String OC_GUARDRAIL = "agentGuardrail";
    public static final String OC_TOOL = "agentTool";
    public static final String OC_IDENTITY_BINDING = "agentIdentityBinding";
    public static final String OC_KNOWLEDGE_BASE = "agentKnowledgeBase";

    // ---------------------------------------------------------------------
    // Common attribute names
    // ---------------------------------------------------------------------
    public static final String ATTR_PLATFORM = "platform";
    public static final String ATTR_AGENT_ID = "agentId";
    public static final String ATTR_AGENT_VERSION = "agentVersion";

    // ---------------------------------------------------------------------
    // Agent attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_DESCRIPTION = "description";
    public static final String ATTR_FOUNDATION_MODEL = "foundationModel";
    public static final String ATTR_CREATED_AT = "createdAt";
    public static final String ATTR_UPDATED_AT = "updatedAt";
    public static final String ATTR_DEFAULT_LANGUAGE_CODE = "defaultLanguageCode";
    public static final String ATTR_TIME_ZONE = "timeZone";
    public static final String ATTR_SAFETY_SETTINGS = "safetySettings";
    public static final String ATTR_START_FLOW = "startFlow";

    // Vertex AI Agent Engine (reasoningEngine) specific
    public static final String ATTR_AGENT_FRAMEWORK = "agentFramework";
    public static final String ATTR_SERVICE_ACCOUNT = "serviceAccount";

    // Raw tools payload serialized as JSON
    public static final String ATTR_TOOLS_RAW = "toolsRaw";

    // OPENICF-4011: per-tool auth summary (multi-valued JSON strings on agent OC)
    public static final String ATTR_TOOL_AUTH_SUMMARY = "toolAuthSummary";

    // OPENICF-4010: search-safe tool identifier (toolId with '/' replaced by '_')
    public static final String ATTR_TOOL_KEY = "toolKey";

    // Relationship: Agent → Tools (multi-valued tool resource names)
    public static final String ATTR_AGENT_TOOL_IDS = "toolIds";

    // Relationship: Agent → Data Stores / Knowledge Bases (multi-valued)
    public static final String ATTR_AGENT_KNOWLEDGE_BASE_IDS = "knowledgeBaseIds";

    // Relationship: Agent → Guardrail (single, synthetic)
    public static final String ATTR_AGENT_GUARDRAIL_ID = "guardrailId";

    // ---------------------------------------------------------------------
    // Tool attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_TOOL_TYPE = "toolType";
    public static final String ATTR_TOOL_ENDPOINT = "endpoint";

    // ---------------------------------------------------------------------
    // Knowledge base (Data Store) attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    public static final String ATTR_KNOWLEDGE_BASE_STATE = "knowledgeBaseState";
    public static final String ATTR_DATA_STORE_TYPE = "dataStoreType";

    // ---------------------------------------------------------------------
    // Guardrail attributes (OPENICF-4004)
    // ---------------------------------------------------------------------
    public static final String ATTR_SAFETY_ENFORCEMENT = "safetyEnforcement";
    public static final String ATTR_BANNED_PHRASES = "bannedPhrases";
    public static final String ATTR_DEFAULT_BANNED_PHRASES = "defaultBannedPhrases";
    public static final String ATTR_RAW_SETTINGS_JSON = "rawSettingsJson";

    // ---------------------------------------------------------------------
    // Identity binding attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_KIND = "kind";
    public static final String ATTR_PRINCIPAL = "principal";
    public static final String ATTR_PERMISSIONS = "permissions";
    public static final String ATTR_SCOPE = "scope";
    public static final String ATTR_IAM_ROLE = "iamRole";
    public static final String ATTR_IAM_MEMBER = "iamMember";

    // OPENICF-4008: additional fields aligned with Python job output schema
    public static final String ATTR_SCOPE_RESOURCE_NAME = "scopeResourceName";
    public static final String ATTR_SOURCE_TAG = "sourceTag";
    public static final String ATTR_CONFIDENCE = "confidence";
    public static final String ATTR_FLAVOR = "flavor";
    public static final String ATTR_EXPANDED = "expanded";

    // ---------------------------------------------------------------------
    // Service account object class and attributes (OPENICF-4001)
    // ---------------------------------------------------------------------
    public static final String OC_SERVICE_ACCOUNT = "serviceAccount";

    public static final String ATTR_SA_EMAIL = "email";
    public static final String ATTR_SA_UNIQUE_ID = "uniqueId";
    public static final String ATTR_SA_DISABLED = "disabled";
    public static final String ATTR_SA_OAUTH2_CLIENT_ID = "oauth2ClientId";
    public static final String ATTR_SA_PROJECT_ID = "saProjectId";
    public static final String ATTR_SA_LINKED_AGENTS = "linkedAgentIds";
    public static final String ATTR_SA_KEYS = "keys";
    public static final String ATTR_SA_KEY_COUNT = "keyCount";

    private GoogleVertexAIConstants() {
        // prevent instantiation
    }
}
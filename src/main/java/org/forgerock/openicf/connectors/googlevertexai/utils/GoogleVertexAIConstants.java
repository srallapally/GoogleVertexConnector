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
    // Identity binding attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_KIND = "kind";
    public static final String ATTR_PRINCIPAL = "principal";
    public static final String ATTR_PERMISSIONS = "permissions";
    public static final String ATTR_SCOPE = "scope";
    public static final String ATTR_IAM_ROLE = "iamRole";
    public static final String ATTR_IAM_MEMBER = "iamMember";

    private GoogleVertexAIConstants() {
        // prevent instantiation
    }
}
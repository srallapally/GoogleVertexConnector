// src/main/java/org/forgerock/openicf/connectors/googlevertexai/operations/GoogleVertexAICrudService.java
package org.forgerock.openicf.connectors.googlevertexai.operations;

import org.forgerock.openicf.connectors.googlevertexai.GoogleVertexAIConnection;
import org.forgerock.openicf.connectors.googlevertexai.client.*;
import org.forgerock.openicf.connectors.googlevertexai.utils.GoogleVertexAIConstants;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

import static org.identityconnectors.framework.common.objects.Name.NAME;

/**
 * Read-only CRUD service for the Google Vertex AI (Dialogflow CX) connector.
 */
public class GoogleVertexAICrudService {

    private static final Log LOG = Log.getLog(GoogleVertexAICrudService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GoogleVertexAIConnection connection;
    private final GoogleVertexAIClient client;

    public GoogleVertexAICrudService(GoogleVertexAIConnection connection) {
        this.connection = connection;
        this.client = connection.getClient();
    }

    // =================================================================
    // Search operations
    // =================================================================

    public void searchAgents(ObjectClass objectClass,
                             Filter query,
                             ResultsHandler handler,
                             OperationOptions options) {
        List<GoogleVertexAgentDescriptor> agents = client.listAgents();

        String filterRegex = connection.getConfiguration().getAgentNameFilterRegex();

        for (GoogleVertexAgentDescriptor agent : agents) {
            // Apply optional name filter
            if (filterRegex != null && !filterRegex.isEmpty()
                    && agent.getDisplayName() != null
                    && !agent.getDisplayName().matches(filterRegex)) {
                continue;
            }

            ConnectorObject obj = toAgentConnectorObject(objectClass, agent);
            if (obj == null) {
                continue;
            }
            if (!handler.handle(obj)) {
                return;
            }
        }
    }

    public void searchTools(ObjectClass objectClass,
                            Filter query,
                            ResultsHandler handler,
                            OperationOptions options) {
        LOG.ok("searchTools called for OC {0}", objectClass);

        List<GoogleVertexToolDescriptor> tools = client.listAllTools();
        if (tools == null || tools.isEmpty()) {
            return;
        }

        String matchUid = extractFilterValue(query, Uid.NAME);
        String matchName = extractFilterValue(query, NAME);

        for (GoogleVertexToolDescriptor tool : tools) {
            ConnectorObject co = toToolConnectorObject(objectClass, tool);
            if (co == null) {
                continue;
            }
            if (matchUid != null && !matchUid.equals(co.getUid().getUidValue())) {
                continue;
            }
            if (matchName != null && !matchName.equals(co.getName().getNameValue())) {
                continue;
            }
            if (!handler.handle(co)) {
                break;
            }
        }
    }

    public void searchKnowledgeBases(ObjectClass objectClass,
                                     Filter query,
                                     ResultsHandler handler,
                                     OperationOptions options) {
        LOG.ok("searchKnowledgeBases called for OC {0}", objectClass);

        List<GoogleVertexDataStoreDescriptor> stores = client.listAllDataStores();
        if (stores == null || stores.isEmpty()) {
            return;
        }

        String matchUid = extractFilterValue(query, Uid.NAME);
        String matchName = extractFilterValue(query, NAME);

        for (GoogleVertexDataStoreDescriptor ds : stores) {
            ConnectorObject co = toKnowledgeBaseConnectorObject(objectClass, ds);
            if (co == null) {
                continue;
            }
            if (matchUid != null && !matchUid.equals(co.getUid().getUidValue())) {
                continue;
            }
            if (matchName != null && !matchName.equals(co.getName().getNameValue())) {
                continue;
            }
            if (!handler.handle(co)) {
                break;
            }
        }
    }

    // OPENICF-4004: Guardrail search
    public void searchGuardrails(ObjectClass objectClass,
                                 Filter query,
                                 ResultsHandler handler,
                                 OperationOptions options) {
        LOG.ok("searchGuardrails called for OC {0}", objectClass);

        List<GoogleVertexGuardrailDescriptor> guardrails = client.listAllGuardrails();
        if (guardrails == null || guardrails.isEmpty()) {
            return;
        }

        String matchUid = extractFilterValue(query, Uid.NAME);
        String matchName = extractFilterValue(query, NAME);

        for (GoogleVertexGuardrailDescriptor guardrail : guardrails) {
            ConnectorObject co = toGuardrailConnectorObject(objectClass, guardrail);
            if (co == null) {
                continue;
            }
            if (matchUid != null && !matchUid.equals(co.getUid().getUidValue())) {
                continue;
            }
            if (matchName != null && !matchName.equals(co.getName().getNameValue())) {
                continue;
            }
            if (!handler.handle(co)) {
                break;
            }
        }
    }

    // OPENICF-4007: Identity bindings are now read from the GCS artifact produced
    // by the offline Python job. The live IAM getIamPolicy path has been removed.
    public void searchIdentityBindings(ObjectClass objectClass,
                                       Filter query,
                                       ResultsHandler handler,
                                       OperationOptions options) {
        LOG.ok("searchIdentityBindings called for OC {0}", objectClass);

        if (!connection.getConfiguration().isIdentityBindingScanEnabled()) {
            LOG.ok("identityBindingScanEnabled=false; skipping agentIdentityBinding search");
            return;
        }

        // Throws ConnectorException on HTTP non-2xx or I/O failure (Q29)
        com.fasterxml.jackson.databind.JsonNode root =
                client.fetchGcsIdentityBindings(connection.getConfiguration().getGcsInventoryBaseUrl());

        com.fasterxml.jackson.databind.JsonNode bindingsNode = root.get("identityBindings");
        if (bindingsNode == null || !bindingsNode.isArray() || bindingsNode.size() == 0) {
            LOG.ok("No identity bindings in GCS artifact");
            return;
        }

        String matchUid = extractFilterValue(query, Uid.NAME);
        String matchName = extractFilterValue(query, NAME);

        for (com.fasterxml.jackson.databind.JsonNode node : bindingsNode) {
            ConnectorObject co = toIdentityBindingConnectorObject(objectClass, node);
            if (co == null) {
                continue;
            }
            if (matchUid != null && !matchUid.equals(co.getUid().getUidValue())) {
                continue;
            }
            if (matchName != null && !matchName.equals(co.getName().getNameValue())) {
                continue;
            }
            if (!handler.handle(co)) {
                break;
            }
        }
    }

    // OPENICF-4007: Service accounts are now read from the GCS artifact produced
    // by the offline Python job. The live IAM/Cloud Asset API path has been removed.
    public void searchServiceAccounts(ObjectClass objectClass,
                                      Filter query,
                                      ResultsHandler handler,
                                      OperationOptions options) {
        LOG.ok("searchServiceAccounts called for OC {0}", objectClass);

        if (!connection.getConfiguration().isIdentityBindingScanEnabled()) {
            LOG.ok("identityBindingScanEnabled=false; skipping serviceAccount search");
            return;
        }

        // Throws ConnectorException on HTTP non-2xx or I/O failure (Q29)
        com.fasterxml.jackson.databind.JsonNode root =
                client.fetchGcsServiceAccounts(connection.getConfiguration().getGcsInventoryBaseUrl());

        com.fasterxml.jackson.databind.JsonNode saNode = root.get("serviceAccounts");
        if (saNode == null || !saNode.isArray() || saNode.size() == 0) {
            LOG.ok("No service accounts in GCS artifact");
            return;
        }

        String matchUid = extractFilterValue(query, Uid.NAME);
        String matchName = extractFilterValue(query, NAME);

        for (com.fasterxml.jackson.databind.JsonNode node : saNode) {
            ConnectorObject co = toServiceAccountConnectorObject(objectClass, node);
            if (co == null) {
                continue;
            }
            if (matchUid != null && !matchUid.equals(co.getUid().getUidValue())) {
                continue;
            }
            if (matchName != null && !matchName.equals(co.getName().getNameValue())) {
                continue;
            }
            if (!handler.handle(co)) {
                break;
            }
        }
    }

    // =================================================================
    // Get operations
    // =================================================================

    public ConnectorObject getAgent(ObjectClass objectClass,
                                    Uid uid,
                                    OperationOptions options) {
        GoogleVertexAgentDescriptor agent = client.getAgent(uid.getUidValue());
        if (agent == null) {
            return null;
        }
        return toAgentConnectorObject(objectClass, agent);
    }

    public ConnectorObject getTool(ObjectClass objectClass,
                                   Uid uid,
                                   OperationOptions options) {
        if (uid == null || uid.getUidValue() == null) {
            return null;
        }

        String id = uid.getUidValue();
        List<GoogleVertexToolDescriptor> tools = client.listAllTools();
        for (GoogleVertexToolDescriptor tool : tools) {
            if (id.equals(tool.getName())) {
                return toToolConnectorObject(objectClass, tool);
            }
        }
        return null;
    }

    public ConnectorObject getKnowledgeBase(ObjectClass objectClass,
                                            Uid uid,
                                            OperationOptions options) {
        if (uid == null || uid.getUidValue() == null) {
            return null;
        }

        String id = uid.getUidValue();
        List<GoogleVertexDataStoreDescriptor> stores = client.listAllDataStores();
        for (GoogleVertexDataStoreDescriptor ds : stores) {
            if (id.equals(ds.getName())) {
                return toKnowledgeBaseConnectorObject(objectClass, ds);
            }
        }
        return null;
    }

    // OPENICF-4004: Get single guardrail
    public ConnectorObject getGuardrail(ObjectClass objectClass,
                                        Uid uid,
                                        OperationOptions options) {
        if (uid == null || uid.getUidValue() == null) {
            return null;
        }

        String id = uid.getUidValue();
        List<GoogleVertexGuardrailDescriptor> guardrails = client.listAllGuardrails();
        for (GoogleVertexGuardrailDescriptor guardrail : guardrails) {
            if (id.equals(guardrail.getId())) {
                return toGuardrailConnectorObject(objectClass, guardrail);
            }
        }
        return null;
    }

    // OPENICF-4007: Get single service account from GCS artifact by UID lookup.
    // The live IAM API path has been removed — service accounts are now produced
    // by the offline Python job and read here via GCS pre-signed URL.
    public ConnectorObject getServiceAccount(ObjectClass objectClass,
                                             Uid uid,
                                             OperationOptions options) {
        if (uid == null || uid.getUidValue() == null) {
            return null;
        }

        if (!connection.getConfiguration().isIdentityBindingScanEnabled()) {
            LOG.ok("identityBindingScanEnabled=false; skipping serviceAccount GET");
            return null;
        }

        // Throws ConnectorException on HTTP non-2xx or I/O failure (Q29)
        com.fasterxml.jackson.databind.JsonNode root =
                client.fetchGcsServiceAccounts(connection.getConfiguration().getGcsInventoryBaseUrl());

        com.fasterxml.jackson.databind.JsonNode saNode = root.get("serviceAccounts");
        if (saNode == null || !saNode.isArray()) {
            return null;
        }

        String targetUid = uid.getUidValue();
        for (com.fasterxml.jackson.databind.JsonNode node : saNode) {
            com.fasterxml.jackson.databind.JsonNode nameNode = node.get("name");
            if (nameNode != null && targetUid.equals(nameNode.asText())) {
                return toServiceAccountConnectorObject(objectClass, node);
            }
        }
        return null;
    }

    // =================================================================
    // ConnectorObject mapping
    // =================================================================

    private ConnectorObject toAgentConnectorObject(ObjectClass objectClass,
                                                   GoogleVertexAgentDescriptor agent) {
        if (agent == null || agent.getResourceName() == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        b.setUid(new Uid(agent.getResourceName()));

        String displayName = agent.getDisplayName() != null
                ? agent.getDisplayName()
                : agent.getResourceName();
        b.setName(new Name(displayName));

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM,
                GoogleVertexAIConstants.PLATFORM_VALUE));

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_AGENT_ID,
                agent.getResourceName()));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_DESCRIPTION, agent.getDescription());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_FOUNDATION_MODEL, agent.getGenerativeModel());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_DEFAULT_LANGUAGE_CODE, agent.getDefaultLanguageCode());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TIME_ZONE, agent.getTimeZone());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_START_FLOW, agent.getStartFlow());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_CREATED_AT, agent.getCreateTime());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_UPDATED_AT, agent.getUpdateTime());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SAFETY_SETTINGS, agent.getSafetySettingsJson());

        // Vertex AI Agent Engine specific
        addIfPresent(b, GoogleVertexAIConstants.ATTR_AGENT_FRAMEWORK, agent.getAgentFramework());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SERVICE_ACCOUNT, agent.getServiceAccount());

        // Fetch tools for this agent to populate toolIds, toolsRaw, and toolAuthSummary
        List<GoogleVertexToolDescriptor> tools = client.listTools(agent.getResourceName());
        if (!tools.isEmpty()) {
            List<String> toolNames = new ArrayList<>();
            List<String> authSummaries = new ArrayList<>();
            for (GoogleVertexToolDescriptor t : tools) {
                toolNames.add(t.getName());
                // OPENICF-4011: build per-tool auth summary entry
                try {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("toolId", t.getName());
                    entry.put("toolKey", t.getToolKey()); // OPENICF-4010
                    entry.put("toolType", t.getToolType());
                    entry.put("authType", t.getAuthType() != null ? t.getAuthType() : "NONE");
                    if (t.getCredentialRef() != null) {
                        entry.put("credentialRef", t.getCredentialRef());
                    }
                    authSummaries.add(OBJECT_MAPPER.writeValueAsString(entry));
                } catch (JsonProcessingException e) {
                    LOG.warn("Failed to serialize toolAuthSummary entry for tool {0}", t.getName());
                }
            }
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_AGENT_TOOL_IDS, toolNames));

            try {
                String toolsJson = OBJECT_MAPPER.writeValueAsString(toolNames);
                b.addAttribute(AttributeBuilder.build(
                        GoogleVertexAIConstants.ATTR_TOOLS_RAW, toolsJson));
            } catch (JsonProcessingException e) {
                LOG.warn("Failed to serialize tool IDs for agent {0}", agent.getResourceName());
            }

            if (!authSummaries.isEmpty()) {
                b.addAttribute(AttributeBuilder.build(
                        GoogleVertexAIConstants.ATTR_TOOL_AUTH_SUMMARY, authSummaries));
            }
        }

        // Fetch data stores for this agent to populate knowledgeBaseIds
        List<GoogleVertexDataStoreDescriptor> stores = client.listDataStores(agent.getResourceName());
        if (!stores.isEmpty()) {
            List<String> storeNames = new ArrayList<>();
            for (GoogleVertexDataStoreDescriptor ds : stores) {
                storeNames.add(ds.getName());
            }
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_AGENT_KNOWLEDGE_BASE_IDS, storeNames));
        }

        return b.build();
    }

    private ConnectorObject toToolConnectorObject(ObjectClass objectClass,
                                                  GoogleVertexToolDescriptor tool) {
        if (tool == null || tool.getName() == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        b.setUid(new Uid(tool.getName()));

        String displayName = tool.getDisplayName() != null
                ? tool.getDisplayName()
                : tool.getName();
        b.setName(new Name(displayName));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_AGENT_ID, tool.getAgentResourceName());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_DESCRIPTION, tool.getDescription());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TOOL_TYPE, tool.getToolType());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TOOL_ENDPOINT, tool.getEndpoint());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TOOL_KEY, tool.getToolKey()); // OPENICF-4010

        return b.build();
    }

    private ConnectorObject toKnowledgeBaseConnectorObject(ObjectClass objectClass,
                                                           GoogleVertexDataStoreDescriptor ds) {
        if (ds == null || ds.getName() == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        b.setUid(new Uid(ds.getName()));

        String displayName = ds.getDisplayName() != null
                ? ds.getDisplayName()
                : ds.getName();
        b.setName(new Name(displayName));

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM,
                GoogleVertexAIConstants.PLATFORM_VALUE));

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_KNOWLEDGE_BASE_ID,
                ds.getName()));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_DATA_STORE_TYPE, ds.getDataStoreType());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_KNOWLEDGE_BASE_STATE, ds.getStatus());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_AGENT_ID, ds.getAgentResourceName());

        return b.build();
    }

    // OPENICF-4007: Maps a raw JSON node from identity-bindings.json (GCS artifact)
    // to a ConnectorObject. Field names match the Python job's output schema.
    private ConnectorObject toIdentityBindingConnectorObject(ObjectClass objectClass,
                                                             com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return null;
        }

        String id = gcsText(node, "id");
        if (id == null || id.isEmpty()) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        b.setUid(new Uid(id));
        // __NAME__: agentId:principal for human readability
        String agentId = gcsText(node, "agentId");
        String principal = gcsText(node, "principal");
        b.setName(new Name(agentId + ":" + principal));

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM,
                GoogleVertexAIConstants.PLATFORM_VALUE));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_AGENT_ID, agentId);
        addIfPresent(b, GoogleVertexAIConstants.ATTR_PRINCIPAL, principal);
        addIfPresent(b, GoogleVertexAIConstants.ATTR_KIND, gcsText(node, "principalType"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SCOPE, gcsText(node, "scopeType"));
        addIfPresent(b, "scopeResourceName", gcsText(node, "scopeResourceName"));
        addIfPresent(b, "sourceTag", gcsText(node, "sourceTag"));
        addIfPresent(b, "confidence", gcsText(node, "confidence"));
        addIfPresent(b, "flavor", gcsText(node, "flavor"));

        // permissions: multi-valued string array from job output
        com.fasterxml.jackson.databind.JsonNode perms = node.get("permissions");
        if (perms != null && perms.isArray()) {
            List<String> permList = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode p : perms) {
                permList.add(p.asText());
            }
            if (!permList.isEmpty()) {
                b.addAttribute(AttributeBuilder.build(
                        GoogleVertexAIConstants.ATTR_PERMISSIONS, permList));
            }
        }

        return b.build();
    }

    // OPENICF-4004: Guardrail mapping
    private ConnectorObject toGuardrailConnectorObject(ObjectClass objectClass,
                                                       GoogleVertexGuardrailDescriptor guardrail) {
        if (guardrail == null || guardrail.getId() == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        // __UID__ = {agentResourceName}:guardrail
        b.setUid(new Uid(guardrail.getId()));

        // __NAME__ = same as UID for guardrails
        b.setName(new Name(guardrail.getId()));

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM,
                GoogleVertexAIConstants.PLATFORM_VALUE));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_AGENT_ID, guardrail.getAgentResourceName());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SAFETY_ENFORCEMENT, guardrail.getSafetyEnforcement());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_RAW_SETTINGS_JSON, guardrail.getRawSettingsJson());

        // Multi-valued: banned phrases
        if (!guardrail.getBannedPhrases().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_BANNED_PHRASES,
                    guardrail.getBannedPhrases()));
        }

        // Multi-valued: default banned phrases
        if (!guardrail.getDefaultBannedPhrases().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_DEFAULT_BANNED_PHRASES,
                    guardrail.getDefaultBannedPhrases()));
        }

        return b.build();
    }

    // OPENICF-4007: Maps a raw JSON node from service-accounts.json (GCS artifact)
    // to a ConnectorObject. Field names match the Python job's output schema.
    // Keys and linkedAgentIds are included as-is from the job output — the live
    // per-SA fetch calls have been removed along with the live SA discovery path.
    private ConnectorObject toServiceAccountConnectorObject(ObjectClass objectClass,
                                                            com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return null;
        }

        String name = gcsText(node, "name");
        if (name == null || name.isEmpty()) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        // __UID__ = full resource name (projects/{project}/serviceAccounts/{email})
        b.setUid(new Uid(name));

        // __NAME__ = email address
        String email = gcsText(node, "email");
        b.setName(new Name(email != null ? email : name));

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM,
                GoogleVertexAIConstants.PLATFORM_VALUE));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_SA_EMAIL, email);
        addIfPresent(b, GoogleVertexAIConstants.ATTR_DESCRIPTION, gcsText(node, "description"));
        addIfPresent(b, "displayName", gcsText(node, "displayName"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SA_PROJECT_ID, gcsText(node, "projectId"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SA_UNIQUE_ID, gcsText(node, "uniqueId"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_CREATED_AT, gcsText(node, "createTime"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SA_OAUTH2_CLIENT_ID, gcsText(node, "oauth2ClientId"));

        com.fasterxml.jackson.databind.JsonNode disabledNode = node.get("disabled");
        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_SA_DISABLED,
                disabledNode != null && disabledNode.asBoolean()));

        // keysJson: already serialized by the Python job
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SA_KEYS, gcsText(node, "keysJson"));

        com.fasterxml.jackson.databind.JsonNode keyCountNode = node.get("keyCount");
        if (keyCountNode != null && !keyCountNode.isNull()) {
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_SA_KEY_COUNT, keyCountNode.asInt()));
        }

        // linkedAgentIds: multi-valued, populated by the Python job
        com.fasterxml.jackson.databind.JsonNode linkedAgents = node.get("linkedAgentIds");
        if (linkedAgents != null && linkedAgents.isArray() && linkedAgents.size() > 0) {
            List<String> ids = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode id : linkedAgents) {
                ids.add(id.asText());
            }
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_SA_LINKED_AGENTS, ids));
        }

        return b.build();
    }

    // =================================================================
    // Helpers
    // =================================================================

    private void addIfPresent(ConnectorObjectBuilder b, String attrName, String value) {
        if (value != null && !value.isEmpty()) {
            b.addAttribute(AttributeBuilder.build(attrName, value));
        }
    }

    /**
     * Extract a single string value from an EqualsFilter on the given attribute name.
     * Returns null if the filter doesn't match.
     */
    private String extractFilterValue(Filter query, String attrName) {
        if (!(query instanceof EqualsFilter)) {
            return null;
        }
        Attribute attr = ((EqualsFilter) query).getAttribute();
        if (attr == null || attr.getValue() == null || attr.getValue().isEmpty()) {
            return null;
        }
        if (!attrName.equalsIgnoreCase(attr.getName())) {
            return null;
        }
        return String.valueOf(attr.getValue().get(0));
    }

    /**
     * Extract a text value from a GCS artifact JSON node.
     * Returns null if the field is absent or null.
     */
    private static String gcsText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
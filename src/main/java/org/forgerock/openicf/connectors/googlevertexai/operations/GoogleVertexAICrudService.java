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

    public void searchIdentityBindings(ObjectClass objectClass,
                                       Filter query,
                                       ResultsHandler handler,
                                       OperationOptions options) {
        LOG.ok("searchIdentityBindings called for OC {0}", objectClass);

        if (!connection.getConfiguration().isIdentityBindingScanEnabled()) {
            LOG.ok("Identity binding scan is disabled.");
            return;
        }

        // OPENICF-4002: Include SA IAM bindings when SA discovery is enabled
        boolean includeServiceAccounts = connection.getConfiguration().isDiscoverServiceAccounts();
        List<GoogleVertexIamBindingDescriptor> bindings = client.listAllIamBindings(includeServiceAccounts);
        if (bindings == null || bindings.isEmpty()) {
            return;
        }

        String matchUid = extractFilterValue(query, Uid.NAME);
        String matchName = extractFilterValue(query, NAME);

        for (GoogleVertexIamBindingDescriptor binding : bindings) {
            ConnectorObject co = toIdentityBindingConnectorObject(objectClass, binding);
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

    // OPENICF-4001: Service account search
    public void searchServiceAccounts(ObjectClass objectClass,
                                      Filter query,
                                      ResultsHandler handler,
                                      OperationOptions options) {
        LOG.ok("searchServiceAccounts called for OC {0}", objectClass);

        if (!connection.getConfiguration().isDiscoverServiceAccounts()) {
            LOG.ok("Service account discovery is disabled.");
            return;
        }

        List<GoogleVertexServiceAccountDescriptor> serviceAccounts = client.listServiceAccounts();
        if (serviceAccounts == null || serviceAccounts.isEmpty()) {
            return;
        }

        String matchUid = extractFilterValue(query, Uid.NAME);
        String matchName = extractFilterValue(query, NAME);

        // Determine which expensive attributes to fetch (Q18)
        boolean includeKeys = connection.getConfiguration().isIncludeServiceAccountKeys()
                && isAttributeRequested(options, GoogleVertexAIConstants.ATTR_SA_KEYS);
        boolean includeLinkedAgents = isAttributeRequested(options, GoogleVertexAIConstants.ATTR_SA_LINKED_AGENTS);

        for (GoogleVertexServiceAccountDescriptor sa : serviceAccounts) {
            ConnectorObject co = toServiceAccountConnectorObject(objectClass, sa, includeKeys, includeLinkedAgents);
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

    // OPENICF-4001: Get single service account
    public ConnectorObject getServiceAccount(ObjectClass objectClass,
                                             Uid uid,
                                             OperationOptions options) {
        if (uid == null || uid.getUidValue() == null) {
            return null;
        }

        if (!connection.getConfiguration().isDiscoverServiceAccounts()) {
            LOG.ok("Service account discovery is disabled.");
            return null;
        }

        GoogleVertexServiceAccountDescriptor sa = client.getServiceAccount(uid.getUidValue());
        if (sa == null) {
            return null;
        }

        // For single-object GET, always include expensive attributes (Q18)
        boolean includeKeys = connection.getConfiguration().isIncludeServiceAccountKeys();
        boolean includeLinkedAgents = true;

        return toServiceAccountConnectorObject(objectClass, sa, includeKeys, includeLinkedAgents);
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

        // Fetch tools for this agent to populate toolIds and toolsRaw
        List<GoogleVertexToolDescriptor> tools = client.listTools(agent.getResourceName());
        if (!tools.isEmpty()) {
            List<String> toolNames = new ArrayList<>();
            for (GoogleVertexToolDescriptor t : tools) {
                toolNames.add(t.getName());
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

    private ConnectorObject toIdentityBindingConnectorObject(ObjectClass objectClass,
                                                             GoogleVertexIamBindingDescriptor binding) {
        if (binding == null || binding.getId() == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        b.setUid(new Uid(binding.getId()));
        b.setName(new Name(binding.getMember() + ":" + binding.getRole()));

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM,
                GoogleVertexAIConstants.PLATFORM_VALUE));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_AGENT_ID, binding.getAgentResourceName());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_IAM_ROLE, binding.getRole());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_IAM_MEMBER, binding.getMember());

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_KIND,
                binding.getKind()));

        // Pack principal info as JSON
        try {
            Map<String, String> principalData = new HashMap<>();
            principalData.put("member", binding.getMember());
            principalData.put("memberType", binding.getMemberType());
            principalData.put("role", binding.getRole());

            String principalJson = OBJECT_MAPPER.writeValueAsString(principalData);
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_PRINCIPAL, principalJson));
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize principal for binding {0}", binding.getId());
        }

        // Permission = the IAM role (single entry)
        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_PERMISSIONS,
                Collections.singletonList(binding.getRole())));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_SCOPE, binding.getAgentResourceName());

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

    // OPENICF-4001: Service account mapping
    private ConnectorObject toServiceAccountConnectorObject(ObjectClass objectClass,
                                                            GoogleVertexServiceAccountDescriptor sa,
                                                            boolean includeKeys,
                                                            boolean includeLinkedAgents) {
        if (sa == null || sa.getName() == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        // __UID__ = full resource name (projects/{project}/serviceAccounts/{email})
        b.setUid(new Uid(sa.getName()));

        // __NAME__ = email address
        String nameValue = sa.getEmail() != null ? sa.getEmail() : sa.getName();
        b.setName(new Name(nameValue));

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM,
                GoogleVertexAIConstants.PLATFORM_VALUE));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_SA_EMAIL, sa.getEmail());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_DESCRIPTION, sa.getDescription());
        addIfPresent(b, "displayName", sa.getDisplayName());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SA_PROJECT_ID, sa.getProjectId());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SA_UNIQUE_ID, sa.getUniqueId());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_CREATED_AT, sa.getCreateTime());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SA_OAUTH2_CLIENT_ID, sa.getOauth2ClientId());

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_SA_DISABLED,
                sa.isDisabled()));

        // Expensive attributes: keys (fetched per-SA if requested)
        if (includeKeys) {
            String keysJson = client.listServiceAccountKeysJson(sa.getName());
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_SA_KEYS, keysJson));

            // Count keys from JSON
            int keyCount = countKeysFromJson(keysJson);
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_SA_KEY_COUNT, keyCount));
        }

        // Expensive attributes: linked agents (requires full agent scan if requested)
        if (includeLinkedAgents && sa.getEmail() != null) {
            List<String> linkedAgents = client.getLinkedAgentsForServiceAccount(sa.getEmail());
            if (!linkedAgents.isEmpty()) {
                b.addAttribute(AttributeBuilder.build(
                        GoogleVertexAIConstants.ATTR_SA_LINKED_AGENTS, linkedAgents));
            }
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
     * Check if an attribute is requested in OperationOptions.
     * Returns true if:
     * - options is null (all attributes)
     * - getAttributesToGet() is null (all attributes)
     * - attrName is in the getAttributesToGet() array
     *
     * OPENICF-4001: Q18 decision
     */
    private boolean isAttributeRequested(OperationOptions options, String attrName) {
        if (options == null) {
            return true;
        }
        String[] attrsToGet = options.getAttributesToGet();
        if (attrsToGet == null) {
            return true;
        }
        for (String attr : attrsToGet) {
            if (attrName.equals(attr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Count keys from JSON array string.
     * OPENICF-4001
     */
    private int countKeysFromJson(String keysJson) {
        if (keysJson == null || keysJson.isEmpty() || "[]".equals(keysJson)) {
            return 0;
        }
        try {
            List<?> keys = OBJECT_MAPPER.readValue(keysJson, List.class);
            return keys.size();
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to parse keys JSON for counting: {0}", e.getMessage());
            return 0;
        }
    }
}
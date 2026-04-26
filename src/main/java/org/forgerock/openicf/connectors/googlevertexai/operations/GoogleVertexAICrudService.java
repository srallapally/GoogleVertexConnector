// src/main/java/org/forgerock/openicf/connectors/googlevertexai/operations/GoogleVertexAICrudService.java
package org.forgerock.openicf.connectors.googlevertexai.operations;

import org.forgerock.openicf.connectors.googlevertexai.GoogleVertexAIConnection;
import org.forgerock.openicf.connectors.googlevertexai.client.*;
import org.forgerock.openicf.connectors.googlevertexai.utils.GoogleVertexAIConstants;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

import static org.identityconnectors.framework.common.objects.Name.NAME;

/**
 * Read-only CRUD service for the Google Vertex AI (Dialogflow CX) connector.
 */
public class GoogleVertexAICrudService {

    private static final Log LOG = Log.getLog(GoogleVertexAICrudService.class);

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

        // RFE-6: cache GCS artifacts once for forward pointer resolution
        JsonNode cachedBindings = null;
        JsonNode cachedServiceAccounts = null;
        JsonNode cachedToolCredentials = null;
        if (connection.getConfiguration().isIdentityBindingScanEnabled()) {
            cachedBindings = client.fetchGcsIdentityBindings(
                    connection.getConfiguration().getGcsIdentityBindingsUrl());
            cachedServiceAccounts = client.fetchGcsServiceAccounts(
                    connection.getConfiguration().getGcsServiceAccountsUrl());
            cachedToolCredentials = client.fetchGcsToolCredentials(
                    connection.getConfiguration().getGcsToolCredentialsUrl());
        }

        String filterRegex = connection.getConfiguration().getAgentNameFilterRegex();

        for (GoogleVertexAgentDescriptor agent : agents) {
            // Apply optional name filter
            if (filterRegex != null && !filterRegex.isEmpty()
                    && agent.getDisplayName() != null
                    && !agent.getDisplayName().matches(filterRegex)) {
                continue;
            }

            ConnectorObject obj = toAgentConnectorObject(objectClass, agent,
                    cachedBindings, cachedServiceAccounts, cachedToolCredentials);
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
                client.fetchGcsIdentityBindings(connection.getConfiguration().getGcsIdentityBindingsUrl());

        // BUG-6: artifact is a bare JSON array, not a wrapped object
        com.fasterxml.jackson.databind.JsonNode bindingsNode = root;
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
                client.fetchGcsServiceAccounts(connection.getConfiguration().getGcsServiceAccountsUrl());

        // BUG-6: artifact is a bare JSON array, not a wrapped object
        com.fasterxml.jackson.databind.JsonNode saNode = root;
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
        // RFE-6: UID is now the short agent ID (last path segment).
        // Must list all agents and match, since we can't reconstruct the full
        // resource name without knowing the flavor (CX vs RE).
        String shortId = uid.getUidValue();
        List<GoogleVertexAgentDescriptor> agents = client.listAgents();
        for (GoogleVertexAgentDescriptor agent : agents) {
            if (shortId.equals(extractShortId(agent.getResourceName()))) {
                // RFE-6: fetch GCS artifacts for forward pointers
                JsonNode cachedBindings = null;
                JsonNode cachedServiceAccounts = null;
                JsonNode cachedToolCredentials = null;
                if (connection.getConfiguration().isIdentityBindingScanEnabled()) {
                    cachedBindings = client.fetchGcsIdentityBindings(
                            connection.getConfiguration().getGcsIdentityBindingsUrl());
                    cachedServiceAccounts = client.fetchGcsServiceAccounts(
                            connection.getConfiguration().getGcsServiceAccountsUrl());
                    cachedToolCredentials = client.fetchGcsToolCredentials(
                            connection.getConfiguration().getGcsToolCredentialsUrl());
                }
                return toAgentConnectorObject(objectClass, agent,
                        cachedBindings, cachedServiceAccounts, cachedToolCredentials);
            }
        }
        return null;
    }

    public ConnectorObject getTool(ObjectClass objectClass,
                                   Uid uid,
                                   OperationOptions options) {
        if (uid == null || uid.getUidValue() == null) {
            return null;
        }

        // RFE-6: UID is now {agentUUID}/tools/{toolUUID} or {agentUUID}/webhooks/{id}
        String shortId = uid.getUidValue();
        List<GoogleVertexToolDescriptor> tools = client.listAllTools();
        for (GoogleVertexToolDescriptor tool : tools) {
            if (shortId.equals(buildShortToolId(tool))) {
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

        // RFE-6: UID is now {agentUUID}/dataStores/{dsId}
        String shortId = uid.getUidValue();
        List<GoogleVertexDataStoreDescriptor> stores = client.listAllDataStores();
        for (GoogleVertexDataStoreDescriptor ds : stores) {
            if (shortId.equals(buildShortDataStoreId(ds))) {
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

        // RFE-6: UID is now {agentUUID}:guardrail
        String shortId = uid.getUidValue();
        List<GoogleVertexGuardrailDescriptor> guardrails = client.listAllGuardrails();
        for (GoogleVertexGuardrailDescriptor guardrail : guardrails) {
            String guardrailShortId = extractShortId(guardrail.getAgentResourceName()) + ":guardrail";
            if (shortId.equals(guardrailShortId)) {
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
                client.fetchGcsServiceAccounts(connection.getConfiguration().getGcsServiceAccountsUrl());

        // BUG-6: artifact is a bare JSON array, not a wrapped object
        com.fasterxml.jackson.databind.JsonNode saNode = root;
        if (saNode == null || !saNode.isArray()) {
            return null;
        }

        String targetUid = uid.getUidValue();
        for (com.fasterxml.jackson.databind.JsonNode node : saNode) {
            // BUG-7: GCS artifact uses "id" for canonical key, not "name"
            com.fasterxml.jackson.databind.JsonNode idNode = node.get("id");
            if (idNode != null && targetUid.equals(idNode.asText())) {
                return toServiceAccountConnectorObject(objectClass, node);
            }
        }
        return null;
    }

    // OPENICF-4017: Tool credentials are read from the GCS artifact produced
    // by the offline Python job. Gated by identityBindingScanEnabled.
    public void searchToolCredentials(ObjectClass objectClass,
                                      Filter query,
                                      ResultsHandler handler,
                                      OperationOptions options) {
        LOG.ok("searchToolCredentials called for OC {0}", objectClass);

        if (!connection.getConfiguration().isIdentityBindingScanEnabled()) {
            LOG.ok("identityBindingScanEnabled=false; skipping agentToolCredential search");
            return;
        }

        com.fasterxml.jackson.databind.JsonNode root =
                client.fetchGcsToolCredentials(connection.getConfiguration().getGcsToolCredentialsUrl());

        if (root == null || !root.isArray() || root.size() == 0) {
            LOG.ok("No tool credentials in GCS artifact");
            return;
        }

        String matchUid = extractFilterValue(query, Uid.NAME);
        String matchName = extractFilterValue(query, NAME);

        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            ConnectorObject co = toToolCredentialConnectorObject(objectClass, node);
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

    // OPENICF-4017: Get single tool credential from GCS artifact by UID.
    public ConnectorObject getToolCredential(ObjectClass objectClass,
                                             Uid uid,
                                             OperationOptions options) {
        if (uid == null || uid.getUidValue() == null) {
            return null;
        }

        if (!connection.getConfiguration().isIdentityBindingScanEnabled()) {
            LOG.ok("identityBindingScanEnabled=false; skipping agentToolCredential GET");
            return null;
        }

        com.fasterxml.jackson.databind.JsonNode root =
                client.fetchGcsToolCredentials(connection.getConfiguration().getGcsToolCredentialsUrl());

        if (root == null || !root.isArray()) {
            return null;
        }

        String targetUid = uid.getUidValue();
        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            com.fasterxml.jackson.databind.JsonNode idNode = node.get("id");
            if (idNode != null && targetUid.equals(idNode.asText())) {
                return toToolCredentialConnectorObject(objectClass, node);
            }
        }
        return null;
    }

    // =================================================================
    // ConnectorObject mapping
    // =================================================================

    // RFE-6: accepts cached GCS artifacts for forward pointer resolution
    private ConnectorObject toAgentConnectorObject(ObjectClass objectClass,
                                                   GoogleVertexAgentDescriptor agent,
                                                   JsonNode cachedBindings,
                                                   JsonNode cachedServiceAccounts,
                                                   JsonNode cachedToolCredentials) {
        if (agent == null || agent.getResourceName() == null) {
            return null;
        }

        String resourceName = agent.getResourceName();
        String shortId = extractShortId(resourceName);

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        // RFE-6: UID = last path segment
        b.setUid(new Uid(shortId));

        String displayName = agent.getDisplayName() != null
                ? agent.getDisplayName()
                : resourceName;
        b.setName(new Name(displayName));

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM,
                GoogleVertexAIConstants.PLATFORM_VALUE));

        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_AGENT_ID,
                resourceName));

        // RFE-6: project/region extracted from resource name
        addIfPresent(b, GoogleVertexAIConstants.ATTR_PROJECT_ID, extractProjectId(resourceName));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_REGION, extractRegion(resourceName));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_DESCRIPTION, agent.getDescription());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_FOUNDATION_MODEL, agent.getGenerativeModel());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_DEFAULT_LANGUAGE_CODE, agent.getDefaultLanguageCode());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TIME_ZONE, agent.getTimeZone());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_START_FLOW, agent.getStartFlow());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_START_PLAYBOOK, agent.getStartPlaybook()); // RFE-2
        addIfPresent(b, GoogleVertexAIConstants.ATTR_CREATED_AT, agent.getCreateTime());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_UPDATED_AT, agent.getUpdateTime());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SAFETY_SETTINGS, agent.getSafetySettingsJson());

        // Vertex AI Agent Engine specific
        addIfPresent(b, GoogleVertexAIConstants.ATTR_AGENT_FRAMEWORK, agent.getAgentFramework());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SERVICE_ACCOUNT, agent.getServiceAccount());

        // RFE-6: toolIds as short IDs
        List<GoogleVertexToolDescriptor> tools = client.listTools(resourceName);
        if (!tools.isEmpty()) {
            List<String> shortToolIds = new ArrayList<>();
            for (GoogleVertexToolDescriptor t : tools) {
                shortToolIds.add(buildShortToolId(t));
            }
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_AGENT_TOOL_IDS, shortToolIds));
        }

        // RFE-6: knowledgeBaseIds as short IDs
        List<GoogleVertexDataStoreDescriptor> stores = client.listDataStores(resourceName);
        if (!stores.isEmpty()) {
            List<String> shortStoreIds = new ArrayList<>();
            for (GoogleVertexDataStoreDescriptor ds : stores) {
                shortStoreIds.add(buildShortDataStoreId(ds));
            }
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_AGENT_KNOWLEDGE_BASE_IDS, shortStoreIds));
        }

        // RFE-6: guardrailId — populated when agent has safety settings
        if (agent.getSafetySettingsJson() != null && !agent.getSafetySettingsJson().isEmpty()) {
            GoogleVertexGuardrailDescriptor guardrail = client.parseGuardrailFromAgent(agent);
            if (guardrail != null && guardrail.hasConfiguration()) {
                b.addAttribute(AttributeBuilder.build(
                        GoogleVertexAIConstants.ATTR_AGENT_GUARDRAIL_ID,
                        shortId + ":guardrail"));
            }
        }

        // RFE-6: cross-OC forward pointers from GCS artifacts
        List<String> bindingIds = resolveBindingIds(shortId, cachedBindings);
        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_IDENTITY_BINDING_IDS, bindingIds));

        List<String> saIds = resolveServiceAccountIds(shortId, cachedServiceAccounts);
        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_SERVICE_ACCOUNT_IDS, saIds));

        List<String> tcIds = resolveToolCredentialIds(resourceName, cachedToolCredentials);
        b.addAttribute(AttributeBuilder.build(
                GoogleVertexAIConstants.ATTR_TOOL_CREDENTIAL_IDS, tcIds));

        return b.build();
    }

    private ConnectorObject toToolConnectorObject(ObjectClass objectClass,
                                                  GoogleVertexToolDescriptor tool) {
        if (tool == null || tool.getName() == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        // RFE-6: UID = {agentUUID}/tools/{toolUUID} or {agentUUID}/webhooks/{id}
        b.setUid(new Uid(buildShortToolId(tool)));

        String displayName = tool.getDisplayName() != null
                ? tool.getDisplayName()
                : tool.getName();
        b.setName(new Name(displayName));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_AGENT_ID, tool.getAgentResourceName());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_DESCRIPTION, tool.getDescription());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TOOL_TYPE, tool.getToolType());
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TOOL_ENDPOINT, tool.getEndpoint());

        // RFE-6: project/region extracted from tool resource name
        addIfPresent(b, GoogleVertexAIConstants.ATTR_PROJECT_ID, extractProjectId(tool.getName()));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_REGION, extractRegion(tool.getName()));

        return b.build();
    }

    private ConnectorObject toKnowledgeBaseConnectorObject(ObjectClass objectClass,
                                                           GoogleVertexDataStoreDescriptor ds) {
        if (ds == null || ds.getName() == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        // RFE-6: UID = {agentUUID}/dataStores/{dsId}
        b.setUid(new Uid(buildShortDataStoreId(ds)));

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

        // RFE-6: project/region extracted from resource name
        addIfPresent(b, GoogleVertexAIConstants.ATTR_PROJECT_ID, extractProjectId(ds.getName()));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_REGION, extractRegion(ds.getName()));

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

        // OPENICF-4008: aligned to Python job NormalizedIdentityBinding output schema
        addIfPresent(b, GoogleVertexAIConstants.ATTR_AGENT_ID, agentId);
        addIfPresent(b, GoogleVertexAIConstants.ATTR_AGENT_VERSION, gcsText(node, "agentVersion"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_PRINCIPAL, principal);
        addIfPresent(b, GoogleVertexAIConstants.ATTR_KIND, gcsText(node, "principalType"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_IAM_MEMBER, gcsText(node, "iamMember"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_IAM_ROLE, gcsText(node, "iamRole"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SCOPE, gcsText(node, "scopeType"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SCOPE_RESOURCE_NAME, gcsText(node, "scopeResourceName"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_SOURCE_TAG, gcsText(node, "sourceTag"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_CONFIDENCE, gcsText(node, "confidence"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_FLAVOR, gcsText(node, "flavor"));

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

        // expanded: boolean — true if principal is not an unexpanded group
        com.fasterxml.jackson.databind.JsonNode expandedNode = node.get("expanded");
        if (expandedNode != null && !expandedNode.isNull()) {
            b.addAttribute(AttributeBuilder.build(
                    GoogleVertexAIConstants.ATTR_EXPANDED, expandedNode.asBoolean()));
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

        // RFE-6: __UID__ = {agentUUID}:guardrail
        String guardrailShortId = extractShortId(guardrail.getAgentResourceName()) + ":guardrail";
        b.setUid(new Uid(guardrailShortId));

        // __NAME__ = same as UID for guardrails
        b.setName(new Name(guardrailShortId));

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

        // BUG-7: GCS artifact uses "id" for canonical key, not "name"
        String name = gcsText(node, "id");
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

    // OPENICF-4017: Maps a raw JSON node from tool-credentials.json (GCS artifact)
    // to a ConnectorObject. Field names match the Python job's output schema.
    private ConnectorObject toToolCredentialConnectorObject(ObjectClass objectClass,
                                                            com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return null;
        }

        String id = gcsText(node, "id");
        if (id == null || id.isEmpty()) {
            return null;
        }

        String toolId = gcsText(node, "toolId");
        if (toolId == null || toolId.isEmpty()) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        // __UID__ = tc-{sha1[:16]} from job
        b.setUid(new Uid(id));
        // __NAME__ = full webhook resource name
        b.setName(new Name(toolId));

        addIfPresent(b, GoogleVertexAIConstants.ATTR_TC_TOOL_ID, toolId);
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TC_TOOL_KEY, gcsText(node, "toolKey"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TC_TOOL_TYPE, gcsText(node, "toolType"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TC_AGENT_ID, gcsText(node, "agentId"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TC_AUTH_TYPE, gcsText(node, "authType"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TC_CREDENTIAL_REF, gcsText(node, "credentialRef"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TC_PROJECT_ID, gcsText(node, "projectId"));
        addIfPresent(b, GoogleVertexAIConstants.ATTR_TC_LOCATION, gcsText(node, "location"));

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

    // =================================================================
    // RFE-6: Resource name parsing helpers
    // =================================================================

    /**
     * Extract the last path segment from a GCP resource name.
     * e.g. "projects/p/locations/l/agents/abc-123" → "abc-123"
     */
    static String extractShortId(String resourceName) {
        if (resourceName == null) {
            return null;
        }
        int idx = resourceName.lastIndexOf('/');
        return idx >= 0 ? resourceName.substring(idx + 1) : resourceName;
    }

    /**
     * Extract project ID from a resource name.
     * e.g. "projects/my-project/locations/us-central1/agents/123" → "my-project"
     */
    static String extractProjectId(String resourceName) {
        if (resourceName == null) {
            return null;
        }
        // Pattern: projects/{projectId}/...
        if (!resourceName.startsWith("projects/")) {
            return null;
        }
        int start = "projects/".length();
        int end = resourceName.indexOf('/', start);
        return end > start ? resourceName.substring(start, end) : null;
    }

    /**
     * Extract region from a resource name.
     * e.g. "projects/p/locations/us-central1/agents/123" → "us-central1"
     */
    static String extractRegion(String resourceName) {
        if (resourceName == null) {
            return null;
        }
        int locIdx = resourceName.indexOf("/locations/");
        if (locIdx < 0) {
            return null;
        }
        int start = locIdx + "/locations/".length();
        int end = resourceName.indexOf('/', start);
        return end > start ? resourceName.substring(start, end) : resourceName.substring(start);
    }

    /**
     * Build short tool ID: {agentUUID}/tools/{toolUUID} or {agentUUID}/webhooks/{webhookId}.
     * Derives the suffix segment type (tools or webhooks) from the full resource name.
     */
    static String buildShortToolId(GoogleVertexToolDescriptor tool) {
        String name = tool.getName();
        String agentShortId = extractShortId(tool.getAgentResourceName());
        // name: .../agents/{a}/tools/{t} or .../agents/{a}/webhooks/{w}
        // Find the segment type and ID after the agent path
        int agentsIdx = name.indexOf("/agents/");
        if (agentsIdx < 0) {
            // fallback for unexpected format
            return agentShortId + "/tools/" + extractShortId(name);
        }
        // skip past "/agents/{agentId}/"
        String afterAgents = name.substring(agentsIdx + "/agents/".length());
        int slashIdx = afterAgents.indexOf('/');
        if (slashIdx < 0) {
            return agentShortId + "/tools/" + extractShortId(name);
        }
        // afterAgents is now "{agentId}/tools/{toolId}" or "{agentId}/webhooks/{id}"
        String suffix = afterAgents.substring(slashIdx + 1); // "tools/{toolId}" or "webhooks/{id}"
        return agentShortId + "/" + suffix;
    }

    /**
     * Build short data store ID: {agentUUID}/dataStores/{dsId}.
     */
    static String buildShortDataStoreId(GoogleVertexDataStoreDescriptor ds) {
        String agentShortId = extractShortId(ds.getAgentResourceName());
        String dsShortId = extractShortId(ds.getName());
        return agentShortId + "/dataStores/" + dsShortId;
    }

    // =================================================================
    // RFE-6: Forward pointer resolution from cached GCS artifacts
    // =================================================================

    /**
     * Find identity binding IDs that reference this agent (by short agent ID).
     * The job's identity-bindings.json uses short agent IDs in the agentId field.
     */
    private List<String> resolveBindingIds(String agentShortId, JsonNode cachedBindings) {
        List<String> ids = new ArrayList<>();
        if (cachedBindings == null || !cachedBindings.isArray()) {
            return ids;
        }
        for (JsonNode node : cachedBindings) {
            String bindingAgentId = gcsText(node, "agentId");
            if (agentShortId.equals(bindingAgentId)) {
                String id = gcsText(node, "id");
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * Find service account IDs linked to this agent (by short agent ID).
     * The job's service-accounts.json has linkedAgentIds containing short IDs.
     */
    private List<String> resolveServiceAccountIds(String agentShortId, JsonNode cachedServiceAccounts) {
        List<String> ids = new ArrayList<>();
        if (cachedServiceAccounts == null || !cachedServiceAccounts.isArray()) {
            return ids;
        }
        for (JsonNode node : cachedServiceAccounts) {
            JsonNode linkedAgents = node.get("linkedAgentIds");
            if (linkedAgents != null && linkedAgents.isArray()) {
                for (JsonNode agentIdNode : linkedAgents) {
                    if (agentShortId.equals(agentIdNode.asText())) {
                        // BUG-7: GCS artifact uses "id" for canonical key, not "name"
                        String saId = gcsText(node, "id");
                        if (saId != null) {
                            ids.add(saId);
                        }
                        break;
                    }
                }
            }
        }
        return ids;
    }

    /**
     * Find tool credential IDs for this agent (by full agent resource name).
     * The job's tool-credentials.json uses full agent resource names in agentId.
     */
    private List<String> resolveToolCredentialIds(String agentResourceName, JsonNode cachedToolCredentials) {
        List<String> ids = new ArrayList<>();
        if (cachedToolCredentials == null || !cachedToolCredentials.isArray()) {
            return ids;
        }
        for (JsonNode node : cachedToolCredentials) {
            String tcAgentId = gcsText(node, "agentId");
            if (agentResourceName.equals(tcAgentId)) {
                String id = gcsText(node, "id");
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
package org.forgerock.openicf.connectors.googlevertexai.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.forgerock.openicf.connectors.googlevertexai.utils.GoogleVertexAIConstants.FLAVOR_DIALOGFLOW_CX;
import static org.forgerock.openicf.connectors.googlevertexai.utils.GoogleVertexAIConstants.FLAVOR_VERTEX_AI;

/**
 * REST client for Google Cloud agent APIs.
 *
 * <p>Supports two API flavors controlled by the {@code agentApiFlavor} field:
 * <ul>
 *   <li><b>dialogflowcx</b> — Dialogflow CX API
 *       ({@code https://{location}-dialogflow.googleapis.com/v3/...})</li>
 *   <li><b>vertexai</b> — Vertex AI Agent Engine / ReasoningEngines API
 *       ({@code https://{location}-aiplatform.googleapis.com/v1/...})</li>
 * </ul>
 *
 * <p>Authentication supports two modes:
 * <ul>
 *   <li>Service account key JSON → JWT exchange at Google OAuth endpoint</li>
 *   <li>Workload Identity / ADC → delegated to google-auth-library (future)</li>
 * </ul>
 */
public class GoogleVertexAIClient implements AutoCloseable, Closeable {

    // ---------------------------------------------------------------------
    // Core context
    // ---------------------------------------------------------------------
    private final String projectId;
    private final String location;
    private final String agentApiFlavor; // "dialogflowcx" or "vertexai"
    private final boolean useWorkloadIdentity;
    private final String serviceAccountKeyJson; // plain-text JSON key

    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Token cache
    private volatile String cachedAccessToken;
    private volatile Instant cachedTokenExpiresAt;

    // Per-agent caches (populated during listing, TTL-based)
    private final Map<String, List<GoogleVertexToolDescriptor>> toolsCache = new ConcurrentHashMap<>();
    private final Map<String, List<GoogleVertexDataStoreDescriptor>> dataStoreCache = new ConcurrentHashMap<>();
    private volatile Instant cacheLoadedAt = Instant.EPOCH;
    private static final long CACHE_TTL_SECONDS = 300L; // 5 minutes

    private static final String DIALOGFLOW_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    // ---------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------

    /**
     * Constructor for workload identity / ADC.
     */
    public GoogleVertexAIClient(String projectId, String location, String agentApiFlavor) {
        this(projectId, location, agentApiFlavor, true, null);
    }

    /**
     * Constructor for service account key authentication.
     */
    public GoogleVertexAIClient(String projectId,
                                String location,
                                String agentApiFlavor,
                                String serviceAccountKeyJson) {
        this(projectId, location, agentApiFlavor, false, serviceAccountKeyJson);
    }

    private GoogleVertexAIClient(String projectId,
                                 String location,
                                 String agentApiFlavor,
                                 boolean useWorkloadIdentity,
                                 String serviceAccountKeyJson) {
        this.projectId = projectId;
        this.location = location;
        this.agentApiFlavor = agentApiFlavor != null ? agentApiFlavor : FLAVOR_DIALOGFLOW_CX;
        this.useWorkloadIdentity = useWorkloadIdentity;
        this.serviceAccountKeyJson = serviceAccountKeyJson;
        this.requestTimeout = Duration.ofSeconds(30);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();

        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ---------------------------------------------------------------------
    // API base URL
    // ---------------------------------------------------------------------

    private String baseUrl() {
        if (FLAVOR_VERTEX_AI.equals(agentApiFlavor)) {
            // https://{location}-aiplatform.googleapis.com/v1
            return "https://" + location + "-aiplatform.googleapis.com/v1";
        }
        // https://{location}-dialogflow.googleapis.com/v3
        return "https://" + location + "-dialogflow.googleapis.com/v3";
    }

    private String agentsParent() {
        return "projects/" + projectId + "/locations/" + location;
    }

    private boolean isVertexAI() {
        return FLAVOR_VERTEX_AI.equals(agentApiFlavor);
    }

    // ---------------------------------------------------------------------
    // Token acquisition
    // ---------------------------------------------------------------------

    /**
     * Acquire a bearer token using the service account key.
     * <p>
     * For workload identity, this would delegate to
     * {@code GoogleCredentials.getApplicationDefault()} — left as a
     * documented extension point.
     */
    protected synchronized String acquireBearerToken() {
        if (useWorkloadIdentity) {
            // TODO: integrate google-auth-library-java for ADC
            throw new IllegalStateException(
                    "Workload identity auth is not yet implemented. " +
                            "Configure a service account key JSON.");
        }

        Instant now = Instant.now();
        if (cachedAccessToken != null && cachedTokenExpiresAt != null
                && cachedTokenExpiresAt.isAfter(now.plusSeconds(60))) {
            return cachedAccessToken;
        }

        if (serviceAccountKeyJson == null || serviceAccountKeyJson.isEmpty()) {
            throw new IllegalStateException(
                    "Service account key JSON must be configured.");
        }

        try {
            // Parse key JSON to get client_email and private_key
            JsonNode keyNode = objectMapper.readTree(serviceAccountKeyJson);
            String clientEmail = keyNode.get("client_email").asText();
            String privateKeyPem = keyNode.get("private_key").asText();

            // Build JWT
            long iat = now.getEpochSecond();
            long exp = iat + 3600;

            String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");

            String claimSet = "{" +
                    "\"iss\":\"" + clientEmail + "\"," +
                    "\"scope\":\"" + DIALOGFLOW_SCOPE + "\"," +
                    "\"aud\":\"" + TOKEN_ENDPOINT + "\"," +
                    "\"iat\":" + iat + "," +
                    "\"exp\":" + exp +
                    "}";
            String payload = base64Url(claimSet);

            String signingInput = header + "." + payload;
            String signature = signRs256(signingInput, privateKeyPem);

            String jwt = signingInput + "." + signature;

            // Exchange JWT for access token
            String body = "grant_type=" + urlEncode("urn:ietf:params:oauth:grant-type:jwt-bearer")
                    + "&assertion=" + urlEncode(jwt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_ENDPOINT))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("Token request failed: HTTP "
                        + response.statusCode() + " body=" + response.body());
            }

            JsonNode tokenNode = objectMapper.readTree(response.body());
            cachedAccessToken = tokenNode.get("access_token").asText();

            long expiresIn = tokenNode.has("expires_in")
                    ? tokenNode.get("expires_in").asLong()
                    : 3600L;
            cachedTokenExpiresAt = now.plusSeconds(expiresIn);

            return cachedAccessToken;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error acquiring access token", e);
        }
    }

    private String signRs256(String data, String privateKeyPem) {
        try {
            String stripped = privateKeyPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");

            byte[] keyBytes = Base64.getDecoder().decode(stripped);
            java.security.spec.PKCS8EncodedKeySpec spec =
                    new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            java.security.PrivateKey key = kf.generatePrivate(spec);

            java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            byte[] signed = sig.sign();

            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signed);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign JWT", e);
        }
    }

    private String base64Url(String input) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private String urlEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder authedRequestBuilder(String url) {
        String token = acquireBearerToken();
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
    }

    // ---------------------------------------------------------------------
    // Agent listing
    // ---------------------------------------------------------------------

    /**
     * List all agents, paging until nextPageToken is empty.
     */
    public List<GoogleVertexAgentDescriptor> listAgents() {
        List<GoogleVertexAgentDescriptor> all = new ArrayList<>();
        String pageToken = null;

        do {
            ListAgentsPage page = listAgentsPaginated(50, pageToken);
            all.addAll(page.getAgents());
            pageToken = page.getNextPageToken();
        } while (pageToken != null);

        return all;
    }

    public ListAgentsPage listAgentsPaginated(int pageSize, String pageToken) {
        // Dialogflow CX: .../agents?pageSize=N
        // Vertex AI:      .../reasoningEngines?pageSize=N
        String resourceSegment = isVertexAI() ? "reasoningEngines" : "agents";

        StringBuilder url = new StringBuilder(baseUrl())
                .append("/").append(agentsParent()).append("/").append(resourceSegment)
                .append("?pageSize=").append(pageSize);

        if (pageToken != null && !pageToken.isEmpty()) {
            url.append("&pageToken=").append(urlEncode(pageToken));
        }

        try {
            HttpRequest request = authedRequestBuilder(url.toString())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("listAgents failed: HTTP "
                        + response.statusCode() + " body=" + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            List<GoogleVertexAgentDescriptor> agents = new ArrayList<>();

            // Response key: "agents" (Dialogflow CX) or "reasoningEngines" (Vertex AI)
            String listKey = isVertexAI() ? "reasoningEngines" : "agents";

            if (root.has(listKey) && root.get(listKey).isArray()) {
                for (JsonNode node : root.get(listKey)) {
                    GoogleVertexAgentDescriptor agent = isVertexAI()
                            ? parseReasoningEngineNode(node)
                            : parseDialogflowAgentNode(node);
                    if (agent != null) {
                        agents.add(agent);
                    }
                }
            }

            String nextToken = optText(root, "nextPageToken");

            return new ListAgentsPage(agents, nextToken);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error calling listAgents", e);
        }
    }

    /**
     * Get a single agent by full resource name.
     */
    public GoogleVertexAgentDescriptor getAgent(String agentResourceName) {
        String url = baseUrl() + "/" + agentResourceName;

        try {
            HttpRequest request = authedRequestBuilder(url)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("getAgent failed: HTTP "
                        + response.statusCode() + " body=" + response.body());
            }

            JsonNode node = objectMapper.readTree(response.body());
            return isVertexAI()
                    ? parseReasoningEngineNode(node)
                    : parseDialogflowAgentNode(node);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error calling getAgent", e);
        }
    }

    // ---------------------------------------------------------------------
    // Tools listing (per agent)
    // ---------------------------------------------------------------------

    /**
     * List tools for a specific agent. Results are cached.
     *
     * <p>Only Dialogflow CX agents expose tools/webhooks as sub-resources.
     * For Vertex AI Agent Engine agents, this returns an empty list.
     */
    public List<GoogleVertexToolDescriptor> listTools(String agentResourceName) {
        if (isVertexAI()) {
            return Collections.emptyList();
        }

        if (isCacheValid() && toolsCache.containsKey(agentResourceName)) {
            return toolsCache.get(agentResourceName);
        }

        List<GoogleVertexToolDescriptor> tools = new ArrayList<>();
        tools.addAll(fetchTools(agentResourceName));
        tools.addAll(fetchWebhooks(agentResourceName));

        toolsCache.put(agentResourceName, Collections.unmodifiableList(tools));
        return tools;
    }

    private List<GoogleVertexToolDescriptor> fetchTools(String agentResourceName) {
        List<GoogleVertexToolDescriptor> result = new ArrayList<>();
        String pageToken = null;

        do {
            StringBuilder url = new StringBuilder(baseUrl())
                    .append("/").append(agentResourceName).append("/tools")
                    .append("?pageSize=100");

            if (pageToken != null && !pageToken.isEmpty()) {
                url.append("&pageToken=").append(urlEncode(pageToken));
            }

            try {
                HttpRequest request = authedRequestBuilder(url.toString())
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() / 100 != 2) {
                    // Tools API may 404 if agent has no tools configured
                    break;
                }

                JsonNode root = objectMapper.readTree(response.body());

                if (root.has("tools") && root.get("tools").isArray()) {
                    for (JsonNode node : root.get("tools")) {
                        GoogleVertexToolDescriptor tool = parseToolNode(node, agentResourceName);
                        if (tool != null) {
                            result.add(tool);
                        }
                    }
                }

                pageToken = optText(root, "nextPageToken");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Error listing tools for " + agentResourceName, e);
            }
        } while (pageToken != null);

        return result;
    }

    private List<GoogleVertexToolDescriptor> fetchWebhooks(String agentResourceName) {
        List<GoogleVertexToolDescriptor> result = new ArrayList<>();
        String pageToken = null;

        do {
            StringBuilder url = new StringBuilder(baseUrl())
                    .append("/").append(agentResourceName).append("/webhooks")
                    .append("?pageSize=100");

            if (pageToken != null && !pageToken.isEmpty()) {
                url.append("&pageToken=").append(urlEncode(pageToken));
            }

            try {
                HttpRequest request = authedRequestBuilder(url.toString())
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() / 100 != 2) {
                    break;
                }

                JsonNode root = objectMapper.readTree(response.body());

                if (root.has("webhooks") && root.get("webhooks").isArray()) {
                    for (JsonNode node : root.get("webhooks")) {
                        GoogleVertexToolDescriptor webhook = parseWebhookNode(node, agentResourceName);
                        if (webhook != null) {
                            result.add(webhook);
                        }
                    }
                }

                pageToken = optText(root, "nextPageToken");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Error listing webhooks for " + agentResourceName, e);
            }
        } while (pageToken != null);

        return result;
    }

    /**
     * List all tools across all agents.
     */
    public List<GoogleVertexToolDescriptor> listAllTools() {
        List<GoogleVertexToolDescriptor> all = new ArrayList<>();
        for (GoogleVertexAgentDescriptor agent : listAgents()) {
            all.addAll(listTools(agent.getResourceName()));
        }
        return all;
    }

    // ---------------------------------------------------------------------
    // Data stores listing (per agent)
    // ---------------------------------------------------------------------

    /**
     * List data store connections for a specific agent. Results are cached.
     *
     * <p>Only Dialogflow CX agents expose data stores as sub-resources.
     * For Vertex AI Agent Engine agents, this returns an empty list.
     */
    public List<GoogleVertexDataStoreDescriptor> listDataStores(String agentResourceName) {
        if (isVertexAI()) {
            return Collections.emptyList();
        }

        if (isCacheValid() && dataStoreCache.containsKey(agentResourceName)) {
            return dataStoreCache.get(agentResourceName);
        }

        List<GoogleVertexDataStoreDescriptor> stores = new ArrayList<>();
        String pageToken = null;

        do {
            StringBuilder url = new StringBuilder(baseUrl())
                    .append("/").append(agentResourceName).append("/dataStores")
                    .append("?pageSize=100");

            if (pageToken != null && !pageToken.isEmpty()) {
                url.append("&pageToken=").append(urlEncode(pageToken));
            }

            try {
                HttpRequest request = authedRequestBuilder(url.toString())
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() / 100 != 2) {
                    break;
                }

                JsonNode root = objectMapper.readTree(response.body());

                if (root.has("dataStores") && root.get("dataStores").isArray()) {
                    for (JsonNode node : root.get("dataStores")) {
                        GoogleVertexDataStoreDescriptor ds = parseDataStoreNode(node, agentResourceName);
                        if (ds != null) {
                            stores.add(ds);
                        }
                    }
                }

                pageToken = optText(root, "nextPageToken");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Error listing data stores for " + agentResourceName, e);
            }
        } while (pageToken != null);

        dataStoreCache.put(agentResourceName, Collections.unmodifiableList(stores));
        return stores;
    }

    /**
     * List all data stores across all agents.
     */
    public List<GoogleVertexDataStoreDescriptor> listAllDataStores() {
        List<GoogleVertexDataStoreDescriptor> all = new ArrayList<>();
        for (GoogleVertexAgentDescriptor agent : listAgents()) {
            all.addAll(listDataStores(agent.getResourceName()));
        }
        return all;
    }

    // ---------------------------------------------------------------------
    // IAM bindings (per agent, opt-in)
    // ---------------------------------------------------------------------

    /**
     * Fetch IAM policy bindings for a specific agent resource.
     */
    public List<GoogleVertexIamBindingDescriptor> getIamBindings(String agentResourceName) {
        String url = baseUrl() + "/" + agentResourceName + ":getIamPolicy";

        try {
            HttpRequest request = authedRequestBuilder(url)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                // May not have permission — return empty rather than fail
                return Collections.emptyList();
            }

            JsonNode root = objectMapper.readTree(response.body());
            List<GoogleVertexIamBindingDescriptor> result = new ArrayList<>();

            if (root.has("bindings") && root.get("bindings").isArray()) {
                for (JsonNode bindingNode : root.get("bindings")) {
                    String role = optText(bindingNode, "role");
                    if (role == null) {
                        continue;
                    }

                    if (bindingNode.has("members") && bindingNode.get("members").isArray()) {
                        for (JsonNode memberNode : bindingNode.get("members")) {
                            String member = memberNode.asText();
                            String memberType = GoogleVertexIamBindingDescriptor.deriveMemberType(member);
                            String id = agentResourceName + ":" + role + ":" + member;

                            result.add(new GoogleVertexIamBindingDescriptor(
                                    id, agentResourceName, role, member, memberType));
                        }
                    }
                }
            }

            return result;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error fetching IAM policy for " + agentResourceName, e);
        }
    }

    /**
     * List all IAM bindings across all agents.
     */
    public List<GoogleVertexIamBindingDescriptor> listAllIamBindings() {
        List<GoogleVertexIamBindingDescriptor> all = new ArrayList<>();
        for (GoogleVertexAgentDescriptor agent : listAgents()) {
            all.addAll(getIamBindings(agent.getResourceName()));
        }
        return all;
    }

    // ---------------------------------------------------------------------
    // JSON parsing helpers
    // ---------------------------------------------------------------------

    private GoogleVertexAgentDescriptor parseDialogflowAgentNode(JsonNode node) {
        if (node == null) {
            return null;
        }

        String name = optText(node, "name");
        if (name == null || name.isEmpty()) {
            return null;
        }

        String generativeModel = null;
        String safetySettingsJson = null;

        if (node.has("generativeSettings")) {
            JsonNode genSettings = node.get("generativeSettings");
            if (genSettings.has("generativeModel")) {
                generativeModel = genSettings.get("generativeModel").asText();
            }
            if (genSettings.has("safetySettings")) {
                safetySettingsJson = genSettings.get("safetySettings").toString();
            }
        }

        // Tool references are not inline in the agent response;
        // they'll be populated separately via listTools().
        // Same for data store references.

        return new GoogleVertexAgentDescriptor(
                name,
                optText(node, "displayName"),
                optText(node, "description"),
                optText(node, "defaultLanguageCode"),
                optText(node, "timeZone"),
                optText(node, "startFlow"),
                optText(node, "createTime"),
                optText(node, "updateTime"),
                generativeModel,
                safetySettingsJson,
                null, // toolIds populated later
                null, // dataStoreIds populated later
                null, // agentFramework — Dialogflow CX only
                null  // serviceAccount — Dialogflow CX only
        );
    }

    /**
     * Parse a Vertex AI Agent Engine (reasoningEngine) JSON node.
     *
     * Key fields:
     * - name: projects/{p}/locations/{l}/reasoningEngines/{id}
     * - displayName
     * - description
     * - createTime, updateTime
     * - spec.agentFramework (e.g. "google-adk", "langchain")
     * - spec.classMethods[] (OpenAPI method declarations)
     * - deploymentSpec.serviceAccount
     */
    private GoogleVertexAgentDescriptor parseReasoningEngineNode(JsonNode node) {
        if (node == null) {
            return null;
        }

        String name = optText(node, "name");
        if (name == null || name.isEmpty()) {
            return null;
        }

        String agentFramework = null;
        if (node.has("spec")) {
            agentFramework = optText(node.get("spec"), "agentFramework");
        }

        String serviceAccount = null;
        if (node.has("deploymentSpec")) {
            serviceAccount = optText(node.get("deploymentSpec"), "serviceAccount");
        }

        return new GoogleVertexAgentDescriptor(
                name,
                optText(node, "displayName"),
                optText(node, "description"),
                null, // no defaultLanguageCode
                null, // no timeZone
                null, // no startFlow
                optText(node, "createTime"),
                optText(node, "updateTime"),
                null, // no generativeModel (model is embedded in agent code)
                null, // no safetySettings
                null, // no toolIds sub-resource
                null, // no dataStoreIds sub-resource
                agentFramework,
                serviceAccount
        );
    }

    private GoogleVertexToolDescriptor parseToolNode(JsonNode node, String agentResourceName) {
        String name = optText(node, "name");
        if (name == null || name.isEmpty()) {
            return null;
        }

        String toolType = optText(node, "toolType");
        if (toolType == null) {
            // Infer from structure
            if (node.has("dataStoreSpec")) {
                toolType = "DATA_STORE_TOOL";
            } else if (node.has("openApiSpec")) {
                toolType = "OPENAPI_TOOL";
            } else if (node.has("functionDeclarations")) {
                toolType = "CUSTOMIZED_TOOL";
            } else {
                toolType = "UNKNOWN";
            }
        }

        return new GoogleVertexToolDescriptor(
                name,
                optText(node, "displayName"),
                toolType,
                optText(node, "description"),
                null, // tools don't have a direct endpoint
                agentResourceName
        );
    }

    private GoogleVertexToolDescriptor parseWebhookNode(JsonNode node, String agentResourceName) {
        String name = optText(node, "name");
        if (name == null || name.isEmpty()) {
            return null;
        }

        String endpoint = null;
        if (node.has("genericWebService")) {
            endpoint = optText(node.get("genericWebService"), "uri");
        } else if (node.has("serviceDirectory")) {
            endpoint = optText(node.get("serviceDirectory"), "service");
        }

        return new GoogleVertexToolDescriptor(
                name,
                optText(node, "displayName"),
                "WEBHOOK",
                null,
                endpoint,
                agentResourceName
        );
    }

    private GoogleVertexDataStoreDescriptor parseDataStoreNode(JsonNode node,
                                                         String agentResourceName) {
        String name = optText(node, "name");
        if (name == null || name.isEmpty()) {
            return null;
        }

        return new GoogleVertexDataStoreDescriptor(
                name,
                optText(node, "displayName"),
                optText(node, "dataStoreType"),
                null, // status not in list response
                agentResourceName
        );
    }

    private String optText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }

    // ---------------------------------------------------------------------
    // Cache management
    // ---------------------------------------------------------------------

    private boolean isCacheValid() {
        return Instant.now().isBefore(cacheLoadedAt.plusSeconds(CACHE_TTL_SECONDS));
    }

    /**
     * Invalidate all caches. Called between reconciliation runs if needed.
     */
    public void invalidateCache() {
        toolsCache.clear();
        dataStoreCache.clear();
        cacheLoadedAt = Instant.EPOCH;
    }

    // ---------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------

    public String getProjectId() {
        return projectId;
    }

    public String getLocation() {
        return location;
    }

    public String getAgentApiFlavor() {
        return agentApiFlavor;
    }

    @Override
    public void close() throws IOException {
        // HttpClient does not require explicit close in Java 11+
    }
}
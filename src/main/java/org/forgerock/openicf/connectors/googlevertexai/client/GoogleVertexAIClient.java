// src/main/java/org/forgerock/openicf/connectors/googlevertexai/client/GoogleVertexAIClient.java
package org.forgerock.openicf.connectors.googlevertexai.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.identityconnectors.common.logging.Log;

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

    private static final Log LOG = Log.getLog(GoogleVertexAIClient.class);

    // ---------------------------------------------------------------------
    // Core context
    // ---------------------------------------------------------------------
    private final String projectId;
    private final String location;
    private final String agentApiFlavor; // "dialogflowcx" or "vertexai"
    private final boolean useWorkloadIdentity;
    private final String serviceAccountKeyJson; // plain-text JSON key
    // OPENICF-4001: Organization ID for org-wide service account discovery
    private final String organizationId;
    // OPENICF-4003: Enable org-wide agent scanning via Cloud Asset API
    private final boolean useCloudAssetApi;

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
        this(projectId, location, agentApiFlavor, true, null, null, false);
    }

    /**
     * Constructor for service account key authentication.
     */
    public GoogleVertexAIClient(String projectId,
                                String location,
                                String agentApiFlavor,
                                String serviceAccountKeyJson) {
        this(projectId, location, agentApiFlavor, false, serviceAccountKeyJson, null, false);
    }

    /**
     * Constructor for service account key authentication with org-wide scanning (OPENICF-4001).
     *
     * @param organizationId GCP organization ID (10-digit numeric) for org-wide SA discovery,
     *                       or null for project-scoped discovery only
     */
    public GoogleVertexAIClient(String projectId,
                                String location,
                                String agentApiFlavor,
                                String serviceAccountKeyJson,
                                String organizationId) {
        this(projectId, location, agentApiFlavor, false, serviceAccountKeyJson, organizationId, false);
    }

    /**
     * Constructor for service account key authentication with org-wide agent scanning (OPENICF-4003).
     *
     * @param organizationId   GCP organization ID (10-digit numeric) for org-wide discovery
     * @param useCloudAssetApi if true, use Cloud Asset API for org-wide agent discovery
     */
    public GoogleVertexAIClient(String projectId,
                                String location,
                                String agentApiFlavor,
                                String serviceAccountKeyJson,
                                String organizationId,
                                boolean useCloudAssetApi) {
        this(projectId, location, agentApiFlavor, false, serviceAccountKeyJson, organizationId, useCloudAssetApi);
    }

    private GoogleVertexAIClient(String projectId,
                                 String location,
                                 String agentApiFlavor,
                                 boolean useWorkloadIdentity,
                                 String serviceAccountKeyJson,
                                 String organizationId,
                                 boolean useCloudAssetApi) {
        this.projectId = projectId;
        this.location = location;
        this.agentApiFlavor = agentApiFlavor != null ? agentApiFlavor : FLAVOR_DIALOGFLOW_CX;
        this.useWorkloadIdentity = useWorkloadIdentity;
        this.serviceAccountKeyJson = serviceAccountKeyJson;
        this.organizationId = organizationId;
        this.useCloudAssetApi = useCloudAssetApi;
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
     *
     * <p>OPENICF-4003: When useCloudAssetApi is enabled and organizationId is set,
     * discovers agents org-wide via Cloud Asset API, then fetches details per-agent.
     */
    public List<GoogleVertexAgentDescriptor> listAgents() {
        // OPENICF-4003: Use Cloud Asset API for org-wide discovery
        if (useCloudAssetApi && organizationId != null && !organizationId.isEmpty()) {
            return listAgentsViaCloudAsset();
        }

        // Default: project-scoped discovery via Dialogflow CX or Vertex AI API
        List<GoogleVertexAgentDescriptor> all = new ArrayList<>();
        String pageToken = null;

        do {
            ListAgentsPage page = listAgentsPaginated(50, pageToken);
            all.addAll(page.getAgents());
            pageToken = page.getNextPageToken();
        } while (pageToken != null);

        return all;
    }

    /**
     * Org-wide agent discovery via Cloud Asset API (OPENICF-4003).
     *
     * <p>Discovers both Dialogflow CX agents and Vertex AI Agent Engine agents
     * across all projects in the organization, then fetches full details per-agent.
     *
     * <p>Cloud Asset API query:
     * <pre>
     * GET https://cloudasset.googleapis.com/v1/organizations/{orgId}:searchAllResources
     *     ?assetTypes=dialogflow.googleapis.com/Agent
     *     &assetTypes=aiplatform.googleapis.com/ReasoningEngine
     * </pre>
     */
    private List<GoogleVertexAgentDescriptor> listAgentsViaCloudAsset() {
        List<GoogleVertexAgentDescriptor> all = new ArrayList<>();
        String pageToken = null;

        do {
            StringBuilder url = new StringBuilder("https://cloudasset.googleapis.com/v1/organizations/")
                    .append(organizationId)
                    .append(":searchAllResources")
                    .append("?assetTypes=dialogflow.googleapis.com/Agent")
                    .append("&assetTypes=aiplatform.googleapis.com/ReasoningEngine")
                    .append("&pageSize=100");

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
                    LOG.error("listAgentsViaCloudAsset failed: HTTP {0} body={1}",
                            response.statusCode(), response.body());
                    throw new RuntimeException("listAgentsViaCloudAsset failed: HTTP "
                            + response.statusCode() + " body=" + response.body());
                }

                JsonNode root = objectMapper.readTree(response.body());

                if (root.has("results") && root.get("results").isArray()) {
                    for (JsonNode node : root.get("results")) {
                        GoogleVertexAgentDescriptor agent = parseCloudAssetAgentAndFetchDetails(node);
                        if (agent != null) {
                            all.add(agent);
                        }
                    }
                }

                pageToken = optText(root, "nextPageToken");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Error calling Cloud Asset API for agents", e);
            }
        } while (pageToken != null);

        LOG.ok("listAgentsViaCloudAsset found {0} agents across organization {1}",
                all.size(), organizationId);
        return all;
    }

    /**
     * Parse agent from Cloud Asset API response and fetch full details.
     *
     * <p>Cloud Asset API returns minimal info:
     * <ul>
     *   <li>name: //dialogflow.googleapis.com/projects/{p}/locations/{l}/agents/{id}</li>
     *   <li>name: //aiplatform.googleapis.com/projects/{p}/locations/{l}/reasoningEngines/{id}</li>
     *   <li>displayName, description, project, location</li>
     * </ul>
     *
     * <p>We extract the resource name and location, then fetch full details via the
     * appropriate regional API.
     */
    private GoogleVertexAgentDescriptor parseCloudAssetAgentAndFetchDetails(JsonNode node) {
        if (node == null) {
            return null;
        }

        String assetName = optText(node, "name");
        if (assetName == null || assetName.isEmpty()) {
            return null;
        }

        // Determine asset type and extract resource name
        // //dialogflow.googleapis.com/projects/{p}/locations/{l}/agents/{id}
        // //aiplatform.googleapis.com/projects/{p}/locations/{l}/reasoningEngines/{id}
        String resourceName;
        boolean isReasoningEngine;

        if (assetName.startsWith("//dialogflow.googleapis.com/")) {
            resourceName = assetName.substring("//dialogflow.googleapis.com/".length());
            isReasoningEngine = false;
        } else if (assetName.startsWith("//aiplatform.googleapis.com/")) {
            resourceName = assetName.substring("//aiplatform.googleapis.com/".length());
            isReasoningEngine = true;
        } else {
            LOG.warn("Unknown asset type in Cloud Asset response: {0}", assetName);
            return null;
        }

        // Extract location from resource name
        String agentLocation = extractLocationFromResourceName(resourceName);
        if (agentLocation == null) {
            LOG.warn("Could not extract location from resource name: {0}", resourceName);
            return null;
        }

        // Fetch full agent details via regional API
        try {
            return getAgentWithLocation(resourceName, agentLocation, isReasoningEngine);
        } catch (Exception e) {
            LOG.warn(e, "Failed to fetch details for agent {0}, using basic info", resourceName);
            // Return basic info from Cloud Asset response as fallback
            return new GoogleVertexAgentDescriptor(
                    resourceName,
                    optText(node, "displayName"),
                    optText(node, "description"),
                    null, null, null, null, null, null, null, null, null,
                    isReasoningEngine ? "unknown" : null,
                    null
            );
        }
    }

    /**
     * Extract location from a resource name.
     *
     * <p>Examples:
     * <ul>
     *   <li>projects/p1/locations/us-central1/agents/123 → us-central1</li>
     *   <li>projects/p1/locations/global/reasoningEngines/456 → global</li>
     * </ul>
     */
    private String extractLocationFromResourceName(String resourceName) {
        if (resourceName == null) {
            return null;
        }
        // Pattern: projects/{p}/locations/{location}/...
        int locIdx = resourceName.indexOf("/locations/");
        if (locIdx < 0) {
            return null;
        }
        int start = locIdx + "/locations/".length();
        int end = resourceName.indexOf('/', start);
        if (end < 0) {
            return resourceName.substring(start);
        }
        return resourceName.substring(start, end);
    }

    /**
     * Get agent details using explicit location for regional endpoint routing.
     *
     * @param resourceName      full resource name (projects/{p}/locations/{l}/agents/{id})
     * @param agentLocation     the location extracted from resourceName
     * @param isReasoningEngine true for Vertex AI Agent Engine, false for Dialogflow CX
     */
    private GoogleVertexAgentDescriptor getAgentWithLocation(String resourceName,
                                                             String agentLocation,
                                                             boolean isReasoningEngine) {
        // Build regional base URL
        String baseUrl;
        if (isReasoningEngine) {
            baseUrl = "https://" + agentLocation + "-aiplatform.googleapis.com/v1";
        } else {
            baseUrl = "https://" + agentLocation + "-dialogflow.googleapis.com/v3";
        }

        String url = baseUrl + "/" + resourceName;

        try {
            HttpRequest request = authedRequestBuilder(url)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                LOG.warn("Agent not found (may have been deleted): {0}", resourceName);
                return null;
            }
            if (response.statusCode() / 100 != 2) {
                LOG.warn("Failed to fetch agent {0}: HTTP {1}", resourceName, response.statusCode());
                return null;
            }

            JsonNode node = objectMapper.readTree(response.body());
            return isReasoningEngine
                    ? parseReasoningEngineNode(node)
                    : parseDialogflowAgentNode(node);
        } catch (IOException | InterruptedException e) {
            LOG.warn(e, "Error fetching agent details for {0}", resourceName);
            return null;
        }
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
     * OPENICF-4002: Improved error handling with status-specific logging.
     *
     * @param agentResourceName full resource name (e.g., projects/p/locations/l/agents/id)
     * @return list of IAM bindings, empty if no bindings or on permission/API errors
     */
    public List<GoogleVertexIamBindingDescriptor> getIamBindings(String agentResourceName) {
        String url = baseUrl() + "/" + agentResourceName + ":getIamPolicy";
        return fetchIamBindings(url, agentResourceName, "agent");
    }

    /**
     * Fetch IAM policy bindings for a service account (OPENICF-4002).
     *
     * <p>Discovers who can impersonate or manage the service account via roles like:
     * <ul>
     *   <li>roles/iam.serviceAccountUser — can run operations as the SA</li>
     *   <li>roles/iam.serviceAccountTokenCreator — can create tokens for the SA</li>
     *   <li>roles/iam.serviceAccountAdmin — full control over the SA</li>
     * </ul>
     *
     * @param saResourceName full SA resource name (e.g., projects/p/serviceAccounts/email)
     * @return list of IAM bindings, empty if no bindings or on permission/API errors
     */
    public List<GoogleVertexIamBindingDescriptor> getServiceAccountIamBindings(String saResourceName) {
        // IAM API endpoint: POST https://iam.googleapis.com/v1/{resource}:getIamPolicy
        String url = "https://iam.googleapis.com/v1/" + saResourceName + ":getIamPolicy";
        return fetchIamBindings(url, saResourceName, "serviceAccount");
    }

    /**
     * Common IAM policy fetch logic with improved error handling (OPENICF-4002).
     *
     * @param url          full URL including :getIamPolicy suffix
     * @param resourceName resource name for logging and binding ID construction
     * @param resourceType "agent" or "serviceAccount" for logging context
     * @return list of IAM bindings, empty on errors
     */
    private List<GoogleVertexIamBindingDescriptor> fetchIamBindings(String url,
                                                                    String resourceName,
                                                                    String resourceType) {
        try {
            HttpRequest request = authedRequestBuilder(url)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();

            // OPENICF-4002: Status-specific error handling
            if (status / 100 != 2) {
                logIamPolicyError(status, resourceName, resourceType, response.body());
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
                            String id = resourceName + ":" + role + ":" + member;

                            result.add(new GoogleVertexIamBindingDescriptor(
                                    id, resourceName, role, member, memberType));
                        }
                    }
                }
            }

            if (!result.isEmpty()) {
                LOG.ok("Found {0} IAM bindings for {1} {2}", result.size(), resourceType, resourceName);
            }

            return result;
        } catch (IOException | InterruptedException e) {
            LOG.error(e, "Error fetching IAM policy for {0} {1}", resourceType, resourceName);
            return Collections.emptyList();
        }
    }

    /**
     * Log IAM policy fetch errors with appropriate severity (OPENICF-4002).
     */
    private void logIamPolicyError(int status, String resourceName, String resourceType, String body) {
        // Truncate body for logging
        String truncatedBody = body != null && body.length() > 200
                ? body.substring(0, 200) + "..."
                : body;

        if (status == 403) {
            // Permission denied — expected if caller lacks getIamPolicy permission
            LOG.info("Permission denied (403) fetching IAM policy for {0} {1}. " +
                            "Ensure caller has {2}.getIamPolicy permission.",
                    resourceType, resourceName,
                    "serviceAccount".equals(resourceType) ? "iam.serviceAccounts" : "aiplatform.reasoningEngines");
        } else if (status == 404) {
            // Resource not found — may have been deleted between list and getIamPolicy
            LOG.warn("Resource not found (404) when fetching IAM policy for {0} {1}",
                    resourceType, resourceName);
        } else if (status == 400) {
            // Bad request — API may not support resource-level IAM
            LOG.info("Bad request (400) fetching IAM policy for {0} {1}. " +
                            "Resource-level IAM may not be supported. Body: {2}",
                    resourceType, resourceName, truncatedBody);
        } else if (status >= 500) {
            // Server error — transient, log as error but continue
            LOG.error("Server error ({0}) fetching IAM policy for {1} {2}. Body: {3}",
                    status, resourceType, resourceName, truncatedBody);
        } else {
            // Other client errors
            LOG.warn("HTTP {0} fetching IAM policy for {1} {2}. Body: {3}",
                    status, resourceType, resourceName, truncatedBody);
        }
    }

    /**
     * List all IAM bindings across all agents and service accounts (OPENICF-4002).
     *
     * <p>When service account discovery is enabled, also fetches IAM bindings
     * for each service account to discover who can impersonate them.
     *
     * @param includeServiceAccounts if true, also fetch SA IAM bindings
     * @return combined list of agent and service account IAM bindings
     */
    public List<GoogleVertexIamBindingDescriptor> listAllIamBindings(boolean includeServiceAccounts) {
        List<GoogleVertexIamBindingDescriptor> all = new ArrayList<>();

        // Agent IAM bindings
        for (GoogleVertexAgentDescriptor agent : listAgents()) {
            all.addAll(getIamBindings(agent.getResourceName()));
        }
        LOG.ok("Collected {0} IAM bindings from agents", all.size());

        // OPENICF-4002: Service account IAM bindings
        if (includeServiceAccounts) {
            int agentBindingCount = all.size();
            for (GoogleVertexServiceAccountDescriptor sa : listServiceAccounts()) {
                all.addAll(getServiceAccountIamBindings(sa.getName()));
            }
            LOG.ok("Collected {0} IAM bindings from service accounts",
                    all.size() - agentBindingCount);
        }

        return all;
    }

    /**
     * List all IAM bindings across all agents.
     * @deprecated Use {@link #listAllIamBindings(boolean)} instead
     */
    @Deprecated
    public List<GoogleVertexIamBindingDescriptor> listAllIamBindings() {
        return listAllIamBindings(false);
    }

    // ---------------------------------------------------------------------
    // Service account listing (OPENICF-4001)
    // ---------------------------------------------------------------------

    /**
     * List service accounts. Uses Cloud Asset API if organizationId is set,
     * otherwise falls back to IAM API for project-scoped discovery.
     */
    public List<GoogleVertexServiceAccountDescriptor> listServiceAccounts() {
        if (organizationId != null && !organizationId.isEmpty()) {
            return listServiceAccountsViaCloudAsset();
        }
        return listServiceAccountsViaIam();
    }

    /**
     * Org-wide discovery via Cloud Asset API.
     * GET https://cloudasset.googleapis.com/v1/organizations/{orgId}:searchAllResources
     *     ?assetTypes=iam.googleapis.com/ServiceAccount
     */
    private List<GoogleVertexServiceAccountDescriptor> listServiceAccountsViaCloudAsset() {
        List<GoogleVertexServiceAccountDescriptor> all = new ArrayList<>();
        String pageToken = null;

        do {
            StringBuilder url = new StringBuilder("https://cloudasset.googleapis.com/v1/organizations/")
                    .append(organizationId)
                    .append(":searchAllResources")
                    .append("?assetTypes=iam.googleapis.com/ServiceAccount")
                    .append("&pageSize=100");

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
                    throw new RuntimeException("listServiceAccountsViaCloudAsset failed: HTTP "
                            + response.statusCode() + " body=" + response.body());
                }

                JsonNode root = objectMapper.readTree(response.body());

                if (root.has("results") && root.get("results").isArray()) {
                    for (JsonNode node : root.get("results")) {
                        GoogleVertexServiceAccountDescriptor sa = parseCloudAssetServiceAccountNode(node);
                        if (sa != null) {
                            all.add(sa);
                        }
                    }
                }

                pageToken = optText(root, "nextPageToken");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Error calling Cloud Asset API for service accounts", e);
            }
        } while (pageToken != null);

        return all;
    }

    /**
     * Project-scoped discovery via IAM API.
     * GET https://iam.googleapis.com/v1/projects/{projectId}/serviceAccounts
     */
    private List<GoogleVertexServiceAccountDescriptor> listServiceAccountsViaIam() {
        List<GoogleVertexServiceAccountDescriptor> all = new ArrayList<>();
        String pageToken = null;

        do {
            StringBuilder url = new StringBuilder("https://iam.googleapis.com/v1/projects/")
                    .append(projectId)
                    .append("/serviceAccounts")
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
                    throw new RuntimeException("listServiceAccountsViaIam failed: HTTP "
                            + response.statusCode() + " body=" + response.body());
                }

                JsonNode root = objectMapper.readTree(response.body());

                if (root.has("accounts") && root.get("accounts").isArray()) {
                    for (JsonNode node : root.get("accounts")) {
                        GoogleVertexServiceAccountDescriptor sa = parseIamServiceAccountNode(node);
                        if (sa != null) {
                            all.add(sa);
                        }
                    }
                }

                pageToken = optText(root, "nextPageToken");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Error calling IAM API for service accounts", e);
            }
        } while (pageToken != null);

        return all;
    }

    /**
     * Get single service account details.
     * GET https://iam.googleapis.com/v1/{saResourceName}
     */
    public GoogleVertexServiceAccountDescriptor getServiceAccount(String saResourceName) {
        String url = "https://iam.googleapis.com/v1/" + saResourceName;

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
                throw new RuntimeException("getServiceAccount failed: HTTP "
                        + response.statusCode() + " body=" + response.body());
            }

            JsonNode node = objectMapper.readTree(response.body());
            return parseIamServiceAccountNode(node);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error calling getServiceAccount", e);
        }
    }

    /**
     * List keys for a service account.
     * GET https://iam.googleapis.com/v1/{saResourceName}/keys
     *
     * @return JSON array string of key metadata
     */
    public String listServiceAccountKeysJson(String saResourceName) {
        String url = "https://iam.googleapis.com/v1/" + saResourceName + "/keys";

        try {
            HttpRequest request = authedRequestBuilder(url)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                // May not have permission — return empty array
                return "[]";
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root.has("keys") && root.get("keys").isArray()) {
                // Build a simplified JSON array with key metadata
                List<Map<String, Object>> keys = new ArrayList<>();
                for (JsonNode keyNode : root.get("keys")) {
                    Map<String, Object> keyMap = new LinkedHashMap<>();
                    keyMap.put("keyId", extractKeyIdFromName(optText(keyNode, "name")));
                    keyMap.put("keyAlgorithm", optText(keyNode, "keyAlgorithm"));
                    keyMap.put("keyOrigin", optText(keyNode, "keyOrigin"));
                    keyMap.put("keyType", optText(keyNode, "keyType"));
                    keyMap.put("createTime", optText(keyNode, "validAfterTime"));
                    keyMap.put("expireTime", optText(keyNode, "validBeforeTime"));
                    keyMap.put("disabled", keyNode.has("disabled") && keyNode.get("disabled").asBoolean());
                    keys.add(keyMap);
                }
                return objectMapper.writeValueAsString(keys);
            }
            return "[]";
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error listing service account keys", e);
        }
    }

    /**
     * Extract key ID from full key resource name.
     * e.g. "projects/p1/serviceAccounts/sa@p1.iam.gserviceaccount.com/keys/abc123" → "abc123"
     */
    private String extractKeyIdFromName(String keyName) {
        if (keyName == null) {
            return null;
        }
        int idx = keyName.lastIndexOf('/');
        return idx >= 0 ? keyName.substring(idx + 1) : keyName;
    }

    /**
     * Find agents that use the given service account (lazy-load).
     * Scans all agents and filters by serviceAccount field.
     *
     * <p>Only Vertex AI Agent Engine agents have a serviceAccount field;
     * Dialogflow CX agents do not expose this.
     */
    public List<String> getLinkedAgentsForServiceAccount(String saEmail) {
        List<String> linkedAgents = new ArrayList<>();
        for (GoogleVertexAgentDescriptor agent : listAgents()) {
            if (saEmail != null && saEmail.equals(agent.getServiceAccount())) {
                linkedAgents.add(agent.getResourceName());
            }
        }
        return linkedAgents;
    }

    // ---------------------------------------------------------------------
    // Guardrail extraction (OPENICF-4004)
    // ---------------------------------------------------------------------

    /**
     * Extract guardrails from all agents that have safety settings configured.
     * Returns one guardrail per agent (only for agents with non-empty safetySettings).
     */
    public List<GoogleVertexGuardrailDescriptor> listAllGuardrails() {
        List<GoogleVertexGuardrailDescriptor> guardrails = new ArrayList<>();
        for (GoogleVertexAgentDescriptor agent : listAgents()) {
            GoogleVertexGuardrailDescriptor guardrail = parseGuardrailFromAgent(agent);
            if (guardrail != null && guardrail.hasConfiguration()) {
                guardrails.add(guardrail);
            }
        }
        return guardrails;
    }

    /**
     * Parse guardrail from an agent's safetySettingsJson.
     *
     * <p>Dialogflow CX safetySettings structure:
     * <pre>
     * {
     *   "defaultBannedPhrases": [{"text": "phrase1", "languageCode": "en"}, ...],
     *   "generativeSafetySettings": {
     *     "bannedPhrases": [{"text": "phrase2", "languageCode": "en"}, ...],
     *     "safetyEnforcement": "BLOCK_NONE|BLOCK_FEW|BLOCK_SOME|BLOCK_MOST"
     *   }
     * }
     * </pre>
     *
     * @param agent the agent descriptor with safetySettingsJson
     * @return parsed guardrail, or null if no safety settings
     */
    public GoogleVertexGuardrailDescriptor parseGuardrailFromAgent(GoogleVertexAgentDescriptor agent) {
        if (agent == null) {
            return null;
        }

        String safetyJson = agent.getSafetySettingsJson();
        if (safetyJson == null || safetyJson.isEmpty()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(safetyJson);

            String safetyEnforcement = null;
            List<String> bannedPhrases = new ArrayList<>();
            List<String> defaultBannedPhrases = new ArrayList<>();

            // Parse defaultBannedPhrases at root level
            if (root.has("defaultBannedPhrases") && root.get("defaultBannedPhrases").isArray()) {
                for (JsonNode phraseNode : root.get("defaultBannedPhrases")) {
                    String text = optText(phraseNode, "text");
                    if (text != null && !text.isEmpty()) {
                        defaultBannedPhrases.add(text);
                    }
                }
            }

            // Parse generativeSafetySettings
            if (root.has("generativeSafetySettings")) {
                JsonNode genSafety = root.get("generativeSafetySettings");

                safetyEnforcement = optText(genSafety, "safetyEnforcement");

                if (genSafety.has("bannedPhrases") && genSafety.get("bannedPhrases").isArray()) {
                    for (JsonNode phraseNode : genSafety.get("bannedPhrases")) {
                        String text = optText(phraseNode, "text");
                        if (text != null && !text.isEmpty()) {
                            bannedPhrases.add(text);
                        }
                    }
                }
            }

            return new GoogleVertexGuardrailDescriptor(
                    agent.getResourceName(),
                    safetyEnforcement,
                    bannedPhrases,
                    defaultBannedPhrases,
                    safetyJson
            );
        } catch (IOException e) {
            // Log and return null if JSON parsing fails
            return null;
        }
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

    /**
     * Parse service account from IAM API response (OPENICF-4001).
     *
     * IAM API returns:
     * - name: projects/{project}/serviceAccounts/{email}
     * - projectId
     * - uniqueId
     * - email
     * - displayName
     * - description
     * - disabled
     * - oauth2ClientId
     */
    private GoogleVertexServiceAccountDescriptor parseIamServiceAccountNode(JsonNode node) {
        if (node == null) {
            return null;
        }

        String name = optText(node, "name");
        if (name == null || name.isEmpty()) {
            return null;
        }

        return new GoogleVertexServiceAccountDescriptor(
                name,
                optText(node, "email"),
                optText(node, "displayName"),
                optText(node, "description"),
                optText(node, "projectId"),
                optText(node, "uniqueId"),
                node.has("disabled") && node.get("disabled").asBoolean(),
                null, // createTime not in IAM list response
                optText(node, "oauth2ClientId"),
                null, // linkedAgentIds populated lazily
                null, // keysJson populated separately
                0     // keyCount populated separately
        );
    }

    /**
     * Parse service account from Cloud Asset API response (OPENICF-4001).
     *
     * Cloud Asset API returns:
     * - name: //iam.googleapis.com/projects/{project}/serviceAccounts/{email}
     * - displayName
     * - description
     * - project (project number, not ID)
     * - additionalAttributes may contain more fields
     */
    private GoogleVertexServiceAccountDescriptor parseCloudAssetServiceAccountNode(JsonNode node) {
        if (node == null) {
            return null;
        }

        String assetName = optText(node, "name");
        if (assetName == null || assetName.isEmpty()) {
            return null;
        }

        // Convert Cloud Asset name to IAM resource name
        // //iam.googleapis.com/projects/{project}/serviceAccounts/{email}
        // → projects/{project}/serviceAccounts/{email}
        String iamName = assetName.startsWith("//iam.googleapis.com/")
                ? assetName.substring("//iam.googleapis.com/".length())
                : assetName;

        // Extract email from the resource name
        String email = null;
        int saIdx = iamName.lastIndexOf("/serviceAccounts/");
        if (saIdx >= 0) {
            email = iamName.substring(saIdx + "/serviceAccounts/".length());
        }

        // Extract project from the resource name
        String saProjectId = null;
        if (iamName.startsWith("projects/")) {
            int endIdx = iamName.indexOf("/", "projects/".length());
            if (endIdx > 0) {
                saProjectId = iamName.substring("projects/".length(), endIdx);
            }
        }

        return new GoogleVertexServiceAccountDescriptor(
                iamName,
                email,
                optText(node, "displayName"),
                optText(node, "description"),
                saProjectId,
                null, // uniqueId not in Cloud Asset response
                false, // disabled not in Cloud Asset response
                null, // createTime not in Cloud Asset response
                null, // oauth2ClientId not in Cloud Asset response
                null, // linkedAgentIds populated lazily
                null, // keysJson populated separately
                0     // keyCount populated separately
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

    /**
     * Get organization ID for org-wide scanning (OPENICF-4001).
     *
     * @return organization ID or null if project-scoped only
     */
    public String getOrganizationId() {
        return organizationId;
    }

    /**
     * Check if Cloud Asset API is enabled for org-wide agent discovery (OPENICF-4003).
     *
     * @return true if org-wide agent scanning is enabled
     */
    public boolean isUseCloudAssetApi() {
        return useCloudAssetApi;
    }

    @Override
    public void close() throws IOException {
        // HttpClient does not require explicit close in Java 11+
    }
}
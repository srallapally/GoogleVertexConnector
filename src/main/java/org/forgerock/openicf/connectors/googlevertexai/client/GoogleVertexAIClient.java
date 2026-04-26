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

import static org.forgerock.openicf.connectors.googlevertexai.utils.GoogleVertexAIConstants.FLAVOR_BOTH;
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
     * Constructor for workload identity authentication with org-wide scanning (BUG-2).
     */
    public GoogleVertexAIClient(String projectId,
                                String location,
                                String agentApiFlavor,
                                String organizationId,
                                boolean useCloudAssetApi) {
        this(projectId, location, agentApiFlavor, true, null, organizationId, useCloudAssetApi);
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

        // RFE-1: dual-flavor — discover CX agents + RE agents via direct API calls
        if (FLAVOR_BOTH.equals(agentApiFlavor)) {
            List<GoogleVertexAgentDescriptor> all = new ArrayList<>();
            all.addAll(listFlavorDirect(FLAVOR_DIALOGFLOW_CX));
            all.addAll(listFlavorDirect(FLAVOR_VERTEX_AI));
            return all;
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
     * RFE-1: Paginated agent list for one specific flavor, independent of
     * {@code agentApiFlavor}. Used by {@link #listAgents()} when flavor is
     * {@code both} to fetch CX agents and RE agents in separate passes.
     */
    private List<GoogleVertexAgentDescriptor> listFlavorDirect(String flavor) {
        boolean isRe = FLAVOR_VERTEX_AI.equals(flavor);
        String baseUrl = isRe
                ? "https://" + location + "-aiplatform.googleapis.com/v1"
                : "https://" + location + "-dialogflow.googleapis.com/v3";
        String resourceSegment = isRe ? "reasoningEngines" : "agents";
        String listKey = isRe ? "reasoningEngines" : "agents";

        List<GoogleVertexAgentDescriptor> all = new ArrayList<>();
        String pageToken = null;

        do {
            StringBuilder url = new StringBuilder(baseUrl)
                    .append("/").append(agentsParent()).append("/").append(resourceSegment)
                    .append("?pageSize=50");
            if (pageToken != null && !pageToken.isEmpty()) {
                url.append("&pageToken=").append(urlEncode(pageToken));
            }

            try {
                HttpRequest request = authedRequestBuilder(url.toString()).GET().build();
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() / 100 != 2) {
                    throw new RuntimeException("listFlavorDirect(" + flavor + ") failed: HTTP "
                            + response.statusCode() + " body=" + response.body());
                }

                JsonNode root = objectMapper.readTree(response.body());
                if (root.has(listKey) && root.get(listKey).isArray()) {
                    for (JsonNode node : root.get(listKey)) {
                        GoogleVertexAgentDescriptor agent = isRe
                                ? parseReasoningEngineNode(node)
                                : parseDialogflowAgentNode(node);
                        if (agent != null) {
                            all.add(agent);
                        }
                    }
                }
                pageToken = optText(root, "nextPageToken");
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Error in listFlavorDirect(" + flavor + ")", e);
            }
        } while (pageToken != null);

        LOG.ok("listFlavorDirect({0}) found {1} agents", flavor, all.size());
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
                    null, null, null, null, null, null, null, null, null, null,
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
        cacheLoadedAt = Instant.now(); // BUG-1: was never set; cache was always invalid
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
        cacheLoadedAt = Instant.now(); // BUG-1: was never set; cache was always invalid
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
    // GCS inventory reads (OPENICF-4007)
    //
    // Identity bindings, service accounts, and tool credentials are now
    // collected by the offline Python job and written to GCS. The connector
    // reads those artifacts here via unauthenticated HTTPS GET — the
    // pre-signed URL is self-authenticating, so no Authorization header
    // is sent. On any HTTP error or I/O failure, ConnectorException is
    // thrown immediately; there is no silent empty fallback.
    // ---------------------------------------------------------------------

    /**
     * Fetch identity-bindings.json from GCS.
     *
     * @param gcsIdentityBindingsUrl full pre-signed GCS URL for identity-bindings.json
     * @return parsed JSON root node of the artifact
     * @throws org.identityconnectors.framework.common.exceptions.ConnectorException on HTTP non-2xx or I/O failure
     */
    public JsonNode fetchGcsIdentityBindings(String gcsIdentityBindingsUrl) {
        // OPENICF-4016: full pre-signed URL passed directly — no path appending
        return fetchGcsArtifact(gcsIdentityBindingsUrl, "identity-bindings.json");
    }

    /**
     * Fetch service-accounts.json from GCS.
     *
     * @param gcsServiceAccountsUrl full pre-signed GCS URL for service-accounts.json
     * @return parsed JSON root node of the artifact
     * @throws org.identityconnectors.framework.common.exceptions.ConnectorException on HTTP non-2xx or I/O failure
     */
    public JsonNode fetchGcsServiceAccounts(String gcsServiceAccountsUrl) {
        // OPENICF-4016: full pre-signed URL passed directly — no path appending
        return fetchGcsArtifact(gcsServiceAccountsUrl, "service-accounts.json");
    }

    /**
     * Fetch tool-credentials.json from GCS.
     *
     * @param gcsToolCredentialsUrl full pre-signed GCS URL for tool-credentials.json
     * @return parsed JSON root node of the artifact
     * @throws org.identityconnectors.framework.common.exceptions.ConnectorException on HTTP non-2xx or I/O failure
     */
    public JsonNode fetchGcsToolCredentials(String gcsToolCredentialsUrl) {
        // OPENICF-4016: full pre-signed URL passed directly — no path appending
        return fetchGcsArtifact(gcsToolCredentialsUrl, "tool-credentials.json");
    }

    /**
     * Common unauthenticated GET for a named GCS artifact.
     *
     * <p>No Authorization header is sent — the pre-signed URL carries all
     * credentials in its query parameters. Sending a Bearer token alongside
     * a SAS/pre-signed URL causes GCS to return 400 InvalidAuthenticationInfo,
     * matching the same pattern used in the Copilot connector.
     *
     * @param url          full pre-signed GCS URL for the artifact
     * @param artifactName file name for logging (e.g. "identity-bindings.json")
     * @return parsed JSON root node
     * @throws org.identityconnectors.framework.common.exceptions.ConnectorException on any failure
     */
    private JsonNode fetchGcsArtifact(String url, String artifactName) {
        LOG.ok("Fetching GCS artifact: {0}", url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new org.identityconnectors.framework.common.exceptions.ConnectorException(
                        "Failed to fetch GCS artifact " + artifactName
                                + ": HTTP " + response.statusCode()
                                + " body=" + response.body());
            }

            return objectMapper.readTree(response.body());
        } catch (org.identityconnectors.framework.common.exceptions.ConnectorException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new org.identityconnectors.framework.common.exceptions.ConnectorException(
                    "I/O error fetching GCS artifact " + artifactName + ": " + e.getMessage(), e);
        }
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
                optText(node, "startPlaybook"), // RFE-2
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

        // RFE-5: Read spec.effectiveIdentity (deploymentSpec.serviceAccount is not valid in v1 API)
        String serviceAccount = null;
        if (node.has("spec")) {
            serviceAccount = optText(node.get("spec"), "effectiveIdentity");
        }

        return new GoogleVertexAgentDescriptor(
                name,
                optText(node, "displayName"),
                optText(node, "description"),
                null, // no defaultLanguageCode
                null, // no timeZone
                null, // no startFlow
                null, // no startPlaybook
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
                agentResourceName,
                "NONE", // OPENICF-4011: CX tools have no auth config at the tool level
                null
        );
    }

    private GoogleVertexToolDescriptor parseWebhookNode(JsonNode node, String agentResourceName) {
        String name = optText(node, "name");
        if (name == null || name.isEmpty()) {
            return null;
        }

        String endpoint = null;
        // OPENICF-4011: derive authType and credentialRef from webhook auth configuration
        String authType = "NONE";
        String credentialRef = null;

        if (node.has("genericWebService")) {
            JsonNode gws = node.get("genericWebService");
            endpoint = optText(gws, "uri");
            if (optText(gws, "serviceAccount") != null) {
                // Google-generated OIDC token for the specified SA
                authType = "SERVICE_ACCOUNT";
                credentialRef = optText(gws, "serviceAccount");
            } else if (gws.has("oauthConfig")) {
                authType = "OAUTH";
            } else if (gws.has("requestHeaders")
                    && gws.get("requestHeaders").isObject()
                    && gws.get("requestHeaders").size() > 0) {
                authType = "API_KEY";
            }
        } else if (node.has("serviceDirectory")) {
            // Service Directory webhooks use GCP service identity
            endpoint = optText(node.get("serviceDirectory"), "service");
            authType = "SERVICE_ACCOUNT";
        }

        return new GoogleVertexToolDescriptor(
                name,
                optText(node, "displayName"),
                "WEBHOOK",
                null,
                endpoint,
                agentResourceName,
                authType,
                credentialRef
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
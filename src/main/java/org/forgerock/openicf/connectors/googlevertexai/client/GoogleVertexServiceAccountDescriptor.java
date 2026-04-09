// src/main/java/org/forgerock/openicf/connectors/googlevertexai/client/GoogleVertexServiceAccountDescriptor.java
package org.forgerock.openicf.connectors.googlevertexai.client;

import java.util.Collections;
import java.util.List;

/**
 * Describes a GCP service account discovered via IAM API or Cloud Asset API.
 *
 * <p>Used to correlate service accounts with AI agents that use them
 * (via deploymentSpec.serviceAccount on Vertex AI Agent Engine agents).
 *
 * @since OPENICF-4001
 */
public class GoogleVertexServiceAccountDescriptor {

    private final String name;              // projects/{project}/serviceAccounts/{email}
    private final String email;             // sa@project.iam.gserviceaccount.com
    private final String displayName;
    private final String description;
    private final String projectId;
    private final String uniqueId;          // numeric ID
    private final boolean disabled;
    private final String createTime;        // RFC-3339
    private final String oauth2ClientId;

    // Relationship: which agents use this SA? (lazy-loaded)
    private final List<String> linkedAgentIds;

    // Service account keys (OPENICF-4001)
    private final String keysJson;          // JSON array of key metadata
    private final int keyCount;

    public GoogleVertexServiceAccountDescriptor(String name,
                                                String email,
                                                String displayName,
                                                String description,
                                                String projectId,
                                                String uniqueId,
                                                boolean disabled,
                                                String createTime,
                                                String oauth2ClientId,
                                                List<String> linkedAgentIds,
                                                String keysJson,
                                                int keyCount) {
        this.name = name;
        this.email = email;
        this.displayName = displayName;
        this.description = description;
        this.projectId = projectId;
        this.uniqueId = uniqueId;
        this.disabled = disabled;
        this.createTime = createTime;
        this.oauth2ClientId = oauth2ClientId;
        this.linkedAgentIds = linkedAgentIds != null ? linkedAgentIds : Collections.emptyList();
        this.keysJson = keysJson;
        this.keyCount = keyCount;
    }

    /**
     * Full resource name: projects/{project}/serviceAccounts/{email}
     * Used as __UID__.
     */
    public String getName() {
        return name;
    }

    /**
     * Service account email address.
     * Used as __NAME__.
     */
    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getProjectId() {
        return projectId;
    }

    /**
     * Numeric unique ID assigned by GCP.
     */
    public String getUniqueId() {
        return uniqueId;
    }

    public boolean isDisabled() {
        return disabled;
    }

    /**
     * RFC-3339 timestamp of when the service account was created.
     */
    public String getCreateTime() {
        return createTime;
    }

    public String getOauth2ClientId() {
        return oauth2ClientId;
    }

    /**
     * List of agent resource names that use this service account.
     * Populated via lazy-load when requested.
     */
    public List<String> getLinkedAgentIds() {
        return linkedAgentIds;
    }

    /**
     * JSON array containing metadata for each key attached to this service account.
     * Each key object includes: keyId, keyAlgorithm, keyOrigin, keyType, createTime, expireTime, disabled.
     */
    public String getKeysJson() {
        return keysJson;
    }

    /**
     * Number of keys attached to this service account.
     */
    public int getKeyCount() {
        return keyCount;
    }
}
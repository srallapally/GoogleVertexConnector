package org.forgerock.openicf.connectors.googlevertexai;

import org.forgerock.openicf.connectors.googlevertexai.client.GoogleVertexAIClient;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;

import java.io.Closeable;
import java.io.IOException;

/**
 * Manages the lifecycle of the GoogleVertexAIClient.
 */
public class GoogleVertexAIConnection implements Closeable {

    private static final Log LOG = Log.getLog(GoogleVertexAIConnection.class);

    private final GoogleVertexAIConfiguration configuration;
    private GoogleVertexAIClient client;

    public GoogleVertexAIConnection(GoogleVertexAIConfiguration configuration) {
        this.configuration = configuration;
        this.client = createClient(configuration);
    }

    private GoogleVertexAIClient createClient(GoogleVertexAIConfiguration config) {
        String projectId = config.getProjectId();
        String location = config.getLocation();
        String flavor = config.getAgentApiFlavor();

        if (config.isUseWorkloadIdentity()) {
            return new GoogleVertexAIClient(projectId, location, flavor);
        } else {
            String keyJson = toPlainString(config.getServiceAccountKeyJson());
            return new GoogleVertexAIClient(projectId, location, flavor, keyJson);
        }
    }

    private String toPlainString(GuardedString guarded) {
        if (guarded == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        guarded.access(chars -> sb.append(chars));
        return sb.toString();
    }

    public GoogleVertexAIClient getClient() {
        return client;
    }

    public GoogleVertexAIConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Connectivity test — list agents with a small page size.
     */
    public void test() {
        LOG.ok("Testing GoogleVertexAIConnection...");
        client.listAgentsPaginated(1, null);
        LOG.ok("GoogleVertexAIConnection test completed successfully.");
    }

    @Override
    public void close() throws IOException {
        LOG.ok("Closing GoogleVertexAIConnection...");
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOG.warn(e, "Error while closing GoogleVertexAIClient");
            } finally {
                client = null;
            }
        }
    }
}
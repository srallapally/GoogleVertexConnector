package org.forgerock.openicf.connectors.googlevertexai;

import org.forgerock.openicf.connectors.googlevertexai.operations.GoogleVertexAICrudService;
import org.forgerock.openicf.connectors.googlevertexai.utils.GoogleVertexAIConstants;
import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.SearchResultsHandler;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.TestOp;

/**
 * OpenICF connector for Google Vertex AI (Dialogflow CX) Agents.
 *
 * Read-only for v1 — focuses on discovery of agents, tools, data stores,
 * and (opt-in) IAM identity bindings.
 */
@ConnectorClass(
        configurationClass = GoogleVertexAIConfiguration.class,
        displayNameKey = "googlevertexai.connector.display"
)
public class GoogleVertexAIConnector implements
        Connector,
        SearchOp<Filter>,
        SchemaOp,
        TestOp {

    private static final Log LOG = Log.getLog(GoogleVertexAIConnector.class);

    private GoogleVertexAIConfiguration configuration;
    private GoogleVertexAIConnection connection;
    private GoogleVertexAICrudService crudService;

    // ---------------------------------------------------------------------
    // Connector lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void init(Configuration configuration) {
        LOG.ok("Initializing GoogleVertexAIConnector...");

        if (!(configuration instanceof GoogleVertexAIConfiguration)) {
            throw new IllegalArgumentException(
                    "Configuration must be an instance of GoogleVertexAIConfiguration");
        }

        this.configuration = (GoogleVertexAIConfiguration) configuration;
        this.configuration.validate();

        this.connection = new GoogleVertexAIConnection(this.configuration);
        this.crudService = new GoogleVertexAICrudService(this.connection);

        LOG.ok("GoogleVertexAIConnector initialized.");
    }

    @Override
    public void dispose() {
        LOG.ok("Disposing GoogleVertexAIConnector...");
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                LOG.warn(e, "Error while closing GoogleVertexAIConnection.");
            } finally {
                connection = null;
            }
        }
        crudService = null;
        configuration = null;
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    // ---------------------------------------------------------------------
    // TestOp
    // ---------------------------------------------------------------------

    @Override
    public void test() {
        LOG.ok("Executing TestOp on GoogleVertexAIConnector...");
        if (connection == null) {
            throw new IllegalStateException("Connection is not initialized.");
        }
        connection.test();
    }

    // ---------------------------------------------------------------------
    // SchemaOp
    // ---------------------------------------------------------------------

    @Override
    public Schema schema() {
        LOG.ok("Building schema for GoogleVertexAIConnector...");

        SchemaBuilder builder = new SchemaBuilder(GoogleVertexAIConnector.class);

        // -----------------------------------------------------------------
        // Agent
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder agentOc = new ObjectClassInfoBuilder();
        agentOc.setType(ObjectClass.ACCOUNT_NAME);

        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_AGENT_ID, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_DESCRIPTION, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_FOUNDATION_MODEL, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_DEFAULT_LANGUAGE_CODE, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_TIME_ZONE, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_START_FLOW, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_CREATED_AT, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_UPDATED_AT, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_SAFETY_SETTINGS, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_TOOLS_RAW, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.define(
                        GoogleVertexAIConstants.ATTR_AGENT_TOOL_IDS)
                .setType(String.class).setMultiValued(true).build());
        agentOc.addAttributeInfo(AttributeInfoBuilder.define(
                        GoogleVertexAIConstants.ATTR_AGENT_KNOWLEDGE_BASE_IDS)
                .setType(String.class).setMultiValued(true).build());
        agentOc.addAttributeInfo(AttributeInfoBuilder.define(
                        GoogleVertexAIConstants.ATTR_AGENT_GUARDRAIL_ID)
                .setType(String.class).build());

        // Vertex AI Agent Engine specific
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_AGENT_FRAMEWORK, String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_SERVICE_ACCOUNT, String.class));

        builder.defineObjectClass(agentOc.build());

        // -----------------------------------------------------------------
        // Tool
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder toolOc = new ObjectClassInfoBuilder();
        toolOc.setType(GoogleVertexAIConstants.OC_TOOL);

        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_AGENT_ID, String.class));
        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_DESCRIPTION, String.class));
        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_TOOL_TYPE, String.class));
        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_TOOL_ENDPOINT, String.class));

        builder.defineObjectClass(toolOc.build());

        // -----------------------------------------------------------------
        // Knowledge Base (Data Store)
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder kbOc = new ObjectClassInfoBuilder();
        kbOc.setType(GoogleVertexAIConstants.OC_KNOWLEDGE_BASE);

        kbOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM, String.class));
        kbOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_KNOWLEDGE_BASE_ID, String.class));
        kbOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_KNOWLEDGE_BASE_STATE, String.class));
        kbOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_DATA_STORE_TYPE, String.class));
        kbOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_AGENT_ID, String.class));

        builder.defineObjectClass(kbOc.build());

        // -----------------------------------------------------------------
        // Guardrail — stub
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder guardrailOc = new ObjectClassInfoBuilder();
        guardrailOc.setType(GoogleVertexAIConstants.OC_GUARDRAIL);
        guardrailOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM, String.class));
        builder.defineObjectClass(guardrailOc.build());

        // -----------------------------------------------------------------
        // Identity Binding
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder ibOc = new ObjectClassInfoBuilder();
        ibOc.setType(GoogleVertexAIConstants.OC_IDENTITY_BINDING);

        ibOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_PLATFORM, String.class));
        ibOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_AGENT_ID, String.class));
        ibOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_KIND, String.class));
        ibOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_PRINCIPAL, String.class));
        ibOc.addAttributeInfo(AttributeInfoBuilder.define(
                        GoogleVertexAIConstants.ATTR_PERMISSIONS)
                .setType(String.class).setMultiValued(true).build());
        ibOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_SCOPE, String.class));
        ibOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_IAM_ROLE, String.class));
        ibOc.addAttributeInfo(AttributeInfoBuilder.build(
                GoogleVertexAIConstants.ATTR_IAM_MEMBER, String.class));

        builder.defineObjectClass(ibOc.build());

        Schema schema = builder.build();
        LOG.ok("Schema built for GoogleVertexAIConnector.");
        return schema;
    }

    // ---------------------------------------------------------------------
    // SearchOp<Filter>
    // ---------------------------------------------------------------------

    @Override
    public FilterTranslator<Filter> createFilterTranslator(ObjectClass objectClass,
                                                           OperationOptions options) {
        return CollectionUtil::newList;
    }

    @Override
    public void executeQuery(ObjectClass objectClass,
                             Filter filter,
                             ResultsHandler handler,
                             OperationOptions options) {

        if (crudService == null) {
            throw new IllegalStateException("CRUD service is not initialized.");
        }

        if (options != null && options.getPageSize() != null && options.getPageSize() < 0) {
            throw new InvalidAttributeValueException("Page size should not be less than zero.");
        }

        LOG.ok("executeQuery called for objectClass {0}, filter {1}", objectClass, filter);

        // GET-by-UID
        Uid uid = getUidIfGetOperation(filter);
        if (uid != null) {
            handleGetByUid(objectClass, uid, handler, options);
            return;
        }

        // QUERY with paging
        int pageSize = (options != null && options.getPageSize() != null)
                ? options.getPageSize() : -1;

        int offset = 0;
        if (options != null && options.getPagedResultsCookie() != null) {
            try {
                offset = Integer.parseInt(options.getPagedResultsCookie());
            } catch (NumberFormatException e) {
                LOG.warn(e, "Invalid pagedResultsCookie: {0}", options.getPagedResultsCookie());
            }
        }

        PagingResultsHandler pagingHandler = new PagingResultsHandler(handler, offset, pageSize);
        String ocName = objectClass.getObjectClassValue();

        if (ObjectClass.ALL.equals(objectClass)
                || objectClass.ACCOUNT_NAME.equals(ocName)) {
            crudService.searchAgents(objectClass, filter, pagingHandler, options);
        } else if (GoogleVertexAIConstants.OC_TOOL.equals(ocName)) {
            crudService.searchTools(objectClass, filter, pagingHandler, options);
        } else if (GoogleVertexAIConstants.OC_KNOWLEDGE_BASE.equals(ocName)) {
            crudService.searchKnowledgeBases(objectClass, filter, pagingHandler, options);
        } else if (GoogleVertexAIConstants.OC_GUARDRAIL.equals(ocName)) {
            crudService.searchGuardrails(objectClass, filter, pagingHandler, options);
        } else if (GoogleVertexAIConstants.OC_IDENTITY_BINDING.equals(ocName)) {
            crudService.searchIdentityBindings(objectClass, filter, pagingHandler, options);
        } else {
            LOG.warn("Unsupported objectClass: {0}", ocName);
        }

        emitSearchResult(handler, pagingHandler, offset);
    }

    // ---------------------------------------------------------------------
    // GET-by-UID
    // ---------------------------------------------------------------------

    private void handleGetByUid(ObjectClass objectClass,
                                Uid uid,
                                ResultsHandler handler,
                                OperationOptions options) {
        ConnectorObject co = null;
        String ocName = objectClass.getObjectClassValue();

        if (objectClass.ACCOUNT_NAME.equals(ocName)) {
            co = crudService.getAgent(objectClass, uid, options);
        } else if (GoogleVertexAIConstants.OC_TOOL.equals(ocName)) {
            co = crudService.getTool(objectClass, uid, options);
        } else if (GoogleVertexAIConstants.OC_KNOWLEDGE_BASE.equals(ocName)) {
            co = crudService.getKnowledgeBase(objectClass, uid, options);
        } else if (GoogleVertexAIConstants.OC_GUARDRAIL.equals(ocName)) {
            co = crudService.getGuardrail(objectClass, uid, options);
        } else {
            throw new UnsupportedOperationException(
                    "Unsupported ObjectClass for GET: " + objectClass);
        }

        if (co != null) {
            handler.handle(co);
        }

        if (handler instanceof SearchResultsHandler) {
            ((SearchResultsHandler) handler).handleResult(new SearchResult(null, -1));
        }
    }

    private Uid getUidIfGetOperation(Filter query) {
        if (query instanceof EqualsFilter) {
            Attribute attr = ((EqualsFilter) query).getAttribute();
            if (attr != null && Uid.NAME.equals(attr.getName())
                    && attr.getValue() != null && !attr.getValue().isEmpty()) {
                Object value = attr.getValue().get(0);
                if (value instanceof String) {
                    return new Uid((String) value);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Paging
    // ---------------------------------------------------------------------

    private static final class PagingResultsHandler implements ResultsHandler {

        private final ResultsHandler delegate;
        private final int offset;
        private final int pageSize;

        private int seen = 0;
        private int returned = 0;

        PagingResultsHandler(ResultsHandler delegate, int offset, int pageSize) {
            this.delegate = delegate;
            this.offset = Math.max(0, offset);
            this.pageSize = pageSize;
        }

        @Override
        public boolean handle(ConnectorObject obj) {
            seen++;

            if (seen <= offset) {
                return true;
            }

            if (pageSize > 0 && returned >= pageSize) {
                return true;
            }

            boolean cont = delegate.handle(obj);
            if (cont) {
                returned++;
            }
            return cont;
        }

        int getSeen() {
            return seen;
        }

        int getReturned() {
            return returned;
        }
    }

    private void emitSearchResult(ResultsHandler handler,
                                  PagingResultsHandler pagingHandler,
                                  int offset) {
        if (!(handler instanceof SearchResultsHandler)) {
            return;
        }

        int totalCount = pagingHandler.getSeen();
        int returnedCount = pagingHandler.getReturned();

        String cookie = null;
        if (returnedCount > 0 && totalCount > offset + returnedCount) {
            cookie = String.valueOf(offset + returnedCount);
        }

        int remaining = (totalCount < 0 || returnedCount < 0)
                ? -1
                : Math.max(0, totalCount - (offset + returnedCount));

        ((SearchResultsHandler) handler).handleResult(new SearchResult(cookie, remaining));
    }
}
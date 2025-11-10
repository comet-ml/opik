package com.comet.opik.api.resources.v1.internal;

import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpansCountResponse;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceCountResponse;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.domain.DemoData;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Usage Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
@ExtendWith(DropwizardAppExtensionProvider.class)
class UsageResourceTest {

    public static final String USAGE_RESOURCE_URL_TEMPLATE = "%s/v1/internal/usage";
    public static final String TRACE_RESOURCE_URL_TEMPLATE = "%s/v1/private/traces";
    public static final String SPANS_RESOURCE_URL_TEMPLATE = "%s/v1/private/spans";
    private static final String EXPERIMENT_RESOURCE_URL_TEMPLATE = "%s/v1/private/experiments";
    private static final String DATASET_RESOURCE_URL_TEMPLATE = "%s/v1/private/datasets";

    private final String USER = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private TransactionTemplateAsync clickHouseTemplate;
    private TransactionTemplate mySqlTemplate;
    private ExperimentResourceClient experimentResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplateAsync clickHouseTemplate,
            TransactionTemplate mySqlTemplate) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.clickHouseTemplate = clickHouseTemplate;
        this.mySqlTemplate = mySqlTemplate;

        ClientSupportUtils.config(client);

        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Nested
    @DisplayName("Opik usage:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Usage {

        @Test
        @DisplayName("Get traces count on previous day for all workspaces, no Auth")
        void tracesCountForWorkspace() {
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();

            // Setup mock workspace with traces
            var workspaceId = UUID.randomUUID().toString();
            var apikey = UUID.randomUUID().toString();
            int tracesCount = setupEntitiesForWorkspace(workspaceId, apikey, traces,
                    TRACE_RESOURCE_URL_TEMPLATE);

            // Change created_at to the previous day in order to capture those traces in count query, since for Stripe we need to count it daily for yesterday
            subtractClickHouseTableRecordsCreatedAtOneDay("traces").accept(workspaceId);

            // Setup second workspace with traces, but leave created_at date set to today, so traces do not end up in the pool
            var workspaceIdForToday = UUID.randomUUID().toString();
            var apikey2 = UUID.randomUUID().toString();

            setupEntitiesForWorkspace(workspaceIdForToday, apikey2, traces, TRACE_RESOURCE_URL_TEMPLATE);

            try (var actualResponse = client.target(USAGE_RESOURCE_URL_TEMPLATE.formatted(baseURI))
                    .path("/workspace-trace-counts")
                    .request()
                    .get()) {

                var response = validateResponse(actualResponse, TraceCountResponse.class);

                var workspaceTraceCount = getMatch(response.workspacesTracesCount(),
                        wtc -> wtc.workspace().equals(workspaceId));

                assertThat(workspaceTraceCount).isPresent();
                assertThat(workspaceTraceCount.get())
                        .isEqualTo(new TraceCountResponse.WorkspaceTraceCount(workspaceId, tracesCount));

                // Check that today's workspace is not returned
                var workspaceTraceCountToday = getMatch(response.workspacesTracesCount(),
                        wtc -> wtc.workspace().equals(workspaceIdForToday));
                assertThat(workspaceTraceCountToday).isEmpty();
            }
        }

        @Test
        @DisplayName("Get spans count on previous day for all workspaces, no Auth")
        void spansCountForWorkspace() {
            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();

            // Setup mock workspace with spans
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            int spansCount = setupEntitiesForWorkspace(workspaceId, apiKey, spans, SPANS_RESOURCE_URL_TEMPLATE);

            // Change created_at to the previous day in order to capture those spans in count query, since for Stripe we
            // need to count it daily for yesterday
            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            // Setup second workspace with spans, but leave created_at date set to today, so spans do not end up in the
            // pool
            var workspaceIdForToday = UUID.randomUUID().toString();
            var apiKey2 = UUID.randomUUID().toString();

            setupEntitiesForWorkspace(workspaceIdForToday, apiKey2, spans, SPANS_RESOURCE_URL_TEMPLATE);

            try (var actualResponse = client.target(USAGE_RESOURCE_URL_TEMPLATE.formatted(baseURI))
                    .path("/workspace-span-counts")
                    .request()
                    .get()) {

                var response = validateResponse(actualResponse, SpansCountResponse.class);

                var workspaceSpanCount = getMatch(response.workspacesSpansCount(),
                        workspaceCount -> workspaceCount.workspace().equals(workspaceId));

                assertThat(workspaceSpanCount).isPresent();
                assertThat(workspaceSpanCount.get())
                        .isEqualTo(new SpansCountResponse.WorkspaceSpansCount(workspaceId, spansCount));

                // Check that today's workspace is not returned
                var workspaceSpanCountToday = getMatch(response.workspacesSpansCount(),
                        workspaceCount -> workspaceCount.workspace().equals(workspaceIdForToday));
                assertThat(workspaceSpanCountToday).isEmpty();
            }
        }

        @Test
        @DisplayName("Get traces daily info for BI events, no Auth")
        void traceBiInfoTest() {
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();
            biInfoTest(traces, TRACE_RESOURCE_URL_TEMPLATE, "traces",
                    subtractClickHouseTableRecordsCreatedAtOneDay("traces"));
        }

        @Test
        @DisplayName("Get spans daily info for BI events, no Auth")
        void spanBiInfoTest() {
            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();
            biInfoTest(spans, SPANS_RESOURCE_URL_TEMPLATE, "spans",
                    subtractClickHouseTableRecordsCreatedAtOneDay("spans"));
        }

        @Test
        @DisplayName("Get experiments daily info for BI events, no Auth")
        void experimentBiInfoTest() {
            var experiments = experimentResourceClient.generateExperimentList();
            biInfoTest(experiments, EXPERIMENT_RESOURCE_URL_TEMPLATE, "experiments",
                    subtractClickHouseTableRecordsCreatedAtOneDay("experiments"));
        }

        @Test
        @DisplayName("Get datasets daily info for BI events, no Auth")
        void datasetBiInfoTest() {
            var datasets = PodamFactoryUtils.manufacturePojoList(factory, Dataset.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();
            biInfoTest(datasets, DATASET_RESOURCE_URL_TEMPLATE, "datasets",
                    subtractDatasetRecordsCreatedAtOneDay());
        }

        private <T> void biInfoTest(List<T> entities, String resourseUri, String biType,
                Consumer<String> decreaseTableRecordsCreatedAt) {
            // Setup mock workspace with corresponding entities
            var workspaceId = UUID.randomUUID().toString();
            var apikey = UUID.randomUUID().toString();
            int entitiesCount = setupEntitiesForWorkspace(workspaceId, apikey, entities,
                    resourseUri);

            // Change created_at to the previous day in order to capture those entities in count query, since for BI events we need to count it daily for yesterday
            decreaseTableRecordsCreatedAt.accept(workspaceId);

            await().atMost(10, SECONDS).until(() -> {
                try (var actualResponse = client.target(USAGE_RESOURCE_URL_TEMPLATE.formatted(baseURI))
                        .path("bi-%s".formatted(biType))
                        .request()
                        .get()) {

                    var response = validateResponse(actualResponse, BiInformationResponse.class);
                    var biInformation = getMatch(response.biInformation(),
                            biInfo -> biInfo.workspaceId().equals(workspaceId));

                    return biInformation
                            .map(biInfo -> biInfo
                                    .equals(BiInformationResponse.BiInformation.builder()
                                            .workspaceId(workspaceId)
                                            .user(USER)
                                            .count(entitiesCount)
                                            .build()))
                            .orElse(false);
                }
            });
        }

        @Test
        @DisplayName("Get traces count excluding demo data projects")
        void tracesCountExcludingDemoData() {
            var regularTraces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .projectName("Regular Project") // Non-demo project
                            .build())
                    .toList();

            var demoTraces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .projectName(DemoData.PROJECTS.get(0)) // Demo project name
                            .build())
                    .toList();

            // Setup workspace with both regular and demo traces
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create both regular and demo traces
            regularTraces.forEach(trace -> createEntity(trace, apiKey, workspaceName, TRACE_RESOURCE_URL_TEMPLATE));
            demoTraces.forEach(trace -> createEntity(trace, apiKey, workspaceName, TRACE_RESOURCE_URL_TEMPLATE));

            // Change created_at to the previous day to capture in usage query
            subtractClickHouseTableRecordsCreatedAtOneDay("traces").accept(workspaceId);

            await().atMost(10, SECONDS).until(() -> {
                try (var actualResponse = client.target(USAGE_RESOURCE_URL_TEMPLATE.formatted(baseURI))
                        .path("workspace-trace-counts")
                        .request()
                        .get()) {

                    var response = validateResponse(actualResponse, TraceCountResponse.class);
                    var traceCount = getMatch(response.workspacesTracesCount(),
                            traceInfo -> traceInfo.workspace().equals(workspaceId));

                    // Should only count regular traces, not demo traces
                    return traceCount
                            .map(info -> info.traceCount() == regularTraces.size())
                            .orElse(false);
                }
            });
        }

        @Test
        @DisplayName("Get spans count excluding demo data projects")
        void spansCountExcludingDemoData() {
            var regularSpans = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .projectName("Regular Project") // Non-demo project
                            .build())
                    .toList();

            var demoSpans = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .projectName(DemoData.PROJECTS.get(1)) // Demo project name
                            .build())
                    .toList();

            // Setup workspace with both regular and demo spans
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create both regular and demo spans
            regularSpans.forEach(span -> createEntity(span, apiKey, workspaceName, SPANS_RESOURCE_URL_TEMPLATE));
            demoSpans.forEach(span -> createEntity(span, apiKey, workspaceName, SPANS_RESOURCE_URL_TEMPLATE));

            // Change created_at to the previous day to capture in usage query
            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            await().atMost(10, SECONDS).until(() -> {
                try (var actualResponse = client.target(USAGE_RESOURCE_URL_TEMPLATE.formatted(baseURI))
                        .path("workspace-span-counts")
                        .request()
                        .get()) {

                    var response = validateResponse(actualResponse, SpansCountResponse.class);
                    var spanCount = getMatch(response.workspacesSpansCount(),
                            spanInfo -> spanInfo.workspace().equals(workspaceId));

                    // Should only count regular spans, not demo spans
                    return spanCount
                            .map(info -> info.spanCount() == regularSpans.size())
                            .orElse(false);
                }
            });
        }

        @Test
        @DisplayName("Mixed workspace with demo and regular data - only regular data counted")
        void mixedWorkspaceExcludesDemoData() {
            var regularTraces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .limit(3)
                    .map(e -> e.toBuilder()
                            .id(null)
                            .projectName("Production Project")
                            .build())
                    .toList();

            var multiDemoTraces = DemoData.PROJECTS.stream()
                    .flatMap(projectName -> PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                            .stream()
                            .limit(2)
                            .map(e -> e.toBuilder()
                                    .id(null)
                                    .projectName(projectName)
                                    .build()))
                    .toList();

            // Setup workspace
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create all traces (regular + demo)
            regularTraces.forEach(trace -> createEntity(trace, apiKey, workspaceName, TRACE_RESOURCE_URL_TEMPLATE));
            multiDemoTraces.forEach(trace -> createEntity(trace, apiKey, workspaceName, TRACE_RESOURCE_URL_TEMPLATE));

            // Change created_at to the previous day to capture in usage query
            subtractClickHouseTableRecordsCreatedAtOneDay("traces").accept(workspaceId);

            await().atMost(10, SECONDS).until(() -> {
                try (var actualResponse = client.target(USAGE_RESOURCE_URL_TEMPLATE.formatted(baseURI))
                        .path("workspace-trace-counts")
                        .request()
                        .get()) {

                    var response = validateResponse(actualResponse, TraceCountResponse.class);
                    var traceCount = getMatch(response.workspacesTracesCount(),
                            traceInfo -> traceInfo.workspace().equals(workspaceId));

                    // Should only count regular traces (3), not demo traces (10 total from 5 projects * 2 traces each)
                    return traceCount
                            .map(info -> info.traceCount() == regularTraces.size())
                            .orElse(false);
                }
            });
        }
    }

    private <T> int setupEntitiesForWorkspace(String workspaceId, String okApikey, List<T> entities,
            String resourseUri) {
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(okApikey, workspaceName, workspaceId);

        entities.forEach(entity -> createEntity(entity, okApikey, workspaceName, resourseUri));

        return entities.size();
    }

    private <T> void createEntity(T entity, String apiKey, String workspaceName, String resourseUri) {
        try (var actualResponse = client.target(resourseUri.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(entity))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    private <T> Optional<T> getMatch(List<T> list, Predicate<T> predicate) {
        return list.stream()
                .filter(predicate)
                .findFirst();
    }

    private <T> T validateResponse(Response response, Class<T> entityType) {
        assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(200);
        assertThat(response.hasEntity()).isTrue();

        return response.readEntity(entityType);
    }

    private Consumer<String> subtractClickHouseTableRecordsCreatedAtOneDay(String table) {
        // Change created_at to the previous day in order to capture this data in query
        return workspaceId -> {
            String updateCreatedAt = "ALTER TABLE %s UPDATE created_at = subtractDays(created_at, 1) WHERE workspace_id=:workspace_id;"
                    .formatted(table);
            clickHouseTemplate.nonTransaction(connection -> {
                var statement = connection.createStatement(updateCreatedAt)
                        .bind("workspace_id", workspaceId);
                return Mono.from(statement.execute());
            }).block();
        };
    }

    private Consumer<String> subtractDatasetRecordsCreatedAtOneDay() {
        // Change created_at to the previous day in order to capture this data in query
        return workspaceId -> {
            mySqlTemplate.inTransaction(WRITE, handle -> {
                handle.createUpdate(
                        "UPDATE datasets SET created_at = TIMESTAMPADD(DAY, -1, created_at) WHERE workspace_id=:workspace_id")
                        .bind("workspace_id", workspaceId)
                        .execute();

                return null;
            });
        };
    }
}

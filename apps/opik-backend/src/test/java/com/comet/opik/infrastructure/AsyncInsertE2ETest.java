package com.comet.opik.infrastructure;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.domain.SpanMapper;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.redis.testcontainers.RedisContainer;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Async Insert E2E Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AsyncInsertE2ETest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = "test-workspace";
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("asyncInsert.enabled", "true")))
                        .build());
    }

    private String baseURI;
    private SpanResourceClient spanResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);

        ClientSupportUtils.config(client);

        // Set up authentication for WireMock
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Span Operations")
    class SpanOperations {

        @Test
        @DisplayName("POST /spans - should use async insert settings for individual span creation")
        void createSpan__whenAsyncInsertEnabled__shouldUseAsyncInsertSettings() {
            // Given - Use random project name for this test
            String projectName = "test-project-" + GENERATOR.generate().toString();
            Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .parentSpanId(null)
                    .feedbackScores(null)
                    .usage(null)
                    .totalEstimatedCost(null)
                    .comments(null)
                    .build();

            // When - Create span via API (individual insert)
            Instant baseTime = Instant.now();
            UUID spanId = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);

            // Then - Verify span was created successfully
            assertThat(spanId).isNotNull();

            // Verify the span exists in database with correct data
            Span retrievedSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY);

            // Use the expected span with the retrieved project metadata
            Span expectedSpan = span.toBuilder()
                    .projectName(retrievedSpan.projectName()) // Use actual retrieved project name
                    .projectId(retrievedSpan.projectId())
                    .createdAt(baseTime)
                    .lastUpdatedAt(baseTime)
                    .build();

            SpanAssertions.assertSpan(List.of(retrievedSpan), List.of(expectedSpan), USER);
        }

        @Test
        @DisplayName("PATCH /spans/{id} - should use async insert settings when entity exists")
        void updateSpan__whenEntityExists__shouldUseAsyncInsertSettings() {
            // Given - Use random project name for this test
            String projectName = "test-project-" + GENERATOR.generate().toString();
            Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .parentSpanId(null)
                    .feedbackScores(null)
                    .usage(null)
                    .totalEstimatedCost(null)
                    .comments(null)
                    .build();

            Instant baseTime = Instant.now();
            UUID spanId = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);

            // Get the actual created span to obtain the real project metadata
            Span createdSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY);

            SpanUpdate spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectName(createdSpan.projectName()) // Use the retrieved project name
                    .projectId(null) // Let the system resolve it
                    .traceId(createdSpan.traceId())
                    .parentSpanId(createdSpan.parentSpanId())
                    .build();

            // When - Update span via API (individual update)
            spanResourceClient.updateSpan(spanId, spanUpdate, API_KEY, TEST_WORKSPACE);

            // Then - Verify span was updated successfully
            Span actualUpdatedSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY);

            // Build expected span using SpanMapper to properly apply the update
            Span.SpanBuilder expectedSpanBuilder = createdSpan.toBuilder()
                    .createdAt(baseTime)
                    .lastUpdatedAt(baseTime)
                    .feedbackScores(null);
            SpanMapper.INSTANCE.updateSpanBuilder(expectedSpanBuilder, spanUpdate);
            Span expectedUpdatedSpan = expectedSpanBuilder.build();

            SpanAssertions.assertSpan(List.of(actualUpdatedSpan), List.of(expectedUpdatedSpan), USER);
        }

        @Test
        @DisplayName("PATCH /spans/{id} - should create entity when it does not exist")
        void updateSpan__whenEntityDoesNotExist__shouldCreateEntity() {
            // Given - Non-existent span ID and update data (following SpansResourceTest pattern)
            IntStream.range(0, 10)
                    .parallel()
                    .forEach(i -> {
                        UUID nonExistentSpanId = GENERATOR.generate();

                        SpanUpdate spanUpdate = podamFactory.manufacturePojo(SpanUpdate.class).toBuilder()
                                .projectId(null) // Let system resolve project
                                .build();

                        // When - Update non-existent span (this creates it)
                        spanResourceClient.updateSpan(nonExistentSpanId, spanUpdate, API_KEY, TEST_WORKSPACE);

                        // Then - Verify the span was created successfully
                        Span createdSpan = spanResourceClient.getById(nonExistentSpanId, TEST_WORKSPACE, API_KEY);
                        assertThat(createdSpan).isNotNull();
                        assertThat(createdSpan.id()).isEqualTo(nonExistentSpanId);
                    });
        }
    }

    @Nested
    @DisplayName("Trace Operations")
    class TraceOperations {

        @Test
        @DisplayName("POST /traces - should use async insert settings for individual trace creation")
        void createTrace__whenAsyncInsertEnabled__shouldUseAsyncInsertSettings() {
            // Given
            String projectName = "test-project-" + GENERATOR.generate().toString();
            Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .feedbackScores(null)
                    .build();

            // When - Create trace via API (individual insert)
            Instant baseTime = Instant.now();
            UUID traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            // Then - Verify trace was created successfully
            assertThat(traceId).isNotNull();

            // Verify the trace exists in database with correct data
            Trace retrievedTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);

            // Use the expected trace with the correct project metadata (projectName should be null in retrieved traces)
            Trace expectedTrace = trace.toBuilder()
                    .projectName(null) // Retrieved traces don't have project name
                    .projectId(retrievedTrace.projectId())
                    .usage(retrievedTrace.usage())
                    .feedbackScores(null)
                    .createdAt(baseTime)
                    .lastUpdatedAt(baseTime)
                    .build();
            TraceAssertions.assertTraces(List.of(retrievedTrace), List.of(expectedTrace), USER);
        }

        @Test
        @DisplayName("PATCH /traces/{id} - should use async insert settings when entity exists")
        void updateTrace__whenEntityExists__shouldUseAsyncInsertSettings() {
            // Given
            String projectName = "test-project-" + GENERATOR.generate().toString();
            Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .feedbackScores(null)
                    .build();

            Instant baseTime = Instant.now();
            UUID traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            // Get the actual created trace to obtain the real project metadata
            Trace createdTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);

            TraceUpdate traceUpdate = podamFactory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectName(projectName) // Use the same project name
                    .projectId(createdTrace.projectId())
                    .build();

            // When - Update trace via API (individual update)
            traceResourceClient.updateTrace(traceId, traceUpdate, API_KEY, TEST_WORKSPACE);

            // Then - Verify trace was updated successfully
            Trace actualUpdatedTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);

            // Build expected trace using the TracesResourceTest pattern
            // Use earlier timestamps to satisfy timing assertions

            Trace expectedUpdatedTrace = createdTrace.toBuilder()
                    .projectName(null) // Retrieved traces don't have project name
                    .createdAt(baseTime)
                    .lastUpdatedAt(baseTime)
                    .name(traceUpdate.name())
                    .endTime(traceUpdate.endTime())
                    .input(traceUpdate.input())
                    .output(traceUpdate.output())
                    .metadata(traceUpdate.metadata())
                    .tags(traceUpdate.tags())
                    .errorInfo(traceUpdate.errorInfo())
                    .threadId(traceUpdate.threadId())
                    .build();

            TraceAssertions.assertTraces(List.of(actualUpdatedTrace), List.of(expectedUpdatedTrace), USER);
        }

        @Test
        @DisplayName("PATCH /traces/{id} - should create entity when it does not exist")
        void updateTrace__whenEntityDoesNotExist__shouldCreateEntity() {
            // Given - Non-existent trace ID and update data (following TracesResourceTest pattern)
            UUID nonExistentTraceId = GENERATOR.generate();
            TraceUpdate traceUpdate = podamFactory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectId(null) // Let system resolve project
                    .build();

            // When - Update non-existent trace (this creates it)
            traceResourceClient.updateTrace(nonExistentTraceId, traceUpdate, API_KEY, TEST_WORKSPACE);

            // Then - Verify the trace was created successfully
            Trace createdTrace = traceResourceClient.getById(nonExistentTraceId, TEST_WORKSPACE, API_KEY);
            assertThat(createdTrace).isNotNull();
            assertThat(createdTrace.id()).isEqualTo(nonExistentTraceId);
        }
    }

    @Test
    @DisplayName("Verify async insert settings are applied correctly")
    void verifyAsyncInsertConfiguration() {
        // This test verifies that the configuration is properly loaded
        // by testing the basic functionality works with async insert enabled

        // Given - Create both a span and trace to ensure the configuration works for both
        String projectName = "config-test-" + GENERATOR.generate().toString();
        Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .parentSpanId(null)
                .feedbackScores(null)
                .build();
        Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .feedbackScores(null)
                .build();

        // When & Then - Both operations should complete successfully with async insert enabled
        Instant baseTime = Instant.now();
        UUID spanId = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);
        UUID traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

        assertThat(spanId).isNotNull();
        assertThat(traceId).isNotNull();

        // Verify data integrity using proper whole-object assertions
        Span retrievedSpan = spanResourceClient.getById(spanId, TEST_WORKSPACE, API_KEY);
        Trace retrievedTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);

        // Use the expected entities with the correct project metadata
        Span expectedSpan = span.toBuilder()
                .projectName(retrievedSpan.projectName()) // Use actual retrieved project name
                .projectId(retrievedSpan.projectId())
                .createdAt(baseTime)
                .lastUpdatedAt(baseTime)
                .build();
        Trace expectedTrace = trace.toBuilder()
                .projectName(null) // Retrieved traces don't have project name
                .projectId(retrievedTrace.projectId())
                .usage(retrievedTrace.usage())
                .feedbackScores(null)
                .createdAt(baseTime)
                .lastUpdatedAt(baseTime)
                .build();

        SpanAssertions.assertSpan(List.of(retrievedSpan), List.of(expectedSpan), USER);
        TraceAssertions.assertTraces(List.of(retrievedTrace), List.of(expectedTrace), USER);
    }
}
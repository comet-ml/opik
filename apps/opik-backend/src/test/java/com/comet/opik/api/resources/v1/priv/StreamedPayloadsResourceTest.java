package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PayloadCases;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.SlimPayloadStorageCheck;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;

/**
 * The payload storage cases of {@code TracesResourceTest} and {@code SpansResourceTest}, on an app with
 * {@code databaseAnalytics.streamPayloads} on: those classes run with it off.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Streamed Payloads Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class StreamedPayloadsResourceTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.secure().nextAlphanumeric(10);
    private static final String API_KEY = UUID.randomUUID().toString();

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();

    /** Own network, and reuse switched off, so ZooKeeper state from another class's ClickHouse cannot leak in. */
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils
            .newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer);
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, clickHouseContainer, mysqlContainer, zookeeperContainer).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        databaseAnalyticsFactory.setStreamPayloads(true);

        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);

        app = newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private ExperimentResourceClient experimentResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        traceResourceClient = new TraceResourceClient(client, baseURI);
        spanResourceClient = new SpanResourceClient(client, baseURI);
        experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    Stream<JsonNode> tracesBatchAndSingleCreateStoreTheSamePayload() {
        return PayloadCases.payloads();
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("traces: a batch create and a single create store the same input and output")
    void tracesBatchAndSingleCreateStoreTheSamePayload(JsonNode payload) {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var batchTrace = trace(projectName, payload);
        var singleTrace = trace(projectName, payload);

        traceResourceClient.batchCreateTraces(List.of(batchTrace), API_KEY, WORKSPACE_NAME);
        traceResourceClient.createTrace(singleTrace, API_KEY, WORKSPACE_NAME);

        var actualTraces = Stream.of(batchTrace, singleTrace)
                .map(trace -> traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY))
                .toList();
        TraceAssertions.assertTraces(actualTraces, List.of(batchTrace, singleTrace), USER);
    }

    Stream<JsonNode> spansBatchAndSingleCreateStoreTheSamePayload() {
        return PayloadCases.payloads();
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("spans: a batch create and a single create store the same input and output")
    void spansBatchAndSingleCreateStoreTheSamePayload(JsonNode payload) {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var batchSpan = span(projectName, payload);
        var singleSpan = span(projectName, payload);

        spanResourceClient.batchCreateSpans(List.of(batchSpan), API_KEY, WORKSPACE_NAME);
        spanResourceClient.createSpan(singleSpan, API_KEY, WORKSPACE_NAME);

        var actualSpans = Stream.of(batchSpan, singleSpan)
                .map(span -> spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY))
                .toList();
        SpanAssertions.assertSpan(actualSpans, List.of(batchSpan, singleSpan), USER);
    }

    @Test
    @DisplayName("traces: a batch create and a single create store the same slim input and output")
    void tracesBatchAndSingleCreateStoreTheSameSlimPayload() {
        SlimPayloadStorageCheck.builder()
                .traceResourceClient(traceResourceClient)
                .projectResourceClient(projectResourceClient)
                .experimentResourceClient(experimentResourceClient)
                .factory(factory)
                .apiKey(API_KEY)
                .workspaceName(WORKSPACE_NAME)
                .user(USER)
                .build()
                .assertBatchAndSingleCreateStoreTheSameSlimPayload();
    }

    private Trace trace(String projectName, JsonNode payload) {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .usage(null)
                .feedbackScores(null)
                .input(payload)
                .output(payload)
                .build();
    }

    private Span span(String projectName, JsonNode payload) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .parentSpanId(null)
                .feedbackScores(null)
                .input(payload)
                .output(payload)
                .build();
    }
}

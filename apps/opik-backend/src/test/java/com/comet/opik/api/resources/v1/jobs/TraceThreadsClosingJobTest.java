package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.domain.threads.TraceThreadCriteria;
import com.comet.opik.domain.threads.TraceThreadModel;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
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
import org.testcontainers.shaded.org.awaitility.Awaitility;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.comet.opik.domain.ProjectService.DEFAULT_USER;
import static com.comet.opik.domain.threads.TraceThreadModel.Status;
import static com.comet.opik.domain.threads.TraceThreadModel.builder;

@DisplayName("Trace Threads Closing Job Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TraceThreadsClosingJobTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICKHOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .build());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, podamFactory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Nested
    @DisplayName("Test Trace Threads Closing Job")
    class TraceThreadsClosingJob {

        @Test
        @DisplayName("Should close trace threads for a project")
        void shouldCloseTraceThreadsForProject(TraceThreadService traceThreadService) {
            // Given

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            var threadId = UUID.randomUUID().toString();

            // Create multiple trace within same thread
            List<Trace> traces = createListOfTraces(projectName, threadId);

            var expectedCreatedAt = Instant.now();

            Instant expectedLastUpdatedAt = getExpectedLastUpdatedAt(traces);

            var expectedTraceThreadModel = createTraceThreadModel(threadId, projectId, expectedCreatedAt,
                    expectedLastUpdatedAt, DEFAULT_USER, Status.INACTIVE);

            var expectedLastUpdateAt = Instant.now();

            // When: Creating trace threads
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Then
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                var criteria = TraceThreadCriteria.builder()
                        .projectId(projectId)
                        .status(Status.INACTIVE)
                        .build();

                List<TraceThreadModel> actualTraceThreadModels = traceThreadService.getThreadsByProject(1, 10, criteria)
                        .contextWrite(context -> context.put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block();

                TraceAssertions.assertClosedThreads(actualTraceThreadModels, List.of(expectedTraceThreadModel),
                        expectedCreatedAt,
                        expectedLastUpdateAt);
            });
        }

        @Test
        @DisplayName("Should reopen trace threads if new traces are added after closing")
        void shouldReopenTraceThreadsIfNewTracesAreAdded(TraceThreadService traceThreadService) {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

            var threadId1 = UUID.randomUUID().toString();
            var threadId2 = UUID.randomUUID().toString();

            // Create initial traces
            List<Trace> initialTraces = new ArrayList<>();

            List<Trace> tracesFromThread1 = createListOfTraces(projectName, threadId1);
            List<Trace> tracesFromThread2 = createListOfTraces(projectName, threadId2);

            initialTraces.addAll(tracesFromThread1);
            initialTraces.addAll(tracesFromThread2);

            Instant expectedCreatedAt = Instant.now();

            Instant expectedLastUpdatedAt1 = getExpectedLastUpdatedAt(tracesFromThread1);
            Instant expectedLastUpdatedAt2 = getExpectedLastUpdatedAt(tracesFromThread2);

            // When: Creating trace threads
            traceResourceClient.batchCreateTraces(initialTraces, apiKey, workspaceName);

            List<TraceThreadModel> expectedOpenedTraceThreadModels = List.of(
                    createTraceThreadModel(threadId1, projectId, expectedCreatedAt, expectedLastUpdatedAt1, USER,
                            Status.ACTIVE),
                    createTraceThreadModel(threadId2, projectId, expectedCreatedAt, expectedLastUpdatedAt2, USER,
                            Status.ACTIVE));

            // Then: Check if the threads are opened
            Awaitility.await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verifyOpenThreads(traceThreadService, projectId, workspaceId, expectedOpenedTraceThreadModels,
                        expectedCreatedAt);
            });

            List<TraceThreadModel> expectedClosedTraceThreadModels = List.of(
                    createTraceThreadModel(threadId1, projectId, expectedCreatedAt, expectedLastUpdatedAt1,
                            DEFAULT_USER, Status.INACTIVE),
                    createTraceThreadModel(threadId2, projectId, expectedCreatedAt, expectedLastUpdatedAt2,
                            DEFAULT_USER, Status.INACTIVE));

            // Then: Check if the threads are closed
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verifyClosedThreads(traceThreadService, projectId, workspaceId, expectedClosedTraceThreadModels,
                        expectedCreatedAt, expectedLastUpdatedAt2);
            });

            // Then: Check if the threads are reopened after adding new traces
            List<Trace> newTracesThread1 = createListOfTraces(projectName, threadId1);

            List<Trace> newTracesThread2 = createListOfTraces(projectName, threadId2);

            List<Trace> newTraces = new ArrayList<>(newTracesThread1.size() + newTracesThread2.size());
            newTraces.addAll(newTracesThread1);
            newTraces.addAll(newTracesThread2);

            traceResourceClient.batchCreateTraces(newTraces, apiKey, workspaceName);

            // Update expected last updated at for the reopened threads
            Instant expectedLastUpdatedAtForReopened1 = getExpectedLastUpdatedAt(newTracesThread1);
            Instant expectedLastUpdatedAtForReopened2 = getExpectedLastUpdatedAt(newTracesThread2);

            // Update expected models for reopened threads
            List<TraceThreadModel> expectedReopenedTraceThreadModels = List.of(
                    createTraceThreadModel(threadId1, projectId, expectedCreatedAt, expectedLastUpdatedAtForReopened1,
                            USER, Status.ACTIVE),
                    createTraceThreadModel(threadId2, projectId, expectedCreatedAt, expectedLastUpdatedAtForReopened2,
                            USER, Status.ACTIVE));

            // Then: Check if the thread is reopened
            Awaitility.await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verifyOpenThreads(traceThreadService, projectId, workspaceId, expectedReopenedTraceThreadModels,
                        expectedCreatedAt);
            });

            List<TraceThreadModel> expectedClosedTraceThreadModels2 = List.of(
                    createTraceThreadModel(threadId1, projectId, expectedCreatedAt, expectedLastUpdatedAtForReopened1,
                            DEFAULT_USER, Status.INACTIVE),
                    createTraceThreadModel(threadId2, projectId, expectedCreatedAt, expectedLastUpdatedAtForReopened2,
                            DEFAULT_USER, Status.INACTIVE));

            // Finally: Check if the threads are closed again after the job runs
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verifyClosedThreads(traceThreadService, projectId, workspaceId, expectedClosedTraceThreadModels2,
                        expectedCreatedAt, expectedLastUpdatedAt2);
            });

        }

        private void verifyClosedThreads(TraceThreadService traceThreadService, UUID projectId, String workspaceId,
                List<TraceThreadModel> expectedClosedTraceThreadModels2, Instant expectedCreatedAt,
                Instant expectedLastUpdatedAt2) {
            var criteria = TraceThreadCriteria.builder()
                    .projectId(projectId)
                    .status(Status.INACTIVE)
                    .build();

            List<TraceThreadModel> closedThreadsAfterReopen = traceThreadService.getThreadsByProject(1, 10, criteria)
                    .contextWrite(context -> context.put(RequestContext.USER_NAME, USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block();

            TraceAssertions.assertClosedThreads(closedThreadsAfterReopen,
                    expectedClosedTraceThreadModels2, expectedCreatedAt, expectedLastUpdatedAt2.plusMillis(1));
        }

        private void verifyOpenThreads(TraceThreadService traceThreadService, UUID projectId, String workspaceId,
                List<TraceThreadModel> expectedReopenedTraceThreadModels, Instant expectedCreatedAt) {
            var criteria = TraceThreadCriteria.builder()
                    .projectId(projectId)
                    .status(Status.ACTIVE)
                    .build();

            List<TraceThreadModel> reopenedThreads = traceThreadService.getThreadsByProject(1, 10, criteria)
                    .contextWrite(context -> context.put(RequestContext.USER_NAME, USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block();

            TraceAssertions.assertOpenThreads(reopenedThreads, expectedReopenedTraceThreadModels, expectedCreatedAt);
        }

        private Instant getExpectedLastUpdatedAt(List<Trace> tracesFromThread1) {
            return tracesFromThread1.stream()
                    .map(Trace::lastUpdatedAt)
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
        }

        private List<Trace> createListOfTraces(String projectName, String threadId1) {
            return PodamFactoryUtils.manufacturePojoList(podamFactory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .usage(null)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .guardrailsValidations(null)
                            .projectId(null)
                            .projectName(projectName)
                            .threadId(threadId1)
                            .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                            .build())
                    .toList();
        }

        private TraceThreadModel createTraceThreadModel(String threadId, UUID projectId, Instant createdAt,
                Instant expectedLastUpdatedAt, String lastUpdatedBy, Status status) {
            return builder()
                    .threadId(threadId)
                    .projectId(projectId)
                    .status(status)
                    .createdBy(USER)
                    .lastUpdatedBy(lastUpdatedBy)
                    .createdAt(createdAt)
                    .lastUpdatedAt(expectedLastUpdatedAt)
                    .build();
        }

    }

}
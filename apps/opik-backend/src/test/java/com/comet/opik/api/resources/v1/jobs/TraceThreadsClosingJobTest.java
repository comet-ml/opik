package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.WorkspaceConfiguration;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.resources.WorkspaceResourceClient;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
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
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.comet.opik.domain.ProjectService.DEFAULT_USER;

@DisplayName("Trace Threads Closing Job Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TraceThreadsClosingJobTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
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
                        .customConfigs(List.of(
                                new CustomConfig("traceThreadConfig.enabled", "true")))
                        .build());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private WorkspaceResourceClient workspaceResourceClient;

    @BeforeAll

    void setUpAll(ClientSupport client) {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, podamFactory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.workspaceResourceClient = new WorkspaceResourceClient(this.client, baseURI, podamFactory);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Nested
    @DisplayName("Test Trace Threads Closing Job")
    class TraceThreadsClosingJob {

        @Test
        @DisplayName("Should close trace threads for a project")
        void shouldCloseTraceThreadsForProject() {
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

            // When: Creating trace threads
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            Instant expectedLastUpdatedAt = getExpectedLastUpdatedAt(traces);

            var expectedTraceThreadModel = createTraceThreadModel(threadId, projectId, expectedCreatedAt,
                    expectedLastUpdatedAt, DEFAULT_USER, TraceThreadStatus.INACTIVE, traces);

            Mono.delay(Duration.ofSeconds(1)).block();

            var expectedLastUpdateAt = Instant.now();
            TraceThread expectedUpdatedTraceThreadModel = expectedTraceThreadModel.toBuilder()
                    .lastUpdatedAt(expectedLastUpdateAt)
                    .build();

            // Then
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verifyClosedThreads(projectId, projectName, apiKey, workspaceName,
                        List.of(expectedUpdatedTraceThreadModel));
            });
        }

        @Test
        @DisplayName("Should reopen trace threads if new traces are added after closing")
        void shouldReopenTraceThreadsIfNewTracesAreAdded() {
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
            List<Trace> tracesFromThread1 = createListOfTraces(projectName, threadId1);
            List<Trace> tracesFromThread2 = createListOfTraces(projectName, threadId2);

            Instant expectedCreatedAt = Instant.now();

            // When: Creating trace threads
            traceResourceClient.batchCreateTraces(tracesFromThread1, apiKey, workspaceName);
            traceResourceClient.batchCreateTraces(tracesFromThread2, apiKey, workspaceName);

            Instant expectedLastUpdatedAt1 = getExpectedLastUpdatedAt(tracesFromThread1);
            Instant expectedLastUpdatedAt2 = getExpectedLastUpdatedAt(tracesFromThread2);

            List<TraceThread> expectedOpenedTraceThreadModels = List.of(
                    createTraceThreadModel(threadId2, projectId, expectedCreatedAt, expectedLastUpdatedAt2, USER,
                            TraceThreadStatus.ACTIVE, tracesFromThread2),
                    createTraceThreadModel(threadId1, projectId, expectedCreatedAt, expectedLastUpdatedAt1, USER,
                            TraceThreadStatus.ACTIVE, tracesFromThread1));

            // Then: Check if the threads are opened
            Awaitility.await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verifyOpenThreads(projectId, projectName, apiKey, workspaceName, expectedOpenedTraceThreadModels);
            });

            Instant expectedLastUpdatedAt = Instant.now();

            List<TraceThread> expectedClosedTraceThreadModels = List.of(
                    createTraceThreadModel(threadId1, projectId, expectedCreatedAt, expectedLastUpdatedAt,
                            DEFAULT_USER, TraceThreadStatus.INACTIVE, tracesFromThread1),
                    createTraceThreadModel(threadId2, projectId, expectedCreatedAt, expectedLastUpdatedAt,
                            DEFAULT_USER, TraceThreadStatus.INACTIVE, tracesFromThread2));

            // Then: Check if the threads are closed
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verifyClosedThreads(projectId, projectName, apiKey, workspaceName, expectedClosedTraceThreadModels);
            });

            // Then: Check if the threads are reopened after adding new traces
            List<Trace> newTracesThread1 = createListOfTraces(projectName, threadId1);
            List<Trace> newTracesThread2 = createListOfTraces(projectName, threadId2);

            traceResourceClient.batchCreateTraces(newTracesThread1, apiKey, workspaceName);
            traceResourceClient.batchCreateTraces(newTracesThread2, apiKey, workspaceName);

            // Update expected last updated at for the reopened threads
            Instant expectedLastUpdatedAtForReopened1 = getExpectedLastUpdatedAt(newTracesThread1);
            Instant expectedLastUpdatedAtForReopened2 = getExpectedLastUpdatedAt(newTracesThread2);

            // Update expected created at and last updated at for reopened threads
            List<Trace> tracesThread1 = Stream.concat(tracesFromThread1.stream(), newTracesThread1.stream()).toList();
            List<Trace> tracesThread2 = Stream.concat(tracesFromThread2.stream(), newTracesThread2.stream()).toList();

            // Update expected models for reopened threads
            List<TraceThread> expectedReopenedTraceThreadModels = List.of(
                    createTraceThreadModel(threadId2, projectId, expectedCreatedAt, expectedLastUpdatedAtForReopened2,
                            USER, TraceThreadStatus.ACTIVE, tracesThread2),
                    createTraceThreadModel(threadId1, projectId, expectedCreatedAt, expectedLastUpdatedAtForReopened1,
                            USER, TraceThreadStatus.ACTIVE, tracesThread1));

            // Then: Check if the thread is reopened
            Awaitility.await().pollInterval(200, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verifyOpenThreads(projectId, projectName, apiKey, workspaceName, expectedReopenedTraceThreadModels);
            });

            expectedLastUpdatedAt = Instant.now();

            List<TraceThread> expectedClosedTraceThreadModels2 = List.of(
                    createTraceThreadModel(threadId1, projectId, expectedCreatedAt, expectedLastUpdatedAt,
                            DEFAULT_USER, TraceThreadStatus.INACTIVE, tracesThread1),
                    createTraceThreadModel(threadId2, projectId, expectedCreatedAt, expectedLastUpdatedAt,
                            DEFAULT_USER, TraceThreadStatus.INACTIVE, tracesThread2));

            // Finally: Check if the threads are closed again after the job runs
            Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verifyClosedThreads(projectId, projectName, apiKey, workspaceName, expectedClosedTraceThreadModels2);
            });
        }

        @Test
        @DisplayName("Should close trace threads for project with custom workspace timeout")
        void shouldCloseTraceThreadsForProjectWithCustomTimeout() {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            var threadId = UUID.randomUUID().toString();

            // Set a custom workspace timeout of 2 seconds (shorter than the job polling, but longer than default 1s in config-test.yml)
            Duration customTimeout = Duration.ofSeconds(2);
            WorkspaceConfiguration configuration = WorkspaceConfiguration.builder()
                    .timeoutToMarkThreadAsInactive(customTimeout)
                    .build();

            workspaceResourceClient.upsertWorkspaceConfiguration(configuration, apiKey, workspaceName);

            // Create multiple traces within same thread
            List<Trace> traces = createListOfTraces(projectName, threadId);
            var expectedCreatedAt = Instant.now();

            // When: Creating trace threads
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            Instant expectedLastUpdatedAt = getExpectedLastUpdatedAt(traces);

            var expectedActiveTraceThreadModel = createTraceThreadModel(threadId, projectId, expectedCreatedAt,
                    expectedLastUpdatedAt, DEFAULT_USER, TraceThreadStatus.ACTIVE, traces);

            // Wait for the job to process the created thread
            Mono.delay(Duration.ofSeconds(1)).block();

            // Then: Verify that threads are created as ACTIVE first
            Awaitility.await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verifyOpenThreads(projectId, projectName, apiKey, workspaceName,
                        List.of(expectedActiveTraceThreadModel));
            });

            // Wait for custom timeout duration + some buffer (2-second total)
            // This should be enough to trigger closure with custom timeout
            Mono.delay(Duration.ofSeconds(2)).block();

            var expectedTraceThreadModel = createTraceThreadModel(threadId, projectId, expectedCreatedAt,
                    expectedLastUpdatedAt, DEFAULT_USER, TraceThreadStatus.INACTIVE, traces);

            var expectedLastUpdateAt = Instant.now();
            TraceThread expectedUpdatedTraceThreadModel = expectedTraceThreadModel.toBuilder()
                    .lastUpdatedAt(expectedLastUpdateAt)
                    .build();

            // Then: Verify threads are closed according to workspace custom timeout
            Awaitility.await().pollInterval(200, TimeUnit.MILLISECONDS).untilAsserted(() -> {
                verifyClosedThreads(projectId, projectName, apiKey, workspaceName,
                        List.of(expectedUpdatedTraceThreadModel));
            });
        }

        private void verifyClosedThreads(UUID projectId, String projectName, String apiKey, String workspaceName,
                List<TraceThread> expectedClosedTraceThreadModels) {

            var statusFilter = TraceThreadFilter.builder()
                    .field(TraceThreadField.STATUS)
                    .operator(Operator.EQUAL)
                    .value(TraceThreadStatus.INACTIVE.getValue())
                    .build();

            TraceThread.TraceThreadPage traceThreadPage = traceResourceClient.getTraceThreads(projectId, projectName,
                    apiKey, workspaceName, List.of(statusFilter), null, null);

            List<TraceThread> actualTraceThreads = traceThreadPage.content();

            TraceAssertions.assertThreads(expectedClosedTraceThreadModels, actualTraceThreads);
        }

        private void verifyOpenThreads(UUID projectId, String projectName, String apiKey, String workspaceName,
                List<TraceThread> expectedOpenTraceThreadModels) {

            var statusFilter = TraceThreadFilter.builder()
                    .field(TraceThreadField.STATUS)
                    .operator(Operator.EQUAL)
                    .value(TraceThreadStatus.ACTIVE.getValue())
                    .build();

            TraceThread.TraceThreadPage traceThreadPage = traceResourceClient.getTraceThreads(projectId, projectName,
                    apiKey, workspaceName, List.of(statusFilter), null, null);

            List<TraceThread> actualTraceThreads = traceThreadPage.content();

            TraceAssertions.assertThreads(actualTraceThreads, expectedOpenTraceThreadModels);
        }

        private Instant getExpectedLastUpdatedAt(List<Trace> tracesFromThread1) {
            return tracesFromThread1.stream()
                    .map(Trace::lastUpdatedAt)
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
        }

        private List<Trace> createListOfTraces(String projectName, String threadId) {
            return PodamFactoryUtils.manufacturePojoList(podamFactory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .usage(null)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .guardrailsValidations(null)
                            .projectId(null)
                            .projectName(projectName)
                            .threadId(threadId)
                            .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                            .build())
                    .toList();
        }

        private TraceThread createTraceThreadModel(String threadId, UUID projectId, Instant createdAt,
                Instant expectedLastUpdatedAt, String lastUpdatedBy, TraceThreadStatus status,
                List<Trace> expectedTraces) {
            return TraceThread.builder()
                    .firstMessage(expectedTraces.stream().min(Comparator.comparing(Trace::startTime)).orElseThrow()
                            .input())
                    .lastMessage(expectedTraces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                            .output())
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                            expectedTraces.stream().min(Comparator.comparing(Trace::startTime)).orElseThrow()
                                    .startTime(),
                            expectedTraces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                                    .endTime()))
                    .projectId(projectId)
                    .createdBy(USER)
                    .startTime(expectedTraces.stream().min(Comparator.comparing(Trace::startTime)).orElseThrow()
                            .startTime())
                    .endTime(expectedTraces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                            .endTime())
                    .numberOfMessages(expectedTraces.size() * 2L)
                    .id(threadId)
                    .status(status)
                    .createdAt(createdAt)
                    .lastUpdatedAt(expectedLastUpdatedAt)
                    .lastUpdatedBy(lastUpdatedBy)
                    .totalEstimatedCost(null)
                    .build();
        }

    }

}
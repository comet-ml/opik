package com.comet.opik.domain;

import com.comet.opik.api.BatchDeleteByProject;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * End-to-end coverage of the deletion-events capture across both source tables. Every public entry point that deletes
 * traces records the deleted trace ids ({@code source_table=traces}, {@code deletion_reason=user_request})
 * synchronously, and cascades to the trace's spans, recorded ({@code source_table=spans},
 * {@code deletion_reason=cascade}) by {@code SpanService.deleteByTraceIds}. Runs with both capture flags enabled and a
 * deliberately small insert batch size, so the multi-entity cases also exercise the chunked-insert path; a single
 * workspace is reused and isolation comes from the random project/trace/span ids.
 *
 * <p>Trace capture is synchronous (inside the delete request), so its rows are asserted directly. Span capture runs on
 * the async trace-delete cascade ({@code AsyncEventBus}), so its rows are polled with Awaitility.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class DeletionEventTest {

    private static final String[] DELETION_EVENT_IGNORED_FIELDS = {"eventTime"};

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = randomName("workspace-");
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = randomName("user-");

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mySQLContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils.newClickHouseContainer(
            zookeeperContainer);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mySQLContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mySQLContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        app = newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mySQLContainer.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .redisUrl(redisContainer.getRedisURI())
                .runtimeInfo(wireMock.runtimeInfo())
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceDeletionEventsCaptureEnabled", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.spanDeletionEventsCaptureEnabled", "true"),
                        // Small on purpose: the multi-entity cases then span several insert chunks.
                        new CustomConfig("databaseAnalyticsDataModel.deletionEventsInsertBatchSize", "2")))
                .build());
    }

    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private DeletionEventDAO deletionEventDAO;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, @Jit DeletionEventDAO deletionEventDAO) {
        ClientSupportUtils.config(clientSupport);
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.deletionEventDAO = deletionEventDAO;
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    @Test
    void deleteTraceCapturesDeletionEvent() {
        var projectName = randomName("project");
        var trace = createTrace(projectName);
        var spanIds = createSpans(projectName, trace.id());

        traceResourceClient.deleteTrace(trace.id(), WORKSPACE_NAME, API_KEY);

        // DELETE /traces/{id} passes no project scope, but the delete path resolves each trace's owning project (to
        // prune the delete on the (workspace_id, project_id) prefix), so the resolved project_id is captured — and the
        // same resolved project is passed to the span cascade.
        getAndAssertDeletionEvents(SourceTable.TRACES,
                List.of(newExpectedTraceDeletionEvent(trace.projectId(), trace.id())));
        awaitAndAssertSpanDeletionEvents(trace.projectId(), spanIds);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Deleting batch of traces captures trace + cascaded span deletion events, scopeToProject: {0}")
    void deleteBatchTracesCapturesDeletionEvents(boolean scopeToProject) {
        var projectName = randomName("project");
        var traces = createTraces(projectName);
        var spanIds = traces.stream()
                .flatMap(trace -> createSpans(projectName, trace.id()).stream())
                .collect(Collectors.toUnmodifiableSet());

        var ids = traces.stream().map(Trace::id).collect(Collectors.toUnmodifiableSet());
        var projectId = traces.getFirst().projectId();
        var batchDeleteByProject = BatchDeleteByProject.builder()
                .ids(ids)
                .projectId(scopeToProject ? projectId : null)
                .build();
        traceResourceClient.deleteTraces(batchDeleteByProject, WORKSPACE_NAME, API_KEY);

        var expectedTraceEvents = traces.stream()
                .map(trace -> newExpectedTraceDeletionEvent(trace.projectId(), trace.id()))
                .toList();
        getAndAssertDeletionEvents(SourceTable.TRACES, expectedTraceEvents);
        awaitAndAssertSpanDeletionEvents(projectId, spanIds);
    }

    @Test
    void deleteTraceThreadsCapturesDeletionEvents() {
        var projectName = randomName("project");
        var threadId = randomName("threadId");
        var traces = createTraces(projectName, threadId);
        var spanIds = traces.stream()
                .flatMap(trace -> createSpans(projectName, trace.id()).stream())
                .collect(Collectors.toUnmodifiableSet());

        traceResourceClient.deleteTraceThreads(List.of(threadId), projectName, null, API_KEY, WORKSPACE_NAME);

        var projectId = traces.getFirst().projectId();
        var expectedTraceEvents = traces.stream()
                .map(trace -> newExpectedTraceDeletionEvent(projectId, trace.id()))
                .toList();
        getAndAssertDeletionEvents(SourceTable.TRACES, expectedTraceEvents);
        awaitAndAssertSpanDeletionEvents(projectId, spanIds);
    }

    private static String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }

    private Trace createTrace(String projectName) {
        var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .build();
        return createTraces(projectName, List.of(trace)).getFirst();
    }

    private List<Trace> createTraces(String projectName) {
        return createTraces(projectName, (String) null);
    }

    private List<Trace> createTraces(String projectName, String threadId) {
        var traces = PodamFactoryUtils.manufacturePojoList(podamFactory, Trace.class).stream()
                .map(trace -> trace.toBuilder().projectName(projectName).threadId(threadId).build())
                .toList();
        return createTraces(projectName, traces);
    }

    private List<Trace> createTraces(String projectName, List<Trace> traces) {
        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
        var createdTraces = traceResourceClient.getByProjectName(projectName, API_KEY, WORKSPACE_NAME);
        assertThat(createdTraces).hasSize(traces.size());
        return createdTraces;
    }

    private Set<UUID> createSpans(String projectName, UUID traceId) {
        var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class).stream()
                .map(span -> span.toBuilder()
                        .projectName(projectName)
                        .traceId(traceId)
                        .feedbackScores(null)
                        .build())
                .toList();
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
        return spans.stream().map(Span::id).collect(Collectors.toUnmodifiableSet());
    }

    private DeletionEvent newExpectedTraceDeletionEvent(UUID projectId, UUID traceId) {
        return newExpectedDeletionEvent(SourceTable.TRACES, DeletionReason.USER_REQUEST, projectId, traceId.toString());
    }

    private DeletionEvent newExpectedDeletionEvent(SourceTable sourceTable, DeletionReason reason, UUID projectId,
            String deletedId) {
        return DeletionEvent.builder()
                .sourceTable(sourceTable)
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .deletedId(deletedId)
                .deletionReason(reason)
                .build();
    }

    private void getAndAssertDeletionEvents(SourceTable sourceTable, List<DeletionEvent> expectedDeletionEvents) {
        var deletedIds = expectedDeletionEvents.stream()
                .map(DeletionEvent::deletedId)
                .collect(Collectors.toUnmodifiableSet());
        // Reference for the event_time assertion: ClickHouse stamps it during the delete that ran just before this
        // call, so it must be within a couple of seconds of now.
        var now = Instant.now();
        var actualDeletionEvents = deletionEventDAO
                .findBySourceTableAndDeletedIds(sourceTable, deletedIds)
                .collectList()
                .block();

        assertThat(actualDeletionEvents)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(DELETION_EVENT_IGNORED_FIELDS)
                .containsExactlyInAnyOrderElementsOf(expectedDeletionEvents);
        assertThat(actualDeletionEvents).allSatisfy(deletionEvent -> assertThat(deletionEvent.eventTime())
                .isCloseTo(now, within(2, ChronoUnit.SECONDS)));
    }

    private void awaitAndAssertSpanDeletionEvents(UUID projectId, Set<UUID> spanIds) {
        var expectedSpanEvents = spanIds.stream()
                .map(spanId -> newExpectedDeletionEvent(SourceTable.SPANS, DeletionReason.CASCADE, projectId,
                        spanId.toString()))
                .toList();
        // Span capture runs on the async trace-delete cascade (AsyncEventBus), unlike the synchronous trace capture, so
        // poll until the bridge rows land.
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> getAndAssertDeletionEvents(SourceTable.SPANS, expectedSpanEvents));
    }
}

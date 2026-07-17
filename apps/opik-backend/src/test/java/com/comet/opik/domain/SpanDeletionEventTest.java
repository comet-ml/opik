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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * End-to-end coverage of the span deletion-events capture. Spans have no standalone delete, so the only span-deletion
 * path is the trace-delete cascade ({@code SpanService.deleteByTraceIds}); deleting a trace must record its spans' ids
 * in the {@code deletion_events_local} bridge with {@code source_table = spans}. The cascade runs asynchronously off
 * the {@code TracesDeleted} event, so assertions poll with Awaitility. Runs with span capture enabled and a
 * deliberately small insert batch size so the multi-span cases exercise the chunked-insert path; a single workspace is
 * reused and isolation comes from the random project/trace/span ids. Mirrors {@code TraceDeletionEventTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanDeletionEventTest {

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
                        new CustomConfig("databaseAnalyticsDataModel.spanDeletionEventsCaptureEnabled", "true"),
                        // Small on purpose: the multi-span cases then span several insert chunks.
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
    void deleteTraceCascadeCapturesSpanDeletionEvents() {
        var projectName = randomName("project");
        var trace = createTrace(projectName);
        var spanIds = createSpans(projectName, trace.id());

        var beforeDelete = Instant.now();
        traceResourceClient.deleteTrace(trace.id(), WORKSPACE_NAME, API_KEY);

        // The trace-delete cascade resolves each trace's owning project and passes it to the span delete, so the
        // resolved project_id (here the trace's project) is what gets captured on the span rows.
        var expectedDeletionEvents = spanIds.stream()
                .map(spanId -> newExpectedDeletionEvent(trace.projectId(), spanId))
                .toList();
        awaitAndAssertSpanDeletionEvents(expectedDeletionEvents, beforeDelete);
    }

    @Test
    void deleteBatchTracesCascadeCapturesSpanDeletionEvents() {
        var projectName = randomName("project");
        var traceA = createTrace(projectName);
        var traceB = createTrace(projectName);
        var spanIdsA = createSpans(projectName, traceA.id());
        var spanIdsB = createSpans(projectName, traceB.id());

        var beforeDelete = Instant.now();
        traceResourceClient.deleteTraces(BatchDeleteByProject.builder()
                .ids(Set.of(traceA.id(), traceB.id()))
                .projectId(traceA.projectId())
                .build(), WORKSPACE_NAME, API_KEY);

        var expectedDeletionEvents = Stream.concat(spanIdsA.stream(), spanIdsB.stream())
                .map(spanId -> newExpectedDeletionEvent(traceA.projectId(), spanId))
                .toList();
        awaitAndAssertSpanDeletionEvents(expectedDeletionEvents, beforeDelete);
    }

    private static String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }

    private Trace createTrace(String projectName) {
        var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .build();
        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);
        var createdTraces = traceResourceClient.getByProjectName(projectName, API_KEY, WORKSPACE_NAME);
        return createdTraces.stream()
                .filter(t -> t.id().equals(trace.id()))
                .findFirst()
                .orElseThrow();
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

    private DeletionEvent newExpectedDeletionEvent(UUID projectId, UUID spanId) {
        return DeletionEvent.builder()
                .sourceTable(SourceTable.SPANS)
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .deletedId(spanId.toString())
                .deletionReason(DeletionReason.USER_REQUEST)
                .build();
    }

    private void awaitAndAssertSpanDeletionEvents(List<DeletionEvent> expectedDeletionEvents, Instant beforeDelete) {
        var deletedIds = expectedDeletionEvents.stream()
                .map(DeletionEvent::deletedId)
                .collect(Collectors.toUnmodifiableSet());
        // The span cascade is asynchronous (off the TracesDeleted event), so poll until the bridge rows appear.
        Awaitility.await()
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var actualDeletionEvents = deletionEventDAO
                            .findBySourceTableAndDeletedIds(SourceTable.SPANS, deletedIds)
                            .collectList()
                            .block();

                    assertThat(actualDeletionEvents)
                            .usingRecursiveFieldByFieldElementComparatorIgnoringFields(DELETION_EVENT_IGNORED_FIELDS)
                            .containsExactlyInAnyOrderElementsOf(expectedDeletionEvents);
                    // ClickHouse stamps event_time during the cascade delete, which runs shortly after beforeDelete.
                    assertThat(actualDeletionEvents).allSatisfy(deletionEvent -> assertThat(deletionEvent.eventTime())
                            .isCloseTo(beforeDelete, within(60, ChronoUnit.SECONDS)));
                });
    }
}

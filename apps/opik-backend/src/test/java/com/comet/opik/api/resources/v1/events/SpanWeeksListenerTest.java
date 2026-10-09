package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.SpanWeek;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.AsyncUtils;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Span weeks registration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanWeeksListenerTest {

    private static final String USER = UUID.randomUUID().toString();

    // A fixed Monday and its partition, and the previous week's; literals so the expectation does not reuse the
    // code under test.
    private static final Instant MONDAY = Instant.parse("2025-03-03T00:00:00Z");
    private static final long MONDAY_WEEK = 20250303L;
    private static final long PREVIOUS_WEEK = 20250224L;
    // Where ClickHouse stores an id at the epoch, and an id at or past 2300 (DateTime64 saturation). The API rejects
    // non-v7 ids, so only historical rows (the backfill's concern) carry those; SpansLocalV2PartitioningTest pins them.
    private static final long EPOCH_WEEK = 19691229L;
    private static final long SATURATED_WEEK = 22991225L;

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        // Ids outside the ingestion window are the point of these tests; installs with validation off accept them.
        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(MYSQL.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .customConfigs(List.of(new CustomConfig("uuidValidation.enabled", "false"),
                        new CustomConfig("databaseAnalyticsDataModel.spanWeeksWriteEnabled", "true")))
                .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private SpanResourceClient spanResourceClient;
    private ProjectResourceClient projectResourceClient;
    private SpanService spanService;

    @BeforeAll
    void setUpAll(ClientSupport client, SpanService spanService) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.spanService = spanService;
    }

    @Test
    @DisplayName("a created batch registers every (project, trace, week) its spans are stored in")
    void createdSpansRegisterTheirStoredWeeks() {
        var ws = newWorkspace();
        var projectName = "span-weeks-" + UUID.randomUUID();
        var otherProjectName = "span-weeks-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, ws.apiKey(), ws.workspaceName());
        var otherProjectId = projectResourceClient.createProject(otherProjectName, ws.apiKey(), ws.workspaceName());
        var traceAcrossWeeks = uuidV7(System.currentTimeMillis());
        var traceWithBoundaryIds = uuidV7(System.currentTimeMillis());

        var spans = List.of(
                span(uuidV7(MONDAY.toEpochMilli()), traceAcrossWeeks, projectName),
                span(uuidV7(MONDAY.toEpochMilli() + 1), traceAcrossWeeks, projectName),
                span(uuidV7(MONDAY.toEpochMilli() - 1), traceAcrossWeeks, projectName),
                // A span of the same trace in another project: the trace's project does not decide the span's.
                span(uuidV7(MONDAY.toEpochMilli()), traceAcrossWeeks, otherProjectName),
                span(uuidV7(0L), traceWithBoundaryIds, projectName),
                span(uuidV7(Instant.parse("2300-01-01T00:00:00Z").toEpochMilli()), traceWithBoundaryIds, projectName));
        spanResourceClient.batchCreateSpans(spans, ws.apiKey(), ws.workspaceName());

        var expected = List.of(
                SpanWeek.builder().projectId(projectId).traceId(traceAcrossWeeks).idWeek(MONDAY_WEEK).build(),
                SpanWeek.builder().projectId(projectId).traceId(traceAcrossWeeks).idWeek(PREVIOUS_WEEK).build(),
                SpanWeek.builder().projectId(otherProjectId).traceId(traceAcrossWeeks).idWeek(MONDAY_WEEK).build(),
                SpanWeek.builder().projectId(projectId).traceId(traceWithBoundaryIds).idWeek(EPOCH_WEEK).build(),
                SpanWeek.builder().projectId(projectId).traceId(traceWithBoundaryIds).idWeek(SATURATED_WEEK).build());
        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(
                spanService.getWeeksByTraceIds(List.of(traceAcrossWeeks, traceWithBoundaryIds))
                        .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, USER, ws.workspaceId()))
                        .block())
                .containsExactlyInAnyOrderElementsOf(expected));
    }

    @Test
    @DisplayName("an update that arrives before its create registers the past-dated span's week")
    void updateBeforeCreateRegistersTheSpanWeek() {
        var ws = newWorkspace();
        var projectName = "span-weeks-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, ws.apiKey(), ws.workspaceName());
        var traceId = uuidV7(System.currentTimeMillis());
        var pastDatedSpanId = uuidV7(MONDAY.toEpochMilli());

        spanResourceClient.updateSpan(pastDatedSpanId, SpanUpdate.builder()
                .projectName(projectName)
                .traceId(traceId)
                .build(), ws.apiKey(), ws.workspaceName());

        await().atMost(30, SECONDS).untilAsserted(() -> assertThat(
                spanService.getWeeksByTraceIds(List.of(traceId))
                        .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, USER, ws.workspaceId()))
                        .block())
                .containsExactly(SpanWeek.builder().projectId(projectId).traceId(traceId).idWeek(MONDAY_WEEK).build()));
    }

    private Span span(UUID id, UUID traceId, String projectName) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .id(id)
                .traceId(traceId)
                .projectName(projectName)
                .projectId(null)
                .parentSpanId(null)
                .build();
    }

    private static UUID uuidV7(long epochMillis) {
        return new UUID((epochMillis << 16) | 0x7000L, 0x8000_0000_0000_0000L | (System.nanoTime() & 0xFFFFFFFFFFFFL));
    }

    private WorkspaceContext newWorkspace() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
        return new WorkspaceContext(apiKey, workspaceName, workspaceId);
    }

    private record WorkspaceContext(String apiKey, String workspaceName, String workspaceId) {
    }
}

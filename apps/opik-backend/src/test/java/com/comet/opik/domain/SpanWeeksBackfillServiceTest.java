package com.comet.opik.domain;

import com.comet.opik.api.Span;
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
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.AsyncUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.http.HttpStatus;
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
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Span weeks backfill")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanWeeksBackfillServiceTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final int MAX_STEPS = 100;

    // Literal weeks, so the expectation does not reuse the code under test.
    private static final Instant MONDAY = Instant.parse("2025-03-03T00:00:00Z");
    private static final long MONDAY_WEEK = 20250303L;
    private static final long PREVIOUS_WEEK = 20250224L;
    private static final long EPOCH_WEEK = 19691229L;
    private static final long SATURATED_WEEK = 22991225L;

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    // Not reused: the backfill runs once per install, so a reused state DB would hold the previous run's finished plan.
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);
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

        // Span writes register nothing here, so every week found afterwards is the backfill's. Validation off admits
        // the boundary ids that only historical rows carry.
        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(MYSQL.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .customConfigs(List.of(new CustomConfig("uuidValidation.enabled", "false"),
                        // One span per chunk, so the backfill takes many steps.
                        new CustomConfig("spanWeeksBackfill.maxSpansPerChunk", "1")))
                .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private SpanResourceClient spanResourceClient;
    private ProjectResourceClient projectResourceClient;
    private SpanService spanService;
    private SpanWeeksBackfillService writesOffBackfillService;
    private SpanWeeksBackfillService backfillService;

    @BeforeAll
    void setUpAll(ClientSupport client, SpanService spanService,
            @Jit SpanWeeksBackfillService writesOffBackfillService, TransactionTemplate template,
            @Jit SpanWeeksDAO spanWeeksDAO, OpikConfiguration configuration) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.spanService = spanService;
        this.writesOffBackfillService = writesOffBackfillService;

        // The backfill as it runs once span writes register their weeks.
        this.backfillService = new SpanWeeksBackfillService(template, spanWeeksDAO,
                configuration.getDatabaseAnalyticsDataModel().toBuilder().spanWeeksWriteEnabled(true).build(),
                configuration.getSpanWeeksBackfill());
    }

    @Test
    @DisplayName("backfills the weeks of existing spans, one range of weeks at a time, oldest first")
    void backfillsExistingSpansOneWeekRangeAtATime() {
        var ws = newWorkspace();
        var projectName = "span-weeks-" + UUID.randomUUID();
        var otherProjectName = "span-weeks-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, ws.apiKey(), ws.workspaceName());
        var otherProjectId = projectResourceClient.createProject(otherProjectName, ws.apiKey(), ws.workspaceName());
        var traceAcrossWeeks = uuidV7(System.currentTimeMillis());
        var traceWithBoundaryIds = uuidV7(System.currentTimeMillis());
        var traceIds = List.of(traceAcrossWeeks, traceWithBoundaryIds);
        var spans = List.of(
                span(uuidV7(MONDAY.toEpochMilli()), traceAcrossWeeks, projectName),
                span(uuidV7(MONDAY.toEpochMilli() - 1), traceAcrossWeeks, projectName),
                span(uuidV7(MONDAY.toEpochMilli()), traceAcrossWeeks, otherProjectName),
                span(uuidV7(0L), traceWithBoundaryIds, projectName),
                span(uuidV7(Instant.parse("2300-01-01T00:00:00Z").toEpochMilli()), traceWithBoundaryIds,
                        projectName));
        spanResourceClient.batchCreateSpans(spans, ws.apiKey(), ws.workspaceName());
        // The batch is written asynchronously; the plan must see every span, as it would in a running install.
        await().atMost(30, SECONDS).untilAsserted(() -> spans.forEach(span -> {
            try (var response = spanResourceClient.callGetSpanIdApi(span.id(), ws.workspaceName(), ws.apiKey())) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            }
        }));
        var upToMonday = List.of(
                SpanWeek.builder().projectId(projectId).traceId(traceWithBoundaryIds).idWeek(EPOCH_WEEK).build(),
                SpanWeek.builder().projectId(projectId).traceId(traceAcrossWeeks).idWeek(PREVIOUS_WEEK).build(),
                SpanWeek.builder().projectId(projectId).traceId(traceAcrossWeeks).idWeek(MONDAY_WEEK).build(),
                SpanWeek.builder().projectId(otherProjectId).traceId(traceAcrossWeeks).idWeek(MONDAY_WEEK).build());
        var saturated = SpanWeek.builder().projectId(projectId).traceId(traceWithBoundaryIds).idWeek(SATURATED_WEEK)
                .build();

        // While span writes do not register weeks, the backfill must not run: its plan would miss what comes next.
        writesOffBackfillService.runStep().block();
        assertThat(weeks(traceIds, ws)).isEmpty();

        // The first step only plans, one week per chunk here.
        backfillService.runStep().block();
        assertThat(weeks(traceIds, ws)).isEmpty();

        // Oldest week first: once the 2025-03-03 range is in, the 2300 one is still pending.
        runStepsUntil(traceIds, ws, upToMonday.size());
        assertThat(weeks(traceIds, ws)).containsExactlyInAnyOrderElementsOf(upToMonday);

        runStepsUntil(traceIds, ws, upToMonday.size() + 1);
        var all = new ArrayList<>(upToMonday);
        all.add(saturated);
        assertThat(weeks(traceIds, ws)).containsExactlyInAnyOrderElementsOf(all);

        // Every chunk is done: a further step changes nothing.
        backfillService.runStep().block();
        assertThat(weeks(traceIds, ws)).containsExactlyInAnyOrderElementsOf(all);
    }

    private void runStepsUntil(List<UUID> traceIds, WorkspaceContext ws, int weeksCount) {
        for (int step = 0; step < MAX_STEPS && weeks(traceIds, ws).size() < weeksCount; step++) {
            backfillService.runStep().block();
        }
    }

    private List<SpanWeek> weeks(List<UUID> traceIds, WorkspaceContext ws) {
        return spanService.getWeeksByTraceIds(traceIds)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, USER, ws.workspaceId()))
                .block();
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

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
import lombok.Builder;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Span weeks backfill")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanWeeksBackfillServiceTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final int MAX_STEPS = 100;

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    // A random past Monday, before the install week; its weeks come from java.time, not from the code under test.
    private static final LocalDate MONDAY_DATE = LocalDate.of(2020, 1, 6)
            .plusWeeks(RandomUtils.secure().randomInt(0, 260));
    private static final Instant MONDAY = MONDAY_DATE.atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final long MONDAY_WEEK = yyyymmdd(MONDAY_DATE);
    private static final long PREVIOUS_WEEK = yyyymmdd(MONDAY_DATE.minusWeeks(1));
    // Where the epoch and ids past 2300 are stored.
    private static final long EPOCH_WEEK = 19691229L;
    private static final long SATURATED_WEEK = 22991225L;
    private static final String SPAN_WEEKS_MV_MIGRATION = "000138_create_span_weeks_mv.sql";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    // Not reused: the backfill runs once per install, so a reused state DB would hold the previous run's finished plan.
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);
    // Not reused either: the changelog stops before the span_weeks MV, which a reused database already has.
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, MYSQL, clickHouse, zookeeper).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        // Stop before the span_weeks materialized view: the spans this test writes are history from before live
        // registration, which is what the backfill is for. The rest of the changelog runs once they are written.
        MigrationUtils.runClickhouseDbMigrationBefore(clickHouse, SPAN_WEEKS_MV_MIGRATION);

        // Live registration stays off here too, so every week found afterwards is the backfill's. Validation off admits
        // the boundary ids that only historical rows carry.
        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(MYSQL.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .customConfigs(List.of(new CustomConfig("uuidValidation.enabled", "false")))
                .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private SpanResourceClient spanResourceClient;
    private ProjectResourceClient projectResourceClient;
    private SpanService spanService;
    private SpanWeeksBackfillService backfillService;
    private SpanWeeksBackfillService otherInstance;

    @BeforeAll
    void setUpAll(ClientSupport client, SpanService spanService, @Jit SpanWeeksBackfillService backfillService,
            TransactionTemplate template, @Jit SpanWeeksDAO spanWeeksDAO, ProjectService projectService,
            OpikConfiguration configuration) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.spanService = spanService;
        this.backfillService = backfillService;
        // A second instance of the service, as another backend instance in the cluster would hold.
        this.otherInstance = new SpanWeeksBackfillService(template, spanWeeksDAO, projectService,
                configuration.getSpanWeeksBackfill());
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouse.stop();
        zookeeper.stop();
        network.close();
    }

    @Test
    @DisplayName("backfills the weeks of existing spans, one range of weeks at a time, oldest first")
    void backfillsExistingSpansOneWeekRangeAtATime() {
        var ws = newWorkspace();
        var projectName = "span-weeks-" + UUID.randomUUID();
        var otherProjectName = "span-weeks-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, ws.apiKey(), ws.workspaceName());
        var otherProjectId = projectResourceClient.createProject(otherProjectName, ws.apiKey(), ws.workspaceName());
        var traceAcrossWeeks = ID_GENERATOR.generateId();
        var traceWithBoundaryIds = ID_GENERATOR.generateId();
        var traceIds = Set.of(traceAcrossWeeks, traceWithBoundaryIds);
        var spans = List.of(
                span(ID_GENERATOR.getTimeOrderedEpoch(MONDAY.toEpochMilli()), traceAcrossWeeks, projectName),
                span(ID_GENERATOR.getTimeOrderedEpoch(MONDAY.toEpochMilli() - 1), traceAcrossWeeks, projectName),
                span(ID_GENERATOR.getTimeOrderedEpoch(MONDAY.toEpochMilli()), traceAcrossWeeks, otherProjectName),
                span(ID_GENERATOR.getTimeOrderedEpoch(0L), traceWithBoundaryIds, projectName),
                span(ID_GENERATOR.getTimeOrderedEpoch(Instant.parse("2300-01-01T00:00:00Z").toEpochMilli()),
                        traceWithBoundaryIds, projectName));
        spanResourceClient.batchCreateSpans(spans, ws.apiKey(), ws.workspaceName());
        // The install now upgrades: from here on the MV registers new spans, but not these.
        MigrationUtils.runClickhouseDbMigration(clickHouse);
        var upToMonday = List.of(
                SpanWeek.builder().projectId(projectId).traceId(traceWithBoundaryIds).idWeek(EPOCH_WEEK).build(),
                SpanWeek.builder().projectId(projectId).traceId(traceAcrossWeeks).idWeek(PREVIOUS_WEEK).build(),
                SpanWeek.builder().projectId(projectId).traceId(traceAcrossWeeks).idWeek(MONDAY_WEEK).build(),
                SpanWeek.builder().projectId(otherProjectId).traceId(traceAcrossWeeks).idWeek(MONDAY_WEEK).build());
        var saturated = SpanWeek.builder().projectId(projectId).traceId(traceWithBoundaryIds).idWeek(SATURATED_WEEK)
                .build();

        // No plan yet, then a plan with every range pending: not complete either way.
        assertThat(backfillService.isCompleted().block()).isFalse();

        // The first step only plans, reading no span week.
        backfillService.runStep().block();
        assertThat(weeks(traceIds, ws)).isEmpty();
        assertThat(backfillService.isCompleted().block()).isFalse();

        // Oldest range first: the ids before the install week (the random Monday and the epoch) come in before the
        // open-ended last range, which holds the 2300 id.
        runStepsUntil(traceIds, ws, upToMonday.size());
        assertThat(weeks(traceIds, ws)).containsExactlyInAnyOrderElementsOf(upToMonday);

        runStepsUntil(traceIds, ws, upToMonday.size() + 1);
        var all = new ArrayList<>(upToMonday);
        all.add(saturated);
        assertThat(weeks(traceIds, ws)).containsExactlyInAnyOrderElementsOf(all);

        // Every range is done: the shared progress table reports the backfill complete to any instance, and a further
        // step changes nothing.
        assertThat(backfillService.isCompleted().block()).isTrue();
        assertThat(otherInstance.isCompleted().block()).isTrue();
        backfillService.runStep().block();
        assertThat(weeks(traceIds, ws)).containsExactlyInAnyOrderElementsOf(all);
    }

    private void runStepsUntil(Set<UUID> traceIds, WorkspaceContext ws, int expectedRowCount) {
        for (int step = 0; step < MAX_STEPS && weeks(traceIds, ws).size() < expectedRowCount; step++) {
            backfillService.runStep().block();
        }
    }

    private List<SpanWeek> weeks(Set<UUID> traceIds, WorkspaceContext ws) {
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

    private static long yyyymmdd(LocalDate date) {
        return Long.parseLong(date.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    private WorkspaceContext newWorkspace() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
        return WorkspaceContext.builder().apiKey(apiKey).workspaceName(workspaceName).workspaceId(workspaceId).build();
    }

    @Builder(toBuilder = true)
    private record WorkspaceContext(String apiKey, String workspaceName, String workspaceId) {
    }
}

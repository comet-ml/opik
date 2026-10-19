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
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.SpanWeek;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.AsyncUtils;
import com.redis.testcontainers.RedisContainer;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Span weeks registration by the materialized view")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanWeeksMaterializedViewTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final long WEEK_MILLIS = Duration.ofDays(7).toMillis();

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
                        // The listener stays off, so every span_weeks row here comes from span_weeks_mv.
                        new CustomConfig("databaseAnalyticsDataModel.spanWeeksWriteEnabled", "false")))
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
        var projectName = "span-weeks-" + RandomStringUtils.secure().nextAlphanumeric(10);
        var otherProjectName = "span-weeks-" + RandomStringUtils.secure().nextAlphanumeric(10);
        var projectId = projectResourceClient.createProject(projectName, ws.apiKey(), ws.workspaceName());
        var otherProjectId = projectResourceClient.createProject(otherProjectName, ws.apiKey(), ws.workspaceName());
        var monday = randomMonday();
        var traceAcrossWeeks = ID_GENERATOR.generateId();
        var traceWithBoundaryIds = ID_GENERATOR.generateId();

        var spans = List.of(
                span(idInWeekOf(monday), traceAcrossWeeks, projectName),
                span(idInWeekOf(monday), traceAcrossWeeks, projectName),
                span(idInWeekOf(monday.minusWeeks(1)), traceAcrossWeeks, projectName),
                // A span of the same trace in another project: the trace's project does not decide the span's.
                span(idInWeekOf(monday), traceAcrossWeeks, otherProjectName),
                span(ID_GENERATOR.generateId(Instant.EPOCH), traceWithBoundaryIds, projectName),
                span(ID_GENERATOR.generateId(Instant.parse("2300-01-01T00:00:00Z")), traceWithBoundaryIds,
                        projectName));
        spanResourceClient.batchCreateSpans(spans, ws.apiKey(), ws.workspaceName());

        var expectedWeeks = List.of(
                SpanWeek.builder().projectId(projectId).traceId(traceAcrossWeeks).idWeek(yyyymmdd(monday)).build(),
                SpanWeek.builder().projectId(projectId).traceId(traceAcrossWeeks)
                        .idWeek(yyyymmdd(monday.minusWeeks(1))).build(),
                SpanWeek.builder().projectId(otherProjectId).traceId(traceAcrossWeeks).idWeek(yyyymmdd(monday))
                        .build(),
                SpanWeek.builder().projectId(projectId).traceId(traceWithBoundaryIds).idWeek(EPOCH_WEEK).build(),
                SpanWeek.builder().projectId(projectId).traceId(traceWithBoundaryIds).idWeek(SATURATED_WEEK)
                        .build());
        await().atMost(30, SECONDS).untilAsserted(() -> {
            var actualWeeks = weeks(Set.of(traceAcrossWeeks, traceWithBoundaryIds), ws);
            assertThat(actualWeeks).containsExactlyInAnyOrderElementsOf(expectedWeeks);
        });
    }

    @Test
    @DisplayName("an update that arrives before its create registers the past-dated span's week (its upsert is an insert)")
    void updateBeforeCreateRegistersTheSpanWeek() {
        var ws = newWorkspace();
        var projectName = "span-weeks-" + RandomStringUtils.secure().nextAlphanumeric(10);
        var projectId = projectResourceClient.createProject(projectName, ws.apiKey(), ws.workspaceName());
        var monday = randomMonday();
        var traceId = ID_GENERATOR.generateId();
        var pastDatedSpanId = idInWeekOf(monday);

        spanResourceClient.updateSpan(pastDatedSpanId, SpanUpdate.builder()
                .projectName(projectName)
                .traceId(traceId)
                .build(), ws.apiKey(), ws.workspaceName());

        var expectedWeeks = List.of(
                SpanWeek.builder().projectId(projectId).traceId(traceId).idWeek(yyyymmdd(monday)).build());
        await().atMost(30, SECONDS).untilAsserted(() -> {
            var actualWeeks = weeks(Set.of(traceId), ws);
            assertThat(actualWeeks).containsExactlyElementsOf(expectedWeeks);
        });
    }

    private List<SpanWeek> weeks(Set<UUID> traceIds, WorkspaceContext ws) {
        return spanService.getWeeksByTraceIds(traceIds)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, USER, ws.workspaceId()))
                .block();
    }

    /** A random Monday from 2024 to 2026, past-dated like the spans the index exists for. */
    private static LocalDate randomMonday() {
        return LocalDate.of(2024, 1, 1).plusWeeks(RandomUtils.secure().randomInt(0, 150));
    }

    private static UUID idInWeekOf(LocalDate monday) {
        long mondayMillis = monday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return ID_GENERATOR
                .generateId(Instant.ofEpochMilli(mondayMillis + RandomUtils.secure().randomLong(0, WEEK_MILLIS)));
    }

    // Computed with java.time rather than WeeklyPartitions, so the expectation does not reuse the code under test.
    private static long yyyymmdd(LocalDate monday) {
        return Long.parseLong(monday.format(DateTimeFormatter.BASIC_ISO_DATE));
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

package com.comet.opik.api.resources.v1.jobs;

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
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.SpanWeek;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.AsyncUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.awaitility.Awaitility;
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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test of the span weeks backfill job, end to end: spans written through the API with live registration off are
 * found in span_weeks once the job has ticked through the plan.
 */
@DisplayName("Span weeks backfill job")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanWeeksBackfillJobIntegrationTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(16);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(16);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    // Not reused: the backfill runs once per install, so a reused state DB would hold another run's finished plan.
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);

    @RegisterApp
    private final TestDropwizardAppExtension app;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(redis, mysql, clickHouse, zookeeper).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickHouse);
        // The job is on and live registration is off, so every week found is the backfill's. Each tick holds the lock for the
        // whole interval, so the interval is short: the startup tick plans, and one chunk follows per interval.
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(redis.getRedisURI())
                .customConfigs(List.of(
                        new CustomConfig("uuidValidation.enabled", "false"),
                        new CustomConfig("databaseAnalyticsDataModel.spanWeeksBackfillEnabled", "true"),
                        new CustomConfig("spanWeeksBackfill.interval", "10s"),
                        new CustomConfig("spanWeeksBackfill.queryTimeout", "5s")))
                .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private SpanResourceClient spanResourceClient;
    private ProjectResourceClient projectResourceClient;
    private SpanService spanService;
    private SpanWeeksBackfillJob job;

    @BeforeAll
    void setUpAll(ClientSupport client, SpanService spanService, SpanWeeksBackfillJob job) {
        var baseUrl = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.spanResourceClient = new SpanResourceClient(client, baseUrl);
        this.projectResourceClient = new ProjectResourceClient(client, baseUrl, factory);
        this.spanService = spanService;
        this.job = job;
    }

    @Test
    @DisplayName("registers the weeks of spans written before it ran")
    void registersTheWeeksOfExistingSpans() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(16);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var traceId = ID_GENERATOR.generateId();
        // A random past Monday, before the install week, and the current week: the plan's two ends.
        var pastMonday = LocalDate.of(2020, 1, 6).plusWeeks(RandomUtils.secure().randomInt(0, 260));
        // One instant for the span and its expected week, so a run across Monday midnight cannot split them.
        var now = Instant.now();
        var thisMonday = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var spans = List.of(
                span(pastMonday.atStartOfDay(ZoneOffset.UTC).toInstant(), traceId, projectName),
                span(now, traceId, projectName));
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
        var expected = List.of(
                SpanWeek.builder().projectId(projectId).traceId(traceId).idWeek(yyyymmdd(pastMonday)).build(),
                SpanWeek.builder().projectId(projectId).traceId(traceId).idWeek(yyyymmdd(thisMonday)).build());

        // One chunk per interval; a tick that finds the lock still held skips. Plan plus two chunks: three intervals.
        Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    job.doJob(null);
                    assertThat(weeks(Set.of(traceId))).containsExactlyInAnyOrderElementsOf(expected);
                });
    }

    private List<SpanWeek> weeks(Set<UUID> traceIds) {
        return spanService.getWeeksByTraceIds(traceIds)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, USER, WORKSPACE_ID))
                .block();
    }

    private Span span(Instant idAt, UUID traceId, String projectName) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli()))
                .traceId(traceId)
                .projectName(projectName)
                .projectId(null)
                .parentSpanId(null)
                .build();
    }

    private static long yyyymmdd(LocalDate date) {
        return Long.parseLong(date.format(DateTimeFormatter.BASIC_ISO_DATE));
    }
}

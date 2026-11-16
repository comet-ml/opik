package com.comet.opik.db;

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
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
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
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code 000138_create_span_weeks_mv} across the cutover: the MV is created on {@code spans} while it is the live local
 * table, then the runbook's EXCHANGE and Distributed wrap run, and the MV must follow the name onto the wrapper, so spans
 * written through the API keep registering their weeks. Dedicated, non-reused containers, because the cutover swaps
 * the live table.
 */
@DisplayName("Span weeks registration by the materialized view, across the cutover")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanWeeksMaterializedViewCutoverTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final long WEEK_MILLIS = Duration.ofDays(7).toMillis();
    private static final long EPOCH_WEEK = 19691229L;
    private static final long SATURATED_WEEK = 22991225L;

    private static final CutoverSchemaParity PARITY = CutoverSchemaParity.SPANS;
    private static final String SPANS = PARITY.getLive();
    private static final String SHADOW = PARITY.getShadow();
    private static final String SHARD = PARITY.getShard();
    private static final String BACKUP = PARITY.getBackup();
    private static final String TEMP_WRAPPER = "spans_dist";
    private static final String MATERIALIZED_VIEW = "span_weeks_mv";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
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
        MigrationUtils.runClickhouseDbMigration(clickHouse);
        applyCutover();
        assertCutOver();

        // The two flags match the post-cutover topology: sentinel columns, and mutations routed to spans_local.
        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(MYSQL.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .customConfigs(List.of(new CustomConfig("uuidValidation.enabled", "false"),
                        new CustomConfig("databaseAnalyticsDataModel.spanColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.spansDistributedWrapEnabled", "true")))
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

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouse.stop();
        zookeeper.stop();
        network.close();
    }

    @Test
    @DisplayName("spans written through the Distributed wrapper register every (project, trace, week) they are stored in")
    void spansThroughTheWrapperRegisterTheirStoredWeeks() {
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
                span(idInWeekOf(monday.minusWeeks(1)), traceAcrossWeeks, projectName),
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
    @DisplayName("the MV follows the spans name onto the Distributed wrapper, not the renamed shard")
    void materializedViewFollowsTheSpansName() throws SQLException {
        // An MV left on the shard would also register inserts delivered to it, so the test above cannot tell the two
        // apart.
        assertThat(dependentViews(SPANS)).containsExactly(MATERIALIZED_VIEW);
        assertThat(dependentViews(SHARD)).isEmpty();
    }

    /** Guards against a no-op cutover before any span is written: {@code spans} must wrap the renamed shard. */
    private void assertCutOver() {
        try {
            assertThat(systemTables("engine", SPANS)).containsExactly("Distributed");
            assertThat(systemTables("engine", SHARD)).containsExactly("ReplicatedReplacingMergeTree");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the spans topology", e);
        }
    }

    private List<String> dependentViews(String table) throws SQLException {
        return systemTables("arrayJoin(dependencies_table)", table);
    }

    private List<String> systemTables(String column, String table) throws SQLException {
        try (var connection = clickHouse.createConnection("");
                var statement = connection.prepareStatement(
                        "SELECT %s FROM system.tables WHERE database = ? AND name = ?".formatted(column))) {
            statement.setString(1, DATABASE_NAME);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                var values = new ArrayList<String>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                return values;
            }
        }
    }

    private void applyCutover() {
        try (var connection = clickHouse.createConnection("");
                var statement = connection.createStatement()) {
            // Cutover step 3, as 000003_exchange_and_wrap.sql runs it.
            statement.execute("EXCHANGE TABLES %s.%s AND %s.%s ON CLUSTER '{cluster}'"
                    .formatted(DATABASE_NAME, SPANS, DATABASE_NAME, SHADOW));
            statement.execute("RENAME TABLE %s.%s TO %s.%s ON CLUSTER '{cluster}'"
                    .formatted(DATABASE_NAME, SHADOW, DATABASE_NAME, BACKUP));
            statement.execute("""
                    CREATE TABLE %s.%s ON CLUSTER '{cluster}' AS %s.%s
                    ENGINE = Distributed('{cluster}', '%s', '%s', sipHash64(project_id))
                    """.formatted(DATABASE_NAME, TEMP_WRAPPER, DATABASE_NAME, SPANS, DATABASE_NAME, SHARD));
            statement.execute("RENAME TABLE %s.%s TO %s.%s, %s.%s TO %s.%s ON CLUSTER '{cluster}'"
                    .formatted(DATABASE_NAME, SPANS, DATABASE_NAME, SHARD, DATABASE_NAME, TEMP_WRAPPER,
                            DATABASE_NAME, SPANS));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply the spans cutover", e);
        }
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

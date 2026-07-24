package com.comet.opik.infrastructure;

import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
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
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.traces.TraceAssertions.assertThreads;
import static com.comet.opik.api.resources.utils.traces.TraceAssertions.assertTraces;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Exercises the trace sentinel-column wiring with {@code traceColumnsNonNullable} enabled. The flag is a runtime
 * concern, not a schema one — the epoch/{@code NaN} sentinels are ordinary values the still-{@code Nullable} columns
 * store — so the flag-ON path (which the flag-OFF suites never reach) is verifiable before the non-nullable DDL ships:
 * writes bind the sentinels, reads translate them back to {@code null}, and filters use the sentinel-aware SQL.
 *
 * <p>Tests are grouped by write path — single create, batch create, and update ({@code partialInsert}) — because each
 * binds the sentinels independently; read and filter behavior is write-path-independent and covered once under
 * {@code CreateTraces}. The physical-write tests read the raw columns back to prove the R2DBC driver serializes a
 * bound {@code Double.NaN} to ClickHouse {@code nan} on write — the link the cutover hinges on, since a stray
 * {@code NULL} write is rejected by a non-nullable column yet reads back indistinguishably from the sentinel.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TraceSentinelIntegrationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);

    private final WireMockUtils.WireMockRuntime wireMock;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer)
                .join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
                                new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true")))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private ConnectionFactory clickHouseConnectionFactory;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        clickHouseConnectionFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME).build();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateTraces {

        @Test
        void absentEndTimeAndTtftRoundTripAsNull() {
            var trace = newTraceBuilder()
                    .endTime(null)
                    .ttft(null)
                    .build();
            var id = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            var actualTrace = traceResourceClient.getById(id, WORKSPACE_NAME, API_KEY);

            // Field-by-field, not assertTraces: that helper also asserts the derived duration, but the materialized
            // duration column is not yet guarded against an epoch end_time (that guard ships with the cutover DDL), so
            // an absent end_time yields a negative duration instead of null. end_time/ttft are the sentinels under test.
            assertThat(actualTrace.endTime()).isNull();
            assertThat(actualTrace.ttft()).isNull();
        }

        @Test
        void presentEndTimeAndTtftRoundTripUnchanged() {
            var startTime = Instant.now().minusSeconds(1);
            var expectedTrace = newTraceBuilder()
                    .startTime(startTime)
                    .endTime(startTime.plusSeconds(1))
                    .build();
            var id = traceResourceClient.createTrace(expectedTrace, API_KEY, WORKSPACE_NAME);

            var actualTrace = traceResourceClient.getById(id, WORKSPACE_NAME, API_KEY);

            assertTraces(List.of(actualTrace), List.of(expectedTrace), USER);
        }

        @Test
        void absentSentinelsArePhysicallyWritten() {
            var trace = newTraceBuilder()
                    .endTime(null)
                    .ttft(null)
                    .duration(null)
                    .build();
            var id = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            var expectedCounts = StoredSentinelCounts.builder()
                    .nanTtftCount(1L)
                    .epochEndTimeCount(1L)
                    .build();
            var actualCounts = queryStoredSentinelCounts(id);

            assertThat(actualCounts).isEqualTo(expectedCounts);
        }

        Stream<Arguments> sentinelFilterExcludesAbsentTrace() {
            return Stream.of(
                    arguments(TraceField.END_TIME, Operator.LESS_THAN,
                            Instant.now().plus(1, ChronoUnit.DAYS).toString()),
                    arguments(TraceField.TTFT, Operator.GREATER_THAN, "1"),
                    arguments(TraceField.DURATION, Operator.GREATER_THAN, "1"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void sentinelFilterExcludesAbsentTrace(TraceField field, Operator operator, String value) {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var startTime = Instant.now().minusSeconds(1);
            var presentTrace = newTraceBuilder()
                    .projectName(projectName)
                    .startTime(startTime)
                    .endTime(startTime.plusSeconds(1))
                    .ttft(5.0d)
                    .build();
            var absentTrace = newTraceBuilder()
                    .projectName(projectName)
                    .startTime(Instant.now())
                    .endTime(null)
                    .ttft(null)
                    .duration(null)
                    .build();
            traceResourceClient.createTrace(presentTrace, API_KEY, WORKSPACE_NAME);
            traceResourceClient.createTrace(absentTrace, API_KEY, WORKSPACE_NAME);

            var filters = TraceFilter.builder()
                    .field(field)
                    .operator(operator).value(value)
                    .build();
            var page = traceResourceClient.getTraces(
                    projectName, null, API_KEY, WORKSPACE_NAME, List.of(filters), List.of(), 10, Map.of());

            assertTraces(page.content(), List.of(presentTrace), List.of(absentTrace), USER);
        }

        @Test
        void sortByEndTimeAscendingOrdersAbsentTraceLast() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var startTime = Instant.now().minusSeconds(1);
            var presentTrace = newTraceBuilder()
                    .projectName(projectName)
                    .startTime(startTime)
                    .endTime(startTime.plusSeconds(1))
                    .build();
            var absentTrace = newTraceBuilder()
                    .projectName(projectName)
                    .endTime(null)
                    .build();
            traceResourceClient.createTrace(presentTrace, API_KEY, WORKSPACE_NAME);
            traceResourceClient.createTrace(absentTrace, API_KEY, WORKSPACE_NAME);
            var sortingField = SortingField.builder()
                    .field(SortableFields.END_TIME)
                    .direction(Direction.ASC)
                    .build();

            var page = traceResourceClient.getTraces(
                    projectName, null, API_KEY, WORKSPACE_NAME, List.of(), List.of(sortingField), 10, Map.of());

            // The sort maps end_time through nullIf(end_time, epoch), so the absent trace sorts as NULL (last),
            // preserving Nullable-era ordering — not as the 1970 epoch, which would sort first ascending. Ids, not a
            // full assertTraces: the absent trace's not-yet-epoch-guarded duration is a negative pre-cutover artifact.
            var actualOrder = page.content().stream().map(Trace::id).toList();
            assertThat(actualOrder).containsExactly(presentTrace.id(), absentTrace.id());
        }
    }

    @Nested
    class BatchCreateTraces {

        @Test
        void absentEndTimeAndTtftRoundTripAsNull() {
            var trace = newTraceBuilder()
                    .endTime(null)
                    .ttft(null)
                    .build();
            traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

            var actualTrace = traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY);

            assertThat(actualTrace.endTime()).isNull();
            assertThat(actualTrace.ttft()).isNull();
        }

        @Test
        void sentinelsArePhysicallyWrittenPerRow() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var startTime = Instant.now().minusSeconds(1);
            var presentTrace = newTraceBuilder()
                    .projectName(projectName)
                    .startTime(startTime)
                    .endTime(startTime.plusSeconds(1))
                    .ttft(5.0d)
                    .build();
            var absentTrace = newTraceBuilder()
                    .projectName(projectName)
                    .endTime(null)
                    .ttft(null)
                    .duration(null)
                    .build();
            traceResourceClient.batchCreateTraces(List.of(presentTrace, absentTrace), API_KEY, WORKSPACE_NAME);

            // Per-row indexed binding (end_time0, end_time1, ...): only the absent row must carry the sentinels.
            var expectedAbsentCounts = StoredSentinelCounts.builder()
                    .nanTtftCount(1L)
                    .epochEndTimeCount(1L)
                    .build();
            var expectedPresentCounts = StoredSentinelCounts.builder()
                    .nanTtftCount(0L)
                    .epochEndTimeCount(0L)
                    .build();
            var actualAbsentCounts = queryStoredSentinelCounts(absentTrace.id());
            var actualPresentCounts = queryStoredSentinelCounts(presentTrace.id());

            assertThat(actualAbsentCounts).isEqualTo(expectedAbsentCounts);
            assertThat(actualPresentCounts).isEqualTo(expectedPresentCounts);
        }
    }

    @Nested
    class UpdateTraces {

        @Test
        void updateOmittingEndTimeAndTtftKeepsStoredValues() {
            var startTime = Instant.now().minusSeconds(1);
            var expectedTrace = newTraceBuilder()
                    .startTime(startTime)
                    .endTime(startTime.plusSeconds(1))
                    .ttft(5.0d)
                    .build();
            var id = traceResourceClient.createTrace(expectedTrace, API_KEY, WORKSPACE_NAME);
            var update = factory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectId(null)
                    .projectName(expectedTrace.projectName())
                    .endTime(null)
                    .ttft(null)
                    .build();

            traceResourceClient.updateTrace(id, update, API_KEY, WORKSPACE_NAME);
            var actualTrace = traceResourceClient.getById(id, WORKSPACE_NAME, API_KEY);

            // Under the flag an omitted end_time/ttft binds the epoch/NaN sentinel, so the merge must keep the stored
            // value rather than overwrite it with the sentinel. Field-by-field since the update merges many fields.
            assertThat(actualTrace.endTime()).isEqualTo(expectedTrace.endTime());
            assertThat(actualTrace.ttft()).isEqualTo(expectedTrace.ttft());
        }

        @Test
        void updateSettingEndTimeAndTtftPersistsThem() {
            var trace = newTraceBuilder()
                    .endTime(null)
                    .ttft(null)
                    .build();
            var id = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
            var expectedUpdate = factory.manufacturePojo(TraceUpdate.class).toBuilder()
                    .projectId(null)
                    .projectName(trace.projectName())
                    .endTime(Instant.now())
                    .ttft(7.0d)
                    .build();

            traceResourceClient.updateTrace(id, expectedUpdate, API_KEY, WORKSPACE_NAME);
            var actualTrace = traceResourceClient.getById(id, WORKSPACE_NAME, API_KEY);

            assertThat(actualTrace.endTime()).isEqualTo(expectedUpdate.endTime());
            assertThat(actualTrace.ttft()).isEqualTo(expectedUpdate.ttft());
        }
    }

    @Nested
    class Threads {

        @Test
        void absentEndTimeThreadReadsAsNull() {
            var threadId = UUID.randomUUID().toString();
            var trace = newTraceBuilder()
                    .threadId(threadId)
                    .endTime(null)
                    .build();
            var traceId = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
            var projectId = traceResourceClient.getById(traceId, WORKSPACE_NAME, API_KEY).projectId();

            var thread = traceResourceClient.getTraceThread(threadId, projectId, API_KEY, WORKSPACE_NAME);

            // A thread's end_time is max(trace end_time); with every trace absent it is the epoch, so the flag-gated
            // ThreadDAO read must surface null rather than the 1970 sentinel.
            assertThat(thread.endTime()).isNull();
        }

        @Test
        void sortByEndTimeAscendingOrdersAbsentThreadLast() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var presentThreadId = "thread-" + UUID.randomUUID();
            var absentThreadId = "thread-" + UUID.randomUUID();
            var startTime = Instant.now().minusSeconds(1);
            var presentTrace = newTraceBuilder()
                    .projectName(projectName)
                    .threadId(presentThreadId)
                    .startTime(startTime)
                    .endTime(startTime.plusSeconds(1))
                    .build();
            var absentTrace = newTraceBuilder()
                    .projectName(projectName)
                    .threadId(absentThreadId)
                    .startTime(Instant.now())
                    .endTime(null)
                    .build();
            var presentTraceId = traceResourceClient.createTrace(presentTrace, API_KEY, WORKSPACE_NAME);
            traceResourceClient.createTrace(absentTrace, API_KEY, WORKSPACE_NAME);

            var projectId = traceResourceClient.getById(presentTraceId, WORKSPACE_NAME, API_KEY).projectId();
            // Retrieve materializes each thread record (so it surfaces in the list) and gives the expected
            // sorted order: present first, all-absent (end_time = NULL) last.
            var expectedPresent = traceResourceClient.getTraceThread(presentThreadId, projectId, API_KEY,
                    WORKSPACE_NAME);
            var expectedAbsent = traceResourceClient.getTraceThread(absentThreadId, projectId, API_KEY, WORKSPACE_NAME);
            var sortingField = SortingField.builder()
                    .field(SortableFields.END_TIME)
                    .direction(Direction.ASC)
                    .build();
            var page = traceResourceClient.getTraceThreads(
                    projectId, projectName, API_KEY, WORKSPACE_NAME, List.of(), List.of(sortingField), Map.of());

            // A thread's end_time is max(trace end_time); the all-absent thread's epoch sorts as NULL (last) via
            // nullIf(end_time, epoch), not as 1970 (first) — only the flag-gated sort mapping produces this order.
            assertThreads(List.of(expectedPresent, expectedAbsent), page.content());
        }
    }

    private Trace.TraceBuilder newTraceBuilder() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null)
                .errorInfo(null);
    }

    /**
     * Raw stored-sentinel counts for a trace. {@code countIf} yields {@code UInt64} (marshaled to {@code Long}); a
     * {@code NaN} ttft / epoch end_time counts 1, whereas a wrongly-written {@code NULL} makes {@code isNaN(...)} /
     * {@code (end_time = epoch)} evaluate to {@code NULL} and count 0 — which is what distinguishes a physically-written
     * sentinel from the {@code NULL} the still-{@code Nullable} column would also accept.
     */
    private StoredSentinelCounts queryStoredSentinelCounts(UUID traceId) {
        return queryOne("""
                SELECT countIf(isNaN(ttft))                                             AS nan_ttft_count,
                       countIf(end_time = toDateTime64('1970-01-01 00:00:00.000', 9))   AS epoch_end_time_count
                FROM traces FINAL
                WHERE workspace_id = '%s' AND id = '%s'
                """.formatted(WORKSPACE_ID, traceId),
                row -> StoredSentinelCounts.builder()
                        .nanTtftCount(row.get("nan_ttft_count", Long.class))
                        .epochEndTimeCount(row.get("epoch_end_time_count", Long.class))
                        .build());
    }

    private <T> T queryOne(String sql, Function<Row, T> mapper) {
        return Mono.usingWhen(
                clickHouseConnectionFactory.create(),
                connection -> Mono.from(connection.createStatement(sql).execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> mapper.apply(row)))),
                Connection::close)
                .block();
    }

    @Builder(toBuilder = true)
    private record StoredSentinelCounts(Long nanTtftCount, Long epochEndTimeCount) {
    }
}

package com.comet.opik.infrastructure;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
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
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.spans.SpanAssertions.assertSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Exercises the span sentinel-column wiring with {@code spanColumnsNonNullable} enabled — the span sibling of
 * {@code TraceSentinelIntegrationTest}. The flag is a runtime concern, not a schema one: the epoch/{@code NaN} sentinels
 * are ordinary values the still-{@code Nullable} columns store, so the flag-ON path (which the flag-OFF suites never
 * reach) is verifiable before the non-nullable DDL ships: writes bind the sentinels, reads translate them back to
 * {@code null}, and filters use the sentinel-aware SQL.
 *
 * <p>Tests are grouped by write path — single create, batch create, and update — because each binds the sentinels
 * independently; read and filter behavior is write-path-independent and covered once under {@code CreateSpans}. The
 * physical-write tests read the raw columns back to prove the R2DBC driver serializes a bound {@code Double.NaN} to
 * ClickHouse {@code nan} on write — the link the cutover hinges on, since a stray {@code NULL} write is rejected by a
 * non-nullable column yet reads back indistinguishably from the sentinel.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanSentinelIntegrationTest {

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
                                new CustomConfig("databaseAnalyticsDataModel.spanColumnsNonNullable", "true")))
                        .build());
    }

    private SpanResourceClient spanResourceClient;
    private ConnectionFactory clickHouseConnectionFactory;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        clickHouseConnectionFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME).build();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateSpans {

        @Test
        void absentEndTimeAndTtftRoundTripAsNull() {
            var span = newSpanBuilder()
                    .endTime(null)
                    .ttft(null)
                    .build();
            var id = spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);

            var actualSpan = spanResourceClient.getById(id, WORKSPACE_NAME, API_KEY);

            // Field-by-field, not assertSpan: end_time/ttft are the sentinels under test, and an absent end_time still
            // yields the not-yet-epoch-guarded materialized duration (that guard ships with the cutover DDL).
            assertThat(actualSpan.endTime()).isNull();
            assertThat(actualSpan.ttft()).isNull();
        }

        @Test
        void presentEndTimeAndTtftRoundTripUnchanged() {
            var startTime = Instant.now().minusSeconds(1);
            var expectedSpan = newSpanBuilder()
                    .startTime(startTime)
                    .endTime(startTime.plusSeconds(1))
                    .build();
            var id = spanResourceClient.createSpan(expectedSpan, API_KEY, WORKSPACE_NAME);

            var actualSpan = spanResourceClient.getById(id, WORKSPACE_NAME, API_KEY);

            assertSpan(List.of(actualSpan), List.of(expectedSpan), USER);
        }

        @Test
        void absentSentinelsArePhysicallyWritten() {
            var span = newSpanBuilder()
                    .endTime(null)
                    .ttft(null)
                    .build();
            var id = spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);

            var expectedCounts = StoredSentinelCounts.builder()
                    .nanTtftCount(1L)
                    .epochEndTimeCount(1L)
                    .build();
            var actualCounts = queryStoredSentinelCounts(id);

            assertThat(actualCounts).isEqualTo(expectedCounts);
        }

        Stream<Arguments> sentinelFilterExcludesAbsentSpan() {
            return Stream.of(
                    arguments(SpanField.END_TIME, Operator.LESS_THAN,
                            Instant.now().plus(1, ChronoUnit.DAYS).toString()),
                    arguments(SpanField.TTFT, Operator.GREATER_THAN, "1"),
                    arguments(SpanField.DURATION, Operator.GREATER_THAN, "1"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        void sentinelFilterExcludesAbsentSpan(SpanField field, Operator operator, String value) {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var startTime = Instant.now().minusSeconds(1);
            var presentSpan = newSpanBuilder()
                    .projectName(projectName)
                    .startTime(startTime)
                    .endTime(startTime.plusSeconds(1))
                    .ttft(5.0d)
                    .build();
            var absentSpan = newSpanBuilder()
                    .projectName(projectName)
                    .startTime(Instant.now())
                    .endTime(null)
                    .ttft(null)
                    .build();
            spanResourceClient.createSpan(presentSpan, API_KEY, WORKSPACE_NAME);
            spanResourceClient.createSpan(absentSpan, API_KEY, WORKSPACE_NAME);

            var filters = SpanFilter.builder()
                    .field(field)
                    .operator(operator).value(value)
                    .build();
            var page = spanResourceClient.findSpans(WORKSPACE_NAME, API_KEY, projectName, null, 1, 10, null, null,
                    List.of(filters), List.of(), List.of());

            assertSpan(page.content(), List.of(presentSpan), List.of(absentSpan), USER);
        }

        @Test
        void sortByEndTimeAscendingOrdersAbsentSpanLast() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var startTime = Instant.now().minusSeconds(1);
            var presentSpan = newSpanBuilder()
                    .projectName(projectName)
                    .startTime(startTime)
                    .endTime(startTime.plusSeconds(1))
                    .build();
            var absentSpan = newSpanBuilder()
                    .projectName(projectName)
                    .endTime(null)
                    .build();
            spanResourceClient.createSpan(presentSpan, API_KEY, WORKSPACE_NAME);
            spanResourceClient.createSpan(absentSpan, API_KEY, WORKSPACE_NAME);
            var sortingField = SortingField.builder()
                    .field(SortableFields.END_TIME)
                    .direction(Direction.ASC)
                    .build();

            var page = spanResourceClient.findSpans(WORKSPACE_NAME, API_KEY, projectName, null, 1, 10, null, null,
                    List.of(), List.of(sortingField), List.of());

            // The sort maps end_time through nullIf(end_time, epoch), so the absent span sorts as NULL (last),
            // preserving Nullable-era ordering — not as the 1970 epoch, which would sort first ascending. Ids, not a
            // full assertSpan: the absent span's not-yet-epoch-guarded duration is a negative pre-cutover artifact.
            var actualOrder = page.content().stream().map(Span::id).toList();
            assertThat(actualOrder).containsExactly(presentSpan.id(), absentSpan.id());
        }
    }

    @Nested
    class BatchCreateSpans {

        @Test
        void absentEndTimeAndTtftRoundTripAsNull() {
            var span = newSpanBuilder()
                    .endTime(null)
                    .ttft(null)
                    .build();
            spanResourceClient.batchCreateSpans(List.of(span), API_KEY, WORKSPACE_NAME);

            var actualSpan = spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY);

            assertThat(actualSpan.endTime()).isNull();
            assertThat(actualSpan.ttft()).isNull();
        }

        @Test
        void sentinelsArePhysicallyWrittenPerRow() {
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
            var startTime = Instant.now().minusSeconds(1);
            var presentSpan = newSpanBuilder()
                    .projectName(projectName)
                    .startTime(startTime)
                    .endTime(startTime.plusSeconds(1))
                    .ttft(5.0d)
                    .build();
            var absentSpan = newSpanBuilder()
                    .projectName(projectName)
                    .endTime(null)
                    .ttft(null)
                    .build();
            spanResourceClient.batchCreateSpans(List.of(presentSpan, absentSpan), API_KEY, WORKSPACE_NAME);

            // Per-row indexed binding (end_time0, end_time1, ...): only the absent row must carry the sentinels.
            var expectedAbsentCounts = StoredSentinelCounts.builder()
                    .nanTtftCount(1L)
                    .epochEndTimeCount(1L)
                    .build();
            var expectedPresentCounts = StoredSentinelCounts.builder()
                    .nanTtftCount(0L)
                    .epochEndTimeCount(0L)
                    .build();
            var actualAbsentCounts = queryStoredSentinelCounts(absentSpan.id());
            var actualPresentCounts = queryStoredSentinelCounts(presentSpan.id());

            assertThat(actualAbsentCounts).isEqualTo(expectedAbsentCounts);
            assertThat(actualPresentCounts).isEqualTo(expectedPresentCounts);
        }
    }

    @Nested
    class UpdateSpans {

        @Test
        void updateOmittingEndTimeAndTtftKeepsStoredValues() {
            var startTime = Instant.now().minusSeconds(1);
            var expectedSpan = newSpanBuilder()
                    .startTime(startTime)
                    .endTime(startTime.plusSeconds(1))
                    .ttft(5.0d)
                    .build();
            var id = spanResourceClient.createSpan(expectedSpan, API_KEY, WORKSPACE_NAME);
            var update = factory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .projectName(expectedSpan.projectName())
                    .traceId(expectedSpan.traceId())
                    .parentSpanId(expectedSpan.parentSpanId())
                    .endTime(null)
                    .ttft(null)
                    .build();

            spanResourceClient.updateSpan(id, update, API_KEY, WORKSPACE_NAME);
            var actualSpan = spanResourceClient.getById(id, WORKSPACE_NAME, API_KEY);

            // Under the flag an omitted end_time/ttft binds the epoch/NaN sentinel, so the merge must keep the stored
            // value rather than overwrite it with the sentinel. Field-by-field since the update merges many fields.
            assertThat(actualSpan.endTime()).isEqualTo(expectedSpan.endTime());
            assertThat(actualSpan.ttft()).isEqualTo(expectedSpan.ttft());
        }

        @Test
        void updateSettingEndTimeAndTtftPersistsThem() {
            var span = newSpanBuilder()
                    .endTime(null)
                    .ttft(null)
                    .build();
            var id = spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);
            var expectedUpdate = factory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .projectName(span.projectName())
                    .traceId(span.traceId())
                    .parentSpanId(span.parentSpanId())
                    .endTime(Instant.now())
                    .ttft(7.0d)
                    .build();

            spanResourceClient.updateSpan(id, expectedUpdate, API_KEY, WORKSPACE_NAME);
            var actualSpan = spanResourceClient.getById(id, WORKSPACE_NAME, API_KEY);

            assertThat(actualSpan.endTime()).isEqualTo(expectedUpdate.endTime());
            assertThat(actualSpan.ttft()).isEqualTo(expectedUpdate.ttft());
        }
    }

    private Span.SpanBuilder newSpanBuilder() {
        return factory.manufacturePojo(Span.class).toBuilder()
                .feedbackScores(null)
                .usage(null)
                .errorInfo(null);
    }

    /**
     * Raw stored-sentinel counts for a span. {@code countIf} yields {@code UInt64} (marshaled to {@code Long}); a
     * {@code NaN} ttft / epoch end_time counts 1, whereas a wrongly-written {@code NULL} makes {@code isNaN(...)} /
     * {@code (end_time = epoch)} evaluate to {@code NULL} and count 0 — which is what distinguishes a physically-written
     * sentinel from the {@code NULL} the still-{@code Nullable} column would also accept.
     */
    private StoredSentinelCounts queryStoredSentinelCounts(UUID spanId) {
        return queryOne("""
                SELECT countIf(isNaN(ttft))                                             AS nan_ttft_count,
                       countIf(end_time = toDateTime64('1970-01-01 00:00:00.000', 9))   AS epoch_end_time_count
                FROM spans FINAL
                WHERE workspace_id = '%s' AND id = '%s'
                """.formatted(WORKSPACE_ID, spanId),
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

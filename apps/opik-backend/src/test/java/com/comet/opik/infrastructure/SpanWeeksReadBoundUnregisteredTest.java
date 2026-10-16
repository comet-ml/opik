package com.comet.opik.infrastructure;

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
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
import com.comet.opik.api.resources.v1.events.SpanWeeksListener;
import com.comet.opik.domain.SpanService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.AsyncUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The trace-keyed spans reads bounded by the span_weeks index before their spans' weeks are indexed, as right after a
 * write or after a registration that failed. Registration is off ({@link SpanWeeksListener} disabled) while the bound
 * is on, so no week is ever indexed: the reads return the span in its trace's weeks, which the bound always admits,
 * and leave the far-future span to the index, while the trace-delete cascade, which has no bound, still removes both.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanWeeksReadBoundUnregisteredTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer);
    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        installPartitionedSpans(databaseAnalyticsFactory);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
                                // Lets the far-future ids through ingestion, as in production.
                                new CustomConfig("uuidValidation.enabled", "false"),
                                // The successor's columns are non-nullable, as the cutover flips this with it.
                                new CustomConfig("databaseAnalyticsDataModel.spanColumnsNonNullable", "true"),
                                // Required for the bound to apply; the disabled listener keeps it from registering.
                                new CustomConfig("databaseAnalyticsDataModel.spanWeeksWriteEnabled", "true"),
                                new CustomConfig("databaseAnalyticsDataModel.spanWeeksReadEnabled", "true")))
                        .disableExtensions(List.of(SpanWeeksListener.class))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private SpanService spanService;
    private SpanWeeksReadFixtures fixtures;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, SpanService spanService) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.spanService = spanService;
        this.fixtures = new SpanWeeksReadFixtures(API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER, factory,
                new ProjectResourceClient(clientSupport, baseUrl, factory),
                traceResourceClient, spanResourceClient,
                new DatasetResourceClient(clientSupport, baseUrl),
                new ExperimentResourceClient(clientSupport, baseUrl, factory), spanService);
    }

    /**
     * Where the near span sits relative to its trace id, which is within the clock skew margin of its week's start:
     * every one of the trace's weeks is admitted.
     */
    private Stream<Arguments> traceSpansPageReturnsTheSpanInTheTracesWeeks() {
        return Stream.of(
                arguments(Named.of("same week", Duration.ofDays(2))),
                arguments(Named.of("previous week, within the clock skew", Duration.ofMinutes(-45))),
                arguments(Named.of("following week", Duration.ofDays(7))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void traceSpansPageReturnsTheSpanInTheTracesWeeks(Duration nearSpanAfterTrace) {
        var traceIdAt = LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(RandomUtils.secure().randomInt(2, 52))
                .atTime(0, 30)
                .toInstant(ZoneOffset.UTC);
        var trace = fixtures.createUnindexedFarFutureTrace(traceIdAt, traceIdAt.plus(nearSpanAfterTrace));

        var actualPage = spanResourceClient.getByTraceIdAndProject(trace.trace().id(), trace.projectName(),
                WORKSPACE_NAME, API_KEY);

        var expectedSpans = fixtures.expectedSpans(List.of(trace.spans().getFirst()));
        SpanAssertions.assertPage(actualPage, 1, expectedSpans.size(), expectedSpans.size());
        SpanAssertions.assertSpan(actualPage.content(), expectedSpans, USER);
    }

    @Test
    void traceSpansStreamReturnsTheSpanOfTheTracesWeek() {
        var trace = fixtures.createUnindexedFarFutureTrace();

        var actualSpans = fixtures.streamTraceSpans(trace);

        var expectedSpans = fixtures.expectedSpans(List.of(trace.spans().getFirst()));
        SpanAssertions.assertSpan(actualSpans, expectedSpans, USER);
    }

    @Test
    void spansByTraceIdsReturnTheSpanOfTheTracesWeek() {
        var trace = fixtures.createUnindexedFarFutureTrace();

        var actualSpanIds = fixtures.getSpanIdsOfDatasetItemFromTrace(trace.trace().id());

        var expectedSpanIds = List.of(trace.spans().getFirst().id());
        assertThat(actualSpanIds).isEqualTo(expectedSpanIds);
    }

    /**
     * The trace-delete cascade is not bound by span_weeks, so it removes spans whose weeks are not indexed. Each span
     * is read back by its own id, whose read derives its week from the id rather than from the index.
     */
    @Test
    void traceDeleteRemovesSpansWhoseWeeksAreNotIndexed() {
        var trace = fixtures.createUnindexedFarFutureTrace();

        traceResourceClient.deleteTrace(trace.trace().id(), WORKSPACE_NAME, API_KEY);

        // The cascade runs on the event bus after the trace delete returns.
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var actualStatuses = trace.spans().stream()
                    .map(span -> {
                        try (var response = spanResourceClient.callGetSpanIdApi(span.id(), WORKSPACE_NAME, API_KEY)) {
                            return response.getStatus();
                        }
                    })
                    .toList();
            assertThat(actualStatuses).containsOnly(HttpStatus.SC_NOT_FOUND);
        });
    }

    /**
     * Read only by the thread online scorers, so through the service. Compared against a this-week copy of the span
     * of the trace's week alone, which the bound admits through its own trace's weeks.
     */
    @Test
    void spansSizeOfTracesCountsTheSpanOfTheTracesWeek() {
        var trace = fixtures.createUnindexedFarFutureTrace();
        var copy = fixtures.createUnindexedThisWeekCopy(trace.toBuilder()
                .spans(List.of(trace.spans().getFirst()))
                .build());
        var expectedSize = spansSize(Set.of(copy.trace().id()));
        assertThat(expectedSize).isPositive();

        var actualSize = spansSize(Set.of(trace.trace().id()));

        assertThat(actualSize).isEqualTo(expectedSize);
    }

    private long spansSize(Set<UUID> traceIds) {
        return spanService.getSpansSizeByTraceIds(traceIds)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, USER, WORKSPACE_ID))
                .block();
    }

    /** The partitioned successor has no public API before the cutover, so it is installed the way the cutover does. */
    private void installPartitionedSpans(DatabaseAnalyticsFactory databaseAnalyticsFactory) {
        TransactionTemplateAsync.create(databaseAnalyticsFactory.build())
                .nonTransaction(connection -> Mono.from(connection
                        .createStatement("EXCHANGE TABLES spans AND spans_local_v2 ON CLUSTER '{cluster}'")
                        .execute()))
                .block();
    }
}

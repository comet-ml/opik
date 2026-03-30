package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.metrics.KpiCardRequest;
import com.comet.opik.api.metrics.KpiCardRequest.EntityType;
import com.comet.opik.api.metrics.KpiCardResponse;
import com.comet.opik.api.metrics.KpiCardResponse.KpiMetricType;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("KPI Cards Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class KpiCardsResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.secure().nextAlphabetic(10);

    private static final Offset<Double> TOLERANCE = Offset.offset(0.1);

    private static final long DURATION_1 = 100;
    private static final long DURATION_2 = 300;
    private static final long DURATION_3 = 500;
    private static final long DURATION_4 = 700;
    private static final double COST_1 = 1.0;
    private static final double COST_2 = 3.0;
    private static final double COST_3 = 5.0;
    private static final double COST_4 = 7.0;

    private static final long FILTER_DURATION_MS = 100;
    private static final double FILTER_COST = 1.0;

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, clickHouseContainer, mysql, zookeeperContainer).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(clickHouseContainer, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                mysql.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), redisContainer.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private IdGenerator idGenerator;
    private String baseURI;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, IdGenerator idGenerator) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.idGenerator = idGenerator;
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);

        ClientSupportUtils.config(client);
        mockTargetWorkspace();
    }

    private void mockTargetWorkspace() {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @ParameterizedTest
    @EnumSource(EntityType.class)
    @DisplayName("returns metrics when data exists in both current and previous periods")
    void bothPeriods(EntityType entityType) {
        mockTargetWorkspace();
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        createEntities(entityType, projectName,
                List.of(DURATION_1, DURATION_2), List.of(true, false), List.of(COST_1, COST_2));

        Instant intervalStart = Instant.now();

        createEntities(entityType, projectName,
                List.of(DURATION_3, DURATION_4), List.of(false, true), List.of(COST_3, COST_4));

        Instant intervalEnd = Instant.now().plus(1, ChronoUnit.MINUTES);

        KpiCardResponse response = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(entityType)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), API_KEY, WORKSPACE_NAME);

        double expectedPrevAvgDuration = (DURATION_1 + DURATION_2) / 2.0;
        double expectedPrevTotalCost = COST_1 + COST_2;
        double expectedCurrAvgDuration = (DURATION_3 + DURATION_4) / 2.0;
        double expectedCurrTotalCost = COST_3 + COST_4;

        assertMetric(response, KpiMetricType.COUNT, 2.0, 2.0);
        assertMetric(response, KpiMetricType.AVG_DURATION, expectedCurrAvgDuration, expectedPrevAvgDuration);
        assertMetric(response, KpiMetricType.TOTAL_COST, expectedCurrTotalCost, expectedPrevTotalCost);

        if (entityType != EntityType.THREADS) {
            assertMetric(response, KpiMetricType.ERRORS, 1.0, 1.0);
        } else {
            assertNoMetric(response, KpiMetricType.ERRORS);
        }
    }

    @ParameterizedTest
    @EnumSource(EntityType.class)
    @DisplayName("returns metrics only for current period when no previous data exists")
    void currentPeriodOnly(EntityType entityType) {
        mockTargetWorkspace();
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        Instant intervalStart = Instant.now();

        createEntities(entityType, projectName,
                List.of(DURATION_1, DURATION_2), List.of(true, false), List.of(COST_1, COST_2));

        Instant intervalEnd = Instant.now().plus(1, ChronoUnit.MINUTES);

        KpiCardResponse response = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(entityType)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), API_KEY, WORKSPACE_NAME);

        double expectedAvgDuration = (DURATION_1 + DURATION_2) / 2.0;
        double expectedTotalCost = COST_1 + COST_2;

        assertMetric(response, KpiMetricType.COUNT, 2.0, 0.0);
        assertMetric(response, KpiMetricType.AVG_DURATION, expectedAvgDuration, null);
        assertMetric(response, KpiMetricType.TOTAL_COST, expectedTotalCost, 0.0);

        if (entityType != EntityType.THREADS) {
            assertMetric(response, KpiMetricType.ERRORS, 1.0, 0.0);
        } else {
            assertNoMetric(response, KpiMetricType.ERRORS);
        }
    }

    @ParameterizedTest
    @EnumSource(EntityType.class)
    @DisplayName("returns metrics only for previous period when no current data exists")
    void previousPeriodOnly(EntityType entityType) {
        mockTargetWorkspace();
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        createEntities(entityType, projectName,
                List.of(DURATION_1, DURATION_2), List.of(true, false), List.of(COST_1, COST_2));

        Instant intervalStart = Instant.now();
        Instant intervalEnd = intervalStart.plus(1, ChronoUnit.MINUTES);

        KpiCardResponse response = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(entityType)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), API_KEY, WORKSPACE_NAME);

        double expectedAvgDuration = (DURATION_1 + DURATION_2) / 2.0;
        double expectedTotalCost = COST_1 + COST_2;

        assertMetric(response, KpiMetricType.COUNT, 0.0, 2.0);
        assertMetric(response, KpiMetricType.AVG_DURATION, null, expectedAvgDuration);
        assertMetric(response, KpiMetricType.TOTAL_COST, 0.0, expectedTotalCost);

        if (entityType != EntityType.THREADS) {
            assertMetric(response, KpiMetricType.ERRORS, 0.0, 1.0);
        } else {
            assertNoMetric(response, KpiMetricType.ERRORS);
        }
    }

    @ParameterizedTest
    @EnumSource(EntityType.class)
    @DisplayName("returns zero counts and null averages when no data exists")
    void noData(EntityType entityType) {
        mockTargetWorkspace();
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        Instant intervalStart = Instant.now();
        Instant intervalEnd = intervalStart.plus(1, ChronoUnit.MINUTES);

        KpiCardResponse response = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(entityType)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), API_KEY, WORKSPACE_NAME);

        assertMetric(response, KpiMetricType.COUNT, 0.0, 0.0);
        assertMetric(response, KpiMetricType.AVG_DURATION, null, null);
        assertMetric(response, KpiMetricType.TOTAL_COST, 0.0, 0.0);

        if (entityType != EntityType.THREADS) {
            assertMetric(response, KpiMetricType.ERRORS, 0.0, 0.0);
        } else {
            assertNoMetric(response, KpiMetricType.ERRORS);
        }
    }

    // === Validation Tests ===

    @ParameterizedTest
    @MethodSource("invalidRequestArguments")
    @DisplayName("returns 422 for invalid requests")
    void invalidRequest(KpiCardRequest request, String expectedError) {
        mockTargetWorkspace();
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        try (var response = projectResourceClient.getKpiCardsRaw(projectId, request, API_KEY, WORKSPACE_NAME)) {
            assertThat(response.getStatus()).isEqualTo(422);
            var error = response.readEntity(ErrorMessage.class);
            assertThat(error.errors()).anyMatch(e -> e.contains(expectedError));
        }
    }

    static Stream<Arguments> invalidRequestArguments() {
        Instant now = Instant.now();
        return Stream.of(
                Arguments.of(
                        KpiCardRequest.builder()
                                .entityType(EntityType.TRACES)
                                .intervalStart(now.plus(1, ChronoUnit.HOURS))
                                .intervalEnd(now)
                                .build(),
                        "intervalStart must be before intervalEnd"),
                Arguments.of(
                        KpiCardRequest.builder()
                                .entityType(null)
                                .intervalStart(now)
                                .intervalEnd(now.plus(1, ChronoUnit.HOURS))
                                .build(),
                        "entityType must not be null"),
                Arguments.of(
                        KpiCardRequest.builder()
                                .entityType(EntityType.SPANS)
                                .intervalStart(null)
                                .intervalEnd(now.plus(1, ChronoUnit.HOURS))
                                .build(),
                        "intervalStart must not be null"),
                Arguments.of(
                        KpiCardRequest.builder()
                                .entityType(EntityType.SPANS)
                                .intervalStart(now)
                                .intervalEnd(null)
                                .build(),
                        "intervalEnd must not be null"));
    }

    // === Span Filter Tests ===

    @ParameterizedTest
    @MethodSource("spanFilterArguments")
    @DisplayName("span filters narrow current period KPI metrics")
    void spanFiltersCurrentPeriod(Function<Span, SpanFilter> filterFn,
            int expectedCurrentCount, int expectedPreviousCount,
            int expectedCurrentErrors, int expectedPreviousErrors) {
        mockTargetWorkspace();
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        createFilterEntities(EntityType.SPANS, projectName, 3);

        Instant intervalStart = Instant.now();

        var currentEntities = createFilterEntities(EntityType.SPANS, projectName, 3);

        Instant intervalEnd = Instant.now().plus(1, ChronoUnit.MINUTES);

        var filter = filterFn.apply(currentEntities.spans().getFirst());

        KpiCardResponse response = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(EntityType.SPANS)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .filters(JsonUtils.writeValueAsString(List.of(filter)))
                .build(), API_KEY, WORKSPACE_NAME);

        assertFilteredMetrics(response, EntityType.SPANS,
                expectedCurrentCount, expectedPreviousCount,
                expectedCurrentErrors, expectedPreviousErrors);
    }

    @ParameterizedTest
    @MethodSource("spanFilterArguments")
    @DisplayName("span filters narrow previous period KPI metrics")
    void spanFiltersPreviousPeriod(Function<Span, SpanFilter> filterFn,
            int expectedCurrentCount, int expectedPreviousCount,
            int expectedCurrentErrors, int expectedPreviousErrors) {
        mockTargetWorkspace();
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var previousEntities = createFilterEntities(EntityType.SPANS, projectName, 3);

        Instant intervalStart = Instant.now();

        createFilterEntities(EntityType.SPANS, projectName, 3);

        Instant intervalEnd = Instant.now().plus(1, ChronoUnit.MINUTES);

        var filter = filterFn.apply(previousEntities.spans().getFirst());

        KpiCardResponse response = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(EntityType.SPANS)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .filters(JsonUtils.writeValueAsString(List.of(filter)))
                .build(), API_KEY, WORKSPACE_NAME);

        assertFilteredMetrics(response, EntityType.SPANS,
                expectedPreviousCount, expectedCurrentCount,
                expectedPreviousErrors, expectedCurrentErrors);
    }

    static Stream<Arguments> spanFilterArguments() {
        return Stream.of(
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.NAME)
                                .operator(Operator.EQUAL)
                                .value(span.name())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.NAME)
                                .operator(Operator.NOT_EQUAL)
                                .value(span.name())
                                .build(),
                        2, 3, 0, 1),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.ID)
                                .operator(Operator.EQUAL)
                                .value(span.id().toString())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.START_TIME)
                                .operator(Operator.EQUAL)
                                .value(span.startTime().toString())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.END_TIME)
                                .operator(Operator.EQUAL)
                                .value(span.endTime().toString())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.INPUT)
                                .operator(Operator.EQUAL)
                                .value(span.input().toString())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.OUTPUT)
                                .operator(Operator.EQUAL)
                                .value(span.output().toString())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.MODEL)
                                .operator(Operator.EQUAL)
                                .value(span.model())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.PROVIDER)
                                .operator(Operator.EQUAL)
                                .value(span.provider())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.METADATA)
                                .operator(Operator.EQUAL)
                                .value(span.metadata().propertyStream().toList().getFirst().getValue().asText())
                                .key(span.metadata().propertyStream().toList().getFirst().getKey())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.TAGS)
                                .operator(Operator.CONTAINS)
                                .value(span.tags().stream().findFirst().orElse(""))
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.FEEDBACK_SCORES)
                                .operator(Operator.EQUAL)
                                .key("score1")
                                .value("1.0")
                                .build(),
                        1, 1, 1, 1),
                Arguments.of(
                        (Function<Span, SpanFilter>) span -> SpanFilter.builder()
                                .field(SpanField.FEEDBACK_SCORES)
                                .operator(Operator.IS_EMPTY)
                                .key("score2")
                                .value("")
                                .build(),
                        2, 2, 0, 0));
    }

    // === Trace Filter Tests ===

    @ParameterizedTest
    @MethodSource("traceFilterArguments")
    @DisplayName("trace filters narrow current period KPI metrics")
    void traceFiltersCurrentPeriod(Function<Trace, TraceFilter> filterFn,
            int expectedCurrentCount, int expectedPreviousCount,
            int expectedCurrentErrors, int expectedPreviousErrors) {
        mockTargetWorkspace();
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        createFilterEntities(EntityType.TRACES, projectName, 3);

        Instant intervalStart = Instant.now();

        var currentEntities = createFilterEntities(EntityType.TRACES, projectName, 3);

        Instant intervalEnd = Instant.now().plus(1, ChronoUnit.MINUTES);

        var filter = filterFn.apply(currentEntities.traces().getFirst());

        KpiCardResponse response = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(EntityType.TRACES)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .filters(JsonUtils.writeValueAsString(List.of(filter)))
                .build(), API_KEY, WORKSPACE_NAME);

        assertFilteredMetrics(response, EntityType.TRACES,
                expectedCurrentCount, expectedPreviousCount,
                expectedCurrentErrors, expectedPreviousErrors);
    }

    @ParameterizedTest
    @MethodSource("traceFilterArguments")
    @DisplayName("trace filters narrow previous period KPI metrics")
    void traceFiltersPreviousPeriod(Function<Trace, TraceFilter> filterFn,
            int expectedCurrentCount, int expectedPreviousCount,
            int expectedCurrentErrors, int expectedPreviousErrors) {
        mockTargetWorkspace();
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var previousEntities = createFilterEntities(EntityType.TRACES, projectName, 3);

        Instant intervalStart = Instant.now();

        createFilterEntities(EntityType.TRACES, projectName, 3);

        Instant intervalEnd = Instant.now().plus(1, ChronoUnit.MINUTES);

        var filter = filterFn.apply(previousEntities.traces().getFirst());

        KpiCardResponse response = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(EntityType.TRACES)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .filters(JsonUtils.writeValueAsString(List.of(filter)))
                .build(), API_KEY, WORKSPACE_NAME);

        assertFilteredMetrics(response, EntityType.TRACES,
                expectedPreviousCount, expectedCurrentCount,
                expectedPreviousErrors, expectedCurrentErrors);
    }

    static Stream<Arguments> traceFilterArguments() {
        return Stream.of(
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.NAME)
                                .operator(Operator.EQUAL)
                                .value(trace.name())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.NAME)
                                .operator(Operator.NOT_EQUAL)
                                .value(trace.name())
                                .build(),
                        2, 3, 0, 1),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.ID)
                                .operator(Operator.EQUAL)
                                .value(trace.id().toString())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.START_TIME)
                                .operator(Operator.EQUAL)
                                .value(trace.startTime().toString())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.END_TIME)
                                .operator(Operator.EQUAL)
                                .value(trace.endTime().toString())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.INPUT)
                                .operator(Operator.EQUAL)
                                .value(trace.input().toString())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.OUTPUT)
                                .operator(Operator.EQUAL)
                                .value(trace.output().toString())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.METADATA)
                                .operator(Operator.EQUAL)
                                .value(trace.metadata().propertyStream().toList().getFirst().getValue().asText())
                                .key(trace.metadata().propertyStream().toList().getFirst().getKey())
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.TAGS)
                                .operator(Operator.CONTAINS)
                                .value(trace.tags().stream().findFirst().orElse(""))
                                .build(),
                        1, 0, 1, 0),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.FEEDBACK_SCORES)
                                .operator(Operator.EQUAL)
                                .key("score1")
                                .value("1.0")
                                .build(),
                        1, 1, 1, 1),
                Arguments.of(
                        (Function<Trace, TraceFilter>) trace -> TraceFilter.builder()
                                .field(TraceField.FEEDBACK_SCORES)
                                .operator(Operator.IS_EMPTY)
                                .key("score2")
                                .value("")
                                .build(),
                        2, 2, 0, 0));
    }

    record FilterEntities(List<Trace> traces, List<Span> spans, List<String> threadIds) {
    }

    private FilterEntities createFilterEntities(EntityType entityType, String projectName, int count) {
        List<String> threadIds = new ArrayList<>();
        List<Trace> traces = new ArrayList<>();
        List<Span> spans = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Instant now = Instant.now();
            boolean hasError = (i == 0);

            String threadId = entityType == EntityType.THREADS
                    ? RandomStringUtils.secure().nextAlphabetic(10)
                    : null;
            if (threadId != null) {
                threadIds.add(threadId);
            }

            long spanDurationMs = entityType == EntityType.SPANS ? FILTER_DURATION_MS : 50;

            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId(now))
                    .projectName(projectName)
                    .startTime(now)
                    .endTime(now.plus(FILTER_DURATION_MS, ChronoUnit.MILLIS))
                    .errorInfo(entityType == EntityType.TRACES && hasError ? buildErrorInfo() : null)
                    .threadId(threadId)
                    .build();
            traces.add(trace);

            spans.add(factory.manufacturePojo(Span.class).toBuilder()
                    .id(idGenerator.generateId(now.plus(1, ChronoUnit.MILLIS)))
                    .traceId(trace.id())
                    .projectName(projectName)
                    .startTime(now)
                    .endTime(now.plus(spanDurationMs, ChronoUnit.MILLIS))
                    .totalEstimatedCost(BigDecimal.valueOf(FILTER_COST))
                    .errorInfo(entityType == EntityType.SPANS && hasError ? buildErrorInfo() : null)
                    .build());
        }

        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);

        if (entityType == EntityType.THREADS) {
            Mono.delay(Duration.ofMillis(100)).block();
            traceResourceClient.closeTraceThreads(Set.copyOf(threadIds), null, projectName, API_KEY, WORKSPACE_NAME);

            traceResourceClient.threadFeedbackScores(List.of(
                    factory.manufacturePojo(FeedbackScoreBatchItemThread.class).toBuilder()
                            .threadId(threadIds.getFirst())
                            .name("score1")
                            .value(BigDecimal.ONE)
                            .projectName(projectName)
                            .build(),
                    factory.manufacturePojo(FeedbackScoreBatchItemThread.class).toBuilder()
                            .threadId(threadIds.getFirst())
                            .name("score2")
                            .value(BigDecimal.valueOf(2.0))
                            .projectName(projectName)
                            .build()),
                    API_KEY, WORKSPACE_NAME);
        } else if (entityType == EntityType.SPANS) {
            spanResourceClient.feedbackScores(List.of(
                    factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(spans.getFirst().id())
                            .name("score1")
                            .value(BigDecimal.ONE)
                            .projectName(projectName)
                            .build(),
                    factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(spans.getFirst().id())
                            .name("score2")
                            .value(BigDecimal.valueOf(2.0))
                            .projectName(projectName)
                            .build()),
                    API_KEY, WORKSPACE_NAME);
        } else {
            traceResourceClient.feedbackScores(List.of(
                    factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(traces.getFirst().id())
                            .name("score1")
                            .value(BigDecimal.ONE)
                            .projectName(projectName)
                            .build(),
                    factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                            .id(traces.getFirst().id())
                            .name("score2")
                            .value(BigDecimal.valueOf(2.0))
                            .projectName(projectName)
                            .build()),
                    API_KEY, WORKSPACE_NAME);
        }

        return new FilterEntities(traces, spans, threadIds);
    }

    // === Thread Filter Tests ===

    @ParameterizedTest
    @MethodSource("threadFilterArguments")
    @DisplayName("thread filters narrow current period KPI metrics")
    void threadFiltersCurrentPeriod(Function<TraceThread, TraceThreadFilter> filterFn,
            int expectedCurrentCount, int expectedPreviousCount) {
        mockTargetWorkspace();
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        createFilterEntities(EntityType.THREADS, projectName, 3);

        Instant intervalStart = Instant.now();

        var currentEntities = createFilterEntities(EntityType.THREADS, projectName, 3);

        Instant intervalEnd = Instant.now().plus(1, ChronoUnit.MINUTES);

        var thread = traceResourceClient.getTraceThread(
                currentEntities.threadIds().getFirst(), projectId, API_KEY, WORKSPACE_NAME);
        var filter = filterFn.apply(thread);

        KpiCardResponse response = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(EntityType.THREADS)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .filters(JsonUtils.writeValueAsString(List.of(filter)))
                .build(), API_KEY, WORKSPACE_NAME);

        assertFilteredMetrics(response, EntityType.THREADS,
                expectedCurrentCount, expectedPreviousCount, 0, 0);
    }

    @ParameterizedTest
    @MethodSource("threadFilterArguments")
    @DisplayName("thread filters narrow previous period KPI metrics")
    void threadFiltersPreviousPeriod(Function<TraceThread, TraceThreadFilter> filterFn,
            int expectedCurrentCount, int expectedPreviousCount) {
        mockTargetWorkspace();
        var projectName = RandomStringUtils.secure().nextAlphabetic(10);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var previousEntities = createFilterEntities(EntityType.THREADS, projectName, 3);

        Instant intervalStart = Instant.now();

        createFilterEntities(EntityType.THREADS, projectName, 3);

        Instant intervalEnd = Instant.now().plus(1, ChronoUnit.MINUTES);

        var thread = traceResourceClient.getTraceThread(
                previousEntities.threadIds().getFirst(), projectId, API_KEY, WORKSPACE_NAME);
        var filter = filterFn.apply(thread);

        KpiCardResponse response = projectResourceClient.getKpiCards(projectId, KpiCardRequest.builder()
                .entityType(EntityType.THREADS)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .filters(JsonUtils.writeValueAsString(List.of(filter)))
                .build(), API_KEY, WORKSPACE_NAME);

        assertFilteredMetrics(response, EntityType.THREADS,
                expectedPreviousCount, expectedCurrentCount, 0, 0);
    }

    static Stream<Arguments> threadFilterArguments() {
        return Stream.of(
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.ID)
                                .operator(Operator.EQUAL)
                                .value(thread.id())
                                .build(),
                        1, 0),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.ID)
                                .operator(Operator.NOT_EQUAL)
                                .value(thread.id())
                                .build(),
                        2, 3),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.START_TIME)
                                .operator(Operator.EQUAL)
                                .value(thread.startTime().toString())
                                .build(),
                        1, 0),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.END_TIME)
                                .operator(Operator.EQUAL)
                                .value(thread.endTime().toString())
                                .build(),
                        1, 0),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.DURATION)
                                .operator(Operator.GREATER_THAN)
                                .value("0")
                                .build(),
                        3, 3),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.CREATED_AT)
                                .operator(Operator.GREATER_THAN)
                                .value(thread.createdAt().minusSeconds(1).toString())
                                .build(),
                        3, 3),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.LAST_UPDATED_AT)
                                .operator(Operator.GREATER_THAN)
                                .value(thread.lastUpdatedAt().minusSeconds(1).toString())
                                .build(),
                        3, 3),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.FEEDBACK_SCORES)
                                .operator(Operator.EQUAL)
                                .key("score1")
                                .value("1.0")
                                .build(),
                        1, 1),
                Arguments.of(
                        (Function<TraceThread, TraceThreadFilter>) thread -> TraceThreadFilter.builder()
                                .field(TraceThreadField.FEEDBACK_SCORES)
                                .operator(Operator.IS_EMPTY)
                                .key("score2")
                                .value("")
                                .build(),
                        2, 2));
    }

    // === Entity Creation Helper ===

    private void createEntities(EntityType entityType, String projectName,
            List<Long> durationsMs, List<Boolean> hasErrors, List<Double> costs) {
        List<String> threadIds = new ArrayList<>();
        List<Trace> traces = new ArrayList<>();
        List<Span> spans = new ArrayList<>();

        for (int i = 0; i < durationsMs.size(); i++) {
            Instant now = Instant.now();

            String threadId = entityType == EntityType.THREADS
                    ? RandomStringUtils.secure().nextAlphabetic(10)
                    : null;
            if (threadId != null) {
                threadIds.add(threadId);
            }

            boolean traceHasError = entityType == EntityType.TRACES && hasErrors.get(i);
            boolean spanHasError = entityType == EntityType.SPANS && hasErrors.get(i);
            long spanDurationMs = entityType == EntityType.SPANS ? durationsMs.get(i) : 50;

            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId(now))
                    .projectName(projectName)
                    .startTime(now)
                    .endTime(now.plus(durationsMs.get(i), ChronoUnit.MILLIS))
                    .errorInfo(traceHasError ? buildErrorInfo() : null)
                    .threadId(threadId)
                    .build();
            traces.add(trace);

            spans.add(factory.manufacturePojo(Span.class).toBuilder()
                    .id(idGenerator.generateId(now.plus(1, ChronoUnit.MILLIS)))
                    .traceId(trace.id())
                    .projectName(projectName)
                    .startTime(now)
                    .endTime(now.plus(spanDurationMs, ChronoUnit.MILLIS))
                    .totalEstimatedCost(BigDecimal.valueOf(costs.get(i)))
                    .errorInfo(spanHasError ? buildErrorInfo() : null)
                    .build());
        }

        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);

        if (entityType == EntityType.THREADS) {
            Mono.delay(Duration.ofMillis(100)).block();
            traceResourceClient.closeTraceThreads(Set.copyOf(threadIds), null, projectName, API_KEY, WORKSPACE_NAME);
        }
    }

    private ErrorInfo buildErrorInfo() {
        return ErrorInfo.builder()
                .exceptionType("TestError")
                .message("test error")
                .traceback("traceback")
                .build();
    }

    // === Assertion Helpers ===

    private void assertMetric(KpiCardResponse response, KpiMetricType type,
            Double expectedCurrent, Double expectedPrevious) {
        var metric = response.stats().stream()
                .filter(m -> m.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Metric '%s' not found in response".formatted(type)));

        if (expectedCurrent == null) {
            assertThat(metric.currentValue())
                    .as("current value for '%s'", type)
                    .isNull();
        } else {
            assertThat(metric.currentValue())
                    .as("current value for '%s'", type)
                    .isNotNull();
            assertThat(metric.currentValue().doubleValue())
                    .as("current value for '%s'", type)
                    .isCloseTo(expectedCurrent, TOLERANCE);
        }

        if (expectedPrevious == null) {
            assertThat(metric.previousValue())
                    .as("previous value for '%s'", type)
                    .isNull();
        } else {
            assertThat(metric.previousValue())
                    .as("previous value for '%s'", type)
                    .isNotNull();
            assertThat(metric.previousValue().doubleValue())
                    .as("previous value for '%s'", type)
                    .isCloseTo(expectedPrevious, TOLERANCE);
        }
    }

    private void assertFilteredMetrics(KpiCardResponse response, EntityType entityType,
            int currentCount, int previousCount,
            int currentErrors, int previousErrors) {
        assertMetric(response, KpiMetricType.COUNT, (double) currentCount, (double) previousCount);

        if (entityType != EntityType.THREADS) {
            assertMetric(response, KpiMetricType.ERRORS, (double) currentErrors, (double) previousErrors);
        }

        assertMetric(response, KpiMetricType.AVG_DURATION,
                currentCount > 0 ? (double) FILTER_DURATION_MS : null,
                previousCount > 0 ? (double) FILTER_DURATION_MS : null);

        assertMetric(response, KpiMetricType.TOTAL_COST,
                currentCount > 0 ? FILTER_COST * currentCount : 0.0,
                previousCount > 0 ? FILTER_COST * previousCount : 0.0);
    }

    private void assertNoMetric(KpiCardResponse response, KpiMetricType type) {
        boolean found = response.stats().stream().anyMatch(m -> m.type() == type);
        assertThat(found)
                .as("metric '%s' should not be present", type)
                .isFalse();
    }
}

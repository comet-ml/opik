package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
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
import org.junit.jupiter.params.provider.EnumSource;
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
        double expectedPrevAvgCost = (COST_1 + COST_2) / 2.0;
        double expectedCurrAvgDuration = (DURATION_3 + DURATION_4) / 2.0;
        double expectedCurrAvgCost = (COST_3 + COST_4) / 2.0;

        assertMetric(response, KpiMetricType.COUNT, 2.0, 2.0);
        assertMetric(response, KpiMetricType.AVG_DURATION, expectedCurrAvgDuration, expectedPrevAvgDuration);
        assertMetric(response, KpiMetricType.AVG_COST, expectedCurrAvgCost, expectedPrevAvgCost);

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
        double expectedAvgCost = (COST_1 + COST_2) / 2.0;

        assertMetric(response, KpiMetricType.COUNT, 2.0, 0.0);
        assertMetric(response, KpiMetricType.AVG_DURATION, expectedAvgDuration, null);
        assertMetric(response, KpiMetricType.AVG_COST, expectedAvgCost, null);

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
        double expectedAvgCost = (COST_1 + COST_2) / 2.0;

        assertMetric(response, KpiMetricType.COUNT, 0.0, 2.0);
        assertMetric(response, KpiMetricType.AVG_DURATION, null, expectedAvgDuration);
        assertMetric(response, KpiMetricType.AVG_COST, null, expectedAvgCost);

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
        assertMetric(response, KpiMetricType.AVG_COST, null, null);

        if (entityType != EntityType.THREADS) {
            assertMetric(response, KpiMetricType.ERRORS, 0.0, 0.0);
        } else {
            assertNoMetric(response, KpiMetricType.ERRORS);
        }
    }

    // === Entity Creation Helpers ===

    private void createEntities(EntityType entityType, String projectName,
            List<Long> durationsMs, List<Boolean> hasErrors, List<Double> costs) {
        switch (entityType) {
            case TRACES -> createTracesWithCostSpans(projectName, durationsMs, hasErrors, costs);
            case SPANS -> createStandaloneSpans(projectName, durationsMs, hasErrors, costs);
            case THREADS -> createThreads(projectName, durationsMs, costs);
        }
    }

    private void createTracesWithCostSpans(String projectName,
            List<Long> durationsMs, List<Boolean> hasErrors, List<Double> costs) {
        List<Trace> traces = new ArrayList<>();
        List<Span> spans = new ArrayList<>();

        for (int i = 0; i < durationsMs.size(); i++) {
            Instant now = Instant.now();

            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId(now))
                    .projectName(projectName)
                    .startTime(now)
                    .endTime(now.plus(durationsMs.get(i), ChronoUnit.MILLIS))
                    .errorInfo(hasErrors.get(i) ? buildErrorInfo() : null)
                    .threadId(null)
                    .build();
            traces.add(trace);

            spans.add(factory.manufacturePojo(Span.class).toBuilder()
                    .id(idGenerator.generateId(now.plus(1, ChronoUnit.MILLIS)))
                    .traceId(trace.id())
                    .projectName(projectName)
                    .startTime(now)
                    .endTime(now.plus(50, ChronoUnit.MILLIS))
                    .totalEstimatedCost(BigDecimal.valueOf(costs.get(i)))
                    .errorInfo(null)
                    .build());
        }

        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
    }

    private void createStandaloneSpans(String projectName,
            List<Long> durationsMs, List<Boolean> hasErrors, List<Double> costs) {
        List<Trace> traces = new ArrayList<>();
        List<Span> spans = new ArrayList<>();

        for (int i = 0; i < durationsMs.size(); i++) {
            Instant now = Instant.now();

            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId(now))
                    .projectName(projectName)
                    .startTime(now)
                    .endTime(now.plus(durationsMs.get(i), ChronoUnit.MILLIS))
                    .errorInfo(null)
                    .threadId(null)
                    .build();
            traces.add(trace);

            spans.add(factory.manufacturePojo(Span.class).toBuilder()
                    .id(idGenerator.generateId(now.plus(1, ChronoUnit.MILLIS)))
                    .traceId(trace.id())
                    .projectName(projectName)
                    .startTime(now)
                    .endTime(now.plus(durationsMs.get(i), ChronoUnit.MILLIS))
                    .errorInfo(hasErrors.get(i) ? buildErrorInfo() : null)
                    .totalEstimatedCost(BigDecimal.valueOf(costs.get(i)))
                    .build());
        }

        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
    }

    private void createThreads(String projectName,
            List<Long> durationsMs, List<Double> costs) {
        List<String> threadIds = new ArrayList<>();
        List<Trace> traces = new ArrayList<>();
        List<Span> spans = new ArrayList<>();

        for (int i = 0; i < durationsMs.size(); i++) {
            String threadId = RandomStringUtils.secure().nextAlphabetic(10);
            threadIds.add(threadId);

            Instant now = Instant.now();

            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId(now))
                    .projectName(projectName)
                    .threadId(threadId)
                    .startTime(now)
                    .endTime(now.plus(durationsMs.get(i), ChronoUnit.MILLIS))
                    .errorInfo(null)
                    .build();
            traces.add(trace);

            spans.add(factory.manufacturePojo(Span.class).toBuilder()
                    .id(idGenerator.generateId(now.plus(1, ChronoUnit.MILLIS)))
                    .traceId(trace.id())
                    .projectName(projectName)
                    .startTime(now)
                    .endTime(now.plus(50, ChronoUnit.MILLIS))
                    .totalEstimatedCost(BigDecimal.valueOf(costs.get(i)))
                    .errorInfo(null)
                    .build());
        }

        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);

        Mono.delay(Duration.ofMillis(100)).block();
        traceResourceClient.closeTraceThreads(Set.copyOf(threadIds), null, projectName, API_KEY, WORKSPACE_NAME);
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

    private void assertNoMetric(KpiCardResponse response, KpiMetricType type) {
        boolean found = response.stats().stream().anyMatch(m -> m.type() == type);
        assertThat(found)
                .as("metric '%s' should not be present", type)
                .isFalse();
    }
}

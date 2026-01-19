package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.Trace;
import com.comet.opik.api.metrics.BreakdownConfig;
import com.comet.opik.api.metrics.BreakdownField;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.GuardrailsGenerator;
import com.comet.opik.api.resources.utils.resources.GuardrailsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectMetricsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.GuardrailResult;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Project Metrics With Breakdown Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ProjectMetricsWithBreakdownResourceTest {
    public static final String URL_TEMPLATE = "%s/v1/private/projects/%s/metrics";

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.secure().nextAlphabetic(10);
    private static final Random RANDOM = new Random();

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, clickHouseContainer, mysql, zookeeperContainer).join();

        wireMock = com.comet.opik.api.resources.utils.WireMockUtils.startWireMock();

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
    private ClientSupport client;
    private ProjectMetricsResourceClient projectMetricsResourceClient;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private GuardrailsResourceClient guardrailsResourceClient;
    private GuardrailsGenerator guardrailsGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, IdGenerator idGenerator) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.projectMetricsResourceClient = new ProjectMetricsResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.guardrailsResourceClient = new GuardrailsResourceClient(client, baseURI);
        this.guardrailsGenerator = new GuardrailsGenerator();

        ClientSupportUtils.config(client);

        mockTargetWorkspace();
        this.idGenerator = idGenerator;
    }

    private void mockTargetWorkspace() {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    // ==================== BREAKDOWN FIELD PROVIDERS ====================

    /**
     * Valid breakdown fields for Trace metrics: PROJECT_ID, TAGS, METADATA, NAME, ERROR_INFO
     */
    static Stream<Arguments> traceValidBreakdownFields() {
        return Stream.of(
                Arguments.of(BreakdownField.PROJECT_ID),
                Arguments.of(BreakdownField.TAGS),
                Arguments.of(BreakdownField.METADATA),
                Arguments.of(BreakdownField.NAME),
                Arguments.of(BreakdownField.ERROR_INFO));
    }

    /**
     * Invalid breakdown fields for Trace metrics: MODEL, PROVIDER, TYPE (Span-only fields)
     */
    static Stream<Arguments> traceInvalidBreakdownFields() {
        return Stream.of(
                Arguments.of(BreakdownField.MODEL),
                Arguments.of(BreakdownField.PROVIDER),
                Arguments.of(BreakdownField.TYPE));
    }

    /**
     * Valid breakdown fields for Thread metrics: PROJECT_ID, TAGS
     */
    static Stream<Arguments> threadValidBreakdownFields() {
        return Stream.of(
                Arguments.of(BreakdownField.PROJECT_ID),
                Arguments.of(BreakdownField.TAGS));
    }

    /**
     * Invalid breakdown fields for Thread metrics: NAME, ERROR_INFO, MODEL, PROVIDER, TYPE
     * Note: METADATA is excluded because it triggers "metadata_key required" error first
     */
    static Stream<Arguments> threadInvalidBreakdownFields() {
        return Stream.of(
                Arguments.of(BreakdownField.NAME),
                Arguments.of(BreakdownField.ERROR_INFO),
                Arguments.of(BreakdownField.MODEL),
                Arguments.of(BreakdownField.PROVIDER),
                Arguments.of(BreakdownField.TYPE));
    }

    /**
     * Valid breakdown fields for Span metrics: all fields
     */
    static Stream<Arguments> spanValidBreakdownFields() {
        return Stream.of(
                Arguments.of(BreakdownField.PROJECT_ID),
                Arguments.of(BreakdownField.TAGS),
                Arguments.of(BreakdownField.METADATA),
                Arguments.of(BreakdownField.NAME),
                Arguments.of(BreakdownField.ERROR_INFO),
                Arguments.of(BreakdownField.MODEL),
                Arguments.of(BreakdownField.PROVIDER),
                Arguments.of(BreakdownField.TYPE));
    }

    /**
     * Invalid breakdown fields for Span metrics: METADATA without metadata_key
     */
    static Stream<Arguments> spanInvalidBreakdownFields() {
        return Stream.of(Arguments.of(BreakdownField.METADATA));
    }

    // ==================== TRACE METRICS ====================

    @Nested
    @DisplayName("Trace Count with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TraceCountWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create traces in 2 time buckets with 2 distinct groups (use past hours to ensure data is in range)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createTracesForBreakdown(projectName, bucket1, breakdownField, "group-a", 2);
            createTracesForBreakdown(projectName, bucket2, breakdownField, "group-a", 2);
            createTracesForBreakdown(projectName, bucket1, breakdownField, "group-b", 2);
            createTracesForBreakdown(projectName, bucket2, breakdownField, "group-b", 2);

            var requestBuilder = ProjectMetricRequest.builder()
                    .metricType(MetricType.TRACE_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now());

            if (breakdownField == BreakdownField.METADATA) {
                requestBuilder.breakdown(BreakdownConfig.builder()
                        .field(breakdownField)
                        .metadataKey("env")
                        .build());
            } else {
                requestBuilder.breakdown(BreakdownConfig.builder().field(breakdownField).build());
            }

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, requestBuilder.build(),
                    Integer.class, API_KEY, WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.TRACE_COUNT);
            assertThat(response.results()).isNotEmpty();
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceInvalidBreakdownFields")
        @DisplayName("invalidParameters: unsupported breakdown field returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.TRACE_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("not compatible with metric type");
            }
        }
    }

    @Nested
    @DisplayName("Duration with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DurationWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create traces with duration in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createTracesWithDurationForBreakdown(projectName, bucket1, breakdownField, "group-a", 2);
            createTracesWithDurationForBreakdown(projectName, bucket2, breakdownField, "group-a", 2);
            createTracesWithDurationForBreakdown(projectName, bucket1, breakdownField, "group-b", 2);
            createTracesWithDurationForBreakdown(projectName, bucket2, breakdownField, "group-b", 2);

            var requestBuilder = ProjectMetricRequest.builder()
                    .metricType(MetricType.DURATION)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now());

            if (breakdownField == BreakdownField.METADATA) {
                requestBuilder.breakdown(BreakdownConfig.builder()
                        .field(breakdownField)
                        .metadataKey("env")
                        .build());
            } else {
                requestBuilder.breakdown(BreakdownConfig.builder().field(breakdownField).build());
            }

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, requestBuilder.build(),
                    Double.class, API_KEY, WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.DURATION);
            assertThat(response.results()).isNotEmpty();
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceInvalidBreakdownFields")
        @DisplayName("invalidParameters: unsupported breakdown field returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.DURATION)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("not compatible with metric type");
            }
        }
    }

    @Nested
    @DisplayName("Token Usage with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TokenUsageWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create traces with spans that have usage data in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createTracesWithSpansForTokenUsage(projectName, bucket1, breakdownField, "group-a", 2);
            createTracesWithSpansForTokenUsage(projectName, bucket2, breakdownField, "group-a", 2);
            createTracesWithSpansForTokenUsage(projectName, bucket1, breakdownField, "group-b", 2);
            createTracesWithSpansForTokenUsage(projectName, bucket2, breakdownField, "group-b", 2);

            var requestBuilder = ProjectMetricRequest.builder()
                    .metricType(MetricType.TOKEN_USAGE)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now());

            if (breakdownField == BreakdownField.METADATA) {
                requestBuilder.breakdown(BreakdownConfig.builder()
                        .field(breakdownField)
                        .metadataKey("env")
                        .build());
            } else {
                requestBuilder.breakdown(BreakdownConfig.builder().field(breakdownField).build());
            }

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, requestBuilder.build(),
                    Integer.class, API_KEY, WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.TOKEN_USAGE);
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceInvalidBreakdownFields")
        @DisplayName("invalidParameters: unsupported breakdown field returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.TOKEN_USAGE)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("not compatible with metric type");
            }
        }
    }

    @Nested
    @DisplayName("Cost with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CostWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create traces with spans that have cost data in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createTracesWithSpansForCost(projectName, bucket1, breakdownField, "group-a", 2);
            createTracesWithSpansForCost(projectName, bucket2, breakdownField, "group-a", 2);
            createTracesWithSpansForCost(projectName, bucket1, breakdownField, "group-b", 2);
            createTracesWithSpansForCost(projectName, bucket2, breakdownField, "group-b", 2);

            var requestBuilder = ProjectMetricRequest.builder()
                    .metricType(MetricType.COST)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now());

            if (breakdownField == BreakdownField.METADATA) {
                requestBuilder.breakdown(BreakdownConfig.builder()
                        .field(breakdownField)
                        .metadataKey("env")
                        .build());
            } else {
                requestBuilder.breakdown(BreakdownConfig.builder().field(breakdownField).build());
            }

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, requestBuilder.build(),
                    Double.class, API_KEY, WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.COST);
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceInvalidBreakdownFields")
        @DisplayName("invalidParameters: unsupported breakdown field returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.COST)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("not compatible with metric type");
            }
        }
    }

    @Nested
    @DisplayName("Feedback Scores with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FeedbackScoresWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create traces with feedback scores in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createTracesWithFeedbackScores(projectName, bucket1, breakdownField, "group-a", 2);
            createTracesWithFeedbackScores(projectName, bucket2, breakdownField, "group-a", 2);
            createTracesWithFeedbackScores(projectName, bucket1, breakdownField, "group-b", 2);
            createTracesWithFeedbackScores(projectName, bucket2, breakdownField, "group-b", 2);

            var requestBuilder = ProjectMetricRequest.builder()
                    .metricType(MetricType.FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now());

            if (breakdownField == BreakdownField.METADATA) {
                requestBuilder.breakdown(BreakdownConfig.builder()
                        .field(breakdownField)
                        .metadataKey("env")
                        .build());
            } else {
                requestBuilder.breakdown(BreakdownConfig.builder().field(breakdownField).build());
            }

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, requestBuilder.build(),
                    Double.class, API_KEY, WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.FEEDBACK_SCORES);
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceInvalidBreakdownFields")
        @DisplayName("invalidParameters: unsupported breakdown field returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("not compatible with metric type");
            }
        }
    }

    @Nested
    @DisplayName("Guardrails Failed Count with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GuardrailsFailedCountWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create traces with guardrails in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createTracesWithGuardrails(projectName, bucket1, breakdownField, "group-a", 2);
            createTracesWithGuardrails(projectName, bucket2, breakdownField, "group-a", 2);
            createTracesWithGuardrails(projectName, bucket1, breakdownField, "group-b", 2);
            createTracesWithGuardrails(projectName, bucket2, breakdownField, "group-b", 2);

            var requestBuilder = ProjectMetricRequest.builder()
                    .metricType(MetricType.GUARDRAILS_FAILED_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now());

            if (breakdownField == BreakdownField.METADATA) {
                requestBuilder.breakdown(BreakdownConfig.builder()
                        .field(breakdownField)
                        .metadataKey("env")
                        .build());
            } else {
                requestBuilder.breakdown(BreakdownConfig.builder().field(breakdownField).build());
            }

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, requestBuilder.build(),
                    Integer.class, API_KEY, WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.GUARDRAILS_FAILED_COUNT);
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#traceInvalidBreakdownFields")
        @DisplayName("invalidParameters: unsupported breakdown field returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.GUARDRAILS_FAILED_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("not compatible with metric type");
            }
        }
    }

    // ==================== THREAD METRICS ====================

    @Nested
    @DisplayName("Thread Count with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ThreadCountWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#threadValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create threads in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createThreadsForBreakdown(projectName, bucket1, breakdownField, "group-a", 2);
            createThreadsForBreakdown(projectName, bucket2, breakdownField, "group-a", 2);
            createThreadsForBreakdown(projectName, bucket1, breakdownField, "group-b", 2);
            createThreadsForBreakdown(projectName, bucket2, breakdownField, "group-b", 2);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(breakdownField).build())
                    .build();

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, request, Integer.class, API_KEY,
                    WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.THREAD_COUNT);
            assertThat(response.results()).isNotEmpty();
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#threadInvalidBreakdownFields")
        @DisplayName("invalidParameters: unsupported breakdown field returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("not compatible with metric type");
            }
        }
    }

    @Nested
    @DisplayName("Thread Duration with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ThreadDurationWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#threadValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create threads with duration in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createThreadsWithDurationForBreakdown(projectName, bucket1, breakdownField, "group-a", 2);
            createThreadsWithDurationForBreakdown(projectName, bucket2, breakdownField, "group-a", 2);
            createThreadsWithDurationForBreakdown(projectName, bucket1, breakdownField, "group-b", 2);
            createThreadsWithDurationForBreakdown(projectName, bucket2, breakdownField, "group-b", 2);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_DURATION)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(breakdownField).build())
                    .build();

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, request, Double.class, API_KEY,
                    WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.THREAD_DURATION);
            assertThat(response.results()).isNotEmpty();
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#threadInvalidBreakdownFields")
        @DisplayName("invalidParameters: unsupported breakdown field returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_DURATION)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("not compatible with metric type");
            }
        }
    }

    @Nested
    @DisplayName("Thread Feedback Scores with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ThreadFeedbackScoresWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#threadValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create threads with feedback scores in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createThreadsWithFeedbackScores(projectName, bucket1, breakdownField, "group-a", 2);
            createThreadsWithFeedbackScores(projectName, bucket2, breakdownField, "group-a", 2);
            createThreadsWithFeedbackScores(projectName, bucket1, breakdownField, "group-b", 2);
            createThreadsWithFeedbackScores(projectName, bucket2, breakdownField, "group-b", 2);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(breakdownField).build())
                    .build();

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, request, Double.class, API_KEY,
                    WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.THREAD_FEEDBACK_SCORES);
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#threadInvalidBreakdownFields")
        @DisplayName("invalidParameters: unsupported breakdown field returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.THREAD_FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("not compatible with metric type");
            }
        }
    }

    // ==================== SPAN METRICS ====================

    @Nested
    @DisplayName("Span Count with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanCountWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#spanValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create spans in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createSpansForBreakdown(projectName, bucket1, breakdownField, "group-a", 2);
            createSpansForBreakdown(projectName, bucket2, breakdownField, "group-a", 2);
            createSpansForBreakdown(projectName, bucket1, breakdownField, "group-b", 2);
            createSpansForBreakdown(projectName, bucket2, breakdownField, "group-b", 2);

            var requestBuilder = ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now());

            if (breakdownField == BreakdownField.METADATA) {
                requestBuilder.breakdown(BreakdownConfig.builder()
                        .field(breakdownField)
                        .metadataKey("env")
                        .build());
            } else {
                requestBuilder.breakdown(BreakdownConfig.builder().field(breakdownField).build());
            }

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, requestBuilder.build(),
                    Integer.class, API_KEY, WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.SPAN_COUNT);
            assertThat(response.results()).isNotEmpty();
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#spanInvalidBreakdownFields")
        @DisplayName("invalidParameters: metadata without key returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // METADATA requires metadata_key - this tests the metadata validation
            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_COUNT)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("metadata_key is required");
            }
        }
    }

    @Nested
    @DisplayName("Span Duration with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanDurationWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#spanValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create spans with duration in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createSpansWithDurationForBreakdown(projectName, bucket1, breakdownField, "group-a", 2);
            createSpansWithDurationForBreakdown(projectName, bucket2, breakdownField, "group-a", 2);
            createSpansWithDurationForBreakdown(projectName, bucket1, breakdownField, "group-b", 2);
            createSpansWithDurationForBreakdown(projectName, bucket2, breakdownField, "group-b", 2);

            var requestBuilder = ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_DURATION)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now());

            if (breakdownField == BreakdownField.METADATA) {
                requestBuilder.breakdown(BreakdownConfig.builder()
                        .field(breakdownField)
                        .metadataKey("env")
                        .build());
            } else {
                requestBuilder.breakdown(BreakdownConfig.builder().field(breakdownField).build());
            }

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, requestBuilder.build(),
                    Double.class, API_KEY, WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.SPAN_DURATION);
            assertThat(response.results()).isNotEmpty();
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#spanInvalidBreakdownFields")
        @DisplayName("invalidParameters: metadata without key returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_DURATION)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("metadata_key is required");
            }
        }
    }

    @Nested
    @DisplayName("Span Token Usage with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanTokenUsageWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#spanValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create spans with usage data in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createSpansWithUsageForBreakdown(projectName, bucket1, breakdownField, "group-a", 2);
            createSpansWithUsageForBreakdown(projectName, bucket2, breakdownField, "group-a", 2);
            createSpansWithUsageForBreakdown(projectName, bucket1, breakdownField, "group-b", 2);
            createSpansWithUsageForBreakdown(projectName, bucket2, breakdownField, "group-b", 2);

            var requestBuilder = ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_TOKEN_USAGE)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now());

            if (breakdownField == BreakdownField.METADATA) {
                requestBuilder.breakdown(BreakdownConfig.builder()
                        .field(breakdownField)
                        .metadataKey("env")
                        .build());
            } else {
                requestBuilder.breakdown(BreakdownConfig.builder().field(breakdownField).build());
            }

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, requestBuilder.build(),
                    Integer.class, API_KEY, WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.SPAN_TOKEN_USAGE);
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#spanInvalidBreakdownFields")
        @DisplayName("invalidParameters: metadata without key returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_TOKEN_USAGE)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("metadata_key is required");
            }
        }

    }

    @Nested
    @DisplayName("Span Feedback Scores with Breakdown")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SpanFeedbackScoresWithBreakdownTest {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#spanValidBreakdownFields")
        @DisplayName("happyPath: valid breakdown field returns grouped results with multiple data points")
        void happyPath(BreakdownField breakdownField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create spans with feedback scores in 2 time buckets (use past hours)
            Instant bucket1 = subtract(marker, 2, interval);
            Instant bucket2 = subtract(marker, 1, interval);
            createSpansWithFeedbackScores(projectName, bucket1, breakdownField, "group-a", 2);
            createSpansWithFeedbackScores(projectName, bucket2, breakdownField, "group-a", 2);
            createSpansWithFeedbackScores(projectName, bucket1, breakdownField, "group-b", 2);
            createSpansWithFeedbackScores(projectName, bucket2, breakdownField, "group-b", 2);

            var requestBuilder = ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, 3, interval))
                    .intervalEnd(Instant.now());

            if (breakdownField == BreakdownField.METADATA) {
                requestBuilder.breakdown(BreakdownConfig.builder()
                        .field(breakdownField)
                        .metadataKey("env")
                        .build());
            } else {
                requestBuilder.breakdown(BreakdownConfig.builder().field(breakdownField).build());
            }

            var response = projectMetricsResourceClient.getProjectMetrics(projectId, requestBuilder.build(),
                    Double.class, API_KEY, WORKSPACE_NAME);

            assertThat(response.projectId()).isEqualTo(projectId);
            assertThat(response.metricType()).isEqualTo(MetricType.SPAN_FEEDBACK_SCORES);
            assertThat(response.breakdownField()).isEqualTo(breakdownField);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ProjectMetricsWithBreakdownResourceTest#spanInvalidBreakdownFields")
        @DisplayName("invalidParameters: metadata without key returns error")
        void invalidParameters(BreakdownField invalidField) {
            mockTargetWorkspace();
            TimeInterval interval = TimeInterval.HOURLY;
            Instant marker = getIntervalStart(interval);
            String projectName = RandomStringUtils.secure().nextAlphabetic(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var request = ProjectMetricRequest.builder()
                    .metricType(MetricType.SPAN_FEEDBACK_SCORES)
                    .interval(interval)
                    .intervalStart(subtract(marker, 1, interval))
                    .intervalEnd(Instant.now())
                    .breakdown(BreakdownConfig.builder().field(invalidField).build())
                    .build();

            try (var response = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(request))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                var error = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(error.getMessage()).contains("metadata_key is required");
            }
        }
    }

    // ==================== HELPER METHODS ====================

    private static Instant subtract(Instant instant, int count, TimeInterval interval) {
        if (interval == TimeInterval.WEEKLY) {
            count *= 7;
        }
        return instant.minus(count, interval == TimeInterval.HOURLY ? ChronoUnit.HOURS : ChronoUnit.DAYS);
    }

    private static Instant getIntervalStart(TimeInterval interval) {
        Instant now = Instant.now();
        return switch (interval) {
            case HOURLY -> now.truncatedTo(ChronoUnit.HOURS);
            case DAILY -> now.truncatedTo(ChronoUnit.DAYS);
            case WEEKLY -> {
                LocalDate today = LocalDate.ofInstant(now, ZoneId.of("UTC"));
                LocalDate startOfWeek = today.with(DayOfWeek.MONDAY);
                yield startOfWeek.atStartOfDay(ZoneId.of("UTC")).toInstant();
            }
        };
    }

    // ==================== TRACE DATA CREATION METHODS ====================

    private void createTracesForBreakdown(String projectName, Instant marker, BreakdownField breakdownField,
            String groupValue, int count) {
        List<Trace> traces = IntStream.range(0, count)
                .mapToObj(i -> {
                    Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
                    var builder = factory.manufacturePojo(Trace.class).toBuilder()
                            .id(idGenerator.generateId(traceStartTime))
                            .projectName(projectName)
                            .startTime(traceStartTime);

                    applyBreakdownFieldToTrace(builder, breakdownField, groupValue);
                    return builder.build();
                })
                .toList();
        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
    }

    private void createTracesWithDurationForBreakdown(String projectName, Instant marker,
            BreakdownField breakdownField, String groupValue, int count) {
        List<Trace> traces = IntStream.range(0, count)
                .mapToObj(i -> {
                    Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
                    var builder = factory.manufacturePojo(Trace.class).toBuilder()
                            .id(idGenerator.generateId(traceStartTime))
                            .projectName(projectName)
                            .startTime(traceStartTime)
                            .endTime(traceStartTime.plusMillis(RANDOM.nextInt(100, 1000)));

                    applyBreakdownFieldToTrace(builder, breakdownField, groupValue);
                    return builder.build();
                })
                .toList();
        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
    }

    private void createTracesWithSpansForTokenUsage(String projectName, Instant marker, BreakdownField breakdownField,
            String groupValue, int count) {
        for (int i = 0; i < count; i++) {
            Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
            var traceBuilder = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId(traceStartTime))
                    .projectName(projectName)
                    .startTime(traceStartTime);

            applyBreakdownFieldToTrace(traceBuilder, breakdownField, groupValue);
            Trace trace = traceBuilder.build();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            // Create span with usage data
            Span span = factory.manufacturePojo(Span.class).toBuilder()
                    .id(idGenerator.generateId(traceStartTime.plusMillis(1)))
                    .traceId(trace.id())
                    .projectName(projectName)
                    .startTime(traceStartTime)
                    .usage(Map.of("completion_tokens", 100, "prompt_tokens", 50))
                    .build();
            spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);
        }
    }

    private void createTracesWithSpansForCost(String projectName, Instant marker, BreakdownField breakdownField,
            String groupValue, int count) {
        for (int i = 0; i < count; i++) {
            Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
            var traceBuilder = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId(traceStartTime))
                    .projectName(projectName)
                    .startTime(traceStartTime);

            applyBreakdownFieldToTrace(traceBuilder, breakdownField, groupValue);
            Trace trace = traceBuilder.build();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            // Create span with cost data
            Span span = factory.manufacturePojo(Span.class).toBuilder()
                    .id(idGenerator.generateId(traceStartTime.plusMillis(1)))
                    .traceId(trace.id())
                    .projectName(projectName)
                    .startTime(traceStartTime)
                    .totalEstimatedCost(BigDecimal.valueOf(0.01 * RANDOM.nextDouble()))
                    .build();
            spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);
        }
    }

    private void createTracesWithFeedbackScores(String projectName, Instant marker, BreakdownField breakdownField,
            String groupValue, int count) {
        for (int i = 0; i < count; i++) {
            Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
            var traceBuilder = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId(traceStartTime))
                    .projectName(projectName)
                    .startTime(traceStartTime);

            applyBreakdownFieldToTrace(traceBuilder, breakdownField, groupValue);
            Trace trace = traceBuilder.build();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            List<FeedbackScoreBatchItem> scores = List.of(FeedbackScoreBatchItem.builder()
                    .id(trace.id())
                    .projectName(projectName)
                    .name("score-" + RANDOM.nextInt(1000))
                    .value(BigDecimal.valueOf(RANDOM.nextDouble()))
                    .source(ScoreSource.SDK)
                    .build());
            traceResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);
        }
    }

    private void createTracesWithGuardrails(String projectName, Instant marker, BreakdownField breakdownField,
            String groupValue, int count) {
        for (int i = 0; i < count; i++) {
            final int index = i;
            Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
            var traceBuilder = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId(traceStartTime))
                    .projectName(projectName)
                    .startTime(traceStartTime);

            applyBreakdownFieldToTrace(traceBuilder, breakdownField, groupValue);
            Trace trace = traceBuilder.build();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            // Add guardrail (alternating between pass and fail)
            var guardrails = guardrailsGenerator.generateGuardrailsForTrace(trace.id(), UUID.randomUUID(),
                    projectName);
            // Set result to FAILED for even indices, PASSED for odd
            var guardrailsWithResult = guardrails.stream()
                    .map(g -> g.toBuilder()
                            .result(index % 2 == 0 ? GuardrailResult.FAILED : GuardrailResult.PASSED)
                            .build())
                    .toList();
            guardrailsResourceClient.addBatch(guardrailsWithResult, API_KEY, WORKSPACE_NAME);
        }
    }

    private void applyBreakdownFieldToTrace(Trace.TraceBuilder builder, BreakdownField breakdownField,
            String groupValue) {
        switch (breakdownField) {
            case PROJECT_ID -> {
            } // No special handling needed
            case TAGS -> builder.tags(Set.of(groupValue));
            case METADATA -> builder.metadata(JsonUtils.getJsonNodeFromString(
                    JsonUtils.writeValueAsString(Map.of("env", groupValue))));
            case NAME -> builder.name(groupValue);
            case ERROR_INFO -> {
                if ("group-a".equals(groupValue)) {
                    builder.errorInfo(ErrorInfo.builder().message("Some error").build());
                } else {
                    builder.errorInfo(null);
                }
            }
            default -> {
            }
        }
    }

    // ==================== THREAD DATA CREATION METHODS ====================

    private void createThreadsForBreakdown(String projectName, Instant marker, BreakdownField breakdownField,
            String groupValue, int count) {
        List<String> threadIds = IntStream.range(0, count)
                .mapToObj(i -> RandomStringUtils.secure().nextAlphabetic(10))
                .toList();

        List<Trace> traces = IntStream.range(0, count)
                .mapToObj(i -> {
                    Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
                    var builder = factory.manufacturePojo(Trace.class).toBuilder()
                            .id(idGenerator.generateId(traceStartTime))
                            .projectName(projectName)
                            .threadId(threadIds.get(i))
                            .startTime(traceStartTime);

                    applyBreakdownFieldToThread(builder, breakdownField, groupValue);
                    return builder.build();
                })
                .toList();

        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

        Mono.delay(Duration.ofMillis(100)).block();
        traceResourceClient.closeTraceThreads(Set.copyOf(threadIds), null, projectName, API_KEY, WORKSPACE_NAME);
    }

    private void createThreadsWithDurationForBreakdown(String projectName, Instant marker,
            BreakdownField breakdownField, String groupValue, int count) {
        List<String> threadIds = IntStream.range(0, count)
                .mapToObj(i -> RandomStringUtils.secure().nextAlphabetic(10))
                .toList();

        List<Trace> traces = IntStream.range(0, count)
                .mapToObj(i -> {
                    Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);
                    var builder = factory.manufacturePojo(Trace.class).toBuilder()
                            .id(idGenerator.generateId(traceStartTime))
                            .projectName(projectName)
                            .threadId(threadIds.get(i))
                            .startTime(traceStartTime)
                            .endTime(traceStartTime.plusMillis(RANDOM.nextInt(100, 1000)));

                    applyBreakdownFieldToThread(builder, breakdownField, groupValue);
                    return builder.build();
                })
                .toList();

        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

        Mono.delay(Duration.ofMillis(100)).block();
        traceResourceClient.closeTraceThreads(Set.copyOf(threadIds), null, projectName, API_KEY, WORKSPACE_NAME);
    }

    private void createThreadsWithFeedbackScores(String projectName, Instant marker, BreakdownField breakdownField,
            String groupValue, int count) {
        for (int i = 0; i < count; i++) {
            String threadId = RandomStringUtils.secure().nextAlphabetic(10);
            Instant traceStartTime = marker.plus(i, ChronoUnit.SECONDS);

            var traceBuilder = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.generateId(traceStartTime))
                    .projectName(projectName)
                    .threadId(threadId)
                    .startTime(traceStartTime);

            applyBreakdownFieldToThread(traceBuilder, breakdownField, groupValue);
            Trace trace = traceBuilder.build();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Mono.delay(Duration.ofMillis(100)).block();
            traceResourceClient.closeTraceThreads(Set.of(threadId), null, projectName, API_KEY, WORKSPACE_NAME);

            List<FeedbackScoreBatchItemThread> scores = List.of(FeedbackScoreBatchItemThread.builder()
                    .threadId(threadId)
                    .projectName(projectName)
                    .name("score-" + RANDOM.nextInt(1000))
                    .value(BigDecimal.valueOf(RANDOM.nextDouble()))
                    .source(ScoreSource.SDK)
                    .build());
            traceResourceClient.threadFeedbackScores(scores, API_KEY, WORKSPACE_NAME);
        }
    }

    private void applyBreakdownFieldToThread(Trace.TraceBuilder builder, BreakdownField breakdownField,
            String groupValue) {
        // Thread metrics only support PROJECT_ID and TAGS
        switch (breakdownField) {
            case PROJECT_ID -> {
            } // No special handling needed
            case TAGS -> builder.tags(Set.of(groupValue));
            default -> {
            }
        }
    }

    // ==================== SPAN DATA CREATION METHODS ====================

    private void createSpansForBreakdown(String projectName, Instant marker, BreakdownField breakdownField,
            String groupValue, int count) {
        List<Span> spans = IntStream.range(0, count)
                .mapToObj(i -> {
                    Instant spanStartTime = marker.plus(i, ChronoUnit.SECONDS);
                    var builder = factory.manufacturePojo(Span.class).toBuilder()
                            .id(idGenerator.generateId(spanStartTime))
                            .projectName(projectName)
                            .startTime(spanStartTime);

                    applyBreakdownFieldToSpan(builder, breakdownField, groupValue);
                    return builder.build();
                })
                .toList();
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
    }

    private void createSpansWithDurationForBreakdown(String projectName, Instant marker, BreakdownField breakdownField,
            String groupValue, int count) {
        List<Span> spans = IntStream.range(0, count)
                .mapToObj(i -> {
                    Instant spanStartTime = marker.plus(i, ChronoUnit.SECONDS);
                    var builder = factory.manufacturePojo(Span.class).toBuilder()
                            .id(idGenerator.generateId(spanStartTime))
                            .projectName(projectName)
                            .startTime(spanStartTime)
                            .endTime(spanStartTime.plusMillis(RANDOM.nextInt(100, 1000)));

                    applyBreakdownFieldToSpan(builder, breakdownField, groupValue);
                    return builder.build();
                })
                .toList();
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
    }

    private void createSpansWithUsageForBreakdown(String projectName, Instant marker, BreakdownField breakdownField,
            String groupValue, int count) {
        List<Span> spans = IntStream.range(0, count)
                .mapToObj(i -> {
                    Instant spanStartTime = marker.plus(i, ChronoUnit.SECONDS);
                    var builder = factory.manufacturePojo(Span.class).toBuilder()
                            .id(idGenerator.generateId(spanStartTime))
                            .projectName(projectName)
                            .startTime(spanStartTime)
                            .usage(Map.of("completion_tokens", 100, "prompt_tokens", 50));

                    applyBreakdownFieldToSpan(builder, breakdownField, groupValue);
                    return builder.build();
                })
                .toList();
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
    }

    private void createSpansWithFeedbackScores(String projectName, Instant marker, BreakdownField breakdownField,
            String groupValue, int count) {
        for (int i = 0; i < count; i++) {
            Instant spanStartTime = marker.plus(i, ChronoUnit.SECONDS);
            var spanBuilder = factory.manufacturePojo(Span.class).toBuilder()
                    .id(idGenerator.generateId(spanStartTime))
                    .projectName(projectName)
                    .startTime(spanStartTime);

            applyBreakdownFieldToSpan(spanBuilder, breakdownField, groupValue);
            Span span = spanBuilder.build();
            spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);

            List<FeedbackScoreBatchItem> scores = List.of(FeedbackScoreBatchItem.builder()
                    .id(span.id())
                    .projectName(projectName)
                    .name("score-" + RANDOM.nextInt(1000))
                    .value(BigDecimal.valueOf(RANDOM.nextDouble()))
                    .source(ScoreSource.SDK)
                    .build());
            spanResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);
        }
    }

    private void applyBreakdownFieldToSpan(Span.SpanBuilder builder, BreakdownField breakdownField, String groupValue) {
        switch (breakdownField) {
            case PROJECT_ID -> {
            } // No special handling needed
            case TAGS -> builder.tags(Set.of(groupValue));
            case METADATA -> builder.metadata(JsonUtils.getJsonNodeFromString(
                    JsonUtils.writeValueAsString(Map.of("env", groupValue))));
            case NAME -> builder.name(groupValue);
            case ERROR_INFO -> {
                if ("group-a".equals(groupValue)) {
                    builder.errorInfo(ErrorInfo.builder().message("Some error").build());
                } else {
                    builder.errorInfo(null);
                }
            }
            case MODEL -> builder.model(groupValue);
            case PROVIDER -> builder.provider(groupValue);
            case TYPE -> builder.type("group-a".equals(groupValue) ? SpanType.llm : SpanType.tool);
            default -> {
            }
        }
    }
}

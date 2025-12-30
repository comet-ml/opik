package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.Comment;
import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem.FeedbackScoreBatchItemBuilder;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Project;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Trace.TracePage;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MinIOContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.TestWorkspace;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AnnotationQueuesResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.GuardrailsGenerator;
import com.comet.opik.api.resources.utils.resources.GuardrailsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.api.resources.utils.traces.TracePageTestAssertion;
import com.comet.opik.api.resources.utils.traces.TraceStatsAssertion;
import com.comet.opik.api.resources.utils.traces.TraceStreamTestAssertion;
import com.comet.opik.api.resources.utils.traces.TraceTestAssertion;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.GuardrailResult;
import com.comet.opik.domain.GuardrailsMapper;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Lists;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.filter.TraceField.CUSTOM;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.api.resources.utils.traces.TraceAssertions.IGNORED_FIELDS_TRACES;
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Get Traces Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class GetTracesByProjectResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/traces";

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);
    private final GenericContainer<?> minio = MinIOContainerUtils.newMinIOContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysqlContainer, clickHouseContainer, zookeeperContainer, minio).join();
        String minioUrl = "http://%s:%d".formatted(minio.getHost(), minio.getMappedPort(9000));

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redis.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .isMinIO(true)
                        .minioUrl(minioUrl)
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final FilterQueryBuilder filterQueryBuilder = new FilterQueryBuilder();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private GuardrailsResourceClient guardrailsResourceClient;
    private GuardrailsGenerator guardrailsGenerator;
    private AnnotationQueuesResourceClient annotationQueuesResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private IdGenerator idGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, IdGenerator idGenerator) {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
        this.guardrailsResourceClient = new GuardrailsResourceClient(client, baseURI);
        this.annotationQueuesResourceClient = new AnnotationQueuesResourceClient(client, baseURI);
        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
        this.guardrailsGenerator = new GuardrailsGenerator();
        this.idGenerator = idGenerator;
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID getProjectId(String projectName, String workspaceName, String apiKey) {
        return projectResourceClient.getByName(projectName, apiKey, workspaceName).id();
    }

    @Nested
    @DisplayName("Filters Test:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FilterTest {

        private final TraceStatsAssertion traceStatsAssertion = new TraceStatsAssertion(traceResourceClient);
        private final TraceTestAssertion traceTestAssertion = new TraceTestAssertion(traceResourceClient, USER);
        private final TraceStreamTestAssertion traceStreamTestAssertion = new TraceStreamTestAssertion(
                traceResourceClient, USER);

        private Stream<Arguments> getFilterTestArguments() {
            return Stream.of(
                    Arguments.of(
                            "/traces/stats",
                            traceStatsAssertion),
                    Arguments.of(
                            "/traces",
                            traceTestAssertion),
                    Arguments.of(
                            "/traces/search",
                            traceStreamTestAssertion));
        }

        private Stream<Arguments> equalAndNotEqualFilters() {
            return Stream.of(
                    Arguments.of(
                            "/traces/stats",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStatsAssertion),
                    Arguments.of(
                            "/traces",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceTestAssertion),
                    Arguments.of(
                            "/traces/search",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStreamTestAssertion),
                    Arguments.of(
                            "/traces/stats",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceStatsAssertion),
                    Arguments.of(
                            "/traces",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceTestAssertion),
                    Arguments.of(
                            "/traces/search",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceStreamTestAssertion));
        }

        private Stream<Arguments> getUsageKeyArgs() {
            return Stream.of(
                    Arguments.of(
                            "/traces/stats",
                            traceStatsAssertion,
                            "completion_tokens",
                            TraceField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/traces/stats",
                            traceStatsAssertion,
                            "prompt_tokens",
                            TraceField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/traces/stats",
                            traceStatsAssertion,
                            "total_tokens",
                            TraceField.USAGE_TOTAL_TOKENS),
                    Arguments.of(
                            "/traces",
                            traceTestAssertion,
                            "completion_tokens",
                            TraceField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/traces",
                            traceTestAssertion,
                            "prompt_tokens",
                            TraceField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/traces",
                            traceTestAssertion,
                            "total_tokens",
                            TraceField.USAGE_TOTAL_TOKENS),
                    Arguments.of(
                            "/traces/search",
                            traceStreamTestAssertion,
                            "completion_tokens",
                            TraceField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/traces/search",
                            traceStreamTestAssertion,
                            "prompt_tokens",
                            TraceField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/traces/search",
                            traceStreamTestAssertion,
                            "total_tokens",
                            TraceField.USAGE_TOTAL_TOKENS));
        }

        private Stream<Arguments> getFeedbackScoresArgs() {
            return Stream.of(
                    Arguments.of(
                            "/traces/stats",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStatsAssertion),
                    Arguments.of(
                            "/traces",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceTestAssertion),
                    Arguments.of(
                            "/traces/search",
                            Operator.EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStreamTestAssertion),
                    Arguments.of(
                            "/traces/stats",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(2, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(0, 2),
                            traceStatsAssertion),
                    Arguments.of(
                            "/traces",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(2, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(0, 2),
                            traceTestAssertion),
                    Arguments.of(
                            "/traces/search",
                            Operator.NOT_EQUAL,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(2, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(0, 2),
                            traceStreamTestAssertion));
        }

        private Stream<Arguments> getDurationArgs() {
            Stream<Arguments> arguments = Stream.of(
                    arguments(Operator.EQUAL, Duration.ofMillis(1L).toNanos() / 1000, 1.0),
                    arguments(Operator.GREATER_THAN, Duration.ofMillis(8L).toNanos() / 1000, 7.0),
                    arguments(Operator.GREATER_THAN_EQUAL, Duration.ofMillis(1L).toNanos() / 1000, 1.0),
                    arguments(Operator.GREATER_THAN_EQUAL, Duration.ofMillis(1L).plusNanos(1000).toNanos() / 1000, 1.0),
                    arguments(Operator.LESS_THAN, Duration.ofMillis(1L).plusNanos(1).toNanos() / 1000, 2.0),
                    arguments(Operator.LESS_THAN_EQUAL, Duration.ofMillis(1L).toNanos() / 1000, 1.0),
                    arguments(Operator.LESS_THAN_EQUAL, Duration.ofMillis(1L).toNanos() / 1000, 2.0));

            return arguments.flatMap(arg -> Stream.of(
                    arguments("/traces/stats", traceStatsAssertion, arg.get()[0],
                            arg.get()[1], arg.get()[2]),
                    arguments("/traces", traceTestAssertion, arg.get()[0],
                            arg.get()[1], arg.get()[2]),
                    arguments("/traces/search", traceStreamTestAssertion,
                            arg.get()[0],
                            arg.get()[1], arg.get()[2])));
        }

        private Stream<Arguments> getFilterInvalidOperatorForFieldTypeArgs() {
            return filterQueryBuilder.getUnSupportedOperators(TraceField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .flatMap(operator -> Stream.of(
                                    Arguments.of("/stats", TraceFilter.builder()
                                            .field(filter.getKey())
                                            .operator(operator)
                                            .key(getKey(filter.getKey()))
                                            .value(getValidValue(filter.getKey()))
                                            .build()),
                                    Arguments.of("/search", TraceFilter.builder()
                                            .field(filter.getKey())
                                            .operator(operator)
                                            .key(getKey(filter.getKey()))
                                            .value(getValidValue(filter.getKey()))
                                            .build()),
                                    Arguments.of("", TraceFilter.builder()
                                            .field(filter.getKey())
                                            .operator(operator)
                                            .key(getKey(filter.getKey()))
                                            .value(getValidValue(filter.getKey()))
                                            .build()))));
        }

        private Stream<Arguments> getFilterInvalidValueOrKeyForFieldTypeArgs() {

            Stream<TraceFilter> filters = filterQueryBuilder.getSupportedOperators(TraceField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .flatMap(operator -> switch (filter.getKey().getType()) {
                                case STRING -> Stream.empty();
                                case DICTIONARY, FEEDBACK_SCORES_NUMBER -> Stream.of(
                                        TraceFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                .key(null)
                                                .value(getValidValue(filter.getKey()))
                                                .build(),
                                        TraceFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                // if no value is expected, create an invalid filter by an empty key
                                                .key(Operator.NO_VALUE_OPERATORS.contains(operator)
                                                        ? ""
                                                        : getKey(filter.getKey()))
                                                .value(getInvalidValue(filter.getKey()))
                                                .build());
                                case ERROR_CONTAINER -> Stream.of();
                                default -> Stream.of(TraceFilter.builder()
                                        .field(filter.getKey())
                                        .operator(operator)
                                        .value(getInvalidValue(filter.getKey()))
                                        .build());
                            }));

            return filters.flatMap(filter -> Stream.of(
                    arguments("/stats", filter),
                    arguments("", filter),
                    arguments("/search", filter)));
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when project name and project id are null, then return bad request")
        void whenProjectNameAndIdAreNull__thenReturnBadRequest(String endpoint, TracePageTestAssertion testAssertion) {

            Project project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            testAssertion.assertTest(null, projectId, API_KEY, TEST_WORKSPACE, List.of(), List.of(), List.of(),
                    List.of(), Map.of());
        }

        private Instant generateStartTime() {
            return Instant.now().minusMillis(randomNumber(1, 1000));
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void findWithUsage(String endpoint, TracePageTestAssertion testAssertion) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .startTime(generateStartTime())
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            var traceIdToSpansMap = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .totalEstimatedCost(null)
                                    .build()))
                    .collect(Collectors.groupingBy(Span::traceId));
            batchCreateSpansAndAssert(
                    traceIdToSpansMap.values().stream().flatMap(List::stream).toList(), API_KEY, TEST_WORKSPACE);

            traces = traces.stream().map(trace -> trace.toBuilder()
                    .usage(traceIdToSpansMap.get(trace.id()).stream()
                            .map(Span::usage)
                            .flatMap(usage -> usage.entrySet().stream())
                            .collect(Collectors.groupingBy(
                                    Map.Entry::getKey, Collectors.summingLong(Map.Entry::getValue))))
                    .build()).toList();

            var traceIdToCommentsMap = traces.stream()
                    .map(trace -> Pair.of(trace.id(),
                            IntStream.range(0, 5)
                                    .mapToObj(i -> traceResourceClient.generateAndCreateComment(trace.id(), API_KEY,
                                            TEST_WORKSPACE, 201))
                                    .toList()))
                    .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));

            traces = traces.stream().map(trace -> trace.toBuilder()
                    .usage(traceIdToSpansMap.get(trace.id()).stream()
                            .map(Span::usage)
                            .flatMap(usage -> usage.entrySet().stream())
                            .collect(Collectors.groupingBy(
                                    Map.Entry::getKey, Collectors.summingLong(Map.Entry::getValue))))
                    .comments(traceIdToCommentsMap.get(trace.id()))
                    .build()).toList();

            traces = updateSpanCounts(traces, traceIdToSpansMap);

            var values = testAssertion.transformTestParams(traces, traces.reversed(), List.of());

            testAssertion.assertTest(projectName, null, API_KEY, TEST_WORKSPACE, values.expected(), values.unexpected(),
                    values.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void findWithoutUsage(String endpoint, TracePageTestAssertion testAssertion) {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .startTime(generateStartTime())
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .toList();
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .startTime(trace.startTime())
                                    .usage(null)
                                    .totalEstimatedCost(null)
                                    .build()))
                    .toList();
            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            traces = updateSpanCounts(traces, spans);

            var values = testAssertion.transformTestParams(traces, traces.reversed(), List.of());

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when project name is not empty, then return traces by project name")
        void whenProjectNameIsNotEmpty__thenReturnTracesByProjectName(String endpoint,
                TracePageTestAssertion testAssertion) {

            var projectName = UUID.randomUUID().toString();

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Trace> traces = new ArrayList<>();

            for (int i = 0; i < 15; i++) {
                Trace trace = setCommonTraceDefaults(createTrace().toBuilder())
                        .projectName(projectName)
                        .endTime(null)
                        .duration(null)
                        .output(null)
                        .tags(null)
                        .build();

                traces.add(trace);
            }

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var values = testAssertion.transformTestParams(traces, traces.reversed(), List.of());

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when project id is not empty, then return traces by project id")
        void whenProjectIdIsNotEmpty__thenReturnTracesByProjectId(String endpoint,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var projectName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Trace trace = setCommonTraceDefaults(createTrace().toBuilder())
                    .projectName(projectName)
                    .endTime(null)
                    .duration(null)
                    .output(null)
                    .tags(null)
                    .build();

            create(trace, apiKey, workspaceName);

            UUID projectId = getProjectId(projectName, workspaceName, apiKey);

            var values = testAssertion.transformTestParams(List.of(), List.of(trace), List.of());

            testAssertion.assertTest(null, projectId, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when filtering by workspace name, then return traces filtered")
        void whenFilterWorkspaceName__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {

            var workspaceName1 = UUID.randomUUID().toString();
            var workspaceName2 = UUID.randomUUID().toString();

            var projectName1 = UUID.randomUUID().toString();

            var workspaceId1 = UUID.randomUUID().toString();
            var workspaceId2 = UUID.randomUUID().toString();

            var apiKey1 = UUID.randomUUID().toString();
            var apiKey2 = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey1, workspaceName1, workspaceId1);
            mockTargetWorkspace(apiKey2, workspaceName2, workspaceId2);

            var traces1 = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName1)
                            .endTime(trace.startTime().plus(randomNumber(), ChronoUnit.MILLIS))
                            .build())
                    .toList();

            var traces2 = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName1)
                            .endTime(trace.startTime().plus(randomNumber(), ChronoUnit.MILLIS))
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces1, apiKey1, workspaceName1);
            traceResourceClient.batchCreateTraces(traces2, apiKey2, workspaceName2);

            var valueTraces1 = testAssertion.transformTestParams(traces1, traces1.reversed(), List.of());
            var valueTraces2 = testAssertion.transformTestParams(traces2, traces2.reversed(), List.of());

            testAssertion.assertTest(projectName1, null, apiKey1, workspaceName1, valueTraces1.expected(),
                    valueTraces1.unexpected(), valueTraces1.all(), List.of(), Map.of());
            testAssertion.assertTest(projectName1, null, apiKey2, workspaceName2, valueTraces2.expected(),
                    valueTraces2.unexpected(), valueTraces2.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("when traces have cost estimation, then return total cost estimation")
        void whenTracesHaveCostEstimation__thenReturnTotalCostEstimation(String endpoint,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var projectName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            List<Trace> traces = new ArrayList<>();

            for (int i = 0; i < 5; i++) {

                Trace trace = setCommonTraceDefaults(createTrace().toBuilder())
                        .projectName(projectName)
                        .endTime(null)
                        .duration(null)
                        .output(null)
                        .tags(null)
                        .totalEstimatedCost(BigDecimal.ZERO)
                        .build();

                List<Span> spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                        .map(span -> span.toBuilder()
                                .usage(spanResourceClient.getTokenUsage())
                                .model(spanResourceClient.randomModel().toString())
                                .provider(spanResourceClient.provider())
                                .traceId(trace.id())
                                .projectName(projectName)
                                .feedbackScores(null)
                                .totalEstimatedCost(null)
                                .build())
                        .toList();

                batchCreateSpansAndAssert(spans, apiKey, workspaceName);

                Trace expectedTrace = trace.toBuilder()
                        .totalEstimatedCost(calculateEstimatedCost(spans))
                        .usage(aggregateSpansUsage(spans))
                        .build();

                expectedTrace = updateSpanCounts(expectedTrace, spans);

                traces.add(expectedTrace);
            }

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            UUID projectId = getProjectId(projectName, workspaceName, apiKey);

            var values = testAssertion.transformTestParams(traces, traces.reversed(), List.of());

            testAssertion.assertTest(null, projectId, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterIdAndNameEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(20);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.ID)
                            .operator(operator)
                            .value(traces.getFirst().id().toString())
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(operator)
                            .value(traces.getFirst().name())
                            .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterByThreadEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(20);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .threadId(UUID.randomUUID().toString())
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(traces.size() - 1, traces.getLast().toBuilder()
                    .threadId(null)
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.THREAD_ID)
                            .operator(operator)
                            .value(traces.getFirst().threadId())
                            .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameEqual__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            List<TraceFilter> filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().name().toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameStartsWith__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.STARTS_WITH)
                    .value(traces.getFirst().name().substring(0, traces.getFirst().name().length() - 4).toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameEndsWith__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.ENDS_WITH)
                    .value(traces.getFirst().name().substring(3).toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameContains__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.CONTAINS)
                    .value(traces.getFirst().name().substring(2, traces.getFirst().name().length() - 3).toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterAnnotationQueueIdContains__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(project.name())
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Create spans for stats endpoint
            var spans = traces.stream()
                    .flatMap(trace -> IntStream.range(0, 1)
                            .mapToObj(i -> factory.manufacturePojo(Span.class).toBuilder()
                                    .usage(null)
                                    .totalEstimatedCost(null)
                                    .projectName(project.name())
                                    .traceId(trace.id())
                                    .type(SpanType.general)
                                    .build()))
                    .toList();
            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            // Update trace objects with span count for stats calculation
            var updatedTraces = traces.stream()
                    .map(trace -> trace.toBuilder().spanCount(1).build())
                    .collect(Collectors.toCollection(ArrayList::new));

            var expectedTraces = List.of(updatedTraces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .projectName(project.name())
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            // Create annotation queue with items
            var queue1 = prepareAnnotationQueue(projectId);
            var queue2 = prepareAnnotationQueue(projectId);
            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(queue1, queue2)), apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    queue1.id(), Set.of(updatedTraces.getFirst().id()), apiKey, workspaceName,
                    HttpStatus.SC_NO_CONTENT);
            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    queue2.id(), Set.of(updatedTraces.get(1).id()), apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.ANNOTATION_QUEUE_IDS)
                    .operator(Operator.CONTAINS)
                    .value(queue1.id().toString())
                    .build());

            var values = testAssertion.transformTestParams(updatedTraces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(project.name(), null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        private AnnotationQueue prepareAnnotationQueue(UUID projectId) {
            return factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .scope(AnnotationQueue.AnnotationScope.TRACE)
                    .build();
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        @DisplayName("When filtering by experiment_id, should return only traces associated with the experiment")
        void whenFilterExperimentIdEquals__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create traces
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(project.name())
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Create spans for stats endpoint
            var spans = traces.stream()
                    .flatMap(trace -> IntStream.range(0, 1)
                            .mapToObj(i -> factory.manufacturePojo(Span.class).toBuilder()
                                    .usage(null)
                                    .totalEstimatedCost(null)
                                    .projectName(project.name())
                                    .traceId(trace.id())
                                    .type(SpanType.general)
                                    .build()))
                    .toList();
            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            // Update trace objects with span count for stats calculation
            var updatedTraces = traces.stream()
                    .map(trace -> trace.toBuilder().spanCount(1).build())
                    .collect(Collectors.toCollection(ArrayList::new));

            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .projectName(project.name())
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            // Create experiments
            var experiment1 = experimentResourceClient.createPartialExperiment()
                    .datasetId(UUID.randomUUID())
                    .build();
            var experiment1Id = experimentResourceClient.create(experiment1, apiKey, workspaceName);

            var experiment2 = experimentResourceClient.createPartialExperiment()
                    .datasetId(UUID.randomUUID())
                    .build();
            var experiment2Id = experimentResourceClient.create(experiment2, apiKey, workspaceName);

            // Create experiment items linking traces to experiments
            var experimentItem1 = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experiment1Id)
                    .traceId(updatedTraces.getFirst().id())
                    .build();
            var experimentItem2 = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experiment2Id)
                    .traceId(updatedTraces.get(1).id())
                    .build();

            experimentResourceClient.createExperimentItem(
                    Set.of(experimentItem1, experimentItem2), apiKey, workspaceName);

            // Create filter for experiment_id
            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.EXPERIMENT_ID)
                    .operator(Operator.EQUAL)
                    .value(experiment1Id.toString())
                    .build());

            var values = testAssertion.transformTestParams(updatedTraces, List.of(updatedTraces.getFirst()),
                    unexpectedTraces);

            testAssertion.assertTest(project.name(), null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameNotContains__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traceName = UUID.randomUUID().toString();
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .name(traceName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .name(UUID.randomUUID().toString())
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.NOT_CONTAINS)
                    .value(traceName.toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterStartTimeEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(operator)
                    .value(traces.getFirst().startTime().toString())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeGreaterThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .startTime(Instant.now().minusSeconds(60 * 5))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now().plusSeconds(60 * 5))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.GREATER_THAN)
                    .value(Instant.now().toString())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeGreaterThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .startTime(Instant.now().minusSeconds(60 * 5))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now())
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.GREATER_THAN_EQUAL)
                    .value(traces.getFirst().startTime().toString())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeLessThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .startTime(Instant.now().plusSeconds(60 * 5))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.LESS_THAN)
                    .value(Instant.now().toString())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeLessThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .startTime(Instant.now().plusSeconds(60 * 5))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.START_TIME)
                    .operator(Operator.LESS_THAN_EQUAL)
                    .value(traces.getFirst().startTime().toString())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterEndTimeEqual__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.END_TIME)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().endTime().toString())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterInputEqual__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.INPUT)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().input().toString())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterOutputEqual__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.OUTPUT)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().output().toString())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterTotalEstimatedCostGreaterThen__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                    .map(spanInStream -> spanInStream.toBuilder()
                            .projectName(projectName)
                            .traceId(traces.getFirst().id())
                            .usage(Map.of("completion_tokens", Math.abs(factory.manufacturePojo(Integer.class)),
                                    "prompt_tokens", Math.abs(factory.manufacturePojo(Integer.class))))
                            .model("gpt-3.5-turbo-1106")
                            .provider("openai")
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toList());

            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            var finalTraces = updateSpanCounts(traces, spans);
            var unexpectedTraces = finalTraces.subList(1, traces.size());
            var expectedTrace = finalTraces.getFirst().toBuilder()
                    .usage(aggregateSpansUsage(spans))
                    .totalEstimatedCost(calculateEstimatedCost(spans))
                    .build();

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.TOTAL_ESTIMATED_COST)
                    .operator(Operator.GREATER_THAN)
                    .value("0")
                    .build());

            var values = testAssertion.transformTestParams(finalTraces, List.of(expectedTrace), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterTotalEstimatedCostEqual_NotEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces, // Here we swap the expected and unexpected traces
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .spanFeedbackScores(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .guardrailsValidations(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                    .map(spanInStream -> spanInStream.toBuilder()
                            .projectName(projectName)
                            .traceId(traces.getFirst().id())
                            .usage(Map.of("completion_tokens", Math.abs(factory.manufacturePojo(Integer.class)),
                                    "prompt_tokens", Math.abs(factory.manufacturePojo(Integer.class))))
                            .model("gpt-3.5-turbo-1106")
                            .provider("openai")
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toList());

            var otherSpans = traces.stream().skip(1)
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .usage(null)
                                    .model(null)
                                    .totalEstimatedCost(null)
                                    .build()))
                    .toList();

            var allSpans = Stream.concat(spans.stream(), otherSpans.stream()).toList();
            batchCreateSpansAndAssert(allSpans, apiKey, workspaceName);

            traces.set(0, traces.getFirst().toBuilder()
                    .usage(aggregateSpansUsage(spans))
                    .totalEstimatedCost(calculateEstimatedCost(spans))
                    .build());

            var finalTraces = updateSpanCounts(traces, allSpans);
            var expectedTraces = getExpectedTraces.apply(finalTraces);
            var unexpectedTraces = getUnexpectedTraces.apply(finalTraces);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.TOTAL_ESTIMATED_COST)
                    .operator(operator)
                    .value("0.00")
                    .build());

            var values = testAssertion.transformTestParams(finalTraces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        Stream<Arguments> whenFilterLlmSpanCountOperator__thenReturnTracesFiltered() {
            return getFilterTestArguments().flatMap(args -> Stream.of(
                    Arguments.of(args.get()[0], args.get()[1], Operator.EQUAL),
                    Arguments.of(args.get()[0], args.get()[1], Operator.NOT_EQUAL),
                    Arguments.of(args.get()[0], args.get()[1], Operator.GREATER_THAN),
                    Arguments.of(args.get()[0], args.get()[1], Operator.GREATER_THAN_EQUAL),
                    Arguments.of(args.get()[0], args.get()[1], Operator.LESS_THAN),
                    Arguments.of(args.get()[0], args.get()[1], Operator.LESS_THAN_EQUAL)));
        }

        @ParameterizedTest
        @MethodSource
        void whenFilterLlmSpanCountOperator__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
                Operator operator) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> {
                        var llmSpanCount = RandomUtils.secure().randomInt(1, 7);
                        return trace.toBuilder()
                                .projectId(null)
                                .projectName(projectName)
                                .usage(null)
                                .feedbackScores(null)
                                .spanFeedbackScores(null)
                                .threadId(null)
                                .totalEstimatedCost(null)
                                .guardrailsValidations(null)
                                .spanCount(llmSpanCount + RandomUtils.secure().randomInt(1, 7))
                                .llmSpanCount(llmSpanCount)
                                .build();
                    })
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = traces.stream()
                    .flatMap(trace -> IntStream.range(0, trace.spanCount())
                            .mapToObj(i -> factory.manufacturePojo(Span.class).toBuilder()
                                    .usage(null)
                                    .totalEstimatedCost(null)
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .type(i < trace.llmSpanCount() ? SpanType.llm : SpanType.general)
                                    .build()))
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var llmSpanCountToCompareAgainst = traces.getFirst().llmSpanCount();

            Predicate<Trace> matchesFilter = makeLlmSpanCountPredicate(operator, llmSpanCountToCompareAgainst);
            Comparator<Trace> traceIdComparator = Comparator.comparing(Trace::id).reversed();

            var expectedTraces = traces.stream()
                    .filter(matchesFilter)
                    .sorted(traceIdComparator)
                    .collect(Collectors.toList());

            var unexpectedTraces = traces.stream()
                    .filter(matchesFilter.negate())
                    .sorted(traceIdComparator)
                    .collect(Collectors.toList());

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.LLM_SPAN_COUNT)
                    .operator(operator)
                    .value(Integer.toString(llmSpanCountToCompareAgainst))
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        Predicate<Trace> makeLlmSpanCountPredicate(Operator operator, int value) {
            switch (operator) {
                case Operator.EQUAL :
                    return trace -> trace.llmSpanCount() == value;
                case Operator.NOT_EQUAL :
                    return trace -> trace.llmSpanCount() != value;
                case Operator.GREATER_THAN :
                    return trace -> trace.llmSpanCount() > value;
                case Operator.GREATER_THAN_EQUAL :
                    return trace -> trace.llmSpanCount() >= value;
                case Operator.LESS_THAN :
                    return trace -> trace.llmSpanCount() < value;
                case Operator.LESS_THAN_EQUAL :
                    return trace -> trace.llmSpanCount() <= value;
                default :
                    throw new IllegalArgumentException("Invalid operator for llm span count filtering: " + operator);
            }
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenTracesHaveToolSpans__thenHasToolSpansIsCorrect(String endpoint, TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .spanFeedbackScores(null)
                            .threadId(null)
                            .totalEstimatedCost(null)
                            .guardrailsValidations(null)
                            .hasToolSpans(false)
                            .spanCount(0)
                            .llmSpanCount(0)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Create spans: first trace has tool spans, others don't
            var spans = traces.stream()
                    .flatMap(trace -> {
                        var isFirstTrace = trace.equals(traces.getFirst());
                        return IntStream.range(0, 3)
                                .mapToObj(i -> factory.manufacturePojo(Span.class).toBuilder()
                                        .usage(null)
                                        .totalEstimatedCost(null)
                                        .projectName(projectName)
                                        .traceId(trace.id())
                                        .type(isFirstTrace && i == 0 ? SpanType.tool : SpanType.general)
                                        .build());
                    })
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            // Update traces with expected hasToolSpans values
            var firstTraceId = traces.getFirst().id();
            var updatedTraces = traces.stream()
                    .map(trace -> {
                        var hasToolSpans = trace.id().equals(firstTraceId);
                        return trace.toBuilder()
                                .hasToolSpans(hasToolSpans)
                                .spanCount(3)
                                .llmSpanCount(0)
                                .build();
                    })
                    .toList();

            var values = testAssertion.transformTestParams(traces, updatedTraces.reversed(), List.of());

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), List.of(), Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterMetadataEqualString__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traces.forEach(trace -> create(trace, apiKey, workspaceName));
            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(operator)
                    .key("$.model[0].version")
                    .value("OPENAI, CHAT-GPT 4.0")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());

        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualNumber__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("2023")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualBoolean__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(
                                    JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":false,\"version\":\"Some " +
                                            "version\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("TRUE")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualNull__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("NULL")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsString__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].version")
                    .value("CHAT-GPT")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsNumber__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":\"two thousand twenty " +
                                    "four\",\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("02")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsBoolean__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(
                                    JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":false,\"version\":\"Some " +
                                            "version\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("TRU")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsNull__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("NUL")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanNumber__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2020," +
                                    "\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("2023")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanString__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].version")
                    .value("a")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanBoolean__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanNull__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanNumber__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2026," +
                                    "\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(0, traces.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("2025")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanString__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].version")
                    .value("z")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanBoolean__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanNull__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.<Trace>of();
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterTagsContains__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.TAGS)
                    .operator(Operator.CONTAINS)
                    .value(traces.getFirst().tags().stream()
                            .toList()
                            .get(2)
                            .substring(0, traces.getFirst().name().length() - 4)
                            .toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
                String usageKey,
                Field field) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var otherUsageValue = randomNumber(1, 8);
            var usageValue = randomNumber();
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .usage(Map.of(usageKey, (long) otherUsageValue))
                            .build())
                    .collect(Collectors.toList());

            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, (long) usageValue))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, otherUsageValue))
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, usageValue))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            traces = updateSpanCounts(traces, traceIdToSpanMap.values().stream().toList());
            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(createTrace());

            traceResourceClient.batchCreateTraces(unrelatedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.EQUAL)
                    .value(traces.getFirst().usage().get(usageKey).toString())
                    .build());

            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageGreaterThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
                String usageKey,
                Field field) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 123L))
                            .llmSpanCount(1)
                            .spanCount(1)
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456L))
                    .build());
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 123))
                            .totalEstimatedCost(null)
                            .type(SpanType.llm)
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(createTrace());

            traceResourceClient.batchCreateTraces(unrelatedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.GREATER_THAN)
                    .value("123")
                    .build());

            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageGreaterThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
                String usageKey,
                Field field) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 123L))
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456L))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 123))
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            traces = updateSpanCounts(traces, traceIdToSpanMap.values().stream().toList());
            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(createTrace());

            traceResourceClient.batchCreateTraces(unrelatedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.GREATER_THAN_EQUAL)
                    .value(traces.getFirst().usage().get(usageKey).toString())
                    .build());

            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageLessThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
                String usageKey,
                Field field) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 456L))
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123L))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 456))
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            traces = updateSpanCounts(traces, traceIdToSpanMap.values().stream().toList());
            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(createTrace());

            traceResourceClient.batchCreateTraces(unrelatedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.LESS_THAN)
                    .value("456")
                    .build());

            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageLessThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
                String usageKey,
                Field field) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 456L))
                            .llmSpanCount(1)
                            .spanCount(1)
                            .build())
                    .collect(Collectors.toList());
            traces.set(0, traces.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123L))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanMap = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .usage(Map.of(usageKey, 456))
                            .totalEstimatedCost(null)
                            .type(SpanType.llm)
                            .build())
                    .collect(Collectors.toMap(Span::traceId, Function.identity()));
            traceIdToSpanMap.put(traces.getFirst().id(), traceIdToSpanMap.get(traces.getFirst().id()).toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());
            batchCreateSpansAndAssert(traceIdToSpanMap.values().stream().toList(), apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());
            var unrelatedTraces = List.of(createTrace());

            traceResourceClient.batchCreateTraces(unrelatedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(field)
                    .operator(Operator.LESS_THAN_EQUAL)
                    .value(traces.getFirst().usage().get(usageKey).toString())
                    .build());

            var unexpectedTraces = Stream.of(traces.subList(1, traces.size()), unrelatedTraces).flatMap(List::stream)
                    .toList();

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFeedbackScoresArgs")
        void whenFilterFeedbackScoresEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .feedbackScores(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()))
                            .llmSpanCount(0)
                            .spanCount(0)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(1, traces.get(1).toBuilder()
                    .feedbackScores(
                            updateFeedbackScore(traces.get(1).feedbackScores(), traces.getFirst().feedbackScores(), 2))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(operator)
                            .key(traces.getFirst().feedbackScores().get(1).name().toUpperCase())
                            .value(traces.getFirst().feedbackScores().get(1).value().toString())
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(operator)
                            .key(traces.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(traces.getFirst().feedbackScores().get(2).value().toString())
                            .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource
        void getTracesByProject__whenFilterFeedbackScoresIsEmpty__thenReturnTracesFiltered(
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .feedbackScores(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traces.set(traces.size() - 1,
                    traces.getLast().toBuilder().feedbackScores(null).spanFeedbackScores(null).build());
            traces.forEach(trace1 -> create(trace1, apiKey, workspaceName));
            traces.subList(0, traces.size() - 1).forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));
            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.FEEDBACK_SCORES)
                    .operator(operator)
                    .key(traces.getFirst().feedbackScores().getFirst().name())
                    .value("")
                    .build());
            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        private Stream<Arguments> getTracesByProject__whenFilterFeedbackScoresIsEmpty__thenReturnTracesFiltered() {
            return Stream.of(
                    Arguments.of(Operator.IS_NOT_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceTestAssertion),
                    Arguments.of(Operator.IS_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceTestAssertion),
                    Arguments.of(Operator.IS_NOT_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStatsAssertion),
                    Arguments.of(Operator.IS_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceStatsAssertion),
                    Arguments.of(Operator.IS_NOT_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStreamTestAssertion),
                    Arguments.of(Operator.IS_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceStreamTestAssertion));
        }

        private BigDecimal toScoreValue(int randomVal) {
            return BigDecimal.valueOf(randomVal)
                    .divide(BigDecimal.valueOf(100), 9, RoundingMode.HALF_UP);
        }

        private BigDecimal calculateAggregatedAverage(List<BigDecimal> values) {
            return values.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), 9, RoundingMode.HALF_UP);
        }

        private String formatFilterValue(BigDecimal value) {
            return value.setScale(9, RoundingMode.HALF_UP).toString();
        }

        /**
         * Determines the span feedback scores for a trace.
         * Trace 1 has no span feedback scores (placeholder with BigDecimal.ZERO), so it returns null.
         * Other traces return their actual scores if available.
         */
        private List<FeedbackScore> determineSpanFeedbackScores(UUID traceId, UUID trace1Id, FeedbackScore score) {
            if (traceId.equals(trace1Id) && score != null && score.value().equals(BigDecimal.ZERO)) {
                return null;
            }
            return score != null ? List.of(score) : null;
        }

        private List<Span> createSpansForTrace(Trace trace, String projectName) {
            return List.of(
                    factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .comments(null)
                            .usage(null)
                            .build(),
                    factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .comments(null)
                            .usage(null)
                            .build());
        }

        private FeedbackScore createFeedbackScore(FeedbackScoreBatchItem templateScore, BigDecimal value) {
            return factory.manufacturePojo(FeedbackScore.class).toBuilder()
                    .name(templateScore.name())
                    .value(value)
                    .categoryName(templateScore.categoryName())
                    .reason(templateScore.reason())
                    .source(templateScore.source())
                    .build();
        }

        private void processTraceWithFeedbackScores(Trace trace, String projectName, String apiKey,
                String workspaceName, FeedbackScoreBatchItem templateScore, int scoreMin, int scoreMax,
                Map<UUID, List<Span>> traceIdToSpansMap,
                Map<UUID, FeedbackScore> traceIdToSpanFeedbackScoresMap) {
            var spans = createSpansForTrace(trace, projectName);
            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);
            traceIdToSpansMap.put(trace.id(), spans);

            var spanValues = new ArrayList<BigDecimal>();
            var batchScores = new ArrayList<FeedbackScoreBatchItem>();
            for (var span : spans) {
                var value = toScoreValue(randomNumber(scoreMin, scoreMax));
                spanValues.add(value);
                batchScores.add(templateScore.toBuilder()
                        .id(span.id())
                        .projectName(projectName)
                        .value(value)
                        .build());
            }

            spanResourceClient.feedbackScores(batchScores, apiKey, workspaceName);
            var aggregatedAvg = calculateAggregatedAverage(spanValues);
            traceIdToSpanFeedbackScoresMap.put(trace.id(),
                    createFeedbackScore(templateScore, aggregatedAvg));
        }

        @ParameterizedTest
        @MethodSource("getFeedbackScoresArgs")
        void whenFilterSpanFeedbackScoresEqual__thenReturnTracesFiltered(String endpoint,
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .limit(4) // Limit to exactly 4 traces for this test scenario
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanFeedbackScoresMap = new HashMap<UUID, FeedbackScore>();
            var traceIdToSpansMap = new HashMap<UUID, List<Span>>();
            var templateScore = initFeedbackScoreItem().build();

            // Create spans and feedback scores for all traces
            // Trace 0: values in [81, 90] range -> will be used for EQUAL filter
            // Trace 1: has NO span feedback scores (won't match EQUAL or NOT_EQUAL)
            // Traces 2 and 3: values in [70, 80] range -> will match NOT_EQUAL filter

            // First, process trace 0 to get its aggregated average
            var trace0 = traces.getFirst();
            processTraceWithFeedbackScores(trace0, projectName, apiKey, workspaceName, templateScore, 81, 90,
                    traceIdToSpansMap, traceIdToSpanFeedbackScoresMap);

            // Now process trace 1: create spans but NO feedback scores
            // This ensures trace 1 won't match EQUAL (no scores) and won't match NOT_EQUAL (no scores to compare)
            var trace1 = traces.get(1);
            var trace1Spans = createSpansForTrace(trace1, projectName);
            spanResourceClient.batchCreateSpans(trace1Spans, apiKey, workspaceName);
            traceIdToSpansMap.put(trace1.id(), trace1Spans);
            // Trace 1 has no span feedback scores, so it won't match any filter
            // No feedback scores added for trace1, as it should not match any filter.
            traceIdToSpanFeedbackScoresMap.put(trace1.id(),
                    createFeedbackScore(templateScore, BigDecimal.ZERO));

            // Now process traces 2 and 3: generate random values in [70, 80] range
            for (int i = 2; i < traces.size(); i++) {
                processTraceWithFeedbackScores(traces.get(i), projectName, apiKey, workspaceName, templateScore, 70, 80,
                        traceIdToSpansMap, traceIdToSpanFeedbackScoresMap);
            }

            // Trace 0: has aggregated average (used for EQUAL filter)
            // Trace 1: has NO span feedback scores (won't match EQUAL or NOT_EQUAL)
            // Traces 2 and 3: have aggregated averages in [70, 80] range (will match NOT_EQUAL filter)
            var trace0Score = traceIdToSpanFeedbackScoresMap.get(traces.getFirst().id());
            var filterValue = formatFilterValue(trace0Score.value());

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.SPAN_FEEDBACK_SCORES)
                            .operator(operator)
                            .key(trace0Score.name().toUpperCase())
                            .value(filterValue)
                            .build());

            var trace1Id = traces.get(1).id();
            traces = traces.stream()
                    .map(trace -> {
                        var score = traceIdToSpanFeedbackScoresMap.get(trace.id());
                        var spanFeedbackScores = determineSpanFeedbackScores(trace.id(), trace1Id, score);
                        var spans = traceIdToSpansMap.get(trace.id());
                        var spanCount = spans != null ? spans.size() : 0;
                        var llmSpanCount = spans != null
                                ? (int) spans.stream()
                                        .filter(s -> SpanType.llm.equals(s.type()))
                                        .count()
                                : 0;
                        return trace.toBuilder()
                                .spanFeedbackScores(spanFeedbackScores)
                                .spanCount(spanCount)
                                .llmSpanCount(llmSpanCount)
                                .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(trace.startTime(),
                                        trace.endTime()))
                                .build();
                    })
                    .collect(Collectors.toCollection(ArrayList::new));

            // Use the provided functions to get expected/unexpected traces
            // These functions use list indices, so traces must be in original order
            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterSpanFeedbackScoresGreaterThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .limit(2)
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanFeedbackScoresMap = new HashMap<UUID, FeedbackScore>();
            var traceIdToSpansMap = new HashMap<UUID, List<Span>>();
            var templateScore = initFeedbackScoreItem().build();

            // Trace 0: aggregated average > threshold (will match GREATER_THAN filter)
            // Trace 1: aggregated average < threshold (won't match GREATER_THAN filter)
            var trace0 = traces.get(0);
            processTraceWithFeedbackScores(trace0, projectName, apiKey, workspaceName, templateScore, 85, 95,
                    traceIdToSpansMap, traceIdToSpanFeedbackScoresMap);

            var trace1 = traces.get(1);
            processTraceWithFeedbackScores(trace1, projectName, apiKey, workspaceName, templateScore, 70, 80,
                    traceIdToSpansMap, traceIdToSpanFeedbackScoresMap);

            var trace0Score = traceIdToSpanFeedbackScoresMap.get(trace0.id());
            var thresholdValue = formatFilterValue(trace0Score.value().subtract(BigDecimal.valueOf(0.1)));

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.SPAN_FEEDBACK_SCORES)
                            .operator(Operator.GREATER_THAN)
                            .key(trace0Score.name().toUpperCase())
                            .value(thresholdValue)
                            .build());

            traces = enrichTracesWithSpanData(traces, traceIdToSpanFeedbackScoresMap, traceIdToSpansMap);
            var expectedTraces = List.of(traces.get(0));
            var unexpectedTraces = List.of(traces.get(1));

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);
            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterSpanFeedbackScoresLessThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .limit(2)
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanFeedbackScoresMap = new HashMap<UUID, FeedbackScore>();
            var traceIdToSpansMap = new HashMap<UUID, List<Span>>();
            var templateScore = initFeedbackScoreItem().build();

            // Trace 0: aggregated average <= threshold (will match LESS_THAN_EQUAL filter)
            // Trace 1: aggregated average > threshold (won't match LESS_THAN_EQUAL filter)
            var trace0 = traces.get(0);
            processTraceWithFeedbackScores(trace0, projectName, apiKey, workspaceName, templateScore, 70, 80,
                    traceIdToSpansMap, traceIdToSpanFeedbackScoresMap);

            var trace1 = traces.get(1);
            processTraceWithFeedbackScores(trace1, projectName, apiKey, workspaceName, templateScore, 85, 95,
                    traceIdToSpansMap, traceIdToSpanFeedbackScoresMap);

            var trace0Score = traceIdToSpanFeedbackScoresMap.get(trace0.id());
            var thresholdValue = formatFilterValue(trace0Score.value());

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.SPAN_FEEDBACK_SCORES)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .key(trace0Score.name().toUpperCase())
                            .value(thresholdValue)
                            .build());

            traces = enrichTracesWithSpanData(traces, traceIdToSpanFeedbackScoresMap, traceIdToSpansMap);
            var expectedTraces = List.of(traces.get(0));
            var unexpectedTraces = List.of(traces.get(1));

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);
            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        private Stream<Arguments> getTracesByProject__whenFilterSpanFeedbackScoresIsEmpty__thenReturnTracesFiltered() {
            return Stream.of(
                    Arguments.of(Operator.IS_NOT_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceTestAssertion),
                    Arguments.of(Operator.IS_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceTestAssertion),
                    Arguments.of(Operator.IS_NOT_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStatsAssertion),
                    Arguments.of(Operator.IS_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceStatsAssertion),
                    Arguments.of(Operator.IS_NOT_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            traceStreamTestAssertion),
                    Arguments.of(Operator.IS_EMPTY,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.subList(1, traces.size()),
                            (Function<List<Trace>, List<Trace>>) traces -> List.of(traces.getFirst()),
                            traceStreamTestAssertion));
        }

        @ParameterizedTest
        @MethodSource("getTracesByProject__whenFilterSpanFeedbackScoresIsEmpty__thenReturnTracesFiltered")
        void getTracesByProject__whenFilterSpanFeedbackScoresIsEmpty__thenReturnTracesFiltered(
                Operator operator,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                Function<List<Trace>, List<Trace>> getUnexpectedTraces,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .limit(2)
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var traceIdToSpanFeedbackScoresMap = new HashMap<UUID, FeedbackScore>();
            var traceIdToSpansMap = new HashMap<UUID, List<Span>>();
            var templateScore = initFeedbackScoreItem().build();

            // Trace 0: has span feedback scores (will match IS_NOT_EMPTY, won't match IS_EMPTY)
            // Trace 1: has NO span feedback scores (will match IS_EMPTY, won't match IS_NOT_EMPTY)
            var trace0 = traces.get(0);
            processTraceWithFeedbackScores(trace0, projectName, apiKey, workspaceName, templateScore, 70, 80,
                    traceIdToSpansMap, traceIdToSpanFeedbackScoresMap);

            var trace1 = traces.get(1);
            var trace1Spans = createSpansForTrace(trace1, projectName);
            spanResourceClient.batchCreateSpans(trace1Spans, apiKey, workspaceName);
            traceIdToSpansMap.put(trace1.id(), trace1Spans);
            // Trace 1 has no span feedback scores - don't add to map, so it will be null

            var trace0Score = traceIdToSpanFeedbackScoresMap.get(trace0.id());
            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.SPAN_FEEDBACK_SCORES)
                            .operator(operator)
                            .key(trace0Score.name().toUpperCase())
                            .value("")
                            .build());

            traces = enrichTracesWithSpanData(traces, traceIdToSpanFeedbackScoresMap, traceIdToSpansMap);
            var expectedTraces = getExpectedTraces.apply(traces);
            var unexpectedTraces = getUnexpectedTraces.apply(traces);

            var values = testAssertion.transformTestParams(traces, expectedTraces.reversed(), unexpectedTraces);
            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        private ArrayList<Trace> enrichTracesWithSpanData(List<Trace> traces,
                Map<UUID, FeedbackScore> traceIdToSpanFeedbackScoresMap,
                Map<UUID, List<Span>> traceIdToSpansMap) {
            return traces.stream()
                    .map(trace -> {
                        var score = traceIdToSpanFeedbackScoresMap.get(trace.id());
                        var spanFeedbackScores = score != null ? List.of(score) : null;
                        var spans = traceIdToSpansMap.get(trace.id());
                        var spanCount = spans != null ? spans.size() : 0;
                        var llmSpanCount = spans != null
                                ? (int) spans.stream()
                                        .filter(s -> SpanType.llm.equals(s.type()))
                                        .count()
                                : 0;
                        return trace.toBuilder()
                                .spanFeedbackScores(spanFeedbackScores)
                                .spanCount(spanCount)
                                .llmSpanCount(llmSpanCount)
                                .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(trace.startTime(),
                                        trace.endTime()))
                                .build();
                    })
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresGreaterThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .feedbackScores(updateFeedbackScore(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()), 2, 1234.5678))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(traces.getFirst().feedbackScores(), 2, 2345.6789))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .feedbackScores(PodamFactoryUtils.manufacturePojoList(factory, FeedbackScore.class))
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);
            unexpectedTraces.forEach(
                    trace -> trace.feedbackScores()
                            .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.EQUAL)
                            .value(traces.getFirst().name())
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.GREATER_THAN)
                            .key(traces.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value("2345.6788")
                            .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresGreaterThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .feedbackScores(updateFeedbackScore(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()), 2, 1234.5678))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(traces.getFirst().feedbackScores(), 2, 2345.6789))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .feedbackScores(PodamFactoryUtils.manufacturePojoList(factory, FeedbackScore.class))
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            unexpectedTraces.forEach(
                    trace -> trace.feedbackScores()
                            .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .key(traces.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(traces.getFirst().feedbackScores().get(2).value().toString())
                            .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresLessThan__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .feedbackScores(updateFeedbackScore(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()), 2, 2345.6789))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(traces.getFirst().feedbackScores(), 2, 1234.5678))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .feedbackScores(PodamFactoryUtils.manufacturePojoList(factory, FeedbackScore.class))
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            unexpectedTraces.forEach(
                    trace -> trace.feedbackScores()
                            .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.LESS_THAN)
                            .key(traces.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value("2345.6788")
                            .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresLessThanEqual__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .feedbackScores(updateFeedbackScore(trace.feedbackScores().stream()
                                    .map(feedbackScore -> feedbackScore.toBuilder()
                                            .value(factory.manufacturePojo(BigDecimal.class))
                                            .build())
                                    .collect(Collectors.toList()), 2, 2345.6789))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(traces.getFirst().feedbackScores(), 2, 1234.5678))
                    .build());;

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            traces.forEach(trace -> trace.feedbackScores()
                    .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectName(RandomStringUtils.secure().nextAlphanumeric(20))
                    .projectId(null)
                    .feedbackScores(PodamFactoryUtils.manufacturePojoList(factory, FeedbackScore.class))
                    .build());

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);
            unexpectedTraces.forEach(
                    trace -> trace.feedbackScores()
                            .forEach(feedbackScore -> create(trace.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.FEEDBACK_SCORES)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .key(traces.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(traces.getFirst().feedbackScores().get(2).value().toString())
                            .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getDurationArgs")
        void whenFilterByDuration__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion,
                Operator operator,
                long end,
                double duration) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = UUID.randomUUID().toString();
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> {
                        Instant now = Instant.now();
                        return setCommonTraceDefaults(trace.toBuilder())
                                .projectName(projectName)
                                .startTime(now)
                                .endTime(Set.of(Operator.LESS_THAN, Operator.LESS_THAN_EQUAL).contains(operator)
                                        ? Instant.now().plusSeconds(2)
                                        : now.plusNanos(1000))
                                .build();
                    })
                    .collect(Collectors.toCollection(ArrayList::new));

            var start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            traces.set(0, traces.getFirst().toBuilder()
                    .startTime(start)
                    .endTime(start.plus(end, ChronoUnit.MICROS))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = List.of(traces.getFirst());

            var unexpectedTraces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.DURATION)
                            .operator(operator)
                            .value(String.valueOf(duration))
                            .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidOperatorForFieldTypeArgs")
        void whenFilterInvalidOperatorForFieldType__thenReturn400(String path, TraceFilter filter) {

            String errorMessage = filter.field().getType() == FieldType.CUSTOM
                    ? "Invalid key '%s' for custom filter".formatted(filter.key())
                    : "Invalid operator '%s' for field '%s' of type '%s'".formatted(
                            filter.operator().getQueryParamOperator(),
                            filter.field().getQueryParamField(),
                            filter.field().getType());

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    HttpStatus.SC_BAD_REQUEST, errorMessage);
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var filters = List.of(filter);

            Response actualResponse;
            if (path.equals("/search")) {
                actualResponse = traceResourceClient.callSearchTracesStream(
                        TraceSearchStreamRequest.builder()
                                .projectName(projectName)
                                .filters(filters)
                                .build(),
                        API_KEY,
                        TEST_WORKSPACE);

            } else {

                Map<String, String> queryParams = new HashMap<>();
                queryParams.put("project_name", projectName);
                queryParams.put("filters", toURLEncodedQueryParam(filters));
                actualResponse = traceResourceClient.callGetTracesWithQueryParams(API_KEY, TEST_WORKSPACE, queryParams);
            }

            try (actualResponse) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
            }

        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidValueOrKeyForFieldTypeArgs")
        void whenFilterInvalidValueOrKeyForFieldType__thenReturn400(String path, TraceFilter filter) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    400,
                    "Invalid value '%s' or key '%s' for field '%s' of type '%s'".formatted(
                            filter.value(),
                            filter.key(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var filters = List.of(filter);

            Response actualResponse;

            if (path.equals("/search")) {

                actualResponse = traceResourceClient.callSearchTracesStream(
                        TraceSearchStreamRequest.builder()
                                .projectName(projectName)
                                .filters(filters)
                                .build(),
                        apiKey,
                        workspaceName);

            } else {
                Map<String, String> queryParams = new HashMap<>();
                queryParams.put("project_name", projectName);
                queryParams.put("filters", toURLEncodedQueryParam(filters));
                actualResponse = traceResourceClient.callGetTracesWithQueryParams(apiKey, workspaceName, queryParams);
            }

            try (actualResponse) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
            }
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterGuardrails__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Create spans for stats endpoint
            var spans = traces.stream()
                    .flatMap(trace -> IntStream.range(0, 1)
                            .mapToObj(i -> factory.manufacturePojo(Span.class).toBuilder()
                                    .usage(null)
                                    .totalEstimatedCost(null)
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .type(SpanType.general)
                                    .build()))
                    .toList();
            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var guardrailsByTraceId = traces.stream()
                    .collect(Collectors.toMap(Trace::id, trace -> guardrailsGenerator.generateGuardrailsForTrace(
                            trace.id(), randomUUID(), trace.projectName())));

            // set the first trace with failed guardrails
            guardrailsByTraceId.put(traces.getFirst().id(), guardrailsByTraceId.get(traces.getFirst().id()).stream()
                    .map(guardrail -> guardrail.toBuilder().result(GuardrailResult.FAILED).build())
                    .toList());

            // set the rest of traces with passed guardrails
            traces.subList(1, traces.size()).forEach(trace -> guardrailsByTraceId.put(trace.id(),
                    guardrailsByTraceId.get(trace.id()).stream()
                            .map(guardrail -> guardrail.toBuilder()
                                    .result(GuardrailResult.PASSED)
                                    .build())
                            .toList()));

            guardrailsByTraceId.values()
                    .forEach(guardrail -> guardrailsResourceClient.addBatch(guardrail, apiKey,
                            workspaceName));

            // Update trace objects with span count and guardrails validations
            traces = traces.stream().map(trace -> trace.toBuilder()
                    .spanCount(1)
                    .guardrailsValidations(GuardrailsMapper.INSTANCE.mapToValidations(
                            guardrailsByTraceId.get(trace.id())))
                    .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            // assert failed guardrails
            var filtersFailed = List.of(
                    TraceFilter.builder()
                            .field(TraceField.GUARDRAILS)
                            .operator(Operator.EQUAL)
                            .value(GuardrailResult.FAILED.getResult())
                            .build());

            var valuesFailed = testAssertion.transformTestParams(traces, List.of(traces.getFirst()),
                    traces.subList(1, traces.size()));
            testAssertion.assertTest(projectName, null, apiKey, workspaceName, valuesFailed.expected(),
                    valuesFailed.unexpected(), valuesFailed.all(), filtersFailed, Map.of());

            // assert passed guardrails
            var filtersPassed = List.of(
                    TraceFilter.builder()
                            .field(TraceField.GUARDRAILS)
                            .operator(Operator.EQUAL)
                            .value(GuardrailResult.PASSED.getResult())
                            .build());

            var valuesPassed = testAssertion.transformTestParams(traces, traces.subList(1, traces.size()).reversed(),
                    List.of(traces.getFirst()));
            testAssertion.assertTest(projectName, null, apiKey, workspaceName, valuesPassed.expected(),
                    valuesPassed.unexpected(), valuesPassed.all(), filtersPassed, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterErrorIsNotEmpty__thenReturnTracesFiltered(String endpoint,
                TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .errorInfo(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .errorInfo(factory.manufacturePojo(ErrorInfo.class))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.ERROR_INFO)
                    .operator(Operator.IS_NOT_EMPTY)
                    .value("")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterErrorIsEmpty__thenReturnTracesFiltered(String endpoint, TracePageTestAssertion testAssertion) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .errorInfo(null)
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            var expectedTraces = List.of(traces.getFirst());
            var unexpectedTraces = List.of(createTrace().toBuilder()
                    .projectId(null)
                    .build());
            traceResourceClient.batchCreateTraces(unexpectedTraces, apiKey, workspaceName);

            var filters = List.of(TraceFilter.builder()
                    .field(TraceField.ERROR_INFO)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build());

            var values = testAssertion.transformTestParams(traces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(projectName, null, apiKey, workspaceName, values.expected(), values.unexpected(),
                    values.all(),
                    filters, Map.of());
        }
    }

    private BigDecimal calculateEstimatedCost(List<Span> spans) {
        return spans.stream()
                .map(span -> CostService.calculateCost(span.model(), span.provider(), span.usage(), null))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String getValidValue(Field field) {
        return switch (field.getType()) {
            case STRING, LIST, DICTIONARY, DICTIONARY_STATE_DB, MAP, CUSTOM, ENUM, STRING_STATE_DB ->
                RandomStringUtils.secure().nextAlphanumeric(10);
            case NUMBER, DURATION, FEEDBACK_SCORES_NUMBER -> String.valueOf(randomNumber(1, 10));
            case DATE_TIME, DATE_TIME_STATE_DB -> Instant.now().toString();
            case ERROR_CONTAINER -> "";
        };
    }

    private String getKey(Field field) {
        return switch (field.getType()) {
            case STRING, NUMBER, DURATION, MAP, DATE_TIME, LIST, ENUM, ERROR_CONTAINER, STRING_STATE_DB,
                    DATE_TIME_STATE_DB,
                    DICTIONARY, DICTIONARY_STATE_DB ->
                null;
            case FEEDBACK_SCORES_NUMBER, CUSTOM -> RandomStringUtils.secure().nextAlphanumeric(10);
        };
    }

    private String getInvalidValue(Field field) {
        return switch (field.getType()) {
            case STRING, DICTIONARY, DICTIONARY_STATE_DB, MAP, CUSTOM, LIST, ENUM, ERROR_CONTAINER, STRING_STATE_DB,
                    DATE_TIME_STATE_DB ->
                " ";
            case NUMBER, DURATION, DATE_TIME, FEEDBACK_SCORES_NUMBER -> RandomStringUtils.secure().nextAlphanumeric(10);
        };
    }

    @Nested
    @DisplayName("Find traces:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindTraces {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.ImageTruncationArgProvider#provideTestArguments")
        void findWithImageTruncation(JsonNode original, JsonNode expected, boolean truncate) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = Stream.of(createTrace())
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .input(original)
                            .output(original)
                            .metadata(original)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("page", "1");
            queryParams.put("size", "5");
            queryParams.put("project_name", projectName);
            queryParams.put("truncate", String.valueOf(truncate));

            var actualResponse = traceResourceClient.callGetTracesWithQueryParams(API_KEY, TEST_WORKSPACE, queryParams);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            var actualPage = actualResponse.readEntity(Trace.TracePage.class);
            var actualTraces = actualPage.content();

            assertThat(actualTraces).hasSize(1);

            var expectedTraces = traces.stream()
                    .map(trace -> trace.toBuilder()
                            .input(expected)
                            .output(expected)
                            .metadata(expected)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(trace.startTime(),
                                    trace.endTime()))
                            .build())
                    .toList();

            assertThat(actualTraces)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_TRACES)
                    .containsExactlyElementsOf(expectedTraces);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.ImageTruncationArgProvider#provideTestArguments")
        void searchWithImageTruncation(JsonNode original, JsonNode expected, boolean truncate) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = Stream.of(createTrace())
                    .map(trace -> trace.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .input(original)
                            .output(original)
                            .metadata(original)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            TraceSearchStreamRequest streamRequest = TraceSearchStreamRequest.builder()
                    .truncate(truncate)
                    .projectName(projectName)
                    .limit(5)
                    .build();

            var actualTraces = traceResourceClient.getStreamAndAssertContent(API_KEY, TEST_WORKSPACE, streamRequest);

            assertThat(actualTraces).hasSize(1);

            var expectedTraces = traces.stream()
                    .map(trace -> trace.toBuilder()
                            .input(expected)
                            .output(expected)
                            .metadata(expected)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(trace.startTime(),
                                    trace.endTime()))
                            .build())
                    .toList();

            TraceAssertions.assertTraces(actualTraces, expectedTraces, USER);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void whenUsingPagination__thenReturnTracesPaginated(boolean stream) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var expectedTraces = traces.stream()
                    .sorted(Comparator.comparing(Trace::id).reversed())
                    .toList();

            int pageSize = 2;

            if (stream) {
                AtomicReference<UUID> lastId = new AtomicReference<>(null);
                Lists.partition(expectedTraces, pageSize)
                        .forEach(trace -> {
                            var actualTraces = traceResourceClient.getStreamAndAssertContent(apiKey, workspaceName,
                                    TraceSearchStreamRequest.builder()
                                            .projectName(projectName)
                                            .lastRetrievedId(lastId.get())
                                            .limit(pageSize)
                                            .build());

                            TraceAssertions.assertTraces(actualTraces, trace, USER);

                            lastId.set(actualTraces.getLast().id());
                        });
            } else {
                for (int i = 0; i < expectedTraces.size() / pageSize; i++) {
                    int page = i + 1;
                    getAndAssertPage(
                            page,
                            pageSize,
                            projectName,
                            null,
                            List.of(),
                            expectedTraces.subList(i * pageSize, Math.min((i + 1) * pageSize, expectedTraces.size())),
                            List.of(),
                            workspaceName,
                            apiKey,
                            List.of(),
                            traces.size(), Set.of());
                }
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void whenFilterByVisibilityScoreEqual__thenReturnTracesFiltered(boolean stream) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            TraceFilter filter = TraceFilter.builder()
                    .field(TraceField.VISIBILITY_MODE)
                    .operator(Operator.EQUAL)
                    .value(VisibilityMode.DEFAULT.getValue())
                    .build();

            var actualTraces = traceResourceClient.getStreamAndAssertContent(apiKey, workspaceName,
                    TraceSearchStreamRequest.builder()
                            .projectName(projectName)
                            .filters(List.of(filter))
                            .build());

            if (stream) {
                TraceAssertions.assertTraces(actualTraces, traces.reversed(), USER);
            } else {
                getAndAssertPage(
                        1,
                        100,
                        projectName,
                        null,
                        List.of(filter),
                        traces.reversed(),
                        List.of(),
                        workspaceName,
                        apiKey,
                        List.of(),
                        traces.size(), Set.of());
            }
        }

        @ParameterizedTest
        @MethodSource
        void whenFilterByCustomFilter__thenReturnTracesFiltered(String key, String value, Operator operator) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .build())
                    .collect(toCollection(ArrayList::new));

            traces.set(0, traces.getFirst().toBuilder()
                    .input(JsonUtils
                            .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                                    "Chat-GPT 4.0\",\"trueFlag\":true,\"nullField\":null}]}"))
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            TraceFilter filter = TraceFilter.builder()
                    .field(CUSTOM)
                    .operator(operator)
                    .key(key)
                    .value(value)
                    .build();

            getAndAssertPage(
                    1,
                    100,
                    projectName,
                    null,
                    List.of(filter),
                    List.of(traces.getFirst()),
                    traces.subList(1, traces.size()),
                    workspaceName,
                    apiKey,
                    List.of(),
                    1, Set.of());
        }

        private Stream<Arguments> whenFilterByCustomFilter__thenReturnTracesFiltered() {
            return Stream.of(
                    Arguments.of(
                            "input.model[0].year",
                            "2024",
                            Operator.EQUAL),
                    Arguments.of(
                            "input.model[0].year",
                            "2025",
                            Operator.LESS_THAN),
                    Arguments.of(
                            "input",
                            "Chat-GPT 4.0",
                            Operator.CONTAINS));
        }

        @ParameterizedTest
        @MethodSource
        void getTracesByProject__whenSortingByValidFields__thenReturnTracesSorted(Comparator<Trace> comparator,
                SortingField sorting) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> {
                        var llmSpanCount = RandomUtils.secure().randomInt(1, 7);
                        return trace.toBuilder()
                                .projectId(null)
                                .projectName(projectName)
                                .usage(null)
                                .feedbackScores(null)
                                .endTime(trace.startTime().plus(randomNumber(), ChronoUnit.MILLIS))
                                .comments(null)
                                .spanCount(llmSpanCount + RandomUtils.secure().randomInt(1, 7))
                                .llmSpanCount(llmSpanCount)
                                .build();
                    })
                    .map(trace -> trace.toBuilder()
                            .duration(trace.startTime().until(trace.endTime(), ChronoUnit.MICROS) / 1000.0)
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var spans = traces.stream()
                    .flatMap(trace -> IntStream.range(0, trace.spanCount())
                            .mapToObj(i -> factory.manufacturePojo(Span.class).toBuilder()
                                    .usage(Map.of("completion_tokens", RandomUtils.secure().randomInt()))
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .type(i < trace.llmSpanCount() ? SpanType.llm : SpanType.general)
                                    .build()))
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var spansByTrace = spans.stream().collect(Collectors.groupingBy(Span::traceId));
            traces = traces.stream()
                    .map(t -> t.toBuilder()
                            .usage(aggregateSpansUsage(spansByTrace.get(t.id())))
                            .build())
                    .toList();

            var expectedTraces = traces.stream()
                    .sorted(comparator)
                    .toList();

            List<SortingField> sortingFields = List.of(sorting);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, expectedTraces, List.of(), apiKey,
                    sortingFields, Set.of());
        }

        @Test
        void createAndRetrieveTraces__spanCountReflectsActualSpans_andTotalCountMatches() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            // Create traces with varying spanCount values
            List<Trace> traces = IntStream.range(0, 5)
                    .mapToObj(i -> setCommonTraceDefaults(createTrace().toBuilder())
                            .projectName(projectName)
                            .spanCount(i * 3) // e.g., 0, 3, 6, 9, 12
                            .endTime(Instant.now())
                            .build())
                    .collect(Collectors.toList());

            int expectedTotalSpanCount = traces.stream().mapToInt(Trace::spanCount).sum();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // For each trace, create the actual number of spans matching the spanCount
            List<Span> allSpans = new ArrayList<>();
            for (Trace trace : traces) {
                List<Span> spansForTrace = IntStream.range(0, trace.spanCount())
                        .mapToObj(j -> factory.manufacturePojo(Span.class).toBuilder()
                                .projectName(projectName)
                                .type(SpanType.llm)
                                .traceId(trace.id())
                                .build())
                        .toList();
                allSpans.addAll(spansForTrace);
            }
            spanResourceClient.batchCreateSpans(allSpans, apiKey, workspaceName);

            // Retrieve traces from the API
            UUID projectId = getProjectId(projectName, workspaceName, apiKey);
            Trace.TracePage resultPage = traceResourceClient.getTraces(projectName, projectId, apiKey, workspaceName,
                    List.of(), List.of(), 100, Map.of());
            List<Trace> returnedTraces = resultPage.content();

            // Check that all created traces are present and have the correct spanCount
            for (Trace created : traces) {
                returnedTraces.stream()
                        .filter(returned -> returned.id().equals(created.id()))
                        .findFirst()
                        .ifPresentOrElse(returned -> {
                            assertThat(returned.spanCount())
                                    .as("Trace with id %s should have spanCount %d", created.id(), created.spanCount())
                                    .isEqualTo(created.spanCount());
                            assertThat(returned.llmSpanCount())
                                    .as("Trace with id %s should have llmSpanCount %d", created.id(),
                                            created.spanCount())
                                    .isEqualTo(created.spanCount());
                        },
                                () -> assertThat(false)
                                        .as("Trace with id %s should be present", created.id())
                                        .isTrue());
            }

            int actualTotalSpanCount = returnedTraces.stream()
                    .filter(rt -> traces.stream().anyMatch(t -> t.id().equals(rt.id())))
                    .mapToInt(Trace::spanCount)
                    .sum();

            assertThat(actualTotalSpanCount)
                    .as("Total spanCount across all traces should match the expected total")
                    .isEqualTo(expectedTotalSpanCount);

            int actualTotalLlmSpanCount = returnedTraces.stream()
                    .filter(rt -> traces.stream().anyMatch(t -> t.id().equals(rt.id())))
                    .mapToInt(Trace::llmSpanCount)
                    .sum();

            assertThat(actualTotalLlmSpanCount)
                    .as("Total llmSpanCount across all traces should match the expected total")
                    .isEqualTo(expectedTotalSpanCount);
        }

        private Stream<Arguments> getTracesByProject__whenSortingByValidFields__thenReturnTracesSorted() {

            Comparator<Trace> inputComparator = Comparator.comparing(trace -> trace.input().toString());
            Comparator<Trace> outputComparator = Comparator.comparing(trace -> trace.output().toString());
            Comparator<Trace> metadataComparator = Comparator.comparing(trace -> trace.metadata().toString());
            Comparator<Trace> tagsComparator = Comparator.comparing(trace -> trace.tags().toString());
            Comparator<Trace> errorInfoComparator = Comparator.comparing(trace -> trace.errorInfo().toString());
            Comparator<Trace> usageComparator = Comparator.comparing(trace -> trace.usage().get("completion_tokens"));

            return Stream.of(
                    Arguments.of(Comparator.comparing(Trace::name),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Trace::name).reversed(),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Trace::startTime),
                            SortingField.builder().field(SortableFields.START_TIME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Trace::startTime).reversed(),
                            SortingField.builder().field(SortableFields.START_TIME).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Trace::endTime),
                            SortingField.builder().field(SortableFields.END_TIME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Trace::endTime).reversed(),
                            SortingField.builder().field(SortableFields.END_TIME).direction(Direction.DESC).build()),
                    Arguments.of(
                            Comparator.comparing(Trace::duration)
                                    .thenComparing(Comparator.comparing(Trace::id).reversed()),
                            SortingField.builder().field(SortableFields.DURATION).direction(Direction.ASC).build()),
                    Arguments.of(
                            Comparator.comparing(Trace::duration).reversed()
                                    .thenComparing(Comparator.comparing(Trace::id).reversed()),
                            SortingField.builder().field(SortableFields.DURATION).direction(Direction.DESC).build()),
                    Arguments.of(inputComparator,
                            SortingField.builder().field(SortableFields.INPUT).direction(Direction.ASC).build()),
                    Arguments.of(inputComparator.reversed(),
                            SortingField.builder().field(SortableFields.INPUT).direction(Direction.DESC).build()),
                    Arguments.of(outputComparator,
                            SortingField.builder().field(SortableFields.OUTPUT).direction(Direction.ASC).build()),
                    Arguments.of(outputComparator.reversed(),
                            SortingField.builder().field(SortableFields.OUTPUT).direction(Direction.DESC).build()),
                    Arguments.of(metadataComparator,
                            SortingField.builder().field(SortableFields.METADATA).direction(Direction.ASC).build()),
                    Arguments.of(metadataComparator.reversed(),
                            SortingField.builder().field(SortableFields.METADATA).direction(Direction.DESC).build()),
                    Arguments.of(tagsComparator,
                            SortingField.builder().field(SortableFields.TAGS).direction(Direction.ASC).build()),
                    Arguments.of(tagsComparator.reversed(),
                            SortingField.builder().field(SortableFields.TAGS).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Trace::id),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Trace::id).reversed(),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.DESC).build()),
                    Arguments.of(errorInfoComparator,
                            SortingField.builder().field(SortableFields.ERROR_INFO).direction(Direction.ASC).build()),
                    Arguments.of(errorInfoComparator.reversed(),
                            SortingField.builder().field(SortableFields.ERROR_INFO).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Trace::threadId), SortingField.builder()
                            .field(SortableFields.THREAD_ID).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Trace::threadId).reversed(), SortingField.builder()
                            .field(SortableFields.THREAD_ID).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Trace::spanCount)
                            .thenComparing(Comparator.comparing(Trace::id).reversed()),
                            SortingField.builder().field(SortableFields.SPAN_COUNT).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Trace::spanCount).reversed()
                            .thenComparing(Comparator.comparing(Trace::id).reversed()),
                            SortingField.builder().field(SortableFields.SPAN_COUNT).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Trace::llmSpanCount)
                            .thenComparing(Comparator.comparing(Trace::id).reversed()),
                            SortingField.builder().field(SortableFields.LLM_SPAN_COUNT).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(Comparator.comparing(Trace::llmSpanCount).reversed()
                            .thenComparing(Comparator.comparing(Trace::id).reversed()),
                            SortingField.builder().field(SortableFields.LLM_SPAN_COUNT).direction(Direction.DESC)
                                    .build()),
                    Arguments.of(usageComparator,
                            SortingField.builder().field("usage.completion_tokens").direction(Direction.ASC).build()),
                    Arguments.of(usageComparator.reversed(),
                            SortingField.builder().field("usage.completion_tokens").direction(Direction.DESC).build()));
        }

        @Test
        void getTracesByProject__whenSortingByInvalidField__thenIgnoreAndReturnSuccess() {
            var field = RandomStringUtils.secure().nextAlphanumeric(10);
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var sortingFields = List.of(SortingField.builder().field(field).direction(Direction.ASC).build());

            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("project_name", projectName);
            queryParams.put("sorting",
                    URLEncoder.encode(JsonUtils.writeValueAsString(sortingFields), StandardCharsets.UTF_8));

            var actualResponse = traceResourceClient.callGetTracesWithQueryParams(API_KEY, TEST_WORKSPACE, queryParams);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            assertThat(actualResponse.hasEntity()).isTrue();

            var actualEntity = actualResponse.readEntity(Trace.TracePage.class);
            assertThat(actualEntity).isNotNull();
        }

        @ParameterizedTest
        @EnumSource(Direction.class)
        void getTracesByProject__whenSortingByFeedbackScores__thenReturnTracesSorted(Direction direction) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> setCommonTraceDefaults(trace.toBuilder())
                            .projectName(projectName)
                            .endTime(trace.startTime().plus(randomNumber(), ChronoUnit.MILLIS))
                            .build())
                    .map(trace -> trace.toBuilder()
                            .duration(trace.startTime().until(trace.endTime(), ChronoUnit.MICROS) / 1000.0)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            List<FeedbackScoreBatchItem> scoreForTrace = PodamFactoryUtils.manufacturePojoList(factory,
                    FeedbackScoreBatchItem.class);

            List<FeedbackScoreBatchItem> allScores = new ArrayList<>();
            for (Trace trace : traces) {
                for (FeedbackScoreBatchItem item : scoreForTrace) {

                    if (traces.getLast().equals(trace) && scoreForTrace.getFirst().equals(item)) {
                        continue;
                    }

                    allScores.add(item.toBuilder()
                            .id(trace.id())
                            .projectName(trace.projectName())
                            .value(factory.manufacturePojo(BigDecimal.class).abs())
                            .build());
                }
            }

            traceResourceClient.feedbackScores(allScores, apiKey, workspaceName);

            var sortingField = new SortingField(
                    "feedback_scores.%s".formatted(scoreForTrace.getFirst().name()),
                    direction);

            Comparator<Trace> comparing = Comparator.comparing(
                    (Trace trace) -> trace.feedbackScores()
                            .stream()
                            .filter(score -> score.name().equals(scoreForTrace.getFirst().name()))
                            .findFirst()
                            .map(FeedbackScore::value)
                            .orElse(null),
                    direction == Direction.ASC
                            ? Comparator.nullsFirst(Comparator.naturalOrder())
                            : Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Comparator.comparing(Trace::id).reversed());

            var expectedTraces = traces.stream()
                    .map(trace -> trace.toBuilder()
                            .feedbackScores(allScores
                                    .stream()
                                    .filter(score -> score.id().equals(trace.id()))
                                    .map(scores -> FeedbackScore.builder()
                                            .name(scores.name())
                                            .value(scores.value())
                                            .categoryName(scores.categoryName())
                                            .source(scores.source())
                                            .reason(scores.reason())
                                            .build())
                                    .toList())
                            .build())
                    .sorted(comparing)
                    .toList();

            List<SortingField> sortingFields = List.of(sortingField);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, expectedTraces, List.of(), apiKey,
                    sortingFields, Set.of());
        }

        @ParameterizedTest
        @EnumSource(Trace.TraceField.class)
        void getTracesByProject__whenExcludeParamIdDefined__thenReturnSpanExcludingFields(Trace.TraceField field) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            var projectName = RandomStringUtils.secure().nextAlphanumeric(20);

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(trace -> trace.toBuilder().projectName(projectName).build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            Map<UUID, Comment> expectedComments = traces
                    .stream()
                    .map(trace -> Map.entry(trace.id(),
                            traceResourceClient.generateAndCreateComment(trace.id(), apiKey, workspaceName, 201)))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

            traces = traces.stream()
                    .map(span -> span.toBuilder()
                            .comments(List.of(expectedComments.get(span.id())))
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(span.startTime(),
                                    span.endTime()))
                            .build())
                    .toList();

            List<Span> spans = traces.stream()
                    .map(trace -> factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(trace.projectName())
                            .traceId(trace.id())
                            .build())
                    .toList();

            batchCreateSpansAndAssert(spans, apiKey, workspaceName);

            traces = traces.stream()
                    .map(trace -> trace.toBuilder()
                            .totalEstimatedCost(spans.stream()
                                    .filter(span -> span.traceId().equals(trace.id()))
                                    .map(Span::totalEstimatedCost)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                            .spanCount((int) spans.stream()
                                    .filter(span -> span.traceId().equals(trace.id()))
                                    .count())
                            .usage(spans.stream()
                                    .filter(span -> span.traceId().equals(trace.id()))
                                    .map(Span::usage)
                                    .flatMap(map -> map.entrySet().stream())
                                    .collect(Collectors.groupingBy(Map.Entry::getKey,
                                            Collectors.summingLong(Map.Entry::getValue))))
                            .build())
                    .toList();

            List<Trace> finalTraces = traces;
            List<FeedbackScoreBatchItem> scoreForSpan = IntStream.range(0, traces.size())
                    .mapToObj(i -> initFeedbackScoreItem()
                            .projectName(finalTraces.get(i).projectName())
                            .id(finalTraces.get(i).id())
                            .build())
                    .collect(Collectors.toList());

            traceResourceClient.feedbackScores(scoreForSpan, apiKey, workspaceName);

            traces = traces.stream()
                    .map(trace -> trace.toBuilder()
                            .feedbackScores(
                                    scoreForSpan
                                            .stream()
                                            .filter(score -> score.id().equals(trace.id()))
                                            .map(scores -> FeedbackScore.builder()
                                                    .name(scores.name())
                                                    .value(scores.value())
                                                    .categoryName(scores.categoryName())
                                                    .source(scores.source())
                                                    .reason(scores.reason())
                                                    .build())
                                            .toList())
                            .build())
                    .toList();

            List<Guardrail> guardrailsByTraceId = traces.stream()
                    .map(trace -> guardrailsGenerator.generateGuardrailsForTrace(trace.id(), randomUUID(),
                            trace.projectName()))
                    .flatMap(Collection::stream)
                    .toList();

            traces = traces.stream()
                    .map(trace -> trace.toBuilder()
                            .guardrailsValidations(
                                    GuardrailsMapper.INSTANCE.mapToValidations(
                                            guardrailsByTraceId
                                                    .stream()
                                                    .filter(gr -> gr.entityId().equals(trace.id()))
                                                    .toList()))
                            .build())
                    .toList();

            guardrailsResourceClient.addBatch(guardrailsByTraceId, apiKey, workspaceName);

            traces = traces.stream()
                    .map(span -> TraceAssertions.EXCLUDE_FUNCTIONS.get(field).apply(span))
                    .toList();

            Set<Trace.TraceField> exclude = Set.of(field);

            getAndAssertPage(workspaceName, projectName, null, List.of(), traces, traces.reversed(), List.of(), apiKey,
                    List.of(), exclude);

        }

        @Test
        @DisplayName("should handle filter with percent characters in value correctly")
        void shouldHandleTracesWithPercentCharactersInName() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traceName = "test%";

            // Create a trace with % characters in the name
            var traces = List.of(setCommonTraceDefaults(createTrace().toBuilder())
                    .projectName(projectName)
                    .name(traceName)
                    .build());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Create a filter to search for the trace by name
            var filter = TraceFilter.builder()
                    .field(TraceField.NAME)
                    .operator(Operator.EQUAL)
                    .value(traceName)
                    .build();

            getAndAssertPage(workspaceName, projectName, null, List.of(filter), traces, traces, List.of(),
                    apiKey, null, Set.of());
        }
    }

    private Integer randomNumber() {
        return randomNumber(10, 99);
    }

    private static int randomNumber(int minValue, int maxValue) {
        return PodamUtils.getIntegerInRange(minValue, maxValue);
    }

    private void getAndAssertPage(String workspaceName, String projectName, UUID projectId,
            List<? extends Filter> filters,
            List<Trace> traces,
            List<Trace> expectedTraces,
            List<Trace> unexpectedTraces,
            String apiKey,
            List<SortingField> sortingFields,
            Set<Trace.TraceField> exclude) {
        int page = 1;

        int size = traces.size() + expectedTraces.size() + unexpectedTraces.size();
        getAndAssertPage(page, size, projectName, projectId, filters, expectedTraces, unexpectedTraces,
                workspaceName, apiKey, sortingFields, expectedTraces.size(), exclude);
    }

    private void getAndAssertPage(int page, int size, String projectName, UUID projectId,
            List<? extends Filter> filters,
            List<Trace> expectedTraces, List<Trace> unexpectedTraces, String workspaceName, String apiKey,
            List<SortingField> sortingFields, int total, Set<Trace.TraceField> exclude) {

        Map<String, String> queryParams = new HashMap<>();

        if (CollectionUtils.isNotEmpty(sortingFields)) {
            queryParams.put("sorting",
                    URLEncoder.encode(JsonUtils.writeValueAsString(sortingFields), StandardCharsets.UTF_8));
        }

        if (page > 0) {
            queryParams.put("page", String.valueOf(page));
        }

        if (size > 0) {
            queryParams.put("size", String.valueOf(size));
        }

        if (projectName != null) {
            queryParams.put("project_name", projectName);
        }

        if (projectId != null) {
            queryParams.put("project_id", projectId.toString());
        }

        if (CollectionUtils.isNotEmpty(exclude)) {
            queryParams.put("exclude", toURLEncodedQueryParam(List.copyOf(exclude)));
        }

        queryParams.put("filters", toURLEncodedQueryParam(filters));

        var actualResponse = traceResourceClient.callGetTracesWithQueryParams(apiKey, workspaceName, queryParams);

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

        var actualPage = actualResponse.readEntity(Trace.TracePage.class);
        var actualTraces = actualPage.content();

        assertThat(actualPage.page()).isEqualTo(page);
        assertThat(actualPage.size()).isEqualTo(expectedTraces.size());
        assertThat(actualPage.total()).isEqualTo(total);

        TraceAssertions.assertTraces(actualTraces, expectedTraces, unexpectedTraces, USER);
    }

    private List<FeedbackScore> updateFeedbackScore(List<FeedbackScore> feedbackScores, int index, double val) {
        feedbackScores.set(index, feedbackScores.get(index).toBuilder()
                .value(BigDecimal.valueOf(val))
                .build());
        return feedbackScores;
    }

    private List<FeedbackScore> updateFeedbackScore(
            List<FeedbackScore> destination, List<FeedbackScore> source, int index) {
        destination.set(index, source.get(index).toBuilder().build());
        return destination;
    }

    private Trace createTrace() {
        return fromBuilder(factory.manufacturePojo(Trace.class).toBuilder());
    }

    private Trace fromBuilder(Trace.TraceBuilder builder) {
        return builder
                .feedbackScores(null)
                .spanFeedbackScores(null)
                .threadId(null)
                .comments(null)
                .totalEstimatedCost(null)
                .usage(null)
                .errorInfo(null)
                .build();
    }

    /**
     * Sets common null/default values for trace builders used in tests.
     * This reduces code duplication across test methods.
     */
    private Trace.TraceBuilder setCommonTraceDefaults(Trace.TraceBuilder builder) {
        return builder
                .projectId(null)
                .usage(null)
                .threadId(null)
                .feedbackScores(null)
                .spanFeedbackScores(null)
                .totalEstimatedCost(null)
                .comments(null)
                .guardrailsValidations(null)
                .llmSpanCount(0)
                .spanCount(0);
    }

    private UUID create(Trace trace, String apiKey, String workspaceName) {
        return traceResourceClient.createTrace(trace, apiKey, workspaceName);
    }

    private void create(UUID entityId, FeedbackScore score, String workspaceName, String apiKey) {
        traceResourceClient.feedbackScore(entityId, score, workspaceName, apiKey);
    }

    private void batchCreateSpansAndAssert(List<Span> expectedSpans, String apiKey, String workspaceName) {
        spanResourceClient.batchCreateSpans(expectedSpans, apiKey, workspaceName);
    }

    private FeedbackScoreBatchItemBuilder<?, ?> initFeedbackScoreItem() {
        return factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder();
    }

    private Map<String, Long> aggregateSpansUsage(List<Span> spans) {
        if (CollectionUtils.isEmpty(spans)) {
            return null;
        }
        return spans.stream()
                .filter(span -> span.usage() != null)
                .flatMap(span -> span.usage().entrySet().stream())
                .map(entry -> Map.entry(entry.getKey(), Long.valueOf(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum));
    }

    private Trace updateSpanCounts(Trace trace, List<Span> spans) {
        return updateSpanCounts(List.of(trace), spans).getFirst();
    }

    private List<Trace> updateSpanCounts(List<Trace> traces, List<Span> spans) {
        var spansByTraceId = spans.stream().collect(Collectors.groupingBy(Span::traceId));
        return updateSpanCounts(traces, spansByTraceId);
    }

    private List<Trace> updateSpanCounts(List<Trace> traces, Map<UUID, List<Span>> spansByTraceId) {
        return traces.stream()
                .map(trace -> {
                    List<Span> ts = spansByTraceId.getOrDefault(trace.id(), List.of());
                    var total = ts.size();
                    var llmCount = ts.stream().filter(s -> s.type() == SpanType.llm).toList().size();
                    return trace.toBuilder()
                            .spanCount(total)
                            .llmSpanCount(llmCount)
                            .build();
                })
                .toList();
    }

    @Nested
    @DisplayName("Get Traces With Time Filtering:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetTracesByProjectTimeFilteringTests {

        private final TraceStatsAssertion traceStatsAssertion = new TraceStatsAssertion(traceResourceClient);
        private final TraceTestAssertion traceTestAssertion = new TraceTestAssertion(traceResourceClient, USER);
        private final TraceStreamTestAssertion traceStreamTestAssertion = new TraceStreamTestAssertion(
                traceResourceClient, USER);

        // Scenario 1: Boundary condition testing - traces at exact lower bound, upper bound, and in between
        private Stream<Arguments> provideBoundaryScenarios() {
            return Stream.of(
                    Arguments.of("/traces/stats", traceStatsAssertion),
                    Arguments.of("/traces", traceTestAssertion),
                    Arguments.of("/traces/stream", traceStreamTestAssertion));
        }

        @ParameterizedTest
        @DisplayName("filter traces by UUID creation time - includes traces at lower bound, upper bound, and between")
        @MethodSource("provideBoundaryScenarios")
        void whenTimeParametersProvided_thenIncludeTracesWithinBounds(
                String endpoint, TracePageTestAssertion testAssertion) {
            var workspace = setupTestWorkspace();

            Instant baseTime = Instant.now();
            Instant lowerBound = baseTime.minus(Duration.ofMinutes(10));
            Instant upperBound = baseTime;

            // Create traces with UUIDs at specific boundary times
            List<Trace> allTraces = List.of(
                    createTraceAtTimestamp(workspace.projectName(), lowerBound,
                            "At exact lower bound (should be included)"),
                    createTraceAtTimestamp(workspace.projectName(), upperBound,
                            "At exact upper bound (should be included)"),
                    createTraceAtTimestamp(workspace.projectName(), lowerBound.plus(Duration.ofMinutes(5)),
                            "Between bounds (should be included)"));

            traceResourceClient.batchCreateTraces(allTraces, workspace.apiKey(), workspace.workspaceName());

            var queryParams = Map.of(
                    "from_time", lowerBound.toString(),
                    "to_time", upperBound.toString());

            // Clear projectName from traces since API returns projectName=null
            allTraces = normalizeTraces(allTraces);
            var expectedTraces = sortByIdDescending(allTraces);
            var values = testAssertion.transformTestParams(allTraces, expectedTraces, List.of());

            testAssertion.assertTest(workspace.projectName(), null, workspace.apiKey(), workspace.workspaceName(),
                    values.expected(), values.unexpected(), values.all(), List.of(), queryParams);
        }

        @ParameterizedTest
        @DisplayName("filter traces by UUID creation time - excludes traces outside bounds")
        @MethodSource("provideBoundaryScenarios")
        void whenTimeParametersProvided_thenExcludeTracesOutsideBounds(
                String endpoint, TracePageTestAssertion testAssertion) {
            var workspace = setupTestWorkspace();

            Instant baseTime = Instant.now();
            Instant lowerBound = baseTime.minus(Duration.ofMinutes(10));
            Instant upperBound = baseTime;

            // Create traces: 3 within bounds, 2 outside bounds
            List<Trace> allTraces = List.of(
                    createTraceAtTimestamp(workspace.projectName(), lowerBound, "Within bounds"),
                    createTraceAtTimestamp(workspace.projectName(), upperBound, "Within bounds"),
                    createTraceAtTimestamp(workspace.projectName(), lowerBound.plus(Duration.ofMinutes(1)),
                            "Within bounds"),
                    createTraceAtTimestamp(workspace.projectName(), lowerBound.minus(Duration.ofMinutes(1)),
                            "Outside bounds (before lower)"),
                    createTraceAtTimestamp(workspace.projectName(), upperBound.plus(Duration.ofMinutes(1)),
                            "Outside bounds (after upper)"));

            traceResourceClient.batchCreateTraces(allTraces, workspace.apiKey(), workspace.workspaceName());

            // Expected: indices 0, 1, 2 (within bounds)
            // Unexpected: indices 3, 4 (outside bounds)
            List<Trace> expectedTraces = allTraces.subList(0, 3);
            List<Trace> unexpectedTraces = allTraces.subList(3, 5);

            var queryParams = Map.of(
                    "from_time", lowerBound.toString(),
                    "to_time", upperBound.toString());

            allTraces = normalizeTraces(allTraces);
            expectedTraces = normalizeTraces(expectedTraces);
            unexpectedTraces = normalizeTraces(unexpectedTraces);
            expectedTraces = sortByIdDescending(expectedTraces);

            var values = testAssertion.transformTestParams(allTraces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(workspace.projectName(), null, workspace.apiKey(), workspace.workspaceName(),
                    values.expected(), values.unexpected(), values.all(), List.of(), queryParams);
        }

        // Scenario 2: ISO-8601 format parsing with extended time range
        private Stream<Arguments> provideFormatParsingScenarios() {
            return Stream.of(
                    Arguments.of("/traces/stats", traceStatsAssertion),
                    Arguments.of("/traces", traceTestAssertion),
                    Arguments.of("/traces/stream", traceStreamTestAssertion));
        }

        @ParameterizedTest
        @DisplayName("time parameters in ISO-8601 format parse correctly and filter traces")
        @MethodSource("provideFormatParsingScenarios")
        void whenTimeParametersInISO8601Format_thenReturnFilteredTraces(
                String endpoint, TracePageTestAssertion testAssertion) {
            var workspace = setupTestWorkspace();

            Instant referenceTime = Instant.now();
            Instant startTime = referenceTime.minus(Duration.ofMinutes(90));
            Instant withinBoundsTime = referenceTime.minus(Duration.ofMinutes(30));
            Instant outsideBoundsTime = referenceTime.plus(Duration.ofMinutes(30));

            List<Trace> allTraces = List.of(
                    createTraceAtTimestamp(workspace.projectName(), startTime, "Should be included: at start of range"),
                    createTraceAtTimestamp(workspace.projectName(), withinBoundsTime,
                            "Should be included: within range"),
                    createTraceAtTimestamp(workspace.projectName(), referenceTime,
                            "Should be included: at end of range"),
                    createTraceAtTimestamp(workspace.projectName(), outsideBoundsTime,
                            "Should NOT be included: outside range"));

            traceResourceClient.batchCreateTraces(allTraces, workspace.apiKey(), workspace.workspaceName());

            // Filter to get first 3 traces (within bounds)
            List<Trace> expectedTraces = allTraces.subList(0, 3);
            List<Trace> unexpectedTraces = allTraces.subList(3, 4);

            // Use mutable HashMap instead of immutable Map.of() because
            // TraceResourceClient.getTraces() needs to process query parameters
            var queryParams = new HashMap<String, String>();
            queryParams.put("from_time", startTime.toString());
            queryParams.put("to_time", referenceTime.toString());

            allTraces = normalizeTraces(allTraces);
            expectedTraces = normalizeTraces(expectedTraces);
            unexpectedTraces = normalizeTraces(unexpectedTraces);
            expectedTraces = sortByIdDescending(expectedTraces);

            var values = testAssertion.transformTestParams(allTraces, expectedTraces, unexpectedTraces);

            testAssertion.assertTest(workspace.projectName(), null, workspace.apiKey(), workspace.workspaceName(),
                    values.expected(), values.unexpected(), values.all(), List.of(), queryParams);
        }

        // Scenario 3: Incomplete time parameters should be rejected
        private Stream<Arguments> provideInvalidParameterScenarios() {
            return Stream.of(
                    Arguments.of("/traces/stats", traceStatsAssertion),
                    Arguments.of("/traces", traceTestAssertion));
        }

        @ParameterizedTest
        @DisplayName("time filtering works with only from_time parameter - to_time is optional")
        @MethodSource("provideInvalidParameterScenarios")
        void whenOnlyFromTimeProvided_thenFilterTracesFromThatTime(
                String endpoint, TracePageTestAssertion testAssertion) {
            var workspace = setupTestWorkspace();

            Instant baseTime = Instant.now();
            Instant fromTime = baseTime.minus(Duration.ofMinutes(5));

            // Create traces: some before fromTime, some after
            List<Trace> allTraces = List.of(
                    createTraceAtTimestamp(workspace.projectName(), fromTime.minus(Duration.ofMinutes(10)),
                            "Before from_time - should be excluded"),
                    createTraceAtTimestamp(workspace.projectName(), fromTime,
                            "At from_time - should be included"),
                    createTraceAtTimestamp(workspace.projectName(), fromTime.plus(Duration.ofMinutes(2)),
                            "After from_time - should be included"),
                    createTraceAtTimestamp(workspace.projectName(), baseTime,
                            "Current time - should be included"));

            traceResourceClient.batchCreateTraces(allTraces, workspace.apiKey(), workspace.workspaceName());

            // Only provide from_time, omit to_time (which defaults to current time or no upper limit)
            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("project_name", workspace.projectName());
            queryParams.put("from_time", fromTime.toString());

            var actualResponse = traceResourceClient.callGetTracesWithQueryParams(
                    workspace.apiKey(), workspace.workspaceName(), queryParams);

            // Should succeed (200 OK) since to_time is now optional
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            // Expected: traces at indices 1, 2, 3 (from fromTime onwards)
            List<Trace> expectedTraces = normalizeTraces(allTraces.subList(1, 4));
            List<Trace> unexpectedTraces = normalizeTraces(allTraces.subList(0, 1));

            var tracePage = actualResponse.readEntity(TracePage.class);
            assertThat(tracePage.content()).hasSize(expectedTraces.size());

            // Verify expected traces are present
            for (Trace expectedTrace : expectedTraces) {
                assertThat(tracePage.content())
                        .anySatisfy(trace -> assertThat(trace.id()).isEqualTo(expectedTrace.id()));
            }

            // Verify unexpected traces are not present
            for (Trace unexpectedTrace : unexpectedTraces) {
                assertThat(tracePage.content())
                        .noneSatisfy(trace -> assertThat(trace.id()).isEqualTo(unexpectedTrace.id()));
            }
        }

        @ParameterizedTest
        @DisplayName("from_time must be before to_time")
        @MethodSource("provideInvalidParameterScenarios")
        void whenFromTimeAfterToTime_thenReturnBadRequest(
                String endpoint, TracePageTestAssertion testAssertion) {
            var workspace = setupTestWorkspace();
            Instant now = Instant.now();
            Instant earlier = now.minus(Duration.ofMinutes(10));

            // from_time (now) is after to_time (earlier) - should fail
            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("project_name", workspace.projectName());
            queryParams.put("from_time", now.toString());
            queryParams.put("to_time", earlier.toString());

            var actualResponse = traceResourceClient.callGetTracesWithQueryParams(
                    workspace.apiKey(), workspace.workspaceName(), queryParams);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        }

        // Helper methods to reduce duplication
        private TestWorkspace setupTestWorkspace() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var projectName = RandomStringUtils.secure().nextAlphanumeric(20);

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            return new TestWorkspace(workspaceName, workspaceId, apiKey, projectName);
        }

        private Trace createTraceAtTimestamp(String projectName, Instant timestamp, String comment) {
            return createTrace().toBuilder()
                    .projectName(projectName)
                    .id(idGenerator.generateId(timestamp))
                    .spanCount(0)
                    .llmSpanCount(0)
                    .guardrailsValidations(null)
                    .feedbackScores(null)
                    .spanFeedbackScores(null)
                    .build();
        }

        private List<Trace> normalizeTraces(List<Trace> traces) {
            return traces.stream().map(t -> t.toBuilder().projectName(null).build()).toList();
        }

        private List<Trace> sortByIdDescending(List<Trace> traces) {
            return traces.stream()
                    .sorted(Comparator.comparing(Trace::id).reversed())
                    .toList();
        }

    }

}

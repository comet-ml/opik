package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadSearchStreamRequest;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.TraceThreadUpdate;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MinIOContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AnnotationQueuesResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.http.HttpStatus;
import org.jetbrains.annotations.NotNull;
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
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Find Trace Threads  Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class FindTraceThreadsResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);
    private final GenericContainer<?> minIOContainer = MinIOContainerUtils.newMinIOContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer, minIOContainer)
                .join();
        String minioUrl = "http://%s:%d".formatted(minIOContainer.getHost(), minIOContainer.getMappedPort(9000));

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
                        .redisUrl(redisContainer.getRedisURI())
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
    private AnnotationQueuesResourceClient annotationQueuesResourceClient;
    private IdGenerator idGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, com.comet.opik.domain.IdGenerator idGenerator) {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
        this.annotationQueuesResourceClient = new AnnotationQueuesResourceClient(client, baseURI);
        this.idGenerator = idGenerator;
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void assertThreadPage(String projectName, UUID projectId, List<TraceThread> expectedThreads,
            List<TraceThreadFilter> filters, Map<String, String> queryParams, String apiKey, String workspaceName) {
        assertThreadPage(projectName, projectId, expectedThreads, filters, queryParams, apiKey, workspaceName,
                List.of());
    }

    private void assertThreadPage(String projectName, UUID projectId, List<TraceThread> expectedThreads,
            List<TraceThreadFilter> filters, Map<String, String> queryParams, String apiKey, String workspaceName,
            List<SortingField> sortingFields) {
        var actualPage = traceResourceClient.getTraceThreads(projectId, projectName, apiKey, workspaceName, filters,
                sortingFields, queryParams);
        var actualTraces = actualPage.content();

        assertThat(actualTraces).hasSize(expectedThreads.size());
        assertThat(actualPage.total()).isEqualTo(expectedThreads.size());

        TraceAssertions.assertThreads(expectedThreads, actualTraces);

        for (int i = 0; i < expectedThreads.size(); i++) {
            var expectedThread = expectedThreads.get(i);
            var actualThread = actualTraces.get(i);

            assertThat(actualThread.createdAt()).isBetween(expectedThread.createdAt(), Instant.now());
            assertThat(actualThread.lastUpdatedAt())
                    // Some JVMs can resolve higher than microseconds, such as nanoseconds in the Ubuntu AMD64 JVM
                    .isBetween(expectedThread.lastUpdatedAt().truncatedTo(ChronoUnit.MICROS), Instant.now());
        }
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

    private static int randomNumber(int minValue, int maxValue) {
        return PodamUtils.getIntegerInRange(minValue, maxValue);
    }

    private void batchCreateSpansAndAssert(List<Span> expectedSpans, String apiKey, String workspaceName) {
        spanResourceClient.batchCreateSpans(expectedSpans, apiKey, workspaceName);
    }

    private UUID getProjectId(String projectName, String workspaceName, String apiKey) {
        return projectResourceClient.getByName(projectName, apiKey, workspaceName).id();
    }

    private BigDecimal calculateEstimatedCost(List<Span> spans) {
        return spans.stream()
                .map(span -> CostService.calculateCost(span.model(), span.provider(), span.usage(), null))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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

    private void assertTheadStream(String projectName, UUID projectId, String apiKey, String workspaceName,
            List<TraceThread> expectedThreads, List<TraceThreadFilter> filters) {
        var actualThreads = traceResourceClient.searchTraceThreadsStream(projectName, projectId, apiKey,
                workspaceName, filters);
        TraceAssertions.assertThreads(expectedThreads, actualThreads);
    }

    @Nested
    @DisplayName("Find trace Threads:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindTraceThreads {

        private Stream<Arguments> getUnsupportedOperations() {
            return filterQueryBuilder.getUnSupportedOperators(TraceThreadField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .flatMap(operator -> Stream.of(
                                    Arguments.of(true, filter.getKey(), operator, getValidValue(filter.getKey())),
                                    Arguments.of(false, filter.getKey(), operator, getValidValue(filter.getKey())))));
        }

        private Stream<Arguments> getFilterInvalidValueOrKeyForFieldTypeArgs() {
            return filterQueryBuilder.getSupportedOperators(TraceThreadField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .flatMap(operator -> switch (filter.getKey().getType()) {
                                case STRING -> Stream.empty();
                                case DICTIONARY, FEEDBACK_SCORES_NUMBER -> Stream.of(
                                        TraceThreadFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                .key(null)
                                                .value(getValidValue(filter.getKey()))
                                                .build(),
                                        TraceThreadFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                // if no value is expected, create an invalid filter by an empty key
                                                .key(Operator.NO_VALUE_OPERATORS.contains(operator)
                                                        ? ""
                                                        : getKey(filter.getKey()))
                                                .value(getInvalidValue(filter.getKey()))
                                                .build());
                                default -> Stream.of(TraceThreadFilter.builder()
                                        .field(filter.getKey())
                                        .operator(operator)
                                        .value(getInvalidValue(filter.getKey()))
                                        .build());
                            }))
                    .flatMap(operator -> Stream.of(
                            Arguments.of(true, operator),
                            Arguments.of(false, operator)));
        }

        private Stream<Arguments> getValidFilters() {
            return Stream.of(
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.ID)
                                    .operator(Operator.EQUAL)
                                    .value(traces.getFirst().threadId())
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .map(trace -> trace.toBuilder()
                                            .threadId(UUID.randomUUID().toString())
                                            .build())
                                    .toList()),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.FIRST_MESSAGE)
                                    .operator(Operator.CONTAINS)
                                    .value(traces.stream().min(Comparator.comparing(Trace::startTime))
                                            .orElseThrow().input().toString().substring(0, 20))
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.LAST_MESSAGE)
                                    .operator(Operator.CONTAINS)
                                    .value(traces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                                            .output().toString().substring(0, 20))
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.DURATION)
                                    .operator(Operator.EQUAL)
                                    .key(null)
                                    .value(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                            traces.stream().min(Comparator.comparing(Trace::startTime)).get()
                                                    .startTime(),
                                            traces.stream().max(Comparator.comparing(Trace::endTime)).get().endTime())
                                            .toString())
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .map(trace -> trace.toBuilder()
                                            .endTime(trace.endTime().plusMillis(100))
                                            .build())
                                    .toList()),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.LAST_UPDATED_AT)
                                    .operator(Operator.EQUAL)
                                    .key(null)
                                    .value(traces.stream().max(Comparator.comparing(Trace::lastUpdatedAt)).get()
                                            .lastUpdatedAt().toString())
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces),
                    Arguments.of(
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.NUMBER_OF_MESSAGES)
                                    .operator(Operator.EQUAL)
                                    .key(null)
                                    .value(String.valueOf(traces.size() * 2))
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces,
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .map(trace -> trace.toBuilder()
                                            .threadId(UUID.randomUUID().toString())
                                            .build())
                                    .toList()))
                    .flatMap(args -> Stream.of(
                            Arguments.of(true, args.get()[0], args.get()[1], args.get()[2]),
                            Arguments.of(false, args.get()[0], args.get()[1], args.get()[2])));
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.ImageTruncationArgProvider#provideTestArguments")
        void findWithImageTruncation(JsonNode original, JsonNode expected, boolean truncate) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var threadId = UUID.randomUUID().toString();

            Trace trace = createTrace();

            var traces = Stream.of(trace)
                    .map(it -> it.toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .input(original)
                            .output(original)
                            .threadId(threadId)
                            .build())
                    .toList();

            List<Span> spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                    .map(span -> span.toBuilder()
                            .usage(spanResourceClient.getTokenUsage())
                            .model(spanResourceClient.randomModel().toString())
                            .provider(spanResourceClient.provider())
                            .traceId(traces.getFirst().id())
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .build())
                    .toList();

            batchCreateSpansAndAssert(spans, API_KEY, TEST_WORKSPACE);

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);

            var expectedThreads = List.of(TraceThread.builder()
                    .firstMessage(expected)
                    .lastMessage(expected)
                    .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(trace.startTime(),
                            trace.endTime()))
                    .projectId(projectId)
                    .createdBy(USER)
                    .startTime(trace.startTime())
                    .endTime(trace.endTime())
                    .numberOfMessages(traces.size() * 2L)
                    .id(threadId)
                    .totalEstimatedCost(calculateEstimatedCost(spans))
                    .usage(aggregateSpansUsage(spans))
                    .createdAt(trace.createdAt())
                    .lastUpdatedAt(trace.lastUpdatedAt())
                    .status(TraceThreadStatus.ACTIVE)
                    .build());

            Map<String, String> queryParams = Map.of("page", "1", "size", "5", "truncate", String.valueOf(truncate));

            assertThreadPage(projectName, null, expectedThreads, List.of(), queryParams, API_KEY,
                    TEST_WORKSPACE);
        }

        @ParameterizedTest
        @MethodSource("getUnsupportedOperations")
        void whenFilterUnsupportedOperation__thenReturn400(boolean stream, TraceThreadField field, Operator operator,
                String value) {
            var filter = TraceThreadFilter.builder()
                    .field(field)
                    .operator(operator)
                    .key(getKey(field))
                    .value(value)
                    .build();

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    HttpStatus.SC_BAD_REQUEST,
                    "Invalid operator '%s' for field '%s' of type '%s'".formatted(
                            filter.operator().getQueryParamOperator(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var filters = List.of(filter);

            try (var actualResponse = !stream
                    ? findThreads(projectName, filters, API_KEY, TEST_WORKSPACE)
                    : streamThreadSearch(projectName, null, filters, API_KEY, TEST_WORKSPACE)) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
            }
        }

        private Response findThreads(String projectName, List<@NotNull TraceThreadFilter> filters, String apiKey,
                String testWorkspace) {
            return traceResourceClient.getTraceThreads(projectName, apiKey, testWorkspace, filters);
        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidValueOrKeyForFieldTypeArgs")
        void whenFilterInvalidValueOrKeyForFieldType__thenReturn400(boolean stream, TraceThreadFilter filter) {
            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    400,
                    "Invalid value '%s' or key '%s' for field '%s' of type '%s'".formatted(
                            filter.value(),
                            filter.key(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var filters = List.of(filter);

            try (var actualResponse = !stream
                    ? findThreads(projectName, filters, API_KEY, TEST_WORKSPACE)
                    : streamThreadSearch(projectName, null, filters, API_KEY, TEST_WORKSPACE)) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
            }
        }

        private Response streamThreadSearch(String projectName, UUID projectId,
                List<@NotNull TraceThreadFilter> filters, String apiKey, String testWorkspace) {
            return traceResourceClient.callSearchTraceThreadStream(projectName, projectId, apiKey, testWorkspace,
                    filters);
        }

        @ParameterizedTest
        @MethodSource("getValidFilters")
        void whenFilterThreads__thenReturnThreadsFiltered(
                boolean stream,
                Function<List<Trace>, TraceThreadFilter> getFilter,
                Function<List<Trace>, List<Trace>> getExpectedThreads,
                Function<List<Trace>, List<Trace>> getUnexpectedThreads) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var threadId = UUID.randomUUID().toString();
            var unexpectedThreadId = UUID.randomUUID().toString();

            var traces = IntStream.range(0, 5)
                    .mapToObj(it -> {
                        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                        return createTrace().toBuilder()
                                .projectName(projectName)
                                .usage(null)
                                .threadId(threadId)
                                .endTime(now.plus(it, ChronoUnit.MILLIS))
                                .startTime(now)
                                .build();
                    })
                    .collect(Collectors.toList());

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

            List<Trace> createTraces = traceResourceClient.getByProjectName(projectName, API_KEY, TEST_WORKSPACE);
            List<Trace> expectedTraces = getExpectedThreads.apply(createTraces);

            var otherTraces = IntStream.range(0, 5)
                    .mapToObj(it -> createTrace().toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .threadId(unexpectedThreadId)
                            .build())
                    .collect(Collectors.toList());

            List<Trace> unexpectedTraces = getUnexpectedThreads.apply(otherTraces);

            traceResourceClient.batchCreateTraces(unexpectedTraces, API_KEY, TEST_WORKSPACE);

            var projectId = getProjectId(projectName, TEST_WORKSPACE, API_KEY);

            List<TraceThread> expectedThreads = getExpectedThreads(expectedTraces, projectId, threadId, List.of(),
                    TraceThreadStatus.ACTIVE);

            var filter = getFilter.apply(expectedTraces);

            if (!stream) {
                assertThreadPage(projectName, null, expectedThreads, List.of(filter), Map.of(), API_KEY,
                        TEST_WORKSPACE);
            } else {
                assertTheadStream(projectName, null, API_KEY, TEST_WORKSPACE, expectedThreads, List.of(filter));
            }
        }

        private Stream<Arguments> getStatusFilterTestArguments() {
            return Stream.of(
                    Arguments.of(true, TraceThreadStatus.ACTIVE, false),
                    Arguments.of(true, TraceThreadStatus.INACTIVE, true),
                    Arguments.of(false, TraceThreadStatus.ACTIVE, false),
                    Arguments.of(false, TraceThreadStatus.INACTIVE, true));
        }

        @ParameterizedTest
        @MethodSource("getStatusFilterTestArguments")
        @DisplayName("When filtering by thread status, should return only threads with matching status")
        void whenFilterByStatus__thenReturnThreadsWithMatchingStatus(boolean stream, TraceThreadStatus filterStatus,
                boolean shouldCloseThread) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var threadId = UUID.randomUUID().toString();

            // Create traces
            var traces = IntStream.range(0, 3)
                    .mapToObj(it -> {
                        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                        return createTrace().toBuilder()
                                .projectName(projectName)
                                .usage(null)
                                .threadId(threadId)
                                .endTime(now.plus(it, ChronoUnit.MILLIS))
                                .startTime(now)
                                .build();
                    })
                    .collect(Collectors.toList());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Close the thread if needed to set its status to INACTIVE
            if (shouldCloseThread) {
                Mono.delay(Duration.ofMillis(500)).block();
                traceResourceClient.closeTraceThread(threadId, null, projectName, apiKey, workspaceName);
            }

            var projectId = getProjectId(projectName, workspaceName, apiKey);

            // Create expected threads with the appropriate status
            TraceThreadStatus expectedStatus = shouldCloseThread
                    ? TraceThreadStatus.INACTIVE
                    : TraceThreadStatus.ACTIVE;

            List<TraceThread> expectedThreads = getExpectedThreads(traces, projectId, threadId, List.of(),
                    expectedStatus);

            // Create filter for the specified status
            var statusFilter = TraceThreadFilter.builder()
                    .field(TraceThreadField.STATUS)
                    .operator(Operator.EQUAL)
                    .value(filterStatus.getValue())
                    .build();

            if (!stream) {
                // When not streaming, assert the thread page with the status filter
                assertThreadPage(null, projectId, expectedThreads, List.of(statusFilter), Map.of(), apiKey,
                        workspaceName);
            } else {
                // When streaming, assert the threads with the status filter
                assertTheadStream(null, projectId, apiKey, workspaceName, expectedThreads, List.of(statusFilter));
            }
        }

        @Test
        @DisplayName("When filtering by thread tag, should return only threads with matching tags")
        void whenFilterByTags__thenReturnThreadsWithMatchingTags() {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var threadId = UUID.randomUUID().toString();

            // Create traces
            var traces = IntStream.range(0, 3)
                    .mapToObj(it -> {
                        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                        return createTrace().toBuilder()
                                .projectName(projectName)
                                .usage(null)
                                .threadId(threadId)
                                .endTime(now.plus(it, ChronoUnit.MILLIS))
                                .startTime(now)
                                .build();
                    })
                    .collect(Collectors.toList());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var projectId = getProjectId(projectName, workspaceName, apiKey);

            // Wait for thread to be created
            Mono.delay(Duration.ofMillis(250)).block();

            var createdThread = traceResourceClient.getTraceThread(threadId, projectId, apiKey, workspaceName);

            // Add tags to the thread
            var update = factory.manufacturePojo(TraceThreadUpdate.class);
            traceResourceClient.updateThread(update, createdThread.threadModelId(), apiKey, workspaceName, 204);

            List<TraceThread> expectedThreads = List.of(createdThread.toBuilder().tags(update.tags()).build());

            // Create filter for the specified status
            var statusFilter = TraceThreadFilter.builder()
                    .field(TraceThreadField.TAGS)
                    .operator(Operator.CONTAINS)
                    .value(update.tags().iterator().next())
                    .build();

            assertThreadPage(null, projectId, expectedThreads, List.of(statusFilter), Map.of(), apiKey,
                    workspaceName);
            assertTheadStream(null, projectId, apiKey, workspaceName, expectedThreads, List.of(statusFilter));
        }

        @Test
        @DisplayName("When filtering by annotation queue id, should return only threads with matching queue ids")
        void whenFilterByAnnotationQueueId__thenReturnThreadsWithMatchingTags() {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);
            var threadId = UUID.randomUUID().toString();

            // Create traces
            var traces = IntStream.range(0, 3)
                    .mapToObj(it -> {
                        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                        return createTrace().toBuilder()
                                .projectName(project.name())
                                .usage(null)
                                .threadId(threadId)
                                .endTime(now.plus(it, ChronoUnit.MILLIS))
                                .startTime(now)
                                .build();
                    })
                    .collect(Collectors.toList());

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Wait for thread to be created
            Mono.delay(Duration.ofMillis(250)).block();

            var createdThread = traceResourceClient.getTraceThread(threadId, projectId, apiKey, workspaceName);

            // Create annotation queue for threads
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .scope(AnnotationQueue.AnnotationScope.THREAD)
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    annotationQueue.id(), Set.of(createdThread.threadModelId()), apiKey, workspaceName,
                    HttpStatus.SC_NO_CONTENT);

            List<TraceThread> expectedThreads = List.of(createdThread);

            // Create filter for the specified status
            var statusFilter = TraceThreadFilter.builder()
                    .field(TraceThreadField.ANNOTATION_QUEUE_IDS)
                    .operator(Operator.CONTAINS)
                    .value(annotationQueue.id().toString())
                    .build();

            assertThreadPage(null, projectId, expectedThreads, List.of(statusFilter), Map.of(), apiKey,
                    workspaceName);
            assertTheadStream(null, projectId, apiKey, workspaceName, expectedThreads, List.of(statusFilter));
        }

        @ParameterizedTest
        @EnumSource(Direction.class)
        @DisplayName("When sorting threads by feedback score, then threads are returned in correct order")
        void sortThreadsByFeedbackScore_withDirection_thenThreadsReturnedInCorrectOrder(Direction direction) {
            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var project = factory.manufacturePojo(Project.class).toBuilder()
                    .name(projectName)
                    .build();

            UUID projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create threads with different feedback scores
            var threadId1 = UUID.randomUUID().toString();
            var threadId2 = UUID.randomUUID().toString();
            var threadId3 = UUID.randomUUID().toString();

            // Create traces for threads
            Trace trace1 = createTrace().toBuilder()
                    .threadId(threadId1)
                    .projectId(projectId)
                    .projectName(projectName)
                    .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                    .build();

            Trace trace2 = createTrace().toBuilder()
                    .threadId(threadId2)
                    .projectId(projectId)
                    .projectName(projectName)
                    .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                    .build();

            Trace trace3 = createTrace().toBuilder()
                    .threadId(threadId3)
                    .projectId(projectId)
                    .projectName(projectName)
                    .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                    .build();

            traceResourceClient.batchCreateTraces(List.of(trace1, trace2, trace3), apiKey, workspaceName);

            // Ensure traces are created with a delay
            Mono.delay(Duration.ofMillis(500)).block();

            // Close the threads to set their status to INACTIVE
            traceResourceClient.closeTraceThread(threadId1, null, projectName, apiKey, workspaceName);
            traceResourceClient.closeTraceThread(threadId2, null, projectName, apiKey, workspaceName);
            traceResourceClient.closeTraceThread(threadId3, null, projectName, apiKey, workspaceName);

            // Add feedback scores with different values
            String scoreName = RandomStringUtils.secure().nextAlphanumeric(10);

            List<FeedbackScoreItem.FeedbackScoreBatchItemThread> scoreItems = Stream.of(threadId1, threadId2, threadId3)
                    .map(threadId -> factory.manufacturePojo(FeedbackScoreItem.FeedbackScoreBatchItemThread.class)
                            .toBuilder()
                            .threadId(threadId)
                            .projectName(projectName)
                            .name(scoreName)
                            .build())
                    .collect(toList());

            Instant now = Instant.now();
            traceResourceClient.threadFeedbackScores(scoreItems, apiKey, workspaceName);

            // Create feedback scores for expected threads
            var feedbackScores = scoreItems.stream()
                    .collect(Collectors.toMap(
                            FeedbackScoreItem::threadId,
                            item -> List.of(createExpectedFeedbackScore(item, now))));

            // Create expected threads in the correct order based on direction
            List<TraceThread> expectedThreads = Stream.of(
                    getExpectedThreads(List.of(trace1), projectId, threadId1, List.of(), TraceThreadStatus.INACTIVE,
                            feedbackScores.get(threadId1)).getFirst(),
                    getExpectedThreads(List.of(trace2), projectId, threadId2, List.of(), TraceThreadStatus.INACTIVE,
                            feedbackScores.get(threadId2)).getFirst(),
                    getExpectedThreads(List.of(trace3), projectId, threadId3, List.of(), TraceThreadStatus.INACTIVE,
                            feedbackScores.get(threadId3)).getFirst())
                    .sorted(Comparator.comparing(thread -> {
                        var score = feedbackScores.get(thread.id()).stream()
                                .filter(fs -> fs.name().equals(scoreName))
                                .findFirst()
                                .orElseThrow();
                        return direction == Direction.ASC ? score.value() : score.value().negate();
                    }))
                    .toList();

            // When & Then - Sort by feedback scores and verify using assertThreadPage
            var sortingFields = List.of(
                    SortingField.builder()
                            .field("feedback_scores." + scoreName)
                            .direction(direction)
                            .build());

            assertThreadPage(projectName, null, expectedThreads, List.of(), Map.of(), apiKey, workspaceName,
                    sortingFields);
        }

        private Stream<Arguments> getFeedbackScoreFilterTestArguments() {
            return Stream.of(
                    Arguments.of(
                            true,
                            Operator.EQUAL,
                            generateExpectedIndices(),
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) this::generateExpectedEqualsMatch,
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) this::generateUnexpectedEqualsMatch,
                            (Function<BigDecimal, String>) BigDecimal::toString), // Filter value function for EQUAL
                    Arguments.of(
                            false,
                            Operator.EQUAL,
                            generateExpectedIndices(),
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) this::generateExpectedEqualsMatch,
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) this::generateUnexpectedEqualsMatch,
                            (Function<BigDecimal, String>) BigDecimal::toString),
                    Arguments.of(
                            true,
                            Operator.IS_NOT_EMPTY,
                            generateExpectedIndices(),
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) (
                                    name,
                                    value) -> generateNotEmptyMatch(name),
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) (
                                    name,
                                    value) -> generateUnexpectedNotEmptyMatch(name),
                            (Function<BigDecimal, String>) value -> ""), // Empty value for IS_NOT_EMPTY
                    Arguments.of(
                            false,
                            Operator.IS_NOT_EMPTY,
                            generateExpectedIndices(),
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) (
                                    name,
                                    value) -> generateNotEmptyMatch(name),
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) (
                                    name,
                                    value) -> generateUnexpectedNotEmptyMatch(name),
                            (Function<BigDecimal, String>) value -> ""),
                    Arguments.of(
                            true,
                            Operator.IS_EMPTY,
                            generateExpectedIndices(),
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) (
                                    name,
                                    value) -> generateIsEmptyMatch(name),
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) (
                                    name,
                                    value) -> generateNotEmptyMatch(name),
                            (Function<BigDecimal, String>) value -> ""), // Empty value for IS_EMPTY
                    Arguments.of(
                            false,
                            Operator.IS_EMPTY,
                            generateExpectedIndices(),
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) (
                                    name,
                                    value) -> generateIsEmptyMatch(name),
                            (BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>>) (
                                    name,
                                    value) -> generateNotEmptyMatch(name),
                            (Function<BigDecimal, String>) value -> "")

            );
        }

        private Set<Integer> generateExpectedIndices() {
            return new HashSet<>(List.of(RandomUtils.secure().randomInt(0, 5), RandomUtils.secure().randomInt(0, 5)));
        }

        private List<FeedbackScoreItem.FeedbackScoreBatchItemThread> generateIsEmptyMatch(String name) {
            return PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreItem.FeedbackScoreBatchItemThread.class)
                    .stream()
                    .filter(score -> !score.name().equals(name))
                    .toList();
        }

        private List<FeedbackScoreItem.FeedbackScoreBatchItemThread> generateNotEmptyMatch(String name) {
            List<FeedbackScoreItem.FeedbackScoreBatchItemThread> scores = PodamFactoryUtils.manufacturePojoList(factory,
                    FeedbackScoreItem.FeedbackScoreBatchItemThread.class);

            scores.set(0, scores.getFirst().toBuilder()
                    .name(name)
                    .build());

            return scores;
        }

        private List<FeedbackScoreItem.FeedbackScoreBatchItemThread> generateUnexpectedNotEmptyMatch(String name) {
            return PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreItem.FeedbackScoreBatchItemThread.class)
                    .stream()
                    .filter(score -> !score.name().equals(name))
                    .toList();
        }

        private List<FeedbackScoreItem.FeedbackScoreBatchItemThread> generateExpectedEqualsMatch(String name,
                BigDecimal value) {
            List<FeedbackScoreItem.FeedbackScoreBatchItemThread> scores = PodamFactoryUtils.manufacturePojoList(factory,
                    FeedbackScoreItem.FeedbackScoreBatchItemThread.class);

            scores.set(0, scores.getFirst().toBuilder()
                    .name(name)
                    .value(value)
                    .build());

            return scores;
        }

        private List<FeedbackScoreItem.FeedbackScoreBatchItemThread> generateUnexpectedEqualsMatch(String name,
                BigDecimal value) {
            List<FeedbackScoreItem.FeedbackScoreBatchItemThread> scores = PodamFactoryUtils.manufacturePojoList(factory,
                    FeedbackScoreItem.FeedbackScoreBatchItemThread.class);

            scores.set(0, scores.getFirst().toBuilder()
                    .name(name)
                    .build());

            return scores;
        }

        @ParameterizedTest
        @MethodSource("getFeedbackScoreFilterTestArguments")
        @DisplayName("When filtering by feedback score with different operators, should return matching threads")
        void whenFilterByFeedbackScore__thenReturnThreadsWithMatchingFeedbackScore(
                boolean stream,
                Operator operator,
                Set<Integer> expectedThreadIndices,
                BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>> matchingScoreFunction,
                BiFunction<String, BigDecimal, List<FeedbackScoreItem.FeedbackScoreBatchItemThread>> unmatchingScoreFunction,
                Function<BigDecimal, String> filterValueFunction) {

            // Given
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var project = factory.manufacturePojo(Project.class).toBuilder()
                    .name(projectName)
                    .build();

            UUID projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create threads with different feedback scores
            var allThreadIds = PodamFactoryUtils.manufacturePojoList(factory, UUID.class);

            // Create traces for threads
            var allTraces = allThreadIds
                    .stream()
                    .map(threadId -> createTrace().toBuilder()
                            .threadId(threadId.toString())
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().minusSeconds(3).truncatedTo(ChronoUnit.MICROS))
                            .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                            .build())
                    .toList();

            allTraces.forEach(trace -> traceResourceClient.createTrace(trace, apiKey, workspaceName));

            // Add feedback scores with different values
            Map<String, Instant> threadIdAndLastUpdateAts = new HashMap<>();

            Mono.delay(Duration.ofMillis(500)).block();
            allThreadIds.forEach(threadId -> {
                threadIdAndLastUpdateAts.put(threadId.toString(), Instant.now());
                traceResourceClient.closeTraceThread(threadId.toString(), null, projectName, apiKey, workspaceName);
            });

            String targetScoreName = RandomStringUtils.secure().nextAlphanumeric(30);
            BigDecimal targetScoreValue = factory.manufacturePojo(BigDecimal.class);

            // Create feedback scores based on the provided map
            List<FeedbackScoreItem.FeedbackScoreBatchItemThread> expectedScores = allThreadIds
                    .stream()
                    .filter(threadId -> isExpected(expectedThreadIndices, threadId, allThreadIds))
                    .flatMap(threadId -> {
                        return matchingScoreFunction.apply(targetScoreName, targetScoreValue).stream()
                                .map(item -> item.toBuilder()
                                        .threadId(threadId.toString())
                                        .projectName(projectName)
                                        .build());
                    }).collect(Collectors.toList());

            List<FeedbackScoreItem.FeedbackScoreBatchItemThread> unexpectedScores = allThreadIds.stream()
                    .filter(threadId -> !isExpected(expectedThreadIndices, threadId, allThreadIds))
                    .flatMap(threadId -> {
                        return unmatchingScoreFunction.apply(targetScoreName, targetScoreValue).stream()
                                .map(item -> item.toBuilder()
                                        .threadId(threadId.toString())
                                        .projectName(projectName)
                                        .build());
                    }).collect(Collectors.toList());

            List<FeedbackScoreItem.FeedbackScoreBatchItemThread> scoreItems = Stream
                    .concat(expectedScores.stream(), unexpectedScores.stream())
                    .toList();

            // Create feedback scores for threads
            Instant feedbackScoreCreationTime = Instant.now();
            traceResourceClient.threadFeedbackScores(scoreItems, apiKey, workspaceName);

            // Determine expected threads based on indices
            var expectedThreadIds = allThreadIds.reversed()
                    .stream()
                    .filter(threadId -> isExpected(expectedThreadIndices, threadId, allThreadIds))
                    .map(UUID::toString)
                    .toList();

            // Create expected threads with ALL feedback scores from matching threads
            Comparator<TraceThread> comparing = Comparator
                    .comparing((TraceThread traceThread) -> threadIdAndLastUpdateAts.get(traceThread.id())).reversed();

            List<TraceThread> expectedThreads = expectedThreadIds.stream()
                    .map(threadId -> {
                        // Get ALL feedback scores for this thread (both expected and unexpected)
                        var allFeedbackScoresForThread = scoreItems.stream()
                                .filter(item -> item.threadId().equals(threadId))
                                .map(item -> createExpectedFeedbackScore(item, feedbackScoreCreationTime))
                                .toList();

                        var traces = allTraces.stream()
                                .filter(trace -> trace.threadId().equals(threadId))
                                .toList();

                        return getExpectedThreads(traces, projectId, threadId, List.of(),
                                TraceThreadStatus.INACTIVE, allFeedbackScoresForThread).getFirst();
                    })
                    .sorted(comparing)
                    .toList();

            // Create filter for the specific feedback score
            var feedbackScoreFilter = TraceThreadFilter.builder()
                    .field(TraceThreadField.FEEDBACK_SCORES)
                    .operator(operator)
                    .key(targetScoreName)
                    .value(filterValueFunction.apply(targetScoreValue))
                    .build();

            // When & Then
            if (!stream) {
                assertThreadPage(null, projectId, expectedThreads, List.of(feedbackScoreFilter), Map.of(), apiKey,
                        workspaceName);
            } else {
                assertTheadStream(null, projectId, apiKey, workspaceName, expectedThreads,
                        List.of(feedbackScoreFilter));
            }
        }

        private static boolean isExpected(Set<Integer> expectedThreadIndices, UUID threadId, List<UUID> allThreadIds) {
            return expectedThreadIndices.stream()
                    .anyMatch(index -> allThreadIds.get(index).toString().equals(threadId.toString()));
        }
    }

    private FeedbackScore createExpectedFeedbackScore(FeedbackScoreItem item, Instant now) {
        return FeedbackScore.builder()
                .name(item.name())
                .value(item.value())
                .categoryName(item.categoryName())
                .source(item.source())
                .reason(item.reason())
                .lastUpdatedAt(now)
                .createdAt(now)
                .createdBy(USER)
                .lastUpdatedBy(USER)
                .build();
    }

    private Trace fromBuilder(Trace.TraceBuilder builder) {
        return builder
                .feedbackScores(null)
                .threadId(null)
                .comments(null)
                .totalEstimatedCost(null)
                .usage(null)
                .errorInfo(null)
                .build();
    }

    private Trace createTrace() {
        return fromBuilder(factory.manufacturePojo(Trace.class).toBuilder());
    }

    private List<TraceThread> getExpectedThreads(List<Trace> expectedTraces, UUID projectId, String threadId,
            List<Span> spans, TraceThreadStatus status) {
        return getExpectedThreads(expectedTraces, projectId, threadId, spans, status, null);
    }

    private List<TraceThread> getExpectedThreads(List<Trace> expectedTraces, UUID projectId, String threadId,
            List<Span> spans, TraceThreadStatus status, List<FeedbackScore> feedbackScores) {

        return expectedTraces.isEmpty()
                ? List.of()
                : List.of(TraceThread.builder()
                        .firstMessage(expectedTraces.stream().min(Comparator.comparing(Trace::startTime)).orElseThrow()
                                .input())
                        .lastMessage(expectedTraces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                                .output())
                        .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                expectedTraces.stream().min(Comparator.comparing(Trace::startTime)).orElseThrow()
                                        .startTime(),
                                expectedTraces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                                        .endTime()))
                        .projectId(projectId)
                        .createdBy(USER)
                        .startTime(expectedTraces.stream().min(Comparator.comparing(Trace::startTime)).orElseThrow()
                                .startTime())
                        .endTime(expectedTraces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                                .endTime())
                        .numberOfMessages(expectedTraces.size() * 2L)
                        .id(threadId)
                        .totalEstimatedCost(Optional.ofNullable(getTotalEstimatedCost(spans))
                                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                                .orElse(null))
                        .usage(getUsage(spans))
                        .status(status)
                        .feedbackScores(feedbackScores)
                        .createdAt(expectedTraces.stream().min(Comparator.comparing(Trace::createdAt)).orElseThrow()
                                .createdAt())
                        .lastUpdatedAt(
                                expectedTraces.stream().max(Comparator.comparing(Trace::lastUpdatedAt)).orElseThrow()
                                        .lastUpdatedAt())
                        .build());
    }

    private Map<String, Long> getUsage(List<Span> spans) {
        return Optional.ofNullable(spans)
                .map(this::aggregateSpansUsage)
                .filter(not(Map::isEmpty))
                .orElse(null);
    }

    private BigDecimal getTotalEstimatedCost(List<Span> spans) {
        boolean shouldUseTotalEstimatedCostField = spans.stream().allMatch(span -> span.totalEstimatedCost() != null);

        if (shouldUseTotalEstimatedCostField) {
            return spans.stream()
                    .map(Span::totalEstimatedCost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        return calculateEstimatedCost(spans);
    }

    @Nested
    @DisplayName("Find Trace Threads With Time Filtering:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindTraceThreadsTimeFilteringTests {

        private void createAndCloseThreads(List<Trace> traces, String projectName, String apiKey,
                String workspaceName) {
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            Mono.delay(Duration.ofMillis(1000)).block();

            traces.stream()
                    .map(Trace::threadId)
                    .distinct()
                    .forEach(threadId -> traceResourceClient.closeTraceThread(threadId, null, projectName, apiKey,
                            workspaceName));

            Mono.delay(Duration.ofMillis(1000)).block();
        }

        private void assertTimeFilteredThreads(boolean stream, List<TraceThread> expectedThreads, String projectName,
                Instant fromTime, Instant toTime, String apiKey, String workspaceName) {
            if (!stream) {
                // Paginated endpoint orders by: last_updated_at DESC
                // Threads are closed sequentially, so last closed appears first
                var sortedExpected = new ArrayList<>(expectedThreads);
                Collections.reverse(sortedExpected);

                var queryParams = Optional.ofNullable(toTime).map(time -> Map.of(
                        "from_time", fromTime.toString(),
                        "to_time", toTime.toString())).orElse(Map.of("from_time", fromTime.toString()));

                assertThreadPage(projectName, null, sortedExpected, List.of(), queryParams, apiKey, workspaceName);
            } else {
                // Stream endpoint orders by: thread_model_id DESC (based on first trace timestamp)
                var sortedExpected = expectedThreads.stream()
                        .sorted(Comparator.comparing(TraceThread::startTime).reversed())
                        .toList();

                var request = TraceThreadSearchStreamRequest.builder()
                        .projectName(projectName)
                        .fromTime(fromTime)
                        .toTime(toTime)
                        .build();

                var actualThreads = traceResourceClient.searchTraceThreadsStream(request, apiKey, workspaceName);
                TraceAssertions.assertThreads(sortedExpected, actualThreads);
            }
        }

        // Scenario 1: Boundary condition testing - threads with traces at exact lower bound, upper bound, and in between
        @ParameterizedTest
        @DisplayName("filter trace threads by UUID creation time - includes threads within bounds")
        @MethodSource("provideBoundaryScenarios")
        void whenTimeParametersProvided_thenIncludeThreadsWithinBounds(boolean stream) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Instant baseTime = Instant.now();
            Instant lowerBound = baseTime.minus(Duration.ofMinutes(20));
            Instant upperBound = baseTime.plus(Duration.ofMinutes(20));

            List<Trace> allTraces = List.of(
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(),
                            lowerBound.plus(Duration.ofMinutes(5)), "Within bounds"),
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(), baseTime, "Within bounds"),
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(),
                            upperBound.minus(Duration.ofMinutes(5)), "Within bounds"));

            createAndCloseThreads(allTraces, projectName, apiKey, workspaceName);

            var projectId = projectResourceClient.getByName(projectName, apiKey, workspaceName).id();

            List<TraceThread> expectedThreads = allTraces.stream()
                    .map(trace -> getExpectedThreads(List.of(trace), projectId, trace.threadId(), List.of(),
                            TraceThreadStatus.INACTIVE).getFirst())
                    .toList();

            assertTimeFilteredThreads(stream, expectedThreads, projectName, lowerBound, upperBound, apiKey,
                    workspaceName);
        }

        // Scenario 2: ISO-8601 format parsing with extended time range
        @ParameterizedTest
        @DisplayName("time parameters in ISO-8601 format parse correctly and filter trace threads")
        @MethodSource("provideBoundaryScenarios")
        void whenTimeParametersInISO8601Format_thenReturnFilteredThreads(boolean stream) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Instant referenceTime = Instant.now();
            Instant startTime = referenceTime.minus(Duration.ofHours(2));
            Instant endTime = referenceTime.plus(Duration.ofHours(1));
            Instant withinBoundsTime = referenceTime.minus(Duration.ofMinutes(30));
            Instant outsideBoundsTime = endTime.plus(Duration.ofMinutes(30));

            List<Trace> allTraces = List.of(
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(),
                            startTime.plus(Duration.ofMinutes(10)), "Should be included: near start of range"),
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(), withinBoundsTime,
                            "Should be included: within range"),
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(),
                            endTime.minus(Duration.ofMinutes(10)), "Should be included: near end of range"),
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(), outsideBoundsTime,
                            "Should NOT be included: outside range"));

            createAndCloseThreads(allTraces, projectName, apiKey, workspaceName);

            var projectId = projectResourceClient.getByName(projectName, apiKey, workspaceName).id();

            // Expected: first 3 threads (within bounds)
            List<Trace> expectedTraces = allTraces.subList(0, 3);
            List<TraceThread> expectedThreads = expectedTraces.stream()
                    .map(trace -> getExpectedThreads(List.of(trace), projectId, trace.threadId(), List.of(),
                            TraceThreadStatus.INACTIVE).getFirst())
                    .toList();

            assertTimeFilteredThreads(stream, expectedThreads, projectName, startTime, endTime, apiKey, workspaceName);
        }

        // Scenario 3: Threads outside bounds should be excluded
        @ParameterizedTest
        @DisplayName("filter trace threads by UUID creation time - excludes threads outside bounds")
        @MethodSource("provideBoundaryScenarios")
        void whenTimeParametersProvided_thenExcludeThreadsOutsideBounds(boolean stream) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Instant baseTime = Instant.now();
            Instant lowerBound = baseTime.minus(Duration.ofHours(1));
            Instant upperBound = baseTime.plus(Duration.ofHours(1));

            List<Trace> allTraces = List.of(
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(),
                            lowerBound.plus(Duration.ofMinutes(10)), "Within bounds"),
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(), baseTime, "Within bounds"),
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(),
                            upperBound.minus(Duration.ofMinutes(10)), "Within bounds"),
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(),
                            lowerBound.minus(Duration.ofMinutes(10)), "Outside bounds (before lower)"),
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(),
                            upperBound.plus(Duration.ofMinutes(10)), "Outside bounds (after upper)"));

            createAndCloseThreads(allTraces, projectName, apiKey, workspaceName);

            var projectId = projectResourceClient.getByName(projectName, apiKey, workspaceName).id();

            // Expected: first 3 threads (within bounds) - exclude outside traces
            List<Trace> expectedTraces = allTraces.subList(0, 3);
            List<TraceThread> expectedThreads = expectedTraces.stream()
                    .map(trace -> getExpectedThreads(List.of(trace), projectId, trace.threadId(), List.of(),
                            TraceThreadStatus.INACTIVE).getFirst())
                    .toList();

            assertTimeFilteredThreads(stream, expectedThreads, projectName, lowerBound, upperBound, apiKey,
                    workspaceName);
        }

        // Scenario 4: time filtering works with only from_time parameter
        @ParameterizedTest
        @DisplayName("time filtering works with only from_time parameter - to_time is optional")
        @MethodSource("provideBoundaryScenarios")
        void whenOnlyFromTimeProvided_thenFilterThreadsFromThatTime(boolean stream) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Instant baseTime = Instant.now();
            Instant lowerBound = baseTime.minus(Duration.ofHours(1));
            Instant upperBound = null; // to_time is optional - test without it

            List<Trace> allTraces = List.of(
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(),
                            lowerBound.plus(Duration.ofMinutes(10)), "Within bounds"),
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(), baseTime, "Within bounds"),
                    createTraceAtTimestamp(projectName, UUID.randomUUID().toString(),
                            lowerBound.minus(Duration.ofMinutes(10)), "Outside bounds (before lower)"));

            createAndCloseThreads(allTraces, projectName, apiKey, workspaceName);

            var projectId = projectResourceClient.getByName(projectName, apiKey, workspaceName).id();

            // Expected: first 2 threads (from lowerBound onwards) - exclude trace before lowerBound
            List<Trace> expectedTraces = allTraces.subList(0, 2);
            List<TraceThread> expectedThreads = expectedTraces.stream()
                    .map(trace -> getExpectedThreads(List.of(trace), projectId, trace.threadId(), List.of(),
                            TraceThreadStatus.INACTIVE).getFirst())
                    .toList();

            assertTimeFilteredThreads(stream, expectedThreads, projectName, lowerBound, upperBound, apiKey,
                    workspaceName);
        }

        // Scenario 5: from_time must be before to_time
        @ParameterizedTest
        @DisplayName("from_time must be before to_time")
        @MethodSource("provideBoundaryScenarios")
        void whenFromTimeAfterToTime_thenReturnBadRequest(boolean stream) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            Instant now = Instant.now();
            Instant earlier = now.minus(Duration.ofMinutes(10));

            // from_time (now) is after to_time (earlier) - should fail
            if (!stream) {
                var queryParams = Map.of(
                        "from_time", now.toString(),
                        "to_time", earlier.toString());
                var actualResponse = traceResourceClient.callGetTraceThreadsWithQueryParams(projectName, null,
                        queryParams, apiKey, workspaceName);
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            } else {
                var request = TraceThreadSearchStreamRequest.builder()
                        .projectName(projectName)
                        .fromTime(now)
                        .toTime(earlier)
                        .build();

                try (var response = traceResourceClient.callSearchTraceThreadsWithRequest(request, apiKey,
                        workspaceName)) {
                    assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                }
            }
        }

        private Stream<Arguments> provideBoundaryScenarios() {
            return Stream.of(
                    Arguments.of(false), // Non-stream (paginated)
                    Arguments.of(true) // Stream search
            );
        }

        private Trace createTraceAtTimestamp(String projectName, String threadId, Instant timestamp, String comment) {
            return createTrace().toBuilder()
                    .projectName(projectName)
                    .threadId(threadId)
                    .id(idGenerator.generateId(timestamp))
                    .spanCount(0)
                    .llmSpanCount(0)
                    .guardrailsValidations(null)
                    .build();
        }
    }

    @Nested
    @DisplayName("Get Thread Stats:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetThreadStats {

        private Long getThreadCount(ProjectStats stats) {
            return stats.stats().stream()
                    .filter(stat -> stat.getName().equals("thread_count"))
                    .findFirst()
                    .map(stat -> ((ProjectStats.CountValueStat) stat).getValue())
                    .orElseThrow(() -> new AssertionError("thread_count stat not found"));
        }

        private List<BigDecimal> getThreadDurationQuantiles(List<Trace> traces) {
            // Group traces by thread_id and calculate duration per thread
            var threadDurations = traces.stream()
                    .collect(Collectors.groupingBy(Trace::threadId))
                    .values()
                    .stream()
                    .map(threadTraces -> {
                        var minStartTime = threadTraces.stream()
                                .map(Trace::startTime)
                                .min(Comparator.naturalOrder())
                                .orElseThrow();
                        var maxEndTime = threadTraces.stream()
                                .map(Trace::endTime)
                                .filter(java.util.Objects::nonNull)
                                .max(Comparator.naturalOrder())
                                .orElse(null);
                        if (maxEndTime == null) {
                            return null;
                        }
                        return minStartTime.until(maxEndTime, ChronoUnit.MICROS) / 1_000.0;
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();

            return StatsUtils.calculateQuantiles(
                    threadDurations,
                    List.of(0.50, 0.90, 0.99));
        }

        private List<ProjectStats.ProjectStatItem<?>> buildExpectedThreadStats(
                List<Trace> traces,
                List<Span> spans,
                List<FeedbackScoreBatchItemThread> feedbackScores) {
            var expectedStats = new ArrayList<ProjectStats.ProjectStatItem<?>>();

            // Thread count
            long threadCount = traces.stream()
                    .map(Trace::threadId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .count();
            expectedStats.add(new ProjectStats.CountValueStat("thread_count", threadCount));

            // Duration percentiles across threads
            var durationPercentiles = getThreadDurationQuantiles(traces);
            if (!durationPercentiles.isEmpty()) {
                expectedStats.add(new ProjectStats.PercentageValueStat("duration",
                        new com.comet.opik.api.PercentageValues(
                                durationPercentiles.get(0),
                                durationPercentiles.get(1),
                                durationPercentiles.get(2))));
            }

            // Input, output, metadata counts (not applicable for threads)
            expectedStats.add(new ProjectStats.CountValueStat("input", 0L));
            expectedStats.add(new ProjectStats.CountValueStat("output", 0L));
            expectedStats.add(new ProjectStats.CountValueStat("metadata", 0L));

            // Tags (not applicable for thread stats aggregation)
            expectedStats.add(new ProjectStats.AvgValueStat("tags", 0.0));

            // Calculate total cost across all threads
            var totalCost = calculateEstimatedCost(spans);
            var avgCostPerThread = threadCount > 0
                    ? totalCost.divide(BigDecimal.valueOf(threadCount), java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            expectedStats.add(new ProjectStats.AvgValueStat("total_estimated_cost", avgCostPerThread.doubleValue()));
            expectedStats.add(new ProjectStats.AvgValueStat("total_estimated_cost_sum", totalCost.doubleValue()));

            // Calculate usage (tokens) - average across threads
            var threadUsage = aggregateSpansUsage(spans);
            if (threadUsage != null) {
                threadUsage.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> {
                            double avgUsagePerThread = threadCount > 0
                                    ? entry.getValue().doubleValue() / threadCount
                                    : 0.0;
                            expectedStats.add(new ProjectStats.AvgValueStat("usage." + entry.getKey(),
                                    avgUsagePerThread));
                        });
            }

            // Calculate feedback scores - average across threads
            if (feedbackScores != null && !feedbackScores.isEmpty()) {
                feedbackScores.stream()
                        .collect(Collectors.groupingBy(FeedbackScoreBatchItemThread::name))
                        .entrySet()
                        .stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> {
                            var avgScore = entry.getValue().stream()
                                    .map(FeedbackScoreBatchItemThread::value)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .divide(BigDecimal.valueOf(entry.getValue().size()),
                                            java.math.RoundingMode.HALF_UP);
                            expectedStats.add(new ProjectStats.AvgValueStat("feedback_scores." + entry.getKey(),
                                    avgScore.doubleValue()));
                        });
            }

            // Guardrails and errors (not tracked in thread stats)
            expectedStats.add(new ProjectStats.CountValueStat("guardrails_failed_count", 0L));
            expectedStats.add(new ProjectStats.CountValueStat("error_count", 0L));

            return expectedStats;
        }

        @Test
        @DisplayName("When getting thread stats with no threads, should return empty stats")
        void whenNoThreads__thenReturnEmptyStats() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            projectResourceClient.createProject(
                    factory.manufacturePojo(Project.class).toBuilder().name(projectName).build(),
                    apiKey, workspaceName);

            var stats = traceResourceClient.getTraceThreadStats(projectName, null, apiKey, workspaceName, List.of(),
                    Map.of());

            assertThat(stats.stats()).isEmpty();
        }

        @Test
        @DisplayName("When getting thread stats with single thread, should return correct aggregated stats")
        void whenSingleThread__thenReturnCorrectStats() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var threadId = UUID.randomUUID().toString();

            // Create traces with thread
            var traces = IntStream.range(0, 3)
                    .mapToObj(it -> {
                        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                        return createTrace().toBuilder()
                                .projectName(projectName)
                                .usage(null)
                                .threadId(threadId)
                                .endTime(now.plus(it, ChronoUnit.MILLIS))
                                .startTime(now)
                                .build();
                    })
                    .toList();

            // Create spans with usage and cost
            List<Span> spans = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .usage(spanResourceClient.getTokenUsage())
                                    .model(spanResourceClient.randomModel().toString())
                                    .provider(spanResourceClient.provider())
                                    .traceId(trace.id())
                                    .projectName(projectName)
                                    .totalEstimatedCost(null)
                                    .build()))
                    .toList();

            batchCreateSpansAndAssert(spans, apiKey, workspaceName);
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var stats = traceResourceClient.getTraceThreadStats(projectName, null, apiKey, workspaceName, List.of(),
                    Map.of());

            // Build expected stats from traces and spans
            var expectedStats = buildExpectedThreadStats(traces, spans, null);

            // Assert all stats match expected
            TraceAssertions.assertStats(stats.stats(), expectedStats);
        }

        @Test
        @DisplayName("When getting thread stats with multiple threads, should aggregate correctly")
        void whenMultipleThreads__thenAggregateCorrectly() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var threadId1 = UUID.randomUUID().toString();
            var threadId2 = UUID.randomUUID().toString();
            var threadId3 = UUID.randomUUID().toString();

            // Create traces for 3 different threads
            var traces = Stream.of(threadId1, threadId2, threadId3)
                    .flatMap(threadId -> IntStream.range(0, 2)
                            .mapToObj(it -> {
                                Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                                return createTrace().toBuilder()
                                        .projectName(projectName)
                                        .usage(null)
                                        .threadId(threadId)
                                        .endTime(now.plus(it, ChronoUnit.MILLIS))
                                        .startTime(now)
                                        .build();
                            }))
                    .toList();

            // Create spans with usage
            List<Span> spans = traces.stream()
                    .flatMap(trace -> PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                            .map(span -> span.toBuilder()
                                    .usage(spanResourceClient.getTokenUsage())
                                    .model(spanResourceClient.randomModel().toString())
                                    .provider(spanResourceClient.provider())
                                    .traceId(trace.id())
                                    .projectName(projectName)
                                    .totalEstimatedCost(null)
                                    .build()))
                    .toList();

            batchCreateSpansAndAssert(spans, apiKey, workspaceName);
            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            var stats = traceResourceClient.getTraceThreadStats(projectName, null, apiKey, workspaceName, List.of(),
                    Map.of());

            // Verify thread_count is 3
            assertThat(getThreadCount(stats)).isEqualTo(3L);

            // Build expected stats from all traces and spans
            var expectedStats = buildExpectedThreadStats(traces, spans, null);

            // Assert all stats match expected
            TraceAssertions.assertStats(stats.stats(), expectedStats);
        }

        @Test
        @DisplayName("When getting thread stats with time range, should filter correctly")
        void whenTimeRangeProvided__thenFilterCorrectly() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            Instant baseTime = Instant.now();
            Instant lowerBound = baseTime.minus(Duration.ofHours(2));
            Instant upperBound = baseTime.plus(Duration.ofHours(1));

            // Create threads within time range
            var threadInRange1 = UUID.randomUUID().toString();
            var threadInRange2 = UUID.randomUUID().toString();
            var threadOutOfRange = UUID.randomUUID().toString();

            var traceInRange1 = createTrace().toBuilder()
                    .projectName(projectName)
                    .threadId(threadInRange1)
                    .id(idGenerator.generateId(baseTime))
                    .startTime(baseTime)
                    .endTime(baseTime.plus(Duration.ofMinutes(5)))
                    .build();

            var traceInRange2 = createTrace().toBuilder()
                    .projectName(projectName)
                    .threadId(threadInRange2)
                    .id(idGenerator.generateId(baseTime.plusMillis(100))) // Different timestamp for different UUID
                    .startTime(baseTime.plusMillis(100))
                    .endTime(baseTime.plus(Duration.ofMinutes(5)))
                    .build();

            var tracesInRange = List.of(traceInRange1, traceInRange2);

            var traceOutOfRange = createTrace().toBuilder()
                    .projectName(projectName)
                    .threadId(threadOutOfRange)
                    .id(idGenerator.generateId(upperBound.plus(Duration.ofHours(1))))
                    .startTime(upperBound.plus(Duration.ofHours(1)))
                    .endTime(upperBound.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(5)))
                    .build();

            traceResourceClient.batchCreateTraces(
                    Stream.concat(tracesInRange.stream(), Stream.of(traceOutOfRange)).toList(),
                    apiKey, workspaceName);

            // Wait for traces to be created and indexed
            Mono.delay(Duration.ofMillis(500)).block();

            var queryParams = Map.of(
                    "from_time", lowerBound.toString(),
                    "to_time", upperBound.toString());

            var stats = traceResourceClient.getTraceThreadStats(projectName, null, apiKey, workspaceName, List.of(),
                    queryParams);

            // Verify only threads within time range are counted
            assertThat(getThreadCount(stats)).isEqualTo(2L);

            // Build expected stats from traces in range
            var expectedStats = buildExpectedThreadStats(tracesInRange, List.of(), null);

            // Assert all stats match expected
            TraceAssertions.assertStats(stats.stats(), expectedStats);
        }

        @Test
        @DisplayName("When getting thread stats with feedback scores, should include feedback score stats")
        void whenThreadsHaveFeedbackScores__thenIncludeFeedbackScoreStats() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var project = factory.manufacturePojo(Project.class).toBuilder()
                    .name(projectName)
                    .build();

            UUID projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            var threadId1 = UUID.randomUUID().toString();
            var threadId2 = UUID.randomUUID().toString();

            // Create traces for threads
            var traces = Stream.of(threadId1, threadId2)
                    .map(threadId -> createTrace().toBuilder()
                            .threadId(threadId)
                            .projectId(projectId)
                            .projectName(projectName)
                            .lastUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                            .build())
                    .toList();

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

            // Wait for threads to be created
            Mono.delay(Duration.ofMillis(500)).block();

            // Close threads
            traceResourceClient.closeTraceThread(threadId1, null, projectName, apiKey, workspaceName);
            traceResourceClient.closeTraceThread(threadId2, null, projectName, apiKey, workspaceName);

            // Add feedback scores
            String scoreName = RandomStringUtils.secure().nextAlphanumeric(10);

            List<FeedbackScoreBatchItemThread> scoreItems = Stream.of(threadId1, threadId2)
                    .map(threadId -> factory.manufacturePojo(FeedbackScoreBatchItemThread.class)
                            .toBuilder()
                            .threadId(threadId)
                            .projectName(projectName)
                            .name(scoreName)
                            .build())
                    .collect(toList());

            traceResourceClient.threadFeedbackScores(scoreItems, apiKey, workspaceName);

            // Wait for feedback scores to be processed
            Mono.delay(Duration.ofMillis(500)).block();

            var stats = traceResourceClient.getTraceThreadStats(projectName, null, apiKey, workspaceName, List.of(),
                    Map.of());

            // Verify thread count
            assertThat(getThreadCount(stats)).isEqualTo(2L);

            // Build expected stats from traces, spans, and feedback scores
            List<ProjectStats.ProjectStatItem<?>> expectedStats = buildExpectedThreadStats(traces, List.of(),
                    scoreItems);

            // Assert all stats match expected (focus on feedback scores)
            var feedbackScoreStats = stats.stats().stream()
                    .filter(stat -> stat.getName().startsWith("feedback_scores."))
                    .toList();
            List<ProjectStats.ProjectStatItem<?>> expectedFeedbackStats = expectedStats.stream()
                    .filter(stat -> stat.getName().startsWith("feedback_scores."))
                    .toList();

            TraceAssertions.assertStats(feedbackScoreStats,
                    expectedFeedbackStats);
        }

        private Stream<Arguments> getValidFiltersForStats() {
            return Stream.of(
                    // ID filter - test filtering by thread ID
                    Arguments.of(
                            "ID = thread_id",
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.ID)
                                    .operator(Operator.EQUAL)
                                    .value(traces.getFirst().threadId())
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .filter(trace -> trace.threadId().equals(traces.getFirst().threadId()))
                                    .toList(),
                            false // Don't close threads
                    ),
                    // FIRST_MESSAGE filter - test filtering by message content
                    Arguments.of(
                            "FIRST_MESSAGE CONTAINS substring",
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.FIRST_MESSAGE)
                                    .operator(Operator.CONTAINS)
                                    .value(traces.stream().min(Comparator.comparing(Trace::startTime))
                                            .orElseThrow().input().toString().substring(0, 20))
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .filter(trace -> trace.threadId().equals(traces.getFirst().threadId()))
                                    .toList(),
                            false),
                    // LAST_MESSAGE filter - test filtering by message content
                    Arguments.of(
                            "LAST_MESSAGE CONTAINS substring",
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.LAST_MESSAGE)
                                    .operator(Operator.CONTAINS)
                                    .value(traces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow()
                                            .output().toString().substring(0, 20))
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .filter(trace -> trace.threadId().equals(traces.getFirst().threadId()))
                                    .toList(),
                            false),
                    // DURATION filter - test filtering by exact duration
                    Arguments.of(
                            "DURATION = exact value",
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.DURATION)
                                    .operator(Operator.EQUAL)
                                    .value(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                            traces.stream().min(Comparator.comparing(Trace::startTime)).get()
                                                    .startTime(),
                                            traces.stream().max(Comparator.comparing(Trace::endTime)).get().endTime())
                                            .toString())
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .filter(trace -> trace.threadId().equals(traces.getFirst().threadId()))
                                    .toList(),
                            false),
                    // END_TIME filter - test filtering by thread end time
                    Arguments.of(
                            "END_TIME < midpoint timestamp",
                            (Function<List<Trace>, TraceThreadFilter>) traces -> {
                                // Use a timestamp between thread1's end and thread2's end
                                var thread1MaxEnd = traces.stream()
                                        .map(Trace::endTime)
                                        .max(Comparator.naturalOrder())
                                        .orElseThrow();
                                // Add 50ms buffer - thread2 ends at +100ms, thread1 at +1ms
                                var midpointTime = thread1MaxEnd.plus(50, ChronoUnit.MILLIS);
                                return TraceThreadFilter.builder()
                                        .field(TraceThreadField.END_TIME)
                                        .operator(Operator.LESS_THAN)
                                        .value(midpointTime.toString())
                                        .build();
                            },
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .filter(trace -> trace.threadId().equals(traces.getFirst().threadId()))
                                    .toList(),
                            false),
                    // NUMBER_OF_MESSAGES filter
                    Arguments.of(
                            "NUMBER_OF_MESSAGES = count",
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.NUMBER_OF_MESSAGES)
                                    .operator(Operator.EQUAL)
                                    .value(String.valueOf(traces.size() * 2))
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .filter(trace -> trace.threadId().equals(traces.getFirst().threadId()))
                                    .toList(),
                            false),
                    // STATUS filter - test filtering by active status
                    Arguments.of(
                            "STATUS = ACTIVE",
                            (Function<List<Trace>, TraceThreadFilter>) traces -> TraceThreadFilter.builder()
                                    .field(TraceThreadField.STATUS)
                                    .operator(Operator.EQUAL)
                                    .value(TraceThreadStatus.ACTIVE.getValue())
                                    .build(),
                            (Function<List<Trace>, List<Trace>>) traces -> traces.stream()
                                    .filter(trace -> trace.threadId().equals(traces.getFirst().threadId()))
                                    .toList(),
                            true // shouldCloseSecondThread to make it inactive
                    ));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("getValidFiltersForStats")
        @DisplayName("When getting thread stats with filters, should apply filters correctly and return accurate stats")
        void whenFiltersProvided__thenApplyFiltersCorrectly(
                String filterDescription,
                Function<List<Trace>, TraceThreadFilter> filterFunction,
                Function<List<Trace>, List<Trace>> getExpectedTraces,
                boolean shouldCloseSecondThread) {

            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var project = factory.manufacturePojo(Project.class).toBuilder()
                    .name(projectName)
                    .build();

            projectResourceClient.createProject(project, apiKey, workspaceName);

            var thread1Id = UUID.randomUUID().toString();
            var thread2Id = UUID.randomUUID().toString();

            // Create 2 threads, each with 2 traces
            var thread1Traces = IntStream.range(0, 2)
                    .mapToObj(it -> {
                        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                        return createTrace().toBuilder()
                                .projectName(projectName)
                                .usage(null)
                                .threadId(thread1Id)
                                .endTime(now.plus(it, ChronoUnit.MILLIS))
                                .startTime(now)
                                .build();
                    })
                    .toList();

            var thread2Traces = IntStream.range(0, 3) // Different number of messages
                    .mapToObj(it -> {
                        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                        return createTrace().toBuilder()
                                .projectName(projectName)
                                .usage(null)
                                .threadId(thread2Id)
                                .endTime(now.plus(it + 100, ChronoUnit.MILLIS)) // Different duration
                                .startTime(now)
                                .build();
                    })
                    .toList();

            var allTraces = Stream.concat(thread1Traces.stream(), thread2Traces.stream()).toList();

            traceResourceClient.batchCreateTraces(allTraces, apiKey, workspaceName);

            // Wait for async trace thread processing to complete (longer wait for CI/CD environments)
            Mono.delay(Duration.ofMillis(1000)).block();
            if (shouldCloseSecondThread) {
                traceResourceClient.closeTraceThread(thread2Id, null, projectName, apiKey, workspaceName);
            }

            // Additional wait to ensure thread status updates are processed
            Mono.delay(Duration.ofMillis(500)).block();

            // Create filter based on first thread characteristics
            var filter = filterFunction.apply(thread1Traces);

            var stats = traceResourceClient.getTraceThreadStats(projectName, null, apiKey, workspaceName,
                    List.of(filter), Map.of());

            // Get expected traces that match the filter
            var expectedTraces = getExpectedTraces.apply(thread1Traces);
            long expectedThreadCount = expectedTraces.stream()
                    .map(Trace::threadId)
                    .distinct()
                    .count();

            // Verify filtered thread count
            assertThat(getThreadCount(stats)).isEqualTo(expectedThreadCount);

            // Build expected stats from filtered traces
            var expectedStats = buildExpectedThreadStats(expectedTraces, List.of(), null);

            // Assert all stats match expected
            TraceAssertions.assertStats(stats.stats(), expectedStats);
        }

        @Test
        @DisplayName("When project has no data, should return empty stats")
        void whenProjectHasNoData__thenReturnEmptyStats() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            projectResourceClient.createProject(
                    factory.manufacturePojo(Project.class).toBuilder().name(projectName).build(),
                    apiKey, workspaceName);

            var stats = traceResourceClient.getTraceThreadStats(projectName, null, apiKey, workspaceName, List.of(),
                    Map.of());

            assertThat(stats.stats()).isEmpty();
        }
    }

}

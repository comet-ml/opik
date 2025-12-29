package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Comment;
import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanSearchStreamRequest;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
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
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
import com.comet.opik.api.resources.utils.spans.SpanPageTestAssertion;
import com.comet.opik.api.resources.utils.spans.SpanStreamTestAssertion;
import com.comet.opik.api.resources.utils.spans.SpansTestAssertion;
import com.comet.opik.api.resources.utils.spans.StatsTestAssertion;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.google.common.collect.Lists;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.http.HttpStatus;
import org.glassfish.jersey.client.ChunkedInput;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.filter.SpanField.CUSTOM;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.api.resources.utils.spans.SpanAssertions.assertSpan;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class FindSpansResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/spans";

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    public static final String INVALID_SEARCH_REQUEST = "{\"filters\": [{\"field\": \"input\", \"key\": \"\", \"type\": \"string\", \"operator\": \"contains\", \"value\": \"If Opik had a motto\"}], \"last_retrieved_id\": null, \"limit\": 1000, \"project_id\": \"Ellipsis\", \"project_name\": \"Demo chatbot \uD83E\uDD16\", \"trace_id\": null, \"truncate\": true, \"type\": \"Ellipsis\"}";
    public static final String INVVALID_SEARCH_RESPONSE_MESSAGE = """
            Unable to process JSON. Cannot deserialize value of type `java.util.UUID` from String "Ellipsis": UUID has to be represented by standard 36-char representation
             at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 160] (through reference chain: com.comet.opik.api.SpanSearchStreamRequest["project_id"])""";

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mySqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);
    private final GenericContainer<?> minIOContainer = MinIOContainerUtils.newMinIOContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mySqlContainer, clickHouseContainer, zookeeperContainer, minIOContainer)
                .join();
        String minioUrl = "http://%s:%d".formatted(minIOContainer.getHost(), minIOContainer.getMappedPort(9000));

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(mySqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(mySqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .isMinIO(true)
                        .minioUrl(minioUrl)
                        .build());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();
    private final FilterQueryBuilder filterQueryBuilder = new FilterQueryBuilder();

    private String baseURI;
    private ClientSupport client;
    private SpanResourceClient spanResourceClient;
    private IdGenerator idGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, IdGenerator idGenerator) throws SQLException {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
        this.idGenerator = idGenerator;
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindSpans {

        private final StatsTestAssertion statsTestAssertion = new StatsTestAssertion(spanResourceClient);
        private final SpansTestAssertion spansTestAssertion = new SpansTestAssertion(spanResourceClient, USER);
        private final SpanStreamTestAssertion spanStreamTestAssertion = new SpanStreamTestAssertion(spanResourceClient,
                USER);

        private Stream<Arguments> getFilterTestArguments() {
            return Stream.of(
                    Arguments.of(
                            "/spans/stats",
                            statsTestAssertion),
                    Arguments.of(
                            "/spans",
                            spansTestAssertion),
                    Arguments.of(
                            "/spans/search",
                            spanStreamTestAssertion));
        }

        private Stream<Arguments> equalAndNotEqualFilters() {
            return Stream.of(
                    Arguments.of(
                            "/spans/stats",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            statsTestAssertion),
                    Arguments.of(
                            "/spans",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            spansTestAssertion),
                    Arguments.of(
                            "/spans/search",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            spanStreamTestAssertion),
                    Arguments.of(
                            "/spans/stats",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            statsTestAssertion),
                    Arguments.of(
                            "/spans",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            spansTestAssertion),
                    Arguments.of(
                            "/spans/search",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            spanStreamTestAssertion));
        }

        private Stream<Arguments> getUsageKeyArgs() {
            return Stream.of(
                    Arguments.of(
                            "/spans/stats",
                            statsTestAssertion,
                            "completion_tokens",
                            SpanField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/spans/stats",
                            statsTestAssertion,
                            "prompt_tokens",
                            SpanField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/spans/stats",
                            statsTestAssertion,
                            "total_tokens",
                            SpanField.USAGE_TOTAL_TOKENS),
                    Arguments.of(
                            "/spans",
                            spansTestAssertion,
                            "completion_tokens",
                            SpanField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/spans",
                            spansTestAssertion,
                            "prompt_tokens",
                            SpanField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/spans",
                            spansTestAssertion,
                            "total_tokens",
                            SpanField.USAGE_TOTAL_TOKENS),
                    Arguments.of(
                            "/spans/search",
                            spanStreamTestAssertion,
                            "completion_tokens",
                            SpanField.USAGE_COMPLETION_TOKENS),
                    Arguments.of(
                            "/spans/search",
                            spanStreamTestAssertion,
                            "prompt_tokens",
                            SpanField.USAGE_PROMPT_TOKENS),
                    Arguments.of(
                            "/spans/search",
                            spanStreamTestAssertion,
                            "total_tokens",
                            SpanField.USAGE_TOTAL_TOKENS));
        }

        private Stream<Arguments> getCustomFilterArgs() {
            String dictInput = "{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                    "Chat-GPT 4.0\",\"trueFlag\":true,\"nullField\":null}]}";
            String listInput = "[\"Chat-GPT 4.0\", 2025, {\"provider\": \"provider_1\"}]";
            return Stream.of(
                    Arguments.of(
                            statsTestAssertion,
                            "input.model[0].year",
                            "2024",
                            Operator.EQUAL,
                            dictInput),
                    Arguments.of(
                            statsTestAssertion,
                            "input.model[0].year",
                            "2025",
                            Operator.LESS_THAN,
                            dictInput),
                    Arguments.of(
                            statsTestAssertion,
                            "input",
                            "Chat-GPT 4.0",
                            Operator.CONTAINS,
                            dictInput),

                    Arguments.of(
                            spansTestAssertion,
                            "input.model[0].year",
                            "2024",
                            Operator.EQUAL,
                            dictInput),
                    Arguments.of(
                            spansTestAssertion,
                            "input.model[0].year",
                            "2025",
                            Operator.LESS_THAN,
                            dictInput),
                    Arguments.of(
                            spansTestAssertion,
                            "input",
                            "Chat-GPT 4.0",
                            Operator.CONTAINS,
                            dictInput),
                    Arguments.of(
                            spansTestAssertion,
                            "input.[1]",
                            "2025",
                            Operator.EQUAL,
                            listInput),
                    Arguments.of(
                            spansTestAssertion,
                            "input.[0]",
                            "Chat-GPT 4.0",
                            Operator.CONTAINS,
                            listInput),
                    Arguments.of(
                            spansTestAssertion,
                            "input[1]",
                            "2025",
                            Operator.EQUAL,
                            listInput),
                    Arguments.of(
                            spansTestAssertion,
                            "input[0]",
                            "Chat-GPT 4.0",
                            Operator.CONTAINS,
                            listInput),
                    Arguments.of(
                            spansTestAssertion,
                            "input[2].provider",
                            "provider_1",
                            Operator.EQUAL,
                            listInput),

                    Arguments.of(
                            spanStreamTestAssertion,
                            "input.model[0].year",
                            "2024",
                            Operator.EQUAL,
                            dictInput),
                    Arguments.of(
                            spanStreamTestAssertion,
                            "input.model[0].year",
                            "2025",
                            Operator.LESS_THAN,
                            dictInput),
                    Arguments.of(
                            spanStreamTestAssertion,
                            "input",
                            "Chat-GPT 4.0",
                            Operator.CONTAINS,
                            dictInput));
        }

        private Stream<Arguments> getFeedbackScoresArgs() {
            return Stream.of(
                    Arguments.of(
                            "/spans/stats",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            statsTestAssertion),
                    Arguments.of(
                            "/spans",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            spansTestAssertion),
                    Arguments.of(
                            "/spans/search",
                            Operator.EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            spanStreamTestAssertion),
                    Arguments.of(
                            "/spans/stats",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(2, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(0, 2),
                            statsTestAssertion),
                    Arguments.of(
                            "/spans",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(2, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(0, 2),
                            spansTestAssertion),
                    Arguments.of(
                            "/spans/search",
                            Operator.NOT_EQUAL,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(2, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(0, 2),
                            spanStreamTestAssertion));
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
                    arguments("/spans/stats", statsTestAssertion, arg.get()[0],
                            arg.get()[1], arg.get()[2]),
                    arguments("/spans", spansTestAssertion, arg.get()[0],
                            arg.get()[1], arg.get()[2]),
                    arguments("/spans/search", spanStreamTestAssertion,
                            arg.get()[0],
                            arg.get()[1], arg.get()[2])));
        }

        private String getValidValue(Field field) {
            return switch (field.getType()) {
                case STRING, LIST, DICTIONARY, DICTIONARY_STATE_DB, MAP, ENUM, ERROR_CONTAINER, STRING_STATE_DB,
                        CUSTOM ->
                    RandomStringUtils.secure().nextAlphanumeric(10);
                case NUMBER, DURATION, FEEDBACK_SCORES_NUMBER -> String.valueOf(randomNumber(1, 10));
                case DATE_TIME, DATE_TIME_STATE_DB -> Instant.now().toString();
            };
        }

        private String getKey(Field field) {
            return switch (field.getType()) {
                case STRING, NUMBER, DURATION, DATE_TIME, LIST, ENUM, ERROR_CONTAINER, STRING_STATE_DB,
                        DATE_TIME_STATE_DB ->
                    null;
                case FEEDBACK_SCORES_NUMBER, CUSTOM -> RandomStringUtils.secure().nextAlphanumeric(10);
                case DICTIONARY, DICTIONARY_STATE_DB, MAP -> "";
            };
        }

        private String getInvalidValue(Field field) {
            return switch (field.getType()) {
                case STRING, DICTIONARY, DICTIONARY_STATE_DB, MAP, CUSTOM, LIST, ENUM, ERROR_CONTAINER, STRING_STATE_DB,
                        DATE_TIME_STATE_DB ->
                    " ";
                case NUMBER, DURATION, DATE_TIME, FEEDBACK_SCORES_NUMBER ->
                    RandomStringUtils.secure().nextAlphanumeric(10);
            };
        }

        private Stream<Arguments> getFilterInvalidOperatorForFieldTypeArgs() {
            return filterQueryBuilder.getUnSupportedOperators(SpanField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .flatMap(operator -> Stream.of(
                                    Arguments.of("/stats", SpanFilter.builder()
                                            .field(filter.getKey())
                                            .operator(operator)
                                            .key(getKey(filter.getKey()))
                                            .value(getValidValue(filter.getKey()))
                                            .build()),
                                    Arguments.of("", SpanFilter.builder()
                                            .field(filter.getKey())
                                            .operator(operator)
                                            .key(getKey(filter.getKey()))
                                            .value(getValidValue(filter.getKey()))
                                            .build()),
                                    Arguments.of("/search", SpanFilter.builder()
                                            .field(filter.getKey())
                                            .operator(operator)
                                            .key(getKey(filter.getKey()))
                                            .value(getValidValue(filter.getKey()))
                                            .build()))));
        }

        private Stream<Arguments> getFilterInvalidValueOrKeyForFieldTypeArgs() {

            Stream<SpanFilter> filters = filterQueryBuilder.getSupportedOperators(SpanField.values())
                    .entrySet()
                    .stream()
                    .flatMap(filter -> filter.getValue()
                            .stream()
                            .flatMap(operator -> switch (filter.getKey().getType()) {
                                case STRING -> Stream.empty();
                                case DICTIONARY, FEEDBACK_SCORES_NUMBER -> Stream.of(
                                        SpanFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                .key(null)
                                                .value(getValidValue(filter.getKey()))
                                                .build(),
                                        SpanFilter.builder()
                                                .field(filter.getKey())
                                                .operator(operator)
                                                // if no value is expected, create an invalid filter by an empty key
                                                .key(Operator.NO_VALUE_OPERATORS.contains(operator)
                                                        ? ""
                                                        : getKey(filter.getKey()))
                                                .value(getInvalidValue(filter.getKey()))
                                                .build());
                                case ERROR_CONTAINER -> Stream.of();
                                default -> Stream.of(SpanFilter.builder()
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

        private Stream<Arguments> whenFilterByCorrespondingField__thenReturnSpansFiltered() {

            return Stream.of(
                    Arguments.of(
                            "/spans/stats",
                            SpanField.TOTAL_ESTIMATED_COST,
                            Operator.GREATER_THAN,
                            "0",
                            statsTestAssertion),
                    Arguments.of(
                            "/spans/search",
                            SpanField.TOTAL_ESTIMATED_COST,
                            Operator.GREATER_THAN,
                            "0",
                            spanStreamTestAssertion),
                    Arguments.of(
                            "/spans",
                            SpanField.TOTAL_ESTIMATED_COST,
                            Operator.GREATER_THAN,
                            "0",
                            spansTestAssertion),
                    Arguments.of(
                            "/spans/stats",
                            SpanField.MODEL,
                            Operator.EQUAL,
                            "gpt-3.5-turbo-1106",
                            statsTestAssertion),
                    Arguments.of(
                            "/spans/search",
                            SpanField.MODEL,
                            Operator.EQUAL,
                            "gpt-3.5-turbo-1106",
                            spanStreamTestAssertion),
                    Arguments.of(
                            "/spans",
                            SpanField.MODEL,
                            Operator.EQUAL,
                            "gpt-3.5-turbo-1106",
                            spansTestAssertion),
                    Arguments.of(
                            "/spans/stats",
                            SpanField.PROVIDER,
                            Operator.EQUAL,
                            null,
                            statsTestAssertion),
                    Arguments.of(
                            "/spans/search",
                            SpanField.PROVIDER,
                            Operator.EQUAL,
                            null,
                            spanStreamTestAssertion),
                    Arguments.of(
                            "/spans",
                            SpanField.PROVIDER,
                            Operator.EQUAL,
                            null,
                            spansTestAssertion),
                    Arguments.of(
                            "",
                            SpanField.TYPE,
                            Operator.EQUAL,
                            "general",
                            spansTestAssertion),
                    Arguments.of(
                            "",
                            SpanField.TYPE,
                            Operator.EQUAL,
                            "general",
                            statsTestAssertion),
                    Arguments.of(
                            "",
                            SpanField.TYPE,
                            Operator.EQUAL,
                            "general",
                            spanStreamTestAssertion),
                    Arguments.of(
                            "",
                            SpanField.TYPE,
                            Operator.NOT_EQUAL,
                            "llm",
                            spansTestAssertion),
                    Arguments.of(
                            "",
                            SpanField.TYPE,
                            Operator.NOT_EQUAL,
                            "llm",
                            statsTestAssertion),
                    Arguments.of(
                            "",
                            SpanField.TYPE,
                            Operator.NOT_EQUAL,
                            "llm",
                            spanStreamTestAssertion));
        }

        @Test
        void createAndGetByProjectName() {
            String projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .parentSpanId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var pageSize = spans.size() - 2;
            var expectedSpans1 = spans.subList(pageSize - 1, spans.size()).reversed();
            var expectedSpans2 = spans.subList(0, pageSize - 1).reversed();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .parentSpanId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> spanResourceClient.createSpan(expectedSpan, apiKey, workspaceName));

            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    null,
                    null,
                    null,
                    1,
                    pageSize,
                    expectedSpans1,
                    spans.size(),
                    unexpectedSpans,
                    apiKey,
                    List.of(),
                    List.of());
            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    null,
                    null,
                    null,
                    2,
                    pageSize,
                    expectedSpans2,
                    spans.size(),
                    unexpectedSpans,
                    apiKey,
                    List.of(),
                    List.of());
        }

        @Test
        void createAndGetByWorkspace() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .parentSpanId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var pageSize = spans.size() - 2;
            var expectedSpans1 = spans.subList(pageSize - 1, spans.size()).reversed();
            var expectedSpans2 = spans.subList(0, pageSize - 1).reversed();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .parentSpanId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> spanResourceClient.createSpan(expectedSpan, apiKey, workspaceName));

            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    null,
                    null,
                    null,
                    1,
                    pageSize,
                    expectedSpans1,
                    spans.size(),
                    unexpectedSpans,
                    apiKey,
                    List.of(),
                    List.of());

            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    null,
                    null,
                    null,
                    2,
                    pageSize,
                    expectedSpans2,
                    spans.size(),
                    unexpectedSpans,
                    apiKey,
                    List.of(),
                    List.of());
        }

        @Test
        void createAndGetByProjectNameAndTraceId() {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traceId = generator.generate();

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .traceId(traceId)
                            .feedbackScores(null)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var pageSize = spans.size() - 2;
            var expectedSpans1 = spans.subList(pageSize - 1, spans.size()).reversed();
            var expectedSpans2 = spans.subList(0, pageSize - 1).reversed();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .projectName(projectName)
                    .parentSpanId(null)
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> spanResourceClient.createSpan(expectedSpan, apiKey, workspaceName));

            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    traceId,
                    null,
                    null,
                    1,
                    pageSize,
                    expectedSpans1,
                    spans.size(),
                    unexpectedSpans,
                    apiKey,
                    List.of(),
                    List.of());
            getAndAssertPage(
                    workspaceName,
                    projectName,
                    null,
                    traceId,
                    null,
                    null,
                    2,
                    pageSize,
                    expectedSpans2,
                    spans.size(),
                    unexpectedSpans,
                    apiKey,
                    List.of(),
                    List.of());
        }

        @ParameterizedTest
        @EnumSource(SpanType.class)
        void createAndGetByProjectIdAndTraceIdAndType(SpanType expectedType) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var traceId = generator.generate();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .parentSpanId(null)
                            .projectName(projectName)
                            .traceId(traceId)
                            .type(expectedType)
                            .feedbackScores(null)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var projectId = getAndAssert(spans.getLast(), apiKey, workspaceName).projectId();

            var pageSize = spans.size() - 2;
            var expectedSpans1 = spans.subList(pageSize - 1, spans.size()).reversed();
            var expectedSpans2 = spans.subList(0, pageSize - 1).reversed();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .projectName(projectName)
                    .traceId(traceId)
                    .parentSpanId(null)
                    .type(findOtherSpanType(expectedType))
                    .build());
            unexpectedSpans.forEach(
                    expectedSpan -> spanResourceClient.createSpan(expectedSpan, apiKey, workspaceName));

            getAndAssertPage(
                    workspaceName,
                    null,
                    projectId,
                    traceId,
                    expectedType,
                    null,
                    1,
                    pageSize,
                    expectedSpans1,
                    spans.size(),
                    unexpectedSpans,
                    apiKey,
                    List.of(),
                    List.of());
            getAndAssertPage(
                    workspaceName,
                    null,
                    projectId,
                    traceId,
                    expectedType,
                    null,
                    2,
                    pageSize,
                    expectedSpans2,
                    spans.size(),
                    unexpectedSpans,
                    apiKey,
                    List.of(),
                    List.of());
        }

        private SpanType findOtherSpanType(SpanType expectedType) {
            return Arrays.stream(SpanType.values()).filter(type -> type != expectedType).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("expected to find another span type"));
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void whenUsingPagination__thenReturnTracesPaginated(boolean stream) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(trace -> trace.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .comments(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = spans.stream()
                    .sorted(stream
                            ? Comparator.comparing(Span::id).reversed()
                            : Comparator.comparing(Span::traceId)
                                    .thenComparing(Span::parentSpanId)
                                    .thenComparing(Span::id)
                                    .reversed())
                    .toList();

            int pageSize = 2;

            if (stream) {
                AtomicReference<UUID> lastId = new AtomicReference<>(null);
                Lists.partition(expectedSpans, pageSize)
                        .forEach(trace -> {
                            var actualSpans = spanResourceClient.getStreamAndAssertContent(apiKey, workspaceName,
                                    SpanSearchStreamRequest.builder()
                                            .projectName(projectName)
                                            .lastRetrievedId(lastId.get())
                                            .limit(pageSize)
                                            .build());

                            SpanAssertions.assertSpan(actualSpans, trace, USER);

                            lastId.set(actualSpans.getLast().id());
                        });
            } else {

                for (int i = 0; i < expectedSpans.size() / pageSize; i++) {
                    int page = i + 1;
                    getAndAssertPage(
                            workspaceName,
                            projectName,
                            null,
                            null,
                            null,
                            List.of(),
                            page,
                            pageSize,
                            expectedSpans.subList(i * pageSize, Math.min((i + 1) * pageSize, expectedSpans.size())),
                            spans.size(),
                            List.of(),
                            apiKey,
                            List.of(),
                            List.of());
                }
            }
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.ImageTruncationArgProvider#provideTestArguments")
        void findWithImageTruncation(JsonNode original, JsonNode expected, boolean truncate) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var spans = Stream.of(podamFactory.manufacturePojo(Span.class))
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .parentSpanId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .input(original)
                            .output(original)
                            .metadata(original)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("page", 1)
                    .queryParam("size", 5)
                    .queryParam("project_name", projectName)
                    .queryParam("truncate", truncate)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {
                var actualPage = actualResponse.readEntity(Span.SpanPage.class);
                var actualSpans = actualPage.content();

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                assertThat(actualSpans).hasSize(1);

                var expectedSpans = spans.stream()
                        .map(span -> span.toBuilder()
                                .input(expected)
                                .output(expected)
                                .metadata(expected)
                                .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(span.startTime(),
                                        span.endTime()))
                                .build())
                        .toList();

                assertSpan(actualSpans, expectedSpans, USER);
            }
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.utils.ImageTruncationArgProvider#provideTestArguments")
        void searchWithImageTruncation(JsonNode original, JsonNode expected, boolean truncate) {
            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var spans = Stream.of(podamFactory.manufacturePojo(Span.class))
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .parentSpanId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .input(original)
                            .output(original)
                            .metadata(original)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var streamRequest = SpanSearchStreamRequest.builder().projectName(projectName).truncate(truncate)
                    .limit(5).build();

            var expectedSpans = spans.stream()
                    .map(span -> span.toBuilder()
                            .input(expected)
                            .output(expected)
                            .metadata(expected)
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(span.startTime(),
                                    span.endTime()))
                            .build())
                    .toList();

            List<Span> actualSpans = spanResourceClient.getStreamAndAssertContent(apiKey, workspaceName, streamRequest);

            assertSpan(actualSpans, expectedSpans, USER);
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterIdAndNameEqual__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.ID)
                            .operator(Operator.EQUAL)
                            .value(spans.getFirst().id().toString())
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.EQUAL)
                            .value(spans.getFirst().name())
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource
        void whenFilterByCorrespondingField__thenReturnSpansFiltered(
                String endpoint, SpanField filterField, Operator filterOperator, String filterValue,
                SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String model = "gpt-3.5-turbo-1106";
            String provider = "openai";
            SpanType spanType = SpanType.general;

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var unexpectedSpans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .type(SpanType.llm)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var expectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .type(spanType)
                    .projectName(projectName)
                    .endTime(Instant.now().plusMillis(randomNumber()))
                    .provider(provider)
                    .model(model)
                    .usage(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))))
                    .feedbackScores(null)
                    .totalEstimatedCost(null)
                    .build());

            spanResourceClient.batchCreateSpans(expectedSpans, apiKey, workspaceName);

            // Check that it's filtered by cost
            List<SpanFilter> filters = List.of(
                    SpanFilter.builder()
                            .field(filterField)
                            .operator(filterOperator)
                            .value(filterField == SpanField.PROVIDER
                                    ? expectedSpans.getFirst().provider()
                                    : filterValue)
                            .build());

            var values = testAssertion.transformTestParams(expectedSpans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterTotalEstimatedCostEqual_NotEqual__thenReturnSpansFiltered(
                String endpoint, Operator operator, Function<List<Span>, List<Span>> getUnexpectedSpans,
                Function<List<Span>, List<Span>> getExpectedSpans, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .model("gpt-3.5-turbo-1106")
                    .provider("openai")
                    .usage(Map.of("completion_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class)),
                            "prompt_tokens", Math.abs(podamFactory.manufacturePojo(Integer.class))))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            List<SpanFilter> filters = List.of(SpanFilter.builder()
                    .field(SpanField.TOTAL_ESTIMATED_COST)
                    .operator(operator)
                    .value("0")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterNameEqual_NotEqual__thenReturnSpansFiltered(
                String endpoint, Operator operator,
                Function<List<Span>, List<Span>> getExpectedSpans,
                Function<List<Span>, List<Span>> getUnexpectedSpans,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(null)
                            .feedbackScores(null)
                            .usage(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            List<SpanFilter> filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(operator)
                    .value(spans.getFirst().name().toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameStartsWith__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.STARTS_WITH)
                    .value(spans.getFirst().name().substring(0, spans.getFirst().name().length() - 4).toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameEndsWith__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.ENDS_WITH)
                    .value(spans.getFirst().name().substring(3).toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameContains__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.CONTAINS)
                    .value(spans.getFirst().name().substring(2, spans.getFirst().name().length() - 3).toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameLessThan__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .name("CCC")
                            .projectName(projectName)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .name("AAA")
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .name("ccc")
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.LESS_THAN)
                    .value("BBB")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterTraceIdEqual__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var traceId = UUID.randomUUID();

            // Create 5 spans with the same trace_id (these should be returned by the filter)
            var expectedSpanCount = 5;
            var expectedSpans = IntStream.range(0, expectedSpanCount)
                    .mapToObj(i -> podamFactory.manufacturePojo(Span.class).toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .traceId(traceId)
                            .name("span-" + i)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(expectedSpans, apiKey, workspaceName);

            // Create 3 spans with different trace_ids (these should NOT be returned)
            var unexpectedSpans = IntStream.range(0, 3)
                    .mapToObj(i -> podamFactory.manufacturePojo(Span.class).toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .traceId(UUID.randomUUID())
                            .name("other-span-" + i)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            // Apply trace_id filter
            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.TRACE_ID)
                    .operator(Operator.EQUAL)
                    .value(traceId.toString())
                    .build());

            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("project_name", projectName);
            queryParams.put("filters", toURLEncodedQueryParam(filters));

            // Execute the request and verify results
            try (var actualResponse = spanResourceClient.callGetSpansWithQueryParams(apiKey, workspaceName,
                    queryParams)) {
                var actualPage = actualResponse.readEntity(Span.SpanPage.class);
                var actualSpans = actualPage.content();

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

                // Verify we got the correct number of spans
                assertThat(actualSpans).hasSize(expectedSpanCount);
                assertThat(actualPage.total()).isEqualTo(expectedSpanCount);

                // Verify all returned spans have the correct trace_id
                assertThat(actualSpans)
                        .allMatch(span -> span.traceId().equals(traceId),
                                "All returned spans should have traceId: " + traceId);

                // Verify no spans with different trace_ids are returned
                var unexpectedTraceIds = unexpectedSpans.stream()
                        .map(Span::traceId)
                        .collect(Collectors.toSet());

                assertThat(actualSpans)
                        .noneMatch(span -> unexpectedTraceIds.contains(span.traceId()),
                                "No spans with different trace_ids should be returned");
            }
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameGreaterThan__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .name("AAA")
                            .projectName(projectName)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .name("ccc")
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .name("aaa")
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.GREATER_THAN)
                    .value("BBB")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterNameNotContains__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spanName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .name(spanName)
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .name(generator.generate().toString())
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.NAME)
                    .operator(Operator.NOT_CONTAINS)
                    .value(spanName.toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterStartTimeEqual_NotEqual__thenReturnSpansFiltered(String endpoint,
                Operator operator, Function<List<Span>, List<Span>> getExpectedSpans,
                Function<List<Span>, List<Span>> getUnexpectedSpans, SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            var values = testAssertion.transformTestParams(spans, expectedSpans.reversed(), unexpectedSpans);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(operator)
                    .value(spans.getFirst().startTime().toString())
                    .build());

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeGreaterThan__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().minusSeconds(60 * 5))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().plusSeconds(60 * 5))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.GREATER_THAN)
                    .value(Instant.now().toString())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeGreaterThanEqual__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().minusSeconds(60 * 5))
                            .feedbackScores(null)
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().plusSeconds(60 * 5))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.GREATER_THAN_EQUAL)
                    .value(spans.getFirst().startTime().toString())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeLessThan__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .startTime(Instant.now().plusSeconds(60 * 5))
                            .feedbackScores(null)
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.LESS_THAN)
                    .value(Instant.now().toString())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterStartTimeLessThanEqual__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .startTime(Instant.now().plusSeconds(60 * 5))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(Instant.now().minusSeconds(60 * 5))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.START_TIME)
                    .operator(Operator.LESS_THAN_EQUAL)
                    .value(spans.getFirst().startTime().toString())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterEndTimeEqual__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.END_TIME)
                    .operator(Operator.EQUAL)
                    .value(spans.getFirst().endTime().toString())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterInputEqual__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.INPUT)
                    .operator(Operator.EQUAL)
                    .value(spans.getFirst().input().toString())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterOutputEqual__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.OUTPUT)
                    .operator(Operator.EQUAL)
                    .value(spans.getFirst().output().toString())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("equalAndNotEqualFilters")
        void whenFilterMetadataEqualString__thenReturnSpansFiltered(String endpoint,
                Operator operator,
                Function<List<Span>, List<Span>> getExpectedSpans,
                Function<List<Span>, List<Span>> getUnexpectedSpans,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(operator)
                    .key("$.model[0].version")
                    .value("OPENAI, CHAT-GPT 4.0")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualNumber__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("2023")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualBoolean__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .metadata(
                                    JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":false,\"version\":\"Some " +
                                            "version\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("TRUE")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataEqualNull__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("model[0].year")
                    .value("NULL")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsString__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].version")
                    .value("CHAT-GPT")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsNumber__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":\"two thousand twenty " +
                                    "four\",\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2023,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("02")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsBoolean__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .totalEstimatedCost(null)
                            .projectName(projectName)
                            .metadata(
                                    JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":false,\"version\":\"Some " +
                                            "version\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("TRU")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataContainsNull__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"Some " +
                                    "version\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model[0].year")
                    .value("NUL")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanNumber__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2020," +
                                    "\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("2023")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanString__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].version")
                    .value("a")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanBoolean__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataGreaterThanNull__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("model[0].year")
                    .value("a")
                    .build());

            var values = testAssertion.transformTestParams(expectedSpans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanNumber__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2026," +
                                    "\"version\":\"OpenAI, Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .metadata(JsonUtils.getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                            "Chat-GPT 4.0\"}]}"))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("2025")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanString__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].version")
                    .value("z")
                    .build());

            var values = testAssertion.transformTestParams(expectedSpans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanBoolean__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":true,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterMetadataLessThanNull__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .metadata(JsonUtils
                                    .getJsonNodeFromString("{\"model\":[{\"year\":null,\"version\":\"openAI, " +
                                            "Chat-GPT 4.0\"}]}"))
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.<Span>of();
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.METADATA)
                    .operator(Operator.LESS_THAN)
                    .key("model[0].year")
                    .value("z")
                    .build());

            var values = testAssertion.transformTestParams(expectedSpans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterTagsContains__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.TAGS)
                    .operator(Operator.CONTAINS)
                    .value(spans.getFirst().tags().stream()
                            .toList()
                            .get(2)
                            .substring(0, spans.getFirst().name().length() - 4)
                            .toUpperCase())
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageEqual__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion,
                String usageKey, Field field) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            int firstUsage = randomNumber(1, 8);

            var spans = new ArrayList<Span>();

            Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .projectName(projectName)
                    .usage(Map.of(usageKey, firstUsage))
                    .feedbackScores(null)
                    .totalEstimatedCost(BigDecimal.ZERO)
                    .build();
            spans.add(span);

            PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(it -> it.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(Map.of(usageKey, randomNumber()))
                            .feedbackScores(null)
                            .build())
                    .forEach(spans::add);

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.EQUAL)
                            .value(spans.getFirst().usage().get(usageKey).toString())
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageGreaterThan__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion,
                String usageKey, Field field) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 123))
                            .feedbackScores(null)
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.GREATER_THAN)
                            .value("123")
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageGreaterThanEqual__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion,
                String usageKey, Field field) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 123))
                            .feedbackScores(null)
                            .totalEstimatedCost(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 456))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .value(spans.getFirst().usage().get(usageKey).toString())
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageLessThan__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion,
                String usageKey, Field field) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 456))
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.LESS_THAN)
                            .value("456")
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getUsageKeyArgs")
        void whenFilterUsageLessThanEqual__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion,
                String usageKey, Field field) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(Map.of(usageKey, 456))
                            .feedbackScores(null)
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .usage(Map.of(usageKey, 123))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(field)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .value(spans.getFirst().usage().get(usageKey).toString())
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getCustomFilterArgs")
        void whenFilterWithCustomFilter__thenReturnSpansFiltered(SpanPageTestAssertion testAssertion,
                String key, String value, Operator operator, String input) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .feedbackScores(null)
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .input(JsonUtils
                            .getJsonNodeFromString(input))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(CUSTOM)
                            .operator(operator)
                            .key(key)
                            .value(value)
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFeedbackScoresArgs")
        void whenFilterFeedbackScoresEqual_NotEqual__thenReturnSpansFiltered(String endpoint,
                Operator operator,
                Function<List<Span>, List<Span>> getExpectedSpans,
                Function<List<Span>, List<Span>> getUnexpectedSpans,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(BigDecimal.ZERO)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spans.set(1, spans.get(1).toBuilder()
                    .feedbackScores(
                            updateFeedbackScore(spans.get(1).feedbackScores(), spans.getFirst().feedbackScores(), 2))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(operator)
                            .key(spans.getFirst().feedbackScores().get(1).name().toUpperCase())
                            .value(spans.getFirst().feedbackScores().get(1).value().toString())
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(operator)
                            .key(spans.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(spans.getFirst().feedbackScores().get(2).value().toString())
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresGreaterThan__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .totalEstimatedCost(null)
                            .projectName(projectName)
                            .feedbackScores(updateFeedbackScore(span.feedbackScores(), 2, 1234.5678))
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 2345.6789))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            unexpectedSpans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.NAME)
                            .operator(Operator.EQUAL)
                            .value(spans.getFirst().name())
                            .build(),
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.GREATER_THAN)
                            .key(spans.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value("2345.6788")
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresGreaterThanEqual__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .feedbackScores(updateFeedbackScore(span.feedbackScores(), 2, 1234.5678))
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 2345.6789))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            unexpectedSpans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.GREATER_THAN_EQUAL)
                            .key(spans.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(spans.getFirst().feedbackScores().get(2).value().toString())
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresLessThan__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .feedbackScores(updateFeedbackScore(span.feedbackScores(), 2, 2345.6789))
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 1234.5678))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            unexpectedSpans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.LESS_THAN)
                            .key(spans.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value("2345.6788")
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterFeedbackScoresLessThanEqual__thenReturnSpansFiltered(String endpoint,
                SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .feedbackScores(updateFeedbackScore(span.feedbackScores(), 2, 2345.6789))
                            .build())
                    .collect(toCollection(ArrayList::new));
            spans.set(0, spans.getFirst().toBuilder()
                    .feedbackScores(updateFeedbackScore(spans.getFirst().feedbackScores(), 2, 1234.5678))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            spans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));
            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            unexpectedSpans.forEach(
                    span -> span.feedbackScores()
                            .forEach(
                                    feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.FEEDBACK_SCORES)
                            .operator(Operator.LESS_THAN_EQUAL)
                            .key(spans.getFirst().feedbackScores().get(2).name().toUpperCase())
                            .value(spans.getFirst().feedbackScores().get(2).value().toString())
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getDurationArgs")
        void whenFilterByDuration__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion,
                Operator operator, long end, double duration) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> {
                        Instant now = Instant.now();
                        return span.toBuilder()
                                .projectId(null)
                                .projectName(projectName)
                                .feedbackScores(null)
                                .totalEstimatedCost(null)
                                .startTime(now)
                                .endTime(Set.of(Operator.LESS_THAN, Operator.LESS_THAN_EQUAL).contains(operator)
                                        ? Instant.now().plusSeconds(2)
                                        : now.plusNanos(1000))
                                .build();
                    })
                    .collect(Collectors.toCollection(ArrayList::new));

            var start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            spans.set(0, spans.getFirst().toBuilder()
                    .startTime(start)
                    .endTime(start.plus(end, ChronoUnit.MICROS))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());

            var unexpectedSpans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class).stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .build())
                    .toList();

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(
                    SpanFilter.builder()
                            .field(SpanField.DURATION)
                            .operator(operator)
                            .value(String.valueOf(duration))
                            .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        Stream<Arguments> whenFilterByIsEmpty__thenReturnSpansFiltered() {
            return Stream.of(
                    arguments(
                            "/spans/search",
                            Operator.IS_NOT_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            spanStreamTestAssertion),
                    arguments(
                            "/spans",
                            Operator.IS_NOT_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            spansTestAssertion),
                    arguments(
                            "/spans/stats",
                            Operator.IS_NOT_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            statsTestAssertion),
                    arguments(
                            "/spans/search",
                            Operator.IS_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            spanStreamTestAssertion),
                    arguments(
                            "/spans",
                            Operator.IS_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            spansTestAssertion),
                    arguments(
                            "/spans/stats",
                            Operator.IS_EMPTY,
                            (Function<List<Span>, List<Span>>) spans -> spans.subList(1, spans.size()),
                            (Function<List<Span>, List<Span>>) spans -> List.of(spans.getFirst()),
                            statsTestAssertion));
        }

        @ParameterizedTest
        @MethodSource
        void whenFilterByIsEmpty__thenReturnSpansFiltered(
                String endpoint,
                Operator operator,
                Function<List<Span>, List<Span>> getExpectedSpans,
                Function<List<Span>, List<Span>> getUnexpectedSpans,
                SpanPageTestAssertion testAssertion) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> {
                        Instant now = Instant.now();
                        return span.toBuilder()
                                .projectId(null)
                                .projectName(projectName)
                                .totalEstimatedCost(null)
                                .startTime(now)
                                .build();
                    })
                    .collect(Collectors.toCollection(ArrayList::new));

            spans.set(spans.size() - 1, spans.getLast().toBuilder().feedbackScores(null).build());
            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);
            spans.subList(0, spans.size() - 1).forEach(span -> span.feedbackScores()
                    .forEach(feedbackScore -> createAndAssert(span.id(), feedbackScore, workspaceName, apiKey)));

            var expectedSpans = getExpectedSpans.apply(spans);
            var unexpectedSpans = getUnexpectedSpans.apply(spans);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.FEEDBACK_SCORES)
                    .operator(operator)
                    .key(spans.getFirst().feedbackScores().getFirst().name())
                    .value("")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans.reversed(), unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidOperatorForFieldTypeArgs")
        void whenFilterInvalidOperatorForFieldType__thenReturn400(String path, SpanFilter filter) {
            int expectedStatus = HttpStatus.SC_BAD_REQUEST;
            String errorMessage = filter.field().getType() == FieldType.CUSTOM
                    ? "Invalid key '%s' for custom filter".formatted(filter.key())
                    : "Invalid operator '%s' for field '%s' of type '%s'".formatted(
                            filter.operator().getQueryParamOperator(),
                            filter.field().getQueryParamField(),
                            filter.field().getType());

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    expectedStatus, errorMessage);
            var projectName = generator.generate().toString();
            List<SpanFilter> filters = List.of(filter);

            Response actualResponse;

            if ("/search".equals(path)) {
                actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(path)
                        .queryParam("project_name", projectName)
                        .queryParam("filters", toURLEncodedQueryParam(filters))
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                        .post(Entity.json(SpanSearchStreamRequest.builder()
                                .projectName(projectName)
                                .filters(filters)
                                .build()));
            } else {
                actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(path)
                        .queryParam("project_name", projectName)
                        .queryParam("filters", toURLEncodedQueryParam(filters))
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                        .get();
            }

            try (actualResponse) {
                assertThat(actualResponse.getStatus()).isEqualTo(expectedStatus);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
            }

        }

        @ParameterizedTest
        @MethodSource("getFilterInvalidValueOrKeyForFieldTypeArgs")
        void whenFilterInvalidValueOrKeyForFieldType__thenReturn400(String path, SpanFilter filter) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var expectedStatus = HttpStatus.SC_BAD_REQUEST;

            var expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    expectedStatus,
                    "Invalid value '%s' or key '%s' for field '%s' of type '%s'".formatted(
                            filter.value(),
                            filter.key(),
                            filter.field().getQueryParamField(),
                            filter.field().getType()));
            var projectName = generator.generate().toString();
            var filters = List.of(filter);

            Response actualResponse;

            if ("/search".equals(path)) {
                actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(path)
                        .queryParam("project_name", projectName)
                        .queryParam("filters", toURLEncodedQueryParam(filters))
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                        .post(Entity.json(SpanSearchStreamRequest.builder()
                                .projectName(projectName)
                                .filters(filters)
                                .build()));
            } else {
                actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(path)
                        .queryParam("project_name", projectName)
                        .queryParam("filters", toURLEncodedQueryParam(filters))
                        .request()
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                        .get();
            }

            try (actualResponse) {
                assertThat(actualResponse.getStatus()).isEqualTo(expectedStatus);

                var actualError = actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);
                assertThat(actualError).isEqualTo(expectedError);
            }

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

        @ParameterizedTest
        @MethodSource
        void whenSortingByValidFields__thenReturnTracesSorted(Comparator<Span> comparator,
                SortingField sorting) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            AtomicInteger index = new AtomicInteger(0);

            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .feedbackScores(null)
                            .comments(null)
                            .projectName(projectName)
                            .endTime(span.startTime().plus(randomNumber(), ChronoUnit.MILLIS))
                            .totalEstimatedCost(Objects.equals(sorting.field(), SortableFields.TOTAL_ESTIMATED_COST)
                                    ? BigDecimal.valueOf(randomNumber())
                                    : null)
                            .usage(Map.of("total_tokens", RandomUtils.secure().randomInt()))
                            .createdAt(Instant.now().plusMillis(index.getAndIncrement()))
                            .lastUpdatedAt(Instant.now().plusMillis(index.getAndIncrement()))
                            .build())
                    .map(span -> span.toBuilder()
                            .duration(span.startTime().until(span.endTime(), ChronoUnit.MICROS) / 1000.0)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            if (Set.of(SortableFields.CREATED_AT, SortableFields.LAST_UPDATED_AT).contains(sorting.field())) {
                spans.forEach(span -> spanResourceClient.createSpan(span, apiKey, workspaceName));
            } else {
                spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);
            }

            var expectedSpans = spans.stream()
                    .sorted(comparator)
                    .toList();

            getAndAssertPage(workspaceName, projectName, List.of(), spans, expectedSpans, List.of(), apiKey,
                    List.of(sorting), List.of());
        }

        static Stream<Arguments> whenSortingByValidFields__thenReturnTracesSorted() {

            Comparator<Span> inputComparator = Comparator.comparing(span -> span.input().toString());
            Comparator<Span> outputComparator = Comparator.comparing(span -> span.output().toString());
            Comparator<Span> metadataComparator = Comparator.comparing(span -> span.metadata().toString());
            Comparator<Span> tagsComparator = Comparator.comparing(span -> span.tags().toString());
            Comparator<Span> errorInfoComparator = Comparator.comparing(span -> span.errorInfo().toString());
            Comparator<Span> usageComparator = Comparator.comparing(span -> span.usage().get("total_tokens"));

            return Stream.of(
                    Arguments.of(Comparator.comparing(Span::id),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::id).reversed(),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::traceId),
                            SortingField.builder().field(SortableFields.TRACE_ID).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::traceId).reversed(),
                            SortingField.builder().field(SortableFields.TRACE_ID).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::parentSpanId),
                            SortingField.builder().field(SortableFields.PARENT_SPAN_ID).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(Comparator.comparing(Span::parentSpanId).reversed(),
                            SortingField.builder().field(SortableFields.PARENT_SPAN_ID).direction(Direction.DESC)
                                    .build()),
                    Arguments.of(Comparator.comparing(Span::name),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::name).reversed(),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::startTime),
                            SortingField.builder().field(SortableFields.START_TIME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::startTime).reversed(),
                            SortingField.builder().field(SortableFields.START_TIME).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::endTime),
                            SortingField.builder().field(SortableFields.END_TIME).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::endTime).reversed(),
                            SortingField.builder().field(SortableFields.END_TIME).direction(Direction.DESC).build()),
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
                    Arguments.of(usageComparator,
                            SortingField.builder().field("usage.total_tokens").direction(Direction.ASC).build()),
                    Arguments.of(usageComparator.reversed(),
                            SortingField.builder().field("usage.total_tokens").direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::createdAt)
                            .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.ASC).build()),
                    Arguments.of(Comparator.comparing(Span::createdAt).reversed()
                            .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC).build()),
                    Arguments.of(Comparator.comparing(Span::lastUpdatedAt)
                            .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(Comparator.comparing(Span::lastUpdatedAt).reversed()
                            .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.DESC)
                                    .build()),
                    Arguments.of(
                            Comparator.comparing(Span::totalEstimatedCost)
                                    .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.TOTAL_ESTIMATED_COST).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(Comparator.comparing(Span::totalEstimatedCost).reversed()
                            .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.TOTAL_ESTIMATED_COST).direction(Direction.DESC)
                                    .build()),
                    Arguments.of(
                            Comparator.comparing(Span::duration)
                                    .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.DURATION).direction(Direction.ASC).build()),
                    Arguments.of(
                            Comparator.comparing(Span::duration).reversed()
                                    .thenComparing(Comparator.comparing(Span::id).reversed()),
                            SortingField.builder().field(SortableFields.DURATION).direction(Direction.DESC).build()),
                    Arguments.of(errorInfoComparator,
                            SortingField.builder().field(SortableFields.ERROR_INFO).direction(Direction.ASC).build()),
                    Arguments.of(errorInfoComparator.reversed(),
                            SortingField.builder().field(SortableFields.ERROR_INFO).direction(Direction.DESC).build()));
        }

        @Test
        void whenSortingByInvalidField__thenIgnoreAndReturnSuccess() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
            var field = RandomStringUtils.secure().nextAlphanumeric(10);

            // Create a span to ensure the project exists
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .projectName(projectName)
                    .feedbackScores(null)
                    .comments(null)
                    .build();
            spanResourceClient.createSpan(span, apiKey, workspaceName);

            var sortingFields = List.of(SortingField.builder().field(field).direction(Direction.ASC).build());
            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_name", projectName)
                    .queryParam("sorting",
                            URLEncoder.encode(JsonUtils.writeValueAsString(sortingFields), StandardCharsets.UTF_8))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            assertThat(actualResponse.hasEntity()).isTrue();

            var actualEntity = actualResponse.readEntity(Span.SpanPage.class);
            assertThat(actualEntity).isNotNull();
        }

        @ParameterizedTest
        @EnumSource(Direction.class)
        void whenSortingByFeedbackScores__thenReturnTracesSorted(Direction direction) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = RandomStringUtils.secure().nextAlphanumeric(10);

            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .usage(null)
                            .feedbackScores(null)
                            .endTime(span.startTime().plus(randomNumber(), ChronoUnit.MILLIS))
                            .comments(null)
                            .build())
                    .map(trace -> trace.toBuilder()
                            .duration(trace.startTime().until(trace.endTime(), ChronoUnit.MICROS) / 1000.0)
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            List<FeedbackScoreItem.FeedbackScoreBatchItem> scoreForSpan = PodamFactoryUtils.manufacturePojoList(
                    podamFactory,
                    FeedbackScoreItem.FeedbackScoreBatchItem.class);

            List<FeedbackScoreItem.FeedbackScoreBatchItem> allScores = new ArrayList<>();
            for (Span span : spans) {
                for (FeedbackScoreItem.FeedbackScoreBatchItem item : scoreForSpan) {

                    if (spans.getLast().equals(span) && scoreForSpan.getFirst().equals(item)) {
                        continue;
                    }

                    allScores.add(item.toBuilder()
                            .id(span.id())
                            .projectName(span.projectName())
                            .value(podamFactory.manufacturePojo(BigDecimal.class).abs())
                            .build());
                }
            }

            spanResourceClient.feedbackScores(allScores, apiKey, workspaceName);

            var sortingField = new SortingField(
                    "feedback_scores.%s".formatted(scoreForSpan.getFirst().name()),
                    direction);

            Comparator<Span> comparing = Comparator.comparing((Span span) -> Optional.ofNullable(span.feedbackScores())
                    .orElse(List.of())
                    .stream()
                    .filter(score -> score.name().equals(scoreForSpan.getFirst().name()))
                    .findFirst()
                    .map(FeedbackScore::value)
                    .orElse(null),
                    direction == Direction.ASC
                            ? Comparator.nullsFirst(Comparator.naturalOrder())
                            : Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Comparator.comparing(Span::id).reversed());

            var expectedSpans = spans.stream()
                    .map(span -> span.toBuilder()
                            .feedbackScores(
                                    allScores
                                            .stream()
                                            .filter(score -> score.id().equals(span.id()))
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

            getAndAssertPage(workspaceName, projectName, List.of(), spans, expectedSpans, List.of(), apiKey,
                    sortingFields, List.of());
        }

        @Test
        void search__whenFilterIsInvalid__thenReturnProperStatusCode() {

            var body = JsonUtils.getJsonNodeFromString(INVALID_SEARCH_REQUEST);

            try (Response actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("search")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(body))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);

                try (var inputStream = actualResponse.readEntity(new GenericType<ChunkedInput<String>>() {
                })) {
                    TypeReference<ErrorMessage> typeReference = new TypeReference<>() {
                    };
                    String line = inputStream.read();
                    var errorMessage = JsonUtils.readValue(line, typeReference);

                    assertThat(errorMessage.getMessage()).isEqualTo(INVVALID_SEARCH_RESPONSE_MESSAGE);
                    assertThat(errorMessage.getCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
                }
            }
        }

        @ParameterizedTest
        @EnumSource(Span.SpanField.class)
        void findSpans__whenExcludeParamIdDefined__thenReturnSpanExcludingFields(Span.SpanField field) {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();

            var projectName = RandomStringUtils.secure().nextAlphanumeric(20);

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class).stream()
                    .map(span -> span.toBuilder().projectName(projectName).build())
                    .toList();

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            Map<UUID, Comment> expectedComments = spans
                    .stream()
                    .map(span -> Map.entry(span.id(),
                            spanResourceClient.generateAndCreateComment(span.id(), apiKey, workspaceName, 201)))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

            spans = spans.stream()
                    .map(span -> span.toBuilder()
                            .comments(List.of(expectedComments.get(span.id())))
                            .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(span.startTime(),
                                    span.endTime()))
                            .build())
                    .toList();

            List<Span> finalSpans = spans;
            List<FeedbackScoreItem.FeedbackScoreBatchItem> scoreForSpan = IntStream.range(0, spans.size())
                    .mapToObj(i -> podamFactory.manufacturePojo(FeedbackScoreItem.FeedbackScoreBatchItem.class)
                            .toBuilder()
                            .projectName(finalSpans.get(i).projectName())
                            .id(finalSpans.get(i).id())
                            .build())
                    .collect(Collectors.toList());

            spanResourceClient.feedbackScores(scoreForSpan, apiKey, workspaceName);

            spans = spans.stream()
                    .map(span -> span.toBuilder()
                            .feedbackScores(
                                    scoreForSpan
                                            .stream()
                                            .filter(score -> score.id().equals(span.id()))
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

            spans = spans.stream()
                    .map(span -> SpanAssertions.EXCLUDE_FUNCTIONS.get(field).apply(span))
                    .toList();

            List<Span.SpanField> exclude = List.of(field);

            getAndAssertPage(workspaceName, projectName, List.of(), spans, spans.reversed(), List.of(), apiKey,
                    List.of(), exclude);
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterErrorIsNotEmpty__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .errorInfo(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spans.set(0, spans.getFirst().toBuilder()
                    .errorInfo(podamFactory.manufacturePojo(ErrorInfo.class))
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.ERROR_INFO)
                    .operator(Operator.IS_NOT_EMPTY)
                    .value("")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }

        @ParameterizedTest
        @MethodSource("getFilterTestArguments")
        void whenFilterErrorIsEmpty__thenReturnSpansFiltered(String endpoint, SpanPageTestAssertion testAssertion) {
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var projectName = generator.generate().toString();
            var spans = PodamFactoryUtils.manufacturePojoList(podamFactory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder()
                            .projectId(null)
                            .projectName(projectName)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build())
                    .collect(toCollection(ArrayList::new));

            spans.set(0, spans.getFirst().toBuilder()
                    .errorInfo(null)
                    .build());

            spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

            var expectedSpans = List.of(spans.getFirst());
            var unexpectedSpans = List.of(podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectId(null)
                    .build());

            spanResourceClient.batchCreateSpans(unexpectedSpans, apiKey, workspaceName);

            var filters = List.of(SpanFilter.builder()
                    .field(SpanField.ERROR_INFO)
                    .operator(Operator.IS_EMPTY)
                    .value("")
                    .build());

            var values = testAssertion.transformTestParams(spans, expectedSpans, unexpectedSpans);

            testAssertion.runTestAndAssert(projectName, null, apiKey, workspaceName, values.expected(),
                    values.unexpected(),
                    values.all(), filters, Map.of());
        }
    }

    private void getAndAssertPage(
            String workspaceName,
            String projectName,
            List<? extends SpanFilter> filters,
            List<Span> spans,
            List<Span> expectedSpans,
            List<Span> unexpectedSpans,
            String apiKey,
            List<SortingField> sortingFields,
            List<Span.SpanField> exclude) {
        int page = 1;
        int size = spans.size() + expectedSpans.size() + unexpectedSpans.size();
        getAndAssertPage(
                workspaceName,
                projectName,
                null,
                null,
                null,
                filters,
                page,
                size,
                expectedSpans,
                expectedSpans.size(),
                unexpectedSpans,
                apiKey,
                sortingFields,
                exclude);
    }

    private void getAndAssertPage(
            String workspaceName,
            String projectName,
            UUID projectId,
            UUID traceId,
            SpanType type,
            List<? extends SpanFilter> filters,
            int page,
            int size,
            List<Span> expectedSpans,
            int expectedTotal,
            List<Span> unexpectedSpans,
            String apiKey,
            List<SortingField> sortingFields,
            List<Span.SpanField> exclude) {

        Span.SpanPage actualPage = spanResourceClient.findSpans(
                workspaceName,
                apiKey,
                projectName,
                projectId,
                page,
                size,
                traceId,
                type,
                filters,
                sortingFields,
                exclude);

        SpanAssertions.assertPage(actualPage, page, expectedSpans.size(), expectedTotal);
        SpanAssertions.assertSpan(actualPage.content(), expectedSpans, unexpectedSpans, USER);
    }

    private void createAndAssert(UUID entityId, FeedbackScore score, String workspaceName, String apiKey) {
        spanResourceClient.feedbackScore(entityId, score, workspaceName, apiKey);
    }

    private static int randomNumber() {
        return randomNumber(10, 99);
    }

    private static int randomNumber(int minValue, int maxValue) {
        return PodamUtils.getIntegerInRange(minValue, maxValue);
    }

    private Span getAndAssert(Span expectedSpan, String apiKey, String workspaceName) {
        return getAndAssert(expectedSpan, null, apiKey, workspaceName);
    }

    private Span getAndAssert(Span expectedSpan, UUID expectedProjectId, String apiKey, String workspaceName) {
        var actualSpan = spanResourceClient.getById(expectedSpan.id(), workspaceName, apiKey);
        if (expectedProjectId == null) {
            assertThat(actualSpan.projectId()).isNotNull();
        } else {
            assertThat(actualSpan.projectId()).isEqualTo(expectedProjectId);
        }
        SpanAssertions.assertSpan(List.of(actualSpan), List.of(expectedSpan), USER);
        return actualSpan;
    }

    @Nested
    @DisplayName("Get Spans With Time Filtering:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetSpansWithTimeFilteringTests {

        private final StatsTestAssertion statsTestAssertion = new StatsTestAssertion(spanResourceClient);
        private final SpansTestAssertion spansTestAssertion = new SpansTestAssertion(spanResourceClient, USER);
        private final SpanStreamTestAssertion spanStreamTestAssertion = new SpanStreamTestAssertion(spanResourceClient,
                USER);

        private Stream<Arguments> provideBoundaryScenarios() {
            return Stream.of(
                    Arguments.of("/spans/stats", statsTestAssertion, Comparator.comparing(Span::id).reversed()),
                    Arguments.of("/spans", spansTestAssertion,
                            Comparator.comparing(Span::traceId).thenComparing(Span::parentSpanId)
                                    .thenComparing(Span::id).reversed()),
                    Arguments.of("/spans/search", spanStreamTestAssertion, Comparator.comparing(Span::id).reversed()));
        }

        private Span createSpanWithTimestamp(String projectName, Instant timestamp) {
            return podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(idGenerator.generateId(timestamp))
                    .projectName(projectName)
                    .projectId(null)
                    .parentSpanId(null)
                    .feedbackScores(null)
                    .totalEstimatedCost(null)
                    .build();
        }

        private TestWorkspace setupTestWorkspace() {
            var workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = UUID.randomUUID().toString();
            var projectName = RandomStringUtils.secure().nextAlphanumeric(20);

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            return new TestWorkspace(workspaceName, workspaceId, apiKey, projectName);
        }

        @ParameterizedTest
        @DisplayName("filter spans by UUID creation time - includes spans at lower bound, upper bound, and between")
        @MethodSource("provideBoundaryScenarios")
        void whenTimeParametersProvided_thenIncludeSpansWithinBounds(
                String endpoint, SpanPageTestAssertion testAssertion, Comparator<Span> comparator) {
            var workspace = setupTestWorkspace();

            Instant baseTime = Instant.now();
            Instant lowerBound = baseTime.minus(Duration.ofMinutes(10));
            Instant upperBound = baseTime;

            // Create spans with UUIDs at specific boundary times
            List<Span> allSpans = new ArrayList<>();
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), lowerBound));
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), upperBound));
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), lowerBound.plus(Duration.ofMinutes(5))));

            spanResourceClient.batchCreateSpans(allSpans, workspace.apiKey(), workspace.workspaceName());

            var queryParams = Map.of(
                    "from_time", lowerBound.toString(),
                    "to_time", upperBound.toString());

            // Clear projectName from spans since API returns projectName=null
            allSpans = allSpans.stream().map(s -> s.toBuilder().projectName(null).build()).toList();

            // Sort by id descending to match API response order
            allSpans = allSpans.stream()
                    .sorted(comparator)
                    .toList();

            var values = testAssertion.transformTestParams(allSpans, allSpans, List.of());

            testAssertion.runTestAndAssert(workspace.projectName(), null, workspace.apiKey(), workspace.workspaceName(),
                    values.expected(),
                    values.unexpected(), values.all(), List.of(), queryParams);
        }

        @ParameterizedTest
        @DisplayName("filter spans by UUID creation time - excludes spans outside bounds")
        @MethodSource("provideBoundaryScenarios")
        void whenTimeParametersProvided_thenExcludeSpansOutsideBounds(
                String endpoint, SpanPageTestAssertion testAssertion, Comparator<Span> comparator) {
            var workspace = setupTestWorkspace();

            Instant baseTime = Instant.now();
            Instant lowerBound = baseTime.minus(Duration.ofMinutes(10));
            Instant upperBound = baseTime;

            // Create spans: 3 within bounds, 2 outside bounds
            List<Span> allSpans = new ArrayList<>();

            // Within bounds - indices 0, 1, 2
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), lowerBound));
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), upperBound));
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), lowerBound.plus(Duration.ofMinutes(1))));

            // Outside bounds - indices 3, 4
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), lowerBound.minus(Duration.ofMinutes(1))));
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), upperBound.plus(Duration.ofMinutes(1))));

            spanResourceClient.batchCreateSpans(allSpans, workspace.apiKey(), workspace.workspaceName());

            var queryParams = Map.of(
                    "from_time", lowerBound.toString(),
                    "to_time", upperBound.toString());

            // Expected: indices 0, 1, 2 (within bounds)
            var expectedSpans = allSpans.subList(0, 3).stream().map(s -> s.toBuilder().projectName(null).build())
                    .toList();
            var unexpectedSpans = allSpans.subList(3, 5).stream().map(s -> s.toBuilder().projectName(null).build())
                    .toList();

            // Sort expected spans by id descending to match API response order

            expectedSpans = expectedSpans.stream()
                    .sorted(comparator)
                    .toList();

            var values = testAssertion.transformTestParams(
                    allSpans.stream().map(s -> s.toBuilder().projectName(null).build()).toList(), expectedSpans,
                    unexpectedSpans);

            testAssertion.runTestAndAssert(workspace.projectName(), null, workspace.apiKey(), workspace.workspaceName(),
                    values.expected(),
                    values.unexpected(), values.all(), List.of(), queryParams);
        }

        @ParameterizedTest
        @DisplayName("time filtering works with only from_time parameter - to_time is optional")
        @MethodSource("provideBoundaryScenarios")
        void whenOnlyFromTimeProvided_thenFilterSpansFromThatTime(
                String endpoint, SpanPageTestAssertion testAssertion, Comparator<Span> comparator) {
            var workspace = setupTestWorkspace();

            Instant baseTime = Instant.now();
            Instant fromTime = baseTime.minus(Duration.ofMinutes(5));

            // Create spans: some before fromTime, some after
            List<Span> allSpans = new ArrayList<>();
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), fromTime.minus(Duration.ofMinutes(10))));
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), fromTime));
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), fromTime.plus(Duration.ofMinutes(2))));
            allSpans.add(createSpanWithTimestamp(workspace.projectName(), baseTime));

            spanResourceClient.batchCreateSpans(allSpans, workspace.apiKey(), workspace.workspaceName());

            var queryParams = Map.of("from_time", fromTime.toString());

            // Expected: spans at indices 1, 2, 3 (from fromTime onwards)
            var expectedSpans = allSpans.subList(1, 4).stream()
                    .map(s -> s.toBuilder().projectName(null).build())
                    .sorted(comparator)
                    .toList();
            var unexpectedSpans = allSpans.subList(0, 1).stream()
                    .map(s -> s.toBuilder().projectName(null).build())
                    .toList();

            var values = testAssertion.transformTestParams(
                    allSpans.stream().map(s -> s.toBuilder().projectName(null).build()).toList(),
                    expectedSpans,
                    unexpectedSpans);

            testAssertion.runTestAndAssert(workspace.projectName(), null, workspace.apiKey(), workspace.workspaceName(),
                    values.expected(),
                    values.unexpected(), values.all(), List.of(), queryParams);
        }

    }

}

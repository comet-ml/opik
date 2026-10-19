package com.comet.opik.infrastructure;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanSearchStreamRequest;
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
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
import com.comet.opik.api.resources.utils.spans.SpanPageTestAssertion;
import com.comet.opik.api.resources.utils.spans.SpanStreamTestAssertion;
import com.comet.opik.api.resources.utils.spans.SpansTestAssertion;
import com.comet.opik.api.resources.utils.spans.StatsTestAssertion;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.spans.SpanAssertions.assertPage;
import static com.comet.opik.api.resources.utils.spans.SpanAssertions.assertSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The spans list, stream and stats reads through a real {@code Distributed} {@code spans} (OPIK-8833): the same
 * responses as on the plain table, with the work done on the shard.
 *
 * <p>Every read runs on each {@link Topology} in turn, as a class parameter, as {@code FreeFormSqlPostRunCheckTest}
 * does: the partitioned successor the cutover installs as {@code spans}, then the same table behind the cutover's
 * {@code Distributed} wrap. The fixture is written through the API before either, and the wrap is metadata-only, so
 * both see the same rows. Each response is asserted against expectations derived from the fixture, with the
 * assertions {@code FindSpansResourceTest} uses.
 *
 * <p>Equal responses do not show the shard did the work: a read that ships the whole project to the initiator returns
 * the right rows too. So on the wrapper the shard-side {@code system.query_log} rows of each read are asserted as
 * well, the only place that work is visible, as {@code SpansReadPathPartitionPruningTest} does for partitions.
 * The test profile sets {@code prefer_localhost_replica = 0}, as deployments do, so this single local shard is queried
 * over the network and logged as a secondary query.
 *
 * <p>Two steps bypass the API. The cutover's {@code EXCHANGE} and wrap are operator work, so they mirror the runbook
 * in raw SQL, as the other cutover suites do. The project the read bound is measured on is one
 * {@code INSERT ... SELECT}: the bound relies on a read in key order stopping early within a part, and API ingestion
 * writes a part per batch of at most 1000 spans, under one granule each, so a project ingested through it reads every
 * part in full until background merges catch up.
 *
 * <p>Dedicated, non-reused containers: the {@code EXCHANGE} and the wrap destructively swap the live {@code spans}.
 */
@ParameterizedClass(name = "{0}")
@EnumSource(SpansDistributedWrapReadTest.Topology.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpansDistributedWrapReadTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    /** Three weeks of spans, so a time range selects one week's partition and leaves the others. */
    private static final List<Duration> SPAN_AGES = List.of(Duration.ofDays(1), Duration.ofDays(8),
            Duration.ofDays(15));
    private static final Instant TIME_RANGE_FROM = NOW.minus(Duration.ofDays(10));
    private static final Instant TIME_RANGE_TO = NOW.minus(Duration.ofDays(5));

    private static final int TRACES = 3;
    private static final int SPANS_PER_TRACE = 4;
    private static final int PAGE_SIZE = 5;
    private static final String TOTAL_TOKENS = "total_tokens";

    /**
     * Many granules (8192 rows each) where a page reads a couple, so a whole-project read and a page read are far
     * apart.
     */
    private static final int BULK_SPANS = 200_000;

    /** The list's default order: trace, then id, descending, both compared as the stored strings. */
    private static final Comparator<Span> LIST_ORDER = Comparator
            .comparing((Span span) -> span.traceId().toString())
            .thenComparing(span -> span.id().toString())
            .reversed();

    private static final Comparator<Span> STREAM_ORDER = Comparator
            .comparing((Span span) -> span.id().toString())
            .reversed();

    /** The shard-side queries of the latest {@code op} call on a project. */
    private static final String SHARD_QUERIES = """
            SELECT count() AS queries, sum(read_rows) AS read_rows, max(result_rows) AS max_result_rows
            FROM system.query_log
            WHERE type = 'QueryFinish'
            AND is_initial_query = 0
            AND initial_query_id = (
                SELECT query_id
                FROM system.query_log
                WHERE type = 'QueryFinish'
                AND is_initial_query = 1
                AND log_comment LIKE concat(:op, ':%')
                AND query LIKE concat('%', :project_id, '%')
                ORDER BY event_time_microseconds DESC
                LIMIT 1
            )
            """;

    private static final String FAST_LOG_FLUSH_CONFIG = "clickhouse-fast-log-flush.xml";

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer)
            .withCopyFileToContainer(MountableFile.forClasspathResource(FAST_LOG_FLUSH_CONFIG),
                    "/etc/clickhouse-server/config.d/%s".formatted(FAST_LOG_FLUSH_CONFIG));
    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    /** Installs the topology, which has to happen before the app boots against it, so it does not go through the app. */
    private final TransactionTemplateAsync template;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        template = TransactionTemplateAsync.create(databaseAnalyticsFactory.build());
        installPartitionedSuccessorUnderSpans();
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
                                // Flipped with the cutover: the successor's end_time/ttft are non-nullable.
                                new CustomConfig("databaseAnalyticsDataModel.spanColumnsNonNullable", "true"),
                                // The production default; the fixture's ids go back three weeks.
                                new CustomConfig("uuidValidation.enabled", "false")))
                        .build());
    }

    @Parameter
    Topology topology;

    private SpanResourceClient spanResourceClient;
    private ProjectResourceClient projectResourceClient;
    private Fixture fixture;
    private UUID bulkProjectId;
    private UUID streamProjectId;
    private UUID statsProjectId;
    private UUID countProjectId;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);

        // Every write lands on the plain table, synchronously, before either topology is read.
        fixture = seedFixture();
        // One project per shard-side assertion, so each project's latest call of an op is the one under test.
        bulkProjectId = createProject();
        seedBulkSpans(bulkProjectId);
        streamProjectId = createProjectWithSpans(PAGE_SIZE * 3);
        statsProjectId = createProjectWithSpans(PAGE_SIZE);
        countProjectId = createProjectWithSpans(PAGE_SIZE);
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /** Every write has landed on the plain table by now, so the wrap leaves the same rows under both topologies. */
    @BeforeParameterizedClassInvocation
    void applyTopology() {
        topology.statements.forEach(sql -> execute(sql, _ -> {
        }));
    }

    private Stream<Arguments> filteredReads() {
        var list = Endpoint.builder()
                .testAssertion(new SpansTestAssertion(spanResourceClient, USER))
                .order(LIST_ORDER)
                .searchable(true)
                .build();
        var stream = Endpoint.builder()
                .testAssertion(new SpanStreamTestAssertion(spanResourceClient, USER))
                .order(STREAM_ORDER)
                // The stream request has no search.
                .searchable(false)
                .build();
        var stats = Endpoint.builder()
                .testAssertion(new StatsTestAssertion(spanResourceClient))
                .order(LIST_ORDER)
                .searchable(true)
                .build();
        return Stream.of(Named.of("/spans", list), Named.of("/spans/search", stream),
                Named.of("/spans/stats", stats))
                .flatMap(endpoint -> readShapes()
                        .filter(shape -> endpoint.getPayload().searchable()
                                || !shape.getPayload().queryParams().containsKey("search"))
                        .map(shape -> arguments(endpoint, shape)));
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("filteredReads")
    @SuppressWarnings("unchecked")
    void filteredReadReturnsTheMatchingSpans(Endpoint endpoint, ReadShape shape) {
        var expectedSpans = fixture.spans().stream()
                .filter(shape.matches())
                .sorted(endpoint.order())
                .toList();
        // An empty expectation would make the assertion vacuous; every shape is built to select something.
        assertThat(expectedSpans).isNotEmpty();

        var testArgs = endpoint.testAssertion().transformTestParams(fixture.spans(), expectedSpans, List.of());

        endpoint.testAssertion().runTestAndAssert(fixture.projectName(), null, API_KEY, WORKSPACE_NAME,
                testArgs.expected(), testArgs.unexpected(), testArgs.all(), shape.filters(),
                shape.queryParams());
    }

    private Stream<Named<ListPage>> listPages() {
        var spansInListOrder = fixture.spans().stream().sorted(LIST_ORDER).toList();
        var defaultSortPages = IntStream.rangeClosed(1, pageCount(spansInListOrder))
                .mapToObj(page -> Named.of("default sort, page " + page, ListPage.builder()
                        .page(page)
                        .size(PAGE_SIZE)
                        .allExpectedSpans(spansInListOrder)
                        .build()));
        return Stream.concat(defaultSortPages, Stream.of(
                Named.of("filter, page 2", ListPage.builder()
                        .page(2)
                        .size(PAGE_SIZE)
                        .filters(metadataFilter())
                        .allExpectedSpans(spansInListOrder.stream().filter(this::hasFilteredMetadata).toList())
                        .build()),
                // A custom sort keeps the dedup and the page on separate levels, unlike the default one.
                Named.of("custom sort, page 2", ListPage.builder()
                        .page(2)
                        .size(PAGE_SIZE)
                        .sorting(List.of(new SortingField("name", Direction.ASC)))
                        .allExpectedSpans(fixture.spans().stream().sorted(Comparator.comparing(Span::name)).toList())
                        .build()),
                Named.of("excluded fields", ListPage.builder()
                        .page(1)
                        .size(spansInListOrder.size())
                        .exclude(List.of(Span.SpanField.INPUT, Span.SpanField.METADATA))
                        .allExpectedSpans(spansInListOrder.stream()
                                .map(SpanAssertions.EXCLUDE_FUNCTIONS.get(Span.SpanField.INPUT))
                                .map(SpanAssertions.EXCLUDE_FUNCTIONS.get(Span.SpanField.METADATA))
                                .toList())
                        .build())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listPages")
    void listReturnsThePage(ListPage listPage) {
        var expectedSpans = pageOf(listPage.allExpectedSpans(), listPage.page(), listPage.size());

        var actualPage = spanResourceClient.findSpans(WORKSPACE_NAME, API_KEY, fixture.projectName(), null,
                listPage.page(), listPage.size(), null, null, listPage.filters(), listPage.sorting(),
                listPage.exclude());

        assertPage(actualPage, listPage.page(), expectedSpans.size(), listPage.allExpectedSpans().size());
        assertSpan(actualPage.content(), expectedSpans, USER);
    }

    private Stream<Named<StreamRead>> streamReads() {
        var spansInStreamOrder = fixture.spans().stream().sorted(STREAM_ORDER).toList();
        var cursor = spansInStreamOrder.get(PAGE_SIZE - 1);
        var spansAfterCursor = spansInStreamOrder.subList(PAGE_SIZE, spansInStreamOrder.size());
        // The tag of the first span past the cursor, so the filtered expectation is never empty.
        var filteredTag = spansAfterCursor.getFirst().tags().iterator().next();
        return Stream.of(
                Named.of("limit", StreamRead.builder()
                        .request(SpanSearchStreamRequest.builder()
                                .projectName(fixture.projectName())
                                .limit(PAGE_SIZE)
                                .build())
                        .expectedSpans(spansInStreamOrder.subList(0, PAGE_SIZE))
                        .build()),
                Named.of("cursor", StreamRead.builder()
                        .request(SpanSearchStreamRequest.builder()
                                .projectName(fixture.projectName())
                                .lastRetrievedId(cursor.id())
                                .build())
                        .expectedSpans(spansAfterCursor)
                        .build()),
                Named.of("cursor and filter", StreamRead.builder()
                        .request(SpanSearchStreamRequest.builder()
                                .projectName(fixture.projectName())
                                .lastRetrievedId(cursor.id())
                                .filters(List.of(SpanFilter.builder()
                                        .field(SpanField.TAGS)
                                        .operator(Operator.CONTAINS)
                                        .value(filteredTag)
                                        .build()))
                                .build())
                        .expectedSpans(spansAfterCursor.stream().filter(span -> span.tags().contains(filteredTag))
                                .toList())
                        .build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streamReads")
    void streamReturnsTheSpans(StreamRead streamRead) {
        var expectedSpans = streamRead.expectedSpans();

        var actualSpans = spanResourceClient.getStreamAndAssertContent(API_KEY, WORKSPACE_NAME,
                streamRead.request());

        assertSpan(actualSpans, expectedSpans, USER);
    }

    /** In key order, the default-sort page stops reading once it has the page, so it reads a slice of the project. */
    @Test
    void defaultSortPageReadsASliceOfTheProjectOnTheShard() {
        assumeTrue(topology == Topology.DISTRIBUTED_WRAPPER, "only the wrapper reads through a shard");

        var actualPage = spanResourceClient.findSpans(WORKSPACE_NAME, API_KEY, null, bulkProjectId, 1,
                PAGE_SIZE, null, null, null, null, null);
        assertThat(actualPage.content()).hasSize(PAGE_SIZE);

        var actualShardQueries = shardQueriesOf("find_spans_by_project_id", bulkProjectId);

        assertThat(actualShardQueries.readRows()).isLessThan(BULK_SPANS / 4);
        assertThat(actualShardQueries.maxResultRows()).isLessThanOrEqualTo(PAGE_SIZE);
    }

    /** The stream cannot read in key order, but its page {@code LIMIT} runs on the shard, so only the page ships. */
    @Test
    void streamShipsOnlyItsPageFromTheShard() {
        assumeTrue(topology == Topology.DISTRIBUTED_WRAPPER, "only the wrapper reads through a shard");

        var actualSpans = spanResourceClient.getStreamAndAssertContent(API_KEY, WORKSPACE_NAME,
                SpanSearchStreamRequest.builder().projectId(streamProjectId).limit(PAGE_SIZE).build());
        assertThat(actualSpans).hasSize(PAGE_SIZE);

        var actualShardQueries = shardQueriesOf("find_span_stream", streamProjectId);

        assertThat(actualShardQueries.maxResultRows()).isLessThanOrEqualTo(PAGE_SIZE);
    }

    /** Aggregated where it reads, so the shard returns one finished row. */
    @Test
    void statsShipOneRowFromTheShard() {
        assumeTrue(topology == Topology.DISTRIBUTED_WRAPPER, "only the wrapper reads through a shard");

        spanResourceClient.getSpansStats(null, statsProjectId, null, API_KEY, WORKSPACE_NAME, Map.of());

        var actualShardQueries = shardQueriesOf("get_span_stats", statsProjectId);

        assertThat(actualShardQueries.maxResultRows()).isEqualTo(1);
    }

    /** Counted where it reads, so the shard returns the finished count rather than every id of the project. */
    @Test
    void countShipsOneRowFromTheShard() {
        assumeTrue(topology == Topology.DISTRIBUTED_WRAPPER, "only the wrapper reads through a shard");

        var actualPage = spanResourceClient.findSpans(WORKSPACE_NAME, API_KEY, null, countProjectId, 1, PAGE_SIZE,
                null, null, null, null, null);
        assertThat(actualPage.total()).isEqualTo(PAGE_SIZE);

        var actualShardQueries = shardQueriesOf("count_spans_by_project_id", countProjectId);

        assertThat(actualShardQueries.maxResultRows()).isEqualTo(1);
    }

    private Stream<Named<ReadShape>> readShapes() {
        var firstTraceId = fixture.traceIds().getFirst();
        return Stream.of(
                Named.of("all spans", ReadShape.builder().matches(_ -> true).build()),
                Named.of("time range", ReadShape.builder()
                        .queryParams(Map.of(
                                "from_time", TIME_RANGE_FROM.toString(),
                                "to_time", TIME_RANGE_TO.toString()))
                        .matches(span -> !span.startTime().isBefore(TIME_RANGE_FROM)
                                && !span.startTime().isAfter(TIME_RANGE_TO))
                        .build()),
                Named.of("trace_id", ReadShape.builder()
                        .queryParams(Map.of("trace_id", firstTraceId.toString()))
                        .matches(span -> span.traceId().equals(firstTraceId))
                        .build()),
                Named.of("type", ReadShape.builder()
                        .queryParams(Map.of("type", SpanType.llm.name()))
                        .matches(span -> span.type() == SpanType.llm)
                        .build()),
                Named.of("search", ReadShape.builder()
                        .queryParams(Map.of("search", fixture.searchTerm()))
                        .matches(span -> containsSearchTerm(span.input()) || containsSearchTerm(span.output()))
                        .build()),
                Named.of("duration filter", ReadShape.builder()
                        .filters(durationFilter())
                        .matches(span -> durationMillis(span) > fixture.durationThreshold())
                        .build()),
                Named.of("tags filter", ReadShape.builder()
                        .filters(List.of(SpanFilter.builder()
                                .field(SpanField.TAGS)
                                .operator(Operator.CONTAINS)
                                .value(fixture.filteredTag())
                                .build()))
                        .matches(span -> span.tags().contains(fixture.filteredTag()))
                        .build()),
                Named.of("usage filter", ReadShape.builder()
                        .filters(List.of(SpanFilter.builder()
                                .field(SpanField.USAGE_TOTAL_TOKENS)
                                .operator(Operator.GREATER_THAN)
                                .value(String.valueOf(fixture.usageThreshold()))
                                .build()))
                        .matches(span -> span.usage().get(TOTAL_TOKENS) > fixture.usageThreshold())
                        .build()),
                Named.of("input filter", ReadShape.builder()
                        .filters(List.of(SpanFilter.builder()
                                .field(SpanField.INPUT)
                                .operator(Operator.CONTAINS)
                                .value(fixture.searchTerm())
                                .build()))
                        .matches(span -> containsSearchTerm(span.input()))
                        .build()),
                Named.of("output filter", ReadShape.builder()
                        .filters(List.of(SpanFilter.builder()
                                .field(SpanField.OUTPUT)
                                .operator(Operator.CONTAINS)
                                .value(fixture.searchTerm())
                                .build()))
                        .matches(span -> containsSearchTerm(span.output()))
                        .build()),
                Named.of("metadata filter", ReadShape.builder()
                        .filters(metadataFilter())
                        .matches(this::hasFilteredMetadata)
                        .build()),
                Named.of("feedback-score filter", ReadShape.builder()
                        .filters(List.of(SpanFilter.builder()
                                .field(SpanField.FEEDBACK_SCORES)
                                .operator(Operator.GREATER_THAN)
                                .key(fixture.scoreName())
                                .value(fixture.scoreThreshold().toPlainString())
                                .build()))
                        .matches(span -> span.feedbackScores() != null
                                && span.feedbackScores().getFirst().value().compareTo(fixture.scoreThreshold()) > 0)
                        .build()),
                Named.of("empty-feedback-score filter", ReadShape.builder()
                        .filters(emptyFeedbackScoreFilter())
                        .matches(span -> span.feedbackScores() == null)
                        .build()),
                // A feedback-score filter with a narrowing one keys the aggregates on the span_id_prefilter CTE.
                Named.of("empty-feedback-score filter and type", ReadShape.builder()
                        .filters(emptyFeedbackScoreFilter())
                        .queryParams(Map.of("type", SpanType.llm.name()))
                        .matches(span -> span.feedbackScores() == null && span.type() == SpanType.llm)
                        .build()));
    }

    private List<SpanFilter> metadataFilter() {
        return List.of(SpanFilter.builder()
                .field(SpanField.METADATA)
                .operator(Operator.EQUAL)
                .key(fixture.metadataKey())
                .value(fixture.filteredMetadataValue())
                .build());
    }

    private boolean hasFilteredMetadata(Span span) {
        return span.metadata() != null
                && fixture.filteredMetadataValue().equals(span.metadata().path(fixture.metadataKey()).asText());
    }

    private List<SpanFilter> emptyFeedbackScoreFilter() {
        return List.of(SpanFilter.builder()
                .field(SpanField.FEEDBACK_SCORES)
                .operator(Operator.IS_EMPTY)
                .key(fixture.scoreName())
                .value("")
                .build());
    }

    private List<SpanFilter> durationFilter() {
        return List.of(SpanFilter.builder()
                .field(SpanField.DURATION)
                .operator(Operator.GREATER_THAN)
                .value(String.valueOf(fixture.durationThreshold()))
                .build());
    }

    private boolean containsSearchTerm(JsonNode payload) {
        return payload != null && payload.toString().contains(fixture.searchTerm());
    }

    private long durationMillis(Span span) {
        return Duration.between(span.startTime(), span.endTime()).toMillis();
    }

    private int pageCount(List<Span> spans) {
        return (spans.size() + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    private List<Span> pageOf(List<Span> spans, int page, int size) {
        return spans.subList((page - 1) * size, Math.min(page * size, spans.size()));
    }

    /**
     * {@value #TRACES} traces of {@value #SPANS_PER_TRACE} spans across {@link #SPAN_AGES}. Every attribute a read
     * filters on varies by span, every other span is renamed so that it has two versions for the dedup to collapse,
     * every third carries a score and every fifth a comment. The spans are returned as stored, which is what the reads
     * must return.
     */
    private Fixture seedFixture() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var searchTerm = "search-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var scoreName = "score-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var metadataKey = "key-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var tags = randomStrings("tag-", 3);
        var metadataValues = randomStrings("value-", 2);
        var traceIds = IntStream.range(0, TRACES).mapToObj(_ -> ID_GENERATOR.generateId()).toList();

        var createdSpans = IntStream.range(0, TRACES * SPANS_PER_TRACE)
                .mapToObj(i -> {
                    var span = factory.manufacturePojo(Span.class);
                    var startTime = NOW.minus(SPAN_AGES.get(i % SPAN_AGES.size()));
                    var promptTokens = RandomUtils.secure().randomInt(1, 10_000);
                    var completionTokens = RandomUtils.secure().randomInt(1, 10_000);
                    return span.toBuilder()
                            .id(ID_GENERATOR.generateId(startTime))
                            .projectName(projectName)
                            .projectId(null)
                            .traceId(traceIds.get(i / SPANS_PER_TRACE))
                            .parentSpanId(null)
                            .type(SpanType.values()[i % SpanType.values().length])
                            .startTime(startTime)
                            .endTime(startTime.plusMillis(RandomUtils.secure().randomLong(1, 60_000)))
                            .tags(Set.of(tags.get(i % tags.size())))
                            .usage(Map.of("prompt_tokens", promptTokens, "completion_tokens", completionTokens,
                                    TOTAL_TOKENS, promptTokens + completionTokens))
                            .input(i % 2 == 0 ? withSearchTerm(searchTerm) : span.input())
                            .output(i % 3 == 0 ? withSearchTerm(searchTerm) : span.output())
                            .metadata(i % 4 == 0
                                    ? null
                                    : JsonUtils.valueToTree(
                                            Map.of(metadataKey, metadataValues.get(i % metadataValues.size()))))
                            .errorInfo(i % 4 == 1 ? span.errorInfo() : null)
                            .totalEstimatedCost(null)
                            .feedbackScores(null)
                            .build();
                })
                .toList();
        spanResourceClient.batchCreateSpans(createdSpans, API_KEY, WORKSPACE_NAME);

        var storedSpans = IntStream.range(0, createdSpans.size())
                .mapToObj(i -> applyFollowUpWrites(createdSpans.get(i), i, scoreName))
                .toList();

        return Fixture.builder()
                .projectName(projectName)
                .traceIds(traceIds)
                .spans(storedSpans)
                .searchTerm(searchTerm)
                .scoreName(scoreName)
                .scoreThreshold(median(storedSpans.stream()
                        .filter(span -> span.feedbackScores() != null)
                        .map(span -> span.feedbackScores().getFirst().value())
                        .toList()))
                .filteredTag(tags.get(1))
                .metadataKey(metadataKey)
                .filteredMetadataValue(metadataValues.get(1))
                .durationThreshold(median(storedSpans.stream()
                        .map(this::durationMillis)
                        .toList()))
                .usageThreshold(median(storedSpans.stream()
                        .map(span -> span.usage().get(TOTAL_TOKENS))
                        .toList()))
                .build();
    }

    /** Renames, scores and comments the span as {@link #seedFixture} describes, and returns it as stored. */
    private Span applyFollowUpWrites(Span span, int i, String scoreName) {
        var storedSpan = span;
        if (i % 2 == 0) {
            var name = "span-" + RandomStringUtils.secure().nextAlphanumeric(32);
            spanResourceClient.updateSpan(span.id(), SpanUpdate.builder()
                    .projectName(span.projectName())
                    .traceId(span.traceId())
                    .name(name)
                    .build(), API_KEY, WORKSPACE_NAME);
            storedSpan = storedSpan.toBuilder().name(name).build();
        }
        if (i % 3 == 0) {
            var score = factory.manufacturePojo(FeedbackScore.class).toBuilder().name(scoreName).build();
            spanResourceClient.feedbackScore(span.id(), score, WORKSPACE_NAME, API_KEY);
            storedSpan = storedSpan.toBuilder().feedbackScores(List.of(score)).build();
        }
        if (i % 5 == 0) {
            var comment = spanResourceClient.generateAndCreateComment(span.id(), API_KEY, WORKSPACE_NAME,
                    HttpStatus.SC_CREATED);
            storedSpan = storedSpan.toBuilder().comments(List.of(comment)).build();
        }
        return storedSpan;
    }

    private JsonNode withSearchTerm(String searchTerm) {
        return JsonUtils.valueToTree(Map.of("key-" + RandomStringUtils.secure().nextAlphanumeric(32), searchTerm));
    }

    private List<String> randomStrings(String prefix, int count) {
        return Stream.generate(() -> prefix + RandomStringUtils.secure().nextAlphanumeric(32)).limit(count).toList();
    }

    /** The middle value, used as a strict lower bound, so a filter on it selects some spans and leaves others. */
    private <T extends Comparable<T>> T median(List<T> values) {
        return values.stream().sorted().toList().get(values.size() / 2);
    }

    private UUID createProject() {
        return projectResourceClient.createProject("project-" + RandomStringUtils.secure().nextAlphanumeric(32),
                API_KEY,
                WORKSPACE_NAME);
    }

    private UUID createProjectWithSpans(int count) {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var spans = IntStream.range(0, count)
                .mapToObj(_ -> factory.manufacturePojo(Span.class).toBuilder()
                        .projectName(projectName)
                        .projectId(null)
                        .parentSpanId(null)
                        .feedbackScores(null)
                        .build())
                .toList();
        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);
        return projectId;
    }

    /** {@value #BULK_SPANS} spans in this week's partition, as one part; the class Javadoc says why not the API. */
    private void seedBulkSpans(UUID projectId) {
        execute("""
                INSERT INTO spans (id, workspace_id, project_id, trace_id, name, type)
                SELECT toString(generateUUIDv7(number)), :workspace_id, :project_id,
                       toString(generateUUIDv7(number + :count)), :name, 'general'
                FROM numbers(:count)
                """, statement -> statement
                .bind("workspace_id", WORKSPACE_ID)
                .bind("project_id", projectId.toString())
                .bind("name", "span-" + RandomStringUtils.secure().nextAlphanumeric(32))
                .bind("count", BULK_SPANS));
    }

    /** Polled, since {@code query_log} is written asynchronously, flushed every 200 ms here. */
    private ShardQueries shardQueriesOf(String op, UUID projectId) {
        return Awaitility.await()
                .alias("query_log holds the shard-side queries of %s on project %s".formatted(op, projectId))
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> template.nonTransaction(connection -> Mono.from(connection.createStatement(SHARD_QUERIES)
                        .bind("op", op)
                        .bind("project_id", projectId.toString())
                        .execute())
                        .flatMap(result -> Mono.from(result.map((row, _) -> ShardQueries.builder()
                                .queries(row.get("queries", Long.class))
                                .readRows(row.get("read_rows", Long.class))
                                .maxResultRows(row.get("max_result_rows", Long.class))
                                .build()))))
                        .block(), shardQueries -> shardQueries.queries() > 0);
    }

    /** The cutover's {@code EXCHANGE}: the partitioned successor becomes the plain {@code spans}. */
    private void installPartitionedSuccessorUnderSpans() {
        execute("EXCHANGE TABLES spans AND spans_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
    }

    private void execute(String sql, Consumer<Statement> binder) {
        template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute()).flatMap(result -> Mono.from(result.getRowsUpdated()));
        }).block();
    }

    /**
     * The topologies each read runs on, in this order: the plain partitioned {@code spans}, then the cutover's wrap
     * ({@code 000003_exchange_and_wrap.sql}) in front of it, built under a temp name and swapped in by one atomic
     * {@code RENAME}. The wrap cannot be undone, so the plain table comes first.
     */
    @RequiredArgsConstructor
    enum Topology {
        PLAIN_TABLE(List.of()),
        DISTRIBUTED_WRAPPER(List.of(
                """
                        CREATE TABLE spans_dist ON CLUSTER '{cluster}' AS spans
                        ENGINE = Distributed('{cluster}', '%s', 'spans_local', sipHash64(project_id))
                        """.formatted(DATABASE_NAME),
                """
                        RENAME TABLE spans TO spans_local, spans_dist TO spans ON CLUSTER '{cluster}'
                        """));

        private final List<String> statements;
    }

    /** An endpoint's assertion, the order it returns spans in, and whether it takes a search. */
    @Builder(toBuilder = true)
    private record Endpoint(SpanPageTestAssertion testAssertion, Comparator<Span> order, boolean searchable) {
    }

    /** A read's filters and query params, and which fixture spans it must return. */
    @Builder(toBuilder = true)
    private record ReadShape(List<SpanFilter> filters, Map<String, String> queryParams, Predicate<Span> matches) {
        private ReadShape {
            filters = filters == null ? List.of() : filters;
            queryParams = queryParams == null ? Map.of() : queryParams;
        }
    }

    /** A list page request, and every span the list must return across its pages, in order. */
    @Builder(toBuilder = true)
    private record ListPage(int page, int size, List<SpanFilter> filters, List<SortingField> sorting,
            List<Span.SpanField> exclude, List<Span> allExpectedSpans) {
    }

    @Builder(toBuilder = true)
    private record StreamRead(SpanSearchStreamRequest request, List<Span> expectedSpans) {
    }

    @Builder(toBuilder = true)
    private record ShardQueries(long queries, long readRows, long maxResultRows) {
    }

    @Builder(toBuilder = true)
    private record Fixture(String projectName, List<UUID> traceIds, List<Span> spans, String searchTerm,
            String scoreName, BigDecimal scoreThreshold, String filteredTag, String metadataKey,
            String filteredMetadataValue, long durationThreshold, int usageThreshold) {
    }
}

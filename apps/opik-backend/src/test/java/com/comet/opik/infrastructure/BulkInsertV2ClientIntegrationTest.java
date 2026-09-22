package com.comet.opik.infrastructure;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
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
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.FeedbackScoreDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Row;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.datasets.DatasetItemAssertions.assertDatasetItems;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.assertExperimentResults;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the {@code bulkInsert.v2ClientEnabled} write path — feedback scores streamed to ClickHouse as
 * JSONEachRow through the v2 client instead of bound as named R2DBC parameters.
 *
 * <p>Why a separate class rather than a flag on the existing suites: the flag defaults to {@code false},
 * so every other test exercises the R2DBC path. Flipping it there would buy coverage of this path by
 * removing coverage of that one, and the R2DBC path is the one every install runs today. This class runs
 * the v2 path alongside them instead. Traces are created here only to hang scores off — that write still
 * goes through R2DBC.
 *
 * <p>It deliberately asserts only what a real server can settle, since the framing and the settings are
 * already unit-tested in {@code JsonEachRowBulkInsertTest}:
 *
 * <ul>
 *   <li><b>Type encodings</b> that JSONEachRow is stricter about than {@code FORMAT Values} — a
 *       {@code Decimal(18, 9)} value written as a quoted string, and a {@code FixedString(36)} queue id
 *       written as {@code ""} when absent.</li>
 *   <li><b>Escaping.</b> Row content carrying a newline and quotes must not split one JSONEachRow line
 *       into two, which would be a server-side parse error rather than a client-visible one.</li>
 *   <li><b>Omitted columns</b> taking their DDL defaults, which is what
 *       {@code input_format_defaults_for_omitted_fields} buys.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class BulkInsertV2ClientIntegrationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String AUTHOR_FOR_REJECTION = "author-that-never-gets-used";

    /**
     * Tolerance for the ClickHouse container's clock against this JVM's when asserting a server-stamped
     * timestamp. Wide enough not to flake on a loaded CI box, far narrower than the drift a stale or
     * hard-coded value would show.
     */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

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
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
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
                        .customConfigs(List.of(new CustomConfig("bulkInsert.v2ClientEnabled", "true")))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private TransactionTemplateAsync clickHouseTemplate;
    private FeedbackScoreDAO feedbackScoreDAO;
    private ProjectResourceClient projectResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, FeedbackScoreDAO feedbackScoreDAO, Injector injector) {
        this.feedbackScoreDAO = feedbackScoreDAO;
        // The shared template rather than a connection factory of our own, so the suite does not open and
        // close a connection per assertion.
        this.clickHouseTemplate = injector.getInstance(TransactionTemplateAsync.class);
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
    }

    private <T> T queryOne(String sql, Function<Row, T> mapper) {
        return clickHouseTemplate.nonTransaction(connection -> Mono
                .from(connection.createStatement(sql).execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> mapper.apply(row)))))
                .block();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
    }

    // Podam's generated id is kept (the factory produces a valid UUIDv7, which ingestion validation
    // requires), and only the sub-objects that would otherwise write scores of their own are nulled.
    private Trace.TraceBuilder newTraceBuilder() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null)
                .errorInfo(null);
    }

    // FeedbackScoreService#getAuthor takes the author from the request context, so anything arriving
    // over HTTP has one and lands in authored_feedback_scores -- the 10-column form of the row. The
    // author-less feedback_scores form is only reachable from a context with no USER_NAME.
    private static String randomName() {
        return "metric-" + RandomStringUtils.secure().nextAlphanumeric(12);
    }

    private FeedbackScoreBatchItem newScore(UUID traceId, String projectName, String name) {
        return factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                .id(traceId)
                .projectName(projectName)
                .name(name)
                // Not "suite_assertion": that category routes to assertion_results instead
                // (ScoreDestination#fromCategoryName) and never reaches this write path.
                .categoryName("quality")
                .sourceQueueId(null)
                .build();
    }

    @Test
    @DisplayName("feedback scores round-trip their decimal value, reason, author and source queue id")
    void feedbackScoresRoundTrip() {
        var trace = newTraceBuilder().build();
        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

        // Decimal(18, 9) at full scale, and a reason whose control characters would end the JSONEachRow
        // line early if they were not escaped -- with a random tail so the assertion is not satisfied by
        // a hardcoded value surviving somewhere it should not.
        var value = new BigDecimal("0." + RandomStringUtils.secure().nextNumeric(9));
        var reason = "line one\nline \"two\"\ttabbed 日本語 " + RandomStringUtils.secure().nextAlphanumeric(16);
        // A podam-generated trace id, which is a valid UUIDv7 -- ingestion rejects anything else.
        var sourceQueueId = factory.manufacturePojo(Trace.class).id();
        var score = newScore(trace.id(), trace.projectName(), randomName()).toBuilder()
                .value(value)
                .reason(reason)
                .sourceQueueId(sourceQueueId)
                .build();

        traceResourceClient.feedbackScores(List.of(score), API_KEY, WORKSPACE_NAME);

        var expected = FeedbackScore.builder()
                .name(score.name())
                .categoryName(score.categoryName())
                .value(value)
                .reason(reason)
                .source(score.source())
                .build();

        var actual = traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY).feedbackScores();
        assertThat(actual)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(TraceAssertions.IGNORED_FIELDS_SCORES)
                .containsExactly(expected);

        // valueByAuthor and sourceQueueId are in IGNORED_FIELDS_SCORES because they are not usually
        // deterministic, but they are two of the columns this write path is responsible for, so they are
        // asserted here rather than left to the shared comparison.
        var stored = actual.getFirst();
        assertThat(stored.valueByAuthor()).hasSize(1);
        var entry = stored.valueByAuthor().values().iterator().next();
        assertThat(entry.author()).isEqualTo(USER);
        assertThat(entry.sourceQueueId()).isEqualTo(sourceQueueId.toString());
    }

    @Test
    @DisplayName("a feedback score batch writes each row once and server-stamps created_at and last_updated_at")
    void feedbackScoreBatchWritesEachRowOnceAndServerStampsTimestamps() {
        var trace = newTraceBuilder().build();
        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

        // Distinct names, so each is its own row under the table's ORDER BY key rather than a dedup
        // candidate.
        var scores = IntStream.range(0, 3)
                .mapToObj(i -> newScore(trace.id(), trace.projectName(), randomName()))
                .toList();

        traceResourceClient.feedbackScores(scores, API_KEY, WORKSPACE_NAME);

        var expected = scores.stream()
                .map(score -> FeedbackScore.builder()
                        .name(score.name())
                        .categoryName(score.categoryName())
                        .value(score.value())
                        .reason(score.reason())
                        .source(score.source())
                        .build())
                .toList();

        var actual = traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY).feedbackScores();
        assertThat(actual)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(TraceAssertions.IGNORED_FIELDS_SCORES)
                .containsExactlyInAnyOrderElementsOf(expected);

        // Both timestamps are omitted from the JSON row so their column DEFAULT now64(9) stamps them.
        // last_updated_at is the ReplacingMergeTree version, so a zero there would make every later score
        // for the same key lose to the original row -- which the API read surfaces directly.
        assertThat(actual).allSatisfy(stored -> {
            assertThat(stored.createdAt()).isAfter(Instant.parse("2000-01-01T00:00:00Z"));
            assertThat(stored.lastUpdatedAt()).isAfter(Instant.parse("2000-01-01T00:00:00Z"));
        });
    }

    @Test
    @DisplayName("an authorless score takes the 8-column feedback_scores branch, not the authored table")
    void authorlessScoreGoesToTheUnauthoredTable() {
        // One of two tests here that cannot be black box. FeedbackScoreService#getAuthor reads the author
        // off the request context, so every score arriving over HTTP has one and only ever reaches
        // authored_feedback_scores. This is the other branch of the row mapper -- a different target
        // table and two fewer columns -- so the DAO seam is the only way in, and the target table is the
        // assertion, which no API read exposes.
        var projectName = "authorless-" + RandomStringUtils.secure().nextAlphanumeric(12);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var trace = newTraceBuilder().projectName(projectName).build();
        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

        var name = randomName();
        var value = new BigDecimal("0." + RandomStringUtils.secure().nextNumeric(9));
        var reason = RandomStringUtils.secure().nextAlphanumeric(24);
        // Below the service layer that resolves projectName -> projectId, so the id is passed explicitly.
        var score = newScore(trace.id(), projectName, name).toBuilder()
                .projectId(projectId)
                .value(value)
                .reason(reason)
                .build();

        feedbackScoreDAO.scoreBatchOf(EntityType.TRACE, List.of(score), null)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();

        var stored = queryOne(
                ("SELECT value, reason, source FROM feedback_scores WHERE workspace_id = '%s' "
                        + "AND entity_id = '%s' AND name = '%s' LIMIT 1").formatted(WORKSPACE_ID, trace.id(), name),
                row -> row.get("value", BigDecimal.class) + "|" + row.get("reason", String.class) + "|"
                        + row.get("source", String.class));
        assertThat(stored).isEqualTo("%s|%s|%s".formatted(value.toPlainString(), reason, score.source().getValue()));

        // Nothing leaked into the authored table, which is what a mis-selected branch looks like -- the
        // row is still written, just to the wrong place.
        Long authoredCount = queryOne(
                ("SELECT count() AS row_count FROM authored_feedback_scores WHERE workspace_id = '%s' "
                        + "AND entity_id = '%s' AND name = '%s'").formatted(WORKSPACE_ID, trace.id(), name),
                row -> row.get("row_count", Long.class));
        assertThat(authoredCount).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("a blank author still selects the authored table, normalized to empty")
    void blankAuthorStillTakesTheAuthoredTable(String blankAuthor) {
        // null and blank are not the same input here. null selects feedback_scores and drops two
        // columns; blank is a present author that normalizes to "", so it stays on the authored table.
        // The R2DBC template agrees -- StringTemplate's <if(author)> is true for "" and for whitespace,
        // matching this path's author != null -- so the two writers pick the same table for this input.
        var projectName = "blank-author-" + RandomStringUtils.secure().nextAlphanumeric(12);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
        var trace = newTraceBuilder().projectName(projectName).build();
        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

        var name = randomName();
        var score = newScore(trace.id(), projectName, name).toBuilder()
                .projectId(projectId)
                .build();

        feedbackScoreDAO.scoreBatchOf(EntityType.TRACE, List.of(score), blankAuthor)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();

        var storedAuthor = queryOne(
                ("SELECT author FROM authored_feedback_scores WHERE workspace_id = '%s' AND entity_id = '%s' "
                        + "AND name = '%s' LIMIT 1").formatted(WORKSPACE_ID, trace.id(), name),
                row -> row.get("author", String.class));
        assertThat(storedAuthor).isEmpty();

        Long unauthoredCount = queryOne(
                ("SELECT count() AS row_count FROM feedback_scores WHERE workspace_id = '%s' "
                        + "AND entity_id = '%s' AND name = '%s'").formatted(WORKSPACE_ID, trace.id(), name),
                row -> row.get("row_count", Long.class));
        assertThat(unauthoredCount).isZero();
    }

    @Test
    @DisplayName("a score with no value is rejected by name, on the v2 path too")
    void scoreWithoutAValueIsRejectedByName() {
        // The other non-black-box case: a null value is rejected by bean validation long before the DAO,
        // so HTTP cannot reach this guard. It exists because the R2DBC binder rejects per item while the
        // row mapper would NPE and take the whole batch down, and it now sits ahead of the path branch so
        // both writers fail alike. This pins that with v2 selected.
        var name = randomName();
        var score = newScore(factory.manufacturePojo(Trace.class).id(), "some-project", name).toBuilder()
                .projectId(UUID.randomUUID())
                .value(null)
                .build();

        assertThatThrownBy(() -> feedbackScoreDAO.scoreBatchOf(EntityType.TRACE, List.of(score), AUTHOR_FOR_REJECTION)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(name)
                .hasMessageContaining("cannot be stored without a value");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("experiment items round-trip through JSONEachRow, with and without a project id")
    void experimentItemsRoundTrip(boolean withProjectId) {
        var experiment = experimentResourceClient.createPartialExperiment().build();
        var experimentId = experimentResourceClient.create(experiment, API_KEY, WORKSPACE_NAME);

        // project_id is Nullable(FixedString(36)) and the only column this mapper writes as an explicit
        // null rather than omitting. The null case is reachable end to end: ExperimentItemService resolves
        // a project from projectName, then from the trace, so an item with neither and a trace id that
        // backs no trace arrives at the DAO with project_id still null.
        var projectName = withProjectId ? "experiment-items-" + RandomStringUtils.secure().nextAlphanumeric(12) : null;
        var projectId = withProjectId
                ? projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME)
                : null;

        // Same shape as ExperimentsResourceTest#getExperimentItemsBatch: trace-derived fields nulled
        // because no trace backs these items, and created/lastUpdatedBy are server-set. What is left is
        // the set of columns this write path owns.
        var items = IntStream.range(0, 3)
                .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(experimentId)
                        .projectId(projectId)
                        .projectName(projectName)
                        .traceVisibilityMode(null)
                        .usage(null)
                        .duration(null)
                        .input(null)
                        .output(null)
                        .totalEstimatedCost(null)
                        .feedbackScores(null)
                        .comments(null)
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build())
                .toList();

        experimentResourceClient.createExperimentItem(new HashSet<>(items), API_KEY, WORKSPACE_NAME);

        // Read each by id rather than streaming: the stream endpoints join the trace, while this returns
        // the row as written -- which is what this path owns.
        var actual = items.stream()
                .map(item -> experimentResourceClient.getExperimentItem(item.id(), API_KEY, WORKSPACE_NAME))
                .toList();

        // The shared helper rather than field by field: it compares the whole object and owns its list of
        // ignored fields, so a column this path stops writing cannot slip through unnoticed. projectId is
        // not among the ignored fields, so both arms of the null branch are actually asserted.
        assertExperimentResults(actual, items, USER);
    }

    @Test
    @DisplayName("dataset items round-trip their data map, tags, source and trace/span ids")
    void datasetItemsRoundTrip() {
        var datasetName = "v2-bulk-" + RandomStringUtils.secure().nextAlphanumeric(12);

        // Two arms, because SourceValidator couples source to the ids: SPAN requires both trace_id and
        // span_id, MANUAL requires both absent. That absent case is the one worth covering here --
        // trace_id/span_id are String DEFAULT '' rather than Nullable, so an omitted id is "" on both
        // write paths, which is what the mapper reuses the binder's getOrDefault for. Tags exercise
        // Array(String) and data exercises Map(String, String).
        var items = IntStream.range(0, 4)
                .mapToObj(i -> {
                    var item = DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                            // Explicit rather than podam's: tags is the Array(String) column this path
                            // owns, and item 3 is left tag-less to cover putStringArray's empty branch.
                            .tags(i == 3
                                    ? null
                                    : Set.of("tag-" + i, "shared-" + RandomStringUtils.secure().nextAlphanumeric(6)))
                            .build();
                    return i % 2 == 0
                            ? item.toBuilder()
                                    .source(DatasetItemSource.SPAN)
                                    // Set explicitly: podam leaves these null some of the time, and
                                    // SourceValidator rejects a SPAN item without both.
                                    .traceId(factory.manufacturePojo(Trace.class).id())
                                    .spanId(factory.manufacturePojo(Trace.class).id())
                                    .build()
                            : item.toBuilder()
                                    .source(DatasetItemSource.MANUAL)
                                    .traceId(null)
                                    .spanId(null)
                                    .build();
                })
                .toList();

        var batch = DatasetResourceClient.buildDatasetItemBatch(factory).toBuilder()
                .datasetName(datasetName)
                .datasetId(null)
                .items(items)
                .build();
        datasetResourceClient.createDatasetItems(batch, WORKSPACE_NAME, API_KEY);

        var actual = items.stream()
                .map(item -> datasetResourceClient.getDatasetItem(item.id(), API_KEY, WORKSPACE_NAME))
                .toList();

        // The shared helper rather than field by field: it compares whole objects and owns its list of
        // ignored fields, so a column this path stops writing cannot slip through unnoticed.
        assertDatasetItems(actual, items);

        // tags is in IGNORED_FIELDS_DATA_ITEM, so the comparison above does not see it -- and tags is the
        // Array(String) column this path is responsible for, the one case the shared helper cannot cover.
        // Compared as sets, since the column does not promise order.
        // null and empty are the same cell here: tags is a non-nullable Array(String), so an absent
        // collection is written as [] by putStringArray and reads back as empty rather than null.
        var actualTags = actual.stream()
                .collect(toMap(item -> item.id(), item -> new HashSet<>(Optional.ofNullable(item.tags())
                        .orElseGet(Set::of))));
        var expectedTags = items.stream()
                .collect(toMap(item -> item.id(), item -> new HashSet<>(Optional.ofNullable(item.tags())
                        .orElseGet(Set::of))));
        assertThat(actualTags).isEqualTo(expectedTags);
        // Not vacuous: three of the four carry tags, so a mapper that dropped them would fail here.
        assertThat(actualTags.values().stream().filter(t -> !t.isEmpty())).hasSize(3);
    }

    @Test
    @DisplayName("a dataset item version carries the item's authorship and server-stamps the row's own timestamps")
    void datasetItemVersionsCarryAuthorshipAndServerStampRowTimestamps() {
        // The version table, not dataset_items. With versioning enabled every read above already goes
        // through DatasetItemVersionDAO, so the shared assertion covers the columns the two tables have
        // in common. What it cannot cover is IGNORED_FIELDS_DATA_ITEM -- and four of those ignored
        // fields are exactly the columns this table adds: item_created_at/by and item_last_updated_at/by,
        // which the read aliases back onto createdAt/createdBy.
        var datasetName = "v2-bulk-versions-" + RandomStringUtils.secure().nextAlphanumeric(12);

        var items = IntStream.range(0, 3)
                .mapToObj(i -> DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                        // MANUAL requires both ids absent; the source/id pairing itself is covered by
                        // datasetItemsRoundTrip, so this test keeps that arm fixed and varies nothing.
                        .source(DatasetItemSource.MANUAL)
                        .traceId(null)
                        .spanId(null)
                        .build())
                .toList();

        var batch = DatasetResourceClient.buildDatasetItemBatch(factory).toBuilder()
                .datasetName(datasetName)
                .datasetId(null)
                .items(items)
                .build();
        // Bracketed rather than lower-bounded: an epoch is not the only wrong value a timestamp column
        // can hold. A hard-coded or stale constant clears any "after 2000" check while proving nothing
        // about server stamping, so the window is the write itself, widened only by clock skew between
        // this JVM and the ClickHouse container.
        var before = Instant.now().minus(CLOCK_SKEW);
        datasetResourceClient.createDatasetItems(batch, WORKSPACE_NAME, API_KEY);
        var after = Instant.now().plus(CLOCK_SKEW);

        var actual = items.stream()
                .map(item -> datasetResourceClient.getDatasetItem(item.id(), API_KEY, WORKSPACE_NAME))
                .toList();

        assertThat(actual).allSatisfy(stored -> {
            assertThat(stored.createdBy()).isEqualTo(USER);
            assertThat(stored.lastUpdatedBy()).isEqualTo(USER);
            // item_created_at has no column DEFAULT, so an omitted or zeroed one reads back as the
            // epoch rather than being stamped. DatasetItem marks these READ_ONLY, so an item arriving
            // over HTTP never carries its own and the mapper's fallback is the only thing that fills
            // them.
            assertThat(stored.createdAt()).isBetween(before, after);
            assertThat(stored.lastUpdatedAt()).isBetween(before, after);
        });

        // One instant for the whole batch rather than one per row. This is a deliberate difference from
        // the R2DBC path, where formatTimestamp(null) mints a fresh Instant.now() per row: the helper
        // re-runs the mapper on every insert attempt, so a per-row clock would give a retried row
        // different timestamp bytes under the same id.
        assertThat(actual.stream().map(DatasetItem::createdAt).distinct()).hasSize(1);

        // The row's own created_at / last_updated_at are omitted from the JSON so their DEFAULT now64(9)
        // stamps them, and no read exposes either. last_updated_at is the ReplacingMergeTree version, so
        // a zero there would make every later write for the same key lose to this row for good.
        var ids = items.stream().map(item -> "'" + item.id() + "'").collect(joining(","));
        var stamped = queryOne(
                ("SELECT count() AS row_count, min(created_at) AS min_created, "
                        + "min(last_updated_at) AS min_updated, max(metadata) AS max_metadata "
                        + "FROM dataset_item_versions WHERE workspace_id = '%s' AND id IN (%s)")
                        .formatted(WORKSPACE_ID, ids),
                row -> new Object[]{row.get("row_count", Long.class), row.get("min_created", Instant.class),
                        row.get("min_updated", Instant.class), row.get("max_metadata", String.class)});

        assertThat(stamped[0]).isEqualTo((long) items.size());
        // Same window as above: these are stamped by now64(9) on the server, so they are the one pair
        // here whose value the client never supplies at all.
        assertThat((Instant) stamped[1]).isBetween(before, after);
        assertThat((Instant) stamped[2]).isBetween(before, after);
        // Written as "" unconditionally, matching the binder -- not carried from the item.
        assertThat(stamped[3]).isEqualTo("");
    }

    @Test
    @DisplayName("traces round-trip their tags, and an absent end_time and ttft stay null")
    void tracesRoundTrip() {
        // traceColumnsNonNullable is false in config-test.yml, so this covers the Nullable branch of
        // end_time / ttft -- the state every install is in until that migration flips. The sentinel
        // branch is not reachable from here (it needs a different app config), so it is covered by
        // TraceJsonRowMapperTest instead.
        var projectName = "v2-traces-" + RandomStringUtils.secure().nextAlphanumeric(12);

        var traces = IntStream.range(0, 4)
                .mapToObj(i -> {
                    var trace = newTraceBuilder()
                            .projectName(projectName)
                            .tags(i == 3 ? null : Set.of("tag-" + i, "shared"))
                            .build();
                    // Half with both optional columns absent: they are Nullable here, so the mapper
                    // writes an explicit JSON null and the read must give null back rather than an
                    // epoch or a 0.0.
                    return i % 2 == 0
                            ? trace.toBuilder().endTime(null).ttft(null).build()
                            : trace;
                })
                .toList();

        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

        var actual = traces.stream()
                .map(trace -> traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY))
                .toList();

        // tags is excluded and compared as a set below: Array(String) keeps the order it was written in,
        // and the source here is a Set, whose iteration order is not defined. Comparing the lists
        // positionally passes or fails on that ordering rather than on anything the mapper controls.
        var ignoredTraceFields = Stream
                .concat(Arrays.stream(TraceAssertions.IGNORED_FIELDS_TRACES), Stream.of("tags"))
                .toArray(String[]::new);

        assertThat(actual)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(ignoredTraceFields)
                .containsExactlyInAnyOrderElementsOf(traces);

        // null and empty are the same cell: tags is a non-nullable Array(String), so an absent collection
        // is written as [] and reads back as empty rather than null.
        var actualTraceTags = actual.stream().collect(toMap(Trace::id,
                trace -> new HashSet<>(Optional.ofNullable(trace.tags()).orElseGet(Set::of))));
        var expectedTraceTags = traces.stream().collect(toMap(Trace::id,
                trace -> new HashSet<>(Optional.ofNullable(trace.tags()).orElseGet(Set::of))));
        assertThat(actualTraceTags).isEqualTo(expectedTraceTags);

        // Asserted separately because the two are what the Nullable branch is about, and a mapper that
        // wrote the sentinel instead would still satisfy the comparison above on every other field.
        assertThat(actual).filteredOn(trace -> traces.stream()
                .anyMatch(expected -> expected.id().equals(trace.id()) && expected.endTime() == null))
                .isNotEmpty()
                .allSatisfy(trace -> {
                    assertThat(trace.endTime()).isNull();
                    assertThat(trace.ttft()).isNull();
                });

        // Not vacuous: three of the four carry tags, so a mapper that dropped them would fail above.
        assertThat(actualTraceTags.values().stream().filter(tags -> !tags.isEmpty())).hasSize(3);

        // Same exactness requirement as spans: the trace mapper writes the decimal expansion, so the
        // double comes back identical rather than 1 ULP away.
        var expectedTraceTtft = traces.stream().filter(trace -> trace.ttft() != null)
                .collect(toMap(Trace::id, Trace::ttft));
        assertThat(expectedTraceTtft).isNotEmpty();
        assertThat(actual).filteredOn(trace -> expectedTraceTtft.containsKey(trace.id()))
                .allSatisfy(trace -> assertThat(trace.ttft()).isEqualTo(expectedTraceTtft.get(trace.id())));
    }

    @Test
    @DisplayName("spans round-trip their usage map and cost, and an absent end_time and ttft stay null")
    void spansRoundTrip() {
        var projectName = "v2-spans-" + RandomStringUtils.secure().nextAlphanumeric(12);
        var trace = newTraceBuilder().projectName(projectName).build();
        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

        var cost = new BigDecimal("0.000000123456");
        var spans = IntStream.range(0, 4)
                .mapToObj(i -> {
                    var span = factory.manufacturePojo(Span.class).toBuilder()
                            .projectName(projectName)
                            .traceId(trace.id())
                            .parentSpanId(null)
                            .feedbackScores(null)
                            .comments(null)
                            .errorInfo(null)
                            // Supplied rather than computed, so total_estimated_cost_version must stay
                            // empty -- the version is stamped only for a cost the DAO derived itself.
                            .totalEstimatedCost(cost)
                            .usage(Map.of("prompt_tokens", 11, "completion_tokens", 22))
                            .build();
                    return i % 2 == 0 ? span.toBuilder().endTime(null).ttft(null).build() : span;
                })
                .toList();

        spanResourceClient.batchCreateSpans(spans, API_KEY, WORKSPACE_NAME);

        var actual = spans.stream()
                .map(span -> spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY))
                .toList();

        // metadata is excluded on top of the shared list and asserted below instead: SpanDAO's READ path
        // runs getMetadataWithProvider, which folds the span's provider into the metadata it returns. That
        // enrichment is independent of the write path, so comparing metadata verbatim would fail on the
        // R2DBC path too.
        // tags joins metadata in the exclusions for the ordering reason given in tracesRoundTrip.
        // ttft is NOT excluded: the mapper writes the exact decimal expansion of the double, so the
        // stored Float64 is bit-for-bit what was handed in and plain equality is the right assertion.
        // Writing Jackson's shortest form instead let ClickHouse's JSON parse land 1 ULP away.
        //
        // totalEstimatedCostVersion stays in the shared ignore list and is asserted separately below.
        // It cannot be compared against the input: the version is the DAO's to decide, so podam's random
        // value on the way in is meaningless -- the assertion worth making is what the DAO stored.
        var ignored = Stream
                .concat(Arrays.stream(SpanAssertions.IGNORED_FIELDS), Stream.of("metadata", "tags"))
                .toArray(String[]::new);

        assertThat(actual)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(ignored)
                .containsExactlyInAnyOrderElementsOf(spans);

        // What the mapper is actually responsible for: every key the span carried survives the round trip.
        // The provider key on top of them is the read-side enrichment above.
        var expectedMetadata = spans.stream().collect(toMap(Span::id, Span::metadata));
        assertThat(actual).allSatisfy(span -> {
            var original = expectedMetadata.get(span.id());
            original.fieldNames().forEachRemaining(field -> assertThat(span.metadata().get(field))
                    .as("metadata field '%s'", field)
                    .isEqualTo(original.get(field)));
        });

        assertThat(actual).allSatisfy(span -> {
            // totalEstimatedCost and totalEstimatedCostVersion are both in IGNORED_FIELDS, so the
            // comparison above never sees them -- and they are the two columns this path owns that no
            // other slice has. Decimal128(12) written via toPlainString: compared by value, since the
            // column's scale is not the BigDecimal's.
            assertThat(span.totalEstimatedCost()).isEqualByComparingTo(cost);
            // Map(String, Int64), the other span-only column shape.
            assertThat(span.usage()).containsEntry("prompt_tokens", 11).containsEntry("completion_tokens", 22);
        });

        var actualSpanTags = actual.stream().collect(toMap(Span::id,
                span -> new HashSet<>(Optional.ofNullable(span.tags()).orElseGet(Set::of))));
        var expectedSpanTags = spans.stream().collect(toMap(Span::id,
                span -> new HashSet<>(Optional.ofNullable(span.tags()).orElseGet(Set::of))));
        assertThat(actualSpanTags).isEqualTo(expectedSpanTags);

        // Bit-for-bit, not approximately: isEqualTo on Double compares the exact value, so a mapper that
        // went back to the shortest-decimal form would fail here rather than pass within a tolerance.
        var expectedTtft = spans.stream().filter(span -> span.ttft() != null)
                .collect(toMap(Span::id, Span::ttft));
        assertThat(expectedTtft).isNotEmpty();
        assertThat(actual).filteredOn(span -> expectedTtft.containsKey(span.id()))
                .allSatisfy(span -> assertThat(span.ttft()).isEqualTo(expectedTtft.get(span.id())));

        // Supplied rather than DAO-derived, so no version is stamped. The stamping branch is
        // deterministic and covered by SpanJsonRowMapperTest, which can set it directly.
        assertThat(actual).allSatisfy(span -> assertThat(span.totalEstimatedCostVersion()).isNullOrEmpty());

        // spanColumnsNonNullable is false here, so an absent end_time/ttft must read back as null rather
        // than as the epoch or 0.0. The sentinel branch is covered by SpanJsonRowMapperTest.
        assertThat(actual).filteredOn(span -> spans.stream()
                .anyMatch(expected -> expected.id().equals(span.id()) && expected.endTime() == null))
                .isNotEmpty()
                .allSatisfy(span -> {
                    assertThat(span.endTime()).isNull();
                    assertThat(span.ttft()).isNull();
                });
    }
}

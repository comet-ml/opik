package com.comet.opik.infrastructure;

import com.comet.opik.api.FeedbackScore;
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
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.FeedbackScoreDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
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
    private ConnectionFactory clickHouseConnectionFactory;
    private FeedbackScoreDAO feedbackScoreDAO;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, FeedbackScoreDAO feedbackScoreDAO) {
        this.feedbackScoreDAO = feedbackScoreDAO;
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        clickHouseConnectionFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME).build();
    }

    private <T> T queryOne(String sql, Function<Row, T> mapper) {
        return Mono.usingWhen(
                clickHouseConnectionFactory.create(),
                connection -> Mono.from(connection.createStatement(sql).execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> mapper.apply(row)))),
                Connection::close)
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

        // The one assertion that cannot be made through the API: reads collapse duplicates, so a writer
        // emitting every row twice is invisible to them. Raw rows with no FINAL is the only view that
        // sees it.
        Long storedRows = queryOne(
                ("SELECT count() AS row_count FROM authored_feedback_scores WHERE workspace_id = '%s' "
                        + "AND entity_id = '%s'").formatted(WORKSPACE_ID, trace.id()),
                row -> row.get("row_count", Long.class));
        assertThat(storedRows).isEqualTo(scores.size());
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
        Long authored = queryOne(
                ("SELECT count() AS row_count FROM authored_feedback_scores WHERE workspace_id = '%s' "
                        + "AND entity_id = '%s' AND name = '%s'").formatted(WORKSPACE_ID, trace.id(), name),
                row -> row.get("row_count", Long.class));
        assertThat(authored).isZero();
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

        Long unauthored = queryOne(
                ("SELECT count() AS row_count FROM feedback_scores WHERE workspace_id = '%s' "
                        + "AND entity_id = '%s' AND name = '%s'").formatted(WORKSPACE_ID, trace.id(), name),
                row -> row.get("row_count", Long.class));
        assertThat(unauthored).isZero();
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
}

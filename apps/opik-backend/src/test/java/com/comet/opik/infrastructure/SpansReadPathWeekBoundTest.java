package com.comet.opik.infrastructure;

import com.comet.opik.api.Comment;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatchUpdate;
import com.comet.opik.api.SpanUpdate;
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
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
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
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Rows unchanged at the span-id reads OPIK-8361 gave a week bound, on this estate's legacy {@code spans}: its 32-bit
 * {@code id_at} stores a far-future id wrapped, so a bound naming only the honest week would lose the row, and an id
 * past the 2300 ceiling must fall back to the unbounded read. That the bounds prune on the weekly-partitioned
 * successor is {@link SpansReadPathPartitionPruningTest}'s job.
 *
 * <p>Everything goes through the public API ({@code POST /spans}, {@code GET} / {@code PATCH /spans/{id}},
 * {@code PATCH /spans/batch}, {@code POST /spans/{id}/comments}, {@code POST /datasets/items}, experiments). Spans are
 * ingested with the timestamp-window check off (the production default), so far-future ids are ones a client can
 * still send today.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpansReadPathWeekBoundTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    /** A second workspace, so a cross-workspace span reference has something to be rejected by. */
    private static final String OTHER_API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String OTHER_WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String OTHER_WORKSPACE_ID = UUID.randomUUID().toString();

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private static final String GET_SPANS_BY_IDS = "get_spans_by_ids";
    private static final String GET_EXPERIMENT_REFS_BY_SPAN_IDS = "get_experiment_refs_by_span_ids";

    /** Past 2106, the only era whose two {@code id_at} representations differ. */
    private static final Instant FAR_FUTURE_ID_AT = Instant.parse("2200-01-01T00:00:00Z");

    /**
     * {@link #FAR_FUTURE_ID_AT}'s week as the legacy 32-bit {@code id_at} stores it (wrapped mod 2^32 seconds) and as
     * {@code spans_local_v2} does. Computed once in ClickHouse 26.3 via
     * {@code toYYYYMMDD(toDate32(d) - toIntervalDay(toDayOfWeek(d, 1)))} over the honest and the wrapped instant.
     */
    private static final String FAR_FUTURE_LEGACY_WEEK = "20631119";
    private static final String FAR_FUTURE_HONEST_WEEK = "21991230";

    /** Where {@code id_at} saturates on the successor, so the bound cannot be derived and the read runs unbounded. */
    private static final Instant PAST_CEILING_ID_AT = LocalDate.of(2300, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

    /** One column of the latest {@code query_log} row for an op whose statement mentions the given span id. */
    private static final String LAST_QUERY_LOG_VALUE = """
            SELECT toString(<column>)
            FROM system.query_log
            WHERE log_comment LIKE concat(:op, ':%')
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :span_id, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
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

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, ClickHouseContainerUtils.DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        // The production default; it is what lets a far-future id reach the table via ingestion.
                        .customConfigs(List.of(new CustomConfig("uuidValidation.enabled", "false")))
                        .build());
    }

    private SpanResourceClient spanResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private TransactionTemplateAsync template;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        mockTargetWorkspace(wireMock.server(), OTHER_API_KEY, OTHER_WORKSPACE_NAME, OTHER_WORKSPACE_ID, USER);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        this.template = template;
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    @Test
    void getSpanByIdResolvesFarFutureAndPastCeilingSpans() {
        // Legacy id_at wraps a far-future id, so a bound naming only the honest week would make this a 404.
        var farFuture = createSpan(FAR_FUTURE_ID_AT, ID_GENERATOR.generateId());
        var pastCeiling = createSpan(PAST_CEILING_ID_AT, ID_GENERATOR.generateId());

        assertThat(spanResourceClient.getById(farFuture.id(), WORKSPACE_NAME, API_KEY).id()).isEqualTo(farFuture.id());
        assertThat(spanResourceClient.getById(pastCeiling.id(), WORKSPACE_NAME, API_KEY).id())
                .isEqualTo(pastCeiling.id());
        // Both weeks written out, not derived: the wrapped one is where this estate holds the row.
        assertThat(lastQueryLogValue("query", GET_SPANS_BY_IDS, farFuture.id()))
                .contains(FAR_FUTURE_LEGACY_WEEK)
                .contains(FAR_FUTURE_HONEST_WEEK);
    }

    @Test
    void createAfterPatchKeepsThePatchedValuesOfAFarFutureSpan() {
        // PATCH first takes the partial-insert path; the later POST merges over it through INSERT's old_span read,
        // which prefers the stored name, so a missed read would surface the POSTed name instead.
        var span = newSpan(FAR_FUTURE_ID_AT, ID_GENERATOR.generateId());
        spanResourceClient.updateSpan(span.id(), SpanUpdate.builder()
                .projectName(span.projectName())
                .traceId(span.traceId())
                .name("patched-name")
                .tags(Set.of("week-bound"))
                .build(), API_KEY, WORKSPACE_NAME);
        assertThat(spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY).tags())
                .containsExactly("week-bound");

        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);

        var actual = spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY);
        assertThat(actual.name()).isEqualTo("patched-name");
        assertThat(actual.startTime()).isEqualTo(span.startTime());
    }

    private Stream<Arguments> farFutureAndPastCeilingIdAts() {
        return Stream.of(
                arguments(Named.of("far future", FAR_FUTURE_ID_AT)),
                arguments(Named.of("past ceiling", PAST_CEILING_ID_AT)));
    }

    private Stream<Arguments> recentFarFutureAndPastCeilingIdAts() {
        return Stream.concat(Stream.of(arguments(Named.of("recent", Instant.now()))), farFutureAndPastCeilingIdAts());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("farFutureAndPastCeilingIdAts")
    void batchUpdateReachesTheSpan(Instant idAt) {
        // BULK_UPDATE rewrites the rows it reads, so a missed read leaves the tags unchanged.
        var span = createSpan(idAt, ID_GENERATOR.generateId());

        batchUpdateTags(span, Set.of(span.id()));

        var actual = spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY);
        assertThat(actual.tags()).as("tags of %s (id_at %s)", span.id(), idAt).containsExactly("week-bound");
        assertThat(actual.name()).as("name of %s (id_at %s)", span.id(), idAt).isEqualTo(span.name());
    }

    @Test
    void datasetItemCreationRejectsAFarFutureSpanFromAnotherWorkspace() {
        // The caller reduces with allMatch, which is true over an empty result, so a missed read would accept this.
        var farFuture = createSpan(FAR_FUTURE_ID_AT, ID_GENERATOR.generateId());

        try (var response = datasetResourceClient.callCreateDatasetItems(
                datasetItemReferencing(farFuture), OTHER_WORKSPACE_NAME, OTHER_API_KEY)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
        }
    }

    @Test
    void updateKeepsAFarFutureSpanIntact() {
        var farFuture = createSpan(FAR_FUTURE_ID_AT, ID_GENERATOR.generateId());

        updateTags(farFuture);

        var actual = spanResourceClient.getById(farFuture.id(), WORKSPACE_NAME, API_KEY);
        assertThat(actual.tags()).containsExactly("week-bound");
        assertThat(actual.name()).isEqualTo(farFuture.name());
    }

    @Test
    void commentOnAFarFutureSpanResolvesItsProject() {
        // A lost project lookup is a 404 here.
        var farFuture = createSpan(FAR_FUTURE_ID_AT, ID_GENERATOR.generateId());

        addComment(farFuture);
    }

    /**
     * A span comment publishes {@code CommentsCreated}, whose listener runs the experiment-refs read to find the
     * experiments to re-aggregate; its {@code result_rows} in {@code query_log} is the one place that read's answer
     * is visible from outside, and a lost row would make it 0.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("recentFarFutureAndPastCeilingIdAts")
    void experimentRefsResolveTheSpan(Instant idAt) {
        var traceId = ID_GENERATOR.generateId();
        var experiment = experimentResourceClient.createPartialExperiment()
                .status(ExperimentStatus.COMPLETED)
                .build();
        experimentResourceClient.create(experiment, API_KEY, WORKSPACE_NAME);
        var span = createSpan(idAt, traceId);
        // Same project as the span: the listener scopes the read to the commented span's project.
        experimentResourceClient.createExperimentItem(Set.of(factory.manufacturePojo(ExperimentItem.class)
                .toBuilder()
                .experimentId(experiment.id())
                .traceId(traceId)
                .projectName(span.projectName())
                .build()), API_KEY, WORKSPACE_NAME);

        addComment(span);

        assertThat(lastQueryLogValue("result_rows", GET_EXPERIMENT_REFS_BY_SPAN_IDS, span.id()))
                .as("experiment refs found for span %s (id_at %s)", span.id(), idAt)
                .isEqualTo("1");
    }

    private void addComment(Span span) {
        try (var response = spanResourceClient.callAddSpanComment(span.id(),
                Comment.builder().text("week-bound").build(), API_KEY, WORKSPACE_NAME)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
        }
    }

    private void batchUpdateTags(Span span, Set<UUID> ids) {
        spanResourceClient.batchUpdateSpans(SpanBatchUpdate.builder()
                .ids(ids)
                .update(SpanUpdate.builder().traceId(span.traceId()).tags(Set.of("week-bound")).build())
                .build(), API_KEY, WORKSPACE_NAME);
    }

    /** A one-item batch into a fresh dataset, referencing {@code span} - the shape that reaches the check. */
    private DatasetItemBatch datasetItemReferencing(Span span) {
        var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                .source(DatasetItemSource.SPAN)
                .spanId(span.id())
                .traceId(span.traceId())
                .experimentItems(null)
                .build();
        return DatasetItemBatch.builder()
                .datasetName("dataset-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32)))
                .items(List.of(item))
                .build();
    }

    private void updateTags(Span span) {
        spanResourceClient.updateSpan(span.id(), SpanUpdate.builder()
                .projectName(span.projectName())
                .traceId(span.traceId())
                .tags(Set.of("week-bound"))
                .build(), API_KEY, WORKSPACE_NAME);
    }

    /** A span through the real ingestion path whose {@code id} carries {@code idAt}; {@code startTime} stays today. */
    private Span createSpan(Instant idAt, UUID traceId) {
        var span = newSpan(idAt, traceId);
        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);
        return span;
    }

    private Span newSpan(Instant idAt, UUID traceId) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli()))
                .projectName("project-" + RandomStringUtils.secure().nextAlphanumeric(16))
                .traceId(traceId)
                .parentSpanId(null)
                .startTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .endTime(null)
                .feedbackScores(null)
                .build();
    }

    /** Polled: a query's {@code query_log} row is written asynchronously, flushed every 200 ms here. */
    private String lastQueryLogValue(String column, String queryName, UUID spanId) {
        var sql = LAST_QUERY_LOG_VALUE.replace("<column>", column);
        return Awaitility.await()
                .alias("query_log holds a %s statement mentioning id %s".formatted(queryName, spanId))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> template.nonTransaction(connection -> Mono.from(connection.createStatement(sql)
                        .bind("op", queryName)
                        .bind("span_id", spanId.toString())
                        .execute())
                        .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class)))))
                        .block(), Objects::nonNull);
    }
}

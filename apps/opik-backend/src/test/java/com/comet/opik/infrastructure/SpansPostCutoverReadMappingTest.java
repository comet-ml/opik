package com.comet.opik.infrastructure;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanSearchStreamRequest;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.error.ErrorMessage;
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
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.spans.SpanAssertions.assertPage;
import static com.comet.opik.api.resources.utils.spans.SpanAssertions.assertSpan;
import static com.comet.opik.domain.SpanService.PARENT_SPAN_IS_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Every span read path against the partitioned {@code spans} successor, with a root span and a span carrying token
 * counts in the fixture — the two row shapes the successor encodes differently from the table it replaces.
 *
 * <p>Migration 000115 narrows {@code parent_span_id} to {@code FixedString(36)}, where ClickHouse stores a root span's
 * absent parent as 36 NUL bytes rather than {@code ''}. NUL is not whitespace, so a {@code !isBlank()} guard let the
 * padded form reach {@code UUID.fromString}, which threw; the R2DBC driver logs that and <em>drops the row</em>, so the
 * API answered {@code 200} with a page whose {@code total} still counted the span its {@code content} had lost. The
 * same migration widens {@code usage} to {@code Map(String, Int64)}, so the counts come back as {@code Long} where the
 * API model declares {@code Integer}, and every span carrying usage failed its response. Both are read-mapping faults
 * that only exist on the successor, and both are invisible upstream of the response — which is why they are asserted on
 * what a read returned rather than on the mapper (OPIK-8551).
 *
 * <p>Read and write paths are parameterised separately because each is several statements over one mapper and one
 * column encoding, not a cross-product worth enumerating. The two {@code SpanDAO} reads with no endpoint of their own,
 * which online scoring and the enrichment services use, share that mapper, so they are covered by construction rather
 * than by reaching past the API.
 *
 * <p>Dedicated, non-reused containers: installing the successor under the {@code spans} name is a destructive
 * {@code EXCHANGE}, which must never touch a container shared with other suites.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpansPostCutoverReadMappingTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer);
    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    /** Installs the topology, which has to happen before the app boots against it, so it runs app-independently. */
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
                        // The successor's end_time/ttft are non-nullable, as the cutover flips this with it.
                        .customConfigs(List.of(
                                new CustomConfig("databaseAnalyticsDataModel.spanColumnsNonNullable", "true")))
                        .build());
    }

    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    private Stream<Arguments> readPaths() {
        return Stream.of(
                arguments("GET /v1/private/spans/{id}",
                        (Function<SpanFixture, List<Span>>) fixture -> fixture.spans().stream()
                                .map(span -> spanResourceClient.getById(span.id(), WORKSPACE_NAME, API_KEY))
                                .toList()),
                arguments("GET /v1/private/spans?trace_id=",
                        (Function<SpanFixture, List<Span>>) fixture -> spanResourceClient
                                .getByTraceIdAndProject(fixture.traceId(), fixture.projectName(), WORKSPACE_NAME,
                                        API_KEY)
                                .content()),
                arguments("POST /v1/private/spans/search",
                        (Function<SpanFixture, List<Span>>) fixture -> spanResourceClient.getStreamAndAssertContent(
                                API_KEY, WORKSPACE_NAME,
                                SpanSearchStreamRequest.builder().projectName(fixture.projectName()).build())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readPaths")
    void spansSurviveTheReadPath(String path, Function<SpanFixture, List<Span>> read) {
        var fixture = createSpans();
        var expectedSpans = fixture.spans();

        var actualSpans = read.apply(fixture);

        assertSpan(actualSpans, expectedSpans, USER);
    }

    /**
     * The shape a dropped row presents as, which no per-span assertion states: the count comes from its own statement,
     * so the page reported a total for a row the driver had already discarded.
     */
    @Test
    void pageTotalMatchesTheContentItReturns() {
        var fixture = createSpans();
        var expectedSpans = fixture.spans();

        var actualPage = spanResourceClient.getByTraceIdAndProject(fixture.traceId(), fixture.projectName(),
                WORKSPACE_NAME, API_KEY);

        assertPage(actualPage, 1, expectedSpans.size(), expectedSpans.size());
    }

    private Stream<Arguments> writePaths() {
        return Stream.of(
                arguments("POST /v1/private/spans",
                        (Consumer<List<Span>>) spans -> spans
                                .forEach(span -> spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME))),
                arguments("POST /v1/private/spans/batch",
                        (Consumer<List<Span>>) spans -> spanResourceClient.batchCreateSpans(spans, API_KEY,
                                WORKSPACE_NAME)));
    }

    /**
     * Each ingestion path binds the absent parent through its own statement, and all of them have to land the empty
     * value the successor's {@code FixedString(36)} column pads. The read back is what says they did.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("writePaths")
    void spansSurviveTheWritePath(String path, Consumer<List<Span>> write) {
        var fixture = newSpans();
        var expectedSpans = fixture.spans();

        write.accept(expectedSpans);

        var actualSpans = spanResourceClient
                .getByTraceIdAndProject(fixture.traceId(), fixture.projectName(), WORKSPACE_NAME, API_KEY)
                .content();

        assertSpan(actualSpans, expectedSpans, USER);
    }

    /**
     * The update reads the span it is updating and carries the stored counts forward whenever the request names a
     * model, provider or usage of its own, binding them back as an {@code Integer[]}. It is the one path where the
     * successor's {@code Int64} counts reach a bind rather than a response, so it breaks on a request that carries no
     * usage at all. The model is re-sent unchanged, so the whole span is still the assertion.
     */
    @Test
    void updateCarryingForwardTheStoredUsageSucceeds() {
        var fixture = createSpans();
        var expectedSpan = fixture.rootSpan();

        spanResourceClient.updateSpan(expectedSpan.id(), SpanUpdate.builder()
                .projectName(fixture.projectName())
                .traceId(fixture.traceId())
                .model(expectedSpan.model())
                .build(), API_KEY, WORKSPACE_NAME);

        var actualSpan = spanResourceClient.getById(expectedSpan.id(), WORKSPACE_NAME, API_KEY);

        assertSpan(List.of(actualSpan), List.of(expectedSpan), USER);
    }

    /**
     * The conflict guard compares parents in Java, on the span the update read back. A root span that does not read
     * back therefore costs more than its visibility: the update finds no existing span, skips the guard, and falls
     * through to the upsert-on-missing path, which rewrites the parent rather than refusing it. Both the 409 and the
     * span being left alone are assertions about that read.
     */
    @Test
    void updateChangingTheParentOfARootSpanConflicts() {
        var fixture = createSpans();
        var expectedSpan = fixture.rootSpan();

        try (var actualResponse = spanResourceClient.updateSpan(expectedSpan.id(), SpanUpdate.builder()
                .projectName(fixture.projectName())
                .traceId(fixture.traceId())
                .parentSpanId(fixture.childSpan().id())
                .build(), API_KEY, WORKSPACE_NAME, HttpStatus.SC_CONFLICT)) {

            assertThat(actualResponse.readEntity(ErrorMessage.class).errors()).contains(PARENT_SPAN_IS_MISMATCH);
        }

        var actualSpan = spanResourceClient.getById(expectedSpan.id(), WORKSPACE_NAME, API_KEY);

        assertSpan(List.of(actualSpan), List.of(expectedSpan), USER);
    }

    /** Batched, as the SDKs ingest: the write path is {@link #spansSurviveTheWritePath}'s subject, not every test's. */
    private SpanFixture createSpans() {
        var fixture = newSpans();
        spanResourceClient.batchCreateSpans(fixture.spans(), API_KEY, WORKSPACE_NAME);
        return fixture;
    }

    /** Podam fills everything else, token counts included, so the row is the shape production stores. */
    private SpanFixture newSpans() {
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var traceId = factory.manufacturePojo(UUID.class);
        var rootSpan = newSpan(projectName, traceId, null);

        return SpanFixture.builder()
                .traceId(traceId)
                .projectName(projectName)
                .rootSpan(rootSpan)
                .childSpan(newSpan(projectName, traceId, rootSpan.id()))
                .build();
    }

    private Span newSpan(String projectName, UUID traceId, UUID parentSpanId) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(traceId)
                .parentSpanId(parentSpanId)
                // Scores are written through their own endpoint, so a span create never persists them.
                .feedbackScores(null)
                .build();
    }

    /**
     * Puts the partitioned successor under the {@code spans} name the way the cutover's {@code EXCHANGE} step does: it
     * has no public API, and the app has to boot against it. Idempotent, because the estate will change — once the
     * cutover migration lands, {@code spans} <em>is</em> the successor and there is nothing to swap.
     *
     * <p>The postcondition is what keeps the suite honest: on the table the successor replaces, an absent parent is
     * already {@code ''} and the counts are already {@code Int32}, so every assertion here would pass without
     * exercising anything.
     */
    private void installPartitionedSuccessorUnderSpans() {
        if (!spansPartitionKey().contains("id_at")) {
            execute("EXCHANGE TABLES spans AND spans_local_v2 ON CLUSTER '{cluster}'");
        }

        assertThat(spansPartitionKey())
                .as("`spans` must be the partitioned successor for this suite to assert against its row encoding")
                .contains("id_at");
    }

    private String spansPartitionKey() {
        return template.nonTransaction(connection -> Mono
                .from(connection.createStatement("""
                        SELECT partition_key AS value
                        FROM system.tables
                        WHERE database = :database AND name = 'spans'
                        """).bind("database", DATABASE_NAME).execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("value", String.class)))))
                .block();
    }

    private void execute(String sql) {
        template.nonTransaction(connection -> Mono.from(connection.createStatement(sql).execute())).block();
    }

    /** A root span and one child of it, sharing a trace and a project so every read path sees exactly these two. */
    @Builder(toBuilder = true)
    private record SpanFixture(UUID traceId, String projectName, Span rootSpan, Span childSpan) {

        /** Both spans in the order the spans read paths return them, id descending. */
        List<Span> spans() {
            return Stream.of(rootSpan, childSpan)
                    .sorted(Comparator.comparing(Span::id).reversed())
                    .toList();
        }
    }
}

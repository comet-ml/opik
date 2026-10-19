package com.comet.opik.infrastructure;

import com.comet.opik.api.resources.utils.AuthTestUtils;
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
import com.comet.opik.api.resources.v1.events.SpanWeeksListener;
import com.comet.opik.domain.SpanService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trace-delete cascade with the span_weeks read bound on, for spans whose weeks are not indexed, as after a
 * partition import, which bypasses {@code span_weeks_mv} until a backfill. Neither registration path runs here: the
 * view is dropped and {@link SpanWeeksListener} is disabled, so no week is ever indexed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpanWeeksUnregisteredTraceDeleteTest {

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

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        installPartitionedSpans(databaseAnalyticsFactory);
        dropSpanWeeksView(databaseAnalyticsFactory);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
                                // Lets the far-future ids through ingestion, as in production.
                                new CustomConfig("uuidValidation.enabled", "false"),
                                // The successor's columns are non-nullable, as the cutover flips this with it.
                                new CustomConfig("databaseAnalyticsDataModel.spanColumnsNonNullable", "true"),
                                // Required for the bound to apply; the disabled listener keeps it from registering.
                                new CustomConfig("databaseAnalyticsDataModel.spanWeeksWriteEnabled", "true"),
                                new CustomConfig("databaseAnalyticsDataModel.spanWeeksReadEnabled", "true")))
                        .disableExtensions(List.of(SpanWeeksListener.class))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private SpanWeeksReadFixtures fixtures;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, SpanService spanService) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.fixtures = new SpanWeeksReadFixtures(API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER, factory,
                new ProjectResourceClient(clientSupport, baseUrl, factory),
                traceResourceClient, spanResourceClient,
                new DatasetResourceClient(clientSupport, baseUrl),
                new ExperimentResourceClient(clientSupport, baseUrl, factory), spanService);
    }

    /**
     * The trace-delete cascade is not bound by span_weeks, so it removes spans whose weeks are not indexed. Each span
     * is read back by its own id, whose read derives its week from the id rather than from the index.
     */
    @Test
    void traceDeleteRemovesSpansWhoseWeeksAreNotIndexed() {
        var trace = fixtures.createFarFutureTraceWithoutWaiting();

        traceResourceClient.deleteTrace(trace.trace().id(), WORKSPACE_NAME, API_KEY);

        // The cascade runs on the event bus after the trace delete returns.
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var actualStatuses = trace.spans().stream()
                    .map(span -> {
                        try (var response = spanResourceClient.callGetSpanIdApi(span.id(), WORKSPACE_NAME, API_KEY)) {
                            return response.getStatus();
                        }
                    })
                    .toList();
            assertThat(actualStatuses).containsOnly(HttpStatus.SC_NOT_FOUND);
        });
    }

    /** The partitioned successor has no public API before the cutover, so it is installed the way the cutover does. */
    private void installPartitionedSpans(DatabaseAnalyticsFactory databaseAnalyticsFactory) {
        TransactionTemplateAsync.create(databaseAnalyticsFactory.build())
                .nonTransaction(connection -> Mono.from(connection
                        .createStatement("EXCHANGE TABLES spans AND spans_local_v2 ON CLUSTER '{cluster}'")
                        .execute()))
                .block();
    }

    /** Without the view, spans written through the API are left unindexed, as a partition import leaves them. */
    private void dropSpanWeeksView(DatabaseAnalyticsFactory databaseAnalyticsFactory) {
        TransactionTemplateAsync.create(databaseAnalyticsFactory.build())
                .nonTransaction(connection -> Mono.from(connection
                        .createStatement("DROP VIEW span_weeks_mv ON CLUSTER '{cluster}' SYNC")
                        .execute()))
                .block();
    }
}

package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AssertionResultBatchItem;
import com.comet.opik.api.AssertionStatus;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AssertionResultsResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.EntityType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Flux;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Assertion Results Endpoint Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AssertionResultsResourceTest {

    private static final String API_KEY = randomUUID().toString();
    private static final String USER = randomUUID().toString();
    private static final String WORKSPACE_ID = randomUUID().toString();
    private static final String TEST_WORKSPACE = randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private AssertionResultsResourceClient assertionResultsResourceClient;
    private TransactionTemplateAsync clickHouseTemplate;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplateAsync clickHouseTemplate) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);

        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.assertionResultsResourceClient = new AssertionResultsResourceClient(client, baseURI);
        this.clickHouseTemplate = clickHouseTemplate;
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @ParameterizedTest(name = "stores {0} assertion results and returns 204 with rows persisted")
    @EnumSource(value = EntityType.class, names = {"TRACE", "SPAN"})
    @DisplayName("PUT /v1/private/assertion-results stores assertion results and returns 204")
    void storeAssertionsBatch_persistsRows(EntityType entityType) {
        UUID entityId = createEntity(entityType);

        var passedItem = AssertionResultBatchItem.builder()
                .entityId(entityId)
                .projectName(DEFAULT_PROJECT)
                .name("assertion-grounded")
                .status(AssertionStatus.PASSED)
                .reason("grounded in context")
                .source(ScoreSource.SDK)
                .build();
        var failedItem = AssertionResultBatchItem.builder()
                .entityId(entityId)
                .projectName(DEFAULT_PROJECT)
                .name("assertion-concise")
                .status(AssertionStatus.FAILED)
                .reason(null)
                .source(ScoreSource.ONLINE_SCORING)
                .build();

        assertionResultsResourceClient.store(entityType, List.of(passedItem, failedItem), API_KEY, TEST_WORKSPACE);

        List<AssertionResultRow> persisted = fetchAssertionResults(WORKSPACE_ID, entityType, entityId);
        assertThat(persisted).hasSize(2);

        AssertionResultRow grounded = persisted.stream()
                .filter(row -> "assertion-grounded".equals(row.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("assertion-grounded row not found"));
        assertThat(grounded.passed()).isEqualTo(AssertionStatus.PASSED.getValue());
        assertThat(grounded.reason()).isEqualTo("grounded in context");
        assertThat(grounded.source()).isEqualTo(ScoreSource.SDK.getValue());
        assertThat(grounded.entityType()).isEqualTo(entityType.getType());

        AssertionResultRow concise = persisted.stream()
                .filter(row -> "assertion-concise".equals(row.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("assertion-concise row not found"));
        assertThat(concise.passed()).isEqualTo(AssertionStatus.FAILED.getValue());
        assertThat(concise.reason()).isEmpty();
        assertThat(concise.source()).isEqualTo(ScoreSource.ONLINE_SCORING.getValue());
    }

    @Test
    @DisplayName("entity_type=THREAD is rejected with 400 (service-level guard)")
    void threadEntityTypeIsRejected() {
        var item = AssertionResultBatchItem.builder()
                .entityId(UUID.randomUUID())
                .projectName(DEFAULT_PROJECT)
                .name("assertion-x")
                .status(AssertionStatus.PASSED)
                .source(ScoreSource.SDK)
                .build();

        try (var response = assertionResultsResourceClient.callStore(EntityType.THREAD, List.of(item), API_KEY,
                TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("non-v7 UUID entity_id is rejected (IdGenerator.validateVersion)")
    void nonV7EntityIdIsRejected() {
        var item = AssertionResultBatchItem.builder()
                .entityId(UUID.randomUUID()) // default random UUID is v4, not v7
                .projectName(DEFAULT_PROJECT)
                .name("assertion-x")
                .status(AssertionStatus.PASSED)
                .source(ScoreSource.SDK)
                .build();

        try (var response = assertionResultsResourceClient.callStore(EntityType.TRACE, List.of(item), API_KEY,
                TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isBetween(400, 499);
        }
    }

    @Test
    @DisplayName("multi-project batch resolves projects independently and persists rows under each project")
    void multiProjectBatchResolvesIndependently() {
        var traceA = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName("assertion-multi-project-a-" + randomUUID())
                .feedbackScores(null)
                .usage(null)
                .build();
        var traceAId = traceResourceClient.createTrace(traceA, API_KEY, TEST_WORKSPACE);

        var traceB = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName("assertion-multi-project-b-" + randomUUID())
                .feedbackScores(null)
                .usage(null)
                .build();
        var traceBId = traceResourceClient.createTrace(traceB, API_KEY, TEST_WORKSPACE);

        var items = List.of(
                AssertionResultBatchItem.builder()
                        .entityId(traceAId)
                        .projectName(traceA.projectName())
                        .name("assertion-cross-project")
                        .status(AssertionStatus.PASSED)
                        .source(ScoreSource.SDK)
                        .build(),
                AssertionResultBatchItem.builder()
                        .entityId(traceBId)
                        .projectName(traceB.projectName())
                        .name("assertion-cross-project")
                        .status(AssertionStatus.FAILED)
                        .source(ScoreSource.SDK)
                        .build());

        assertionResultsResourceClient.store(EntityType.TRACE, items, API_KEY, TEST_WORKSPACE);

        List<AssertionResultRow> rowsA = fetchAssertionResults(WORKSPACE_ID, EntityType.TRACE, traceAId);
        assertThat(rowsA).hasSize(1);
        assertThat(rowsA.getFirst().passed()).isEqualTo(AssertionStatus.PASSED.getValue());

        List<AssertionResultRow> rowsB = fetchAssertionResults(WORKSPACE_ID, EntityType.TRACE, traceBId);
        assertThat(rowsB).hasSize(1);
        assertThat(rowsB.getFirst().passed()).isEqualTo(AssertionStatus.FAILED.getValue());

        assertThat(rowsA.getFirst().projectId()).isNotEqualTo(rowsB.getFirst().projectId());
    }

    private UUID createEntity(EntityType entityType) {
        return switch (entityType) {
            case TRACE -> {
                var trace = factory.manufacturePojo(Trace.class).toBuilder()
                        .id(null)
                        .projectName(DEFAULT_PROJECT)
                        .feedbackScores(null)
                        .usage(null)
                        .build();
                yield traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);
            }
            case SPAN -> {
                var span = factory.manufacturePojo(Span.class).toBuilder()
                        .id(null)
                        .projectName(DEFAULT_PROJECT)
                        .feedbackScores(null)
                        .usage(null)
                        .totalEstimatedCost(null)
                        .build();
                yield spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);
            }
            default -> throw new IllegalArgumentException("Unsupported entity type: " + entityType);
        };
    }

    private List<AssertionResultRow> fetchAssertionResults(String workspaceId, EntityType entityType, UUID entityId) {
        String query = """
                SELECT entity_type, entity_id, project_id, name, passed, reason, source
                FROM assertion_results FINAL
                WHERE workspace_id = :workspace_id
                  AND entity_type = :entity_type
                  AND entity_id = :entity_id
                ORDER BY name
                """;

        return clickHouseTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(query)
                    .bind("workspace_id", workspaceId)
                    .bind("entity_type", entityType.getType())
                    .bind("entity_id", entityId.toString());
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> new AssertionResultRow(
                            row.get("entity_type", String.class),
                            row.get("entity_id", String.class),
                            row.get("project_id", String.class),
                            row.get("name", String.class),
                            row.get("passed", String.class),
                            row.get("reason", String.class),
                            row.get("source", String.class))))
                    .collectList();
        }).block();
    }

    private record AssertionResultRow(
            String entityType,
            String entityId,
            String projectId,
            String name,
            String passed,
            String reason,
            String source) {
    }
}

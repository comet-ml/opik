package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.GuardrailBatchItem;
import com.comet.opik.api.GuardrailsValidation;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.GuardrailsResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.GuardrailsMapper;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@DisplayName("Guardrails Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
public class GuardrailsResourceTest {
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
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

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private TraceResourceClient traceResourceClient;
    private GuardrailsResourceClient guardrailsResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        var baseURI = "http://localhost:%d".formatted(client.getPort());

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.guardrailsResourceClient = new GuardrailsResourceClient(client, baseURI);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("test create guardrails, get trace by id")
    void testCreateGuardrails_getTraceById() {
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, workspaceId);
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName(DEFAULT_PROJECT)
                .usage(null)
                .feedbackScores(null)
                .build();
        var traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

        var guardrails = createGuardrails(traceId, trace.projectName());

        guardrailsResourceClient.addBatch(guardrails, API_KEY, TEST_WORKSPACE);
        Trace actual = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);

        assertThat(actual).isNotNull();
        assertThat(actual.guardrailsValidations())
                .withFailMessage("guardrails are expected to be grouped")
                .hasSize(1);
        assertGuardrailValidations(
                GuardrailsMapper.INSTANCE.mapToValidations(guardrails),
                actual.guardrailsValidations());
    }

    @Test
    @DisplayName("test create guardrails, find traces")
    void testCreateGuardrails_findTraces() {
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, workspaceId);
        var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                .map(trace -> trace.toBuilder()
                        .projectName(DEFAULT_PROJECT)
                        .usage(null)
                        .feedbackScores(null)
                        .build())
                .toList();

        traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);

        var guardrailsByTraceId = traces.stream()
                .collect(Collectors.toMap(Trace::id, trace -> Stream.concat(
                        // mimic two separate guardrails validation groups
                        createGuardrails(trace.id(), trace.projectName()).stream(),
                        createGuardrails(trace.id(), trace.projectName()).stream()).toList()));

        guardrailsByTraceId.values()
                .forEach(guardrail -> guardrailsResourceClient.addBatch(guardrail, API_KEY, TEST_WORKSPACE));
        Trace.TracePage actual = traceResourceClient.getTraces(DEFAULT_PROJECT, null, API_KEY, TEST_WORKSPACE,
                null, null, traces.size(), Map.of());

        assertThat(actual).isNotNull();
        assertThat(actual.content()).hasSize(traces.size());
        actual.content().forEach(actualTrace -> assertGuardrailValidations(
                GuardrailsMapper.INSTANCE.mapToValidations(guardrailsByTraceId.get(actualTrace.id())),
                actualTrace.guardrailsValidations()));
    }

    private List<GuardrailBatchItem> createGuardrails(UUID traceId, String projectName) {
        var spanId = UUID.randomUUID();
        return PodamFactoryUtils.manufacturePojoList(factory, GuardrailBatchItem.class).stream()
                .map(guardrail -> guardrail.toBuilder()
                        .entityId(traceId)
                        .secondaryId(spanId)
                        .projectName(projectName)
                        .build())
                // deduplicate by guardrail name
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                GuardrailBatchItem::name,
                                Function.identity(),
                                (existing, replacement) -> existing),
                        map -> new ArrayList<>(map.values())));
    }

    private void assertGuardrailValidations(List<GuardrailsValidation> expected, List<GuardrailsValidation> actual) {
        assertThat(actual).hasSize(expected.size());
        expected.forEach(expectedValidation -> {
            actual.stream()
                    .filter(validation -> validation.spanId().equals(expectedValidation.spanId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected validation not found: " +
                            expectedValidation.spanId()));
            assertThat(expectedValidation.checks()).containsExactlyInAnyOrder(expectedValidation.checks()
                    .toArray(new GuardrailsValidation.Check[0]));
        });
    }
}

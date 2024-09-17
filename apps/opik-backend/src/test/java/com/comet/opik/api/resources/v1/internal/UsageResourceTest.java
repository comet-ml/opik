package com.comet.opik.api.resources.v1.internal;

import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceCountResponse;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.db.TransactionTemplate;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(parallel = true)
@DisplayName("Usage Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UsageResourceTest {
    public static final String USAGE_RESOURCE_URL_TEMPLATE = "%s/v1/internal/usage";
    public static final String TRACE_RESOURCE_URL_TEMPLATE = "%s/v1/private/traces";

    private static final String USER = UUID.randomUUID().toString();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        MYSQL_CONTAINER.start();
        CLICK_HOUSE_CONTAINER.start();
        REDIS.start();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private TransactionTemplate template;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi, TransactionTemplate template) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;
        this.template = template;

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Nested
    @DisplayName("Opik usage:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Usage {

        private final String okApikey = UUID.randomUUID().toString();

        @Test
        @DisplayName("Get traces count on previous day for all workspaces, no Auth")
        void tracesCountForWorkspace() {
            // Setup mock workspace with traces
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            int tracesCount = setupTracesForWorkspace(workspaceName, workspaceId, okApikey);

            // Change created_at to the previous day in order to capture those traces in count query, since for Stripe we need to count it daily for yesterday
            String updateCreatedAt = "ALTER TABLE traces UPDATE created_at = subtractDays(created_at, 1) WHERE workspace_id=:workspace_id;";
            template.nonTransaction(connection -> {
                var statement = connection.createStatement(updateCreatedAt)
                        .bind("workspace_id", workspaceId);
                return Mono.from(statement.execute());
            }).block();

            // Setup second workspace with traces, but leave created_at date set to today, so traces do not end up in the pool
            var workspaceNameForToday = UUID.randomUUID().toString();
            var workspaceIdForToday = UUID.randomUUID().toString();
            setupTracesForWorkspace(workspaceNameForToday, workspaceIdForToday, okApikey);

            try (var actualResponse = client.target(USAGE_RESOURCE_URL_TEMPLATE.formatted(baseURI))
                    .path("/workspace-trace-counts")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, okApikey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                assertThat(actualResponse.hasEntity()).isTrue();

                var response = actualResponse.readEntity(TraceCountResponse.class);
                assertThat(response.workspacesTracesCount().size()).isEqualTo(1);
                assertThat(response.workspacesTracesCount().get(0))
                        .isEqualTo(new TraceCountResponse.WorkspaceTraceCount(workspaceId, tracesCount));
            }
        }
    }

    private int setupTracesForWorkspace(String workspaceName, String workspaceId, String okApikey) {
        mockTargetWorkspace(okApikey, workspaceName, workspaceId);

        var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                .stream()
                .map(t -> t.toBuilder()
                        .projectId(null)
                        .projectName(DEFAULT_PROJECT)
                        .feedbackScores(null)
                        .build())
                .toList();

        traces.forEach(trace -> createTrace(trace, okApikey, workspaceName));

        return traces.size();
    }

    private UUID createTrace(Trace trace, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(TRACE_RESOURCE_URL_TEMPLATE.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(trace))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();

            var actualId = TestUtils.getIdFromLocation(actualResponse.getLocation());

            if (trace.id() != null) {
                assertThat(actualId).isEqualTo(trace.id());
            }
            return actualId;
        }
    }
}

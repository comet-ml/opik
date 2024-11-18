package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.Project;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthModuleCache2E2Test {

    public static final String URL_TEMPLATE = "%s/v1/private/projects";

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    private static final WireMockUtils.WireMockRuntime wireMock;

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final int CACHE_TTL_IN_SECONDS = 1;

    public static final String AUTH_PATH = "/opik/auth";

    public static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    static {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE,
                ClickHouseContainerUtils.DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(MYSQL.getJdbcUrl(),
                databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI(), CACHE_TTL_IN_SECONDS);
    }

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void beforeAll(ClientSupport client, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runDbMigration(connection, MigrationUtils.CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
    }

    @Test
    void testAuthCache__whenApiKeyAndWorkspaceAreCached__thenUseTheCacheUntilTTLExpire() {

        try (Response response = client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .post(Entity.json(Project.builder().name(UUID.randomUUID().toString()).build()))) {

            assertThat(response.getStatus()).isEqualTo(201);
        }

        try (var response = callEndpoint()) {

            assertThat(response.getStatus()).isEqualTo(200);

            wireMock.server().verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo(AUTH_PATH)));
        }

        try (var response = callEndpoint()) {

            assertThat(response.getStatus()).isEqualTo(200);

            wireMock.server().verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo(AUTH_PATH)));
        }

        Mono.delay(Duration.ofMillis((CACHE_TTL_IN_SECONDS * 1000) + 100)).block();

        try (var response = callEndpoint()) {

            assertThat(response.getStatus()).isEqualTo(200);

            wireMock.server().verify(2, WireMock.postRequestedFor(WireMock.urlEqualTo(AUTH_PATH)));
        }
    }

    private Response callEndpoint() {
        return client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .get();
    }
}

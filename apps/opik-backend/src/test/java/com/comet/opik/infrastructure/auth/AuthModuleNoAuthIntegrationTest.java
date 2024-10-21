package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.Project;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.sql.SQLException;
import java.util.UUID;

import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(parallel = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthModuleNoAuthIntegrationTest {

    public static final String URL_TEMPLATE = "%s/v1/private/projects";

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    static {
        MYSQL.start();
        CLICKHOUSE.start();
        REDIS.start();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE,
                ClickHouseContainerUtils.DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(MYSQL.getJdbcUrl(),
                databaseAnalyticsFactory, null, REDIS.getRedisURI());
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
    }

    @Test
    void testAuth__noAuthIsConfiguredAndDefaultWorkspaceIsINTheHeader__thenAcceptRequest() {

        var response = client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .header(WORKSPACE_HEADER, DEFAULT_WORKSPACE_NAME)
                .get();

        assertThat(response.getStatus()).isEqualTo(200);

        var projectPage = response.readEntity(Project.ProjectPage.class);
        assertThat(projectPage.content()).isNotEmpty();
        assertThat(projectPage.content()).allMatch(project -> DEFAULT_PROJECT.equals(project.name()));
    }

    @Test
    void testAuth__noAuthIsConfiguredAndDefaultWorkspaceIsUsed__thenAcceptRequest() {

        var response = client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .header(WORKSPACE_HEADER, DEFAULT_WORKSPACE_NAME)
                .get();

        assertThat(response.getStatus()).isEqualTo(200);

        var projectPage = response.readEntity(Project.ProjectPage.class);
        assertThat(projectPage.content()).isNotEmpty();
        assertThat(projectPage.content()).allMatch(project -> DEFAULT_PROJECT.equals(project.name()));
    }

    @Test
    void testAuth__noAuthIsConfiguredAndNonValidWorkspaceIsUsed__thenFail() {
        var response = client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .header(WORKSPACE_HEADER, UUID.randomUUID().toString())
                .get();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.readEntity(ErrorMessage.class))
                .isEqualTo(new ErrorMessage(404, "Workspace not found"));
    }

    @Test
    void testAuth__noAuthIsConfiguredAndNoWorkspaceIsProvidedUseDefault__thenAcceptRequest() {
        var response = client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .get();

        assertThat(response.getStatus()).isEqualTo(200);

        var projectPage = response.readEntity(Project.ProjectPage.class);

        assertThat(projectPage.content()).isNotEmpty();
        assertThat(projectPage.content()).allMatch(project -> DEFAULT_PROJECT.equals(project.name()));
    }

}

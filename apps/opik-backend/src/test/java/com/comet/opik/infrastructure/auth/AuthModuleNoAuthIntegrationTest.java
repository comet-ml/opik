package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.Project;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.apache.hc.core5.http.HttpStatus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.sql.SQLException;
import java.util.UUID;

import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthModuleNoAuthIntegrationTest {

    public static final String URL_TEMPLATE = "%s/v1/private/projects";
    public static final String FEEDBACK_DEFINITION_TEMPLATE = "%s/v1/private/feedback-definitions";

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    static {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS).join();

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
            MigrationUtils.runClickhouseDbMigration(connection, MigrationUtils.CLICKHOUSE_CHANGELOG_FILE,
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

    @Test
    @DisplayName("when getting default feedback definition, then return default feedback")
    void getById__whenGettingDefaultFeedbackDefinition__thenReturnDefaultFeedback() {

        var id = UUID.fromString("0190babc-62a0-71d2-832a-0feffa4676eb");

        var actualResponse = client.target(FEEDBACK_DEFINITION_TEMPLATE.formatted(baseURI))
                .path(id.toString())
                .request()
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
        var actualEntity = actualResponse.readEntity(FeedbackDefinition.CategoricalFeedbackDefinition.class);

        assertThat(actualEntity.getName()).isEqualTo("User feedback");
        assertThat(actualEntity.getDetails().getCategories())
                .hasEntrySatisfying("\uD83D\uDC4D", value -> assertThat(value).isEqualTo(1.0));
        assertThat(actualEntity.getDetails().getCategories())
                .hasEntrySatisfying("\uD83D\uDC4E", value -> assertThat(value).isEqualTo(0.0));
    }

}

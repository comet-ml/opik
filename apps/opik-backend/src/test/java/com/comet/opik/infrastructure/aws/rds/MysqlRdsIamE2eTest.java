package com.comet.opik.infrastructure.aws.rds;

import com.comet.opik.api.Project;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.sql.SQLException;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@Disabled
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MysqlRdsIamE2eTest {

    private static final String URL_TEMPLATE = "%s/v1/private/projects";

    /// See PR: https://github.com/comet-ml/opik/pull/306
    // RDS DB endpoint, port, and database name
    // JDBC URL format: jdbc:aws-wrapper:mysql://<rds-endpoint>:<port>/<db-name>?createDatabaseIfNotExist=true&rewriteBatchedStatements=true
    // DB endpoint: <rds-instance>.<region>.rds.amazonaws.com
    // AWS Driver only supports rds.amazonaws.com endpoints, not custom endpoints
    private static final String MYSQL_TEMPLATE_URL = "jdbc:aws-wrapper:mysql://%s";
    private static final String DB_PLUGINS = "iam";
    private static final String DB_USER = "db_user";
    private static final String AWS_JDBC_DRIVER = "software.amazon.jdbc.Driver";

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final String TEST_WORKSPACE = "default";

    static {
        Startables.deepStart(CLICKHOUSE, REDIS).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE,
                ClickHouseContainerUtils.DATABASE_NAME);

        String rdsEndpoint = "<rds-instance>.<aws-region>.rds.amazonaws.com:3306/opik?createDatabaseIfNotExist=true&rewriteBatchedStatements=true";

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(String.format(MYSQL_TEMPLATE_URL, rdsEndpoint))
                        .awsJdbcDriverPlugins(DB_PLUGINS)
                        .jdbcUserName(DB_USER)
                        .jdbcDriverClass(AWS_JDBC_DRIVER)
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .build());
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
    void testAwsRds__whenRdsIamDbAuthenticationIsEnabled__shouldAcceptRequest() {

        try (Response response = client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .post(Entity.json(Project.builder().name(UUID.randomUUID().toString()).build()))) {

            assertThat(response.getStatus()).isEqualTo(201);
        }
    }

}

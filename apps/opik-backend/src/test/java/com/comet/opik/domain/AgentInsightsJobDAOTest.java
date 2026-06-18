package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.AgentInsightsJob.Status;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AgentInsightsJobDAOTest {

    // Pure MySQL/JDBI DAO — only Redis + MySQL are needed (no ClickHouse/MinIO analytics stack).
    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL).join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(MYSQL);
        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), null, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private TransactionTemplate template;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplate mySqlTemplate) {
        ClientSupportUtils.config(client);
        this.template = mySqlTemplate;
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void create(UUID id, String workspaceId, UUID projectId, String userName) {
        template.inTransaction(WRITE, handle -> {
            handle.attach(AgentInsightsJobDAO.class).create(id, workspaceId, projectId, userName);
            return null;
        });
    }

    private Optional<AgentInsightsJob> find(String workspaceId, UUID projectId) {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(AgentInsightsJobDAO.class).findByProject(workspaceId, projectId));
    }

    @Test
    @DisplayName("create inserts a fully-populated row; status enabled")
    void create__insertsRow() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var id = UUID.randomUUID();
        var userName = "user-" + UUID.randomUUID();

        create(id, workspaceId, projectId, userName);

        var job = find(workspaceId, projectId).orElseThrow();
        assertThat(job.id()).isEqualTo(id);
        assertThat(job.projectId()).isEqualTo(projectId);
        assertThat(job.status()).isEqualTo(Status.ENABLED);
        assertThat(job.createdBy()).isEqualTo(userName);
        assertThat(job.lastUpdatedBy()).isEqualTo(userName);
        assertThat(job.createdAt()).isNotNull();
        assertThat(job.lastUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("create is insert-only: a second create on (workspace, project) violates the unique key")
    void create__duplicateThrows() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();

        create(UUID.randomUUID(), workspaceId, projectId, "user-" + UUID.randomUUID());

        assertThatThrownBy(() -> create(UUID.randomUUID(), workspaceId, projectId, "user-" + UUID.randomUUID()))
                .isInstanceOf(UnableToExecuteStatementException.class);
    }

    @Test
    @DisplayName("updateStatus flips status; returns 0 when no row matches")
    void updateStatus__flipsStatus_andZeroWhenAbsent() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();

        int missing = template.inTransaction(WRITE, handle -> handle.attach(AgentInsightsJobDAO.class)
                .updateStatus(workspaceId, projectId, Status.DISABLED.getValue(), "user-" + UUID.randomUUID()));
        assertThat(missing).isZero();

        create(UUID.randomUUID(), workspaceId, projectId, "user-" + UUID.randomUUID());
        template.inTransaction(WRITE, handle -> handle.attach(AgentInsightsJobDAO.class)
                .updateStatus(workspaceId, projectId, Status.DISABLED.getValue(), "user-" + UUID.randomUUID()));
        assertThat(find(workspaceId, projectId).orElseThrow().status()).isEqualTo(Status.DISABLED);

        // ... and back to enabled (updateStatus is general, not disable-only).
        template.inTransaction(WRITE, handle -> handle.attach(AgentInsightsJobDAO.class)
                .updateStatus(workspaceId, projectId, Status.ENABLED.getValue(), "user-" + UUID.randomUUID()));
        assertThat(find(workspaceId, projectId).orElseThrow().status()).isEqualTo(Status.ENABLED);
    }

    @Test
    @DisplayName("findByProject is scoped by workspace")
    void findByProject__workspaceScoped() {
        var workspaceId = UUID.randomUUID().toString();
        var otherWorkspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();

        create(UUID.randomUUID(), workspaceId, projectId, "user-" + UUID.randomUUID());

        assertThat(find(workspaceId, projectId)).isPresent();
        assertThat(find(otherWorkspaceId, projectId)).isEmpty();
    }
}

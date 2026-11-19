package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TopicsConfig;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("topics workspace enablement")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicWorkspaceServiceTest {

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysql, clickHouse, zookeeper).join();
        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickHouse);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .redisUrl(redis.getRedisURI())
                .build());
    }

    private TopicWorkspaceService service;
    private TopicsConfig config;
    private TransactionTemplate template;

    @BeforeAll
    void beforeAll(@Jit TopicWorkspaceService service, OpikConfiguration configuration, TransactionTemplate template) {
        this.service = service;
        this.config = configuration.getTopics();
        this.template = template;
    }

    @Test
    @DisplayName("on: the listed workspaces that are enabled in ai_label_workspaces")
    void listedAndEnabledForAiLabels() {
        var listedAndEnabled = UUID.randomUUID().toString();
        var listedTwiceAndEnabled = UUID.randomUUID().toString();
        var notListed = UUID.randomUUID().toString();
        var notInTheTable = UUID.randomUUID().toString();
        var disabledInTheTable = UUID.randomUUID().toString();
        upsert(listedAndEnabled, true);
        upsert(listedTwiceAndEnabled, true);
        upsert(notListed, true);
        upsert(disabledInTheTable, false);
        config.setEnabled(true);
        config.setEnabledWorkspaces(" %s, %s,, %s , %s,%s ".formatted(listedTwiceAndEnabled, notInTheTable,
                listedAndEnabled, disabledInTheTable, listedTwiceAndEnabled));

        assertThat(service.enabledWorkspaceIds()).containsExactlyInAnyOrder(listedAndEnabled, listedTwiceAndEnabled);
        assertThat(service.isEnabled(listedAndEnabled)).isTrue();
        assertThat(service.isEnabled(notListed)).isFalse();
        assertThat(service.isEnabled(notInTheTable)).isFalse();
        assertThat(service.isEnabled(disabledInTheTable)).isFalse();
    }

    @Test
    @DisplayName("off: no workspace, though listed and enabled for ai-labels")
    void toggleOff() {
        var workspaceId = UUID.randomUUID().toString();
        upsert(workspaceId, true);
        config.setEnabled(false);
        config.setEnabledWorkspaces(workspaceId);

        assertThat(service.enabledWorkspaceIds()).isEmpty();
        assertThat(service.isEnabled(workspaceId)).isFalse();
    }

    @Test
    @DisplayName("on with an empty list: no workspace")
    void emptyList() {
        var workspaceId = UUID.randomUUID().toString();
        upsert(workspaceId, true);
        config.setEnabled(true);
        config.setEnabledWorkspaces(" , ,");

        assertThat(service.enabledWorkspaceIds()).isEmpty();
        assertThat(service.isEnabled(workspaceId)).isFalse();
    }

    private void upsert(String workspaceId, boolean enabled) {
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, enabled, "operator");
            return null;
        });
    }
}

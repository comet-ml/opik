package com.comet.opik.domain.ailabels;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.v1.jobs.AiLabelsJob;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jobs.GuiceJobManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.ArrayList;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/** The ai-labels toggle (spec 07): off by default, and on only with the cut-over traces. */
@DisplayName("ai-labels toggle")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AiLabelsToggleTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);

    // The default test config: ai-labels off, no inference url or credential.
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

    @Test
    @DisplayName("off, the app boots without an inference url or credential and the tick job isn't scheduled")
    void disabledJobIsNotScheduled(OpikConfiguration config, GuiceJobManager jobManager) throws SchedulerException {
        var scheduler = jobManager.getScheduler();
        var jobClasses = new ArrayList<Class<?>>();
        for (var key : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
            jobClasses.add(scheduler.getJobDetail(key).getJobClass());
        }

        assertThat(config.getAiLabels().isEnabled()).isFalse();
        assertThat(config.getAiLabels().getInference().getUrl()).isNull();
        assertThat(jobClasses).isNotEmpty().doesNotContain(AiLabelsJob.class);
    }

    @Test
    @DisplayName("on, the config is invalid unless traces are cut over")
    void enabledRequiresTraceColumnsNonNullable() {
        var config = new OpikConfiguration();
        config.getAiLabels().setEnabled(true);

        assertThat(VALIDATOR.validateProperty(config, "aiLabelsDataModelSupported")).singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .contains("databaseAnalyticsDataModel.traceColumnsNonNullable"));

        config.setDatabaseAnalyticsDataModel(config.getDatabaseAnalyticsDataModel().toBuilder()
                .traceColumnsNonNullable(true)
                .build());

        assertThat(VALIDATOR.validateProperty(config, "aiLabelsDataModelSupported")).isEmpty();
    }

    @Test
    @DisplayName("on, the inference url and credential are required; off, nothing is")
    void enabledRequiresInference() {
        var config = new AiLabelsConfig();

        assertThat(VALIDATOR.validate(config)).isEmpty();

        config.setEnabled(true);

        assertThat(VALIDATOR.validate(config)).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("inferenceConfigured");

        config.getInference().setUrl("https://inference.example.com");
        config.getInference().setCredential("credential");

        assertThat(VALIDATOR.validate(config)).isEmpty();
    }
}

package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
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
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Stream Consumer Reaper Job Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class StreamConsumerReaperJobTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICKHOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .build());
    }

    // Stream names of every concrete BaseRedisSubscriber wired into the real application (config-test.yml).
    // Auto-discovery must find all of them so their orphaned consumers get reaped (OPIK-6982).
    private static final String[] EXPECTED_SUBSCRIBER_STREAMS = {
            // online-scoring (the six groups from OPIK-6982)
            "stream_scoring_llm_as_judge",
            "stream_scoring_user_defined_metric_python",
            "stream_scoring_trace_thread_llm_as_judge",
            "stream_scoring_trace_thread_user_defined_metric_python",
            "stream_scoring_span_llm_as_judge",
            "stream_scoring_span_user_defined_metric_python",
            // the remaining stream subscribers
            "webhook-events", // WebhookSubscriber
            "experiment_item_processing_stream", // ExperimentItemProcessingSubscriber
            "experiment_denormalization_stream", // ExperimentAggregatesSubscriber
            "trace_thread_closing_stream", // ClosingTraceThreadSubscriber
            "dataset-export-test", // DatasetExportJobSubscriber
    };

    private StreamConsumerReaperJob reaperJob;

    @BeforeAll
    void setUpAll(ClientSupport client, StreamConsumerReaperJob reaperJob) {
        this.reaperJob = reaperJob;
    }

    @Test
    @DisplayName("Should discover every registered subscriber's stream from the Guice context")
    void shouldDiscoverAllRegisteredSubscriberStreams() {
        var discovered = reaperJob.discoverStreamNames();

        assertThat(discovered)
                .doesNotContainNull()
                .doesNotHaveDuplicates()
                .contains(EXPECTED_SUBSCRIBER_STREAMS);
    }
}

package com.comet.opik.infrastructure.bi;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.sql.SQLException;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class ApplicationStartupListenerTest {

    private static final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer(false);

    @Nested
    @Order(1)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FirstStartupTest {

        private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
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

            try {
                MigrationUtils.runDbMigration(MYSQL_CONTAINER.createConnection(""), MySQLContainerUtils.migrationParameters());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
                MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                        ClickHouseContainerUtils.migrationParameters());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/v1/notify/event"))
                            .willReturn(WireMock.aResponse().withStatus(200).withBody("{ \"result\":\"OK\" }"))
            );

            app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                    AppContextConfig.builder()
                            .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .redisUrl(REDIS.getRedisURI())
                            .usageReportEnabled(true)
                            .usageReportUrl("%s/v1/notify/event".formatted(wireMock.runtimeInfo().getHttpBaseUrl()))
                            .build());
        }

        @Test
        void shouldNotifyEvent(UsageReportDAO reportDAO) {
            wireMock.server().verify(postRequestedFor(urlPathEqualTo("/v1/notify/event")));

            Assertions.assertTrue(reportDAO.isEventReported(GuiceyLifecycle.ApplicationStarted.name()));
            Assertions.assertTrue(reportDAO.getAnonymousId().isPresent());
        }
    }

    @Nested
    @Order(2)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SecondStartupTest {

        private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
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

            try {
                MigrationUtils.runDbMigration(MYSQL_CONTAINER.createConnection(""), MySQLContainerUtils.migrationParameters());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
                MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                        ClickHouseContainerUtils.migrationParameters());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            wireMock.server().resetAll();

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/v1/notify/event"))
                            .willReturn(WireMock.aResponse().withStatus(200).withBody("{ \"result\":\"OK\" }"))
            );

            app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                    AppContextConfig.builder()
                            .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .redisUrl(REDIS.getRedisURI())
                            .usageReportEnabled(true)
                            .usageReportUrl("%s/v1/notify/event".formatted(wireMock.runtimeInfo().getHttpBaseUrl()))
                            .build());
        }

        @Test
        void shouldNotNotifyEvent(UsageReportDAO reportDAO) {
            wireMock.server().verify(exactly(0), postRequestedFor(urlPathEqualTo("/v1/notify/event")));

            Assertions.assertTrue(reportDAO.isEventReported(GuiceyLifecycle.ApplicationStarted.name()));
            Assertions.assertTrue(reportDAO.getAnonymousId().isPresent());
        }
    }

    @AfterAll
    static void tearDown() {
        MYSQL_CONTAINER.stop();
    }
}
package com.comet.opik.infrastructure.bi;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.sql.SQLException;
import java.util.Random;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class OpikGuiceyLifecycleEventListenerTest {

    /**
    In this test, we are testing the OpikGuiceyLifecycleEventListener class
    which is a GuiceyLifecycleListener that listens for the GuiceyLifecycle.ApplicationStarted event
    and then calls the reportInstallation method of the InstallationReportService class.

    The two test classes have each its own TestDropwizardAppExtension instance.
    That way, we can simulate two different application startups.
    The order of the tests is defined by the @TestClassOrder annotation.
    the reason this is needed is to make sure that only the first test will notify the event.
    */

    private final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer(false);
    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer();

    private static final Random RANDOM = new Random();
    private static final String VERSION = "%s.%s.%s".formatted(RANDOM.nextInt(10), RANDOM.nextInt(),
            RANDOM.nextInt(99));

    private static final String SUCCESS_RESPONSE = "{\"message\":\"Event added successfully\",\"success\":\"true\"}";

    @Nested
    @Order(1)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class FirstStartupTest {

        @RegisterApp
        private final TestDropwizardAppExtension APP;

        private final WireMockUtils.WireMockRuntime wireMock;

        {
            Startables.deepStart(MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, REDIS).join();

            wireMock = WireMockUtils.startWireMock();

            var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                    CLICK_HOUSE_CONTAINER, DATABASE_NAME);

            try {
                MigrationUtils.runDbMigration(MYSQL_CONTAINER.createConnection(""),
                        MySQLContainerUtils.migrationParameters());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
                MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                        ClickHouseContainerUtils.migrationParameters());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/v1/notify/event"))
                            .withRequestBody(matchingJsonPath("$.anonymous_id", matching(
                                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")))
                            .withRequestBody(matchingJsonPath("$.event_type",
                                    matching(InstallationReportService.NOTIFICATION_EVENT_TYPE)))
                            .withRequestBody(matchingJsonPath("$.event_properties.opik_app_version", matching(VERSION)))
                            .willReturn(WireMock.okJson(SUCCESS_RESPONSE)));

            APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                    AppContextConfig.builder()
                            .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .redisUrl(REDIS.getRedisURI())
                            .usageReportEnabled(true)
                            .usageReportUrl("%s/v1/notify/event".formatted(wireMock.runtimeInfo().getHttpBaseUrl()))
                            .metadataVersion(VERSION)
                            .build());
        }

        @Test
        void shouldNotifyEvent(UsageReportService usageReportService) {
            wireMock.server().verify(postRequestedFor(urlPathEqualTo("/v1/notify/event")));

            Assertions.assertTrue(usageReportService.isEventReported(GuiceyLifecycle.ApplicationStarted.name()));
            Assertions.assertTrue(usageReportService.getAnonymousId().isPresent());
        }
    }

    @Nested
    @Order(2)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(DropwizardAppExtensionProvider.class)
    class SecondStartupTest {

        @RegisterApp
        private final TestDropwizardAppExtension APP;

        private final WireMockUtils.WireMockRuntime wireMock;

        {
            Startables.deepStart(MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, REDIS).join();

            wireMock = WireMockUtils.startWireMock();

            var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                    CLICK_HOUSE_CONTAINER, DATABASE_NAME);

            try {
                MigrationUtils.runDbMigration(MYSQL_CONTAINER.createConnection(""),
                        MySQLContainerUtils.migrationParameters());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
                MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                        ClickHouseContainerUtils.migrationParameters());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/v1/notify/event"))
                            .willReturn(WireMock.okJson(SUCCESS_RESPONSE)));

            APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                    AppContextConfig.builder()
                            .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                            .databaseAnalyticsFactory(databaseAnalyticsFactory)
                            .redisUrl(REDIS.getRedisURI())
                            .usageReportEnabled(true)
                            .usageReportUrl("%s/v1/notify/event".formatted(wireMock.runtimeInfo().getHttpBaseUrl()))
                            .metadataVersion(VERSION)
                            .build());
        }

        @Test
        void shouldNotNotifyEvent(UsageReportService usageReportService) {
            wireMock.server().verify(exactly(0), postRequestedFor(urlPathEqualTo("/v1/notify/event"))
                    .withRequestBody(matchingJsonPath("$.event_type",
                            matching(InstallationReportService.NOTIFICATION_EVENT_TYPE))));

            Assertions.assertTrue(usageReportService.isEventReported(GuiceyLifecycle.ApplicationStarted.name()));
            Assertions.assertTrue(usageReportService.getAnonymousId().isPresent());
        }
    }

    @AfterAll
    void tearDown() {
        MYSQL_CONTAINER.stop();
    }
}
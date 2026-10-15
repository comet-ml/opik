package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Service Toggles Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ServiceTogglesResourceTest {

    private static final String USER = UUID.randomUUID().toString();

    private static final String API_KEY_ENABLED = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_ENABLED = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_ENABLED = UUID.randomUUID().toString();

    private static final String API_KEY_OTHER = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME_OTHER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID_OTHER = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("serviceToggles.ollieEnabled", "true"),
                                new CustomConfig("customCharts.enabledWorkspaces", WORKSPACE_ID_ENABLED)))
                        .build());
    }

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_ENABLED, WORKSPACE_NAME_ENABLED,
                WORKSPACE_ID_ENABLED, USER);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY_OTHER, WORKSPACE_NAME_OTHER,
                WORKSPACE_ID_OTHER, USER);
    }

    Stream<Arguments> customChartsWorkspaces() {
        return Stream.of(
                arguments(API_KEY_ENABLED, WORKSPACE_NAME_ENABLED, true),
                arguments(API_KEY_OTHER, WORKSPACE_NAME_OTHER, false));
    }

    @ParameterizedTest
    @MethodSource("customChartsWorkspaces")
    @DisplayName("custom_charts_enabled is true only for a workspace on the Custom Charts allowlist")
    void getToggles__customChartsEnabled__followsTheCallersWorkspace(String apiKey, String workspaceName,
            boolean expected) {
        try (var response = client.target("%s/v1/private/toggles/".formatted(baseURI))
                .request(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(200);
            var toggles = response.readEntity(JsonNode.class);
            assertThat(toggle(toggles, "custom_charts_enabled")).isEqualTo(expected);
            assertThat(toggle(toggles, "ollie_enabled")).isTrue();
        }
    }

    // The test app writes these keys in camelCase, the deployed one in snake_case; match either.
    private static boolean toggle(JsonNode toggles, String snakeCaseName) {
        String name = snakeCaseName.replace("_", "");
        return toggles.properties().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no toggle " + snakeCaseName + " in " + toggles))
                .getValue()
                .asBoolean();
    }
}

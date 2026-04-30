package com.comet.opik.domain;

import com.comet.opik.api.AgentConfigCreate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AgentConfigsResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.hc.core5.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AgentConfigsResource analytics BI events")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AgentConfigsResourceAnalyticsTest {

    private static final String USER = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final Network network = Network.newNetwork();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;
    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        wireMock.server().stubFor(post(urlPathEqualTo("/v1/notify/event"))
                .willReturn(okJson("{\"message\":\"Event added successfully\",\"success\":\"true\"}")));

        app = newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .usageReportEnabled(true)
                        .usageReportUrl("%s/v1/notify/event".formatted(wireMock.runtimeInfo().getHttpBaseUrl()))
                        .customConfigs(List.of(
                                new TestDropwizardAppExtensionUtils.CustomConfig("analytics.enabled", "true")))
                        .build());
    }

    private AgentConfigsResourceClient agentConfigsClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        ClientSupportUtils.config(client);
        agentConfigsClient = new AgentConfigsResourceClient(client);
    }

    @AfterAll
    void tearDownAll() {
        MYSQL.stop();
        wireMock.server().stop();
        CLICKHOUSE.stop();
        ZOOKEEPER_CONTAINER.stop();
        network.close();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    static Stream<String> demoProjectNames() {
        return DemoData.PROJECTS.stream();
    }

    private AgentConfigCreate blueprintRequest(UUID projectId, String projectName) {
        var value = factory.manufacturePojo(AgentConfigValue.class).toBuilder()
                .type(AgentConfigValue.ValueType.STRING)
                .build();

        var blueprint = factory.manufacturePojo(AgentBlueprint.class).toBuilder()
                .id(null)
                .type(AgentBlueprint.BlueprintType.BLUEPRINT)
                .values(List.of(value))
                .build();

        return AgentConfigCreate.builder()
                .projectId(projectId)
                .projectName(projectName)
                .blueprint(blueprint)
                .build();
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("createConfig analytics")
    class CreateConfigAnalytics {

        @Test
        @DisplayName("non-demo project: saved and deployed events are sent")
        void createConfig_nonDemo_sendsSavedAndDeployedEvents() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var projectName = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            wireMock.server().resetRequests();

            var request = blueprintRequest(null, projectName);
            var blueprintId = agentConfigsClient.createAgentConfig(
                    request, apiKey, workspaceName, HttpStatus.SC_CREATED);

            assertThat(blueprintId).isNotNull();

            var prodBlueprint = agentConfigsClient.getBlueprintById(
                    blueprintId, null, apiKey, workspaceName, HttpStatus.SC_OK);

            assertThat(prodBlueprint).isNotNull();
            assertThat(prodBlueprint.id()).isEqualTo(blueprintId);
            assertThat(prodBlueprint.type()).isEqualTo(AgentBlueprint.BlueprintType.BLUEPRINT);
            assertThat(prodBlueprint.description()).isEqualTo(request.blueprint().description());
            assertThat(prodBlueprint.values())
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .isEqualTo(request.blueprint().values());

            var savedEvent = AnalyticsService.EVENT_PREFIX + "agent_config_saved";
            var deployedEvent = AnalyticsService.EVENT_PREFIX + "agent_config_deployed";

            Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
                wireMock.server().verify(postRequestedFor(urlPathEqualTo("/v1/notify/event"))
                        .withRequestBody(matchingJsonPath("$.event_type", equalTo(savedEvent))
                                .and(matchingJsonPath("$.event_properties.workspace_id", equalTo(workspaceId)))));
                wireMock.server().verify(postRequestedFor(urlPathEqualTo("/v1/notify/event"))
                        .withRequestBody(matchingJsonPath("$.event_type", equalTo(deployedEvent))
                                .and(matchingJsonPath("$.event_properties.workspace_id", equalTo(workspaceId)))
                                .and(matchingJsonPath("$.event_properties.environment", equalTo("prod")))
                                .and(matchingJsonPath("$.event_properties.deployed_to_prod", equalTo("true")))));
            });
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.domain.AgentConfigsResourceAnalyticsTest#demoProjectNames")
        @DisplayName("demo project: no analytics events are sent")
        void createConfig_demoProject_doesNotSendEvents(String demoProjectName) {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            wireMock.server().resetRequests();

            agentConfigsClient.createAgentConfig(
                    blueprintRequest(null, demoProjectName),
                    apiKey, workspaceName, HttpStatus.SC_CREATED);

            var savedEvent = AnalyticsService.EVENT_PREFIX + "agent_config_saved";
            var deployedEvent = AnalyticsService.EVENT_PREFIX + "agent_config_deployed";

            Awaitility.await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                wireMock.server().verify(0, postRequestedFor(urlPathEqualTo("/v1/notify/event"))
                        .withRequestBody(matchingJsonPath("$.event_type", equalTo(savedEvent))));
                wireMock.server().verify(0, postRequestedFor(urlPathEqualTo("/v1/notify/event"))
                        .withRequestBody(matchingJsonPath("$.event_type", equalTo(deployedEvent))));
            });
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("updateConfig analytics")
    class UpdateConfigAnalytics {

        @Test
        @DisplayName("non-demo project: saved event is sent")
        void updateConfig_nonDemo_sendsSavedEvent() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var projectName = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            agentConfigsClient.createAgentConfig(
                    blueprintRequest(null, projectName),
                    apiKey, workspaceName, HttpStatus.SC_CREATED);

            wireMock.server().resetRequests();

            var updateRequest = blueprintRequest(null, projectName);
            var updatedBlueprintId = agentConfigsClient.updateAgentConfig(
                    updateRequest, apiKey, workspaceName, HttpStatus.SC_CREATED);

            assertThat(updatedBlueprintId).isNotNull();

            var updatedBlueprint = agentConfigsClient.getBlueprintById(
                    updatedBlueprintId, null, apiKey, workspaceName, HttpStatus.SC_OK);

            assertThat(updatedBlueprint).isNotNull();
            assertThat(updatedBlueprint.id()).isEqualTo(updatedBlueprintId);
            assertThat(updatedBlueprint.type()).isEqualTo(AgentBlueprint.BlueprintType.BLUEPRINT);
            assertThat(updatedBlueprint.description()).isEqualTo(updateRequest.blueprint().description());
            assertThat(updatedBlueprint.values())
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .isEqualTo(updateRequest.blueprint().values());

            var savedEvent = AnalyticsService.EVENT_PREFIX + "agent_config_saved";

            Awaitility.await().atMost(30, TimeUnit.SECONDS)
                    .untilAsserted(() -> wireMock.server().verify(postRequestedFor(urlPathEqualTo("/v1/notify/event"))
                            .withRequestBody(matchingJsonPath("$.event_type", equalTo(savedEvent))
                                    .and(matchingJsonPath("$.event_properties.workspace_id",
                                            equalTo(workspaceId))))));
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.domain.AgentConfigsResourceAnalyticsTest#demoProjectNames")
        @DisplayName("demo project: no analytics events are sent")
        void updateConfig_demoProject_doesNotSendEvents(String demoProjectName) {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            agentConfigsClient.createAgentConfig(
                    blueprintRequest(null, demoProjectName),
                    apiKey, workspaceName, HttpStatus.SC_CREATED);

            wireMock.server().resetRequests();

            agentConfigsClient.updateAgentConfig(
                    blueprintRequest(null, demoProjectName),
                    apiKey, workspaceName, HttpStatus.SC_CREATED);

            var savedEvent = AnalyticsService.EVENT_PREFIX + "agent_config_saved";

            Awaitility.await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(
                    () -> wireMock.server().verify(0, postRequestedFor(urlPathEqualTo("/v1/notify/event"))
                            .withRequestBody(matchingJsonPath("$.event_type", equalTo(savedEvent)))));
        }
    }
}

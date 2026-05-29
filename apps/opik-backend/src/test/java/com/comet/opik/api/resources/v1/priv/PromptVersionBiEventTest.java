package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Prompt Version BI Event")
@ExtendWith(DropwizardAppExtensionProvider.class)
class PromptVersionBiEventTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final AnalyticsService analyticsService = Mockito.mock(AnalyticsService.class);

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickhouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(redis, clickhouse, mysql, zookeeper).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(clickhouse, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickhouse);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysql.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(redis.getRedisURI())
                        .modules(List.of(new AbstractModule() {
                            @Override
                            protected void configure() {
                                bind(AnalyticsService.class).toInstance(analyticsService);
                            }
                        }))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private PromptResourceClient promptResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.promptResourceClient = new PromptResourceClient(client, baseURI, factory);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @BeforeEach
    void resetMocks() {
        Mockito.reset(analyticsService);
    }

    @Test
    @DisplayName("when creating prompt with template then prompt_version_created BI event is emitted")
    void whenCreatingPromptWithTemplate_thenBiEventIsEmitted() {
        Prompt prompt = PromptResourceClient.buildPrompt(factory).toBuilder()
                .id(null)
                .template("Hello {{name}}")
                .build();

        UUID promptId = promptResourceClient.createPrompt(prompt, API_KEY, TEST_WORKSPACE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> propsCaptor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(analyticsService).trackEvent(Mockito.eq("prompt_version_created"), propsCaptor.capture());

        Map<String, String> props = propsCaptor.getValue();
        assertThat(props).containsEntry("prompt_id", promptId.toString());
        assertThat(props).containsEntry("workspace_id", WORKSPACE_ID);
        assertThat(props).containsEntry("user_name", USER);
        assertThat(props).containsEntry("version_type", "prompt_version");
        assertThat(props).containsKey("prompt_version_id");
        assertThat(props).containsKey("date");
        assertThat(props).doesNotContainKey("project_id");
    }

    @Test
    @DisplayName("when creating prompt version for project-scoped prompt then project_id is included")
    void whenCreatingPromptVersionForProjectScopedPrompt_thenProjectIdIsIncluded() {
        UUID projectId = projectResourceClient.createProject(factory.manufacturePojo(String.class), API_KEY,
                TEST_WORKSPACE);

        Prompt prompt = PromptResourceClient.buildPrompt(factory).toBuilder()
                .id(null)
                .projectId(projectId)
                .template("Hello {{name}}")
                .build();

        UUID promptId = promptResourceClient.createPrompt(prompt, API_KEY, TEST_WORKSPACE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> propsCaptor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(analyticsService).trackEvent(Mockito.eq("prompt_version_created"), propsCaptor.capture());

        Map<String, String> props = propsCaptor.getValue();
        assertThat(props).containsEntry("prompt_id", promptId.toString());
        assertThat(props).containsEntry("project_id", projectId.toString());
    }

    @Test
    @DisplayName("when creating new prompt version via versions endpoint then prompt_version_created BI event is emitted")
    void whenCreatingNewPromptVersionViaVersionsEndpoint_thenBiEventIsEmitted() {
        Prompt prompt = PromptResourceClient.buildPrompt(factory).toBuilder()
                .id(null)
                .template(null)
                .build();

        UUID promptId = promptResourceClient.createPrompt(prompt, API_KEY, TEST_WORKSPACE);

        Mockito.reset(analyticsService);

        PromptVersion versionPayload = factory.manufacturePojo(PromptVersion.class).toBuilder()
                .id(null)
                .commit(null)
                .environments(null)
                .build();

        CreatePromptVersion request = CreatePromptVersion.builder()
                .name(prompt.name())
                .version(versionPayload)
                .build();

        try (var response = promptResourceClient.callCreatePromptVersion(request, API_KEY, TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> propsCaptor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(analyticsService).trackEvent(Mockito.eq("prompt_version_created"), propsCaptor.capture());

        Map<String, String> props = propsCaptor.getValue();
        assertThat(props).containsEntry("prompt_id", promptId.toString());
        assertThat(props).containsEntry("workspace_id", WORKSPACE_ID);
        assertThat(props).containsEntry("version_type", "prompt_version");
    }
}

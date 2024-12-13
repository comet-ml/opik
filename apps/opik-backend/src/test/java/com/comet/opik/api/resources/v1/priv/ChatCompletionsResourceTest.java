package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ChatCompletionsClient;
import com.comet.opik.api.resources.utils.resources.LlmProviderApiKeyResourceClient;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import dev.ai4j.openai4j.chat.ChatCompletionModel;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.Role;
import org.apache.http.HttpStatus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.org.apache.commons.lang3.RandomStringUtils;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ChatCompletionsResourceTest {

    private static final String API_KEY = RandomStringUtils.randomAlphanumeric(25);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.randomAlphanumeric(20);
    private static final String USER = RandomStringUtils.randomAlphanumeric(20);

    private static final RedisContainer REDIS_CONTAINER = RedisContainerUtils.newRedisContainer();
    private static final MySQLContainer<?> MY_SQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private static final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();

    private static final WireMockUtils.WireMockRuntime WIRE_MOCK = WireMockUtils.startWireMock();

    @RegisterExtension
    private static final TestDropwizardAppExtension APP;

    static {
        Startables.deepStart(REDIS_CONTAINER, MY_SQL_CONTAINER, CLICK_HOUSE_CONTAINER).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, ClickHouseContainerUtils.DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MY_SQL_CONTAINER.getJdbcUrl(),
                databaseAnalyticsFactory,
                WIRE_MOCK.runtimeInfo(),
                REDIS_CONTAINER.getRedisURI());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private ChatCompletionsClient chatCompletionsClient;
    private LlmProviderApiKeyResourceClient llmProviderApiKeyResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runDbMigration(
                    connection,
                    MigrationUtils.CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        ClientSupportUtils.config(clientSupport);

        mockTargetWorkspace(WORKSPACE_NAME, WORKSPACE_ID);

        this.chatCompletionsClient = new ChatCompletionsClient(clientSupport);
        this.llmProviderApiKeyResourceClient = new LlmProviderApiKeyResourceClient(clientSupport);
    }

    private static void mockTargetWorkspace(String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(WIRE_MOCK.server(), API_KEY, workspaceName, workspaceId, USER);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Create {

        @Test
        void create() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createLlmProviderApiKey(workspaceName);
            var expectedModel = ChatCompletionModel.GPT_4O_MINI.toString();

            var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                    .stream(false)
                    .model(expectedModel)
                    .addUserMessage("Say 'Hello World'")
                    .build();

            var response = chatCompletionsClient.create(API_KEY, workspaceName, request);

            assertThat(response.model()).containsIgnoringCase(expectedModel);
            assertThat(response.choices()).anySatisfy(choice -> {
                assertThat(choice.message().content()).containsIgnoringCase("Hello World");
                assertThat(choice.message().role()).isEqualTo(Role.ASSISTANT);
            });
        }

        @Test
        void createReturnsBadRequestWhenNoLlmProviderApiKey() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            var expectedModel = ChatCompletionModel.GPT_4O_MINI.toString();

            var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                    .stream(false)
                    .model(expectedModel)
                    .addUserMessage("Say 'Hello World'")
                    .build();

            var errorMessage = chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST);

            assertThat(errorMessage.getCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(errorMessage.getMessage())
                    .containsIgnoringCase("API key not configured for LLM provider '%s'"
                            .formatted(LlmProvider.OPEN_AI.getValue()));
        }

        @Test
        void createReturnsBadRequestWhenNoModel() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createLlmProviderApiKey(workspaceName);

            var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                    .stream(false)
                    .addUserMessage("Say 'Hello World'")
                    .build();

            var errorMessage = chatCompletionsClient.create(API_KEY, workspaceName, request, HttpStatus.SC_BAD_REQUEST);

            assertThat(errorMessage.getCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(errorMessage.getMessage())
                    .containsIgnoringCase("Only %s model is available".formatted(ChatCompletionModel.GPT_4O_MINI));
        }

        @Test
        void createAndStreamResponse() {
            var workspaceName = RandomStringUtils.randomAlphanumeric(20);
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(workspaceName, workspaceId);
            createLlmProviderApiKey(workspaceName);
            var expectedModel = ChatCompletionModel.GPT_4O_MINI.toString();

            var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                    .stream(true)
                    .model(expectedModel)
                    .addUserMessage("Say 'Hello World'")
                    .build();

            var response = chatCompletionsClient.createAndStream(API_KEY, workspaceName, request);

            assertThat(response)
                    .allSatisfy(entity -> assertThat(entity.model())
                            .containsIgnoringCase(expectedModel));

            var choices = response.stream().flatMap(entity -> entity.choices().stream()).toList();
            assertThat(choices)
                    .anySatisfy(choice -> assertThat(choice.delta().content())
                            .containsIgnoringCase("Hello"));
            assertThat(choices)
                    .anySatisfy(choice -> assertThat(choice.delta().content())
                            .containsIgnoringCase("World"));
            assertThat(choices).anySatisfy(choice -> assertThat(choice.delta().role())
                    .isEqualTo(Role.ASSISTANT));
        }
    }

    @Test
    void createAndStreamResponseReturnsBadRequestWhenNoModel() {
        var workspaceName = RandomStringUtils.randomAlphanumeric(20);
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(workspaceName, workspaceId);
        createLlmProviderApiKey(workspaceName);

        var request = podamFactory.manufacturePojo(ChatCompletionRequest.Builder.class)
                .stream(true)
                .addUserMessage("Say 'Hello World'")
                .build();

        var errorMessages = chatCompletionsClient.createAndStreamError(API_KEY, workspaceName, request);

        assertThat(errorMessages).hasSize(1);
        assertThat(errorMessages.getFirst().getCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        assertThat(errorMessages.getFirst().getMessage())
                .containsIgnoringCase("Only %s model is available".formatted(ChatCompletionModel.GPT_4O_MINI));
    }

    private void createLlmProviderApiKey(String workspaceName) {
        var llmProviderApiKey = UUID.randomUUID().toString();
        llmProviderApiKeyResourceClient.createProviderApiKey(
                llmProviderApiKey, LlmProvider.OPEN_AI, API_KEY, workspaceName, 201);
    }
}

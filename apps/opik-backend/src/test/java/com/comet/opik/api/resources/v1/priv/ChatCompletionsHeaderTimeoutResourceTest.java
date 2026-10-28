package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ChatCompletionsClient;
import com.comet.opik.api.resources.utils.resources.LlmProviderApiKeyResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A provider can hold a stream's response headers while the model reasons, so a streamed call waits for them up to
 * the longer of the read timeout and the stream read timeout, and a call that isn't streamed up to the read timeout.
 * Both are short here, and the custom provider is a WireMock stub that delays its whole reply.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Chat completions: the wait for a provider's response headers")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ChatCompletionsHeaderTimeoutResourceTest {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration STREAM_READ_TIMEOUT = Duration.ofSeconds(3);
    private static final String USER = UUID.randomUUID().toString();
    private static final String CUSTOM_PROVIDER_NAME = "header-timeout";
    private static final String MODEL = "custom-llm/%s/gpt-4o".formatted(CUSTOM_PROVIDER_NAME);
    private static final String CHAT_COMPLETIONS_REGEX = ".*/chat/completions.*";
    private static final ErrorMessage TIMED_OUT = new ErrorMessage(HttpStatus.SC_REQUEST_TIMEOUT,
            UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("llmProviderClient.readTimeout",
                                        "%dms".formatted(READ_TIMEOUT.toMillis())),
                                new CustomConfig("llmProviderClient.streamReadTimeout",
                                        "%dms".formatted(STREAM_READ_TIMEOUT.toMillis()))))
                        .build());
    }

    private ChatCompletionsClient chatCompletionsClient;
    private LlmProviderApiKeyResourceClient llmProviderApiKeyResourceClient;

    private String apiKey;
    private String workspaceName;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport) {
        ClientSupportUtils.config(clientSupport);
        chatCompletionsClient = new ChatCompletionsClient(clientSupport);
        llmProviderApiKeyResourceClient = new LlmProviderApiKeyResourceClient(clientSupport);
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
        apiKey = UUID.randomUUID().toString();
        workspaceName = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, UUID.randomUUID().toString(),
                USER);
        llmProviderApiKeyResourceClient.createProviderApiKey(factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .provider(LlmProvider.CUSTOM_LLM)
                .providerName(CUSTOM_PROVIDER_NAME)
                .createdBy(USER)
                .lastUpdatedBy(USER)
                .baseUrl(wireMock.runtimeInfo().getHttpBaseUrl())
                .configuration(Map.of("provider_name", CUSTOM_PROVIDER_NAME, "models", MODEL))
                .build(), apiKey, workspaceName, HttpStatus.SC_CREATED);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void stubProviderStream(Duration headerDelay, String chunk) {
        wireMock.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withHeader("Content-Type", "text/event-stream")
                        .withFixedDelay(Math.toIntExact(headerDelay.toMillis()))
                        .withBody("data: %s\n\ndata: [DONE]\n\n".formatted(chunk))));
    }

    private static ChatCompletionRequest request(boolean stream) {
        return ChatCompletionRequest.builder()
                .stream(stream)
                .model(MODEL)
                .addUserMessage(RandomStringUtils.secure().nextAlphanumeric(10))
                .build();
    }

    @Test
    @DisplayName("a stream whose headers take longer than the read timeout, within the stream read timeout, completes")
    void aStreamWhoseHeadersArriveWithinTheStreamReadTimeoutCompletes() {
        var chunk = """
                {"id":"chatcmpl-%s","object":"chat.completion.chunk","created":%d,"model":"gpt-4o",\
                "choices":[{"index":0,"delta":{"role":"assistant","content":"%s"},"finish_reason":"stop"}]}"""
                .formatted(RandomStringUtils.secure().nextAlphanumeric(10),
                        System.currentTimeMillis() / 1000,
                        RandomStringUtils.secure().nextAlphanumeric(20));
        stubProviderStream(READ_TIMEOUT.multipliedBy(2), chunk);
        var expectedChunks = List.of(JsonUtils.readValue(chunk, ChatCompletionResponse.class));

        var actualChunks = chatCompletionsClient.createAndStream(apiKey, workspaceName, request(true));

        assertThat(actualChunks).isEqualTo(expectedChunks);
    }

    @Test
    @DisplayName("a stream whose headers take longer than both timeouts fails at the stream read timeout")
    void aStreamWhoseHeadersTakeLongerThanBothTimeoutsFails() {
        stubProviderStream(STREAM_READ_TIMEOUT.multipliedBy(3), "{}");
        var expectedErrors = List.of(TIMED_OUT);
        long startNanos = System.nanoTime();

        var actualErrors = chatCompletionsClient.createAndGetStreamedError(apiKey, workspaceName, request(true));

        var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        assertThat(actualErrors).isEqualTo(expectedErrors);
        assertThat(actualElapsed).isBetween(STREAM_READ_TIMEOUT, STREAM_READ_TIMEOUT.multipliedBy(2));
    }

    @Test
    @DisplayName("a call that isn't streamed keeps the read timeout for its headers")
    void aCallThatIsNotStreamedFailsAtTheReadTimeout() {
        wireMock.server().stubFor(post(urlPathMatching(CHAT_COMPLETIONS_REGEX))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.SC_OK)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(Math.toIntExact(READ_TIMEOUT.multipliedBy(2).toMillis()))
                        .withBody("{}")));

        var actualError = chatCompletionsClient.create(apiKey, workspaceName, request(false),
                HttpStatus.SC_REQUEST_TIMEOUT);

        assertThat(actualError).isEqualTo(TIMED_OUT);
    }
}

package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.infrastructure.llm.antropic.AnthropicClientGenerator;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils.bundledRegistry;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

/**
 * LlmProviderFactoryImpl skips its own base URL check for Bedrock, as every Bedrock route takes its HTTP client from
 * CustomLlmClientGenerator, which checks each request. These hold that for the Converse and Anthropic Messages routes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BedrockDestinationGuardTest {

    private static final String PROVIDER_NAME = "bedrock";
    private static final String MODEL = "us.amazon.nova-pro-v1:0";
    private static final String OPIK_MODEL = "custom-llm/" + PROVIDER_NAME + "/" + MODEL;
    private static final String PROVIDER_PATHS = "/(model|anthropic)/.*";
    private static final String REDIRECT_TARGET = "/redirected";

    private static final LlmProviderClientConfig CONFIG = TestConfigUtils.loadConfigTest().getLlmProviderClient();
    private static final ProviderBaseUrlGuard RELAXED_GUARD = ProviderBaseUrlGuard.builder()
            .mode(DestinationGuard.Mode.RELAXED)
            .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
            .build();
    private static final ProviderBaseUrlGuard STRICT_GUARD = RELAXED_GUARD.toBuilder()
            .mode(DestinationGuard.Mode.STRICT)
            .build();

    private enum Call {
        PLAYGROUND,
        PLAYGROUND_STREAM,
        JUDGE
    }

    private WireMockServer bedrock;

    @BeforeAll
    void startServer() {
        bedrock = LlmTestClients.newLoopbackWireMock();
        bedrock.start();
    }

    @AfterAll
    void stopServer() {
        bedrock.stop();
    }

    @BeforeEach
    void resetServer() {
        bedrock.resetAll();
    }

    private Stream<Arguments> refusesAnInternalDestinationBeforeConnecting() {
        var baseUrls = List.of(
                "http://127.0.0.1:" + bedrock.port(),
                "http://localhost:" + bedrock.port(),
                "http://[::1]:" + bedrock.port(),
                "http://[::ffff:127.0.0.1]:" + bedrock.port(),
                "http://2130706433:" + bedrock.port(),
                "http://169.254.169.254",
                "http://10.0.0.1",
                "file:///etc/passwd");
        return everyCall().flatMap(arguments -> baseUrls.stream()
                .map(baseUrl -> arguments(arguments.get()[0], arguments.get()[1], baseUrl)));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource
    void refusesAnInternalDestinationBeforeConnecting(BedrockApi api, Call call, String baseUrl) {
        var serviceProvider = serviceProvider(STRICT_GUARD, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);

        var failure = failureOf(serviceProvider, config(api, baseUrl), call);

        assertRefused(failure);
        bedrock.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * The URL check is relaxed and lets localhost through, so only the connect-time check, which the client reports
     * as the same refusal, can stop it, as it would an address changed by a DNS rebinding.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyCall")
    void neverConnectsToAnAddressRefusedAtConnectTime(BedrockApi api, Call call) {
        var strictAtConnect = new CustomLlmModule().llmHttpClientBuilders(CONFIG,
                CustomLlmModule.llmHttpClient(CONFIG, STRICT_GUARD.destinationGuard()));
        var serviceProvider = serviceProvider(RELAXED_GUARD, strictAtConnect);

        var failure = failureOf(serviceProvider, config(api, "http://localhost:" + bedrock.port()), call);

        assertRefused(failure);
        bedrock.verify(0, anyRequestedFor(anyUrl()));
    }

    private Stream<Arguments> doesNotFollowARedirect() {
        return everyCall().flatMap(arguments -> Stream.of(302, 307, 308)
                .map(status -> arguments(arguments.get()[0], arguments.get()[1], status)));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource
    void doesNotFollowARedirect(BedrockApi api, Call call, int status) {
        bedrock.stubFor(any(urlPathMatching(PROVIDER_PATHS)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Location", LlmTestClients.baseUrlOf(bedrock) + REDIRECT_TARGET)));
        bedrock.stubFor(any(urlEqualTo(REDIRECT_TARGET)).willReturn(okJson("{}")));
        var serviceProvider = serviceProvider(RELAXED_GUARD, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);

        var failure = failureOf(serviceProvider, config(api, LlmTestClients.baseUrlOf(bedrock)), call);

        assertThat(failure).isNotNull();
        bedrock.verify(moreThanOrExactly(1), postRequestedFor(urlPathMatching(PROVIDER_PATHS)));
        bedrock.verify(0, anyRequestedFor(urlEqualTo(REDIRECT_TARGET)));
    }

    private static void assertRefused(Throwable failure) {
        assertThat(ExceptionUtils.getThrowableList(failure))
                .filteredOn(ProviderRequestRefusedException.class::isInstance)
                .extracting(Throwable::getMessage)
                .containsExactly(ProviderBaseUrlGuard.REFUSED);
    }

    private static Stream<Arguments> everyCall() {
        return Stream.of(BedrockApi.CONVERSE, BedrockApi.ANTHROPIC_MESSAGES)
                .flatMap(api -> Arrays.stream(Call.values()).map(call -> arguments(api, call)));
    }

    private static OpenAICompatibleServiceProvider serviceProvider(ProviderBaseUrlGuard guard,
            LlmHttpClientBuilders httpClientBuilders) {
        return new OpenAICompatibleServiceProvider(
                new CustomLlmClientGenerator(CONFIG, guard, mock(AuthTokenProvider.class), httpClientBuilders),
                new AnthropicClientGenerator(CONFIG, bundledRegistry(), httpClientBuilders),
                bundledRegistry(),
                mock(LlmProviderFactory.class));
    }

    private static Throwable failureOf(OpenAICompatibleServiceProvider serviceProvider,
            LlmProviderClientApiConfig config, Call call) {
        return switch (call) {
            case PLAYGROUND -> catchThrowable(() -> serviceProvider.getService(config)
                    .generate(request(false), "workspace"));
            case PLAYGROUND_STREAM -> streamFailureOf(serviceProvider.getService(config));
            case JUDGE -> catchThrowable(() -> serviceProvider
                    .getLanguageModel(config, LlmAsJudgeModelParameters.builder().name(OPIK_MODEL).build())
                    .chat(ChatRequest.builder().messages(UserMessage.from("Is Dublin right?")).build()));
        };
    }

    private static Throwable streamFailureOf(LlmProviderService service) {
        var done = new CompletableFuture<Void>();
        service.generateStream(request(true), "workspace", chunk -> {
        }, () -> done.complete(null), done::completeExceptionally);
        return catchThrowable(() -> done.get(30, TimeUnit.SECONDS));
    }

    private static ChatCompletionRequest request(boolean stream) {
        return ChatCompletionRequest.builder()
                .model(OPIK_MODEL)
                .maxCompletionTokens(16)
                .addUserMessage("What is the capital of Ireland?")
                .stream(stream)
                .build();
    }

    private static LlmProviderClientApiConfig config(BedrockApi api, String baseUrl) {
        return LlmProviderClientApiConfig.builder()
                .provider(LlmProvider.BEDROCK)
                .apiKey("bedrock-test-key")
                .baseUrl(api == BedrockApi.ANTHROPIC_MESSAGES ? baseUrl + "/anthropic" : baseUrl)
                .configuration(Map.of(
                        "provider_name", PROVIDER_NAME,
                        "models", OPIK_MODEL,
                        BedrockApi.CONFIG_KEY, api.name().toLowerCase(Locale.ROOT)))
                .headers(Map.of())
                .build();
    }
}

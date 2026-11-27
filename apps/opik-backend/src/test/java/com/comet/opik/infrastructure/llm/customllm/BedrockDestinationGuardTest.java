package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.EncryptedAuthConfig;
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
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmProviderFactoryImpl skips its own base URL check for Bedrock, as every Bedrock route takes its HTTP client from
 * CustomLlmClientGenerator, which checks each request. These hold that for the Converse and Anthropic Messages routes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BedrockDestinationGuardTest {

    private static final String PROVIDER_NAME = "bedrock";
    private static final String MODEL = "us.amazon.nova-pro-v1:0";
    private static final String OPIK_MODEL = "custom-llm/" + PROVIDER_NAME + "/" + MODEL;
    private static final String LOOPBACK_MODEL = "custom-llm/" + PROVIDER_NAME + "/127.0.0.1";
    private static final String PROVIDER_PATHS = "/(model|anthropic)/.*";
    private static final String REDIRECT_TARGET = "/redirected";
    private static final String WORKSPACE = "workspace";
    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final EncryptedAuthConfig AUTH_CONFIG = EncryptedAuthConfig.fromCiphertext("ciphertext");

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

    /** Every one but NONE puts InterceptingHttpClient, which rewrites the request, in front of the URL check. */
    private enum Rewrite {
        NONE,
        QUERY_PARAMS,
        TOKEN
    }

    private final AuthTokenProvider authTokenProvider = mock(AuthTokenProvider.class);
    private WireMockServer bedrock;

    @BeforeAll
    void startServer() {
        when(authTokenProvider.bearer(WORKSPACE, PROVIDER_ID, AUTH_CONFIG)).thenReturn("fetched-token");
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
                .map(baseUrl -> arguments(ArrayUtils.add(arguments.get(), baseUrl))));
    }

    @ParameterizedTest(name = "{0} {1} {2} {3}")
    @MethodSource
    void refusesAnInternalDestinationBeforeConnecting(BedrockApi api, Call call, Rewrite rewrite, String baseUrl) {
        var serviceProvider = serviceProvider(STRICT_GUARD, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);

        var failure = failureOf(serviceProvider, config(api, baseUrl, rewrite), call, OPIK_MODEL);

        assertRefused(failure);
        bedrock.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * The URL check is relaxed and lets localhost through, so only the connect-time check, which the client reports
     * as the same refusal, can stop it, as it would an address changed by a DNS rebinding.
     */
    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("everyCall")
    void neverConnectsToAnAddressRefusedAtConnectTime(BedrockApi api, Call call, Rewrite rewrite) {
        var strictAtConnect = new CustomLlmModule().llmHttpClientBuilders(CONFIG,
                CustomLlmModule.llmHttpClient(CONFIG, STRICT_GUARD.destinationGuard()));
        var serviceProvider = serviceProvider(RELAXED_GUARD, strictAtConnect);

        var failure = failureOf(serviceProvider, config(api, "http://localhost:" + bedrock.port(), rewrite), call,
                OPIK_MODEL);

        assertRefused(failure);
        bedrock.verify(0, anyRequestedFor(anyUrl()));
    }

    private Stream<Arguments> doesNotFollowARedirect() {
        return everyCall().flatMap(arguments -> Stream.of(302, 307, 308)
                .map(status -> arguments(ArrayUtils.add(arguments.get(), status))));
    }

    @ParameterizedTest(name = "{0} {1} {2} {3}")
    @MethodSource
    void doesNotFollowARedirect(BedrockApi api, Call call, Rewrite rewrite, int status) {
        bedrock.stubFor(any(urlPathMatching(PROVIDER_PATHS)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Location", LlmTestClients.baseUrlOf(bedrock) + REDIRECT_TARGET)));
        bedrock.stubFor(any(urlEqualTo(REDIRECT_TARGET)).willReturn(okJson("{}")));
        var serviceProvider = serviceProvider(RELAXED_GUARD, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);

        var failure = failureOf(serviceProvider, config(api, LlmTestClients.baseUrlOf(bedrock), rewrite), call,
                OPIK_MODEL);

        assertThat(failure).isNotNull();
        bedrock.verify(moreThanOrExactly(1), postRequestedFor(urlPathMatching(PROVIDER_PATHS)));
        bedrock.verify(0, anyRequestedFor(urlEqualTo(REDIRECT_TARGET)));
    }

    /** Anthropic Messages only: Converse names the model in the path, so its request body has none to substitute. */
    @ParameterizedTest
    @EnumSource(Call.class)
    void refusesAModelInTheHostThatNamesAnInternalAddress(Call call) {
        var serviceProvider = serviceProvider(STRICT_GUARD, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);
        var config = config(BedrockApi.ANTHROPIC_MESSAGES, modelInHostBaseUrl(), Rewrite.NONE);

        var failure = failureOf(serviceProvider, config, call, LOOPBACK_MODEL);

        assertRefused(failure);
        bedrock.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * Until {model} is substituted the host is only a placeholder, which a strict check can't parse and so refuses:
     * checked any earlier, every such provider would be refused.
     */
    @ParameterizedTest
    @EnumSource(Call.class)
    void checksAModelInTheHostOnlyOnceSubstituted(Call call) {
        var guard = spy(RELAXED_GUARD);
        var serviceProvider = serviceProvider(guard, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);
        var config = config(BedrockApi.ANTHROPIC_MESSAGES, modelInHostBaseUrl(), Rewrite.NONE);
        var checkedUrls = ArgumentCaptor.forClass(String.class);

        failureOf(serviceProvider, config, call, LOOPBACK_MODEL);

        verify(guard, atLeastOnce()).validateRequest(checkedUrls.capture());
        assertThat(checkedUrls.getAllValues())
                .containsOnly(LlmTestClients.baseUrlOf(bedrock) + "/anthropic/v1/messages");
    }

    private String modelInHostBaseUrl() {
        return "http://" + InterceptingHttpClient.MODEL_PLACEHOLDER + ":" + bedrock.port();
    }

    private static void assertRefused(Throwable failure) {
        assertThat(ExceptionUtils.getThrowableList(failure))
                .filteredOn(ProviderRequestRefusedException.class::isInstance)
                .extracting(Throwable::getMessage)
                .containsExactly(ProviderBaseUrlGuard.REFUSED);
    }

    private static Stream<Arguments> everyCall() {
        return Stream.of(BedrockApi.CONVERSE, BedrockApi.ANTHROPIC_MESSAGES)
                .flatMap(api -> Arrays.stream(Call.values())
                        .flatMap(call -> Arrays.stream(Rewrite.values())
                                .map(rewrite -> arguments(api, call, rewrite))));
    }

    private OpenAICompatibleServiceProvider serviceProvider(ProviderBaseUrlGuard guard,
            LlmHttpClientBuilders httpClientBuilders) {
        return new OpenAICompatibleServiceProvider(
                new CustomLlmClientGenerator(CONFIG, guard, authTokenProvider, httpClientBuilders),
                new AnthropicClientGenerator(CONFIG, bundledRegistry(), httpClientBuilders),
                bundledRegistry(),
                mock(LlmProviderFactory.class));
    }

    private static Throwable failureOf(OpenAICompatibleServiceProvider serviceProvider,
            LlmProviderClientApiConfig config, Call call, String model) {
        return switch (call) {
            case PLAYGROUND -> catchThrowable(() -> serviceProvider.getService(config)
                    .generate(request(model, false), WORKSPACE));
            case PLAYGROUND_STREAM -> streamFailureOf(serviceProvider.getService(config), model);
            case JUDGE -> catchThrowable(() -> serviceProvider
                    .getLanguageModel(config, LlmAsJudgeModelParameters.builder().name(model).build())
                    .chat(ChatRequest.builder().messages(UserMessage.from("Is Dublin right?")).build()));
        };
    }

    private static Throwable streamFailureOf(LlmProviderService service, String model) {
        var done = new CompletableFuture<Void>();
        service.generateStream(request(model, true), WORKSPACE, chunk -> {
        }, () -> done.complete(null), done::completeExceptionally);
        return catchThrowable(() -> done.get(30, TimeUnit.SECONDS));
    }

    private static ChatCompletionRequest request(String model, boolean stream) {
        return ChatCompletionRequest.builder()
                .model(model)
                .maxCompletionTokens(16)
                .addUserMessage("What is the capital of Ireland?")
                .stream(stream)
                .build();
    }

    private static LlmProviderClientApiConfig config(BedrockApi api, String baseUrl, Rewrite rewrite) {
        var configuration = new HashMap<>(Map.of(
                "provider_name", PROVIDER_NAME,
                "models", OPIK_MODEL,
                BedrockApi.CONFIG_KEY, api.name().toLowerCase(Locale.ROOT)));
        if (rewrite == Rewrite.QUERY_PARAMS) {
            configuration.put(InterceptingHttpClient.URL_QUERY_PARAMS_CONFIG_KEY, "{\"api-version\":\"1\"}");
        }
        var builder = LlmProviderClientApiConfig.builder()
                .provider(LlmProvider.BEDROCK)
                .apiKey("bedrock-test-key")
                .baseUrl(api == BedrockApi.ANTHROPIC_MESSAGES ? baseUrl + "/anthropic" : baseUrl)
                .configuration(configuration)
                .headers(Map.of());
        if (rewrite == Rewrite.TOKEN) {
            builder.apiKey(null).authConfig(AUTH_CONFIG).providerId(PROVIDER_ID).workspaceId(WORKSPACE);
        }
        return builder.build();
    }
}

package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.GeminiThinkingParams;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.common.base.Preconditions;
import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.google.genai.GoogleGenAiChatModel;
import dev.langchain4j.model.google.genai.GoogleGenAiStreamingChatModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
public class VertexAIClientGenerator implements LlmProviderClientGenerator<ChatModel> {

    private final @NonNull LlmProviderClientConfig clientConfig;

    /**
     * Only set by the tests, which need a client trusting the local stub's self-signed certificate: the SDK talks
     * through OkHttp, so the JVM-wide {@code HttpsURLConnection} defaults a test sets never reach it.
     */
    private final OkHttpClient httpClient;

    public VertexAIClientGenerator(@NonNull LlmProviderClientConfig clientConfig) {
        this(clientConfig, null);
    }

    VertexAIClientGenerator(@NonNull LlmProviderClientConfig clientConfig, OkHttpClient httpClient) {
        this.clientConfig = clientConfig;
        this.httpClient = httpClient;
    }

    CloseableVertexAiChatModel newVertexAIClient(LlmProviderClientApiConfig apiKey, ChatCompletionRequest request) {
        return buildOwnedClient(apiKey, request, (client, model) -> {
            var builder = GoogleGenAiChatModel.builder()
                    .client(client)
                    .modelName(model);

            Optional.ofNullable(request.temperature()).ifPresent(builder::temperature);
            Optional.ofNullable(request.topP()).ifPresent(builder::topP);
            Optional.ofNullable(request.stop()).ifPresent(builder::stopSequences);
            Optional.ofNullable(request.presencePenalty()).ifPresent(builder::presencePenalty);
            Optional.ofNullable(request.frequencyPenalty()).ifPresent(builder::frequencyPenalty);
            Optional.ofNullable(request.maxTokens()).ifPresent(builder::maxOutputTokens);
            Optional.ofNullable(request.seed()).ifPresent(builder::seed);

            applyThinking(builder::thinkingLevel, builder::thinkingBudget, model, request.customParameters());

            return new CloseableVertexAiChatModel(builder.build(), client);
        });
    }

    CloseableVertexAiStreamingChatModel newVertexAIStreamingClient(@NonNull LlmProviderClientApiConfig apiKey,
            @NonNull ChatCompletionRequest request) {
        return buildOwnedClient(apiKey, request, (client, model) -> {
            var builder = GoogleGenAiStreamingChatModel.builder()
                    .client(client)
                    .modelName(model);

            Optional.ofNullable(request.temperature()).ifPresent(builder::temperature);
            Optional.ofNullable(request.topP()).ifPresent(builder::topP);
            Optional.ofNullable(request.stop()).ifPresent(builder::stopSequences);
            Optional.ofNullable(request.presencePenalty()).ifPresent(builder::presencePenalty);
            Optional.ofNullable(request.frequencyPenalty()).ifPresent(builder::frequencyPenalty);
            Optional.ofNullable(request.maxTokens()).ifPresent(builder::maxOutputTokens);
            Optional.ofNullable(request.seed()).ifPresent(builder::seed);

            applyThinking(builder::thinkingLevel, builder::thinkingBudget, model, request.customParameters());

            return new CloseableVertexAiStreamingChatModel(builder.build(), client);
        });
    }

    /**
     * Gemini 3 and later take {@code thinking_level}; earlier models take the budget it translates to, and reject a
     * level outright. The two are mutually exclusive — the SDK throws if both are set — so exactly one is applied.
     * <p>
     * Takes the setters rather than a builder because the chat and streaming builders share these methods but no
     * supertype.
     * <p>
     * {@code include_thoughts} is deliberately not forwarded. The module keeps thought parts out of the answer text
     * unless {@code returnThinking} is set, so asking for them would bill thinking tokens and surface nothing. Wire it
     * up only alongside a way to display them.
     */
    private static void applyThinking(Consumer<String> level, Consumer<Integer> budget, String model,
            Map<String, Object> customParameters) {
        var params = GeminiThinkingParams.from(customParameters);
        if (params.isAbsent()) {
            return;
        }

        params.wireLevelFor(model).ifPresentOrElse(
                level,
                () -> params.wireBudgetFor(model).ifPresent(budget));
    }

    // Fresh Client per call, handed to the wrapper that owns and closes it; closed here if setup fails first.
    // The model builder keeps its client private with no accessor, so ownership is only possible by building
    // the Client here and passing it in.
    private <T> T buildOwnedClient(LlmProviderClientApiConfig apiKey, ChatCompletionRequest request,
            OwnedClientFactory<T> factory) {
        var vertexAIModelName = VertexAIModelName.byQualifiedName(request.model())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported model: " + request.model()));

        Client client = buildClient(apiKey);
        try {
            return factory.create(client, vertexAIModelName.toString());
        } catch (RuntimeException e) {
            closeSuppressing(client, e);
            throw e;
        }
    }

    @FunctionalInterface
    private interface OwnedClientFactory<T> {
        T create(Client client, String model);
    }

    private InternalServerErrorException failWithError(Exception e) {
        return new InternalServerErrorException("Failed to create GoogleCredentials", e);
    }

    // Close a client we built but couldn't hand to a wrapping owner, so it can't outlive the failure.
    private static void closeSuppressing(Client client, RuntimeException failure) {
        try {
            client.close();
        } catch (Exception e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * The location is free-text in the provider configuration but ends up in the {@code locations/%s} resource path as
     * well as driving the endpoint lookup, so it has to be canonicalised before either is derived from it. The
     * configured endpoint keys are constrained to the same lower-case form, so both sides of the lookup agree.
     */
    private static String canonicalLocation(String location) {
        return location.strip().toLowerCase(Locale.ROOT);
    }

    private Optional<String> apiEndpointFor(String canonicalLocation) {
        return Optional.ofNullable(clientConfig.getVertexAIClient().multiRegionApiEndpoints().get(canonicalLocation));
    }

    private Client buildClient(LlmProviderClientApiConfig config) {
        var location = Optional.ofNullable(config.configuration().get("location"))
                .filter(StringUtils::isNotBlank)
                .map(VertexAIClientGenerator::canonicalLocation);

        try {
            var credentials = ServiceAccountCredentials.fromStream(
                    new ByteArrayInputStream(config.apiKey().getBytes(StandardCharsets.UTF_8)));

            var builder = Client.builder()
                    .vertexAI(true)
                    .project(credentials.getProjectId())
                    .credentials(credentials.createScoped(clientConfig.getVertexAIClient().scope()));

            location.ifPresent(builder::location);

            // Only multi-region locations are remapped; single-region ones keep the endpoint the SDK derives
            // from the location itself. Note this goes through httpOptions rather than Client.Builder#baseUrl:
            // the latter is only honoured when no project/location is set, which Vertex always has.
            var httpOptions = HttpOptions.builder();
            location.flatMap(this::apiEndpointFor).ifPresent(httpOptions::baseUrl);

            // The SDK disables the HTTP client's own timeouts and applies one only if asked, so an unset
            // timeout means a request can hang indefinitely.
            Optional.ofNullable(clientConfig.getCallTimeout())
                    .ifPresent(timeout -> httpOptions.timeout((int) timeout.toMilliseconds()));

            Optional.ofNullable(httpClient).ifPresent(
                    client -> builder.clientOptions(ClientOptions.builder().customHttpClient(client).build()));

            return builder.httpOptions(httpOptions.build()).build();
        } catch (IOException e) {
            throw failWithError(e);
        }
    }

    @Override
    public ChatModel generate(@NonNull LlmProviderClientApiConfig config, Object... params) {
        Preconditions.checkArgument(params.length >= 1, "Expected at least 1 parameter, got " + params.length);
        ChatCompletionRequest request = (ChatCompletionRequest) Objects.requireNonNull(params[0],
                "ChatCompletionRequest is required");

        return newVertexAIClient(config, request);
    }

    @Override
    public ChatModel generateChat(@NonNull LlmProviderClientApiConfig apiKey,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        var requestBuilder = ChatCompletionRequest.builder()
                .model(modelParameters.name());

        Optional.ofNullable(modelParameters.temperature()).ifPresent(requestBuilder::temperature);
        Optional.ofNullable(modelParameters.seed()).ifPresent(requestBuilder::seed);

        // Round-tripped through the request so the generation config is derived in one place for both paths.
        // Only an object converts to a Map: custom_parameters is unvalidated free-form JSON, and Jackson throws
        // IllegalArgumentException on an array or scalar, which would fail the whole run rather than be ignored.
        Optional.ofNullable(modelParameters.customParameters())
                .filter(JsonNode::isObject)
                .map(customParameters -> JsonUtils.getMapper()
                        .convertValue(customParameters, new TypeReference<Map<String, Object>>() {
                        }))
                .ifPresent(requestBuilder::customParameters);

        return newVertexAIClient(apiKey, requestBuilder.build());
    }
}

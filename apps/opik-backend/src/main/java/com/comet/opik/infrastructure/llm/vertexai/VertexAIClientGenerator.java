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
import com.google.genai.types.HttpOptions;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.google.genai.GoogleGenAiChatModel;
import dev.langchain4j.model.google.genai.GoogleGenAiStreamingChatModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class VertexAIClientGenerator implements LlmProviderClientGenerator<ChatModel> {

    private final @NonNull LlmProviderClientConfig clientConfig;

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
     * Takes the setters rather than a builder because the chat and streaming builders share these methods but no
     * supertype. At most one of level and budget is ever present — the SDK throws if both are set.
     * <p>
     * {@code include_thoughts} is deliberately not forwarded: the module drops thought parts unless
     * {@code returnThinking} is set, so asking for them would bill thinking tokens and surface nothing.
     */
    private static void applyThinking(Consumer<String> level, Consumer<Integer> budget, String model,
            Map<String, Object> customParameters) {
        var params = GeminiThinkingParams.from(customParameters);

        params.wireLevelFor(model).ifPresent(level);
        params.wireBudgetFor(model).ifPresent(budget);
    }

    // Fresh Client per call, handed to the wrapper that owns and closes it; closed here if setup fails first.
    // Built here rather than by the model builder, which keeps its client private and is not closeable.
    private <T> T buildOwnedClient(LlmProviderClientApiConfig apiKey, ChatCompletionRequest request,
            OwnedClientFactory<T> factory) {
        var vertexAIModelName = VertexAIModelName.byQualifiedName(request.model())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported model: %s".formatted(request.model())));

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
     * The location is free-text in the configuration but reaches both the {@code locations/%s} resource path and the
     * endpoint lookup, whose keys are constrained to this same lower-case form.
     */
    private static String canonicalLocation(String location) {
        return location.strip().toLowerCase(Locale.ROOT);
    }

    private Optional<String> apiEndpointFor(String canonicalLocation) {
        return Optional.ofNullable(clientConfig.getVertexAIClient().multiRegionApiEndpoints().get(canonicalLocation))
                .map(VertexAIClientGenerator::withScheme);
    }

    /**
     * The SDK concatenates the endpoint into a URL and re-parses it, so a bare host would land in the path and
     * misroute the request silently. The configuration still accepts one, so default the scheme here instead.
     */
    private static String withScheme(String endpoint) {
        return endpoint.startsWith("http://") || endpoint.startsWith("https://") ? endpoint : "https://" + endpoint;
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

            // Only multi-region locations are remapped; single-region ones keep the SDK-derived endpoint.
            // Must go through httpOptions: Client.Builder#baseUrl is ignored once project/location is set.
            var httpOptions = HttpOptions.builder();
            location.flatMap(this::apiEndpointFor).ifPresent(httpOptions::baseUrl);

            // The SDK disables its HTTP client's timeouts, so without this a request can hang indefinitely.
            // The SDK takes milliseconds as an int, and the configuration is only bounded below, so clamp rather
            // than let a value over ~24.8 days wrap into a negative timeout.
            Optional.ofNullable(clientConfig.getCallTimeout())
                    .map(timeout -> (int) Math.min(timeout.toMilliseconds(), Integer.MAX_VALUE))
                    .ifPresent(httpOptions::timeout);

            return builder.httpOptions(httpOptions.build()).build();
        } catch (IOException e) {
            throw failWithError(e);
        }
    }

    @Override
    public ChatModel generate(@NonNull LlmProviderClientApiConfig config, Object... params) {
        Preconditions.checkArgument(params.length >= 1, "Expected at least 1 parameter, got %s", params.length);
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

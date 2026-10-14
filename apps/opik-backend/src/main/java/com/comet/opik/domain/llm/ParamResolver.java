package com.comet.opik.domain.llm;

import com.comet.opik.api.ModelParams;
import com.comet.opik.api.ModelParams.NumberParam;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shapes a request from the model catalog: the settings to send, and a list of what it changed and why.
 *
 * <p>Applied centrally, before the request reaches a provider, because a constraint belongs to the model rather than
 * to whoever routes it: the same Claude models reach us through Anthropic, Bedrock and OpenAI-compatible proxies, and
 * Bedrock answers a request carrying both temperature and top_p with "temperature and top_p cannot both be specified
 * for this model".
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ParamResolver {

    public record Resolution<T>(@NonNull T request, @NonNull List<String> changes) {
    }

    private final @NonNull LlmProviderFactory llmProviderFactory;
    private final @NonNull LlmModelRegistryService registryService;

    public Resolution<ChatCompletionRequest> resolve(@NonNull ChatCompletionRequest request) {
        var changes = new ArrayList<String>();
        var builder = ChatCompletionRequest.builder().from(request);
        var model = request.model();

        // Some Claude models refuse both outright rather than refusing them together, and extended thinking
        // refuses them on any Claude: "temperature may only be set to 1 when thinking is enabled", "top_p must be
        // greater than or equal to 0.95 or unset when thinking is enabled".
        var refusedReason = "%s does not take it".formatted(model);
        var thinkingReason = SamplingParamsNormalizer.thinkingEnabled(request) ? "thinking is on" : null;
        var temperature = request.temperature();
        var topP = request.topP();
        if (temperature != null) {
            var reason = ModelCapabilities.rejectsTemperature(model, registryService) ? refusedReason : thinkingReason;
            if (reason != null) {
                builder.temperature(null);
                temperature = null;
                changes.add("temperature not sent: " + reason);
            }
        }
        if (topP != null) {
            var reason = ModelCapabilities.rejectsTopP(model, registryService) ? refusedReason : thinkingReason;
            if (reason != null) {
                builder.topP(null);
                topP = null;
                changes.add("top_p not sent: " + reason);
            }
        }
        if (temperature != null && topP != null && ModelCapabilities.requiresExclusiveSamplingParams(model)) {
            // Temperature wins, matching LlmProviderAnthropicMapper and Anthropic's own guidance.
            builder.topP(null);
            topP = null;
            changes.add("top_p not sent: %s takes temperature or top_p, not both".formatted(model));
        }

        var params = openAiParams(model);
        if (params.isPresent()) {
            if (temperature != null && isRefused(params.get().temperature())) {
                builder.temperature(null);
                changes.add("temperature not sent: " + refusedReason);
            }
            if (topP != null && isRefused(params.get().topP())) {
                builder.topP(null);
                changes.add("top_p not sent: " + refusedReason);
            }
            if (request.frequencyPenalty() != null && isRefused(params.get().frequencyPenalty())) {
                builder.frequencyPenalty(null);
                changes.add("frequency_penalty not sent: " + refusedReason);
            }
            if (request.presencePenalty() != null && isRefused(params.get().presencePenalty())) {
                builder.presencePenalty(null);
                changes.add("presence_penalty not sent: " + refusedReason);
            }
        }

        return changes.isEmpty()
                ? new Resolution<>(request, List.of())
                : new Resolution<>(builder.build(), List.copyOf(changes));
    }

    // Saved judge rules keep the temperature they were created with, and the frontend strips it only on save.
    public Resolution<LlmAsJudgeModelParameters> resolve(@NonNull LlmAsJudgeModelParameters parameters) {
        if (parameters.temperature() != null
                && openAiParams(parameters.name()).map(ModelParams::temperature).filter(this::isRefused).isPresent()) {
            return new Resolution<>(parameters.toBuilder().temperature(null).build(),
                    List.of("temperature not sent: %s does not take it".formatted(parameters.name())));
        }
        return new Resolution<>(parameters, List.of());
    }

    private Optional<ModelParams> openAiParams(String model) {
        return model == null ? Optional.empty() : llmProviderFactory.openAiParams(model);
    }

    private boolean isRefused(NumberParam param) {
        return param != null && Boolean.FALSE.equals(param.accepted());
    }
}

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
 * for this model". Chat requests and judges both pass through it in {@link ChatCompletionService}, so the providers'
 * mappers and client generators send the sampling params it leaves instead of checking them again.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ParamResolver {

    public record Resolution<T>(@NonNull T request, @NonNull List<ParamChange> changes) {
    }

    /** One setting left out of a request, by its wire name. */
    public record ParamChange(@NonNull String param, @NonNull Reason reason) {

        public enum Reason {
            REFUSED_BY_MODEL,
            THINKING_ENABLED,
            EXCLUSIVE_SAMPLING
        }
    }

    private static final String TEMPERATURE = "temperature";
    private static final String TOP_P = "top_p";

    private final @NonNull LlmProviderFactory llmProviderFactory;
    private final @NonNull LlmModelRegistryService registryService;

    public Resolution<ChatCompletionRequest> resolve(@NonNull ChatCompletionRequest request) {
        var changes = new ArrayList<ParamChange>();
        var builder = ChatCompletionRequest.builder().from(request);
        var model = request.model();

        // Some Claude models refuse both outright rather than refusing them together, and extended thinking
        // refuses them on any Claude: "temperature may only be set to 1 when thinking is enabled", "top_p must be
        // greater than or equal to 0.95 or unset when thinking is enabled".
        var thinkingEnabled = SamplingParamsNormalizer.thinkingEnabled(request);
        var temperature = request.temperature();
        var topP = request.topP();
        if (temperature != null) {
            var reason = ModelCapabilities.rejectsTemperature(model, registryService)
                    ? ParamChange.Reason.REFUSED_BY_MODEL
                    : thinkingEnabled ? ParamChange.Reason.THINKING_ENABLED : null;
            if (reason != null) {
                builder.temperature(null);
                temperature = null;
                changes.add(new ParamChange(TEMPERATURE, reason));
            }
        }
        if (topP != null) {
            var reason = ModelCapabilities.rejectsTopP(model, registryService)
                    ? ParamChange.Reason.REFUSED_BY_MODEL
                    : thinkingEnabled ? ParamChange.Reason.THINKING_ENABLED : null;
            if (reason != null) {
                builder.topP(null);
                topP = null;
                changes.add(new ParamChange(TOP_P, reason));
            }
        }
        if (temperature != null && topP != null && ModelCapabilities.requiresExclusiveSamplingParams(model)) {
            // Temperature wins, matching Anthropic's own guidance.
            builder.topP(null);
            topP = null;
            changes.add(new ParamChange(TOP_P, ParamChange.Reason.EXCLUSIVE_SAMPLING));
        }

        var params = openAiParams(model);
        if (params.isPresent()) {
            if (temperature != null && isRefused(params.get().temperature())) {
                builder.temperature(null);
                changes.add(new ParamChange(TEMPERATURE, ParamChange.Reason.REFUSED_BY_MODEL));
            }
            if (topP != null && isRefused(params.get().topP())) {
                builder.topP(null);
                changes.add(new ParamChange(TOP_P, ParamChange.Reason.REFUSED_BY_MODEL));
            }
            if (request.frequencyPenalty() != null && isRefused(params.get().frequencyPenalty())) {
                builder.frequencyPenalty(null);
                changes.add(new ParamChange("frequency_penalty", ParamChange.Reason.REFUSED_BY_MODEL));
            }
            if (request.presencePenalty() != null && isRefused(params.get().presencePenalty())) {
                builder.presencePenalty(null);
                changes.add(new ParamChange("presence_penalty", ParamChange.Reason.REFUSED_BY_MODEL));
            }
        }

        return changes.isEmpty()
                ? new Resolution<>(request, List.of())
                : new Resolution<>(builder.build(), List.copyOf(changes));
    }

    /**
     * Of these settings a judge sends only temperature. Saved judge rules keep the temperature they were created
     * with, and the frontend strips it only on save, so a rule saved before a model stopped taking it still sends it.
     */
    public Resolution<LlmAsJudgeModelParameters> resolve(@NonNull LlmAsJudgeModelParameters parameters) {
        if (parameters.temperature() == null) {
            return new Resolution<>(parameters, List.of());
        }
        var model = parameters.name();
        var refused = ModelCapabilities.rejectsTemperature(model, registryService)
                || openAiParams(model).map(ModelParams::temperature).filter(this::isRefused).isPresent();
        // Thinking costs a judge its temperature only on a Claude (the exclusive-sampling rule's loose match), unlike
        // the chat path: a judge on another provider's thinking model keeps the temperature it was saved with.
        var claudeThinks = ModelCapabilities.requiresExclusiveSamplingParams(model)
                && SamplingParamsNormalizer.thinkingEnabled(parameters.customParameters());
        if (!refused && !claudeThinks) {
            return new Resolution<>(parameters, List.of());
        }
        var reason = refused ? ParamChange.Reason.REFUSED_BY_MODEL : ParamChange.Reason.THINKING_ENABLED;
        return new Resolution<>(parameters.toBuilder().temperature(null).build(),
                List.of(new ParamChange(TEMPERATURE, reason)));
    }

    private Optional<ModelParams> openAiParams(String model) {
        return model == null ? Optional.empty() : llmProviderFactory.openAiParams(model);
    }

    // A param the catalog leaves out counts as accepted: an OpenAI model keeps every setting its catalog row doesn't
    // refuse. ModelCapabilities.isAccepted reads a missing Claude param the other way on purpose.
    private boolean isRefused(NumberParam param) {
        return param != null && Boolean.FALSE.equals(param.accepted());
    }
}

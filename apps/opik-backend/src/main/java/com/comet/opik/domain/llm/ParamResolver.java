package com.comet.opik.domain.llm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ModelParams;
import com.comet.opik.api.ModelParams.EffortParam;
import com.comet.opik.api.ModelParams.FlagParam;
import com.comet.opik.api.ModelParams.NumberParam;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;

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
            REFUSED_BY_ROUTE,
            THINKING_ENABLED,
            EXCLUSIVE_SAMPLING
        }
    }

    private static final String TEMPERATURE = "temperature";
    private static final String TOP_P = "top_p";
    private static final String REASONING_EFFORT = "reasoning_effort";

    private final @NonNull LlmProviderFactory llmProviderFactory;
    private final @NonNull LlmModelRegistryService registryService;

    public Resolution<ChatCompletionRequest> resolve(@NonNull ChatCompletionRequest request, @Nullable String route) {
        var changes = new ArrayList<ParamChange>();
        var builder = ChatCompletionRequest.builder().from(request);
        var model = request.model();

        // Some Claude models refuse both outright rather than refusing them together, and extended thinking
        // refuses them on any Claude: "temperature may only be set to 1 when thinking is enabled", "top_p must be
        // greater than or equal to 0.95 or unset when thinking is enabled".
        var thinkingEnabled = SamplingParamsNormalizer.thinkingEnabled(request)
                || SamplingParamsNormalizer.effortTurnsOnThinking(model, request.customParameters());
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
        // The name match rather than the catalog's samplingExclusive: it also catches gateway names the catalog can't
        // place, like custom-llm/gw/claude-prod in front of Bedrock, and every name the catalog places matches it.
        if (temperature != null && topP != null && ModelCapabilities.requiresExclusiveSamplingParams(model)) {
            // Temperature wins, matching Anthropic's own guidance.
            builder.topP(null);
            topP = null;
            changes.add(new ParamChange(TOP_P, ParamChange.Reason.EXCLUSIVE_SAMPLING));
        }

        var params = catalogParams(model, route);
        if (params.isPresent()) {
            var modelParams = params.get();
            var effectiveParams = route == null || modelParams.routes() == null
                    ? modelParams
                    : modelParams.routes().getOrDefault(route, modelParams);
            if (temperature != null && isRefused(effectiveParams.temperature())) {
                builder.temperature(null);
                changes.add(new ParamChange(TEMPERATURE, refusalReason(isRefused(modelParams.temperature()))));
            }
            if (topP != null && isRefused(effectiveParams.topP())) {
                builder.topP(null);
                changes.add(new ParamChange(TOP_P, refusalReason(isRefused(modelParams.topP()))));
            }
            if (request.frequencyPenalty() != null && isRefused(effectiveParams.frequencyPenalty())) {
                builder.frequencyPenalty(null);
                changes.add(
                        new ParamChange("frequency_penalty", refusalReason(isRefused(modelParams.frequencyPenalty()))));
            }
            if (request.presencePenalty() != null && isRefused(effectiveParams.presencePenalty())) {
                builder.presencePenalty(null);
                changes.add(
                        new ParamChange("presence_penalty", refusalReason(isRefused(modelParams.presencePenalty()))));
            }
            if (CollectionUtils.isNotEmpty(request.stop()) && isRefused(effectiveParams.stop())) {
                // stop(null) keeps the list from() copied; an empty one is left out of the body (NON_EMPTY).
                builder.stop(List.of());
                changes.add(new ParamChange("stop", refusalReason(isRefused(modelParams.stop()))));
            }
            if (request.seed() != null && isRefused(effectiveParams.seed())) {
                builder.seed(null);
                changes.add(new ParamChange("seed", refusalReason(isRefused(modelParams.seed()))));
            }
        }

        return changes.isEmpty()
                ? new Resolution<>(request, List.of())
                : new Resolution<>(builder.build(), List.copyOf(changes));
    }

    /**
     * Of these settings a judge sends only temperature, plus whatever its custom parameters carry. Saved judge rules
     * keep the temperature they were created with, and the frontend strips it only on save, so a rule saved before a
     * model stopped taking it still sends it.
     */
    public Resolution<LlmAsJudgeModelParameters> resolve(@NonNull LlmAsJudgeModelParameters parameters) {
        var changes = new ArrayList<ParamChange>();
        var builder = parameters.toBuilder();
        var model = parameters.name();
        var catalogParams = catalogParams(model, null);
        if (parameters.temperature() != null) {
            var refused = ModelCapabilities.rejectsTemperature(model, registryService)
                    || catalogParams.map(ModelParams::temperature).filter(this::isRefused).isPresent();
            // Thinking costs a judge its temperature only on a Claude (the exclusive-sampling rule's loose match),
            // unlike the chat path: a judge on another provider's thinking model keeps the temperature it was saved
            // with.
            var claudeThinks = ModelCapabilities.requiresExclusiveSamplingParams(model)
                    && (SamplingParamsNormalizer.thinkingEnabled(parameters.customParameters())
                            || SamplingParamsNormalizer.effortTurnsOnThinking(model, parameters.customParameters()));
            if (refused || claudeThinks) {
                builder.temperature(null);
                changes.add(new ParamChange(TEMPERATURE,
                        refused ? ParamChange.Reason.REFUSED_BY_MODEL : ParamChange.Reason.THINKING_ENABLED));
            }
        }
        // A rule made through the API can carry an effort its model has no levels for, and gpt-4o-mini answers
        // "Unrecognized request argument supplied: reasoning_effort". The rule editor never offers one there.
        if (parameters.customParameters() instanceof ObjectNode customParameters
                && customParameters.has(REASONING_EFFORT)
                && catalogParams.map(ModelParams::effort).filter(this::isRefused).isPresent()) {
            var kept = customParameters.deepCopy();
            kept.remove(REASONING_EFFORT);
            builder.customParameters(kept.isEmpty() ? null : kept);
            changes.add(new ParamChange(REASONING_EFFORT, ParamChange.Reason.REFUSED_BY_MODEL));
        }
        return changes.isEmpty()
                ? new Resolution<>(parameters, List.of())
                : new Resolution<>(builder.build(), List.copyOf(changes));
    }

    // Only OpenAI keys have a route, so an id the registry doesn't list still gets the OpenAI profile there.
    private Optional<ModelParams> catalogParams(String model, String route) {
        if (model == null) {
            return Optional.empty();
        }
        return llmProviderFactory.openAiParams(model)
                .or(() -> llmProviderFactory.geminiParams(model))
                .or(() -> route == null
                        ? Optional.empty()
                        : Optional.ofNullable(registryService.paramsOf(LlmProvider.OPEN_AI.getValue(), model)));
    }

    private static ParamChange.Reason refusalReason(boolean refusedByModel) {
        return refusedByModel ? ParamChange.Reason.REFUSED_BY_MODEL : ParamChange.Reason.REFUSED_BY_ROUTE;
    }

    // A param the catalog leaves out counts as accepted: an OpenAI, Gemini or Vertex AI model keeps every setting its
    // catalog row doesn't refuse. ModelCapabilities.isAccepted reads a missing Claude param the other way on purpose.
    private boolean isRefused(NumberParam param) {
        return param != null && Boolean.FALSE.equals(param.accepted());
    }

    private boolean isRefused(FlagParam param) {
        return param != null && Boolean.FALSE.equals(param.accepted());
    }

    private boolean isRefused(EffortParam param) {
        return param != null && Boolean.FALSE.equals(param.accepted());
    }
}

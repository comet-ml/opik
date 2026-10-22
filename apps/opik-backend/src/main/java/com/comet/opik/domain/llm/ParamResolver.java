package com.comet.opik.domain.llm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ModelParams;
import com.comet.opik.api.ModelParams.EffortParam;
import com.comet.opik.api.ModelParams.FlagParam;
import com.comet.opik.api.ModelParams.NumberParam;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** One setting left out of a request, or moved to a level the model offers, by its wire name. */
    public record ParamChange(@NonNull String param, @NonNull Reason reason) {

        public enum Reason {
            REFUSED_BY_MODEL,
            REFUSED_BY_ROUTE,
            THINKING_ENABLED,
            EXCLUSIVE_SAMPLING,
            LEVEL_NOT_OFFERED
        }
    }

    private static final String TEMPERATURE = "temperature";
    private static final String TOP_P = "top_p";
    private static final String REASONING_EFFORT = "reasoning_effort";
    private static final String REASONING = "reasoning";
    private static final String EFFORT = "effort";
    private static final String THINKING = "thinking";
    private static final String LEVEL = "level";
    private static final String THINKING_LEVEL = THINKING + "." + LEVEL;
    private static final String EFFORT_NONE = "none";
    // From least to most thinking, so the nearest offered level can be found by distance.
    private static final List<String> THINKING_LEVELS = List.of("minimal", "low", "medium", "high");

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
            var samplingAtEffortNone = runsAtEffortNone(effectiveParams, requestedEffort(request));
            if (temperature != null && isRefused(effectiveParams.temperature()) && !samplingAtEffortNone) {
                builder.temperature(null);
                changes.add(new ParamChange(TEMPERATURE, refusalReason(isRefused(modelParams.temperature()))));
            }
            if (topP != null && isRefused(effectiveParams.topP()) && !samplingAtEffortNone) {
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

        if (request.customParameters() != null
                && request.customParameters().get(THINKING) instanceof Map<?, ?> thinking
                && thinking.get(LEVEL) instanceof String level) {
            nearestOfferedThinkingLevel(model, level).ifPresent(offered -> {
                var nextThinking = new LinkedHashMap<String, Object>();
                thinking.forEach((key, value) -> nextThinking.put(String.valueOf(key), value));
                nextThinking.put(LEVEL, offered);
                var nextCustomParameters = new LinkedHashMap<>(request.customParameters());
                nextCustomParameters.put(THINKING, nextThinking);
                builder.customParameters(nextCustomParameters);
                changes.add(new ParamChange(THINKING_LEVEL, ParamChange.Reason.LEVEL_NOT_OFFERED));
            });
        }

        return changes.isEmpty()
                ? new Resolution<>(request, List.of())
                : new Resolution<>(builder.build(), List.copyOf(changes));
    }

    /**
     * Of these settings a judge sends only temperature, plus what its custom parameters carry, a Gemini thinking level
     * and an OpenAI effort among them. Saved judge rules keep the values they were created with, and the frontend
     * strips them only on save, so a rule saved before a model stopped taking one still sends it.
     */
    public Resolution<LlmAsJudgeModelParameters> resolve(@NonNull LlmAsJudgeModelParameters parameters) {
        var model = parameters.name();
        var changes = new ArrayList<ParamChange>();
        var resolved = parameters;
        var catalogParams = catalogParams(model, null);

        var offeredLevel = judgeThinkingLevel(parameters.customParameters())
                .flatMap(level -> nearestOfferedThinkingLevel(model, level));
        if (offeredLevel.isPresent()) {
            var customParameters = (ObjectNode) parameters.customParameters().deepCopy();
            ((ObjectNode) customParameters.get(THINKING)).put(LEVEL, offeredLevel.get());
            resolved = resolved.toBuilder().customParameters(customParameters).build();
            changes.add(new ParamChange(THINKING_LEVEL, ParamChange.Reason.LEVEL_NOT_OFFERED));
        }

        if (parameters.temperature() != null) {
            var effort = judgeEffort(parameters.customParameters());
            var refused = ModelCapabilities.rejectsTemperature(model, registryService)
                    || catalogParams
                            .filter(params -> isRefused(params.temperature()) && !runsAtEffortNone(params, effort))
                            .isPresent();
            // Thinking costs a judge its temperature only on a Claude (the exclusive-sampling rule's loose match),
            // unlike the chat path: a judge on another provider's thinking model keeps the temperature it was saved
            // with.
            var claudeThinks = ModelCapabilities.requiresExclusiveSamplingParams(model)
                    && (SamplingParamsNormalizer.thinkingEnabled(parameters.customParameters())
                            || SamplingParamsNormalizer.effortTurnsOnThinking(model, parameters.customParameters()));
            if (refused || claudeThinks) {
                resolved = resolved.toBuilder().temperature(null).build();
                changes.add(new ParamChange(TEMPERATURE,
                        refused ? ParamChange.Reason.REFUSED_BY_MODEL : ParamChange.Reason.THINKING_ENABLED));
            }
        }

        // A rule made through the API can carry an effort its model has no levels for, and gpt-4o-mini answers
        // "Unrecognized request argument supplied: reasoning_effort". The rule editor never offers one there.
        if (resolved.customParameters() instanceof ObjectNode customParameters
                && catalogParams.map(ModelParams::effort).filter(this::isRefused).isPresent()) {
            var kept = customParameters.deepCopy();
            var removed = removeReasoningEffort(kept);
            if (!removed.isEmpty()) {
                resolved = resolved.toBuilder().customParameters(kept.isEmpty() ? null : kept).build();
                removed.forEach(param -> changes.add(new ParamChange(param, ParamChange.Reason.REFUSED_BY_MODEL)));
            }
        }

        return changes.isEmpty()
                ? new Resolution<>(parameters, List.of())
                : new Resolution<>(resolved, List.copyOf(changes));
    }

    // The Responses-shaped reasoning object carries the same effort, but the rest of it, such as summary, can still
    // apply to a reasoning model whose catalog row lists no effort levels.
    public static List<String> removeReasoningEffort(@NonNull ObjectNode customParameters) {
        var removed = new ArrayList<String>();
        if (customParameters.remove(REASONING_EFFORT) != null) {
            removed.add(REASONING_EFFORT);
        }
        if (customParameters.get(REASONING) instanceof ObjectNode reasoning && reasoning.remove(EFFORT) != null) {
            removed.add(REASONING + "." + EFFORT);
            if (reasoning.isEmpty()) {
                customParameters.remove(REASONING);
            }
        }
        return removed;
    }

    // Only OpenAI keys have a route, so an id the registry doesn't list still gets the OpenAI profile there.
    private Optional<ModelParams> catalogParams(String model, String route) {
        if (model == null) {
            return Optional.empty();
        }
        return llmProviderFactory.catalogParams(model)
                .or(() -> route == null
                        ? Optional.empty()
                        : Optional.ofNullable(registryService.paramsOf(LlmProvider.OPEN_AI.getValue(), model)));
    }

    // The catalog's samplingAtEffortNone: a refused temperature or top_p comes back when the request runs at none, sent
    // or as the model's default. A model without a none level can't run there, whatever the request asks.
    private static boolean runsAtEffortNone(ModelParams params, @Nullable String requestedEffort) {
        var effort = params.effort();
        if (!Boolean.TRUE.equals(params.samplingAtEffortNone()) || effort == null
                || CollectionUtils.isEmpty(effort.levels()) || !effort.levels().contains(EFFORT_NONE)) {
            return false;
        }
        return EFFORT_NONE.equals(StringUtils.isNotBlank(requestedEffort) ? requestedEffort : effort.defaultLevel());
    }

    // An extra body key replaces the typed field of the same name on the way out (CustomParametersOverrides), so it is
    // the effort the model runs at.
    private static String requestedEffort(ChatCompletionRequest request) {
        return request.customParameters() != null
                && request.customParameters().get(REASONING_EFFORT) instanceof String effort
                && StringUtils.isNotBlank(effort)
                        ? effort
                        : request.reasoningEffort();
    }

    private static String judgeEffort(JsonNode customParameters) {
        var effort = customParameters == null ? null : customParameters.get(REASONING_EFFORT);
        return effort != null && effort.isTextual() ? effort.asText() : null;
    }

    private static Optional<String> judgeThinkingLevel(JsonNode customParameters) {
        var thinking = customParameters == null || !customParameters.isObject()
                ? null
                : customParameters.get(THINKING);
        var level = thinking == null || !thinking.isObject() ? null : thinking.get(LEVEL);
        return level != null && level.isTextual() ? Optional.of(level.asText()) : Optional.empty();
    }

    // Gemini answers a level its model doesn't offer with a 400 ("minimal" on gemini-3.8-flash), so it moves to the
    // nearest offered one, the lower on a tie, as the frontend's model switch does: minimal becomes low, not the
    // model's default, which may think the most. Never to none, which turns thinking off instead.
    private Optional<String> nearestOfferedThinkingLevel(String model, String level) {
        var requested = level.toLowerCase(Locale.ROOT);
        var rank = THINKING_LEVELS.indexOf(requested);
        var offered = offeredThinkingLevels(model);
        if (rank < 0 || offered.isEmpty() || offered.contains(requested)) {
            return Optional.empty();
        }
        return offered.stream()
                .filter(THINKING_LEVELS::contains)
                .min(Comparator.<String>comparingInt(candidate -> Math.abs(THINKING_LEVELS.indexOf(candidate) - rank))
                        .thenComparingInt(THINKING_LEVELS::indexOf));
    }

    // The catalog's effort levels are a Gemini model's thinking levels.
    private List<String> offeredThinkingLevels(String model) {
        if (model == null) {
            return List.of();
        }
        return registryService.findModel(model)
                .filter(result -> result.provider() == LlmProvider.GEMINI || result.provider() == LlmProvider.VERTEX_AI)
                .map(result -> ObjectUtils.getIfNull(result.model().params(),
                        () -> registryService.paramsOf(result.provider().getValue(), result.model().id())))
                .map(ModelParams::effort)
                .filter(effort -> !Boolean.FALSE.equals(effort.accepted()) && effort.levels() != null)
                .map(ModelParams.EffortParam::levels)
                .orElse(List.of());
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

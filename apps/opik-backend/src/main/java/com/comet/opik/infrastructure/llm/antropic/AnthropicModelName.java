package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ModelParams;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * This information is taken from <a href="https://docs.anthropic.com/en/docs/about-claude/models">Anthropic docs</a>
 */
@Getter
@RequiredArgsConstructor
public enum AnthropicModelName implements StructuredOutputSupported {
    CLAUDE_SONNET_3_7("claude-3-7-sonnet-20250219"),
    CLAUDE_FABLE_5("claude-fable-5"),
    CLAUDE_FABLE_5_1("claude-fable-5-1"),
    CLAUDE_HAIKU_4_5("claude-haiku-4-5-20251001"),
    CLAUDE_OPUS_4_1("claude-opus-4-1-20250805"),
    CLAUDE_OPUS_4("claude-opus-4-20250514"),
    CLAUDE_OPUS_4_5("claude-opus-4-5-20251101"),
    CLAUDE_OPUS_4_6("claude-opus-4-6"),
    CLAUDE_OPUS_4_7("claude-opus-4-7"),
    CLAUDE_OPUS_4_8("claude-opus-4-8"),
    CLAUDE_OPUS_5("claude-opus-5"),
    CLAUDE_SONNET_4("claude-sonnet-4-20250514"),
    CLAUDE_SONNET_4_5("claude-sonnet-4-5"),
    CLAUDE_SONNET_4_5_20250929("claude-sonnet-4-5-20250929"),
    CLAUDE_SONNET_4_6("claude-sonnet-4-6"),
    CLAUDE_SONNET_5("claude-sonnet-5"),
    CLAUDE_MYTHOS_5("claude-mythos-5"),
    CLAUDE_MYTHOS_5_1("claude-mythos-5-1"),
    CLAUDE_MYTHOS_PREVIEW("claude-mythos-preview"),
    CLAUDE_OPUS_4_6_20260205("claude-opus-4-6-20260205"),
    CLAUDE_OPUS_4_7_20260416("claude-opus-4-7-20260416"),
    CLAUDE_OPUS_5_5("claude-opus-5-5");

    private final String value;

    private static final String PROVIDER = LlmProvider.ANTHROPIC.getValue();

    private static final Set<String> ALL_MODEL_IDS = Arrays.stream(values())
            .map(AnthropicModelName::getValue)
            .collect(Collectors.toUnmodifiableSet());

    /** The Claude ids whose catalog params take temperature. A newly synced id without a row takes none. */
    public static Set<String> samplingCapableModelIds() {
        return FromCatalog.SAMPLING_CAPABLE_MODEL_IDS;
    }

    /** The Claude ids whose catalog params say they run without thinking when a request omits {@code thinking}. */
    public static Set<String> thinkingOffByDefaultModelIds() {
        return FromCatalog.THINKING_OFF_BY_DEFAULT_MODEL_IDS;
    }

    public static Set<String> allModelIds() {
        return ALL_MODEL_IDS;
    }

    static Optional<List<String>> effortLevels(String modelId) {
        return ALL_MODEL_IDS.contains(modelId)
                ? Optional.of(effortLevelsOf(LlmModelRegistryService.bundledParams(PROVIDER, modelId)))
                : Optional.empty();
    }

    /** What an id without a row may send: any level Anthropic names, since Anthropic has the final word on it. */
    static List<String> unlistedModelEffortLevels() {
        return FromCatalog.UNLISTED_MODEL_EFFORT_LEVELS;
    }

    private static List<String> effortLevelsOf(ModelParams params) {
        var effort = params == null ? null : params.effort();
        return effort != null && Boolean.TRUE.equals(effort.accepted()) ? effort.levels() : List.of();
    }

    // Holds what is computed from the catalog, so that loading the enum does not read the catalog.
    private static final class FromCatalog {
        private static final Set<String> SAMPLING_CAPABLE_MODEL_IDS = idsWhere(params -> params.temperature() != null
                && Boolean.TRUE.equals(params.temperature().accepted()));
        private static final Set<String> THINKING_OFF_BY_DEFAULT_MODEL_IDS = idsWhere(
                params -> Boolean.FALSE.equals(params.thinksByDefault()));

        private static final List<String> UNLISTED_MODEL_EFFORT_LEVELS = effortLevelsOf(
                LlmModelRegistryService.bundledParams(PROVIDER, null));

        private static Set<String> idsWhere(Predicate<ModelParams> test) {
            return ALL_MODEL_IDS.stream()
                    .filter(id -> {
                        var params = LlmModelRegistryService.bundledParams(PROVIDER, id);
                        return params != null && test.test(params);
                    })
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean isStructuredOutputSupported() {
        return false;
    }
}

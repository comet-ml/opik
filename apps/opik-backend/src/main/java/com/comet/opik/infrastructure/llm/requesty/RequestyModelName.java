package com.comet.opik.infrastructure.llm.requesty;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * This information is taken from <a href="https://app.requesty.ai/model-library">Requesty model library</a>.
 * Requesty is an OpenAI-compatible LLM router that exposes models using the same bare
 * {@code vendor/model} naming convention as OpenRouter. Because that namespace is already
 * claimed by {@code OpenRouterModelName}, every Requesty model in Opik carries the
 * {@code requesty/} prefix (for example {@code requesty/openai/gpt-4o}) so the two routers can
 * never be confused. The prefix is only meaningful inside Opik: {@link #stripPrefix(String)}
 * removes it before the model id is sent to the Requesty API.
 */
@Slf4j
@RequiredArgsConstructor
public enum RequestyModelName implements StructuredOutputSupported {
    OPENAI_GPT_4O("openai/gpt-4o"),
    OPENAI_GPT_4O_MINI("openai/gpt-4o-mini"),
    OPENAI_GPT_4_1("openai/gpt-4.1"),
    OPENAI_GPT_4_1_MINI("openai/gpt-4.1-mini"),
    OPENAI_GPT_4_1_NANO("openai/gpt-4.1-nano"),
    OPENAI_GPT_5("openai/gpt-5"),
    OPENAI_GPT_5_MINI("openai/gpt-5-mini"),
    OPENAI_GPT_5_NANO("openai/gpt-5-nano"),
    OPENAI_GPT_5_4("openai/gpt-5.4"),
    OPENAI_GPT_5_4_MINI("openai/gpt-5.4-mini"),
    OPENAI_O1("openai/o1"),
    OPENAI_O3("openai/o3"),
    OPENAI_O3_MINI("openai/o3-mini"),
    OPENAI_O4_MINI("openai/o4-mini"),
    ANTHROPIC_CLAUDE_HAIKU_4_5("anthropic/claude-haiku-4-5"),
    ANTHROPIC_CLAUDE_SONNET_4_5("anthropic/claude-sonnet-4-5"),
    ANTHROPIC_CLAUDE_SONNET_4_6("anthropic/claude-sonnet-4-6"),
    ANTHROPIC_CLAUDE_OPUS_4_5("anthropic/claude-opus-4-5"),
    ANTHROPIC_CLAUDE_OPUS_4_6("anthropic/claude-opus-4-6"),
    ANTHROPIC_CLAUDE_SONNET_5("anthropic/claude-sonnet-5"),
    ANTHROPIC_CLAUDE_OPUS_5("anthropic/claude-opus-5"),
    DEEPSEEK_DEEPSEEK_CHAT("deepseek/deepseek-chat"),
    DEEPSEEK_DEEPSEEK_REASONER("deepseek/deepseek-reasoner"),
    GOOGLE_GEMINI_2_5_FLASH("google/gemini-2.5-flash"),
    GOOGLE_GEMINI_2_5_FLASH_LITE("google/gemini-2.5-flash-lite"),
    GOOGLE_GEMINI_2_5_PRO("google/gemini-2.5-pro"),
    MISTRAL_MISTRAL_LARGE_LATEST("mistral/mistral-large-latest"),
    MISTRAL_MISTRAL_MEDIUM_LATEST("mistral/mistral-medium-latest"),
    MISTRAL_MISTRAL_SMALL_LATEST("mistral/mistral-small-latest"),
    XAI_GROK_4("xai/grok-4"),
    XAI_GROK_4_FAST("xai/grok-4-fast"),
    ;

    public static final String REQUESTY_MODEL_PREFIX = LlmProvider.REQUESTY.getValue() + "/";

    private static final String WARNING_UNKNOWN_MODEL = "could not find RequestyModelName with value '{}'";

    private static final Set<RequestyModelName> STRUCTURED_OUTPUT_SUPPORTED_MODELS = Set.of(
            OPENAI_GPT_4O,
            OPENAI_GPT_4O_MINI,
            OPENAI_GPT_4_1,
            OPENAI_GPT_4_1_MINI,
            OPENAI_GPT_4_1_NANO,
            OPENAI_GPT_5,
            OPENAI_GPT_5_MINI,
            OPENAI_GPT_5_NANO,
            OPENAI_GPT_5_4,
            OPENAI_GPT_5_4_MINI,
            OPENAI_O1,
            OPENAI_O3,
            OPENAI_O3_MINI,
            OPENAI_O4_MINI,
            ANTHROPIC_CLAUDE_HAIKU_4_5,
            ANTHROPIC_CLAUDE_SONNET_4_5,
            ANTHROPIC_CLAUDE_SONNET_4_6,
            ANTHROPIC_CLAUDE_OPUS_4_5,
            ANTHROPIC_CLAUDE_OPUS_4_6,
            ANTHROPIC_CLAUDE_SONNET_5,
            ANTHROPIC_CLAUDE_OPUS_5,
            DEEPSEEK_DEEPSEEK_CHAT,
            GOOGLE_GEMINI_2_5_FLASH,
            GOOGLE_GEMINI_2_5_FLASH_LITE,
            GOOGLE_GEMINI_2_5_PRO);

    /**
     * The bare {@code vendor/model} id understood by the Requesty API, without the Opik prefix.
     */
    private final String routerModel;

    @Override
    public boolean isStructuredOutputSupported() {
        return STRUCTURED_OUTPUT_SUPPORTED_MODELS.contains(this);
    }

    /**
     * @return {@code true} when the model id carries the {@code requesty/} prefix, whether or not the
     *         rest of it matches one of the curated values. Requesty serves far more models than this
     *         enum lists, so the prefix alone decides the routing.
     */
    public static boolean isRequestyModel(@NonNull String model) {
        return model.startsWith(REQUESTY_MODEL_PREFIX);
    }

    /**
     * Removes the {@code requesty/} prefix so the bare {@code vendor/model} id can be sent to the
     * router. Model ids without the prefix are returned unchanged.
     */
    public static String stripPrefix(@NonNull String model) {
        return isRequestyModel(model) ? model.substring(REQUESTY_MODEL_PREFIX.length()) : model;
    }

    /**
     * Looks a model up by its prefixed Opik id ({@code requesty/openai/gpt-4o}). The bare router id
     * is accepted too, so callers that already stripped the prefix keep working.
     */
    public static Optional<RequestyModelName> byValue(String value) {
        if (value == null) {
            log.warn(WARNING_UNKNOWN_MODEL, value);
            return Optional.empty();
        }
        String bareModel = stripPrefix(value);
        var response = Arrays.stream(RequestyModelName.values())
                .filter(modelName -> modelName.routerModel.equals(bareModel))
                .findFirst();
        if (response.isEmpty()) {
            log.warn(WARNING_UNKNOWN_MODEL, value);
        }
        return response;
    }

    /**
     * @return the bare {@code vendor/model} id expected by the Requesty API
     */
    public String routerModel() {
        return routerModel;
    }

    /**
     * @return the prefixed id used inside Opik, for example {@code requesty/openai/gpt-4o}
     */
    @Override
    public String toString() {
        return REQUESTY_MODEL_PREFIX + routerModel;
    }
}

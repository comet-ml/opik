package com.comet.opik.infrastructure.llm.requesty;

import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * This information is taken from <a href="https://app.requesty.ai/router/list">Requesty model list</a>.
 * Requesty is an OpenAI-compatible LLM router that exposes models using the same
 * {@code provider/model} naming convention as other gateways.
 */
@Slf4j
@RequiredArgsConstructor
public enum RequestyModelName implements StructuredOutputSupported {
    OPENAI_GPT_4O("openai/gpt-4o"),
    OPENAI_GPT_4O_MINI("openai/gpt-4o-mini"),
    OPENAI_GPT_4_1("openai/gpt-4.1"),
    OPENAI_GPT_4_1_MINI("openai/gpt-4.1-mini"),
    OPENAI_GPT_4_1_NANO("openai/gpt-4.1-nano"),
    OPENAI_O1("openai/o1"),
    OPENAI_O3("openai/o3"),
    OPENAI_O3_MINI("openai/o3-mini"),
    OPENAI_O4_MINI("openai/o4-mini"),
    ANTHROPIC_CLAUDE_3_5_HAIKU("anthropic/claude-3.5-haiku"),
    ANTHROPIC_CLAUDE_3_5_SONNET("anthropic/claude-3.5-sonnet"),
    ANTHROPIC_CLAUDE_3_7_SONNET("anthropic/claude-3.7-sonnet"),
    ANTHROPIC_CLAUDE_OPUS_4("anthropic/claude-opus-4"),
    ANTHROPIC_CLAUDE_SONNET_4("anthropic/claude-sonnet-4"),
    ANTHROPIC_CLAUDE_SONNET_4_5("anthropic/claude-sonnet-4-5"),
    DEEPSEEK_DEEPSEEK_CHAT("deepseek/deepseek-chat"),
    DEEPSEEK_DEEPSEEK_REASONER("deepseek/deepseek-reasoner"),
    GOOGLE_GEMINI_2_0_FLASH("google/gemini-2.0-flash"),
    GOOGLE_GEMINI_2_5_FLASH("google/gemini-2.5-flash"),
    GOOGLE_GEMINI_2_5_PRO("google/gemini-2.5-pro"),
    META_LLAMA_LLAMA_3_1_8B_INSTRUCT("meta-llama/llama-3.1-8b-instruct"),
    META_LLAMA_LLAMA_3_3_70B_INSTRUCT("meta-llama/llama-3.3-70b-instruct"),
    MISTRALAI_MISTRAL_LARGE("mistralai/mistral-large"),
    MISTRALAI_MISTRAL_SMALL("mistralai/mistral-small"),
    ;

    private static final String WARNING_UNKNOWN_MODEL = "could not find RequestyModelName with value '{}'";

    private static final Set<RequestyModelName> STRUCTURED_OUTPUT_SUPPORTED_MODELS = Set.of(
            OPENAI_GPT_4O,
            OPENAI_GPT_4O_MINI,
            OPENAI_GPT_4_1,
            OPENAI_GPT_4_1_MINI,
            OPENAI_GPT_4_1_NANO,
            OPENAI_O1,
            OPENAI_O3,
            OPENAI_O3_MINI,
            OPENAI_O4_MINI,
            ANTHROPIC_CLAUDE_3_5_SONNET,
            ANTHROPIC_CLAUDE_3_7_SONNET,
            ANTHROPIC_CLAUDE_OPUS_4,
            ANTHROPIC_CLAUDE_SONNET_4,
            ANTHROPIC_CLAUDE_SONNET_4_5,
            DEEPSEEK_DEEPSEEK_CHAT,
            GOOGLE_GEMINI_2_0_FLASH,
            GOOGLE_GEMINI_2_5_FLASH,
            GOOGLE_GEMINI_2_5_PRO);

    private final String value;

    @Override
    public boolean isStructuredOutputSupported() {
        return STRUCTURED_OUTPUT_SUPPORTED_MODELS.contains(this);
    }

    public static Optional<RequestyModelName> byValue(String value) {
        var response = Arrays.stream(RequestyModelName.values())
                .filter(modelName -> modelName.value.equals(value))
                .findFirst();
        if (response.isEmpty()) {
            log.warn(WARNING_UNKNOWN_MODEL, value);
        }
        return response;
    }

    @Override
    public String toString() {
        return value;
    }
}

package com.comet.opik.infrastructure.llm.orcarouter;

import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Catalog of OrcaRouter models. Every value carries the dedicated {@code orcarouter/} prefix so the
 * provider can be resolved from the model string without colliding with OpenRouter's shared namespaces
 * (see {@link com.comet.opik.infrastructure.llm.orcarouter.OrcaRouterProvider} for the outgoing transform).
 * Sourced from the public OrcaRouter catalog at <a href="https://www.orcarouter.ai/models">orcarouter.ai/models</a>.
 */
@Slf4j
@RequiredArgsConstructor
public enum OrcaRouterModelName implements StructuredOutputSupported {
    ORCAROUTER_AUTO("orcarouter/auto"),
    ORCAROUTER_ANTHROPIC_CLAUDE_FABLE_5("orcarouter/anthropic/claude-fable-5"),
    ORCAROUTER_ANTHROPIC_CLAUDE_HAIKU_4_5("orcarouter/anthropic/claude-haiku-4.5"),
    ORCAROUTER_ANTHROPIC_CLAUDE_OPUS_4_1("orcarouter/anthropic/claude-opus-4.1"),
    ORCAROUTER_ANTHROPIC_CLAUDE_OPUS_4_5("orcarouter/anthropic/claude-opus-4.5"),
    ORCAROUTER_ANTHROPIC_CLAUDE_OPUS_4_6("orcarouter/anthropic/claude-opus-4.6"),
    ORCAROUTER_ANTHROPIC_CLAUDE_OPUS_4_7("orcarouter/anthropic/claude-opus-4.7"),
    ORCAROUTER_ANTHROPIC_CLAUDE_OPUS_4_8("orcarouter/anthropic/claude-opus-4.8"),
    ORCAROUTER_ANTHROPIC_CLAUDE_SONNET_4_5("orcarouter/anthropic/claude-sonnet-4.5"),
    ORCAROUTER_ANTHROPIC_CLAUDE_SONNET_4_6("orcarouter/anthropic/claude-sonnet-4.6"),
    ORCAROUTER_ANTHROPIC_CLAUDE_SONNET_5("orcarouter/anthropic/claude-sonnet-5"),
    ORCAROUTER_DEEPSEEK_DEEPSEEK_CHAT("orcarouter/deepseek/deepseek-chat"),
    ORCAROUTER_DEEPSEEK_DEEPSEEK_REASONER("orcarouter/deepseek/deepseek-reasoner"),
    ORCAROUTER_DEEPSEEK_DEEPSEEK_V4_FLASH("orcarouter/deepseek/deepseek-v4-flash"),
    ORCAROUTER_DEEPSEEK_DEEPSEEK_V4_PRO("orcarouter/deepseek/deepseek-v4-pro"),
    ORCAROUTER_GOOGLE_GEMINI_2_5_FLASH("orcarouter/google/gemini-2.5-flash"),
    ORCAROUTER_GOOGLE_GEMINI_2_5_FLASH_LITE("orcarouter/google/gemini-2.5-flash-lite"),
    ORCAROUTER_GOOGLE_GEMINI_2_5_PRO("orcarouter/google/gemini-2.5-pro"),
    ORCAROUTER_GOOGLE_GEMINI_3_FLASH_PREVIEW("orcarouter/google/gemini-3-flash-preview"),
    ORCAROUTER_GOOGLE_GEMINI_3_1_FLASH_LITE("orcarouter/google/gemini-3.1-flash-lite"),
    ORCAROUTER_GOOGLE_GEMINI_3_1_FLASH_LITE_PREVIEW("orcarouter/google/gemini-3.1-flash-lite-preview"),
    ORCAROUTER_GOOGLE_GEMINI_3_1_PRO_PREVIEW("orcarouter/google/gemini-3.1-pro-preview"),
    ORCAROUTER_GOOGLE_GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS("orcarouter/google/gemini-3.1-pro-preview-customtools"),
    ORCAROUTER_GOOGLE_GEMINI_3_5_FLASH("orcarouter/google/gemini-3.5-flash"),
    ORCAROUTER_GOOGLE_GEMINI_FLASH_LATEST("orcarouter/google/gemini-flash-latest"),
    ORCAROUTER_GOOGLE_GEMINI_FLASH_LITE_LATEST("orcarouter/google/gemini-flash-lite-latest"),
    ORCAROUTER_GOOGLE_GEMINI_PRO_LATEST("orcarouter/google/gemini-pro-latest"),
    ORCAROUTER_GOOGLE_GEMINI_ROBOTICS_ER_1_6_PREVIEW("orcarouter/google/gemini-robotics-er-1.6-preview"),
    ORCAROUTER_GOOGLE_GEMMA_4_26B_A4B_IT("orcarouter/google/gemma-4-26b-a4b-it"),
    ORCAROUTER_GOOGLE_GEMMA_4_31B_IT("orcarouter/google/gemma-4-31b-it"),
    ORCAROUTER_GROK_GROK_4_3("orcarouter/grok/grok-4.3"),
    ORCAROUTER_GROK_GROK_4_5("orcarouter/grok/grok-4.5"),
    ORCAROUTER_KIMI_KIMI_K2_5("orcarouter/kimi/kimi-k2.5"),
    ORCAROUTER_KIMI_KIMI_K2_6("orcarouter/kimi/kimi-k2.6"),
    ORCAROUTER_KIMI_KIMI_K2_7_CODE("orcarouter/kimi/kimi-k2.7-code"),
    ORCAROUTER_MINIMAX_MINIMAX_M2_5("orcarouter/minimax/minimax-m2.5"),
    ORCAROUTER_MINIMAX_MINIMAX_M2_5_HIGHSPEED("orcarouter/minimax/minimax-m2.5-highspeed"),
    ORCAROUTER_MINIMAX_MINIMAX_M2_7("orcarouter/minimax/minimax-m2.7"),
    ORCAROUTER_MINIMAX_MINIMAX_M2_7_HIGHSPEED("orcarouter/minimax/minimax-m2.7-highspeed"),
    ORCAROUTER_MINIMAX_MINIMAX_M3("orcarouter/minimax/minimax-m3"),
    ORCAROUTER_OBSIDIAN_QWEN3_6_35B_A3B("orcarouter/obsidian/Qwen3.6-35B-A3B"),
    ORCAROUTER_OBSIDIAN_GEMMA_4_26B_A4B("orcarouter/obsidian/gemma-4-26B-A4B"),
    ORCAROUTER_OPENAI_GPT_3_5_TURBO("orcarouter/openai/gpt-3.5-turbo"),
    ORCAROUTER_OPENAI_GPT_3_5_TURBO_0125("orcarouter/openai/gpt-3.5-turbo-0125"),
    ORCAROUTER_OPENAI_GPT_3_5_TURBO_1106("orcarouter/openai/gpt-3.5-turbo-1106"),
    ORCAROUTER_OPENAI_GPT_3_5_TURBO_16K("orcarouter/openai/gpt-3.5-turbo-16k"),
    ORCAROUTER_OPENAI_GPT_4("orcarouter/openai/gpt-4"),
    ORCAROUTER_OPENAI_GPT_4_0613("orcarouter/openai/gpt-4-0613"),
    ORCAROUTER_OPENAI_GPT_4_TURBO("orcarouter/openai/gpt-4-turbo"),
    ORCAROUTER_OPENAI_GPT_4_TURBO_2024_04_09("orcarouter/openai/gpt-4-turbo-2024-04-09"),
    ORCAROUTER_OPENAI_GPT_4_1("orcarouter/openai/gpt-4.1"),
    ORCAROUTER_OPENAI_GPT_4_1_2025_04_14("orcarouter/openai/gpt-4.1-2025-04-14"),
    ORCAROUTER_OPENAI_GPT_4_1_MINI("orcarouter/openai/gpt-4.1-mini"),
    ORCAROUTER_OPENAI_GPT_4_1_MINI_2025_04_14("orcarouter/openai/gpt-4.1-mini-2025-04-14"),
    ORCAROUTER_OPENAI_GPT_4_1_NANO("orcarouter/openai/gpt-4.1-nano"),
    ORCAROUTER_OPENAI_GPT_4_1_NANO_2025_04_14("orcarouter/openai/gpt-4.1-nano-2025-04-14"),
    ORCAROUTER_OPENAI_GPT_4O("orcarouter/openai/gpt-4o"),
    ORCAROUTER_OPENAI_GPT_4O_2024_05_13("orcarouter/openai/gpt-4o-2024-05-13"),
    ORCAROUTER_OPENAI_GPT_4O_2024_08_06("orcarouter/openai/gpt-4o-2024-08-06"),
    ORCAROUTER_OPENAI_GPT_4O_2024_11_20("orcarouter/openai/gpt-4o-2024-11-20"),
    ORCAROUTER_OPENAI_GPT_4O_MINI("orcarouter/openai/gpt-4o-mini"),
    ORCAROUTER_OPENAI_GPT_4O_MINI_2024_07_18("orcarouter/openai/gpt-4o-mini-2024-07-18"),
    ORCAROUTER_OPENAI_GPT_4O_MINI_SEARCH_PREVIEW("orcarouter/openai/gpt-4o-mini-search-preview"),
    ORCAROUTER_OPENAI_GPT_4O_MINI_SEARCH_PREVIEW_2025_03_11("orcarouter/openai/gpt-4o-mini-search-preview-2025-03-11"),
    ORCAROUTER_OPENAI_GPT_4O_SEARCH_PREVIEW("orcarouter/openai/gpt-4o-search-preview"),
    ORCAROUTER_OPENAI_GPT_4O_SEARCH_PREVIEW_2025_03_11("orcarouter/openai/gpt-4o-search-preview-2025-03-11"),
    ORCAROUTER_OPENAI_GPT_5("orcarouter/openai/gpt-5"),
    ORCAROUTER_OPENAI_GPT_5_2025_08_07("orcarouter/openai/gpt-5-2025-08-07"),
    ORCAROUTER_OPENAI_GPT_5_CHAT_LATEST("orcarouter/openai/gpt-5-chat-latest"),
    ORCAROUTER_OPENAI_GPT_5_CODEX("orcarouter/openai/gpt-5-codex"),
    ORCAROUTER_OPENAI_GPT_5_MINI("orcarouter/openai/gpt-5-mini"),
    ORCAROUTER_OPENAI_GPT_5_MINI_2025_08_07("orcarouter/openai/gpt-5-mini-2025-08-07"),
    ORCAROUTER_OPENAI_GPT_5_NANO("orcarouter/openai/gpt-5-nano"),
    ORCAROUTER_OPENAI_GPT_5_NANO_2025_08_07("orcarouter/openai/gpt-5-nano-2025-08-07"),
    ORCAROUTER_OPENAI_GPT_5_PRO("orcarouter/openai/gpt-5-pro"),
    ORCAROUTER_OPENAI_GPT_5_PRO_2025_10_06("orcarouter/openai/gpt-5-pro-2025-10-06"),
    ORCAROUTER_OPENAI_GPT_5_SEARCH_API("orcarouter/openai/gpt-5-search-api"),
    ORCAROUTER_OPENAI_GPT_5_SEARCH_API_2025_10_14("orcarouter/openai/gpt-5-search-api-2025-10-14"),
    ORCAROUTER_OPENAI_GPT_5_1("orcarouter/openai/gpt-5.1"),
    ORCAROUTER_OPENAI_GPT_5_1_2025_11_13("orcarouter/openai/gpt-5.1-2025-11-13"),
    ORCAROUTER_OPENAI_GPT_5_1_CHAT_LATEST("orcarouter/openai/gpt-5.1-chat-latest"),
    ORCAROUTER_OPENAI_GPT_5_1_CODEX("orcarouter/openai/gpt-5.1-codex"),
    ORCAROUTER_OPENAI_GPT_5_1_CODEX_MINI("orcarouter/openai/gpt-5.1-codex-mini"),
    ORCAROUTER_OPENAI_GPT_5_2("orcarouter/openai/gpt-5.2"),
    ORCAROUTER_OPENAI_GPT_5_2_2025_12_11("orcarouter/openai/gpt-5.2-2025-12-11"),
    ORCAROUTER_OPENAI_GPT_5_2_CHAT_LATEST("orcarouter/openai/gpt-5.2-chat-latest"),
    ORCAROUTER_OPENAI_GPT_5_2_CODEX("orcarouter/openai/gpt-5.2-codex"),
    ORCAROUTER_OPENAI_GPT_5_2_PRO("orcarouter/openai/gpt-5.2-pro"),
    ORCAROUTER_OPENAI_GPT_5_2_PRO_2025_12_11("orcarouter/openai/gpt-5.2-pro-2025-12-11"),
    ORCAROUTER_OPENAI_GPT_5_3_CHAT_LATEST("orcarouter/openai/gpt-5.3-chat-latest"),
    ORCAROUTER_OPENAI_GPT_5_3_CODEX("orcarouter/openai/gpt-5.3-codex"),
    ORCAROUTER_OPENAI_GPT_5_4("orcarouter/openai/gpt-5.4"),
    ORCAROUTER_OPENAI_GPT_5_4_2026_03_05("orcarouter/openai/gpt-5.4-2026-03-05"),
    ORCAROUTER_OPENAI_GPT_5_4_MINI("orcarouter/openai/gpt-5.4-mini"),
    ORCAROUTER_OPENAI_GPT_5_4_MINI_2026_03_17("orcarouter/openai/gpt-5.4-mini-2026-03-17"),
    ORCAROUTER_OPENAI_GPT_5_4_NANO("orcarouter/openai/gpt-5.4-nano"),
    ORCAROUTER_OPENAI_GPT_5_4_NANO_2026_03_17("orcarouter/openai/gpt-5.4-nano-2026-03-17"),
    ORCAROUTER_OPENAI_GPT_5_4_PRO("orcarouter/openai/gpt-5.4-pro"),
    ORCAROUTER_OPENAI_GPT_5_4_PRO_2026_03_05("orcarouter/openai/gpt-5.4-pro-2026-03-05"),
    ORCAROUTER_OPENAI_GPT_5_5("orcarouter/openai/gpt-5.5"),
    ORCAROUTER_OPENAI_GPT_5_5_2026_04_23("orcarouter/openai/gpt-5.5-2026-04-23"),
    ORCAROUTER_OPENAI_GPT_5_5_PRO("orcarouter/openai/gpt-5.5-pro"),
    ORCAROUTER_OPENAI_GPT_5_5_PRO_2026_04_23("orcarouter/openai/gpt-5.5-pro-2026-04-23"),
    ORCAROUTER_OPENAI_GPT_5_6_LUNA("orcarouter/openai/gpt-5.6-luna"),
    ORCAROUTER_OPENAI_GPT_5_6_SOL("orcarouter/openai/gpt-5.6-sol"),
    ORCAROUTER_OPENAI_GPT_5_6_TERRA("orcarouter/openai/gpt-5.6-terra"),
    ORCAROUTER_QWEN_QWEN3_MAX("orcarouter/qwen/qwen3-max"),
    ORCAROUTER_QWEN_QWEN3_MAX_PREVIEW("orcarouter/qwen/qwen3-max-preview"),
    ORCAROUTER_QWEN_QWEN3_VL_235B_A22B_INSTRUCT("orcarouter/qwen/qwen3-vl-235b-a22b-instruct"),
    ORCAROUTER_QWEN_QWEN3_VL_235B_A22B_THINKING("orcarouter/qwen/qwen3-vl-235b-a22b-thinking"),
    ORCAROUTER_QWEN_QWEN3_VL_8B_INSTRUCT("orcarouter/qwen/qwen3-vl-8b-instruct"),
    ORCAROUTER_QWEN_QWEN3_VL_8B_THINKING("orcarouter/qwen/qwen3-vl-8b-thinking"),
    ORCAROUTER_QWEN_QWEN3_5_122B_A10B("orcarouter/qwen/qwen3.5-122b-a10b"),
    ORCAROUTER_QWEN_QWEN3_5_27B("orcarouter/qwen/qwen3.5-27b"),
    ORCAROUTER_QWEN_QWEN3_5_35B_A3B("orcarouter/qwen/qwen3.5-35b-a3b"),
    ORCAROUTER_QWEN_QWEN3_5_397B_A17B("orcarouter/qwen/qwen3.5-397b-a17b"),
    ORCAROUTER_QWEN_QWEN3_5_FLASH("orcarouter/qwen/qwen3.5-flash"),
    ORCAROUTER_QWEN_QWEN3_5_FLASH_2026_02_23("orcarouter/qwen/qwen3.5-flash-2026-02-23"),
    ORCAROUTER_QWEN_QWEN3_5_PLUS("orcarouter/qwen/qwen3.5-plus"),
    ORCAROUTER_QWEN_QWEN3_5_PLUS_2026_02_15("orcarouter/qwen/qwen3.5-plus-2026-02-15"),
    ORCAROUTER_QWEN_QWEN3_6_35B_A3B("orcarouter/qwen/qwen3.6-35b-a3b"),
    ORCAROUTER_QWEN_QWEN3_6_FLASH("orcarouter/qwen/qwen3.6-flash"),
    ORCAROUTER_QWEN_QWEN3_6_FLASH_2026_04_16("orcarouter/qwen/qwen3.6-flash-2026-04-16"),
    ORCAROUTER_QWEN_QWEN3_6_PLUS("orcarouter/qwen/qwen3.6-plus"),
    ORCAROUTER_QWEN_QWEN3_6_PLUS_2026_04_02("orcarouter/qwen/qwen3.6-plus-2026-04-02"),
    ORCAROUTER_QWEN_QWEN3_7_MAX("orcarouter/qwen/qwen3.7-max"),
    ORCAROUTER_QWEN_QWEN3_7_MAX_2026_05_20("orcarouter/qwen/qwen3.7-max-2026-05-20"),
    ORCAROUTER_QWEN_QWEN3_7_PLUS("orcarouter/qwen/qwen3.7-plus"),
    ORCAROUTER_TENCENT_HUNYUAN_A13B_INSTRUCT("orcarouter/tencent/Hunyuan-A13B-Instruct"),
    ORCAROUTER_TENCENT_HY3_PREVIEW("orcarouter/tencent/Hy3-preview"),
    ORCAROUTER_Z_AI_GLM_4_5("orcarouter/z-ai/glm-4.5"),
    ORCAROUTER_Z_AI_GLM_4_5_AIR("orcarouter/z-ai/glm-4.5-air"),
    ORCAROUTER_Z_AI_GLM_4_6("orcarouter/z-ai/glm-4.6"),
    ORCAROUTER_Z_AI_GLM_4_7("orcarouter/z-ai/glm-4.7"),
    ORCAROUTER_Z_AI_GLM_5("orcarouter/z-ai/glm-5"),
    ORCAROUTER_Z_AI_GLM_5_1("orcarouter/z-ai/glm-5.1"),
    ORCAROUTER_Z_AI_GLM_5_2("orcarouter/z-ai/glm-5.2");

    private static final String WARNING_UNKNOWN_MODEL = "could not find OrcaRouterModelName with value '{}'";

    private static final Set<OrcaRouterModelName> STRUCTURED_OUTPUT_SUPPORTED_MODELS = Set.of(
            ORCAROUTER_AUTO,
            ORCAROUTER_OPENAI_GPT_4O,
            ORCAROUTER_OPENAI_GPT_4O_MINI,
            ORCAROUTER_OPENAI_GPT_5_5,
            ORCAROUTER_ANTHROPIC_CLAUDE_OPUS_4_8,
            ORCAROUTER_ANTHROPIC_CLAUDE_SONNET_4_6,
            ORCAROUTER_GOOGLE_GEMINI_3_5_FLASH,
            ORCAROUTER_DEEPSEEK_DEEPSEEK_V4_PRO,
            ORCAROUTER_QWEN_QWEN3_7_MAX);

    private final String value;

    @Override
    public boolean isStructuredOutputSupported() {
        return STRUCTURED_OUTPUT_SUPPORTED_MODELS.contains(this);
    }

    public static Optional<OrcaRouterModelName> byValue(String value) {
        var response = Arrays.stream(OrcaRouterModelName.values())
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

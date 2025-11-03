package com.comet.opik.domain.llm;

import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Explicit mappings from provider enum values to canonical keys in model_prices_and_context_window.json
 *
 * This provides deterministic, maintainable model resolution with zero magic.
 * No suffix matching, no algorithms - just explicit 1:1 or N:1 mappings.
 */
@Slf4j
@UtilityClass
public class ModelNameMapper {

    private static final Map<String, String> ENUM_TO_CANONICAL = buildMappings();

    /**
     * Build explicit mappings from enum values to canonical JSON keys.
     *
     * Rules:
     * - Key: normalized enum value (lowercase, trimmed)
     * - Value: exact key from model_prices_and_context_window.json (preserve original casing)
     * - Multiple enum values can map to same canonical key (e.g., :free variants)
     */
    private static Map<String, String> buildMappings() {
        var mappings = new HashMap<String, String>();

        // ========== OpenRouter: Qwen Vision Models ==========

        // Qwen 2.5 VL 32B Instruct (maps to DeepInfra canonical key)
        addMapping(mappings, OpenRouterModelName.QWEN_QWEN2_5_VL_32B_INSTRUCT,
                "deepinfra/Qwen/Qwen2.5-VL-32B-Instruct");
        addMapping(mappings, OpenRouterModelName.QWEN_QWEN2_5_VL_32B_INSTRUCT_FREE,
                "deepinfra/Qwen/Qwen2.5-VL-32B-Instruct");

        // Qwen 2.5 VL 72B Instruct
        addMapping(mappings, OpenRouterModelName.QWEN_QWEN2_5_VL_72B_INSTRUCT,
                "deepinfra/Qwen/Qwen2.5-VL-72B-Instruct");
        addMapping(mappings, OpenRouterModelName.QWEN_QWEN2_5_VL_72B_INSTRUCT_FREE,
                "deepinfra/Qwen/Qwen2.5-VL-72B-Instruct");

        // ========== OpenRouter: Qwen Text Models ==========

        // Qwen 2.5 7B Instruct
        addMapping(mappings, OpenRouterModelName.QWEN_QWEN_2_5_7B_INSTRUCT,
                "qwen/qwen-2.5-7b-instruct");

        // Qwen 2.5 72B Instruct
        addMapping(mappings, OpenRouterModelName.QWEN_QWEN_2_5_72B_INSTRUCT,
                "qwen/qwen-2.5-72b-instruct");
        addMapping(mappings, OpenRouterModelName.QWEN_QWEN_2_5_72B_INSTRUCT_FREE,
                "qwen/qwen-2.5-72b-instruct");

        // ========== OpenRouter: Anthropic Claude Models ==========

        addMapping(mappings, OpenRouterModelName.ANTHROPIC_CLAUDE_3_HAIKU,
                "anthropic/claude-3-haiku");
        addMapping(mappings, OpenRouterModelName.ANTHROPIC_CLAUDE_3_OPUS,
                "anthropic/claude-3-opus");
        addMapping(mappings, OpenRouterModelName.ANTHROPIC_CLAUDE_3_5_HAIKU,
                "anthropic/claude-3.5-haiku");
        addMapping(mappings, OpenRouterModelName.ANTHROPIC_CLAUDE_3_5_HAIKU_20241022,
                "anthropic/claude-3.5-haiku-20241022");
        addMapping(mappings, OpenRouterModelName.ANTHROPIC_CLAUDE_3_5_SONNET,
                "anthropic/claude-3.5-sonnet");
        addMapping(mappings, OpenRouterModelName.ANTHROPIC_CLAUDE_3_5_SONNET_20240620,
                "anthropic/claude-3.5-sonnet-20240620");
        addMapping(mappings, OpenRouterModelName.ANTHROPIC_CLAUDE_3_7_SONNET,
                "anthropic/claude-3.7-sonnet");
        addMapping(mappings, OpenRouterModelName.ANTHROPIC_CLAUDE_OPUS_4,
                "anthropic/claude-opus-4");

        // ========== OpenRouter: OpenAI GPT Models ==========

        addMapping(mappings, OpenRouterModelName.OPENAI_GPT_4O,
                "gpt-4o");
        addMapping(mappings, OpenRouterModelName.OPENAI_GPT_4O_MINI,
                "gpt-4o-mini");
        addMapping(mappings, OpenRouterModelName.OPENAI_GPT_4O_2024_08_06,
                "gpt-4o-2024-08-06");
        addMapping(mappings, OpenRouterModelName.OPENAI_GPT_4O_2024_11_20,
                "gpt-4o-2024-11-20");
        addMapping(mappings, OpenRouterModelName.OPENAI_GPT_4_TURBO,
                "gpt-4-turbo");
        addMapping(mappings, OpenRouterModelName.OPENAI_GPT_4,
                "gpt-4");
        addMapping(mappings, OpenRouterModelName.OPENAI_O1,
                "o1");
        addMapping(mappings, OpenRouterModelName.OPENAI_O1_MINI,
                "o1-mini");
        addMapping(mappings, OpenRouterModelName.OPENAI_O3,
                "o3");
        addMapping(mappings, OpenRouterModelName.OPENAI_O3_MINI,
                "o3-mini");

        // ========== OpenRouter: Google Models ==========

        addMapping(mappings, OpenRouterModelName.GOOGLE_GEMINI_2_5_FLASH,
                "google/gemini-2.5-flash");
        addMapping(mappings, OpenRouterModelName.GOOGLE_GEMINI_2_5_PRO,
                "google/gemini-2.5-pro");

        // ========== DeepInfra Models ==========
        // TODO: Add DeepInfra enum mappings when enum is created

        // ========== Other Providers ==========
        // TODO: Add additional provider mappings as needed

        log.info("Initialized ModelNameMapper with '{}' explicit model mappings", mappings.size());
        return Collections.unmodifiableMap(mappings);
    }

    /**
     * Helper to add mapping with normalized enum value as key
     */
    private static void addMapping(Map<String, String> mappings, OpenRouterModelName enumValue,
            String canonicalKey) {
        String normalized = normalize(enumValue.toString());

        // Detect duplicates (should not happen in well-formed mappings)
        if (mappings.containsKey(normalized)) {
            String existing = mappings.get(normalized);
            if (!existing.equals(canonicalKey)) {
                log.warn("Duplicate mapping detected: '{}' already maps to '{}', attempted to map to '{}'",
                        normalized, existing, canonicalKey);
            }
        }

        mappings.put(normalized, canonicalKey);
    }

    /**
     * Resolve model name to canonical JSON key.
     *
     * @param modelName Model name from request (e.g., "qwen/qwen2.5-vl-32b-instruct")
     * @return Canonical key from JSON, or normalized input if no mapping exists
     */
    public static String resolveCanonicalKey(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return "";
        }

        String normalized = normalize(modelName);

        // Check explicit enum mapping
        String canonical = ENUM_TO_CANONICAL.get(normalized);
        if (canonical != null) {
            return canonical;
        }

        // Fallback for colon suffixes (e.g., "gpt-4:free" -> try "gpt-4")
        int colonIndex = normalized.indexOf(':');
        if (colonIndex > 0) {
            String withoutColon = normalized.substring(0, colonIndex);
            canonical = ENUM_TO_CANONICAL.get(withoutColon);
            if (canonical != null) {
                return canonical;
            }
            // If no enum mapping, return without colon for direct JSON lookup
            return withoutColon;
        }

        // Fallback: return normalized input (for models not in enums)
        // This handles direct JSON keys like "gpt-4", "claude-3-opus", etc.
        return normalized;
    }

    /**
     * Normalize model name: trim and lowercase
     */
    public static String normalize(String modelName) {
        return modelName == null ? "" : modelName.trim().toLowerCase();
    }

    /**
     * Check if model is explicitly mapped via enum
     */
    public static boolean isMapped(String modelName) {
        return ENUM_TO_CANONICAL.containsKey(normalize(modelName));
    }
}

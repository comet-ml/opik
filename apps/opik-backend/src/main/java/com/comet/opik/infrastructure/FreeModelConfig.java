package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

@Getter
@Setter
@ToString
public class FreeModelConfig {

    /**
     * Fixed UUID for the free model provider. This is a virtual provider that doesn't exist in the database.
     */
    public static final UUID FREE_MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Fixed model name for the free model provider. This must remain constant because automation rules
     * store this model name in their configuration. Changing it would break existing rules.
     */
    public static final String FREE_MODEL = "opik-free-model";

    /**
     * Minimum temperature required by OpenAI reasoning models (GPT-5, O-series).
     * These models reject temperature < 1.0, so we clamp to this value when needed.
     */
    public static final double OPENAI_REASONING_MODEL_MIN_TEMPERATURE = 1.0;

    /**
     * Whether the free model provider is enabled in configuration.
     * Note: Use {@link #isEnabled()} to check if the provider is actually usable,
     * as it also validates that required fields are configured.
     */
    @JsonProperty
    private boolean enabled = false;

    /**
     * The actual model name sent to the AI server (e.g., "gpt-4o-mini").
     * Required when enabled=true. If empty, the provider will not be registered.
     */
    @JsonProperty
    private String actualModel = "";

    /**
     * Provider name stored in spans for cost tracking (e.g., "openai").
     * Required when enabled=true. If empty, the provider will not be registered.
     */
    @JsonProperty
    private String spanProvider = "";

    /**
     * Base URL for the OpenAI-compatible endpoint (e.g., "https://api.openai.com/v1").
     * Required when enabled=true. If empty, the provider will not be registered.
     */
    @JsonProperty
    private String baseUrl = "";

    /**
     * API key for the endpoint. Optional for auth-less endpoints.
     */
    @JsonProperty
    @ToString.Exclude
    private String apiKey = "";

    /**
     * Returns true if the free model provider is enabled AND all required fields are configured.
     * This ensures fail-fast behavior - the provider won't register with missing configuration.
     */
    public boolean isEnabled() {
        return enabled
                && actualModel != null && !actualModel.isBlank()
                && spanProvider != null && !spanProvider.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * Returns the fixed model name. This is not configurable to ensure backward compatibility
     * with stored automation rules.
     */
    public String getModel() {
        return FREE_MODEL;
    }

    /**
     * Checks if the actual model is a reasoning model that requires temperature >= 1.0.
     * Reasoning models include GPT-5 family (gpt-5, gpt-5-mini, gpt-5-nano, gpt-5.1, gpt-5.2, etc.)
     * and O-series models (o1, o3, o4-mini, etc.).
     * <p>
     * This is needed because existing automation rules created when the free model was gpt-4o-mini
     * have temperature=0.0 saved in the database, which was valid for gpt-4o-mini but causes API
     * errors with reasoning models that reject temperature < 1.0.
     *
     * @return true if the actual model is a reasoning model, false otherwise
     */
    public boolean isReasoningModel() {
        if (actualModel == null || actualModel.isBlank()) {
            return false;
        }

        String modelLower = actualModel.toLowerCase();
        return modelLower.startsWith("gpt-5") || modelLower.startsWith("o1")
                || modelLower.startsWith("o3") || modelLower.startsWith("o4");
    }
}

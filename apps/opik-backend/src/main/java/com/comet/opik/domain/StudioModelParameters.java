package com.comet.opik.domain;

import com.comet.opik.api.OptimizationStudioConfig;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Spliterators;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

/**
 * The model parameters a Studio optimization may carry: those the Studio runner passes on to the LLM gateway. Any
 * other is refused when the optimization is saved, by name, never by value.
 */
@UtilityClass
public class StudioModelParameters {

    /**
     * The LiteLLM parameters the Studio runner keeps on a gateway call, and drops the others. Keep in step with
     * {@code _ALLOWED_PARAMS} in {@code apps/opik-python-backend/src/opik_backend/jobs/optimizer_runner.py}.
     */
    static final Set<String> ALLOWED = Set.of(
            "model", "messages", "tools", "tool_choice", "parallel_tool_calls", "response_format", "metadata",
            "num_retries", "timeout", "stream", "extra_headers", "n", "seed", "logprobs", "top_logprobs",
            "temperature", "top_p", "max_tokens", "max_completion_tokens", "stop", "presence_penalty",
            "frequency_penalty", "reasoning_effort", "custom_parameters",
            "top_k", "min_p", "top_a", "repetition_penalty",
            "user", "safety_identifier", "logit_bias", "verbosity", "prediction", "stream_options", "thinking");

    private static final String CUSTOM_PARAMETERS = "custom_parameters";

    /**
     * Keys of the provider request body that pick a destination, a credential or a fallback model on the servers
     * that read them from the body, LiteLLM's proxy among them. Compared in lower case, along with the suffixes below.
     */
    static final Set<String> DESTINATION_KEYS = Set.of(
            "base_url", "api_base", "api_key", "api_version", "headers", "extra_headers", "extra_body",
            "model_list", "fallbacks");

    private static final List<String> DESTINATION_KEY_SUFFIXES = List.of("_base_url", "_api_base", "_api_key");

    /** Refuses, with a 400, model parameters the runner wouldn't pass on, or a body key picking a destination. */
    public static void validate(@NonNull OptimizationStudioConfig config) {
        validate("llm_model.parameters", config.llmModel().parameters());
        var optimizerParameters = config.optimizer().parameters();
        if (optimizerParameters != null && optimizerParameters.isObject()) {
            validate("optimizer.parameters.model_parameters", optimizerParameters.get("model_parameters"));
        }
    }

    private static void validate(String path, @Nullable JsonNode parameters) {
        if (parameters == null || parameters.isNull()) {
            return;
        }
        if (!parameters.isObject()) {
            throw new BadRequestException("%s must be an object".formatted(path));
        }
        reject(parameters, key -> !ALLOWED.contains(key),
                "%s has keys the Studio runner doesn't pass on".formatted(path));
        var customParameters = parameters.get(CUSTOM_PARAMETERS);
        if (customParameters != null && customParameters.isObject()) {
            reject(customParameters, StudioModelParameters::isDestinationKey,
                    "%s.%s can't pick a destination or a credential".formatted(path, CUSTOM_PARAMETERS));
        }
    }

    /** Throws when a key of {@code parameters} is refused: {@code message}, then the refused keys. */
    private static void reject(JsonNode parameters, Predicate<String> refused, String message) {
        var refusedKeys = StreamSupport.stream(Spliterators.spliteratorUnknownSize(parameters.fieldNames(), 0), false)
                .filter(refused)
                .sorted()
                .toList();
        if (!refusedKeys.isEmpty()) {
            throw new BadRequestException("%s, keys '%s'".formatted(message, refusedKeys));
        }
    }

    private static boolean isDestinationKey(String key) {
        var lowerCase = key.toLowerCase(Locale.ROOT);
        return DESTINATION_KEYS.contains(lowerCase) || DESTINATION_KEY_SUFFIXES.stream().anyMatch(lowerCase::endsWith);
    }
}

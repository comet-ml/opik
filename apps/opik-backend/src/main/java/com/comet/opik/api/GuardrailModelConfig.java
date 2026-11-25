package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * What a guardrail model's classifier is asked. Stored as the model's config JSON, as the online evaluators store theirs,
 * so a later setting becomes a field here with no migration.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GuardrailModelConfig(
        @Schema(description = "The task the classifier judges each text with, asked as a yes/no question, yes meaning the text violates; examples, if any, go here. Every check uses the current prompt, so editing it needs no retrain") @NotBlank @Size(max = PROMPT_MAX_LENGTH, message = "cannot exceed {max} characters") String prompt) {

    // About 2,500 tokens: room for a policy and its examples, while most of the decision pool's context stays for the
    // text under check.
    public static final int PROMPT_MAX_LENGTH = 10_000;
}

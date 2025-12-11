package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreatePromptVersion(@JsonView( {
        PromptVersion.View.Detail.class}) @NotBlank String name,
        @JsonView({PromptVersion.View.Detail.class}) @NotNull @Valid PromptVersion version,
        @JsonView({
                PromptVersion.View.Detail.class}) @Schema(description = "Template structure for the prompt: 'text' or 'chat'. Note: This field is only used when creating a new prompt. If a prompt with the given name already exists, this field is ignored and the existing prompt's template structure is used. Template structure is immutable after prompt creation.", defaultValue = "text") TemplateStructure templateStructure){

    /**
     * Returns the template structure, defaulting to TEXT if not provided.
     * This ensures backwards compatibility for clients that don't send this field
     * (e.g., TypeScript SDK which doesn't support ChatPrompt yet).
     */
    @Override
    public TemplateStructure templateStructure() {
        return templateStructure == null ? TemplateStructure.TEXT : templateStructure;
    }
}

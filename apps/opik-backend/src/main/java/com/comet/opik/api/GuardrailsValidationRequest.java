package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

/**
 * One text and the checks to run on it. The envelope (a text plus a type and settings per check) matches the v1
 * validations contract of the Python guardrails service; the settings of each type are new.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GuardrailsValidationRequest(
        @Schema(description = "The text to check, at most 16384 characters as the decision service counts them: on the text's JSON encoding, so the two quotes count, a quote, backslash or line break counts as 2, and any other control or non-ASCII character as 6 (an emoji as 12). A longer text is rejected rather than cut.") @NotBlank String text,
        @Schema(description = "The checks to run. Together they must fit in 64 questions to the decision service, asked in up to two calls of 32: each PII class and each topic list is one question, prompt injection is one") @NotNull @Size(min = 1, max = 32) @Valid List<@NotNull @Valid ValidationDescriptor> validations) {

    /** The decision service's realtime limit on the text, which it applies to the text's JSON encoding. */
    public static final int MAX_TEXT_LENGTH = 16_384;

    @JsonIgnore
    @AssertTrue(message = "text must be at most 16384 characters as the decision service counts them") public boolean isTextWithinLimit() {
        // The encoding is never shorter than the text plus its quotes, so a long text is rejected without a scan.
        return text == null || (text.length() <= MAX_TEXT_LENGTH - 2 && encodedLength(text) <= MAX_TEXT_LENGTH);
    }

    /** The length of Python's json.dumps of the text, with its default of escaping everything outside ASCII. */
    private static int encodedLength(String text) {
        return 2 + text.chars()
                .map(character -> switch (character) {
                    case '"', '\\', '\b', '\f', '\n', '\r', '\t' -> 2;
                    default -> character >= ' ' && character <= '~' ? 1 : 6;
                })
                .sum();
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ValidationDescriptor(
            @NotNull GuardrailType type,
            @Schema(implementation = JsonNode.class, ref = "JsonNode", description = "User settings only: PII entities (each supported class at most once), or allowed_topics and restricted_topics (at least one; each up to 254 distinct labels of up to 256 characters, not 'none of these', and no topic in both). Thresholds are set by the server and rejected here.") @NotNull JsonNode config) {

        public ValidationDescriptor {
            // Jackson reads an explicit "config": null as a NullNode, which @NotNull would let through.
            if (config != null && config.isNull()) {
                config = null;
            }
        }
    }
}

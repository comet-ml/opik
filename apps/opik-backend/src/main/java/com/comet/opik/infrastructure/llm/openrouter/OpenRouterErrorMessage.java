package com.comet.opik.infrastructure.llm.openrouter;

import com.comet.opik.domain.llm.ProviderErrorMessageValidator.DocumentedErrors;
import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.llm.openrouter.OpenRouterErrorMessage.OpenRouterError;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenRouterErrorMessage(
        OpenRouterError error) implements LlmProviderError<OpenRouterError> {

    private static final int MAX_UPSTREAM_REASON_LENGTH = 500;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenRouterError(@NotBlank String message, @NotNull Integer code, JsonNode metadata) {
    }

    public ErrorMessage toErrorMessage() {
        return new ErrorMessage(error.code(), upstreamReason().map(this::withSource).orElse(error.message()));
    }

    public boolean isDocumented() {
        return DocumentedErrors.OPENROUTER.documents(error.code());
    }

    /**
     * OpenRouter answers "Provider returned error" when the upstream fails, and keeps the upstream's own body in
     * metadata.raw: usually that provider's JSON error as a string, sometimes plain text, which isn't passed on.
     */
    private Optional<String> upstreamReason() {
        if (error.metadata() == null) {
            return Optional.empty();
        }
        return text(error.metadata().path("raw"))
                .flatMap(raw -> parseObject(raw).flatMap(OpenRouterErrorMessage::reasonOf))
                .map(StringUtils::normalizeSpace)
                .map(reason -> StringUtils.abbreviate(reason, MAX_UPSTREAM_REASON_LENGTH));
    }

    private String withSource(String reason) {
        return text(error.metadata().path("provider_name"))
                .or(() -> Optional.ofNullable(error.message()).filter(StringUtils::isNotBlank))
                .map(source -> source + ": " + reason)
                .orElse(reason);
    }

    private static Optional<JsonNode> parseObject(String text) {
        try {
            return Optional.of(JsonUtils.getJsonNodeFromString(text)).filter(JsonNode::isObject);
        } catch (UncheckedIOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> reasonOf(JsonNode upstreamError) {
        return Stream.of(
                upstreamError.path("error").path("message"),
                upstreamError.path("error"),
                upstreamError.path("message"),
                upstreamError.path("detail"))
                .map(OpenRouterErrorMessage::text)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<String> text(JsonNode node) {
        return Optional.of(node)
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .filter(StringUtils::isNotBlank);
    }
}

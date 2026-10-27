package com.comet.opik.domain.llm;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Set;

import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;

/**
 * The one place that decides which provider error text reaches a caller: a parsed provider error message is kept
 * only when its envelope names an error the provider documents and it reads as a short plain message. Anything else
 * becomes {@link ChatCompletionService#UNEXPECTED_ERROR_CALLING_LLM_PROVIDER}.
 */
@Singleton
public class ProviderErrorMessageValidator {

    /** The error identifiers each provider documents for its error envelope. */
    public enum DocumentedErrors {
        // https://developers.openai.com/api/docs/guides/error-codes, .../guides/agents-api/errors; error.type or code
        OPENAI(Set.of(
                "invalid_request_error", "authentication_error", "not_found_error", "conflict_error",
                "rate_limit_error", "insufficient_quota", "service_unavailable_error", "server_error",
                "internal_error", "invalid_request", "invalid_api_key", "unauthorized", "forbidden",
                "model_not_found", "context_length_exceeded", "unsupported_parameter", "rate_limit_exceeded",
                "slow_down", "credit_balance_exhausted", "usage_limit_exceeded",
                "organization_spend_limit_exceeded", "project_spend_limit_exceeded",
                "organization_usage_limit_exceeded", "server_is_overloaded", "request_timeout")),
        // https://platform.claude.com/docs/en/api/errors; error.type
        ANTHROPIC(Set.of(
                "invalid_request_error", "authentication_error", "billing_error", "permission_error",
                "not_found_error", "conflict_error", "request_too_large", "rate_limit_error", "api_error",
                "timeout_error", "overloaded_error")),
        // https://docs.cloud.google.com/vertex-ai/generative-ai/docs/model-reference/api-errors; error.status
        GOOGLE(Set.of(
                "INVALID_ARGUMENT", "FAILED_PRECONDITION", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND",
                "RESOURCE_EXHAUSTED", "CANCELLED", "UNKNOWN", "INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED")),
        // https://openrouter.ai/docs/api-reference/errors; error.code
        OPENROUTER(Set.of(
                "400", "401", "402", "403", "404", "408", "412", "413", "422", "429", "500", "502", "503", "504"));

        private final Set<String> identifiers;

        DocumentedErrors(Set<String> identifiers) {
            this.identifiers = identifiers;
        }

        public boolean documents(Object identifier) {
            return identifier != null && identifiers.contains(identifier.toString());
        }
    }

    private final int maxLength;

    @Inject
    public ProviderErrorMessageValidator(@NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        this.maxLength = config.getProviderErrorMessageMaxLength();
    }

    /** The provider's status with none of its text, for an envelope that names no documented error. */
    public static ErrorMessage withoutProviderText(@NonNull ErrorMessage providerError) {
        return new ErrorMessage(providerError.getCode(), UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
    }

    public ErrorMessage validate(@NonNull ErrorMessage providerError) {
        var message = isPlainMessage(providerError.getMessage())
                ? providerError.getMessage()
                : UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
        var details = isPlainMessage(providerError.getDetails()) ? providerError.getDetails() : null;
        return new ErrorMessage(providerError.getCode(), message, details);
    }

    private boolean isPlainMessage(String text) {
        return StringUtils.isNotBlank(text)
                && text.length() <= maxLength
                && text.codePoints().noneMatch(ProviderErrorMessageValidator::isDisallowed);
    }

    private static boolean isDisallowed(int codePoint) {
        return codePoint == '<' || codePoint == '>'
                || Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.LINE_SEPARATOR
                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR;
    }
}

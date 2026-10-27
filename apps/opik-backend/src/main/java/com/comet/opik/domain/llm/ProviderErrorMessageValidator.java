package com.comet.opik.domain.llm;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * The one place that decides which provider error text reaches a caller: a parsed provider error message is kept
 * only when it reads as a short plain message, and anything else is reduced to the status the provider answered.
 */
@Singleton
public class ProviderErrorMessageValidator {

    private static final String STATUS_ONLY = "provider responded with HTTP %s";

    private final int maxLength;

    @Inject
    public ProviderErrorMessageValidator(@NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        this.maxLength = config.getProviderErrorMessageMaxLength();
    }

    public static String statusOnly(int status) {
        return STATUS_ONLY.formatted(status);
    }

    /**
     * @param providerStatus the status the provider answered, which a mapper may have replaced in the error's code
     */
    public ErrorMessage validate(@NonNull ErrorMessage providerError, int providerStatus) {
        var message = isPlainMessage(providerError.getMessage())
                ? providerError.getMessage()
                : statusOnly(providerStatus);
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

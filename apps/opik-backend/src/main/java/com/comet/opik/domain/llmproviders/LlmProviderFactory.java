package com.comet.opik.domain.llmproviders;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

@Singleton
public class LlmProviderFactory {
    private final LlmProviderClientConfig llmProviderClientConfig;
    private final LlmProviderApiKeyService llmProviderApiKeyService;

    @Inject
    public LlmProviderFactory(
            @NonNull @Config LlmProviderClientConfig llmProviderClientConfig,
            @NonNull LlmProviderApiKeyService llmProviderApiKeyService) {
        this.llmProviderApiKeyService = llmProviderApiKeyService;
        this.llmProviderClientConfig = llmProviderClientConfig;
    }

    public LlmProviderService getService(@NonNull String workspaceId, @NonNull String model) {
        var llmProvider = getLlmProvider(model);
        var apiKey = EncryptionUtils.decrypt(getEncryptedApiKey(workspaceId, llmProvider));

        if (llmProvider == LlmProvider.OPEN_AI) {
            return new OpenAi(llmProviderClientConfig, apiKey);
        }

        throw new IllegalArgumentException("not supported provider " + llmProvider);
    }

    /**
     * The agreed requirement is to resolve the LLM provider and its API key based on the model.
     * Currently, only OPEN AI is supported, so model param is ignored.
     * No further validation is needed on the model, as it's just forwarded in the OPEN AI request and will be rejected
     * if not valid.
     */
    private LlmProvider getLlmProvider(String model) {
        return LlmProvider.OPEN_AI;
    }

    /**
     * Finding API keys isn't paginated at the moment, since only OPEN AI is supported.
     * Even in the future, the number of supported LLM providers per workspace is going to be very low.
     */
    private String getEncryptedApiKey(String workspaceId, LlmProvider llmProvider) {
        return llmProviderApiKeyService.find(workspaceId).content().stream()
                .filter(providerApiKey -> llmProvider.equals(providerApiKey.provider()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("API key not configured for LLM provider '%s'".formatted(
                        llmProvider.getValue())))
                .apiKey();
    }
}

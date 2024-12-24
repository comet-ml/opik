package com.comet.opik.domain.llmproviders;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import dev.ai4j.openai4j.OpenAiClient;
import dev.langchain4j.internal.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Optional;

@Singleton
public class LlmProviderFactory {
    private final LlmProviderClientConfig llmProviderClientConfig;
    private final LlmProviderApiKeyService llmProviderApiKeyService;
    private final RetryUtils.RetryPolicy retryPolicy;

    @Inject
    public LlmProviderFactory(
            @NonNull @Config LlmProviderClientConfig llmProviderClientConfig,
            @NonNull LlmProviderApiKeyService llmProviderApiKeyService) {
        this.llmProviderApiKeyService = llmProviderApiKeyService;
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.retryPolicy = newRetryPolicy();
    }

    public LlmProviderService getService(@NonNull String workspaceId, @NonNull String model) {
        var llmProvider = getLlmProvider(model);
        if (llmProvider == LlmProvider.OPEN_AI) {
            var encryptedApiKey = getEncryptedApiKey(workspaceId, llmProvider);
            return new OpenAi(newOpenAiClient(encryptedApiKey), retryPolicy);
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

    /**
     * Initially, only OPEN AI is supported, so no need for a more sophisticated client resolution to start with.
     * At the moment, openai4j client and also langchain4j wrappers, don't support dynamic API keys. That can imply
     * an important performance penalty for next phases. The following options should be evaluated:
     * - Cache clients, but can be unsafe.
     * - Find and evaluate other clients.
     * - Implement our own client.
     * TODO as part of : <a href="https://comet-ml.atlassian.net/browse/OPIK-522">OPIK-522</a>
     */
    private OpenAiClient newOpenAiClient(String encryptedApiKey) {
        var openAiClientBuilder = OpenAiClient.builder();
        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(LlmProviderClientConfig.OpenAiClientConfig::url)
                .ifPresent(baseUrl -> {
                    if (StringUtils.isNotBlank(baseUrl)) {
                        openAiClientBuilder.baseUrl(baseUrl);
                    }
                });
        Optional.ofNullable(llmProviderClientConfig.getCallTimeout())
                .ifPresent(callTimeout -> openAiClientBuilder.callTimeout(callTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> openAiClientBuilder.connectTimeout(connectTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getReadTimeout())
                .ifPresent(readTimeout -> openAiClientBuilder.readTimeout(readTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getWriteTimeout())
                .ifPresent(writeTimeout -> openAiClientBuilder.writeTimeout(writeTimeout.toJavaDuration()));
        return openAiClientBuilder
                .openAiApiKey(EncryptionUtils.decrypt(encryptedApiKey))
                .build();
    }

    private RetryUtils.RetryPolicy newRetryPolicy() {
        var retryPolicyBuilder = RetryUtils.retryPolicyBuilder();
        Optional.ofNullable(llmProviderClientConfig.getMaxAttempts()).ifPresent(retryPolicyBuilder::maxAttempts);
        Optional.ofNullable(llmProviderClientConfig.getJitterScale()).ifPresent(retryPolicyBuilder::jitterScale);
        Optional.ofNullable(llmProviderClientConfig.getBackoffExp()).ifPresent(retryPolicyBuilder::backoffExp);
        return retryPolicyBuilder
                .delayMillis(llmProviderClientConfig.getDelayMillis())
                .build();
    }
}

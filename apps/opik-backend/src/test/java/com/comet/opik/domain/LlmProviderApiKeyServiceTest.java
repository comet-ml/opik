package com.comet.opik.domain;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.EncryptedAuthConfig;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.ProviderAuthConfig;
import com.comet.opik.infrastructure.llm.customllm.AuthTokenProvider;
import com.comet.opik.infrastructure.net.DestinationGuard;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("LLM provider api key service: strict destination guard")
class LlmProviderApiKeyServiceTest {

    private final TransactionTemplate template = mock(TransactionTemplate.class);
    private final LlmProviderApiKeyService service;

    {
        var config = TestConfigUtils.loadConfigTest();
        config.getLlmProviderClient().setDestinationGuard(DestinationGuard.Mode.STRICT);
        config.getLlmProviderTokenAuth().setDestinationGuard(DestinationGuard.Mode.STRICT);
        service = new LlmProviderApiKeyServiceImpl(mock(IdGenerator.class), template, config,
                mock(AuthTokenProvider.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080/v1", "http://10.0.0.1/v1", "http://169.254.1.1/v1"})
    @DisplayName("create and update refuse a non-public base_url before touching the database")
    void rejectsNonPublicBaseUrl(String baseUrl) {
        var create = ProviderApiKey.builder().provider(LlmProvider.CUSTOM_LLM).baseUrl(baseUrl).build();
        var update = ProviderApiKeyUpdate.builder().baseUrl(baseUrl).build();

        assertThatThrownBy(() -> service.saveApiKey(create, "user", "workspace"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateApiKey(UUID.randomUUID(), update, "user", "workspace"))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("create and update refuse a non-public auth_config token_url before touching the database")
    void rejectsNonPublicTokenUrl() {
        var authConfig = ProviderAuthConfig.builder()
                .tokenUrl("https://127.0.0.1/oauth/token")
                .credentials(List.of(ProviderAuthConfig.Credential.builder()
                        .key("client_secret").value("secret").secret(true).build()))
                .build();
        var create = ProviderApiKey.builder()
                .provider(LlmProvider.CUSTOM_LLM)
                .authConfig(EncryptedAuthConfig.of(authConfig))
                .build();
        var update = ProviderApiKeyUpdate.builder().authConfig(authConfig).build();

        assertThatThrownBy(() -> service.saveApiKey(create, "user", "workspace"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateApiKey(UUID.randomUUID(), update, "user", "workspace"))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(template);
    }
}

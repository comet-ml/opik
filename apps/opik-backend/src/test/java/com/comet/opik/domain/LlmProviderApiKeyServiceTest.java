package com.comet.opik.domain;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.EncryptedAuthConfig;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.ProviderAuthConfig;
import com.comet.opik.infrastructure.llm.customllm.AuthTokenProvider;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import uk.co.jemos.podam.api.PodamFactory;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LLM provider api key service: strict destination guard")
class LlmProviderApiKeyServiceTest {

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final TransactionTemplate template = mock(TransactionTemplate.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final LlmProviderApiKeyService service;

    {
        var config = TestConfigUtils.loadConfigTest();
        config.getLlmProviderClient().setDestinationGuard(DestinationGuard.Mode.STRICT);
        config.getLlmProviderTokenAuth().setDestinationGuard(DestinationGuard.Mode.STRICT);
        service = new LlmProviderApiKeyServiceImpl(idGenerator, template, config,
                mock(AuthTokenProvider.class));
    }

    private ProviderApiKey customProvider() {
        return factory.manufacturePojo(ProviderApiKey.class).toBuilder()
                .provider(LlmProvider.CUSTOM_LLM)
                .build();
    }

    private ProviderAuthConfig authConfig(String tokenUrl) {
        return factory.manufacturePojo(ProviderAuthConfig.class).toBuilder().tokenUrl(tokenUrl).build();
    }

    private void givenStoredProvider(Optional<ProviderApiKey> stored) {
        var dao = mock(LlmProviderApiKeyDAO.class);
        when(dao.fetch(any(), any())).thenReturn(stored);
        var handle = mock(Handle.class);
        when(handle.attach(LlmProviderApiKeyDAO.class)).thenReturn(dao);
        when(template.inTransaction(eq(READ_ONLY), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080/v1", "http://10.0.0.1/v1", "http://169.254.1.1/v1"})
    @DisplayName("create and update refuse a non-public base_url before writing")
    void rejectsNonPublicBaseUrl(String baseUrl) {
        givenStoredProvider(Optional.of(customProvider()));
        var create = customProvider().toBuilder().baseUrl(baseUrl).build();
        var update = factory.manufacturePojo(ProviderApiKeyUpdate.class).toBuilder().baseUrl(baseUrl).build();

        assertThatThrownBy(() -> service.saveApiKey(create, "user", "workspace"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(URI.create(baseUrl).getHost());
        assertThatThrownBy(() -> service.updateApiKey(UUID.randomUUID(), update, "user", "workspace"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(URI.create(baseUrl).getHost());
        verify(template, never()).inTransaction(eq(WRITE), any());
    }

    @Test
    @DisplayName("create and update refuse a non-public auth_config token_url before writing")
    void rejectsNonPublicTokenUrl() {
        givenStoredProvider(Optional.of(customProvider()));
        var authConfig = authConfig("https://127.0.0.1/oauth/token");
        var create = customProvider().toBuilder()
                .apiKey(null)
                .baseUrl(null)
                .authConfig(EncryptedAuthConfig.of(authConfig))
                .build();
        var update = factory.manufacturePojo(ProviderApiKeyUpdate.class).toBuilder()
                .baseUrl(null)
                .authConfig(authConfig)
                .build();

        assertThatThrownBy(() -> service.saveApiKey(create, "user", "workspace"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("127.0.0.1");
        assertThatThrownBy(() -> service.updateApiKey(UUID.randomUUID(), update, "user", "workspace"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("127.0.0.1");
        verify(template, never()).inTransaction(eq(WRITE), any());
    }

    @Test
    @DisplayName("a {model} in the host is accepted on save: only the substituted request URL can be judged")
    void acceptsHostLevelModelPlaceholder() {
        givenStoredProvider(Optional.of(customProvider()));
        when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
        var baseUrl = "https://{model}.example.com/v1";
        var create = customProvider().toBuilder().baseUrl(baseUrl).build();
        var update = factory.manufacturePojo(ProviderApiKeyUpdate.class).toBuilder().baseUrl(baseUrl).build();

        assertThatNoException().isThrownBy(() -> service.saveApiKey(create, "user", "workspace"));
        assertThatNoException().isThrownBy(() -> service.updateApiKey(UUID.randomUUID(), update, "user", "workspace"));
        verify(template, times(2)).inTransaction(eq(WRITE), any());
    }

    @Test
    @DisplayName("a {model} in the path doesn't exempt the host from the check")
    void rejectsPathLevelModelPlaceholderOnNonPublicHost() {
        givenStoredProvider(Optional.of(customProvider()));
        var baseUrl = "http://10.0.0.1/{model}/v1";
        var update = factory.manufacturePojo(ProviderApiKeyUpdate.class).toBuilder().baseUrl(baseUrl).build();

        assertThatThrownBy(() -> service.updateApiKey(UUID.randomUUID(), update, "user", "workspace"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10.0.0.1");
        verify(template, never()).inTransaction(eq(WRITE), any());
    }

    @Test
    @DisplayName("a {model} in the host is checked as saved for providers whose client doesn't substitute it")
    void rejectsHostLevelModelPlaceholderForNonSubstitutingProvider() {
        // .invalid never resolves (RFC 2606), so the stand-in host can't pass the check
        var baseUrl = "https://{model}.invalid/v1";
        var openAi = customProvider().toBuilder().provider(LlmProvider.OPEN_AI).build();
        givenStoredProvider(Optional.of(openAi));
        var create = openAi.toBuilder().baseUrl(baseUrl).build();
        var update = factory.manufacturePojo(ProviderApiKeyUpdate.class).toBuilder().baseUrl(baseUrl).build();

        assertThatThrownBy(() -> service.saveApiKey(create, "user", "workspace"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("model.invalid");
        assertThatThrownBy(() -> service.updateApiKey(UUID.randomUUID(), update, "user", "workspace"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("model.invalid");
        verify(template, never()).inTransaction(eq(WRITE), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://{model}@10.0.0.1/v1", "http://user:{model}@10.0.0.1/v1"})
    @DisplayName("a {model} in userinfo doesn't exempt the host from the check")
    void rejectsUserinfoModelPlaceholderOnNonPublicHost(String baseUrl) {
        givenStoredProvider(Optional.of(customProvider()));
        var update = factory.manufacturePojo(ProviderApiKeyUpdate.class).toBuilder().baseUrl(baseUrl).build();

        assertThatThrownBy(() -> service.updateApiKey(UUID.randomUUID(), update, "user", "workspace"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10.0.0.1");
        verify(template, never()).inTransaction(eq(WRITE), any());
    }

    @Test
    @DisplayName("update of a provider missing from the workspace is 404, before any destination check")
    void updateOfMissingProviderIsNotFound() {
        givenStoredProvider(Optional.empty());
        var update = factory.manufacturePojo(ProviderApiKeyUpdate.class).toBuilder()
                .baseUrl("http://10.0.0.1/v1")
                .build();

        assertThatThrownBy(() -> service.updateApiKey(UUID.randomUUID(), update, "user", "workspace"))
                .isInstanceOf(NotFoundException.class);
        verify(template, never()).inTransaction(eq(WRITE), any());
    }
}

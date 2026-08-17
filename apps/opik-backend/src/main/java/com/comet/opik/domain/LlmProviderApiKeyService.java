package com.comet.opik.domain;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.ProviderAuthConfig;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.FreeModelConfig.FREE_MODEL_PROVIDER_ID;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(LlmProviderApiKeyServiceImpl.class)
public interface LlmProviderApiKeyService {

    ProviderApiKey find(UUID id, String workspaceId);

    ProviderApiKey.ProviderApiKeyPage find(String workspaceId);

    ProviderApiKey saveApiKey(ProviderApiKey providerApiKey, String userName, String workspaceId);

    void updateApiKey(UUID id, ProviderApiKeyUpdate providerApiKeyUpdate, String userName, String workspaceId);

    void delete(Set<UUID> ids, String workspaceId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LlmProviderApiKeyServiceImpl implements LlmProviderApiKeyService {

    private static final String PROVIDER_API_KEY_ALREADY_EXISTS = "Api key for this provider already exists";
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull OpikConfiguration configuration;

    @Override
    public ProviderApiKey find(@NonNull UUID id, @NonNull String workspaceId) {

        return template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(LlmProviderApiKeyDAO.class);

            return repository.fetch(id, workspaceId).orElseThrow(this::createNotFoundError);
        });
    }

    @Override
    public ProviderApiKey.ProviderApiKeyPage find(@NonNull String workspaceId) {
        List<ProviderApiKey> providerApiKeys = template.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(LlmProviderApiKeyDAO.class);
            return repository.find(workspaceId);
        });

        var content = new ArrayList<>(providerApiKeys);

        // Inject virtual free model provider if enabled
        var freeModelConfig = configuration.getFreeModel();
        if (freeModelConfig.isEnabled()) {
            // Model label shows actual model name (e.g., "gpt-4o-mini")
            // Frontend will append "(free)" suffix
            var virtualProvider = ProviderApiKey.builder()
                    .id(FREE_MODEL_PROVIDER_ID)
                    .provider(LlmProvider.OPIK_FREE)
                    .configuration(Map.of(
                            "models", freeModelConfig.getModel(),
                            "model_label", freeModelConfig.getActualModel()))
                    .readOnly(true)
                    .build();
            // Add at the end so user-configured providers are auto-selected by default in the Playground
            // (frontend selects the first provider/model when user hasn't made a selection yet)
            content.add(virtualProvider);
        }

        return new ProviderApiKey.ProviderApiKeyPage(
                0, content.size(), content.size(),
                content, List.of());
    }

    @Override
    public ProviderApiKey saveApiKey(@NonNull ProviderApiKey providerApiKey, @NonNull String userName,
            @NonNull String workspaceId) {
        UUID apiKeyId = idGenerator.generateId();

        // For non-custom providers, explicitly set providerName to null (ignore user input)
        // Providers that support naming keep their provider_name to allow multiple instances
        String providerName = providerApiKey.provider().supportsProviderName()
                ? providerApiKey.providerName()
                : null;

        var newProviderApiKey = providerApiKey.toBuilder()
                .id(apiKeyId)
                .providerName(providerName)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        try {
            template.inTransaction(WRITE, handle -> {

                var repository = handle.attach(LlmProviderApiKeyDAO.class);

                repository.save(workspaceId, newProviderApiKey);

                return newProviderApiKey;
            });

            return find(apiKeyId, workspaceId);
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                log.warn("Attempted to create duplicate provider: workspace='{}', provider='{}', providerName='{}'",
                        workspaceId, newProviderApiKey.provider(), newProviderApiKey.providerName());
                throw newConflict();
            } else {
                throw e;
            }
        }
    }

    @Override
    public void updateApiKey(@NonNull UUID id, @NonNull ProviderApiKeyUpdate providerApiKeyUpdate,
            @NonNull String userName,
            @NonNull String workspaceId) {

        template.inTransaction(WRITE, handle -> {

            var repository = handle.attach(LlmProviderApiKeyDAO.class);

            ProviderApiKey providerApiKey = repository.fetch(id, workspaceId)
                    .orElseThrow(this::createNotFoundError);

            var authConfigUpdate = resolveAuthConfigUpdate(providerApiKeyUpdate, providerApiKey);

            repository.update(providerApiKey.id(),
                    workspaceId,
                    userName,
                    providerApiKeyUpdate,
                    authConfigUpdate.clear(),
                    authConfigUpdate.authConfig());

            return null;
        });
    }

    private record AuthConfigUpdate(boolean clear, ProviderAuthConfig authConfig) {
    }

    /**
     * Maps the update's auth_config to the DAO binds: absent -> keep (null value, no clear),
     * empty object -> clear, otherwise validate + resolve {@code __SECRET__} sentinels against
     * the stored recipe -> set.
     */
    private AuthConfigUpdate resolveAuthConfigUpdate(ProviderApiKeyUpdate update, ProviderApiKey stored) {
        ProviderAuthConfig incoming = update.authConfig();
        if (incoming == null) {
            validateNoStaticKeyConflict(
                    update.apiKey() != null ? update.apiKey() : stored.apiKey(), stored.authConfig());
            return new AuthConfigUpdate(false, null);
        }
        if (incoming.isEmpty()) {
            return new AuthConfigUpdate(true, null);
        }
        if (!stored.provider().supportsProviderName()) {
            throw new BadRequestException("auth_config is only supported for providers with provider_name");
        }
        var errors = incoming.validationErrors();
        if (!errors.isEmpty()) {
            throw new BadRequestException(String.join("; ", errors));
        }
        validateNoStaticKeyConflict(update.apiKey() != null ? update.apiKey() : stored.apiKey(), incoming);
        var merged = mergeSecretSentinels(incoming, stored.authConfig());
        return new AuthConfigUpdate(false, merged);
    }

    private void validateNoStaticKeyConflict(String encryptedApiKey, ProviderAuthConfig authConfig) {
        if (authConfig != null && encryptedApiKey != null
                && StringUtils.isNotBlank(EncryptionUtils.decrypt(encryptedApiKey))) {
            throw new BadRequestException(
                    "api_key and auth_config cannot both be set; clear one of them (send auth_config as an empty object to clear it)");
        }
    }

    /**
     * Resolves {@code __SECRET__} sentinel values ("keep the stored secret") and enforces that a
     * secret flag can never be unset once saved. Sentinels are only accepted where the stored
     * recipe holds a secret under the same key — so a literal password that happens to look masked
     * can never be confused with "unchanged".
     */
    private ProviderAuthConfig mergeSecretSentinels(ProviderAuthConfig incoming, ProviderAuthConfig stored) {
        Map<String, ProviderAuthConfig.Credential> storedByKey = stored == null || stored.credentials() == null
                ? Map.of()
                : stored.credentials().stream()
                        .collect(Collectors.toMap(
                                ProviderAuthConfig.Credential::key, Function.identity(), (first, second) -> second));

        var mergedCredentials = incoming.credentials().stream()
                .map(credential -> {
                    var storedCredential = storedByKey.get(credential.key());
                    boolean lockedInStore = storedCredential != null && storedCredential.secret();
                    if (ProviderAuthConfig.SECRET_SENTINEL.equals(credential.value())) {
                        if (!lockedInStore) {
                            throw new BadRequestException(
                                    "credential '%s' uses the secret sentinel but no stored secret exists under that key; provide the value"
                                            .formatted(credential.key()));
                        }
                        return credential.toBuilder().value(storedCredential.value()).secret(true).build();
                    }
                    // once saved as secret, a credential stays secret
                    return lockedInStore && !credential.secret()
                            ? credential.toBuilder().secret(true).build()
                            : credential;
                })
                .toList();

        return incoming.toBuilder().credentials(mergedCredentials).build();
    }

    @Override
    public void delete(@NonNull Set<UUID> ids, @NonNull String workspaceId) {
        if (ids.isEmpty()) {
            log.info("ids list is empty, returning");
            return;
        }

        template.inTransaction(WRITE, handle -> {
            handle.attach(LlmProviderApiKeyDAO.class).delete(ids, workspaceId);
            return null;
        });
    }

    private EntityAlreadyExistsException newConflict() {
        log.info(PROVIDER_API_KEY_ALREADY_EXISTS);
        return new EntityAlreadyExistsException(new ErrorMessage(List.of(PROVIDER_API_KEY_ALREADY_EXISTS)));
    }

    private NotFoundException createNotFoundError() {
        String message = "Provider api key not found";
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }

}

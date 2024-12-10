package com.comet.opik.domain;

import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.EncryptionService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(ProxyServiceImpl.class)
public interface ProxyService {

    ProviderApiKey get(UUID id);
    ProviderApiKey saveApiKey(ProviderApiKey providerApiKey);
    void updateApiKey(UUID id, ProviderApiKeyUpdate providerApiKeyUpdate);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProxyServiceImpl implements ProxyService {

    private static final String PROVIDER_API_KEY_ALREADY_EXISTS = "Api key for this provider already exists";
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull EncryptionService encryptionService;

    @Override
    public ProviderApiKey get(UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting provider api key with id '{}', workspaceId '{}'", id, workspaceId);

        var providerApiKey = template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(ProviderApiKeyDAO.class);

            return repository.fetch(id, workspaceId).orElseThrow(this::createNotFoundError);
        });
        log.info("Got provider api key with id '{}', workspaceId '{}'", id, workspaceId);

        return providerApiKey.toBuilder()
                .apiKey(encryptionService.decrypt(providerApiKey.apiKey()))
                .build();
    }

    @Override
    public ProviderApiKey saveApiKey(@NonNull ProviderApiKey providerApiKey) {
        UUID apiKeyId = idGenerator.generateId();
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        var newProviderApiKey = providerApiKey.toBuilder()
                .id(apiKeyId)
                .apiKey(encryptionService.encrypt(providerApiKey.apiKey()))
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        try {
            template.inTransaction(WRITE, handle -> {

                var repository = handle.attach(ProviderApiKeyDAO.class);
                repository.save(workspaceId, newProviderApiKey);

                return newProviderApiKey;
            });

            return get(apiKeyId);
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw newConflict();
            } else {
                throw e;
            }
        }
    }

    @Override
    public void updateApiKey(@NonNull UUID id, @NonNull ProviderApiKeyUpdate providerApiKeyUpdate) {
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();
        String encryptedApiKey = encryptionService.encrypt(providerApiKeyUpdate.getApiKey());

        template.inTransaction(WRITE, handle -> {

            var repository = handle.attach(ProviderApiKeyDAO.class);

            ProviderApiKey providerApiKey = repository.fetch(id, workspaceId)
                    .orElseThrow(this::createNotFoundError);

            repository.update(providerApiKey.id(),
                    workspaceId,
                    encryptedApiKey,
                    userName);

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

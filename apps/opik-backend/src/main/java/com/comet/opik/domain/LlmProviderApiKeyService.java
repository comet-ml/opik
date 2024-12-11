package com.comet.opik.domain;

import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
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

@ImplementedBy(LlmProviderApiKeyServiceImpl.class)
public interface LlmProviderApiKeyService {

    ProviderApiKey get(UUID id, String workspaceId);
    ProviderApiKey saveApiKey(ProviderApiKey providerApiKey, String userName, String workspaceId);
    void updateApiKey(UUID id, ProviderApiKeyUpdate providerApiKeyUpdate, String userName, String workspaceId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LlmProviderApiKeyServiceImpl implements LlmProviderApiKeyService {

    private static final String PROVIDER_API_KEY_ALREADY_EXISTS = "Api key for this provider already exists";
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;

    @Override
    public ProviderApiKey get(UUID id, String workspaceId) {
        log.info("Getting provider api key with id '{}', workspaceId '{}'", id, workspaceId);

        ProviderApiKey providerApiKey = template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(LlmProviderApiKeyDAO.class);

            return repository.fetch(id, workspaceId).orElseThrow(this::createNotFoundError);
        });
        log.info("Got provider api key with id '{}', workspaceId '{}'", id, workspaceId);

        return providerApiKey.toBuilder()
                .build();
    }

    @Override
    public ProviderApiKey saveApiKey(@NonNull ProviderApiKey providerApiKey, String userName, String workspaceId) {
        UUID apiKeyId = idGenerator.generateId();

        var newProviderApiKey = providerApiKey.toBuilder()
                .id(apiKeyId)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        try {
            template.inTransaction(WRITE, handle -> {

                var repository = handle.attach(LlmProviderApiKeyDAO.class);
                repository.save(workspaceId, newProviderApiKey);

                return newProviderApiKey;
            });

            return get(apiKeyId, workspaceId);
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw newConflict();
            } else {
                throw e;
            }
        }
    }

    @Override
    public void updateApiKey(@NonNull UUID id, @NonNull ProviderApiKeyUpdate providerApiKeyUpdate, String userName,
            String workspaceId) {

        template.inTransaction(WRITE, handle -> {

            var repository = handle.attach(LlmProviderApiKeyDAO.class);

            ProviderApiKey providerApiKey = repository.fetch(id, workspaceId)
                    .orElseThrow(this::createNotFoundError);

            repository.update(providerApiKey.id(),
                    workspaceId,
                    providerApiKeyUpdate.getApiKey(),
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

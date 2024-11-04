package com.comet.opik.domain;

import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(PromptServiceImpl.class)
public interface PromptService {
    Prompt create(Prompt prompt);

}

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
class PromptServiceImpl implements PromptService {

    private static final String ALREADY_EXISTS = "Prompt id or name already exists";
    private static final String VERSION_ALREADY_EXISTS = "Prompt version already exists";
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;

    @Override
    public Prompt create(Prompt prompt) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var newPrompt = prompt.toBuilder()
                .id(prompt.id() == null ? idGenerator.generateId() : prompt.id())
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        IdGenerator.validateVersion(prompt.id(), "prompt");

        var createdPrompt = EntityConstraintHandler
                .handle(() -> savePrompt(workspaceId, newPrompt))
                .withError(this::newPromptConflict);

        log.info("Prompt created with id '{}' name '{}', on workspace_id '{}'", createdPrompt.id(),
                createdPrompt.name(),
                workspaceId);

        if (!StringUtils.isEmpty(prompt.template())) {
            EntityConstraintHandler
                    .handle(() -> createPromptVersionFromPromptRequest(prompt, createdPrompt, workspaceId))
                    .withRetry(3, this::newVersionConflict);
        }

        return createdPrompt;
    }

    private PromptVersion createPromptVersionFromPromptRequest(Prompt prompt, Prompt createdPrompt,
            String workspaceId) {
        log.info("Creating prompt version for prompt id '{}'", createdPrompt.id());

        var createdVersion = transactionTemplate.inTransaction(WRITE, handle -> {
            PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

            UUID versionId = idGenerator.generateId();
            PromptVersion promptVersion = PromptVersion.builder()
                    .id(versionId)
                    .promptId(createdPrompt.id())
                    .commit(CommitUtils.getCommit(versionId))
                    .template(prompt.template())
                    .createdBy(createdPrompt.createdBy())
                    .build();

            promptVersionDAO.save(workspaceId, promptVersion);

            return promptVersionDAO.findById(versionId, workspaceId);
        });

        log.info("Created Prompt version for prompt id '{}'", createdPrompt.id());

        return createdVersion;
    }

    private Prompt savePrompt(String workspaceId, Prompt newPrompt) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            promptDAO.save(workspaceId, newPrompt);

            return promptDAO.findById(newPrompt.id(), workspaceId);
        });
    }

    private EntityAlreadyExistsException newConflict(String alreadyExists) {
        log.info(alreadyExists);
        return new EntityAlreadyExistsException(new ErrorMessage(alreadyExists));
    }

    private EntityAlreadyExistsException newVersionConflict() {
        return newConflict(VERSION_ALREADY_EXISTS);
    }

    private EntityAlreadyExistsException newPromptConflict() {
        return newConflict(ALREADY_EXISTS);
    }
}

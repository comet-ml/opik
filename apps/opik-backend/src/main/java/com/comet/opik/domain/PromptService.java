package com.comet.opik.domain;

import com.comet.opik.api.CreatePromptVersion;
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

import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.Prompt.PromptPage;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(PromptServiceImpl.class)
public interface PromptService {
    Prompt prompt(Prompt prompt);

    PromptPage find(String name, int page, int size);

    PromptVersion createPromptVersion(CreatePromptVersion promptVersion);
}

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
class PromptServiceImpl implements PromptService {

    public static final String ALREADY_EXISTS = "Prompt id or name already exists";
    public static final String VERSION_ALREADY_EXISTS = "Prompt version already exists";
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;

    @Override
    public Prompt prompt(@NonNull Prompt prompt) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var newPrompt = prompt.toBuilder()
                .id(prompt.id() == null ? idGenerator.generateId() : prompt.id())
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        Prompt createdPrompt = EntityConstraintHandler
                .handle(() -> savePrompt(workspaceId, newPrompt))
                .withError(this::newConflict);

        log.info("Prompt created with id '{}' name '{}', on workspace_id '{}'", createdPrompt.id(),
                createdPrompt.name(),
                workspaceId);

        if (!StringUtils.isEmpty(prompt.template())) {
            EntityConstraintHandler
                    .handle(() -> createPromptVersionFromPromptRequest(createdPrompt, workspaceId, prompt.template()))
                    .withRetry(3, this::newConflict);
        }

        return createdPrompt;
    }

    private PromptVersion createPromptVersionFromPromptRequest(Prompt createdPrompt, String workspaceId, String template) {
        log.info("Creating prompt version for prompt id '{}'", createdPrompt.id());

        var createdVersion = transactionTemplate.inTransaction(WRITE, handle -> {
            PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

            UUID versionId = idGenerator.generateId();
            PromptVersion promptVersion = PromptVersion.builder()
                    .id(versionId)
                    .promptId(createdPrompt.id())
                    .commit(CommitGenerator.generateCommit(versionId))
                    .template(template)
                    .createdBy(createdPrompt.createdBy())
                    .build();

            promptVersionDAO.save(workspaceId, promptVersion);

            return promptVersionDAO.findById(versionId, workspaceId);
        });

        log.info("Created Prompt version for prompt id '{}'", createdPrompt.id());

        return createdVersion;
    }

    private Prompt savePrompt(String workspaceId, Prompt prompt) {

        IdGenerator.validateVersion(prompt.id(), "prompt");

        return transactionTemplate.inTransaction(WRITE, handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            promptDAO.save(workspaceId, prompt);

            return promptDAO.findById(prompt.id(), workspaceId);
        });
    }

    private EntityAlreadyExistsException newConflict() {
        log.info(ALREADY_EXISTS);
        return new EntityAlreadyExistsException(new ErrorMessage(ALREADY_EXISTS));
    }

    private EntityAlreadyExistsException newVersionConflict() {
        log.info(VERSION_ALREADY_EXISTS);
        return new EntityAlreadyExistsException(new ErrorMessage(VERSION_ALREADY_EXISTS));
    }

    @Override
    public PromptPage find(String name, int page, int size) {

        String workspaceId = requestContext.get().getWorkspaceId();

        return transactionTemplate.inTransaction(handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            long total = promptDAO.count(name, workspaceId);

            var offset = (page - 1) * size;

            List<Prompt> content = promptDAO.find(name, workspaceId, offset, size);

            return PromptPage.builder()
                    .page(page)
                    .size(content.size())
                    .content(content)
                    .total(total)
                    .build();
        });
    }

    private Prompt getOrCreatePrompt(String workspaceId, String name, String userName) {

        Prompt prompt = findByName(workspaceId, name);

        if (prompt != null) {
            return prompt;
        }

        var newPrompt = Prompt.builder()
                .id(idGenerator.generateId())
                .name(name)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        return EntityConstraintHandler
                .handle(() -> savePrompt(workspaceId, newPrompt))
                .onErrorDo(() -> findByName(workspaceId, name));
    }

    private Prompt findByName(String workspaceId, String name) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            PromptDAO promptDAO = handle.attach(PromptDAO.class);

            return promptDAO.findByName(name, workspaceId);
        });
    }

    @Override
    public PromptVersion createPromptVersion(@NonNull CreatePromptVersion createPromptVersion) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        Prompt prompt = getOrCreatePrompt(workspaceId, createPromptVersion.name(), userName);

        UUID id = createPromptVersion.version().id() == null ? idGenerator.generateId() : createPromptVersion.version().id();
        String commit = createPromptVersion.version().commit() == null ? CommitGenerator.generateCommit(id) : createPromptVersion.version().commit();

        EntityConstraintHandler<PromptVersion> handler = EntityConstraintHandler.handle(() -> {
            PromptVersion promptVersion = createPromptVersion.version().toBuilder()
                    .promptId(prompt.id())
                    .createdBy(userName)
                    .id(id)
                    .commit(commit)
                    .build();

            return savePromptVersion(workspaceId, promptVersion);
        });

        if (createPromptVersion.version().commit() != null) {
            return handler.withError(this::newVersionConflict);
        } else {
            return handler.onErrorDo(() -> retryableCreateVersion(workspaceId, createPromptVersion, prompt, userName));
        }
    }

    private PromptVersion retryableCreateVersion(String workspaceId, CreatePromptVersion request, Prompt prompt, String userName) {
        return EntityConstraintHandler.handle(() -> {
            UUID newId = idGenerator.generateId();

            PromptVersion promptVersion = request.version().toBuilder()
                    .promptId(prompt.id())
                    .createdBy(userName)
                    .id(newId)
                    .commit(CommitGenerator.generateCommit(newId))
                    .build();

            return savePromptVersion(workspaceId, promptVersion);

        }).withRetry(3, this::newVersionConflict);
    }

    private PromptVersion savePromptVersion(String workspaceId, PromptVersion promptVersion) {
        log.info("Creating prompt version for prompt id '{}'", promptVersion.promptId());

        var createdVersion = transactionTemplate.inTransaction(WRITE, handle -> {
            PromptVersionDAO promptVersionDAO = handle.attach(PromptVersionDAO.class);

            promptVersionDAO.save(workspaceId, promptVersion);

            return promptVersionDAO.findById(promptVersion.id(), workspaceId);
        });

        log.info("Created Prompt version for prompt id '{}'", promptVersion.promptId());

        return createdVersion;
    }

}
